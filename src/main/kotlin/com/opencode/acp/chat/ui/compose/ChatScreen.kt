package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    project: Project
) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val controlState by viewModel.controlState.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val permissionPrompt by viewModel.permissionPrompt.collectAsState()
    val scope = rememberCoroutineScope()
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }

    val onAttach: () -> Unit = {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Attach File")
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = chooser.choose(project)
        files.forEach { file ->
            val bytes = file.contentsToByteArray()
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            val mime = java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val dataUri = "data:$mime;base64,$base64"
            attachedFiles.add(AttachedFile(name = file.name, path = file.path, mime = mime, dataUri = dataUri))
        }
    }

    val onRemoveFile: (Int) -> Unit = { index -> attachedFiles.removeAt(index) }

    Column(Modifier.fillMaxSize()) {
        // Connection banner (shows/hides based on state)
        ConnectionBanner(
            state = connectionState,
            onRetry = { scope.launch { viewModel.initialize(project.basePath ?: ".") } }
        )

        // Message list (fills remaining space)
        MessageList(
            messages = messages,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            project = project
        )

        // Permission prompt (shows/hides based on state)
        permissionPrompt?.let { prompt ->
            PermissionPrompt(
                prompt = prompt,
                onRespond = { response ->
                    scope.launch { viewModel.respondPermission(response) }
                }
            )
        }

        // Input area (always visible at bottom, disabled when disconnected or permission active)
        val inputEnabled = connectionState == ConnectionState.CONNECTED && permissionPrompt == null
        InputArea(
            enabled = inputEnabled,
            isStreaming = isStreaming,
            controlState = controlState,
            onSend = { text ->
                scope.launch {
                    viewModel.sendMessage(text, attachedFiles.toList())
                    attachedFiles.clear()
                }
            },
            onCancel = { scope.launch { viewModel.cancel() } },
            onAgentChanged = { viewModel.selectAgent(it) },
            onModelChanged = { viewModel.selectModel(it) },
            onThinkingChanged = { viewModel.selectThinkingEffort(it) },
            attachedFiles = attachedFiles,
            onAttach = onAttach,
            onRemoveFile = onRemoveFile
        )
    }
}