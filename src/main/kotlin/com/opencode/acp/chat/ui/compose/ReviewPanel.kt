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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opencode.acp.chat.ui.theme.ChatTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.CancellationException

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListAdapter
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.CommentCounts
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.model.ReviewState
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.chat.service.getRelativePath
import com.opencode.acp.follow.EditorFollowManager
import com.opencode.acp.review.ReviewComment
import com.opencode.acp.review.ReviewCommentDiffExtension
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewIndex
import com.opencode.acp.review.ReviewSeverity
import com.opencode.acp.review.ReviewStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Review Panel (sidebar tab content) ───────────────────────────────────────

/**
 * Main composable for the Review tab.
 *
 * ARCHITECTURE NOTE: The change listener and StateFlow are unified in a single
 * DisposableEffect to ensure the listener that emits events is the same one that's
 * registered. All VCS reads happen inside runReadActionBlocking on Dispatchers.IO.
 * All UI mutations happen on EDT.
 *
 * PERFORMANCE: Uses Mutex to prevent concurrent refreshes, debounced at
 * 300ms, caches LineDelta results in GitService.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReviewPanel(
    project: Project,
    modifier: Modifier = Modifier,
    fileChangeSignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    commentChangeSignal: kotlinx.coroutines.flow.StateFlow<ReviewIndex>,
) {
    val gitService = remember { GitService(project) }
    val refreshSignal = remember { MutableStateFlow(0L) }
    val refreshMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    // Register ChangeListListener + VFS listener for immediate file change detection.
    // ChangeListManager fires when VCS state updates (has its own internal polling).
    // VFS listener fires immediately when files are created/modified/deleted on disk.
    DisposableEffect(project) {
        val changeListManager = ChangeListManager.getInstance(project)
        val clListener = object : ChangeListAdapter() {
            override fun changeListUpdateDone() {
                refreshSignal.update { it + 1 }
            }
        }
        changeListManager.addChangeListListener(clListener)

        // VFS listener — fires immediately on any file change (create, modify, delete)
        val vfsListener = object : AsyncFileListener {
            override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
                // Emit on any non-transactional file event (skip .git internal, build dirs, etc.)
                val relevant = events.any { ev ->
                    val path = ev.file?.path?.replace('\\', '/') ?: return@any false
                    !path.contains("/.git/") &&
                    !path.contains("/.idea/") &&
                    !path.contains("/build/")
                }
                if (relevant) {
                    refreshSignal.update { it + 1 }
                }
                return null // no custom change applier needed
            }
        }
        // Use a controlled disposable (not `project`) so the listener is
        // removed when the composable leaves composition, not just on project dispose.
        val vfsDisposable = Disposer.newDisposable("OpenCodeReviewPanelVfsListener")
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, vfsDisposable)

        onDispose {
            changeListManager.removeChangeListListener(clListener)
            Disposer.dispose(vfsDisposable)
        }
    }

    // Listen to ViewModel's file change signal for immediate refresh when tool calls modify files.
    // This bypasses ChangeListManager's internal polling delay.
    if (fileChangeSignal != null) {
        LaunchedEffect(Unit) {
            fileChangeSignal.collect {
                refreshSignal.update { it + 1 }
            }
        }
    }

    // Collect comment changes from ReviewCommentManager
    val commentIndex by commentChangeSignal.collectAsState(ReviewIndex())

    // Build CommentCounts from the review index
    val commentCounts = remember(commentIndex) {
        val counts = commentIndex.commentsByFile
            .mapValues { (_, comments) -> comments.count { it.status == ReviewStatus.OPEN } }
            .filterValues { it > 0 }
        CommentCounts(counts)
    }

    // Build open-comments-per-file map for direct navigation + child rows.
    // Sorted by startLine so the first entry is the topmost comment.
    val openCommentsByFile = remember(commentIndex) {
        commentIndex.commentsByFile
            .mapValues { (_, comments) ->
                comments.filter { it.status == ReviewStatus.OPEN }
                    .sortedBy { it.startLine }
            }
            .filterValues { it.isNotEmpty() }
    }

    // Debounce the refresh signal (300ms — responsive but prevents rapid-fire during bulk ops)
    val debouncedRefresh by refreshSignal
        .debounce(300)
        .collectAsState(initial = 0L)

    // Re-compute when debouncedRefresh OR commentCounts changes
    val refreshKey = remember(debouncedRefresh, commentCounts) {
        "$debouncedRefresh-${commentCounts.totalOpen}-${commentCounts.countsByFile.size}"
    }

    // Fetch data on background thread inside read action, update state on EDT.
    // Uses Mutex to prevent concurrent refreshes — only one refresh runs at a time.
    // Catch Exception to avoid swallowing OutOfMemoryError and other serious JVM errors.
    val state by produceState<ReviewState>(
        initialValue = ReviewState.Loading,
        key1 = refreshKey
    ) {
        try {
            refreshMutex.withLock {
                value = withContext(Dispatchers.IO) {
                    readAction {
                    val files = gitService.getChangedFiles()
                    if (files.isEmpty()) ReviewState.Empty
                    else ReviewState.Loaded(files, commentCounts, openCommentsByFile)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            value = ReviewState.Error(
                message = e.message ?: "Failed to load changes",
                retryable = true
            )
        }
    }

    // Render based on state
    Column(modifier = modifier.fillMaxSize()) {
        // Refresh button row — always visible so the user can force a re-read of
        // .review/ files even when the file watcher misses an external write.
        ReviewRefreshBar(
            onRefresh = {
                scope.launch {
                    ReviewCommentManager.getInstance(project).loadAll()
                    refreshSignal.update { it + 1 }
                }
            }
        )
        when (val s = state) {
            is ReviewState.Loading -> ReviewLoadingContent(Modifier.fillMaxSize())
            is ReviewState.Empty -> ReviewEmptyContent(Modifier.fillMaxSize())
            is ReviewState.Error -> ReviewErrorContent(
                message = s.message,
                retryable = s.retryable,
                onRetry = { refreshSignal.value = refreshSignal.value + 1 },
                modifier = Modifier.fillMaxSize()
            )
            is ReviewState.Loaded -> ReviewFileListContent(
                files = s.files,
                commentCounts = s.commentCounts,
                openCommentsByFile = s.openCommentsByFile,
                onFileClick = { _, _, virtualFile ->
                    if (virtualFile != null) {
                        openFileInEditor(project, virtualFile)
                    }
                },
                onOpenFile = { filePath, status, virtualFile ->
                    when (status) {
                        FileChangeStatus.UNTRACKED -> {
                            // Use virtualFile directly — ChangedFile already stores it.
                            if (virtualFile != null) {
                                openFileInEditor(project, virtualFile)
                            }
                        }
                        else -> openDiffForPath(project, filePath, virtualFile, scope)
                    }
                },
                onCommentClick = { filePath, line ->
                    EditorFollowManager.getInstance(project)
                        .openFileAtLine(project, filePath, line, focus = true)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── File List ──────────────────────────────────────────────────────────────────

@Composable
private fun ReviewFileListContent(
    files: List<ChangedFile>,
    commentCounts: CommentCounts = CommentCounts(),
    openCommentsByFile: Map<String, List<ReviewComment>> = emptyMap(),
    onFileClick: (filePath: String, status: FileChangeStatus, virtualFile: com.intellij.openapi.vfs.VirtualFile?) -> Unit,
    onOpenFile: (filePath: String, status: FileChangeStatus, virtualFile: com.intellij.openapi.vfs.VirtualFile?) -> Unit,
    onCommentClick: (filePath: String, line: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        items(files, key = { it.filePath }) { file ->
            val comments = openCommentsByFile[file.filePath].orEmpty()
            ChangedFileRow(
                file = file,
                comments = comments,
                onRowClick = { onFileClick(file.filePath, file.status, file.virtualFile) },
                onOpenFile = { onOpenFile(file.filePath, file.status, file.virtualFile) },
                onCommentClick = { line -> onCommentClick(file.filePath, line) }
            )
        }
    }
}

@Composable
private fun ChangedFileRow(
    file: ChangedFile,
    comments: List<ReviewComment> = emptyList(),
    onRowClick: () -> Unit,
    onOpenFile: () -> Unit,
    onCommentClick: (line: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowHovered by rowInteractionSource.collectIsHoveredAsState()

    // Colors — ChatTheme provides semantic colors
    val hoverBg = ChatTheme.colors.component.hoverBg
    val addedColor = ChatTheme.colors.accent.codeAdded   // Bright mint green like OpenCode
    val deletedColor = ChatTheme.colors.accent.codeDeleted // Salmon/coral red like OpenCode
    val pathColor = ChatTheme.colors.text.link.copy(alpha = 0.5f)
    val normalColor = ChatTheme.colors.text.primary
    val commentColor = ChatTheme.colors.accent.blue
    val commentBg = commentColor.copy(alpha = 0.15f)
    val commentHoverBg = commentColor.copy(alpha = 0.25f)

    val commentCount = comments.size

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
                .background(if (isRowHovered) hoverBg else Color.Transparent)
                .hoverable(rowInteractionSource)
                .clickable(onClick = onRowClick)
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // File name only
                Text(
                    text = file.fileName,
                    fontSize = ChatTheme.fonts.reviewFileName,
                    fontWeight = FontWeight.Normal,
                    color = normalColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Status / review chips on their own line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Status label for new/untracked files
                    if (file.status == FileChangeStatus.UNTRACKED) {
                        Text(
                            text = "Added",
                            fontSize = ChatTheme.fonts.reviewStatusLabel,
                            fontWeight = FontWeight.Medium,
                            color = ChatTheme.colors.component.reviewAddedLabel
                        )
                    }
                    // Review comments are shown in the collapsible list below
                }
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

            // Open file icon (always visible target icon) — hoverable + clickable
            if (file.virtualFile != null) {
                Spacer(Modifier.width(8.dp))
                val locateInteractionSource = remember { MutableInteractionSource() }
                val isLocateHovered by locateInteractionSource.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(ChatTheme.dims.reviewOpenFileIconSize + 4.dp)
                        .clip(CircleShape)
                        .background(if (isLocateHovered) hoverBg else Color.Transparent)
                        .hoverable(locateInteractionSource)
                        .clickable(onClick = onOpenFile),
                    contentAlignment = Alignment.Center
                ) {
                Icon(
                    key = AllIconsKeys.Actions.Diff,
                    contentDescription = "Diff",
                    modifier = Modifier.size(ChatTheme.dims.reviewOpenFileIconSize),
                    tint = if (isLocateHovered) ChatTheme.colors.text.link else pathColor
                )
                }
            }
        }

        // Collapsible child review rows — only when >1 open comments.
        // Default expanded so all comments are visible; click header to collapse.
        if (commentCount > 0) {
            var expanded by remember { mutableStateOf(true) }
            val toggleInteractionSource = remember { MutableInteractionSource() }
            val isToggleHovered by toggleInteractionSource.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp)
                    .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
                    .background(if (isToggleHovered) hoverBg else Color.Transparent)
                    .hoverable(toggleInteractionSource)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    key = if (expanded) AllIconsKeys.General.ChevronDown
                          else AllIconsKeys.General.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(12.dp),
                    tint = commentColor
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$commentCount comments",
                    fontSize = ChatTheme.fonts.reviewStatusLabel,
                    color = commentColor,
                )
            }
            if (expanded) {
                comments.forEach { comment ->
                    ReviewCommentChildRow(
                        comment = comment,
                        onClick = { onCommentClick(comment.startLine) }
                    )
                }
            }
        }
    }
}

/**
 * A single child row under a file row, representing one open review comment.
 * Shows the severity icon and a brief (1-line) message; click navigates to
 * the comment's location in the editor. When the comment has replies, a reply
 * count badge is shown; clicking it expands/collapses the reply thread.
 */
@Composable
private fun ReviewCommentChildRow(
    comment: ReviewComment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var repliesExpanded by remember(comment.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBg = ChatTheme.colors.component.hoverBg

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
                .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
                .background(if (isHovered) hoverBg else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Severity icon
            Icon(
                key = severityIconKey(comment.severity),
                contentDescription = comment.severity.name,
                modifier = Modifier.size(12.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(6.dp))
            // Brief message — 1 line, ellipsized
            Text(
                text = comment.comment,
                fontSize = ChatTheme.fonts.reviewStatusLabel,
                color = ChatTheme.colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Reply count badge — click toggles the reply thread
            if (comment.replies.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1AFFFFFF))
                        .clickable { repliesExpanded = !repliesExpanded }
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${comment.replies.size} reply(ies)",
                        fontSize = ChatTheme.fonts.reviewStatusLabel,
                        color = ChatTheme.colors.text.secondary,
                    )
                }
            }
        }
        // Expandable reply thread
        if (repliesExpanded && comment.replies.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 56.dp, end = 6.dp)) {
                for (reply in comment.replies) {
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${reply.author}:",
                            fontSize = ChatTheme.fonts.reviewStatusLabel,
                            color = ChatTheme.colors.text.secondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = reply.text,
                            fontSize = ChatTheme.fonts.reviewStatusLabel,
                            color = ChatTheme.colors.text.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Map a review severity to a Jewel icon key matching [ReviewIcons]. */
private fun severityIconKey(severity: ReviewSeverity): IconKey = when (severity) {
    ReviewSeverity.ERROR   -> AllIconsKeys.General.BalloonError
    ReviewSeverity.WARNING -> AllIconsKeys.General.Warning
    ReviewSeverity.INFO    -> AllIconsKeys.General.BalloonInformation
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
        "gradle" -> AllIconsKeys.Nodes.Folder  // Fallback for Gradle
        "properties" -> AllIconsKeys.FileTypes.Text
        "gitignore" -> AllIconsKeys.FileTypes.Text
        "svg" -> AllIconsKeys.FileTypes.Image
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> AllIconsKeys.FileTypes.Image
        else -> resolveFileTypeIconFromPlatform(fileName)
    }
}

/**
 * Fallback for file types not covered by the static [AllIconsKeys] map above.
 *
 * The platform's [AllIcons]/[AllIconsKeys] only ship icons for a handful of
 * languages (Kotlin, Java, Python, …). Rider-specific languages — C#, C++,
 * F#, VB, Razor, .csproj/.sln, etc. — and CLion's C/C++ have **no** constant
 * in `AllIcons.FileTypes`/`AllIcons.Language`. Their icons are contributed by
 * the host IDE's file-type registry instead.
 *
 * This asks [FileTypeManager] for the registered [FileType] for the file name
 * and wraps its icon as a Jewel [IconKey] via
 * [IntelliJIconKey.fromPlatformIcon]. On Rider this resolves the real C#/C++/
 * F#/VB/Razor/csproj/sln icons; on IntelliJ IDEA (no .NET plugin) it falls
 * back to the plain-text file-type icon, which is the same as the previous
 * hard-coded `AllIconsKeys.FileTypes.Text` fallback. The lookup is cheap and
 * read-safe ([FileTypeManager.getFileTypeByFileName] does not require a read
 * action), so it is safe to call from composition.
 *
 * Guarded with a try/catch so a misbehaving FileType extension can never break
 * the review tab — it degrades to the generic text icon.
 */
private fun resolveFileTypeIconFromPlatform(fileName: String): org.jetbrains.jewel.ui.icon.IconKey {
    return try {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val icon = fileType.icon
        if (icon != null) {
            IntelliJIconKey.fromPlatformIcon(icon)
        } else {
            AllIconsKeys.FileTypes.Text
        }
    } catch (t: Throwable) {
        AllIconsKeys.FileTypes.Text
    }
}

// ── State composables ──────────────────────────────────────────────────────────

/**
 * Thin header bar with a refresh button. Lets the user force a re-read of
 * `.review/` files from disk when the file watcher misses an external write
 * (e.g., the LLM agent writes a file while the VFS is mid-refresh, or the
 * `.review/` directory is created by an external tool that doesn't trigger
 * a VFS event the plugin's AsyncFileListener catches).
 *
 * The button calls [ReviewCommentManager.loadAll] (which walks the disk
 * directly via `java.nio.file.Files.walk`, bypassing VFS) and then bumps
 * `refreshSignal` to re-fetch the changed-files list from VCS.
 */
@Composable
private fun ReviewRefreshBar(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBg = ChatTheme.colors.component.hoverBg
    val iconColor = if (isHovered) ChatTheme.colors.text.link else ChatTheme.colors.text.secondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isHovered) hoverBg else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onRefresh)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                key = AllIconsKeys.Actions.Refresh,
                contentDescription = "Refresh reviews",
                modifier = Modifier.size(14.dp),
                tint = iconColor,
            )
            Text(
                text = "Refresh",
                fontSize = ChatTheme.fonts.reviewLoading,
                color = iconColor,
            )
        }
    }
}

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
 * All VCS reads happen on Dispatchers.IO inside readAction;
 * only DiffManager.showDiff runs on EDT via invokeLater.
 *
 * If the Change is stale (committed/reverted), falls back to opening the file.
 * Uses full fileName (not extension) as 3rd arg to DiffContentFactory for syntax highlighting.
 *
 * @param scope CoroutineScope for launching the VCS read — should be tied to
 *   the caller's lifecycle (e.g., the composable's rememberCoroutineScope)
 *   to prevent coroutine leaks after project disposal.
 */
fun openDiffForPath(project: Project, filePath: String, virtualFile: com.intellij.openapi.vfs.VirtualFile?, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val changeListManager = ChangeListManager.getInstance(project)
            val change = readAction {
                val currentChanges = changeListManager.defaultChangeList.changes.toList()
                currentChanges.find {
                    getRelativePath(project, it) == filePath
                }
            }

            if (change != null) {
                val fileName = readAction { change.virtualFile?.name } ?: filePath.substringAfterLast('/')
                // Resolve FileType from file name for syntax highlighting in diff viewer
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                val beforeContent = readAction {
                    change.beforeRevision?.content
                } ?: ""
                val afterContent = readAction {
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
                // Stash the relative source path so ReviewCommentDiffExtension
                // can recover it and apply review-comment highlights to the
                // diff viewer's after-side editor (TDD §4.3 — NOT via
                // ContentDiffRequest.contentTitles, which are display labels).
                if (request is SimpleDiffRequest) {
                    request.putUserData(ReviewCommentDiffExtension.REVIEW_PATH_KEY, filePath)
                }
                // DiffManager.showDiff must run on EDT.
                ApplicationManager.getApplication().invokeLater {
                    DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)
                }
            } else if (virtualFile != null) {
                // Change was committed/reverted — open file directly
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // PCE must be rethrown — swallowing it breaks cancellation propagation
            // (IntelliJ contract: ProcessCanceledException is control flow, not an error).
            throw e
        } catch (e: OutOfMemoryError) {
            // JVM-level errors must propagate — never swallow these.
            throw e
        } catch (e: StackOverflowError) {
            throw e
        } catch (e: NoClassDefFoundError) {
            // Missing optional dependency — indicates a broken plugin installation.
            // Log at ERROR level so the user is aware of the configuration issue.
            com.intellij.openapi.diagnostic.Logger.getInstance("ACP")
                .error("[ACP] Failed to open diff viewer for $filePath — missing dependency: ${e.message}")
        } catch (e: Exception) {
            // Log for diagnostics — silently swallowing makes diff-open failures invisible.
            com.intellij.openapi.diagnostic.Logger.getInstance("ACP")
                .warn("[ACP] Failed to open diff viewer for $filePath: ${e.message}")
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