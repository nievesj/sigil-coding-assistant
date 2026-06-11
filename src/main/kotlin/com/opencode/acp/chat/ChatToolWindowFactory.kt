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
        /** Reference to the active ComposePanel for JVM shutdown hook cleanup. */
        @Volatile
        private var activeComposePanel: androidx.compose.ui.awt.ComposePanel? = null

        private fun disposeComposePanel(container: java.awt.Container?) {
            if (container == null) return
            for (child in container.components) {
                if (child is androidx.compose.ui.awt.ComposePanel) {
                    try { child.dispose() } catch (_: Exception) { }
                    return
                }
                if (child is java.awt.Container) {
                    disposeComposePanel(child)
                }
            }
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
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

            // Find and store the ComposePanel reference for shutdown cleanup.
            val composePanelRef = findComposePanel(component)
            activeComposePanel = composePanelRef

            val pasteAction = DumbAwareAction.create {
                viewModel.requestImagePaste()
            }
            val pasteShortcut = CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), null),
            )
            pasteAction.registerCustomShortcutSet(pasteShortcut, component, project)

            content.setDisposer(object : com.intellij.openapi.Disposable {
                override fun dispose() {
                    scope.cancel()
                    viewModel.close()
                    // Stop Skiko rendering before disposing. Setting isVisible=false
                    // tells Skiko to skip rendering, letting the render thread finish
                    // its current frame so dispose() completes quickly.
                    try { component.isVisible = false } catch (_: Exception) {}
                    composePanelRef?.let {
                        try { it.dispose() } catch (_: Exception) {}
                    }
                    activeComposePanel = null
                }
            })
        }

        // Register a JVM shutdown hook that disposes the ComposePanel if it
        // hasn't been disposed yet. IntelliJ's restart sequence may NOT call
        // Content.dispose() — it might just close projects and exit the JVM.
        // Without this hook, Skiko's non-daemon rendering thread keeps running
        // and prevents the JVM from exiting, blocking the restart.
        // Safe for restart (JVM is exiting anyway) and only triggers if the
        // Content disposer hasn't already cleaned up.
        Runtime.getRuntime().addShutdownHook(Thread({
            activeComposePanel?.let {
                try { it.dispose() } catch (_: Exception) {}
                activeComposePanel = null
            }
        }, "opencode-compose-shutdown").apply { isDaemon = true })

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
