package com.opencode.acp.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.opencode.acp.chat.ui.compose.ChatScreen
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.util.edtScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val scope = edtScope()
        val viewModel = ChatViewModel(scope)

        // addComposeTab() automatically handles SwingBridgeTheme, enableNewSwingCompositing(),
        // and JewelComposePanel creation — no explicit SwingBridgeTheme {} wrapper needed.
        toolWindow.addComposeTab("Chat") {
            ChatScreen(viewModel, project)
        }

        // Register disposable for ViewModel cleanup
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