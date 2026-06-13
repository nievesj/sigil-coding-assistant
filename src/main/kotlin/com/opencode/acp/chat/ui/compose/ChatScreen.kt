package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.ChatInputState
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.SelectionResponse
import com.opencode.acp.chat.model.SidebarTab
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Computes recent files: currently open editors first, then recently closed files.
 */
private fun computeRecentFiles(project: Project): List<RecentFile> {
    val openFiles = FileEditorManager.getInstance(project).openFiles
        .filter { it.isValid && !it.isDirectory }
        .map { RecentFile(name = it.name, path = it.path) }

    val openPaths = openFiles.map { it.path }.toSet()

    val closedFiles = com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project).fileList
        .filter { it.isValid && !it.isDirectory && it.path !in openPaths }
        .takeLast(15)
        .map { RecentFile(name = it.name, path = it.path) }

    return openFiles + closedFiles
}

/**
 * Searches project files by name using IntelliJ's FilenameIndex.
 * First tries exact match, then falls back to iterating project scopes for partial matches.
 */
fun searchProjectFiles(project: Project, query: String, maxResults: Int = 20): List<RecentFile> {
    if (query.isBlank()) return emptyList()
    val results = mutableListOf<RecentFile>()
    val seen = mutableSetOf<String>()

    // Exact filename match first
    com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(query, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
        .filter { it.isValid && !it.isDirectory }
        .take(maxResults)
        .forEach { vf ->
            if (vf.path !in seen) {
                seen.add(vf.path)
                results.add(RecentFile(name = vf.name, path = vf.path))
            }
        }

    // If we have enough results, return early
    if (results.size >= maxResults) return results.take(maxResults)

    // Local file system for VFS operations
    val localFileSystem = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
    // Fall back to project base path for partial match traversal
    val basePath = project.basePath ?: return results
    val baseDir = localFileSystem.findFileByPath(basePath) ?: return results

    fun searchDir(dir: VirtualFile) {
        if (results.size >= maxResults) return
        val children = dir.children ?: return
        for (child in children) {
            if (results.size >= maxResults) return
            if (child.isDirectory) {
                // Skip hidden and build directories
                if (!child.name.startsWith(".") && child.name !in setOf("build", "node_modules", ".git", "out", "target")) {
                    searchDir(child)
                }
            } else if (child.isValid) {
                val nameLower = child.name.lowercase()
                if (nameLower.contains(query.lowercase()) && child.path !in seen) {
                    seen.add(child.path)
                    results.add(RecentFile(name = child.name, path = child.path))
                }
            }
        }
    }

    // If exact match didn't find enough, do partial search
    if (results.size < maxResults) {
        searchDir(baseDir)
    }

    return results.take(maxResults)
}

@Composable
fun
        ChatScreen(
    viewModel: ChatViewModel,
    project: Project
) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val controlState by viewModel.controlState.collectAsState()
    val inputState by viewModel.inputState.collectAsState()
    val isStreaming = inputState is ChatInputState.Streaming
    val permissionPrompt = (inputState as? ChatInputState.AwaitingPermission)?.prompt
    val selectionPrompt = (inputState as? ChatInputState.AwaitingSelection)?.prompt
    val sessionListState by viewModel.sessionListState.collectAsState()
    val isSidebarVisible by viewModel.isSidebarVisible.collectAsState()
    val sessionContextState by viewModel.sessionContextState.collectAsState()
    val todoItems by viewModel.todoItems.collectAsState()
    val streamingSessionIds by viewModel.streamingSessionIds.collectAsState()
    val pendingCreationSessionIds by viewModel.pendingCreationSessionIds.collectAsState()
    val availableCommands by viewModel.availableCommands.collectAsState()
    val commandHistory by viewModel.commandHistory.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    var selectedSidebarTab by remember { mutableStateOf(SidebarTab.SESSIONS) }

    // Local (non-server) slash commands — always shown first
    val localCommands = remember {
        listOf(
            SlashCommand("clear", "Start a new session", AllIconsKeys.General.Add),
            SlashCommand("cancel", "Cancel current response", AllIconsKeys.Actions.Suspend),
        )
    }
    // Merged list: local commands first, then server commands
    val allSlashCommands = localCommands + availableCommands
    val scope = rememberCoroutineScope()
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }
    var previewImageUri by remember { mutableStateOf<String?>(null) }

    // Recent files — reactive state that updates when files open/close
    val recentFiles = remember { mutableStateListOf<RecentFile>() }

    // Clear-all confirmation dialog state
    var showClearAllDialog by remember { mutableStateOf(false) }
    val clearAllState by viewModel.clearAllState.collectAsState()

    // Populate initially and subscribe to file editor changes
    LaunchedEffect(project) {
        // Initial load — use submit() + get() on IO dispatcher instead of
        // executeSynchronously() which wraps in a non-cancellable runReadAction
        // that blocks write actions (settings dialog, plugin updater).
        val initial = withContext(Dispatchers.IO) {
            readAction { computeRecentFiles(project) }
        }
        recentFiles.clear()
        recentFiles.addAll(initial)

        // Listen for file open/close events to update the list reactively
        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        readAction { computeRecentFiles(project) }
                    }
                    recentFiles.clear()
                    recentFiles.addAll(updated)
                }
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        readAction { computeRecentFiles(project) }
                    }
                    recentFiles.clear()
                    recentFiles.addAll(updated)
                }
            }
        })

        // Keep LaunchedEffect alive until cancelled; disconnect bus on dispose
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }

    // Listen for Ctrl+V paste signal from IntelliJ's action system.
    // IntelliJ consumes Ctrl+V before Compose's onPreviewKeyEvent, so we
    // register an AnAction with Ctrl+V shortcut on the tool window content
    // (see ChatToolWindowFactory). When triggered, it checks the clipboard
    // for images, files, or text and handles each case.
    LaunchedEffect(viewModel) {
        viewModel.pasteImageSignal.collectLatest {
            when (val result = readClipboardContent()) {
                is ClipboardResult.FileResult -> {
                    attachedFiles.add(result.file)
                }
                is ClipboardResult.TextResult -> {
                    viewModel.requestTextPaste(result.text)
                }
                null -> { /* nothing on clipboard */ }
            }
        }
    }

    // "Files and Folders" — opens generic file chooser
    val onFilesAndFolders: () -> Unit = {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Attach File")
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = chooser.choose(project)
        files.forEach { file ->
            addFileAttachment(file, attachedFiles)
        }
    }

    // "Image..." — opens image-only file chooser
    val onImage: () -> Unit = {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Attach Image")
            .withFileFilter { file ->
                val ext = file.extension?.lowercase() ?: ""
                ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")
            }
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
        val files = chooser.choose(project)
        files.forEach { file ->
            addFileAttachment(file, attachedFiles)
        }
    }

    // Recent file click — attach a recent file
    val onRecentFileClick: (RecentFile) -> Unit = { recentFile ->
        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        var vf = vfs.findFileByPath(recentFile.path)
        if (vf == null) {
            // Try via URL for non-local paths (jar://, etc.)
            vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://${recentFile.path}")
        }
        if (vf != null) {
            addFileAttachment(vf, attachedFiles)
        }
    }

    val onRemoveFile: (Int) -> Unit = { index -> attachedFiles.removeAt(index) }

    // Search results for project files — computed when user types in attach menu search
    val searchResults = remember { mutableStateListOf<RecentFile>() }
    val onSearch: (String) -> Unit = { query ->
        if (query.isBlank()) {
            searchResults.clear()
        } else {
            scope.launch {
                val results = withContext(Dispatchers.IO) {
                    readAction { searchProjectFiles(project, query) }
                }
                searchResults.clear()
                searchResults.addAll(results)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Show splash screen when not connected
        if (connectionState != ConnectionState.CONNECTED) {
            ConnectionSplashScreen(
                connectionState = connectionState,
                onConnect = { 
                    viewModel.scope.launch { viewModel.connect(project.basePath) }
                },
                onRetry = { 
                    viewModel.scope.launch { viewModel.retryConnection(project.basePath) }
                },
                onStop = { 
                    viewModel.stopConnection()
                },
                onAutoConnectChanged = { enabled ->
                    // Auto-connect setting is persisted in the settings
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Main chat UI
            Column(Modifier.fillMaxSize()) {
                // Top section: sidebar + chat area
                Row(modifier = Modifier.weight(1f)) {
                    // Sidebar — animated width for show/hide and tab switching
                    val sidebarTargetWidth = if (isSidebarVisible) {
                        when (selectedSidebarTab) {
                            SidebarTab.SESSIONS -> ChatTheme.dims.sidebarWidth
                            SidebarTab.CONTEXT -> ChatTheme.dims.sidebarContextWidth
                            SidebarTab.REVIEW -> ChatTheme.dims.sidebarReviewWidth
                        }
                    } else 0.dp
                    val sidebarWidth by animateDpAsState(
                        targetValue = sidebarTargetWidth,
                        label = "sidebarWidth"
                    )

                    SessionSidebar(
                        state = sessionListState,
                        contextState = sessionContextState,
                        selectedTab = selectedSidebarTab,
                        onTabSelected = { selectedSidebarTab = it },
                        onNewSession = { viewModel.scope.launch { viewModel.createAndSwitchSession() } },
                        onSessionSelected = { viewModel.scope.launch { viewModel.switchSession(it) } },
                        onSessionArchived = { viewModel.scope.launch { viewModel.archiveSession(it) } },
                        onRetry = { viewModel.scope.launch { viewModel.loadSessions() } },
                        onContextRetry = { viewModel.retryContextFetch() },
                        onShowDetails = { /* Context tab is already showing */ },
                        onLoadMore = { viewModel.loadMoreSessions() },
                        onClearAll = {
                            val loaded = sessionListState as? com.opencode.acp.chat.model.SessionListState.Loaded
                            if (loaded != null && loaded.topLevelSessions.size > 1) {
                                showClearAllDialog = true
                            }
                        },
                        project = project,
                        modifier = Modifier.width(sidebarWidth),
                        fileChangeSignal = viewModel.fileChangeSignal,
                        streamingSessionIds = streamingSessionIds,
                        pendingCreationSessionIds = pendingCreationSessionIds,
                        clearAllState = clearAllState,
                    )
                    // Main chat area
                    Column(modifier = Modifier.weight(1f)) {
                        ChatHeader(
                            isSidebarVisible = isSidebarVisible,
                            onToggleSidebar = { viewModel.toggleSidebar() }
                        )
                        // Connection banner (shows/hides based on state)
                        ConnectionBanner(
                            state = connectionState,
                            onRetry = { viewModel.scope.launch { viewModel.retryConnection(project.basePath) } }
                        )

                        // Message list (fills remaining space)
                        MessageList(
                            messages = messages.values.toList(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            project = project,
                            onImagePreview = { uri -> previewImageUri = uri },
                            getStreamingText = { sessionId -> viewModel.getStreamingText(sessionId) },
                            queuedMessages = queuedMessages,
                            onCancelQueuedMessage = { msgId -> viewModel.removeQueuedMessage(msgId) },
                        )
                    }
                }

                // Bottom section spans full width (including sidebar)
                // Permission prompt (shows/hides based on state)
                permissionPrompt?.let { prompt ->
                    PermissionPrompt(
                        prompt = prompt,
                        onRespond = { response ->
                            viewModel.scope.launch { viewModel.respondPermission(response) }
                        }
                    )
                }

                // Selection prompt (shows/hides based on state)
                selectionPrompt?.let { prompt ->
                    SelectionPrompt(
                        prompt = prompt,
                        onSubmit = { response -> viewModel.respondSelection(response) },
                        onDismiss = { viewModel.respondSelection(SelectionResponse(emptySet())) }
                    )
                }

                // Input area (always visible at bottom, disabled when disconnected or prompt active)
                val inputEnabled = inputState !is ChatInputState.Disabled
                InputArea(
                    enabled = inputEnabled,
                isStreaming = isStreaming,
                controlState = controlState,
                contextState = sessionContextState,
                onSend = { text, files ->
                    // Capture a snapshot BEFORE clearing — attachedFiles is a mutable list
                    // and InputArea passes the same reference. If we clear first, the coroutine
                    // would find an empty list by the time it runs.
                    val fileSnapshot = files.toList()
                    attachedFiles.clear()
                    viewModel.scope.launch {
                        if (isStreaming) {
                            val queueMode = OpenCodeSettingsState.getInstance().queueInsteadOfSteer
                            if (queueMode) {
                                // Queue mode: hold the message, auto-send when current response completes
                                viewModel.queueMessage(text, fileSnapshot)
                            } else {
                                // Steer mode (legacy): abort current response and send immediately
                                viewModel.steerMessage(text, fileSnapshot)
                            }
                        } else {
                            viewModel.sendMessage(text, fileSnapshot)
                        }
                    }
                },
                onCancel = { viewModel.scope.launch { viewModel.cancel() } },
                onAgentChanged = { viewModel.selectAgent(it) },
                onModelChanged = { viewModel.selectModel(it) },
                onThinkingChanged = { viewModel.selectThinkingEffort(it) },
                onShowContextDetails = {
                    // Open sidebar and switch to context tab
                    if (!isSidebarVisible) viewModel.toggleSidebar()
                    selectedSidebarTab = SidebarTab.CONTEXT
                },
                onRetryContext = { viewModel.retryContextFetch() },
                attachedFiles = attachedFiles,
                onAttachFile = { file -> attachedFiles.add(file) },
                onRemoveFile = onRemoveFile,
                onImagePasted = { file -> attachedFiles.add(file) },
                onImagePreview = { uri -> previewImageUri = uri },
                pasteTextSignal = viewModel.pasteTextSignal,
                recentFiles = recentFiles,
                searchResults = searchResults,
                onSearch = onSearch,
                onFilesAndFolders = onFilesAndFolders,
                onImage = onImage,
                onRecentFileClick = onRecentFileClick,
                onSlashCommand = { command ->
                    viewModel.scope.launch {
                        when (command.name) {
                            "clear" -> viewModel.createAndSwitchSession()
                            "cancel" -> viewModel.cancel()
                            else -> viewModel.executeServerCommand(command.name)
                        }
                    }
                },
                commands = allSlashCommands,
                todos = todoItems,
                commandHistory = commandHistory,
                onLoadHistoryEntry = { entry ->
                    attachedFiles.clear()
                    attachedFiles.addAll(entry.toAttachedFiles())
                },
            )
            }
        }

        // Image preview overlay — centered in the entire plugin window.
        // Only the dark background dismisses the preview; clicking the image itself does not.
        previewImageUri?.let { uri ->
            val bitmap = remember(uri) { decodeDataUriToBitmap(uri) }
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChatTheme.colors.accent.overlaySemiTransparent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { previewImageUri = null },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposeImage(
                        bitmap = bitmap,
                        contentDescription = "Preview",
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .fillMaxHeight(0.85f)
                            .clip(ChatTheme.shapes.overlayCornerRadius)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { /* Consume click — don't dismiss preview when clicking the image */ },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // Clear all sessions confirmation dialog
        if (showClearAllDialog) {
            val loaded = sessionListState as? com.opencode.acp.chat.model.SessionListState.Loaded
            val countToDelete = (loaded?.topLevelSessions?.size ?: 0) - 1  // exclude active
            ClearAllConfirmationDialog(
                sessionCount = countToDelete.coerceAtLeast(0),
                onConfirm = { viewModel.scope.launch { viewModel.clearAllSessions() } },
                onDismiss = { showClearAllDialog = false },
            )
        }
    }
}

/**
 * Reads a VirtualFile into an AttachedFile and adds it to the list.
 */
private fun addFileAttachment(
    file: VirtualFile,
    attachedFiles: MutableList<AttachedFile>
) {
    try {
        val bytes = file.contentsToByteArray()
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val mime = com.opencode.acp.util.MimeTypes.guessFromFileName(file.name)
        val dataUri = "data:$mime;base64,$base64"
        attachedFiles.add(AttachedFile(name = file.name, path = file.path, mime = mime, dataUri = dataUri))
    } catch (_: Exception) {
        // Skip files that can't be read
    }
}
