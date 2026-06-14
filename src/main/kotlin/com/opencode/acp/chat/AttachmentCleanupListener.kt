package com.opencode.acp.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files

/**
 * Deletes `<projectDir>/.opencode/attachments/` on project close.
 *
 * Safety checks:
 *   1. Directory name must be exactly "attachments"
 *   2. If the directory is a symlink, the symlink itself is deleted (not followed)
 *   3. `deleteRecursively()` return value is checked; partial deletion is logged
 */
class AttachmentCleanupListener : ProjectManagerListener {

    override fun projectClosed(project: Project) {
        cleanupAttachments(project)
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Deletes the project's `.opencode/attachments/` directory.
         * Also called by [ShutdownListener] for IDE-wide cleanup on shutdown.
         */
        fun cleanupAttachments(project: Project) {
            val baseDir = project.basePath ?: return
            val attachmentsDir = File(baseDir, ".opencode/attachments")
            if (!attachmentsDir.exists()) return

            // Safety check 1: directory name must be exactly "attachments"
            if (attachmentsDir.name != "attachments") return

            // Safety check 2: resolved canonical path must match expected path
            val expectedCanonical = File(baseDir, ".opencode/attachments").canonicalPath
            val actualCanonical = attachmentsDir.canonicalPath
            if (actualCanonical != expectedCanonical) {
                log.warn { "[ACP] Attachments dir canonical path mismatch — skipping cleanup: ${attachmentsDir.absolutePath} (expected=$expectedCanonical, actual=$actualCanonical)" }
                return
            }

            // Safety check 3: if the path is a symlink, delete the symlink, not its target
            if (Files.isSymbolicLink(attachmentsDir.toPath())) {
                val deleted = attachmentsDir.delete()
                if (deleted) {
                    log.info { "[ACP] Cleaned up symlink ${attachmentsDir.absolutePath}" }
                } else {
                    log.warn { "[ACP] Failed to delete symlink ${attachmentsDir.absolutePath}" }
                }
                return
            }

            // Normal directory: delete recursively
            val deleted = attachmentsDir.deleteRecursively()
            if (deleted) {
                log.info { "[ACP] Cleaned up ${attachmentsDir.absolutePath}" }
            } else {
                log.warn { "[ACP] Partial deletion of ${attachmentsDir.absolutePath} — some files may be locked" }
            }
        }
    }
}
