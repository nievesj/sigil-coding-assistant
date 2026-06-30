package com.opencode.acp.chat

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.project.ProjectManager
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Listens for IDE shutdown/restart and disposes the ComposePanel BEFORE
 * the JVM starts its shutdown sequence. This ensures Skiko's render thread
 * is stopped before the JVM waits for non-daemon threads — preventing
 * the "IDE won't restart" hang.
 *
 * Unlike OpenCodeService.dispose() (which may not be called during restart),
 * AppLifecycleListener.appWillBeClosed() is ALWAYS called before System.exit().
 *
 * Also cleans up `.opencode/attachments/` directories for all open projects
 * on IDE shutdown, plus the `user.home/.opencode/attachments/` fallback
 * directory used by detached projects.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ShutdownListener : AppLifecycleListener {

    private val logger = KotlinLogging.logger {}

    override fun appWillBeClosed(isRestart: Boolean) {
        logger.info { "[ACP] ShutdownListener.appWillBeClosed(isRestart=$isRestart)" }

        // Clean up attachment directories for all open projects
        try {
            ProjectManager.getInstance().openProjects.forEach { project ->
                AttachmentCleanupListener.cleanupAttachments(project)
            }
        } catch (e: Exception) {
            logger.warn { "[ACP] ShutdownListener: attachment cleanup failed: ${e.message}" }
        }

        // Clean up user.home/.opencode/attachments/ (orphan clipboard images from detached projects)
        try {
            val userHome = System.getProperty("user.home")
            if (userHome != null) {
                val opencodeDir = File(userHome, ".opencode")
                val orphanDir = File(opencodeDir, "attachments")
                if (orphanDir.exists() && orphanDir.name == "attachments") {
                    if (java.nio.file.Files.isSymbolicLink(opencodeDir.toPath())) {
                        logger.warn { "[ACP] Skipping user.home cleanup — .opencode is a symlink: ${opencodeDir.absolutePath}" }
                    } else if (java.nio.file.Files.isSymbolicLink(orphanDir.toPath())) {
                        if (orphanDir.delete()) {
                            logger.info { "[ACP] Cleaned up user.home symlink ${orphanDir.absolutePath}" }
                        }
                    } else {
                        // Safety check: ensure the canonical path is still inside user.home
                        // before deleting. This catches symlinks on parent directories that
                        // the isSymbolicLink check on opencodeDir would miss.
                        val canonicalOrphan = orphanDir.canonicalFile
                        val canonicalHome = File(userHome).canonicalFile
                        if (canonicalOrphan.path.startsWith(canonicalHome.path + File.separator) ||
                            canonicalOrphan.path == canonicalHome.path) {
                            if (orphanDir.deleteRecursively()) {
                                logger.info { "[ACP] Cleaned up user.home ${orphanDir.absolutePath}" }
                            } else {
                                logger.warn { "[ACP] Partial deletion of user.home ${orphanDir.absolutePath}" }
                            }
                        } else {
                            logger.warn { "[ACP] Skipping user.home cleanup — canonical path escapes user.home: ${canonicalOrphan.path}" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn { "[ACP] ShutdownListener: user.home attachment cleanup failed: ${e.message}" }
        }

        if (isRestart) {
            // CRITICAL: Dispose ComposePanel on a daemon thread, NOT on EDT.
            // ComposePanel.dispose() blocks if Skiko's render thread is mid-frame,
            // causing EDT to freeze → IDE lockup.
            // Use the per-project async dispose API — disposes ALL registered panels
            // across all projects on daemon threads, then clears the registry.
            logger.info { "[ACP] ShutdownListener: async disposing all ComposePanels" }
            ChatToolWindowFactory.disposeActiveComposePanelAsync()
        }
    }
}