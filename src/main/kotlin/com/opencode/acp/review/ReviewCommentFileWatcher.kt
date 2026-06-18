package com.opencode.acp.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches `.review/` for changes and re-reads the changed file into the index.
 * Extracted from [ReviewCommentManager] so file-watching is testable in isolation.
 *
 * Exposes two listeners:
 * - [asyncListener] — [AsyncFileListener] for `.review/` JSON file content changes.
 * - [bulkMoveListener] — [BulkFileListener] for source-file moves/renames/deletes,
 *   so the `.review/` mirror file follows the source file.
 */
class ReviewCommentFileWatcher(
    private val project: Project,
    private val repository: ReviewCommentRepository,
    private val onExternalFileChange: suspend (String) -> Unit,
    private val isSelfWrite: (String) -> Boolean,
) : Disposable {

    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        // Cancel in-flight moveFile/deleteReviewFile coroutines so they don't
        // keep doing file I/O for a project whose ReviewCommentManager is gone.
        // The VFS listeners themselves are auto-removed by their parent Disposable
        // (the manager); this only stops work already launched on this scope.
        scope.cancel()
    }

    /** [AsyncFileListener] — fires on any VFS change under `.review/`.
     *  Filters to `.json` (ignores `.json.tmp` orphan cleanup) and skips
     *  self-writes. Delegates the actual re-read to [onExternalFileChange]
     *  on a background coroutine so the VFS thread isn't blocked. */
    val asyncListener = object : AsyncFileListener {
        override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
            val reviewEvents = events.filter { ev ->
                val f = ev.file ?: return@filter false
                val path = f.path
                path.startsWith(PathUtils.reviewRootPrefix(project)) &&
                    path.endsWith(".json") && !path.endsWith(".json.tmp")
            }
            if (reviewEvents.isEmpty()) return null
            return object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    for (ev in reviewEvents) {
                        val vf = ev.file ?: continue
                        val sourcePath = PathUtils.resolveSourcePath(project, vf) ?: continue
                        if (isSelfWrite(sourcePath)) continue
                        scope.launch {
                            try {
                                onExternalFileChange(sourcePath)
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Failed to handle external file change for $sourcePath" }
                            }
                        }
                    }
                }
            }
        }
    }

    /** [BulkFileListener] — catches file moves/renames/deletes from ALL sources
     *  (IDE refactoring, Git operations, OS moves, VFS refresh). Registered
     *  programmatically by [ReviewCommentManager.init] via
     *  `project.messageBus.connect(this).subscribe(VFS_CHANGES, …)`.
     *  Works in dumb mode (no PSI needed).
     *
     *  C3: each branch prunes events whose source path has NO `.review/`
     *  file via a cheap synchronous `exists()` check, so a 200-file package
     *  rename of files with no comments doesn't spawn 200 no-op coroutines.
     *  The check is intentionally on the EDT thread (where BulkFileListener
     *  fires) — `Path.exists()` is a stat() call, far cheaper than launching
     *  a coroutine + doing the same stat inside `moveFile`. */
    val bulkMoveListener = object : BulkFileListener {
        override fun after(events: MutableList<out VFileEvent>) {
            for (event in events) {
                when (event) {
                    is VFileMoveEvent -> {
                        val fileName = event.file?.name ?: continue
                        val oldParent = event.oldParent
                        val newParent = event.file?.parent
                        if (oldParent == null || newParent == null) continue
                        val oldPath = PathUtils.relativePath(project, oldParent, fileName) ?: continue
                        val newPath = PathUtils.relativePath(project, newParent, fileName) ?: continue
                        // C3: only act if a .review file actually exists for the old path.
                        val oldJson = repository.jsonPathFor(oldPath) ?: continue
                        if (!oldJson.toFile().isFile) continue
                        scope.launch {
                            try {
                                repository.moveFile(oldPath, newPath)
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Failed to move review file" }
                            }
                        }
                    }
                    is VFilePropertyChangeEvent -> {
                        if (event.propertyName == VirtualFile.PROP_NAME) {
                            val parent = event.file?.parent ?: continue
                            val oldValue = event.oldValue ?: continue
                            val newValue = event.newValue ?: continue
                            val oldName = oldValue.toString()
                            val newName = newValue.toString()
                            val oldPath = PathUtils.relativePath(project, parent, oldName) ?: continue
                            val newPath = PathUtils.relativePath(project, parent, newName) ?: continue
                            // C3: only act if a .review file actually exists for the old path.
                            val oldJson = repository.jsonPathFor(oldPath) ?: continue
                            if (!oldJson.toFile().isFile) continue
                            scope.launch {
                                try {
                                    repository.moveFile(oldPath, newPath)
                                } catch (e: Exception) {
                                    logger.warn(e) { "[ACP] Failed to move review file" }
                                }
                            }
                        }
                    }
                    // M9: source file deleted → delete the orphaned .review/ JSON.
                    is VFileDeleteEvent -> {
                        val vf = event.file
                        val path = PathUtils.relativePath(project, vf) ?: continue
                        val oldJson = repository.jsonPathFor(path) ?: continue
                        if (!oldJson.toFile().isFile) continue
                        scope.launch {
                            try {
                                repository.deleteReviewFile(path)
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Failed to delete review file" }
                            }
                        }
                    }
                }
            }
        }
    }
}