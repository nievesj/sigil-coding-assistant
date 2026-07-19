package com.opencode.acp.follow

import com.agentclientprotocol.model.ToolKind
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.projectView.ProjectView
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.chat.util.EDT
import com.opencode.acp.review.EditorHighlightSupport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages "Follow Agent" editor interaction.
 * Opens files, scrolls to line ranges, applies transient highlights,
 * and adds block inlay labels when the LLM reads/edits/searches files.
 *
 * Thread safety: SessionState.processEventInternal() runs on Dispatchers.Default.
 * This manager is called from that context. All editor API calls are dispatched
 * to EDT via ApplicationManager.invokeLater(). Throttle state uses @Volatile
 * for cross-thread visibility.
 */
@Service(Service.Level.PROJECT)
class EditorFollowManager(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val FOLLOW_FILE_COOLDOWN_MS = 2_000L
        const val HIGHLIGHT_DURATION_MS = 5_000L
        const val PROJECT_VIEW_COOLDOWN_MS = 5_000L

        private val LINE_HEADER_REGEX = Regex("""^Line (\d+):""")
        private val SINGLE_LINE_MATCH_REGEX = Regex("""^(.+):(\d+):.*$""")

        fun getInstance(project: Project): EditorFollowManager =
            project.service<EditorFollowManager>()
    }

    private val lastFollowFileMs = java.util.concurrent.atomic.AtomicLong(0)
    private val lastProjectViewSelectMs = java.util.concurrent.atomic.AtomicLong(0)
    private val pendingFollowRef = AtomicReference<PendingFollow?>(null)

    private data class PendingFollow(
        val filePath: String, val startLine: Int, val endLine: Int, val kind: ToolKind,
        val agentName: String? = null, val modelName: String? = null,
        val input: JsonObject? = null, val startTimeMs: Long? = null,
    )

    // Job handle for the in-flight throttle wait. Cancelled and replaced on each
    // new throttled call — guarantees at most ONE pending coroutine at a time,
    // so we don't pile up N coroutines that all race to read pendingFollowRef.
    private val pendingJobRef = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    private var pendingJob: kotlinx.coroutines.Job?
        get() = pendingJobRef.get()
        set(value) {
            // CAS first, then cancel the old value only after a successful CAS.
            // This avoids cancelling a job that another writer just set and wants
            // to keep (the cancel-before-CAS ordering was fragile under contention).
            while (true) {
                val existing = pendingJobRef.get()
                if (pendingJobRef.compareAndSet(existing, value)) {
                    existing?.cancel()
                    return
                }
            }
        }

    override fun dispose() {
        scope.cancel()
    }

    /** Check if Follow Agent is enabled for this project. */
    fun isFollowEnabled(): Boolean {
        return OpenCodeFollowSettingsState.getInstance().followAgentEnabled
    }

    /** Enable or disable Follow Agent. */
    fun setFollowEnabled(enabled: Boolean) {
        OpenCodeFollowSettingsState.getInstance().followAgentEnabled = enabled
    }

    /**
     * Main entry point: called by SessionState on ToolUse events.
     * Thread-safe: can be called from any thread.
     */
    fun followToolCall(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        kind: ToolKind,
        agentName: String? = null,
        modelName: String? = null,
        input: JsonObject? = null,
        startTimeMs: Long? = null,
    ) {
        if (!isFollowEnabled()) return
        val color = FollowColorProvider.getColor(kind) ?: return
        val label = FollowColorProvider.composeInlayLabel(kind, agentName, modelName, input, startTimeMs)

        if (!throttleCheck()) {
            pendingFollowRef.set(PendingFollow(filePath, startLine, endLine, kind, agentName, modelName, input, startTimeMs))
            logger.debug { "[ACP] Follow Agent: queued $filePath (throttled)" }
            schedulePendingFollow()
            return
        }

        navigateOnEdt(project, filePath, startLine, endLine, color, label, focus = false)
    }

    /**
     * Entry point for ToolResult events (search tools — line numbers in results).
     * Thread-safe: can be called from any thread.
     */
    fun followToolResult(
        project: Project,
        toolCallId: String,
        output: List<JsonObject>?,
        kind: ToolKind,
        agentName: String? = null,
        modelName: String? = null,
        input: JsonObject? = null,
    ) {
        if (!isFollowEnabled()) return
        if (kind != ToolKind.SEARCH) return
        val color = FollowColorProvider.getColor(kind) ?: return
        val label = FollowColorProvider.composeInlayLabel(
            kind, agentName, modelName, input, null
        )
        if (label.isBlank()) return

        // Parse first match from tool output text.
        // OpenCode grep format: two lines per match:
        //   <abs_path>:            (line 1, trailing colon)
        //     Line <N>: <text>     (line 2, "Line N:" prefix)
        val firstText = output?.firstOrNull()?.let { obj ->
            obj["text"]?.jsonPrimitive?.contentOrNull
        } ?: run {
            logger.debug { "[ACP] Follow Agent: grep output format mismatch (no text in output)" }
            return
        }

        // Collect candidate (filePath, line) pairs from the output text.
        // Supports two formats:
        //   Format A (two-line): "<abs_path>:\n  Line <N>: <text>"
        //   Format B (single-line): "<path>:<line>:<text>" or "<path>:<line>:<text>"
        val candidates = mutableListOf<Pair<String, Int>>()
        val allLines = firstText.lines()
        var i = 0
        while (i < allLines.size) {
            val pathLine = allLines[i].trimEnd()
            // Format B: "<path>:<line>:<text>" on a single line.
            // Use a greedy match for the path (up to the last colon before a number)
            // to handle Windows drive letters (C:\Users\foo\bar.kt:42:text).
            // The non-greedy `.+?` would stop at the drive-letter colon.
            // Checked FIRST so that a Format B line ending with ':' (e.g. a path
            // with no line number but a trailing colon) is not misparsed as the
            // start of a Format A two-line block.
            val singleMatch = SINGLE_LINE_MATCH_REGEX.find(pathLine)
            if (singleMatch != null) {
                val fp = singleMatch.groupValues[1]
                val ln = singleMatch.groupValues[2].toIntOrNull()
                if (ln != null) {
                    candidates.add(fp to ln)
                }
                i++
                continue
            }
            // Format A: path on its own line ending with ':', followed by a
            // "Line <N>:" header on the next line. Only consume the next line if
            // it matches the LINE_HEADER_REGEX.
            if (pathLine.endsWith(':') && i + 1 < allLines.size) {
                val lineLine = allLines[i + 1].trim()
                val lineMatch = LINE_HEADER_REGEX.find(lineLine)
                if (lineMatch != null) {
                    val fp = pathLine.dropLast(1)
                    val ln = lineMatch.groupValues[1].toIntOrNull()
                    if (ln != null) {
                        candidates.add(fp to ln)
                        i += 2
                        continue
                    }
                }
            }
            i++
        }

        if (candidates.isEmpty()) {
            logger.debug { "[ACP] Follow Agent: grep output format mismatch (no parseable matches)" }
            return
        }

        // Resolve candidates synchronously (resolveVirtualFile is cheap for direct
        // paths — only the FilenameIndex fallback does I/O, and only on miss) and
        // navigate to the first one that resolves inside the project. Previously
        // only the first candidate was tried; if it resolved outside the project
        // (e.g. an absolute path from a different project), later valid candidates
        // were silently ignored.
        scope.launch(Dispatchers.IO) {
            for ((filePath, line) in candidates) {
                val vf = resolveVirtualFile(project, filePath) ?: continue
                val navigated = withContext(Dispatchers.EDT) {
                    if (project.isDisposed) return@withContext false
                    if (!throttleCheck()) {
                        pendingFollowRef.set(PendingFollow(filePath, line, line, kind, agentName, modelName, input, null))
                        schedulePendingFollow()
                        return@withContext false
                    }
                    navigateToResolvedFile(project, vf, line, line, color, label, focus = false, filePathForLog = filePath)
                    true
                }
                if (navigated) return@launch
            }
            logger.debug { "[ACP] Follow Agent: no candidate resolved to a project file" }
        }
    }

    /**
     * Open file at a specific line with explicit focus control.
     * Used by ToolPill click-to-open (focus=true) vs auto-follow (focus=false).
     */
    fun openFileAtLine(project: Project, filePath: String, line: Int, focus: Boolean) {
        scope.launch(Dispatchers.IO) {
            val vf = resolveVirtualFile(project, filePath) ?: return@launch
            withContext(Dispatchers.EDT) {
                try {
                    if (line > 0) {
                        OpenFileDescriptor(project, vf, line - 1, 0).navigate(focus)
                    } else {
                        FileEditorManager.getInstance(project).openFile(vf, focus)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[ACP] Follow Agent: error opening $filePath" }
                }
            }
        }
    }

    /**
     * Resolve file path to VirtualFile.
     * Uses findFileByPath() first (VFS snapshot, no I/O).
     * Falls back to refreshAndFindFileByPath() for externally-created files.
     *
     * Marked `suspend` so callers can run it on Dispatchers.IO: the direct VFS
     * lookup is cheap, but the FilenameIndex fallback does filesystem I/O
     * (canonicalization) that must not block the EDT.
     */
    suspend fun resolveVirtualFile(project: Project, filePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val absPath = if (Path.of(filePath).isAbsolute) {
            filePath
        } else {
            Path.of(basePath).resolve(filePath).normalize().toString()
        }
        // CWE-22: reject paths that escape the project root.
        // Use canonical paths to handle symlinks and case-insensitive filesystems correctly.
        val canonicalBase = try { java.io.File(basePath).canonicalPath } catch (_: Exception) { return null }
        val canonicalAbs = try { java.io.File(absPath).canonicalPath } catch (_: Exception) { return null }
        val baseNormalized = canonicalBase.replace('\\', '/')
        val absNormalized = canonicalAbs.replace('\\', '/')
        if (!absNormalized.startsWith("$baseNormalized/")) {
            logger.warn { "[ACP] Follow Agent: path traversal blocked: $filePath resolves outside project" }
            return null
        }
        val vfs = LocalFileSystem.getInstance()
        // Use the canonical path for both the traversal check (above) AND the VFS
        // lookup. The canonical path is the authoritative traversal guard — using
        // a non-canonical fallback would weaken it (a symlink string form could
        // appear inside the project while canonicalizing outside). If the canonical
        // VFS lookup fails, fall back to refreshAndFindFileByPath on the canonical
        // path only.
        //
        // LocalFileSystem.findFileByPath contractually expects forward-slash-
        // separated paths on all platforms (the VFS is platform-independent).
        // On Windows, passing a backslash path would fail the direct lookup,
        // forcing the slower refreshAndFindFileByPath and then the FilenameIndex
        // fallback on every call — a silent performance regression.
        val canonicalFwdSlash = canonicalAbs.replace('\\', '/')
        val direct = vfs.findFileByPath(canonicalFwdSlash)
            ?: withContext(Dispatchers.EDT) {
                // refreshAndFindFileByPath internally acquires a write action.
                // On a background thread (Dispatchers.IO), the write action's
                // beforeWriteActionStart callback fires on that thread, where
                // DaemonListeners.stopDaemon → TrafficLightRenderer.getErrorCounts
                // → CodeInsightContextManagerImpl.getPreferredContext tries to read
                // editor context without a read action — causing
                // "RuntimeExceptionWithAttachments: Read access is allowed from
                // inside read-action only". Running on EDT gives implicit read
                // access in write-intent mode. Same platform bug as the
                // asyncRefresh case documented in ChatViewModel.kt:897-909.
                // This only fires when findFileByPath returns null (file not yet
                // in VFS — the fallback for externally-created files), so the
                // EDT cost is minimal.
                vfs.refreshAndFindFileByPath(canonicalFwdSlash)
            }
        if (direct != null) return direct

        // Fallback: search the project file index by filename. LLMs frequently emit
        // bare filenames (Foo.kt:42) or partial paths (src/Foo.kt) that don't match
        // the actual project location. FilenameIndex scans the VFS snapshot.
        return resolveViaFilenameIndex(project, filePath, baseNormalized)
    }

    /**
     * Fallback resolver: use FilenameIndex to find files by name when the direct
     * path lookup fails. Filters results to valid non-directory files inside the
     * project basePath (re-uses the CWE-22 traversal guard). Prefers matches whose
     * path ends with the original relative path (suffix match, case-insensitive on
     * Windows); otherwise picks the shortest-path match (heuristic: closest to the
     * project root). FilenameIndex requires a read action.
     *
     * The FilenameIndex lookup + canonicalization runs on Dispatchers.IO inside a
     * read action — canonicalization does filesystem I/O that must not block EDT.
     */
    private suspend fun resolveViaFilenameIndex(
        project: Project,
        filePath: String,
        baseNormalized: String,
    ): VirtualFile? {
        // Pure-logic extraction (unit-tested in FileRefMatchingTest).
        val fileName = FileRefMatching.extractFileName(filePath) ?: return null
        val relForSuffix = FileRefMatching.extractRelativePath(filePath) ?: return null

        val matches = withContext(Dispatchers.IO) {
            ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
                FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                    .filter { it.isValid && !it.isDirectory }
                    .filter { vf ->
                        // Re-apply CWE-22 traversal guard on index results too.
                        val canon = try { java.io.File(vf.path).canonicalPath } catch (_: Exception) { return@filter false }
                        val canonNorm = canon.replace('\\', '/')
                        canonNorm == baseNormalized || canonNorm.startsWith("$baseNormalized/")
                    }
            }
        }

        if (matches.isEmpty()) return null

        logger.debug {
            "[ACP] Follow Agent: direct path lookup failed for $filePath, found ${matches.size} index matches: " +
                matches.joinToString(",") { it.path }
        }

        // Pure-logic selection (unit-tested in FileRefMatchingTest): prefer a
        // case-insensitive suffix match (handles partial paths like "src/Foo.kt"
        // matching ".../src/Foo.kt" or bare "Foo.kt" matching any "Foo.kt" in the
        // project). Falls back to shortest path (closest to root, less likely to
        // be a generated/test variant). FilenameIndex order is stable but not
        // meaningful for our purposes.
        val selectedPath = FileRefMatching.selectBestMatch(matches.map { it.path }, relForSuffix)
            ?: return null
        return matches.firstOrNull { it.path == selectedPath }
    }

    // ── Throttle ────────────────────────────────────────────────────────

    private fun throttleCheck(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastFollowFileMs.get()
        if (now - last < FOLLOW_FILE_COOLDOWN_MS) return false
        return lastFollowFileMs.compareAndSet(last, now)
    }

    private fun schedulePendingFollow() {
        val expectedLast = lastFollowFileMs.get()
        pendingJob = scope.launch {
            delay(FOLLOW_FILE_COOLDOWN_MS)
            // Explicit cancellation/dispose check for clarity — the pendingFollowRef
            // getAndSet below also handles the no-op case, but this makes the intent
            // obvious to future maintainers and guards against the brief double-
            // navigation window noted in code review.
            if (project.isDisposed || !isActive) return@launch
            val pending = pendingFollowRef.getAndSet(null) ?: return@launch
            val color = FollowColorProvider.getColor(pending.kind) ?: return@launch
            val label = FollowColorProvider.composeInlayLabel(
                pending.kind, pending.agentName, pending.modelName, pending.input, pending.startTimeMs
            )
            // CAS: only claim the slot if no other call claimed it during our delay.
            // If a non-throttled followToolCall ran while we were waiting, its timestamp
            // differs from expectedLast and the CAS fails — skip navigation since the
            // newer call already navigated.
            val now = System.currentTimeMillis()
            if (!lastFollowFileMs.compareAndSet(expectedLast, now)) {
                logger.debug { "[ACP] Follow Agent: pending follow superseded by a newer call — skipping" }
                return@launch
            }
            navigateOnEdt(project, pending.filePath, pending.startLine, pending.endLine, color, label, focus = false)
        }
    }

    // ── Editor interaction (all on EDT) ─────────────────────────────────

    private fun navigateOnEdt(
        project: Project, filePath: String,
        startLine: Int, endLine: Int,
        color: java.awt.Color, label: String, focus: Boolean
    ) {
        // Resolve on Dispatchers.IO (FilenameIndex fallback does filesystem I/O),
        // then hop to EDT for the editor navigation. invokeLater + nonModal
        // ensures the EDT hop happens outside any modal dialog context.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            scope.launch(Dispatchers.IO) {
                val vf = resolveVirtualFile(project, filePath) ?: run {
                    logger.warn { "[ACP] Follow Agent: file not found: $filePath" }
                    return@launch
                }
                withContext(Dispatchers.EDT) {
                    if (project.isDisposed) return@withContext
                    navigateToResolvedFile(project, vf, startLine, endLine, color, label, focus, filePath)
                }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Editor-navigation helper that takes a pre-resolved [VirtualFile] instead
     * of a path string. Extracted from [navigateOnEdt] so callers that have
     * already resolved the file (e.g. the candidate-iteration path in
     * [followToolResult]) can avoid re-resolving on EDT. Must be called on EDT.
     */
    private fun navigateToResolvedFile(
        project: Project, vf: VirtualFile,
        startLine: Int, endLine: Int,
        color: java.awt.Color, label: String, focus: Boolean,
        filePathForLog: String,
    ) {
        try {
            val fem = FileEditorManager.getInstance(project)
            val hasLineRange = startLine > 0 && endLine > 0

            if (hasLineRange) {
                val midLine = (startLine + endLine) / 2
                OpenFileDescriptor(project, vf, midLine - 1, 0).navigate(focus)
                scrollAndHighlight(fem, vf, startLine, endLine, midLine, color, label)
            } else {
                // No line numbers in the tool input (e.g. edit/write/apply_patch).
                // Just open the file at the top — do NOT navigate to line 0 via
                // OpenFileDescriptor(line=0) because that scrolls the editor and
                // moves the caret to a meaningless position. Then add the inlay
                // label at the top of the file so the user sees "Agent is editing".
                fem.openFile(vf, focus)
                addInlayOnly(fem, vf, color, label)
            }

            selectInProjectView(project, vf)
            logger.info { "[ACP] Follow Agent: opened $filePathForLog" }

            // Balloon notifications intentionally omitted — the inlay label
            // and project-view selection are sufficient visual cues.
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Follow Agent: error navigating to $filePathForLog" }
        }
    }

    /**
     * Add a block inlay label at the top of the file (offset 0) without a
     * highlighter. Used for tools that don't carry line numbers in their
     * input (edit, write, apply_patch) — the user gets a visual "Agent is
     * editing" pill but the cursor and viewport stay where they were.
     */
    private fun addInlayOnly(
        fem: FileEditorManager, vf: VirtualFile,
        color: java.awt.Color, actionLabel: String
    ) {
        val selectedEditor = fem.getSelectedEditor(vf)
        val targetEditor = if (selectedEditor is TextEditor) selectedEditor
            else fem.getEditors(vf).filterIsInstance<TextEditor>().firstOrNull()
            ?: return
        val editor = targetEditor.editor
        val doc = editor.document
        if (doc.textLength == 0) return
        val firstLineOffset = doc.getLineStartOffset(0)
        val inlay = editor.inlayModel.addBlockElement(
            firstLineOffset,
            InlayProperties()
                .relatesToPrecedingText(true)
                .showAbove(true),
            AgentActionRenderer(actionLabel, color)
        )
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        scope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                // Wrap the entire disposal (including the isValid check, which can
                // itself throw if the inlay model is disposed) in runCatching so we
                // never propagate exceptions from a disposed editor.
                runCatching {
                    if (inlay?.isValid == true) inlay.dispose()
                }.onFailure { e ->
                    logger.debug(e) { "[ACP] Follow Agent: inlay already disposed" }
                }
            }
        }
    }

    private fun scrollAndHighlight(
        fem: FileEditorManager, vf: VirtualFile,
        startLine: Int, endLine: Int, midLine: Int,
        color: java.awt.Color, actionLabel: String
    ) {
        val selectedEditor = fem.getSelectedEditor(vf)
        val targetEditor = if (selectedEditor is TextEditor) selectedEditor
            else fem.getEditors(vf).filterIsInstance<TextEditor>().firstOrNull()
            ?: return

        val editor = targetEditor.editor
        val doc = editor.document
        val lineCount = doc.lineCount

        if (midLine - 1 < lineCount) {
            val lineHeight = editor.lineHeight
            if (lineHeight <= 0 || editor.scrollingModel.visibleArea.height <= 0) {
                // Editor not yet initialized — fall back to centering on midLine.
                val offset = doc.getLineStartOffset(maxOf(midLine - 1, 0))
                editor.scrollingModel.scrollTo(editor.offsetToLogicalPosition(offset), ScrollType.MAKE_VISIBLE)
                editor.caretModel.moveToOffset(offset)
                flashLineRange(editor, doc, startLine, endLine, color, actionLabel)
                return
            }
            val visibleLines = editor.scrollingModel.visibleArea.height / lineHeight
            val rangeLines = if (startLine > 0 && endLine > 0) endLine - startLine + 1 else 0
            val fitsInViewport = rangeLines in 1..visibleLines

            // Scroll to the START of the range (not the CENTER). The inlay label
            // is anchored at hlStart with showAbove=true, so the inlay appears
            // ABOVE the first highlighted line. If we scrolled to midLine, the
            // inlay would be above the viewport and invisible. Scrolling to the
            // start of the range puts the inlay at the top of the viewport.
            val offset = if (fitsInViewport) {
                // Range fits in viewport — center it so both inlay and highlight are visible
                doc.getLineStartOffset(maxOf(midLine - 1, 0))
            } else {
                // Range is larger than viewport — put the start (with inlay) at the top
                doc.getLineStartOffset(maxOf(startLine - 1, 0))
            }

            // Use MAKE_VISIBLE to ensure the offset is in the viewport without
            // unnecessary scrolling. Falls back to CENTER if the offset is already visible.
            editor.scrollingModel.scrollTo(
                editor.offsetToLogicalPosition(offset), ScrollType.MAKE_VISIBLE
            )
            editor.caretModel.moveToOffset(offset)

            flashLineRange(editor, doc, startLine, endLine, color, actionLabel)
        }
    }

    private fun flashLineRange(
        editor: Editor, doc: Document,
        startLine: Int, endLine: Int,
        color: java.awt.Color, actionLabel: String
    ) {
        // Delegate to shared EditorHighlightSupport, then auto-cleanup after 5s.
        // Pass null for disposable since we manage lifecycle via the coroutine below.
        val handle = EditorHighlightSupport.addHighlight(
            editor, startLine, endLine, color, actionLabel, disposable = null
        )
        scope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                // Wrap the entire removal (including any disposed-editor internal
                // access) in runCatching — removeHighlight may touch disposed editor
                // internals if the editor was closed before the delay elapsed.
                runCatching { EditorHighlightSupport.removeHighlight(handle) }
                    .onFailure { e -> logger.debug(e) { "[ACP] Follow Agent: highlight already disposed" } }
            }
        }
    }

    private fun selectInProjectView(project: Project, vf: VirtualFile) {
        val now = System.currentTimeMillis()
        val last = lastProjectViewSelectMs.get()
        if (now - last < PROJECT_VIEW_COOLDOWN_MS) return
        if (!lastProjectViewSelectMs.compareAndSet(last, now)) return

        try {
            val twm = ToolWindowManager.getInstance(project)
            val tw = twm.getToolWindow("Project") ?: return
            if (!tw.isVisible) return
            ProjectView.getInstance(project).select(null, vf, false)
        } catch (e: Exception) {
            // warn (not debug) — the user enables this feature explicitly and
            // should see in idea.log if the Project View integration silently fails.
            logger.warn(e) { "[ACP] Follow Agent: error selecting in Project View" }
        }
    }
}
