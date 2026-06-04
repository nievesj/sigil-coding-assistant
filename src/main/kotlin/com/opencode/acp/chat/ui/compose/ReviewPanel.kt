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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
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
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.foundation.theme.JewelTheme

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
    modifier: Modifier = Modifier
) {
    val gitService = remember { GitService(project) }
    val refreshSignal = remember { MutableStateFlow(0) }
    val refreshMutex = remember { Mutex() }

    // Register ChangeListListener with debounce in a SINGLE DisposableEffect.
    // The listener emits to refreshSignal, which is debounced before triggering produceState.
    DisposableEffect(project) {
        val changeListManager = ChangeListManager.getInstance(project)
        val listener = object : ChangeListAdapter() {
            override fun changeListUpdateDone() {
                refreshSignal.tryEmit(refreshSignal.value + 1)
            }
        }
        changeListManager.addChangeListListener(listener)
        onDispose {
            changeListManager.removeChangeListListener(listener)
        }
    }

    // Debounce the refresh signal (2000ms - increased from 500ms to prevent
    // thundering herd during rapid VCS events like branch switch, git rebase)
    val debouncedRefresh by refreshSignal
        .debounce(2000)
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
                        if (!gitService.isGitAvailable()) {
                            ReviewState.NoGitRepository
                        } else {
                            val files = gitService.getChangedFiles()
                            if (files.isEmpty()) ReviewState.Empty
                            else ReviewState.Loaded(files)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Catch Throwable to handle NoClassDefFoundError from optional git4idea dependency
            value = ReviewState.Error(
                message = e.message ?: "Failed to load changes",
                retryable = true
            )
        }
    }

    // Render based on state
    when (val s = state) {
        is ReviewState.Loading -> ReviewLoadingContent(modifier)
        is ReviewState.NoGitRepository -> ReviewNoGitContent(modifier)
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

    // Colors — retrieveColorOrUnspecified is @Composable so must be called directly
    val hoverBg = retrieveColorOrUnspecified("MenuItem.selectionBackground")
    val addedColor = retrieveColorOrUnspecified("FileStatus.AddedColor")
    val deletedColor = retrieveColorOrUnspecified("FileStatus.DeletedColor")
    val disabledColor = JewelTheme.globalColors.text.disabled
    val normalColor = JewelTheme.globalColors.text.normal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) hoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File type icon
        Icon(
            key = IntelliJIconKey.fromPlatformIcon(getFileTypeIcon(file.fileName)),
            contentDescription = file.fileName,
            modifier = Modifier.size(16.dp),
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
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = normalColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Status label for new/untracked files
                if (file.status == FileChangeStatus.UNTRACKED) {
                    Text(
                        text = "Added",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF7CB342)  // Green color like in the image
                    )
                }
            }
            // Relative path (dimmed)
            Text(
                text = file.filePath,
                fontSize = 10.sp,
                color = disabledColor,
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
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = addedColor
                    )
                }
                if (delta.deletions > 0) {
                    if (delta.additions > 0) Spacer(Modifier.width(4.dp))
                    Text(
                        text = "-${delta.deletions}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = deletedColor
                    )
                }
                if (delta.additions == 0 && delta.deletions == 0) {
                    Text(
                        text = "0",
                        fontSize = 11.sp,
                        color = disabledColor
                    )
                }
            }
            is LineDelta.Unknown -> {
                Text(
                    text = "—",
                    fontSize = 11.sp,
                    color = disabledColor
                )
            }
        }

        // Open file chevron icon (visible on hover)
        if (isHovered && file.virtualFile != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Forward),
                contentDescription = "Open file",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onOpenFile)
                    .padding(1.dp),
                tint = disabledColor
            )
        }
    }
}

// ── File type icons ────────────────────────────────────────────────────────────

private fun getFileTypeIcon(fileName: String): javax.swing.Icon {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "kt" -> AllIcons.Language.Kotlin
        "kts" -> AllIcons.Language.Kotlin
        "java" -> AllIcons.FileTypes.Java
        "xml" -> AllIcons.FileTypes.Xml
        "json" -> AllIcons.FileTypes.Json
        "yaml", "yml" -> AllIcons.FileTypes.Yaml
        "md" -> AllIcons.FileTypes.Text
        "txt" -> AllIcons.FileTypes.Text
        "js", "jsx" -> AllIcons.FileTypes.JavaScript
        "ts", "tsx" -> AllIcons.FileTypes.JavaScript
        "css" -> AllIcons.FileTypes.Css
        "html", "htm" -> AllIcons.FileTypes.Html
        "py" -> AllIcons.Language.Python
        "rb" -> AllIcons.Language.Ruby
        "rs" -> AllIcons.Language.Rust
        "go" -> AllIcons.Language.GO
        "scala" -> AllIcons.Language.Scala
        "php" -> AllIcons.Language.Php
        "gradle", "gradle.kts" -> AllIcons.Nodes.Folder  // Fallback for Gradle
        "properties" -> AllIcons.FileTypes.Text
        "gitignore" -> AllIcons.FileTypes.Text
        "svg" -> AllIcons.FileTypes.Image
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> AllIcons.FileTypes.Image
        else -> AllIcons.FileTypes.Text
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
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled
        )
    }
}

@Composable
private fun ReviewNoGitContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            key = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Console),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = JewelTheme.globalColors.text.disabled,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No git repository detected",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = JewelTheme.globalColors.text.disabled
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Initialize git to track changes.",
            fontSize = 11.sp,
            color = JewelTheme.globalColors.text.disabled
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
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled
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
            key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.BalloonError),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFFDB4437),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled,
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

/** Opens a file in the editor (not diff). Used by the chevron icon on hover. */
private fun openFileInEditor(project: Project, virtualFile: com.intellij.openapi.vfs.VirtualFile) {
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
}