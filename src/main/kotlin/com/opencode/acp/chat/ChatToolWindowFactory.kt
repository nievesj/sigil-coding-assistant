package com.opencode.acp.chat

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.ui.compose.ChatScreen
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.chat.viewmodel.ChatViewModel
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        @Volatile
        var activeComposePanel: androidx.compose.ui.awt.ComposePanel? = null
            internal set

        /** Synchronous dispose — for Content disposer (tool window close). */
        fun disposeActiveComposePanel() {
            activeComposePanel?.let {
                try { it.isVisible = false; it.dispose() } catch (_: Exception) {}
                activeComposePanel = null
            }
        }

        /** Async dispose — for OpenCodeService.dispose() during IDE restart.
         *  ComposePanel.dispose() can block EDT if Skiko is mid-frame.
         *  Daemon thread ensures EDT is NEVER blocked. */
        fun disposeActiveComposePanelAsync() {
            val panel = activeComposePanel ?: return
            activeComposePanel = null
            Thread({
                try { panel.isVisible = false; panel.dispose() } catch (_: Exception) {}
            }, "opencode-compose-dispose").apply { isDaemon = true; start() }
        }

    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Force Skiko software rendering BEFORE any ComposePanel is created.
        // Skiko on Windows defaults to Direct3D (DirectX 12). The GPU context
        // can hang during JVM exit if the render thread is mid-frame, preventing
        // IDE restart after plugin update. Software rendering has no GPU resources.
        System.setProperty("skiko.renderApi", "SOFTWARE")

        val service = project.service<OpenCodeService>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = ChatViewModel(scope, service, project)

        toolWindow.addComposeTab("") {
            ChatTheme {
                ChatScreen(viewModel, project)
            }
        }

        val content = toolWindow.contentManager.contents.firstOrNull()
        if (content != null) {
            val component = content.component
            val composePanelRef = findComposePanel(component)
            activeComposePanel = composePanelRef

            val pasteAction = DumbAwareAction.create { viewModel.requestImagePaste() }
            val pasteShortcut = CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), null),
            )
            pasteAction.registerCustomShortcutSet(pasteShortcut, component, project)

            content.setDisposer(object : com.intellij.openapi.Disposable {
                override fun dispose() {
                    val logger = com.intellij.openapi.diagnostic.Logger.getInstance("ACP")
                    logger.info("[ACP] ContentDisposer: disposing tool window content")
                    scope.cancel()
                    viewModel.close()
                    try { component.isVisible = false } catch (_: Exception) {}
                    // CRITICAL: Do NOT call ComposePanel.dispose() synchronously on EDT.
                    // Skiko's render thread may be mid-frame, causing EDT to block → IDE lockup.
                    // Use async dispose on a daemon thread (same pattern as disposeActiveComposePanelAsync).
                    val panel = composePanelRef ?: activeComposePanel
                    activeComposePanel = null
                    if (panel != null) {
                        logger.info("[ACP] ContentDisposer: async disposing ComposePanel=$panel")
                        Thread({
                            try {
                                panel.isVisible = false
                                panel.dispose()
                            } catch (e: Exception) {
                                logger.warn("[ACP] ContentDisposer: ComposePanel.dispose() failed: ${e.message}")
                            }
                        }, "opencode-compose-dispose").apply { isDaemon = true; start() }
                    } else {
                        logger.warn("[ACP] ContentDisposer: composePanelRef is null!")
                    }
                    logger.info("[ACP] ContentDisposer: done")
                }
            })
        }

        val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance()
        if (settings.autoConnect) {
            scope.launch { viewModel.initialize(project.basePath) }
        }
    }

    private fun findComposePanel(container: java.awt.Container): androidx.compose.ui.awt.ComposePanel? {
        for (child in container.components) {
            if (child is androidx.compose.ui.awt.ComposePanel) return child
            if (child is java.awt.Container) {
                val found = findComposePanel(child)
                if (found != null) return found
            }
        }
        return null
    }
}
