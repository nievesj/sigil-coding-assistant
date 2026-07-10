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
import androidx.compose.runtime.State
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.ChatInputState
import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.ReadyState
import com.opencode.acp.chat.model.SelectionResponse
import com.opencode.acp.chat.model.SidebarTab
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.util.decodeFileToBitmap
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // NOTE: EditorHistoryManager is an internal `impl` package API and may change
    // or be removed on platform upgrades. Wrap in try/catch so the plugin degrades
    // gracefully (returns empty list for closed files) instead of crashing.
    val closedFiles = try {
        com.intellij.openapi.fileEditor.impl.EditorHistoryManager.getInstance(project).fileList
            .filter { it.isValid && !it.isDirectory && it.path !in openPaths }
            .takeLast(15)
            .map { RecentFile(name = it.name, path = it.path) }
    } catch (e: NoClassDefFoundError) {
        io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (NoClassDefFoundError)" }
        emptyList()
    } catch (e: LinkageError) {
        io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (LinkageError)" }
        emptyList()
    } catch (e: Exception) {
        io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] EditorHistoryManager unavailable (Exception)" }
        emptyList()
    }

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

    // Bounded recursive traversal: depth limit prevents deep recursion on
    // huge directory trees, and maxNodes caps total nodes (directories + files)
    // inspected to avoid holding the read lock for too long on large projects.
    val maxDepth = 10
    val maxNodes = 5000
    var visited = 0

    fun searchDir(dir: VirtualFile, depth: Int) {
        if (results.size >= maxResults || depth > maxDepth || visited >= maxNodes) return
        visited++
        // Check for cancellation periodically to allow write actions to interrupt
        // the read action. Without this, holding the read lock for 5000 nodes
        // can starve write actions (file saves, project config changes).
        if (visited % 500 == 0) {
            // ProgressManager.checkCanceled() throws ProcessCanceledException
            // which must be rethrown, not swallowed.
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
        }
        val children = dir.children ?: return
        for (child in children) {
            if (results.size >= maxResults || visited >= maxNodes) return
            if (child.isDirectory && !isSymLink(child)) {
                // Skip hidden and build directories; don't follow symlinks (path traversal / cycles)
                if (!child.name.startsWith(".") && child.name !in setOf("build", "node_modules", ".git", "out", "target", ".idea", "__pycache__", ".gradle", "dist", ".next", ".venv")) {
                    searchDir(child, depth + 1)
                }
            } else if (child.isValid && !isSymLink(child)) {
                visited++
                val nameLower = child.name.lowercase()
                if (nameLower.contains(query.lowercase()) && child.path !in seen) {
                    // Reject symlinks that escape the project base path.
                    // On canonicalization failure, reject the file — a path that can't be
                    // canonicalized is untrusted (broken symlink, restricted path, or a
                    // symlink that resolves outside the project). Falling back to the raw
                    // path would bypass the boundary check via symlinks.
                    val canonicalChild = try { java.io.File(child.path).canonicalPath } catch (_: Exception) { continue }
                    val canonicalBase = try { java.io.File(basePath).canonicalPath } catch (_: Exception) { basePath }
                    if (canonicalChild.startsWith(canonicalBase + java.io.File.separator) || canonicalChild == canonicalBase) {
                        seen.add(child.path)
                        results.add(RecentFile(name = child.name, path = child.path))
                    }
                }
            }
        }
    }

    // If exact match didn't find enough, do partial search
    if (results.size < maxResults) {
        searchDir(baseDir, 0)
    }

    return results.take(maxResults)
}

/**
 * Returns true if [file] is a symlink. Uses `java.nio.file.Files.isSymbolicLink`
 * on the local path so it works against the public [VirtualFile] API (the
 * `isSymlink()` method is only on the internal `VirtualFileSystemEntry` impl).
 * Non-local VFS schemes (jar://, http://, etc.) cannot be symlinks.
 */
private fun isSymLink(file: VirtualFile): Boolean {
    if (file.fileSystem !is com.intellij.openapi.vfs.LocalFileSystem) return false
    return try {
        java.nio.file.Files.isSymbolicLink(java.io.File(file.path).toPath())
    } catch (_: java.nio.file.InvalidPathException) {
        // Path has invalid characters — fail closed: treat as symlink so the caller
        // skips it. A path that can't be resolved is untrusted.
        true
    } catch (_: SecurityException) {
        // Security manager denied access — fail closed: treat as symlink so the
        // caller skips it rather than following a path it can't inspect.
        true
    } catch (_: java.io.IOException) {
        // I/O error checking symlink — fail closed: treat as symlink so the
        // caller skips it rather than risk following a broken/restricted path.
        true
    }
}

@Composable
fun
        ChatScreen(
    viewModel: ChatViewModel,
    project: Project
) {
    val messagesState = viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionErrorReason by viewModel.connectionErrorReason.collectAsState()
    val readyState by viewModel.readyState.collectAsState()
    val controlState by viewModel.controlState.collectAsState()
    val inputState by viewModel.inputState.collectAsState()
    val isStreaming = inputState is ChatInputState.Sending || inputState is ChatInputState.Streaming
    val permissionPrompt = (inputState as? ChatInputState.AwaitingPermission)?.prompt
    val selectionPrompt = (inputState as? ChatInputState.AwaitingSelection)?.prompt
    val sessionListState by viewModel.sessionListState.collectAsState()
    val isSidebarVisible by viewModel.isSidebarVisible.collectAsState()
    val sessionContextState by viewModel.sessionContextState.collectAsState()
    val todoItems by viewModel.todoItems.collectAsState()
    val streamingSessionIds by viewModel.streamingSessionIds.collectAsState()
    val pendingCreationSessionIds by viewModel.pendingCreationSessionIds.collectAsState()
    val hiddenChildSessionIds by viewModel.hiddenChildSessionIds.collectAsState()
    val availableCommands by viewModel.availableCommands.collectAsState()
    val commandHistory by viewModel.commandHistory.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    val followAgentEnabled by viewModel.followAgentEnabled.collectAsState()
    val selectedSidebarTab by viewModel.selectedSidebarTab.collectAsState()

    // Local (non-server) slash commands — always shown first
    val localCommands = remember {
        listOf(
            SlashCommand("clear", "Start a new session", AllIconsKeys.General.Add),
            SlashCommand("cancel", "Cancel current response", AllIconsKeys.Actions.Suspend),
            SlashCommand("compact", "Compact context (summarize history to free space)", AllIconsKeys.Actions.Execute),
            SlashCommand("review-perform", "Adversarial review: add comments on changed files", AllIconsKeys.General.BalloonError),
            SlashCommand("review-perform-gaming", "Adversarial review: game-engine checklist (Unreal C++ / Unity C#)", AllIconsKeys.General.BalloonError),
            SlashCommand("review-resolve", "Fix all open review comments", AllIconsKeys.General.BalloonInformation),
            SlashCommand("review-recheck", "Re-review: verify replies, re-raise open issues, add new comments", AllIconsKeys.General.BalloonInformation),
        )
    }
    // Merged list: local commands first, then server commands
    val allSlashCommands = localCommands + availableCommands
    val scope = rememberCoroutineScope()
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    // Recent files — reactive state that updates when files open/close
    val recentFiles = remember { mutableStateListOf<RecentFile>() }

    // Clear-all confirmation dialog state
    var showClearAllDialog by remember { mutableStateOf(false) }
    val clearAllState by viewModel.clearAllState.collectAsState()
    val compactionState by viewModel.compactionState.collectAsState()
    val checkpointReady by viewModel.checkpointReady.collectAsState()

    // Populate initially and subscribe to file editor changes
    LaunchedEffect(project) {
        // Initial load — use submit() + get() on IO dispatcher instead of
        // executeSynchronously() which wraps in a non-cancellable runReadAction
        // that blocks write actions (settings dialog, plugin updater).
        val initial = withContext(Dispatchers.IO) {
            readAction { computeRecentFiles(project) }
        }
        withContext(Dispatchers.Main) {
            recentFiles.clear()
            recentFiles.addAll(initial)
        }

        // Listen for file open/close events to update the list reactively.
        // Debounced: rapid file operations (multi-file paste, git checkout) coalesce
        // into a single recomputation after a 100ms quiet period, avoiding UI flicker
        // from repeated clear()+addAll() calls.
        var refreshJob: kotlinx.coroutines.Job? = null
        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                scheduleRecentFilesRefresh()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                scheduleRecentFilesRefresh()
            }

            private fun scheduleRecentFilesRefresh() {
                refreshJob?.cancel()
                refreshJob = scope.launch {
                    kotlinx.coroutines.delay(100) // debounce — coalesce rapid events
                    val updated = withContext(Dispatchers.IO) {
                        readAction { computeRecentFiles(project) }
                    }
                    withContext(Dispatchers.Main) {
                        recentFiles.clear()
                        recentFiles.addAll(updated)
                    }
                }
            }
        })

        // Keep LaunchedEffect alive until cancelled; disconnect bus on dispose
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            refreshJob?.cancel()
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
            when (val result = readClipboardContent(project)) {
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
            addFileAttachment(file, attachedFiles, project)
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
            addFileAttachment(file, attachedFiles, project, requireImage = true)
        }
    }

    // Recent file click — attach a recent file.
    // NOTE: No re-validation of recentFile.path here — the path came from
    // FileEditorManager/EditorHistoryManager (trusted VFS sources) and
    // addFileAttachment() copies external files into the allowed dir. The
    // service guard in OpenCodeService.sendMessageInternal() is the final
    // authority and catches any invalid/out-of-bound paths. Defense-in-depth.
    val onRecentFileClick: (RecentFile) -> Unit = { recentFile ->
        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        var vf = vfs.findFileByPath(recentFile.path)
        if (vf == null) {
            // Fallback for non-local paths. If the path is already a VFS URL
            // (e.g. jar://, http://), resolve it directly; otherwise treat it
            // as a local filesystem path.
            vf = if (recentFile.path.contains("://")) {
                com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(recentFile.path)
            } else {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByIoFile(java.io.File(recentFile.path))
            }
        }
        if (vf != null) {
            addFileAttachment(vf, attachedFiles, project)
        }
    }

    val onRemoveFile: (Int) -> Unit = { index -> attachedFiles.removeAt(index) }

    // Search results for project files — computed when user types in attach menu search.
    // Track the current search job so a new keystroke cancels the previous in-flight
    // search — otherwise stale broad-query results can overwrite the current narrow-query
    // results (race: slow broad query finishes after fast narrow query).
    val searchResults = remember { mutableStateListOf<RecentFile>() }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val onSearch: (String) -> Unit = { query ->
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.clear()
            searchJob = null
        } else {
            searchJob = scope.launch {
                try {
                    val results = withContext(Dispatchers.IO) {
                        readAction { searchProjectFiles(project, query) }
                    }
                    searchResults.clear()
                    searchResults.addAll(results)
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    // checkCanceled() inside searchProjectFiles threw — search was
                    // interrupted by a write action or cancelled by a new keystroke.
                    // Rethrow so the coroutine cancellation propagates correctly.
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn(e) { "[ACP] File search failed" }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Show splash screen when not fully ready
        val isFullyReady = connectionState == ConnectionState.CONNECTED && readyState == ReadyState.READY
        if (!isFullyReady) {
            ConnectionSplashScreen(
                connectionState = connectionState,
                connectionErrorReason = connectionErrorReason,
                readyState = readyState,
                onConnect = { 
                    viewModel.scope.launch { viewModel.connect(project.basePath) }
                },
                onRetry = { 
                    viewModel.scope.launch { viewModel.retryConnection(project.basePath) }
                },
                onStop = { 
                    viewModel.stopConnection()
                },
                onCancel = {
                    viewModel.cancelInitialization()
                },
                onOpenSettings = {
                    com.opencode.acp.config.settings.OpenCodeSettingsConfigurable.showSettingsDialog(project)
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
                        onTabSelected = { viewModel.selectSidebarTab(it) },
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
                        commentChangeSignal = viewModel.commentChangeSignal,
                        streamingSessionIds = streamingSessionIds,
                        pendingCreationSessionIds = pendingCreationSessionIds,
                        hiddenChildIds = hiddenChildSessionIds,
                        clearAllState = clearAllState,
                        compactionState = compactionState,
                        onCompact = { viewModel.compactSession() },
                        checkpointReady = checkpointReady,
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
                            errorReason = connectionErrorReason,
                            onRetry = { viewModel.scope.launch { viewModel.retryConnection(project.basePath) } }
                        )

                        // Message list (fills remaining space)
                        MessageList(
                            messagesState = messagesState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            project = project,
                            onImagePreview = { path -> previewImagePath = path },
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
                    // By design: if the send fails, the user can re-attach files. Keeping
                    // attachments until success would require complex rollback logic that
                    // isn't worth the complexity for a rare failure path.
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
                    viewModel.selectSidebarTab(SidebarTab.CONTEXT)
                },
                onRetryContext = { viewModel.retryContextFetch() },
                attachedFiles = attachedFiles,
                onAttachFile = { file -> attachedFiles.add(file) },
                onRemoveFile = onRemoveFile,
                onImagePasted = { file -> attachedFiles.add(file) },
                onImagePreview = { path -> previewImagePath = path },
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
                            "compact" -> viewModel.compactSession()
                            "review-perform" -> viewModel.executeReviewPerformCommand(command.args)
                            "review-perform-gaming" -> viewModel.executeReviewPerformGamingCommand(command.args)
                            "review-resolve" -> viewModel.executeReviewResolveCommand()
                            "review-recheck" -> viewModel.executeReviewRecheckCommand(command.args)
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
                project = project,
                isConnected = connectionState == ConnectionState.CONNECTED,
                isReconnecting = connectionState == ConnectionState.RECONNECTING,
                isFollowEnabled = followAgentEnabled,
                onDisconnect = {
                    if (OpenCodeSettingsState.getInstance().showDisconnectConfirmation) {
                        val result = Messages.showOkCancelDialog(
                            project,
                            "This will disconnect from the OpenCode server. The server process will keep running so you can reconnect quickly. Any in-progress responses will be aborted.",
                            "Disconnect from OpenCode?",
                            "Disconnect",
                            "Cancel",
                            Messages.getQuestionIcon(),
                        )
                        if (result == Messages.OK) {
                            viewModel.stopConnection()
                        }
                    } else {
                        viewModel.stopConnection()
                    }
                },
                onToggleFollow = { viewModel.toggleFollowAgent() },
            )
            }
        }

        // Image preview overlay — centered in the entire plugin window.
        // Only the dark background dismisses the preview; clicking the image itself does not.
        previewImagePath?.let { path ->
            var bitmap by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(path) {
                bitmap = withContext(Dispatchers.IO) { decodeFileToBitmap(path) }
            }
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChatTheme.colors.accent.overlaySemiTransparent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { previewImagePath = null },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposeImage(
                        bitmap = bitmap!!,
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
            val activeId = loaded?.selectedId
            val countToDelete = loaded?.sessions?.count { it.id != activeId } ?: 0
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
 *
 * If the file lives outside the allowed attachment directories (project base,
 * `<project>/.opencode/attachments/`, `<user.home>/.opencode/attachments/`), it is
 * copied into `<project>/.opencode/attachments/` first so the security guard in
 * `OpenCodeService.sendMessageInternal()` doesn't silently drop it. The attachment
 * chip shows the copied path.
 */
private fun addFileAttachment(
    file: VirtualFile,
    attachedFiles: MutableList<AttachedFile>,
    project: Project,
    requireImage: Boolean = false
) {
    try {
        if (requireImage) {
            val ext = file.extension?.lowercase() ?: ""
            if (ext !in setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")) {
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn { "[ACP] addFileAttachment: rejecting non-image file from image dialog: ${file.name} (.$ext)" }
                return
            }
        }
        val mime = com.opencode.acp.util.MimeTypes.guessFromFileName(file.name)
        // VirtualFile.path is already an absolute filesystem path for local files.
        val sourceFile = java.io.File(file.path)
        val effectivePath = if (sourceFile.isAbsolute && sourceFile.exists()) {
            // Copy external files (Desktop, Documents, iCloud Drive, ...) into the
            // project's .opencode/attachments/ dir so they pass the service guard.
            // If the copy fails, fall back to the original path and let the guard
            // log/skip it — never silently drop here.
            com.opencode.acp.util.copyExternalAttachmentToAllowedDir(sourceFile, project)
                ?.takeIf { it.absolutePath != sourceFile.absolutePath }
                ?.let { copied ->
                    // Preserve the original display name; use the copied file's path.
                    AttachedFile(name = file.name, path = copied.absolutePath, mime = mime)
                } ?: AttachedFile(name = file.name, path = sourceFile.canonicalPath, mime = mime)
        } else {
            AttachedFile(name = file.name, path = file.path, mime = mime)
        }
        attachedFiles.add(effectivePath)
    } catch (e: Exception) {
        // Skip files that can't be read (e.g., deleted between picker and confirm)
        io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug(e) { "[ACP] addFileAttachment: failed to attach ${file.name}" }
    }
}
