package com.opencode.acp.chat

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

/**
 * Listens for IDE shutdown/restart and disposes the ComposePanel BEFORE
 * the JVM starts its shutdown sequence. This ensures Skiko's render thread
 * is stopped before the JVM waits for non-daemon threads — preventing
 * the "IDE won't restart" hang.
 *
 * Unlike OpenCodeService.dispose() (which may not be called during restart),
 * AppLifecycleListener.appWillBeClosed() is ALWAYS called before System.exit().
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ShutdownListener : AppLifecycleListener {

    private val logger = Logger.getInstance("ACP")

    override fun appWillBeClosed(isRestart: Boolean) {
        logger.info("[ACP] ShutdownListener.appWillBeClosed(isRestart=$isRestart)")
        if (isRestart) {
            // CRITICAL: Dispose ComposePanel on a daemon thread, NOT on EDT.
            // ComposePanel.dispose() blocks if Skiko's render thread is mid-frame,
            // causing EDT to freeze → IDE lockup.
            val panel = ChatToolWindowFactory.activeComposePanel
            ChatToolWindowFactory.activeComposePanel = null
            if (panel != null) {
                logger.info("[ACP] ShutdownListener: async disposing ComposePanel=$panel")
                Thread({
                    try {
                        panel.isVisible = false
                        panel.dispose()
                    } catch (e: Exception) {
                        logger.warn("[ACP] ShutdownListener: ComposePanel.dispose() failed: ${e.message}")
                    }
                }, "opencode-compose-shutdown-dispose").apply { isDaemon = true; start() }
            }
        }
    }
}