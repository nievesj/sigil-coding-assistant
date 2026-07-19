package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.progress.ProgressManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException

/**
 * Pure-logic file-search utilities extracted from `ChatScreen.kt` (TDD §9 step 7).
 *
 * These functions have no Compose dependencies and are unit-testable in isolation
 * (modulo the IntelliJ `Project` requirement for [searchProjectFiles] and
 * [computeRecentFiles], which need a real application context).
 *
 * MUST NOT touch (per TDD §4.7.2):
 * - [ProgressManager.checkCanceled] calls — preserved exactly
 * - `maxNodes = 5000` cap — preserved exactly
 * - `maxDepth = 10` — preserved exactly
 * - Symlink fail-closed behavior in [isSymLink] — preserved exactly
 * - The `LinkageError` catch in [computeRecentFiles] for `EditorHistoryManager`
 *   unavailability — preserved exactly
 * - The canonical path traversal guard in [searchProjectFiles] — preserved exactly
 * - The hardcoded skip set for build/dependency directories — preserved exactly
 */
object ProjectFileSearch {

    /**
     * Computes recent files: currently open editors first, then recently closed files.
     */
    fun computeRecentFiles(project: Project): List<RecentFile> {
        val openFiles = FileEditorManager.getInstance(project).openFiles
            .filter { it.isValid && !it.isDirectory }
            .map { RecentFile(name = it.name, path = it.path, isOpen = true) }

        val openPaths = openFiles.map { it.path }.toSet()

        // NOTE: EditorHistoryManager is an internal `impl` package API and may change
        // or be removed on platform upgrades. Wrap in try/catch so the plugin degrades
        // gracefully (returns empty list for closed files) instead of crashing.
        val closedFiles = try {
            com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project).fileList
                .filter { it.isValid && !it.isDirectory && it.path !in openPaths }
                .takeLast(15)
                .map { RecentFile(name = it.name, path = it.path, isOpen = false) }
        } catch (e: NoClassDefFoundError) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (NoClassDefFoundError)" }
            emptyList()
        } catch (e: LinkageError) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (LinkageError)" }
            emptyList()
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (Exception)" }
            emptyList()
        }

        return openFiles + closedFiles
    }

    /**
     * Searches project files by name using IntelliJ's FilenameIndex.
     * First tries exact match, then falls back to iterating project scopes for partial matches.
     * @param openPaths set of paths currently open in editor tabs — matched files are marked
     *  with [RecentFile.isOpen] = true so they can be prioritized in the UI.
     */
    fun searchProjectFiles(
        project: Project,
        query: String,
        maxResults: Int = 20,
        openPaths: Set<String> = emptySet()
    ): List<RecentFile> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<RecentFile>()
        val seen = mutableSetOf<String>()

        // Exact filename match first
        FilenameIndex.getVirtualFilesByName(query, GlobalSearchScope.projectScope(project))
            .filter { it.isValid && !it.isDirectory }
            .take(maxResults)
            .forEach { vf ->
                if (vf.path !in seen) {
                    seen.add(vf.path)
                    results.add(RecentFile(name = vf.name, path = vf.path, isOpen = vf.path in openPaths))
                }
            }

        // If we have enough results, return early
        if (results.size >= maxResults) return results.take(maxResults)

        // Local file system for VFS operations
        val localFileSystem = LocalFileSystem.getInstance()
        // Fall back to project base path for partial match traversal
        val basePath = project.basePath ?: return results
        val baseDir = localFileSystem.findFileByPath(basePath) ?: return results

        // Bounded recursive traversal: depth limit prevents deep recursion on
        // huge directory trees, and maxNodes caps total nodes (directories + files)
        // inspected to avoid holding the read lock for too long on large projects.
        val maxDepth = 10
        val maxNodes = 5000
        var visited = 0

        fun searchDir(dir: VirtualFile, depth: Int) {
            if (results.size >= maxResults || depth > maxDepth || visited >= maxNodes) return
            visited++
            // Check for cancellation periodically to allow write actions to interrupt
            // the read action. Without this, holding the read lock for 5000 nodes
            // can starve write actions (file saves, project config changes).
            // NOTE: A node-count-based check can still hold the read lock for a long
            // time on slow filesystems (network drives, large repos). A time-based
            // check (e.g. checkCanceled() every N ms) would bound latency better.
            if (visited % 500 == 0) {
                // ProgressManager.checkCanceled() throws ProcessCanceledException
                // which must be rethrown, not swallowed.
                ProgressManager.checkCanceled()
            }
            val children = dir.children ?: return
            for (child in children) {
                if (results.size >= maxResults || visited >= maxNodes) return
                // Check for cancellation periodically within large directories
                if (visited % 500 == 0) {
                    ProgressManager.checkCanceled()
                }
                if (child.isDirectory && !isSymLink(child)) {
                    // Skip hidden and build directories; don't follow symlinks (path traversal / cycles)
                    if (!child.name.startsWith(".") && child.name !in setOf("build", "node_modules", ".git", "out", "target", ".idea", "__pycache__", ".gradle", "dist", ".next", ".venv", "bin", "obj", ".build", "vendor", "Pods", ".dart_tool", "coverage", ".cache", "tmp", "temp", "bower_components", ".vs", "cmake-build-debug", ".cargo", ".npm", ".yarn", ".pnpm-store")) {
                        // NOTE: This skip set is hardcoded and covers the most common build/dependency
                        // directories. It may still miss non-standard locations. A future enhancement
                        // could use IntelliJ's ProjectFileIndex.isExcluded or read .gitignore entries
                        // for a more robust solution. The canonicalization check below remains the
                        // security boundary; this skip set is only a performance/usability optimization.
                        searchDir(child, depth + 1)
                    }
                } else if (child.isValid && !isSymLink(child)) {
                    visited++
                    val nameLower = child.name.lowercase()
                    if (nameLower.contains(query.lowercase()) && child.path !in seen) {
                        // Reject symlinks that escape the project base path.
                        // On canonicalization failure, reject the file — a path that can't be
                        // canonicalized is untrusted (broken symlink, restricted path, or a
                        // symlink that resolves outside the project). Falling back to the raw
                        // path would bypass the boundary check via symlinks.
                        // Canonicalization is a filesystem stat call — deferred to only matched files
                        // to minimize I/O. For 5000 nodes with 100 matches, this is 100 stat calls.
                        val canonicalChild = try { File(child.path).canonicalPath } catch (_: Exception) { continue }
                        // Fail closed for the base path too: if the base cannot be canonicalized,
                        // skip the entire partial-match search rather than falling back to the raw
                        // basePath (which could contain ".." or resolve outside the project root
                        // via a symlink, bypassing the startsWith boundary check). Consistent with
                        // the child path's fail-closed behavior above.
                        val canonicalBase = try { File(basePath).canonicalPath } catch (_: Exception) { return }
                        // On Windows, paths are case-insensitive — normalize for comparison
                        val compareChild = if (System.getProperty("os.name").lowercase().contains("win")) canonicalChild.lowercase() else canonicalChild
                        val compareBase = if (System.getProperty("os.name").lowercase().contains("win")) canonicalBase.lowercase() else canonicalBase
                        if (compareChild.startsWith(compareBase + File.separator) || compareChild == compareBase) {
                            seen.add(child.path)
                            results.add(RecentFile(name = child.name, path = child.path, isOpen = child.path in openPaths))
                        }
                    }
                }
            }
        }

        // If exact match didn't find enough, do partial search
        if (results.size < maxResults) {
            searchDir(baseDir, 0)
        }

        return results.take(maxResults)
    }

    /**
     * Returns true if [file] is a symlink. Uses `java.nio.file.Files.isSymbolicLink`
     * on the local path so it works against the public [VirtualFile] API (the
     * `isSymlink()` method is only on the internal `VirtualFileSystemEntry` impl).
     * Non-local VFS schemes (jar://, http://, etc.) cannot be symlinks.
     *
     * NOTE: This function fails closed (returns true) on any I/O or security error.
     * Transient I/O errors (e.g. a flaky network mount momentarily unreadable) will
     * cause affected files/directories to be silently skipped in search results —
     * they disappear from results even though they exist on disk.
     */
    fun isSymLink(file: VirtualFile): Boolean {
        if (file.fileSystem !is LocalFileSystem) return false
        return try {
            Files.isSymbolicLink(File(file.path).toPath())
        } catch (_: InvalidPathException) {
            // Path has invalid characters — fail closed: treat as symlink so the caller
            // skips it. A path that can't be resolved is untrusted.
            true
        } catch (_: SecurityException) {
            // Security manager denied access — fail closed: treat as symlink so the
            // caller skips it rather than following a path it can't inspect.
            true
        } catch (_: IOException) {
            // I/O error checking symlink — fail closed: treat as symlink so the
            // caller skips it rather than risk following a broken/restricted path.
            true
        }
    }
}