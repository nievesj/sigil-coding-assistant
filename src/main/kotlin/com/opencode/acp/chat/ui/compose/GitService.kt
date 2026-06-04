package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import java.util.concurrent.ConcurrentHashMap

/**
 * Service layer — wraps IntelliJ VCS APIs.
 *
 * CRITICAL: All methods that touch VCS data must be called inside
 * runReadAction on Dispatchers.IO. Git4Idea classes are accessed
 * via reflection to avoid NoClassDefFoundError when the plugin is absent.
 *
 * PERFORMANCE: Caches LineDelta results keyed by file path + revision hash
 * to avoid recomputing LCS diff on every VCS event.
 */
class GitService(private val project: Project) {

    companion object {
        private const val GIT4IDEA_PLUGIN_ID = "Git4Idea"

        // Cache reflection results to avoid repeated Class.forName/getMethod calls
        private var gitPluginInstalledCache: Boolean? = null
        private var gitRepositoryManagerClass: Class<*>? = null
        private var gitGetInstanceMethod: java.lang.reflect.Method? = null
        private var gitGetRepositoriesMethod: java.lang.reflect.Method? = null

        /** Check if git4idea is installed via PluginManager (cached). */
        fun isGitPluginInstalled(): Boolean {
            gitPluginInstalledCache?.let { return it }
            return try {
                val pluginId = PluginId.getId(GIT4IDEA_PLUGIN_ID)
                val installed = com.intellij.ide.plugins.PluginManager.isPluginInstalled(pluginId)
                gitPluginInstalledCache = installed
                installed
            } catch (_: Exception) {
                gitPluginInstalledCache = false
                false
            }
        }

        /** Clear cached state (for testing or plugin reload). */
        fun clearCache() {
            gitPluginInstalledCache = null
            gitRepositoryManagerClass = null
            gitGetInstanceMethod = null
            gitGetRepositoriesMethod = null
        }
    }

    // Cache LineDelta results keyed by file path + revision hash
    private val lineDeltaCache = ConcurrentHashMap<String, LineDelta>()

    // Cache git availability check per project
    private var gitAvailableCache: Boolean? = null

    /** Returns true if git4idea is installed AND at least one git repo exists (cached). */
    fun isGitAvailable(): Boolean {
        gitAvailableCache?.let { return it }
        if (!isGitPluginInstalled()) {
            gitAvailableCache = false
            return false
        }
        // Access GitRepositoryManager via reflection to avoid NoClassDefFoundError
        return try {
            val managerClass = gitRepositoryManagerClass
                ?: Class.forName("git4idea.repo.GitRepositoryManager").also { gitRepositoryManagerClass = it }
            val getInstance = gitGetInstanceMethod
                ?: managerClass.getMethod("getInstance", Project::class.java).also { gitGetInstanceMethod = it }
            val manager = getInstance.invoke(null, project)
            val getRepositories = gitGetRepositoriesMethod
                ?: managerClass.getMethod("getRepositories").also { gitGetRepositoriesMethod = it }
            @Suppress("UNCHECKED_CAST")
            val repos = getRepositories.invoke(manager) as? List<*> ?: emptyList<Any>()
            val available = repos.isNotEmpty()
            gitAvailableCache = available
            available
        } catch (_: Exception) {
            // Catch Exception for git4idea reflection failures.
            // Note: NoClassDefFoundError extends Error, not Exception — it is caught
            // by the outer produceState's catch (Throwable) block, not here.
            gitAvailableCache = false
            false
        }
    }

    /**
     * Returns list of changed files with line deltas.
     * MUST be called inside runReadAction.
     *
     * PERFORMANCE: Caches LineDelta results. Only recomputes for files
     * whose revision hash has changed.
     */
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

        return trackedChanges + untrackedFiles
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

        return lineDeltaCache.getOrPut(cacheKey) {
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
        } catch (e: Throwable) {
            // Catch Throwable (not Exception) to handle NoClassDefFoundError,
            // VcsException, IOException, etc. for locked/binary/large files
            LineDelta.Unknown
        }
    }

    /**
     * LCS-based diff algorithm that computes real additions/deletions.
     * Unlike simple line-count comparison, this correctly identifies lines
     * that were changed (not just net additions).
     *
     * PERFORMANCE: Space-optimized DP with only 2 rows. Falls back to
     * net-change for very large files (>5000 lines).
     */
    private fun computeLcsDiff(before: List<String>, after: List<String>): Pair<Int, Int> {
        val m = before.size
        val n = after.size

        // Optimize: if one side is empty, all lines are added or deleted
        if (m == 0) return Pair(n, 0)
        if (n == 0) return Pair(0, m)

        // Standard LCS dynamic programming
        // Use space-optimized version for large files (only keep 2 rows)
        val maxDim = maxOf(m, n)
        if (maxDim > 5000) {
            // Fall back to line-count comparison for very large files
            // to avoid O(n*m) memory/time cost
            return Pair(
                (after.size - before.size).coerceAtLeast(0),
                (before.size - after.size).coerceAtLeast(0)
            )
        }

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
    private fun getRelativePath(change: Change): String {
        val absolutePath = change.virtualFile?.path
            ?: change.beforeRevision?.file?.path
            ?: return "unknown"
        return getRelativePathFromRoot(absolutePath)
    }

    private fun getRelativePathFromRoot(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        // Normalize separators for cross-platform consistency
        val normalizedAbsolute = absolutePath.replace('\\', '/')
        val normalizedBase = basePath.replace('\\', '/') + "/"
        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase)
        } else {
            normalizedAbsolute
        }
    }

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
        gitAvailableCache = null
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
    val basePath = project.basePath ?: return absolutePath
    val normalizedAbsolute = absolutePath.replace('\\', '/')
    val normalizedBase = basePath.replace('\\', '/') + "/"
    return if (normalizedAbsolute.startsWith(normalizedBase)) {
        normalizedAbsolute.removePrefix(normalizedBase)
    } else {
        normalizedAbsolute
    }
}