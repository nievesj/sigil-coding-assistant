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
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<OpenCodeService>()
        // Use Dispatchers.Default (not EDT) so initialization HTTP calls
        // (health check, list agents/providers) don't block the UI thread.
        // StateFlow updates are thread-safe — Compose recomposes on EDT automatically.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = ChatViewModel(scope, service, project)

        // addComposeTab() automatically handles SwingBridgeTheme, enableNewSwingCompositing(),
        // and JewelComposePanel creation — no explicit SwingBridgeTheme {} wrapper needed.
        toolWindow.addComposeTab("") {
            ChatTheme {
                ChatScreen(viewModel, project)
            }
        }

        // Register Ctrl+V / Cmd+V action on the tool window content component.
        // IntelliJ's action system consumes Ctrl+V before it reaches Compose's onPreviewKeyEvent,
        // so we must intercept at the IDE level and signal the Compose UI.
        val content = toolWindow.contentManager.contents.firstOrNull()
        if (content != null) {
            val component = content.component
            val pasteAction = DumbAwareAction.create {
                viewModel.requestImagePaste()
            }
            val pasteShortcut = CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), null),
            )
            pasteAction.registerCustomShortcutSet(pasteShortcut, component, project)

            // CRITICAL: Set a proper Content disposer.
            // addComposeTab() does NOT set Content.setDisposer(), which means the
            // ComposePanel's Skiko rendering thread is never properly disposed during
            // IDE shutdown/plugin update. Skiko holds Swing's treeLock during paint,
            // and the IDE's disposal sequence needs treeLock too — causing a deadlock
            // that freezes the IDE until the user manually closes the plugin window.
            // By setting a disposer, IntelliJ's disposal system calls dispose() on the
            // content before completing shutdown, which stops Skiko's rendering thread.
            content.setDisposer(object : com.intellij.openapi.Disposable {
                override fun dispose() {
                    // Cancel the ViewModel's coroutine scope first — stops all background
                    // work (SSE, HTTP calls, event processing).
                    scope.cancel()
                    viewModel.close()
                    // Dispose the ComposePanel explicitly — stops Skiko's rendering thread
                    // and releases Swing treeLock. Without this, the IDE hangs during
                    // shutdown/plugin update because Skiko's paint loop holds treeLock
                    // while the IDE tries to acquire a write lock for disposal.
                    disposeComposePanel(component)
                }
            })
        }

        // Auto-connect on tool window open (if setting enabled)
        val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance()
        if (settings.autoConnect) {
            scope.launch {
                viewModel.initialize(project.basePath)
            }
        }
    }

    companion object {
        /**
         * Dispose the [ComposePanel][androidx.compose.ui.awt.ComposePanel] inside
         * a Jewel container by traversing the Swing component hierarchy.
         *
         * [JewelToolWindowComposePanel] wraps a JPanel containing a ComposePanel
         * as its child. The ComposePanel's Skiko rendering thread must be stopped
         * explicitly — it does not auto-dispose during IDE shutdown because
         * `addComposeTab()` never sets `Content.setDisposer()`.
         */
        private fun disposeComposePanel(container: java.awt.Container?) {
            if (container == null) return
            for (child in container.components) {
                if (child is androidx.compose.ui.awt.ComposePanel) {
                    try {
                        child.dispose()
                    } catch (_: Exception) {
                        // ComposePanel.dispose() may throw if already disposed
                        // or if Skiko's renderer is in a bad state during shutdown.
                    }
                    return
                }
                if (child is java.awt.Container) {
                    disposeComposePanel(child)
                }
            }
        }
    }
}
