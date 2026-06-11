package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.opencode.acp.chat.ui.theme.ChatTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListAdapter
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.model.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Review Panel (sidebar tab content) ───────────────────────────────────────

/**
 * Main composable for the Review tab.
 *
 * ARCHITECTURE NOTE: The change listener and StateFlow are unified in a single
 * DisposableEffect to ensure the listener that emits events is the same one that's
 * registered. All VCS reads happen inside runReadAction on Dispatchers.IO.
 * All UI mutations happen on EDT.
 *
 * PERFORMANCE: Uses Mutex to prevent concurrent refreshes, increased debounce
 * to 2000ms, caches LineDelta results in GitService.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReviewPanel(
    project: Project,
    modifier: Modifier = Modifier,
    fileChangeSignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
) {
    val gitService = remember { GitService(project) }
    val refreshSignal = remember { MutableStateFlow(0) }
    val refreshMutex = remember { Mutex() }

    // Register ChangeListListener + VFS listener for immediate file change detection.
    // ChangeListManager fires when VCS state updates (has its own internal polling).
    // VFS listener fires immediately when files are created/modified/deleted on disk.
    DisposableEffect(project) {
        val changeListManager = ChangeListManager.getInstance(project)
        val clListener = object : ChangeListAdapter() {
            override fun changeListUpdateDone() {
                refreshSignal.tryEmit(refreshSignal.value + 1)
            }
        }
        changeListManager.addChangeListListener(clListener)

        // VFS listener — fires immediately on any file change (create, modify, delete)
        val vfsListener = object : AsyncFileListener {
            override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
                // Emit on any non-transactional file event (skip .git internal, build dirs, etc.)
                val relevant = events.any { ev ->
                    val path = ev.file?.path ?: return@any false
                    !path.contains("/.git/") && !path.contains("\\.git\\") &&
                    !path.contains("/.idea/") && !path.contains("\\.idea\\") &&
                    !path.contains("/build/") && !path.contains("\\build\\")
                }
                if (relevant) {
                    refreshSignal.tryEmit(refreshSignal.value + 1)
                }
                return null // no custom change applier needed
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, project)

        onDispose {
            changeListManager.removeChangeListListener(clListener)
        }
    }

    // Listen to ViewModel's file change signal for immediate refresh when tool calls modify files.
    // This bypasses ChangeListManager's internal polling delay.
    if (fileChangeSignal != null) {
        LaunchedEffect(Unit) {
            fileChangeSignal.collect {
                refreshSignal.tryEmit(refreshSignal.value + 1)
            }
        }
    }

    // Debounce the refresh signal (300ms — responsive but prevents rapid-fire during bulk ops)
    val debouncedRefresh by refreshSignal
        .debounce(300)
        .collectAsState(initial = 0)

    // Fetch data on background thread inside read action, update state on EDT.
    // Uses Mutex to prevent concurrent refreshes — only one refresh runs at a time.
    // Catch Throwable (not Exception) to handle NoClassDefFoundError from git4idea.
    val state by produceState<ReviewState>(
        initialValue = ReviewState.Loading,
        key1 = debouncedRefresh
    ) {
        try {
            refreshMutex.withLock {
                value = withContext(Dispatchers.IO) {
                    ApplicationManager.getApplication().runReadAction<ReviewState> {
                        val files = gitService.getChangedFiles()
                        if (files.isEmpty()) ReviewState.Empty
                        else ReviewState.Loaded(files)
                    }
                }
            }
        } catch (e: Throwable) {
            value = ReviewState.Error(
                message = e.message ?: "Failed to load changes",
                retryable = true
            )
        }
    }

    // Render based on state
    when (val s = state) {
        is ReviewState.Loading -> ReviewLoadingContent(modifier)
        is ReviewState.Empty -> ReviewEmptyContent(modifier)
        is ReviewState.Error -> ReviewErrorContent(
            message = s.message,
            retryable = s.retryable,
            onRetry = { refreshSignal.tryEmit(refreshSignal.value + 1) },
            modifier = modifier
        )
        is ReviewState.Loaded -> ReviewFileListContent(
            files = s.files,
            onFileClick = { filePath, status, virtualFile ->
                when (status) {
                    FileChangeStatus.UNTRACKED -> {
                        // Use virtualFile directly — ChangedFile already stores it.
                        if (virtualFile != null) {
                            openUntrackedFile(project, virtualFile)
                        }
                    }
                    else -> openDiffForPath(project, filePath, virtualFile)
                }
            },
            onOpenFile = { virtualFile ->
                if (virtualFile != null) {
                    openFileInEditor(project, virtualFile)
                }
            },
            modifier = modifier
        )
    }
}

// ── File List ──────────────────────────────────────────────────────────────────

@Composable
private fun ReviewFileListContent(
    files: List<ChangedFile>,
    onFileClick: (filePath: String, status: FileChangeStatus, virtualFile: com.intellij.openapi.vfs.VirtualFile?) -> Unit,
    onOpenFile: (com.intellij.openapi.vfs.VirtualFile?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(files, key = { it.filePath }) { file ->
            ChangedFileRow(
                file = file,
                onClick = { onFileClick(file.filePath, file.status, file.virtualFile) },
                onOpenFile = { onOpenFile(file.virtualFile) }
            )
        }
    }
}

@Composable
private fun ChangedFileRow(
    file: ChangedFile,
    onClick: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Colors — ChatTheme provides semantic colors
    val hoverBg = ChatTheme.colors.component.hoverBg
    val addedColor = ChatTheme.colors.accent.codeAdded   // Bright mint green like OpenCode
    val deletedColor = ChatTheme.colors.accent.codeDeleted // Salmon/coral red like OpenCode
    val pathColor = ChatTheme.colors.text.link.copy(alpha = 0.5f)
    val normalColor = ChatTheme.colors.text.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
            .background(if (isHovered) hoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File type icon
        Icon(
            key = getFileTypeIcon(file.fileName),
            contentDescription = file.fileName,
            modifier = Modifier.size(ChatTheme.dims.reviewFileIconSize),
            tint = Color.Unspecified
        )
        Spacer(Modifier.width(8.dp))

        // File info column
        Column(modifier = Modifier.weight(1f)) {
            // File name + status label row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = file.fileName,
                    fontSize = ChatTheme.fonts.reviewFileName,
                    fontWeight = FontWeight.Normal,
                    color = normalColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Status label for new/untracked files
                if (file.status == FileChangeStatus.UNTRACKED) {
                    Text(
                        text = "Added",
                        fontSize = ChatTheme.fonts.reviewStatusLabel,
                        fontWeight = FontWeight.Medium,
                        color = ChatTheme.colors.component.reviewAddedLabel  // Green color like in the image
                    )
                }
            }
            // Relative path (dimmed, tinted)
            Text(
                text = file.filePath,
                fontSize = ChatTheme.fonts.reviewFilePath,
                color = pathColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Line delta indicator
        when (val delta = file.lineDelta) {
            is LineDelta.Known -> {
                if (delta.additions > 0) {
                    Text(
                        text = "+${delta.additions}",
                        fontSize = ChatTheme.fonts.reviewLineDelta,
                        fontWeight = FontWeight.Medium,
                        color = addedColor
                    )
                }
                if (delta.deletions > 0) {
                    if (delta.additions > 0) Spacer(Modifier.width(4.dp))
                    Text(
                        text = "-${delta.deletions}",
                        fontSize = ChatTheme.fonts.reviewLineDelta,
                        fontWeight = FontWeight.Medium,
                        color = deletedColor
                    )
                }
                if (delta.additions == 0 && delta.deletions == 0) {
                    Text(
                        text = "0",
                        fontSize = ChatTheme.fonts.reviewLineDelta,
                        color = pathColor
                    )
                }
            }
            is LineDelta.Unknown -> {
                Text(
                    text = "—",
                    fontSize = ChatTheme.fonts.reviewLineDelta,
                    color = pathColor
                )
            }
        }

        // Open file icon (always visible target icon)
        if (file.virtualFile != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                key = AllIconsKeys.General.Locate,
                contentDescription = "Open file",
                modifier = Modifier
                    .size(ChatTheme.dims.reviewOpenFileIconSize)
                    .clickable(onClick = onOpenFile)
                    .padding(1.dp),
                tint = pathColor
            )
        }
    }
}

// ── File type icons ────────────────────────────────────────────────────────────

internal fun getFileTypeIcon(fileName: String): org.jetbrains.jewel.ui.icon.IconKey {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "kt" -> AllIconsKeys.Language.Kotlin
        "kts" -> AllIconsKeys.Language.Kotlin
        "java" -> AllIconsKeys.FileTypes.Java
        "xml" -> AllIconsKeys.FileTypes.Xml
        "json" -> AllIconsKeys.FileTypes.Json
        "yaml", "yml" -> AllIconsKeys.FileTypes.Yaml
        "md" -> AllIconsKeys.FileTypes.Text
        "txt" -> AllIconsKeys.FileTypes.Text
        "js", "jsx" -> AllIconsKeys.FileTypes.JavaScript
        "ts", "tsx" -> AllIconsKeys.FileTypes.JavaScript
        "css" -> AllIconsKeys.FileTypes.Css
        "html", "htm" -> AllIconsKeys.FileTypes.Html
        "py" -> AllIconsKeys.Language.Python
        "rb" -> AllIconsKeys.Language.Ruby
        "rs" -> AllIconsKeys.Language.Rust
        "go" -> AllIconsKeys.Language.GO
        "scala" -> AllIconsKeys.Language.Scala
        "php" -> AllIconsKeys.Language.Php
        "gradle", "gradle.kts" -> AllIconsKeys.Nodes.Folder  // Fallback for Gradle
        "properties" -> AllIconsKeys.FileTypes.Text
        "gitignore" -> AllIconsKeys.FileTypes.Text
        "svg" -> AllIconsKeys.FileTypes.Image
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> AllIconsKeys.FileTypes.Image
        else -> AllIconsKeys.FileTypes.Text
    }
}

// ── State composables ──────────────────────────────────────────────────────────

@Composable
private fun ReviewLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading changes...",
            fontSize = ChatTheme.fonts.reviewLoading,
            color = ChatTheme.colors.text.disabled
        )
    }
}

@Composable
private fun ReviewEmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No changes",
            fontSize = ChatTheme.fonts.reviewEmpty,
            color = ChatTheme.colors.text.disabled
        )
    }
}

@Composable
private fun ReviewErrorContent(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            key = AllIconsKeys.General.BalloonError,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = ChatTheme.colors.text.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = ChatTheme.fonts.reviewError,
            color = ChatTheme.colors.text.disabled,
            maxLines = 2
        )
        if (retryable) {
            Spacer(Modifier.height(8.dp))
            Link("Retry", onClick = onRetry)
        }
    }
}

// ── Diff/Editor helpers ───────────────────────────────────────────────────────

/**
 * Opens the IDE's native diff viewer for a tracked change.
 * Called on EDT via invokeLater.
 *
 * If the Change is stale (committed/reverted), falls back to opening the file.
 * Uses full fileName (not extension) as 3rd arg to DiffContentFactory for syntax highlighting.
 */
fun openDiffForPath(project: Project, filePath: String, virtualFile: com.intellij.openapi.vfs.VirtualFile?) {
    ApplicationManager.getApplication().invokeLater {
        try {
            val changeListManager = ChangeListManager.getInstance(project)
            val currentChanges = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vcs.changes.Change>> {
                changeListManager.defaultChangeList.changes.toList()
            }

            val change = currentChanges.find {
                getRelativePath(project, it) == filePath
            }

            if (change != null) {
                val fileName = change.virtualFile?.name ?: filePath.substringAfterLast('/')
                // Resolve FileType from file name for syntax highlighting in diff viewer
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                val beforeContent = ApplicationManager.getApplication().runReadAction<String?> {
                    change.beforeRevision?.content
                } ?: ""
                val afterContent = ApplicationManager.getApplication().runReadAction<String?> {
                    change.afterRevision?.content
                } ?: ""

                val factory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(
                    fileName,
                    factory.create(project, beforeContent, fileType),
                    factory.create(project, afterContent, fileType),
                    change.beforeRevision?.revisionNumber?.asString() ?: "Base",
                    change.afterRevision?.revisionNumber?.asString() ?: "Working"
                )
                DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)
            } else if (virtualFile != null) {
                // Change was committed/reverted — open file directly
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        } catch (_: Throwable) {
            // Catch Throwable (not Exception) to handle NoClassDefFoundError from optional plugins
            // Silently ignore — diff viewer is best-effort
        }
    }
}

/** Opens an untracked file in the editor. Uses VirtualFile directly (already stored in ChangedFile). */
private fun openUntrackedFile(project: Project, virtualFile: com.intellij.openapi.vfs.VirtualFile) {
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
}

/** Opens a file in the editor (not diff). Used by the target icon. */
internal fun openFileInEditor(project: Project, virtualFile: com.intellij.openapi.vfs.VirtualFile) {
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
}