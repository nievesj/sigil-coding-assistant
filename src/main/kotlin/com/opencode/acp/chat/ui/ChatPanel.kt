package com.opencode.acp.chat.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.util.edtScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel

class ChatPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {
    private val scope = edtScope()
    private val viewModel = ChatViewModel(scope)
    private val messageList = MessageListComponent()
    private val inputArea = InputAreaComponent(
        onSend = { text -> scope.launch { viewModel.sendMessage(text) } },
        onCancel = { scope.launch { viewModel.cancel() } }
    )
    private val controlBar = ControlBarComponent(
        onAgentChanged = { viewModel.selectAgent(it) },
        onModelChanged = { viewModel.selectModel(it) },
        onThinkingChanged = { viewModel.selectThinkingEffort(it) }
    )
    private val connectionBanner = ConnectionBannerComponent(
        onRetry = { scope.launch { viewModel.initialize(project.basePath ?: ".") } }
    )

    init {
        Disposer.register(parentDisposable, this)

        // Wire ViewModel flows -> Swing updates
        scope.launch {
            viewModel.messages.collect { messages ->
                messageList.syncMessages(messages)
            }
        }
        scope.launch {
            viewModel.connectionState.collect { state ->
                connectionBanner.updateState(state)
                inputArea.setInputEnabled(state == ConnectionState.CONNECTED)
            }
        }
        scope.launch {
            viewModel.controlState.collect { state ->
                controlBar.updateState(state)
            }
        }
        scope.launch {
            viewModel.isStreaming.collect { streaming ->
                inputArea.showCancelMode(streaming)
            }
        }

        // Assemble layout
        add(connectionBanner, BorderLayout.NORTH)
        add(messageList.component, BorderLayout.CENTER)

        val southPanel = JPanel(BorderLayout())
        southPanel.add(controlBar, BorderLayout.NORTH)
        southPanel.add(inputArea, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)

        // Auto-connect on tool window open
        scope.launch {
            viewModel.initialize(project.basePath ?: ".")
        }
    }

    fun getViewModel(): ChatViewModel = viewModel

    override fun dispose() {
        scope.cancel()
        viewModel.close()
    }
}
