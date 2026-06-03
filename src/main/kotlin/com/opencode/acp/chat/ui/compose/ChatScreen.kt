package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.ConnectionState
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
    val scope = rememberCoroutineScope()
    val attachedFiles = remember { mutableStateListOf<AttachedFile>() }

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
    // for image data and attaches it.
    LaunchedEffect(viewModel) {
        viewModel.pasteImageSignal.collectLatest {
            val file = readClipboardImage()
            if (file != null) {
                attachedFiles.add(file)
                println("[ChatScreen] Pasted image from clipboard: ${file.name}")
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
            onAttachFile = { file -> attachedFiles.add(file) },
            onRemoveFile = onRemoveFile,
            onImagePasted = { file -> attachedFiles.add(file) },
            recentFiles = recentFiles,
            searchResults = searchResults,
            onSearch = onSearch,
            onFilesAndFolders = onFilesAndFolders,
            onImage = onImage,
            onRecentFileClick = onRecentFileClick,
        )
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
