package com.opencode.acp.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped service — the single public entry point for all review
 * comment operations. Coordinates [ReviewCommentRepository], [ReviewCommentParser],
 * [ReviewStateHolder], [ReviewCommentFileWatcher], [EditorLifecycleHook],
 * and [EditorHighlightSupport].
 *
 * ## State
 *
 * The current index lives in [stateHolder] (a single `MutableStateFlow<ReviewIndex>`).
 * `StateFlow.value` is the atomic source of truth, readable synchronously from
 * EDT ([ReviewCommentLineMarkerProvider]) and writable from background dispatchers.
 *
 * ## Dispatchers
 *
 * The manager scope uses `Dispatchers.Default` (matching the codebase convention
 * for project services). File I/O inside [ReviewCommentRepository] switches to
 * `Dispatchers.IO` via `withContext`.
 *
 * ## hasComments cache (M3)
 *
 * The line-marker short-circuit cache lives here as a project-scoped instance
 * field, NOT on the [ReviewCommentLineMarkerProvider] companion object. A
 * static cache keyed by relative path would be shared across open projects and
 * poison one project's result into another. Invalidated via [invalidateCommentsCache]
 * on every index update.
 */
@Service(Service.Level.PROJECT)
class ReviewCommentManager(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}

    private val repository = ReviewCommentRepository(project, onWrote = ::markSelfWrite)
    private val stateHolder = ReviewStateHolder()

    /** File watcher — extracted into its own class. Suppresses self-writes. */
    private val fileWatcher = ReviewCommentFileWatcher(
        project, repository,
        ::onExternalFileChange, ::isSelfWrite,
    )

    /** Editor lifecycle — implements EditorFactoryListener. */
    private val editorLifecycle = EditorLifecycleHook(project, this)

    /** Coroutine scope — `Dispatchers.Default` for orchestration; the
     *  repository uses `withContext(Dispatchers.IO)` for blocking I/O.
     *  Exposed as `internal` so action/dialog classes (e.g.
     *  [AddReviewCommentAction]) can launch suspend-API calls without
     *  creating their own scopes that outlive the EDT action lifecycle. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes overlapping [loadAll] calls so the startup-activity load and
     *  the tool-window-open load (and any [refreshReviewFiles]-triggered load)
     *  don't interleave their `clearForEditor` + `addHighlights` on the same
     *  editor. The second caller waits for the first to finish, then re-reads
     *  (idempotent). Without this, two concurrent loads race on the unsynchronized
     *  EditorHighlightSupport lists and can clear each other's just-applied
     *  highlights (visible flicker / missing highlights after project open). */
    private val loadAllMutex = Mutex()

    /** Read-only StateFlow for collectors (Review tab UI, editor highlight
     *  re-application). UI collectors must switch to EDT before touching editors. */
    val commentChanges: kotlinx.coroutines.flow.StateFlow<ReviewIndex> = stateHolder.state

    /** Synchronous current-value read — thread-safe. Used by
     *  [ReviewCommentLineMarkerProvider] on EDT (no dispatcher switch). */
    fun getIndex(): ReviewIndex = stateHolder.value

    /** Pre-built line→comments map for a file path (O(1) lookup per line).
     *  Delegates to [EditorLifecycleHook]. Used by [ReviewCommentLineMarkerProvider]
     *  to avoid O(N) filter scans per PsiElement. Returns an empty map if the
     *  file has no comments or no editor is open for it. */
    fun lineMapForFile(path: String): LineCommentMap = editorLifecycle.lineMapForFile(path)

    // ── Programmatic listener registration ──

    init {
        // EditorFactoryListener — registered programmatically with `this` as parent.
        // (The XML <projectListeners> extension point cannot inject this manager
        // into the constructor — that was a startup crash in an earlier draft.)
        EditorFactory.getInstance().addEditorFactoryListener(editorLifecycle, this)

        // AsyncFileListener for .review/ JSON file changes.
        VirtualFileManager.getInstance().addAsyncFileListener(fileWatcher.asyncListener, this)

        // BulkFileListener for source file moves/renames/deletes.
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, fileWatcher.bulkMoveListener)

        // Initial .tmp file cleanup (orphaned writes from a previous crash).
        scope.launch { repository.cleanupOrphanTempFiles() }
    }

    // ── Self-write suppression (C2) ──

    /** Paths recently written by THIS manager, with a short TTL. The file
     *  watcher skips VFS events for these paths so a plugin write doesn't
     *  trigger a re-read → re-emit → re-write cycle. */
    private val recentSelfWrites = ConcurrentHashMap.newKeySet<String>()

    /** C2: invoked by [ReviewCommentRepository.writeFile] BEFORE the rename,
     *  so the VFS event generated by the rename is already suppressed when
     *  the AsyncFileListener processes it. Previously this was called by the
     *  public API AFTER `updateFile()` returned, which raced the VFS
     *  pipeline and could trigger a redundant re-read. */
    private fun markSelfWrite(sourcePath: String) {
        recentSelfWrites.add(sourcePath)
        scope.launch {
            delay(SELF_WRITE_SUPPRESS_MS)
            recentSelfWrites.remove(sourcePath)
        }
    }

    /** Returns true if [sourcePath] was written by this manager recently
     *  (the file watcher should skip the resulting VFS event). */
    private fun isSelfWrite(sourcePath: String): Boolean = sourcePath in recentSelfWrites

    // ── hasComments cache (M3) ──

    /** Project-scoped "this file has open comments?" cache keyed by relative
     *  path. Avoids recomputing the path + consulting the LineCommentMap for
     *  files with no comments. Invalidated for changed paths on every
     *  [updateIndex]. */
    private val hasCommentsCache = ConcurrentHashMap<String, Boolean>()

    /** True / false / null(if absent) for the cached has-comments state. */
    internal fun hasCommentsCache(path: String): Boolean? = hasCommentsCache[path]

    /** Record the has-comments state for a path. */
    internal fun putCommentsCache(path: String, has: Boolean) {
        hasCommentsCache[path] = has
    }

    /** Invalidate the cache for specific paths (called from [updateIndex]). */
    private fun invalidateCommentsCache(changedPaths: Set<String>) {
        for (p in changedPaths) hasCommentsCache.remove(p)
    }

    /** Invalidate the entire cache (e.g., on full reload). */
    fun invalidateAllCaches() {
        hasCommentsCache.clear()
    }

    // ── Index updates ──

    /** Called by [ReviewCommentFileWatcher] when an EXTERNAL process writes
     *  a `.review/` file. Re-reads just that file and merges into the index.
     *
     *  Acquires [loadAllMutex] to prevent interleaving with [loadAll]:
     *  without this, a concurrent loadAll could overwrite the index with a
     *  stale snapshot that doesn't include this external change. */
    internal suspend fun onExternalFileChange(sourcePath: String) {
        if (isSelfWrite(sourcePath)) return  // feedback-loop guard
        loadAllMutex.withLock {
            val file = repository.readFile(sourcePath) ?: return
            val newIndex = stateHolder.value.withFile(sourcePath, file)
            updateIndex(newIndex, setOf(sourcePath))
        }
    }

    /** Atomically swap the index, invalidate the has-comments cache for the
     *  changed paths, refresh editor highlights for changed files, and force
     *  the daemon to re-run line markers so gutter icons update. Safe to call
     *  from any dispatcher. */
    private fun updateIndex(newIndex: ReviewIndex, changedPaths: Set<String> = emptySet()) {
        stateHolder.set(newIndex)
        invalidateCommentsCache(changedPaths)
        // Refresh editor highlights + gutter icons for every affected open file.
        for (path in changedPaths) {
            // Only pass OPEN comments — resolved/deleted comments don't get
            // highlights or gutter icons. The LineCommentMap and highlights
            // are built from this filtered list.
            val comments = newIndex.openForFile(path)
            editorLifecycle.onFileCommentsChanged(path, comments)
        }
        refreshGutterIcons(changedPaths)
    }

    /** C8: force the daemon code analyzer to re-run line markers for
     *  changed files and the currently-focused file — not every open file
     *  with comments. Previously this restarted the daemon for ALL open
     *  files with comments, which was wasteful when only one file changed.
     *
     *  API NOTE: IntelliJ 2026.1 has only `restart(PsiFile)` — there is NO
     *  `restart(PsiFile, String)` overload. Must be called on EDT (the daemon
     *  silently skips restart when called inside a write action). */
    private fun refreshGutterIcons(changedPaths: Set<String> = emptySet()) {
        val app = ApplicationManager.getApplication()
        val onEdt = app.isDispatchThread
        val runRestart = {
            // Restart daemon for changed paths that have comments.
            for (path in changedPaths) {
                val psi = psiFileForPath(path) ?: continue
                DaemonCodeAnalyzer.getInstance(project).restart(psi)
            }
            // Also restart for the currently-focused file in case it has no
            // tracked comments yet (e.g., a file the user just opened whose
            // comments are still being loaded).
            currentEditorPsiFile()?.let {
                DaemonCodeAnalyzer.getInstance(project).restart(it)
            }
        }
        if (onEdt) runRestart() else app.invokeLater { runRestart() }
    }

    /** Get the PsiFile for the editor that currently has focus, or null. */
    private fun currentEditorPsiFile(): PsiFile? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
    }

    /** Resolve a project-relative path to its open PsiFile, or null.
     *  Uses the EditorLifecycleHook's openEditorsByPath cache for O(1)
     *  lookup instead of iterating all open editors. Falls back to
     *  full iteration if the cache doesn't have the path (e.g., editor
     *  opened after the cache was last populated). */
    private fun psiFileForPath(path: String): PsiFile? {
        // Fast path: check the editor lifecycle hook's cache (O(1))
        val cachedEditor = editorLifecycle.findOpenEditorForPath(path)
        if (cachedEditor != null) {
            return PsiDocumentManager.getInstance(project).getPsiFile(cachedEditor.document)
        }
        // Slow path: iterate open editors (cache miss)
        val fem = FileEditorManager.getInstance(project)
        for (fe in fem.allEditors) {
            if (fe is TextEditor) {
                val feVf = fe.file ?: continue
                val rel = PathUtils.relativePath(project, feVf) ?: continue
                if (rel == path) {
                    return PsiDocumentManager.getInstance(project)
                        .getPsiFile(fe.editor.document)
                }
            }
        }
        return null
    }

    // ── Public API ──

    /** Load the full index from disk (called from [ReviewCommentStartupActivity]
     *  on project open and from [ChatToolWindowFactory] on tool window open).
     *  Safe to run on `Dispatchers.Default`.
     *
     *  The per-path [editorLifecycle.onFileCommentsChanged] loop (line 281)
     *  builds line maps and applies highlights for files that are already open.
     *  Editors opened AFTER loadAll get their highlights applied via
     *  [EditorLifecycleHook.editorCreated]. */
    suspend fun loadAll() = loadAllMutex.withLock {
        try {
            val files = repository.listAllFiles()
            var newIndex = ReviewIndex()
            for (path in files) {
                val sourcePath = PathUtils.resolveSourcePath(project, path) ?: continue
                val file = repository.readFile(sourcePath) ?: continue
                newIndex = newIndex.withFile(sourcePath, file)
            }
            // Don't prune locks here — let them accumulate (bounded by project lifetime).
            // Pruning while addComment is in flight for a new file can remove its Mutex,
            // breaking serialization. The lock count is bounded by the number of source
            // files ever commented on, which is <10k in practice (<0.5MB memory).
            // repository.pruneLocks(newIndex.commentsByFile.keys)
            // Build line maps and apply RangeHighlighter backgrounds for files
            // that are already open when loadAll runs. Otherwise comments loaded
            // after editor creation never get highlights until something else
            // triggers onFileCommentsChanged.
            stateHolder.set(newIndex)
            invalidateAllCaches()
            for ((sourcePath, comments) in newIndex.commentsByFile) {
                val open = comments.filter { it.status == ReviewStatus.OPEN }
                editorLifecycle.onFileCommentsChanged(sourcePath, open)
            }
            refreshGutterIcons(newIndex.commentsByFile.keys)
            logger.info {
                "[ACP] Loaded ${files.size} review file(s), ${newIndex.totalOpen} open comment(s)"
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Failed to load review index from disk" }
        }
    }

    /** Add a comment. Uses optimistic concurrency + per-path Mutex internally.
     *  Updates the in-memory index DIRECTLY (not via VFS reactivity) so the
     *  caller sees the new state on the next [getIndex] — no
     *  eventual-consistency surprise. The VFS event from the write is
     *  suppressed by [markSelfWrite] (called by the repository before rename). */
    suspend fun addComment(sourcePath: String, comment: ReviewComment): Boolean {
        // m3: pure validate + caller-side logging (no side effect in a predicate).
        if (!comment.validate()) {
            logger.warn {
                "[ACP] Invalid review comment skipped: id=${comment.id} " +
                    "startLine=${comment.startLine} endLine=${comment.endLine}"
            }
            return false
        }
        val written = repository.updateFile(sourcePath) { existing ->
            // M7: do NOT set etag here — updateFile stamps a fresh one. Setting
            // it here would be immediately overwritten and waste a UUID generation.
            val file = existing ?: ReviewFile()
            file.copy(comments = file.comments + comment)
        }
        // markSelfWrite is already invoked inside repository.writeFile (C2).
        if (written != null) {
            val newIndex = stateHolder.value.withFile(sourcePath, written)
            updateIndex(newIndex, setOf(sourcePath))
            logger.info { "[ACP] Added review comment ${comment.id} on $sourcePath:${comment.startLine}" }
            return true
        }
        return false
    }

    /** Delete a comment (soft-delete — sets status=DELETED). */
    suspend fun deleteComment(sourcePath: String, commentId: String) {
        val written = repository.updateFile(sourcePath) { existing ->
            if (existing == null) return@updateFile null
            // Guard: skip no-op write if commentId doesn't exist.
            if (existing.comments.none { it.id == commentId }) {
                logger.warn { "[ACP] deleteComment: commentId $commentId not found in $sourcePath" }
                return@updateFile null
            }
            existing.copy(
                comments = existing.comments.map {
                    if (it.id == commentId) it.copy(status = ReviewStatus.DELETED) else it
                }
            )
        }
        if (written != null) {
            val newIndex = stateHolder.value.withFile(sourcePath, written)
            updateIndex(newIndex, setOf(sourcePath))
            logger.info { "[ACP] Soft-deleted review comment $commentId" }
        }
    }

    /** Update a comment's status (open → resolved, etc.). */
    suspend fun updateCommentStatus(
        sourcePath: String,
        commentId: String,
        status: ReviewStatus,
        resolution: String?,
    ) {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val written = repository.updateFile(sourcePath) { existing ->
            if (existing == null) return@updateFile null
            // Guard: skip no-op write if commentId doesn't exist.
            if (existing.comments.none { it.id == commentId }) {
                logger.warn { "[ACP] updateCommentStatus: commentId $commentId not found in $sourcePath" }
                return@updateFile null
            }
            existing.copy(
                comments = existing.comments.map {
                    if (it.id == commentId) it.copy(
                        status = status,
                        resolution = resolution,
                        resolvedAt = if (status == ReviewStatus.RESOLVED) now else null,
                    ) else it
                }
            )
        }
        if (written != null) {
            val newIndex = stateHolder.value.withFile(sourcePath, written)
            updateIndex(newIndex, setOf(sourcePath))
            logger.info { "[ACP] Updated review comment $commentId → $status" }
        }
    }

    /** Resolve all open comments on a file. */
    suspend fun resolveAll(sourcePath: String, resolution: String) {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val written = repository.updateFile(sourcePath) { existing ->
            existing?.copy(
                comments = existing.comments.map {
                    if (it.status == ReviewStatus.OPEN)
                        it.copy(status = ReviewStatus.RESOLVED, resolution = resolution, resolvedAt = now)
                    else it
                }
            )
        }
        if (written != null) {
            val newIndex = stateHolder.value.withFile(sourcePath, written)
            updateIndex(newIndex, setOf(sourcePath))
            logger.info {
                "[ACP] Resolved all ${written.comments.count { it.status == ReviewStatus.RESOLVED }} " +
                    "comments on $sourcePath"
            }
        }
    }

    // ── Disposal ──

    override fun dispose() {
        scope.cancel()
        // C7: dispose the editor lifecycle hook (clears its maps; the
        // EditorFactoryListener itself is auto-removed because it was
        // registered with `this` as parent).
        Disposer.dispose(editorLifecycle)
        // Dispose the file watcher so its in-flight coroutines are cancelled
        // and its scope doesn't leak for the disposed project's lifetime.
        Disposer.dispose(fileWatcher)
        recentSelfWrites.clear()
        hasCommentsCache.clear()
        logger.info { "[ACP] ReviewCommentManager disposed" }
    }

    companion object {
        const val SELF_WRITE_SUPPRESS_MS = 2_000L

        fun getInstance(project: Project): ReviewCommentManager =
            project.service<ReviewCommentManager>()
    }
}