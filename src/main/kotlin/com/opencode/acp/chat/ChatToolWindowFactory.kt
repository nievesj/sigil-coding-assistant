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
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.util.edtScope
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<OpenCodeService>()
        val scope = edtScope()
        val viewModel = ChatViewModel(scope, service)

        // addComposeTab() automatically handles SwingBridgeTheme, enableNewSwingCompositing(),
        // and JewelComposePanel creation — no explicit SwingBridgeTheme {} wrapper needed.
        toolWindow.addComposeTab("") {
            ChatScreen(viewModel, project)
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
        }

        // Register disposable for ViewModel cleanup on tool window close.
        // The service (OpenCodeService) is NOT disposed here — it lives as long as the project.
        com.intellij.openapi.util.Disposer.register(toolWindow.contentManager, object : com.intellij.openapi.Disposable {
            override fun dispose() {
                scope.cancel()
                viewModel.close()
            }
        })

        // Auto-connect on tool window open
        scope.launch {
            viewModel.initialize(project.basePath ?: ".")
        }
    }
}
