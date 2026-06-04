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
import androidx.compose.foundation.layout.heightIn
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.SidebarTab
import com.opencode.acp.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    com.intellij.psi.search.FilenameIndex.getFilesByName(project, query, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
        .filter { it.isValid && !it.isDirectory }
        .take(maxResults)
        .forEach {
            val vf = it.virtualFile ?: return@forEach
            if (vf.path !in seen) {
                seen.add(vf.path)
                results.add(RecentFile(name = vf.name, path = vf.path))
            }
        }

    // If we have enough results, return early
    if (results.size >= maxResults) return results.take(maxResults)

    // Partial match: iterate all files in project sources and content roots
    val projectScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
    com.intellij.psi.search.FilenameIndex.getFilesByName(project, query, projectScope)
    // For partial matches, use VFS refresh + iterate project files
    val baseDir = project.baseDir ?: return results

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
fun ChatScreen(
    viewModel: ChatViewModel,
    project: Project
) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val controlState by viewModel.controlState.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val permissionPrompt by viewModel.permissionPrompt.collectAsState()
    val sessionListState by viewModel.sessionListState.collectAsState()
    val isSidebarVisible by viewModel.isSidebarVisible.collectAsState()
    val sessionContextState by viewModel.sessionContextState.collectAsState()
    var selectedSidebarTab by remember { mutableStateOf(SidebarTab.SESSIONS) }
    val scope = rememberCoroutineScope()
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }
    var previewImageUri by remember { mutableStateOf<String?>(null) }

    // Recent files — reactive state that updates when files open/close
    val recentFiles = remember { mutableStateListOf<RecentFile>() }

    // Populate initially and subscribe to file editor changes
    LaunchedEffect(project) {
        // Initial load
        val initial = ApplicationManager.getApplication().runReadAction<List<RecentFile>> {
            computeRecentFiles(project)
        }
        recentFiles.clear()
        recentFiles.addAll(initial)
        println("[AttachMenu] Initial recentFiles: ${initial.size} files, names=${initial.map { it.name }}")

        // Listen for file open/close events to update the list reactively
        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                val updated = ApplicationManager.getApplication().runReadAction<List<RecentFile>> {
                    computeRecentFiles(project)
                }
                recentFiles.clear()
                recentFiles.addAll(updated)
                println("[AttachMenu] fileOpened -> recentFiles: ${updated.size} files")
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                val updated = ApplicationManager.getApplication().runReadAction<List<RecentFile>> {
                    computeRecentFiles(project)
                }
                recentFiles.clear()
                recentFiles.addAll(updated)
                println("[AttachMenu] fileClosed -> recentFiles: ${updated.size} files")
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
                    println("[ChatScreen] Pasted file from clipboard: ${result.file.name}")
                }
                is ClipboardResult.TextResult -> {
                    viewModel.requestTextPaste(result.text)
                    println("[ChatScreen] Pasted text from clipboard, length=${result.text.length}")
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
        println("[AttachMenu] onRecentFileClick: name=${recentFile.name}, path=${recentFile.path}")
        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        var vf = vfs.findFileByPath(recentFile.path)
        println("[AttachMenu] LocalFileSystem.findFileByPath result: ${vf?.let { "${it.name} (${it.path})" } ?: "null"}")
        if (vf == null) {
            // Try via URL for non-local paths (jar://, etc.)
            vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://${recentFile.path}")
            println("[AttachMenu] VirtualFileManager.findFileByUrl result: ${vf?.let { "${it.name} (${it.path})" } ?: "null"}")
        }
        if (vf != null) {
            addFileAttachment(vf, attachedFiles)
            println("[AttachMenu] Attached file: ${vf.name}")
        } else {
            println("[AttachMenu] FAILED to resolve file for path: ${recentFile.path}")
        }
    }

    val onRemoveFile: (Int) -> Unit = { index -> attachedFiles.removeAt(index) }

    // Search results for project files — computed when user types in attach menu search
    val searchResults = remember { mutableStateListOf<RecentFile>() }
    val onSearch: (String) -> Unit = { query ->
        if (query.isBlank()) {
            searchResults.clear()
        } else {
            val results = ApplicationManager.getApplication().runReadAction<List<RecentFile>> {
                searchProjectFiles(project, query)
            }
            searchResults.clear()
            searchResults.addAll(results)
            println("[AttachMenu] onSearch('$query') -> ${results.size} results")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top section: sidebar + chat area
            Row(modifier = Modifier.weight(1f)) {
                // Sidebar — animated width for show/hide and tab switching
                val sidebarTargetWidth = if (isSidebarVisible) {
                    when (selectedSidebarTab) {
                        SidebarTab.SESSIONS -> ChatConstants.SIDEBAR_WIDTH_DP
                        SidebarTab.CONTEXT -> ChatConstants.SIDEBAR_CONTEXT_WIDTH_DP
                    }
                } else 0
                val sidebarWidth by animateDpAsState(
                    targetValue = sidebarTargetWidth.dp,
                    label = "sidebarWidth"
                )

                if (isSidebarVisible) {
                    SessionSidebar(
                        state = sessionListState,
                        contextState = sessionContextState,
                        selectedTab = selectedSidebarTab,
                        onTabSelected = { selectedSidebarTab = it },
                        onNewSession = { scope.launch { viewModel.createAndSwitchSession() } },
                        onSessionSelected = { scope.launch { viewModel.switchSession(it) } },
                        onSessionArchived = { scope.launch { viewModel.archiveSession(it) } },
                        onRetry = { scope.launch { viewModel.loadSessions() } },
                        onContextRetry = { viewModel.retryContextFetch() },
                        onShowDetails = { /* Context tab is already showing */ },
                        modifier = Modifier.width(sidebarWidth)
                    )
                }
                // Main chat area
                Column(modifier = Modifier.weight(1f)) {
                    ChatHeader(
                        isSidebarVisible = isSidebarVisible,
                        onToggleSidebar = { viewModel.toggleSidebar() }
                    )
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
                }
            }

            // Bottom section spans full width (including sidebar)
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
                contextState = sessionContextState,
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
            )
        }

        // Image preview overlay — centered in the entire plugin window
        previewImageUri?.let { uri ->
            val bitmap = remember(uri) { decodeDataUriToBitmap(uri) }
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                        .clickable { previewImageUri = null },
                    contentAlignment = Alignment.Center,
                ) {
                    ComposeImage(
                        bitmap = bitmap,
                        contentDescription = "Preview",
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .fillMaxHeight(0.85f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
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
        val mime = java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val dataUri = "data:$mime;base64,$base64"
        attachedFiles.add(AttachedFile(name = file.name, path = file.path, mime = mime, dataUri = dataUri))
    } catch (_: Exception) {
        // Skip files that can't be read
    }
}
