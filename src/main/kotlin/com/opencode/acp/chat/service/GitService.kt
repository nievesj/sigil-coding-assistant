package com.opencode.acp.chat.service

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.StagedFilesResult
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.util.DiffEntry
import com.opencode.acp.chat.util.UnifiedDiffParser
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Service layer — wraps IntelliJ VCS APIs.
 *
 * Uses platform ChangeListManager (VCS-agnostic) — works with any VCS
 * (git, svn, mercurial, perforce) without requiring specific VCS plugins.
 *
 * CRITICAL: All methods that touch VCS data must be called inside
 * runReadAction on Dispatchers.IO.
 *
 * PERFORMANCE: Caches LineDelta results keyed by file path + revision hash
 * to avoid recomputing LCS diff on every VCS event.
 */
class GitService(private val project: Project) {

    // Cache LineDelta results keyed by file path + revision hash
    private val lineDeltaCache = ConcurrentHashMap<String, LineDelta>()

    private val logger = KotlinLogging.logger {}

    /**
     * Returns list of changed files with line deltas.
     * MUST be called inside runReadAction.
     *
     * PERFORMANCE: Caches LineDelta results. Only recomputes for files
     * whose revision hash has changed.
     */
    @Deprecated("Use getStagedFiles() for the Review feature. Retained for compatibility.")
    fun getChangedFiles(): List<ChangedFile> {
        val changeListManager = ChangeListManager.getInstance(project)

        // Use default changelist only (not allChanges which includes shelves)
        val defaultChanges = changeListManager.defaultChangeList.changes

        val trackedChanges = defaultChanges.mapNotNull { change ->
            try {
                val filePath = getRelativePath(change)
                val fileName = change.virtualFile?.name
                    ?: change.beforeRevision?.file?.name
                    ?: "unknown"
                val virtualFile = change.virtualFile
                ChangedFile(
                    filePath = filePath,
                    fileName = fileName,
                    status = mapFileStatus(change.fileStatus),
                    lineDelta = computeLineDeltaCached(change, filePath),
                    virtualFile = virtualFile
                )
            } catch (_: Exception) {
                null // Skip changes that throw (binary, locked, etc.)
            }
        }

        // Untracked files — ChangeListManager.unversionedFilesPaths returns List<FilePath>
        val untrackedFiles = changeListManager.unversionedFilesPaths.mapNotNull { filePath ->
            try {
                val virtualFile = filePath.virtualFile ?: return@mapNotNull null
                val path = virtualFile.path
                val relativePath = getRelativePathFromRoot(path)
                ChangedFile(
                    filePath = relativePath,
                    fileName = virtualFile.name,
                    status = FileChangeStatus.UNTRACKED,
                    lineDelta = LineDelta.Unknown,
                    virtualFile = virtualFile
                )
            } catch (_: Exception) {
                null
            }
        }

        // Clean up cache entries for files no longer in the changelist
        val currentPaths = (trackedChanges.map { it.filePath } + untrackedFiles.map { it.filePath }).toSet()
        lineDeltaCache.keys.retainAll(currentPaths)

        // Filter out plugin-internal files that should never appear in the Review tab
        // (e.g. `.review/` JSON files written by the adversarial-review feature).
        return (trackedChanges + untrackedFiles).filterNot { it.filePath.startsWith(".review/") }
    }

    /**
     * Returns staged files (git index) with line deltas parsed from `git diff --cached`.
     *
     * Unlike [getChangedFiles] which uses the platform ChangeListManager (VCS-agnostic),
     * this method requires git4idea and runs `git diff --cached --no-renames` directly.
     * Line deltas come from the diff output itself (no LCS computation needed).
     *
     * MUST be called inside runReadAction on Dispatchers.IO (same contract as
     * [getChangedFiles]).
     *
     * @return [StagedFilesResult.Staged] with a non-empty file list,
     *         [StagedFilesResult.NothingStaged] if git is present but nothing is staged,
     *         [StagedFilesResult.NoGitRepository] if git4idea is not loaded or no git
     *         repository is found in the project.
     */
    fun getStagedFiles(): StagedFilesResult {
        // Guard: git4idea plugin must be present (optional dependency).
        // NOTE: PluginManager.isPluginInstalled returns true for disabled plugins
        // too, so this guard is necessary but not sufficient — the git4idea calls
        // below are additionally wrapped in a try-catch for NoClassDefFoundError /
        // IllegalStateException to handle present-but-disabled and classloading
        // edge cases (see review comment cmt_b2c3d4e5f6a7).
        if (!PluginManager.isPluginInstalled(PluginId.getId("Git4Idea"))) {
            return StagedFilesResult.NoGitRepository
        }

        // git4idea classes (GitRepositoryManager, GitLineHandler, Git) are only on
        // the classpath when the Git4Idea plugin is loaded. If the plugin is
        // installed-but-disabled or classloading fails, accessing these classes
        // throws NoClassDefFoundError — an Error, not an Exception, which would
        // escape callers' catch(Exception) blocks (e.g. ReviewPanel's produceState).
        // Wrap the entire git4idea interaction and degrade gracefully to
        // NoGitRepository on any classloading/state error.
        val repositories = try {
            GitRepositoryManager.getInstance(project).repositories
        } catch (e: NoClassDefFoundError) {
            logger.warn { "[ACP] git4idea classes unavailable (plugin disabled or not loaded): ${e.message}" }
            return StagedFilesResult.NoGitRepository
        } catch (e: IllegalStateException) {
            logger.warn { "[ACP] GitRepositoryManager not registered: ${e.message}" }
            return StagedFilesResult.NoGitRepository
        }
        if (repositories.isEmpty()) {
            return StagedFilesResult.NoGitRepository
        }

        val stagedFiles = mutableListOf<ChangedFile>()
        val repoErrors = mutableListOf<String>()

        for (repo in repositories) {
            try {
                val handler = GitLineHandler(project, repo.root, GitCommand.DIFF)
                handler.addParameters("--cached", "--no-renames")
                handler.setStdoutSuppressed(true)
                handler.setStderrSuppressed(true)
                handler.setSilent(true)
                val result = Git.getInstance().runCommand(handler)

                if (!result.success()) {
                    val errMsg = "${repo.root.path}: ${result.errorOutputAsJoinedString}"
                    logger.warn { "[ACP] git diff --cached failed for $errMsg" }
                    repoErrors.add(errMsg)
                    continue
                }

                val diffOutput = result.outputAsJoinedString
                val entries: List<DiffEntry> = UnifiedDiffParser.parse(diffOutput)

                for ((filePath, status, additions, deletions) in entries) {
                    // Defense-in-depth: validate git-parsed path before constructing
                    // absolute path. Git diff output is trusted (paths are relative to
                    // repo root), but a malformed output or future code path could
                    // produce absolute paths or `../` sequences that resolve outside
                    // the repo (CWE-22).
                    if (!isSafeRelativePath(filePath)) {
                        logger.warn { "[ACP] Skipping staged file with unsafe path: $filePath" }
                        continue
                    }
                    val absolutePath = "${repo.root.path}/$filePath"
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                    stagedFiles.add(
                        ChangedFile(
                            filePath = filePath,
                            fileName = filePath.substringAfterLast('/'),
                            status = status,
                            lineDelta = LineDelta.Known(additions = additions, deletions = deletions),
                            virtualFile = virtualFile,
                        )
                    )
                }
            } catch (e: NoClassDefFoundError) {
                // git4idea classes (GitLineHandler, Git) missing despite the
                // GitRepositoryManager guard above — plugin disabled mid-execution
                // or classloading edge case. Degrade to NoGitRepository.
                logger.warn { "[ACP] git4idea classes unavailable during diff: ${e.message}" }
                return StagedFilesResult.NoGitRepository
            } catch (e: IllegalStateException) {
                logger.warn { "[ACP] git4idea service unavailable during diff: ${e.message}" }
                return StagedFilesResult.NoGitRepository
            }
        }

        // Filter out plugin-internal .review/ files (same filter as getChangedFiles).
        val filtered = stagedFiles.filterNot { it.filePath.startsWith(".review/") }

        // NOTE: lineDeltaCache is NOT touched here. That cache is populated only by
        // computeLineDeltaCached (used by the deprecated getChangedFiles()). Since
        // getStagedFiles() computes line deltas directly from the diff output and
        // never reads/writes the cache, evicting entries here would silently destroy
        // getChangedFiles()'s cache (cross-method interference). The cache is
        // self-managing via getChangedFiles()'s own retainAll on its result set.

        return when {
            // No files collected AND at least one repo failed → surface the error
            // even if another repo succeeded-with-empty. The doc on StagedFilesResult.Error
            // says it's "Distinct from NothingStaged so callers can show an error, not
            // a misleading 'stage your files' prompt." Returning NothingStaged when one
            // repo is corrupt would mislead the user into thinking nothing is staged
            // when actually a repo error prevented us from knowing.
            filtered.isEmpty() && repoErrors.isNotEmpty() -> {
                StagedFilesResult.Error(repoErrors.joinToString("; "))
            }
            // No files collected, all repos succeeded with empty diffs → genuinely nothing staged
            filtered.isEmpty() -> {
                StagedFilesResult.NothingStaged
            }

            else -> {
                StagedFilesResult.Staged(filtered)
            }
        }
    }

    /**
     * Computes line deltas with caching. Uses file path + revision hash as cache key.
     * Falls back to recomputation if cache miss.
     */
    private fun computeLineDeltaCached(change: Change, filePath: String): LineDelta {
        // Build cache key from file path + before/after revision hashes
        val beforeHash = change.beforeRevision?.revisionNumber?.asString() ?: "none"
        val afterHash = change.afterRevision?.revisionNumber?.asString() ?: "none"
        val cacheKey = "$filePath|$beforeHash|$afterHash"

        return lineDeltaCache.computeIfAbsent(cacheKey) {
            computeLineDelta(change)
        }
    }

    /**
     * Computes line deltas using LCS diff algorithm on ContentRevision.content.
     * Returns LineDelta.Unknown for binary files or when content is unavailable.
     * MUST be called inside runReadAction.
     *
     * PERFORMANCE: Early-exit for ADDED/DELETED files where result is trivially known.
     * Binary file check before loading content.
     */
    private fun computeLineDelta(change: Change): LineDelta {
        return try {
            val before = change.beforeRevision?.content
            val after = change.afterRevision?.content

            // Binary files or completely unavailable content
            if (before == null && after == null) return LineDelta.Unknown

            // Early-exit for ADDED files: additions = line count, deletions = 0
            if (before == null && after != null) {
                val lineCount = after.lines().size
                return LineDelta.Known(additions = lineCount, deletions = 0)
            }

            // Early-exit for DELETED files: additions = 0, deletions = line count
            if (before != null && after == null) {
                val lineCount = before.lines().size
                return LineDelta.Known(additions = 0, deletions = lineCount)
            }

            // Both before and after exist — compute LCS diff
            val beforeLines = before!!.lines()
            val afterLines = after!!.lines()

            // Use LCS diff to compute actual additions and deletions
            val (additions, deletions) = computeLcsDiff(beforeLines, afterLines)
            LineDelta.Known(additions = additions, deletions = deletions)
        } catch (e: Exception) {
            // VcsException, IOException, etc. for locked/binary/large files
            LineDelta.Unknown
        }
    }

    /**
     * LCS-based diff algorithm that computes real additions/deletions.
     * Unlike simple line-count comparison, this correctly identifies lines
     * that were changed (not just net additions).
     *
     * PERFORMANCE: Space-optimized DP with only 2 rows — O(n) space, O(m*n) time.
     * Previous HashSet-based fallback for large files was removed because it
     * produced incorrect results for reordered content (gave 0/0 when lines
     * were reordered rather than deleted/added). The 2-row DP approach keeps
     * memory bounded even for large files.
     */
    private fun computeLcsDiff(before: List<String>, after: List<String>): Pair<Int, Int> {
        val m = before.size
        val n = after.size

        // Optimize: if one side is empty, all lines are added or deleted
        if (m == 0) return Pair(n, 0)
        if (n == 0) return Pair(0, m)

        // Guard: skip O(m*n) LCS for very large files — use simple line count
        // comparison instead. This is less accurate but prevents CPU spikes
        // on generated/minified files with 100k+ lines.
        if (m > LCS_SIZE_THRESHOLD || n > LCS_SIZE_THRESHOLD) {
            return Pair(n, m) // Conservative: report all lines as changed
        }

        // Standard LCS dynamic programming — space-optimized to 2 rows
        val dp = Array(2) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i % 2][j] = if (before[i - 1] == after[j - 1]) {
                    dp[(i - 1) % 2][j - 1] + 1
                } else {
                    maxOf(dp[(i - 1) % 2][j], dp[i % 2][j - 1])
                }
            }
        }

        val lcsLength = dp[m % 2][n]
        val additions = n - lcsLength  // Lines in "after" not in LCS
        val deletions = m - lcsLength  // Lines in "before" not in LCS
        return Pair(additions, deletions)
    }

    /**
     * Gets relative path from project root.
     * Uses beforeRevision.file.path for deleted files (virtualFile is null).
     * Normalizes path separators to '/' for cross-platform consistency.
     */
    private fun getRelativePath(change: Change): String =
        getRelativePath(project, change)

    private fun getRelativePathFromRoot(absolutePath: String): String =
        getRelativePathFromRoot(project, absolutePath)

    private fun mapFileStatus(status: FileStatus): FileChangeStatus = when (status) {
        FileStatus.MODIFIED -> FileChangeStatus.MODIFIED
        FileStatus.ADDED -> FileChangeStatus.ADDED
        FileStatus.DELETED -> FileChangeStatus.DELETED
        FileStatus.MERGED_WITH_CONFLICTS -> FileChangeStatus.CONFLICTED
        // Unversioned files use FileStatus.UNKNOWN in IntelliJ API
        FileStatus.UNKNOWN -> FileChangeStatus.UNTRACKED
        // IGNORED, HIJACKED, SWITCHED, OBSOLETE, TYPE_CHANGED, etc.
        else -> FileChangeStatus.MODIFIED
    }

    /** Invalidate cached data (call when project changes or VCS state resets). */
    fun invalidateCache() {
        lineDeltaCache.clear()
    }

    companion object {
        private const val LCS_SIZE_THRESHOLD = 10_000

        /**
         * Validates that [path] is a safe relative path (no path traversal).
         * Rejects absolute paths (starting with `/` or containing `:` on Windows),
         * Windows UNC paths (`\\server\share\...`), null bytes (CWE-22 path
         * injection via NUL truncation), and paths with `../` sequences that
         * could escape the repo root (CWE-22).
         * Used as defense-in-depth on git diff output paths.
         */
        internal fun isSafeRelativePath(path: String): Boolean {
            if (path.isBlank()) return false
            // Reject embedded NUL bytes — git uses NUL as a path delimiter in
            // --null output, and NUL truncation is a classic CWE-22 vector.
            if ('\u0000' in path) return false
            if (path.startsWith("/")) return false
            // Reject Windows UNC paths (\\server\share\...) — these are absolute
            // paths that resolve outside the repo but have no ':' at index 1,
            // so the drive-letter check below doesn't catch them.
            if (path.startsWith("\\\\")) return false
            // Reject Windows drive letters (e.g., "C:/foo") and UNC paths
            if (path.length >= 2 && path[1] == ':') return false
            // Reject any ".." path segment (covers "../foo", "foo/../bar", "foo/..")
            val segments = path.replace('\\', '/').split("/")
            return segments.none { it == ".." }
        }
    }
}

/**
 * Shared utility: compute relative path from project root.
 * Used by both GitService and openDiffForPath to ensure consistent path matching.
 * Normalizes path separators to '/' for cross-platform consistency.
 */
fun getRelativePath(project: Project, change: Change): String {
    val absolutePath = change.virtualFile?.path
        ?: change.beforeRevision?.file?.path
        ?: return "unknown"
    return getRelativePathFromRoot(project, absolutePath)
}

fun getRelativePathFromRoot(project: Project, absolutePath: String): String {
    val basePath = project.basePath
    if (basePath == null) {
        // No base path (default project, remote dev, lightweight test project).
        // Return the absolute path — cannot compute relative path.
        return absolutePath
    }
    val normalizedAbsolute = absolutePath.replace('\\', '/')
    val normalizedBase = basePath.replace('\\', '/') + "/"
    return if (normalizedAbsolute.startsWith(normalizedBase)) {
        normalizedAbsolute.removePrefix(normalizedBase)
    } else {
        // File is outside the project root — return absolute path.
        // This is correct behavior but means the review UI will show
        // absolute paths for out-of-project files.
        normalizedAbsolute
    }
}