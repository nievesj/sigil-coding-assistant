package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTargetModifierNode
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sqrt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.CommandHistoryEntry
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.model.TodoItem
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import androidx.compose.foundation.TooltipArea
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.DataFlavor
import com.opencode.acp.util.decodeFileToBitmap
import com.opencode.acp.util.saveClipboardImageToDisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Validates that an attached file's canonical path is within the allowed directories
 * (project base, project .opencode/attachments, or user home .opencode/attachments).
 * Returns true if the path is allowed, false otherwise.
 */
private fun isAttachedPathAllowed(
    attachedPath: String,
    projectBase: String?,
    userHome: String?,
): Boolean {
    val canonicalAttached = com.opencode.acp.chat.util.AttachmentPathValidator.canonicalizeOrReject(attachedPath) ?: return false
    return com.opencode.acp.chat.util.AttachmentPathValidator.isAllowed(canonicalAttached, projectBase, userHome)
}

/**
 * Thin wrapper that delegates to [ClipboardReader.readClipboardContent].
 *
 * Kept as a top-level function so the existing `InputAreaClipboardTest` regression
 * test (which calls `readClipboardContent(null)` as a top-level function) continues
 * to compile and pass unchanged after the Phase 4 extraction
 * (TDD `docs/tdd/ui-testability-refactor.md` §9 step 9).
 *
 * The actual implementation lives in [ClipboardReader] (extracted from this file).
 */
suspend fun readClipboardContent(project: com.intellij.openapi.project.Project? = null): ClipboardResult? =
    ClipboardReader.readClipboardContent(project)

/**
 * ModifierNodeElement that attaches a [DragAndDropTarget] to the modifier chain.
 * This is needed because the bundled Compose (CMP 1.10) does not expose a
 * [Modifier.dragAndDropTarget] extension — only the node-based API.
 *
 * Note: The factory function [DragAndDropTargetModifierNode] returns the sealed interface type,
 * not [Modifier.Node]. However, the underlying implementation ([DragAndDropNode]) IS a
 * [Modifier.Node], so the cast is safe.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class DropTargetElement(
    private val target: DragAndDropTarget,
    private val shouldStartDragAndDrop: (DragAndDropEvent) -> Boolean,
) : ModifierNodeElement<Modifier.Node>() {
    @Suppress("UNCHECKED_CAST")
    override fun create(): Modifier.Node = DragAndDropTargetModifierNode(
        shouldStartDragAndDrop = shouldStartDragAndDrop,
        target = target,
    ) as Modifier.Node

    override fun update(node: Modifier.Node) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "dragAndDropTarget"
    }

    // equals()/hashCode() compare ONLY the `target` (not `shouldStartDragAndDrop`).
    //
    // Rationale: `shouldStartDragAndDrop` is a fresh lambda created on every
    // DropTargetElement construction. Lambda references use identity equality
    // (Object.equals), so two equivalent-but-distinct lambda instances are never
    // equal — including `shouldStartDragAndDrop` in equals() would make the
    // ModifierNodeElement appear to change on every recomposition, triggering
    // unnecessary update()/create() cycles.
    //
    // The `target` is constructed via `remember(onAttachFile, onImagePasted)`
    // (see line ~279), so its identity changes appropriately when the callbacks
    // change. `shouldStartDragAndDrop` is a derived property of `target` (it
    // delegates to the target's `onDrop`/acceptance logic), so comparing `target`
    // alone is sufficient — when the target changes, the element changes; when
    // the target is stable, the element is stable (no spurious updates).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DropTargetElement) return false
        return target === other.target
    }

    override fun hashCode(): Int = target.hashCode()
}

@OptIn(ExperimentalJewelApi::class, ExperimentalComposeUiApi::class)
@Composable
fun InputArea(
    enabled: Boolean,
    isStreaming: Boolean,
    controlState: ControlBarState,
    contextState: SessionContextState = SessionContextState.Loading,
    onSend: (String, List<AttachedFile>) -> Unit,
    onCancel: () -> Unit,
    onAgentChanged: (OpenCodeAgentInfo) -> Unit,
    onModelChanged: (ProviderModel) -> Unit,
    onThinkingChanged: (ThinkingEffort) -> Unit,
    onShowContextDetails: () -> Unit = {},
    onRetryContext: () -> Unit = {},
    attachedFiles: List<AttachedFile> = emptyList(),
    onAttachFile: (AttachedFile) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onImagePasted: (AttachedFile) -> Unit = {},
    onImagePreview: (String) -> Unit = {},
    pasteTextSignal: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    recentFiles: List<RecentFile> = emptyList(),
    searchResults: List<RecentFile> = emptyList(),
    onSearch: (String) -> Unit = {},
    onFilesAndFolders: () -> Unit = {},
    onImage: () -> Unit = {},
    onRecentFileClick: (RecentFile) -> Unit = {},
    onSlashCommand: (SlashCommand) -> Unit = {},
    commands: List<SlashCommand> = emptyList(),
    // @ mention file autocomplete
    mentionFiles: List<RecentFile> = emptyList(),
    onMentionSearch: (String) -> Unit = {},
    onMentionFileSelected: (RecentFile) -> Unit = {},
    todos: List<TodoItem> = emptyList(),
    commandHistory: List<CommandHistoryEntry> = emptyList(),
    onLoadHistoryEntry: (CommandHistoryEntry) -> Unit = {},
    project: com.intellij.openapi.project.Project? = null,
    isConnected: Boolean = false,                    // NEW: connectionState == CONNECTED
    isReconnecting: Boolean = false,                 // NEW: connectionState == RECONNECTING
    isFollowEnabled: Boolean = false,                // NEW
    onDisconnect: () -> Unit = {},                   // NEW
    onToggleFollow: () -> Unit = {},                 // NEW
    isBraveModeEnabled: Boolean = false,               // NEW: brave mode state
    onToggleBraveMode: () -> Unit = {},               // NEW: brave mode toggle
    placeholderText: String = "Type a message...",
    ) {
    val textState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Slash command palette state: shown when text starts with "/"
    var showSlashPalette by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val currentText = textState.text.toString()
    // Only treat a single leading "/" as a slash command query; "//" is the
    // escape mechanism to send literal text starting with "/".
    val slashQuery = if (currentText.startsWith("/") && !currentText.startsWith("//")) currentText.substring(1) else ""

    val filtered = remember(slashQuery, commands) {
        if (slashQuery.isBlank()) commands
        else commands.filter { it.name.startsWith(slashQuery, ignoreCase = true) }
    }

    LaunchedEffect(filtered.size) { selectedIndex = 0 }

    /** Extract trailing args from slashQuery for the given command. */
    fun extractArgs(cmd: SlashCommand): String =
        if (slashQuery.startsWith(cmd.name, ignoreCase = true) && slashQuery.length > cmd.name.length) {
            slashQuery.substring(cmd.name.length).trim()
        } else ""

    // @ mention palette state: shown when the user types "@" followed by a file query.
    // The @ can appear anywhere in the text (not just at the start like slash commands).
    // We detect the last "@" and extract the query text between it and the cursor/next space.
    var showMentionPalette by remember { mutableStateOf(false) }
    var mentionSelectedIndex by remember { mutableStateOf(0) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) } // text offset of the "@" char

    // Filter mention files by the current query — open files first, then others
    val filteredMentionFiles = remember(mentionQuery, mentionFiles) {
        if (mentionQuery.isBlank()) {
            mentionFiles.take(20)
        } else {
            mentionFiles.filter {
                it.name.contains(mentionQuery, ignoreCase = true) ||
                it.path.contains(mentionQuery, ignoreCase = true)
            }.take(20)
        }
    }

    LaunchedEffect(filteredMentionFiles.size) { mentionSelectedIndex = 0 }

    // Command history navigation state
    var historyIndex by remember { mutableStateOf(-1) }  // -1 = not navigating, 0 = newest, 1 = next older...
    var draftText by remember { mutableStateOf("") }
    var draftFiles by remember { mutableStateOf<List<AttachedFile>>(emptyList()) }
    var inHistoryMode by remember { mutableStateOf(false) }

    /** Snapshot the current input as the draft (only once per history session). */
    fun saveDraftIfNeeded(currentAttachedFiles: List<AttachedFile>) {
        if (!inHistoryMode) {
            draftText = textState.text.toString()
            draftFiles = currentAttachedFiles.toList() // snapshot
            inHistoryMode = true
        }
    }

    /** Load a history entry into the text field and attached files. */
    fun loadHistoryEntry(index: Int) {
        if (index !in commandHistory.indices) return
        val entry = commandHistory[index]
        textState.edit { replace(0, length, entry.text) }
        historyIndex = index
        onLoadHistoryEntry(entry)
    }

    // Drag-and-drop target for file drops
    val fileDropTarget = remember(onAttachFile, onImagePasted) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDragging = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragging = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragging = false
                val dragData = event.dragData()

                when (dragData) {
                    is DragData.FilesList -> {
                        val uris = dragData.readFiles()
                        uris.map { uri ->
                            try {
                                // Reject non-file URI schemes (e.g. http://, ftp://) to
                                // prevent attaching arbitrary remote resources. Only
                                // local file:// URIs are allowed for drag-and-drop.
                                val parsed = try { java.net.URI(uri) } catch (e: java.net.URISyntaxException) {
                                    logger.warn { "[ACP] Drag-and-drop: malformed URI: ${uri.take(50)}" }
                                    null
                                }
                                if (parsed == null) {
                                    null
                                } else if (parsed.scheme?.lowercase() != "file") {
                                    logger.warn { "[ACP] Drag-and-drop: rejecting non-file URI scheme: ${parsed.scheme}" }
                                    null
                                } else {
                                    val file = java.io.File(parsed)
                                    // toAttachedFile copies external files into the allowed attachment dir.
                                    // Post-condition: verify the returned AttachedFile.path is within
                                    // allowed dirs after the copy. This guards against a copy failure
                                    // silently returning the raw external path, which would bypass the
                                    // attachment security boundary.
                                    val projectBase = project?.basePath?.let { com.opencode.acp.chat.util.AttachmentPathValidator.canonicalizeOrReject(it) }
                                    val userHome = System.getProperty("user.home")?.let { com.opencode.acp.chat.util.AttachmentPathValidator.canonicalizeOrReject(it) }
                                    val attached = file.toAttachedFile(project)
                                    val isAllowed = attached != null && isAttachedPathAllowed(attached.path, projectBase, userHome)
                                    if (isAllowed) attached else {
                                        logger.warn { "[ACP] Drag-and-drop: attached file path outside allowed dirs after copy: ${attached?.path}" }
                                        null
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Drag-and-drop: failed to process file URI: ${uri.take(50)}" }
                                null
                            }
                        }.filterNotNull().let { attached ->
                            attached.forEach { file ->
                                onAttachFile(file)
                            }
                            return attached.isNotEmpty()
                        }
                    }
                    is DragData.Image -> {
                        // DragData.Image.readImage() returns Painter (CMP 1.10), which we can't
                        // convert to an AttachedFile. Fall through to the AWT transferable
                        // fallback below, which handles image drops via DataFlavor.imageFlavor.
                        null
                    }
                }

                // AWT fallback: handles DragData.Image (reads as AWT Image),
                // unknown drag data, and any case above that fell through.
                try {
                    val transferable = event.awtTransferable

                    // Try image first (covers DragData.Image and clipboard image drops)
                    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        try {
                            val image = transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                            if (image != null) {
                                val bufferedImage = image.toBufferedImage()
                                val savedPath = saveClipboardImageToDisk(bufferedImage, project)
                                if (savedPath != null) {
                                    val filename = java.io.File(savedPath).name
                                    val attached = AttachedFile(
                                        name = filename,
                                        path = savedPath,
                                        mime = "image/png",
                                    )
                                    onImagePasted(attached)
                                    return true
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "[ACP] Drag-and-drop AWT fallback: image read failed" }
                        }
                    }

                    // Try file list
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                        var anyAttached = false
                        // Canonicalize the base paths once before the loop. Wrap in
                        // try-catch so a canonicalization failure (e.g., network drive
                        // disconnected, invalid chars on Windows) falls back to the
                        // non-canonical path instead of aborting ALL file attachments.
                        // Matches the pattern in OpenCodeService.kt:115-128.
                        val projectBase = project?.basePath?.let { basePath ->
                            try {
                                java.io.File(basePath).canonicalPath.trimEnd(java.io.File.separatorChar)
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Drag-and-drop AWT fallback: project base canonicalization failed, using raw path" }
                                basePath.trimEnd(java.io.File.separatorChar)
                            }
                        }
                        val userHome = System.getProperty("user.home")?.let { home ->
                            try {
                                java.io.File(home).canonicalPath.trimEnd(java.io.File.separatorChar)
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Drag-and-drop AWT fallback: user.home canonicalization failed, using raw path" }
                                home.trimEnd(java.io.File.separatorChar)
                            }
                        }
                        files?.forEach { file ->
                            try {
                                val attached = file.toAttachedFile(project)
                                // Post-condition: verify the returned AttachedFile.path is within
                                // allowed dirs after toAttachedFile (which copies external files in).
                                // This guards against a copy failure silently returning the raw external
                                // path, which would bypass the attachment security boundary.
                                val isAllowed = attached != null && isAttachedPathAllowed(attached.path, projectBase, userHome)
                                if (isAllowed) {
                                    onAttachFile(attached)
                                    anyAttached = true
                                } else {
                                    logger.warn { "[ACP] Drag-and-drop AWT fallback: attached file path outside allowed dirs after copy: ${attached?.path}" }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Drag-and-drop AWT fallback: file attach failed" }
                            }
                        }
                        return anyAttached
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Drag-and-drop AWT fallback failed" }
                }

                return false
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Collect text paste signals and insert into text field.
    // The cursor position is read INSIDE the edit block so the read and write
    // are atomic within the edit — two rapid pastes in the same frame cannot
    // interleave and insert at stale cursors.
    if (pasteTextSignal != null) {
        LaunchedEffect(pasteTextSignal) {
            pasteTextSignal.collect { text ->
                textState.edit {
                    val cursorPos = selection.start
                    replace(cursorPos, cursorPos, text)
                }
            }
        }
    }

    // Watch text changes to show/hide slash palette.
    // The palette stays open as long as the text starts with "/" and has no
    // newline — commands with args (e.g. "/review-perform glm5.2 claude") can
    // be long, so we don't cap by length here. The palette itself filters by
    // command-name prefix; non-matching text shows "No matching commands".
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { textState.text.toString() }
            .collect { text ->
                // Show palette only for a single leading "/" — "//" is the escape
                // mechanism to send literal text starting with "/".
                showSlashPalette = text.startsWith("/") && !text.startsWith("//") && !text.contains("\n")
            }
    }

    // Watch text changes to detect "@" mention triggers.
    // The "@" can appear anywhere in the text. We find the last "@" before the
    // cursor and extract the query between it and the next whitespace or cursor.
    // The palette is dismissed if:
    //   - there's no "@" in the text
    //   - the text after "@" contains whitespace (user typed a space, ending the mention)
    //   - the slash palette is active (slash commands take priority)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { textState.text.toString() }
            .collect { text ->
                if (showSlashPalette) {
                    showMentionPalette = false
                    return@collect
                }
                val cursorPos = textState.selection.start
                val result = detectMentionTrigger(text, cursorPos)
                if (result.active) {
                    showMentionPalette = true
                    mentionQuery = result.query
                    mentionStartIndex = result.startIndex
                    // Trigger search for the query
                    onMentionSearch(result.query)
                } else {
                    showMentionPalette = false
                    mentionQuery = ""
                    mentionStartIndex = -1
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTheme.dims.inputOuterPaddingH, vertical = ChatTheme.dims.inputOuterPaddingV),
    ) {
        // Slash command palette — shown above the input when text starts with "/"
        if (showSlashPalette) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomStart
            ) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, -4),
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                    onDismissRequest = { showSlashPalette = false },
                ) {
                    SlashCommandPalette(
                        filtered = filtered,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { selectedIndex = it },
                        onCommandSelected = { command ->
                            val args = extractArgs(command)
                            showSlashPalette = false
                            textState.edit { replace(0, length, "") }
                            onSlashCommand(command.copy(args = args))
                        },
                        onDismiss = { showSlashPalette = false },
                    )
                }
            }
        }

        // @ mention palette — shown above the input when the user types "@query"
        if (showMentionPalette && !showSlashPalette) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomStart
            ) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, -4),
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                    onDismissRequest = { showMentionPalette = false },
                ) {
                    MentionPalette(
                        filtered = filteredMentionFiles,
                        selectedIndex = mentionSelectedIndex,
                        onSelectedIndexChange = { mentionSelectedIndex = it },
                        onFileSelected = { file ->
                            // Replace the "@query" text with "@filename" and attach the file
                            val text = textState.text.toString()
                            val endIdx = textState.selection.start.coerceIn(0, text.length)
                            if (mentionStartIndex >= 0 && endIdx > mentionStartIndex) {
                                textState.edit {
                                    replace(mentionStartIndex, endIdx, "@${file.name}")
                                }
                            }
                            showMentionPalette = false
                            onMentionFileSelected(file)
                        },
                        onDismiss = { showMentionPalette = false },
                    )
                }
            }
        }

        // Todo list panel — shown above input when there are active todos
        if (todos.isNotEmpty()) {
            val incomplete = todos.filter { it.status != "completed" && it.status != "cancelled" }
            if (incomplete.isNotEmpty()) {
                TodoListPanel(
                    todos = todos,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Text area container with rounded corners and border
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Animated blue glow when LLM is streaming.
            //
            // A fixed seamless sweep gradient (transparent at 0°/360°, bright bump
            // at 180°) is rotated each frame via DrawScope.rotate to orbit the bright
            // spot around the input box.  clipPath limits the gradient to a ring
            // (outer rounded rect minus inner rounded rect) so only the border glows.
            //
            // Because the gradient never changes and the seam (0°/360°) is always in
            // the transparent region 180° opposite the bump, the bump crosses every
            // corner smoothly — no stop shifting, no seam artifact.
            //
            // The brush is built once (remember) and only the canvas rotation changes
            // per frame inside drawBehind, so the composable body does not recompose
            // on every animation frame — only the draw scope re-executes.
            if (isStreaming) {
                // Throttled glow rotation — replaces rememberInfiniteTransition to reduce
                // GPU command flush pressure. See rememberThrottledInfiniteAnimation docs.
                val rotation = rememberThrottledInfiniteAnimation(
                    active = isStreaming,
                    initialValue = 0f,
                    targetValue = 360f,
                    durationMillis = ChatTheme.animations.glowPulseMs,
                    repeatMode = RepeatMode.Restart,
                    label = "glow",
                )
                val density = LocalDensity.current
                val cornerRadiusPx = with(density) { ChatTheme.dims.inputCornerRadius.toPx() }

                // Read @Composable color tokens into local vals before remember
                val glowTransparent = ChatTheme.colors.component.glowTransparent
                val glowStart = ChatTheme.colors.component.glowStart
                val glowPeak = ChatTheme.colors.component.glowPeak
                val glowHot = ChatTheme.colors.component.glowHot

                // Fixed seamless gradient: transparent at 0°/360°, bright bump at 180°.
                // The seam is always in the transparent region — never crosses the bump.
                val seamlessBrush = remember(glowTransparent, glowStart, glowPeak, glowHot) {
                    Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.00f to glowTransparent,
                            0.35f to glowTransparent,
                            0.42f to glowStart,
                            0.46f to glowPeak,
                            0.50f to glowHot,       // peak at 180° (left side at rotation=0)
                            0.54f to glowPeak,
                            0.58f to glowStart,
                            0.65f to glowTransparent,
                            1.00f to glowTransparent,
                        ),
                        center = Offset.Unspecified   // resolves to size.center at shader creation
                    )
                }

                val ringPath = remember { Path() }
                val outerPath = remember { Path() }
                val innerPath = remember { Path() }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            val angleDeg = rotation.value
                            val glowPx = 3.dp.toPx()
                            val halfGlow = glowPx / 2f

                            // Build ring path: outer rounded rect minus inner rounded rect
                            val outerRR = RoundRect(
                                left = -halfGlow, top = -halfGlow,
                                right = size.width + halfGlow, bottom = size.height + halfGlow,
                                cornerRadius = CornerRadius(cornerRadiusPx + halfGlow, cornerRadiusPx + halfGlow)
                            )
                            val innerRR = RoundRect(
                                left = halfGlow, top = halfGlow,
                                right = size.width - halfGlow, bottom = size.height - halfGlow,
                                cornerRadius = CornerRadius(
                                    (cornerRadiusPx - halfGlow).coerceAtLeast(0f),
                                    (cornerRadiusPx - halfGlow).coerceAtLeast(0f)
                                )
                            )
                            ringPath.reset()
                            outerPath.reset()
                            outerPath.addRoundRect(outerRR)
                            innerPath.reset()
                            innerPath.addRoundRect(innerRR)
                            ringPath.op(outerPath, innerPath, PathOperation.Difference)

                            // Diagonal ensures the circle covers the entire ring when rotated
                            val diagonal = sqrt(size.width * size.width + size.height * size.height)

                            // Clip to the ring (axis-aligned, not rotated), then rotate the scope
                            // and draw a filled circle. The circle is rotation-invariant, so only
                            // the sweep gradient pattern rotates. The clip limits visible pixels
                            // to the ring shape.
                            clipPath(ringPath) {
                                rotate(angleDeg, center) {
                                    drawCircle(
                                        brush = seamlessBrush,
                                        radius = diagonal / 2f,
                                        center = center
                                    )
                                }
                            }
                        }
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ChatTheme.shapes.inputCornerRadius)
                    .background(if (isDragging) ChatTheme.colors.component.dragActiveBg else ChatTheme.colors.component.inputBg)
                    .border(
                        width = if (isDragging) ChatTheme.dims.inputDragBorderWidth else ChatTheme.dims.inputDefaultBorderWidth,
                        color = if (isDragging) ChatTheme.colors.accent.green else ChatTheme.colors.component.inputBorder,
                        shape = ChatTheme.shapes.inputCornerRadius,
                    )
                    .then(
                        DropTargetElement(
                            target = fileDropTarget,
                            shouldStartDragAndDrop = { true },
                        )
                    ),
            ) {
            Column {
                // Attached images — thumbnails inside the box
                if (attachedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        attachedFiles.forEachIndexed { index, file ->
                            val isImage = file.mime.startsWith("image/")
                            if (isImage) {
                                // Image thumbnail with click-to-preview
                                var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                                LaunchedEffect(file.path) {
                                    bitmap = withContext(Dispatchers.IO) { try { decodeFileToBitmap(file.path) } catch (_: Exception) { null } }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(ChatTheme.dims.attachmentThumbnailSize)
                                        .clip(ChatTheme.shapes.attachmentCornerRadius)
                                        .background(ChatTheme.colors.component.attachmentBg)
                                        .clickable { onImagePreview(file.path) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bitmap != null) {
                                        ComposeImage(
                                            bitmap = bitmap!!,
                                            contentDescription = file.name,
                                            modifier = Modifier
                                                .size(ChatTheme.dims.attachmentThumbnailSize)
                                                .clip(ChatTheme.shapes.attachmentCornerRadius),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Icon(
                                            key = AllIconsKeys.FileTypes.Image,
                                            contentDescription = file.name,
                                            modifier = Modifier.size(20.dp),
                                            tint = ChatTheme.colors.component.attachmentRemoveIcon,
                                        )
                                    }
                                    // Remove button overlay
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(ChatTheme.dims.attachmentImageRemoveSize)
                                            .clip(ChatTheme.shapes.imageRemoveBadgeShape)
                                            .background(ChatTheme.colors.component.attachmentImageOverlay)
                                            .clickable { onRemoveFile(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            key = AllIconsKeys.Actions.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(ChatTheme.dims.attachmentImageRemoveBadge),
                                            tint = ChatTheme.colors.component.attachmentImageRemove,
                                        )
                                    }
                                }
                            } else {
                                // Non-image file pill
                                Row(
                                    modifier = Modifier
                                        .clip(ChatTheme.shapes.attachmentCornerRadius)
                                        .background(ChatTheme.colors.component.attachmentBg)
                                        .padding(horizontal = ChatTheme.dims.attachmentChipPaddingH, vertical = ChatTheme.dims.attachmentChipPaddingV),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        key = AllIconsKeys.FileTypes.Text,
                                        contentDescription = null,
                                        modifier = Modifier.size(ChatTheme.dims.attachmentFileIconSize),
                                        tint = ChatTheme.colors.component.attachmentRemoveIcon,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = file.name,
                                        fontSize = ChatTheme.fonts.attachmentFileName,
                                        color = ChatTheme.colors.component.attachmentRemoveIcon,
                                        maxLines = 1,
                                        modifier = Modifier.widthIn(max = 120.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(ChatTheme.shapes.fileRemoveBadgeShape)
                                            .clickable { onRemoveFile(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            key = AllIconsKeys.Actions.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(ChatTheme.dims.attachmentFileRemoveBadge),
                                            tint = ChatTheme.colors.component.attachmentFileSize,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Input area: Text field on top, buttons below
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    // Text area — grows with content, 2 rows min, max ~7 lines
                    val scrollState = rememberScrollState()
                    val lineCount = textState.text.lines().size.coerceAtLeast(1)
                    val maxLines = 7
                    val lineHeight = ChatTheme.dims.inputLineHeight
                    val targetHeight = (lineCount.coerceAtMost(maxLines) * lineHeight.value).dp + 16.dp // 16dp for vertical padding
                    val textFieldHeight = targetHeight.coerceIn(ChatTheme.dims.inputMinHeight, ChatTheme.dims.inputMaxHeight)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(textFieldHeight)
                            .padding(horizontal = ChatTheme.dims.inputContentPaddingH, vertical = ChatTheme.dims.inputContentPaddingV),
                    ) {
                        BasicTextField(
                            state = textState,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    // Build the immutable state snapshot for the pure reducer.
                                    // The reducer decides the action; the executor below applies
                                    // it to the real TextFieldState and Compose state.
                                    val currentTextStr = textState.text.toString()
                                    val trimmedText = currentTextStr.trim()
                                    val hasMatchingSlashCommand = trimmedText.startsWith("/") &&
                                        !trimmedText.startsWith("//") &&
                                        run {
                                            val firstWord = trimmedText.substring(1).substringBefore(" ")
                                            commands.any { it.name.equals(firstWord, ignoreCase = true) }
                                        }
                                    val state = InputKeyboardState(
                                        text = currentTextStr,
                                        cursorPos = textState.selection.start.coerceIn(0, currentTextStr.length),
                                        showSlashPalette = showSlashPalette,
                                        showMentionPalette = showMentionPalette,
                                        showAttachMenu = showAttachMenu,
                                        filteredSlashSize = filtered.size,
                                        filteredMentionSize = filteredMentionFiles.size,
                                        slashSelectedIndex = selectedIndex,
                                        mentionSelectedIndex = mentionSelectedIndex,
                                        historyIndex = historyIndex,
                                        inHistoryMode = inHistoryMode,
                                        commandHistorySize = commandHistory.size,
                                        hasMatchingSlashCommand = hasMatchingSlashCommand,
                                    )
                                    val action = InputKeyboardHandler.handleKeyEvent(event, state)
                                    // Executor: apply the action to the real Compose state.
                                    // Preserves the exact behavior of the original inline handler.
                                    when (action) {
                                        is InputKeyboardAction.SelectSlashIndex -> {
                                            selectedIndex = action.index
                                            true
                                        }
                                        is InputKeyboardAction.SelectMentionIndex -> {
                                            mentionSelectedIndex = action.index
                                            true
                                        }
                                        InputKeyboardAction.SelectMentionFile -> {
                                            val file = filteredMentionFiles.getOrNull(mentionSelectedIndex)
                                                ?: filteredMentionFiles.firstOrNull()
                                            if (file != null) {
                                                val text = textState.text.toString()
                                                val endIdx = textState.selection.start.coerceIn(0, text.length)
                                                if (mentionStartIndex >= 0 && endIdx > mentionStartIndex) {
                                                    textState.edit {
                                                        replace(mentionStartIndex, endIdx, "@${file.name}")
                                                    }
                                                }
                                                showMentionPalette = false
                                                onMentionFileSelected(file)
                                            }
                                            true
                                        }
                                        InputKeyboardAction.DismissMention -> {
                                            showMentionPalette = false
                                            true
                                        }
                                        is InputKeyboardAction.NavigateHistory -> {
                                            saveDraftIfNeeded(attachedFiles)
                                            val newIndex = if (historyIndex < 0) 0
                                                else (historyIndex + action.delta).coerceAtMost(commandHistory.size - 1)
                                            if (newIndex != historyIndex || historyIndex < 0) {
                                                loadHistoryEntry(newIndex)
                                            }
                                            true
                                        }
                                        InputKeyboardAction.RestoreDraft -> {
                                            textState.edit { replace(0, length, draftText) }
                                            historyIndex = -1
                                            inHistoryMode = false
                                            onLoadHistoryEntry(CommandHistoryEntry(draftText, draftFiles))
                                            true
                                        }
                                        InputKeyboardAction.ExecuteSlashCommand -> {
                                            if (showSlashPalette) {
                                                // Palette-visible path: execute the selected command.
                                                val cmd = filtered.getOrNull(selectedIndex) ?: filtered.firstOrNull()
                                                if (cmd != null) {
                                                    val args = extractArgs(cmd)
                                                    showSlashPalette = false
                                                    textState.edit { replace(0, length, "") }
                                                    onSlashCommand(cmd.copy(args = args))
                                                }
                                            } else {
                                                // Slash interception path: text starts with "/" and the
                                                // first word matches a known command. Execute it with
                                                // trailing args instead of sending to the server.
                                                val text = textState.text.toString().trim()
                                                val firstWord = text.substring(1).substringBefore(" ")
                                                val matchedCmd = commands.filter { it.name.equals(firstWord, ignoreCase = true) }
                                                    .firstOrNull()
                                                if (matchedCmd != null) {
                                                    val args = if (text.length > firstWord.length + 1) {
                                                        text.substring(firstWord.length + 1).trim()
                                                    } else ""
                                                    logger.debug { "[ACP] Slash command intercepted: /${matchedCmd.name} args='$args'" }
                                                    showSlashPalette = false
                                                    inHistoryMode = false
                                                    historyIndex = -1
                                                    textState.edit { replace(0, length, "") }
                                                    onSlashCommand(matchedCmd.copy(args = args))
                                                }
                                            }
                                            true
                                        }
                                        InputKeyboardAction.Send -> {
                                            val text = textState.text.toString().trim()
                                            // Escape mechanism: "//" prefix strips one "/" and sends
                                            // the rest as literal text (lets the user send text that
                                            // starts with "/" without triggering a slash command).
                                            val sendText = if (text.startsWith("//")) text.substring(1) else text
                                            if (sendText.isNotEmpty()) {
                                                onSend(sendText, attachedFiles)
                                                textState.edit { replace(0, length, "") }
                                                showSlashPalette = false
                                                inHistoryMode = false
                                                historyIndex = -1
                                            }
                                            true
                                        }
                                        InputKeyboardAction.InsertNewline -> {
                                            // Explicitly insert newline — CMP BasicTextField
                                            // doesn't always handle this via pass-through
                                            val pos = textState.selection.start
                                            textState.edit { replace(pos, pos, "\n") }
                                            showSlashPalette = false
                                            showMentionPalette = false
                                            true
                                        }
                                        InputKeyboardAction.Cancel -> {
                                            onCancel()
                                            true
                                        }
                                        InputKeyboardAction.DismissSlashPalette -> {
                                            showSlashPalette = false
                                            true
                                        }
                                        InputKeyboardAction.DismissAttachMenu -> {
                                            showAttachMenu = false
                                            true
                                        }
                                        InputKeyboardAction.None -> false
                                    }
                                },
                            enabled = enabled,
                            cursorBrush = SolidColor(ChatTheme.colors.component.inputCursor),
                            textStyle = TextStyle(
                                color = ChatTheme.colors.component.inputText,
                                fontSize = ChatTheme.fonts.inputText,
                            ),
                            decorator = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (textState.text.isEmpty()) {
                                        Text(
                                            text = placeholderText,
                                            color = ChatTheme.colors.component.inputPlaceholder,
                                            fontSize = ChatTheme.fonts.inputPlaceholder,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }

                    // Buttons row: + button | spacer | Send/Stop button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Attach button — opens the attach menu popup
                        Box(
                            modifier = Modifier
                                .size(ChatTheme.dims.modelPickerButtonSize)
                                .clip(ChatTheme.shapes.modelPickerButtonShape)
                                .clickable(enabled = enabled) {
                                    showAttachMenu = !showAttachMenu

                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                key = AllIconsKeys.General.Add,
                                contentDescription = "Attach",
                                modifier = Modifier.size(ChatTheme.dims.actionIconSize),
                                tint = ChatTheme.colors.component.inputPlaceholder,
                            )

                            // Attach menu popup anchored to the + button
                            if (showAttachMenu) {

                                Popup(
                                    alignment = Alignment.BottomStart,
                                    offset = IntOffset(0, 4),
                                    properties = PopupProperties(
                                        focusable = true,
                                        dismissOnBackPress = true,
                                        dismissOnClickOutside = true,
                                    ),
                                    onDismissRequest = { showAttachMenu = false },
                                ) {
                                    AttachMenu(
                                        recentFiles = recentFiles,
                                        searchResults = searchResults,
                                        onFilesAndFolders = {
                                            onFilesAndFolders()
                                        },
                                        onImage = {
                                            onImage()
                                        },
                                        onRecentFileClick = { file ->
                                            onRecentFileClick(file)
                                        },
                                        onDismiss = { showAttachMenu = false },
                                        onSearch = onSearch,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Contextual Send / Stop button:
                        // - Has text → green Send (even during streaming, triggers queue or steer)
                        // - No text + streaming → red Stop
                        // - No text + idle → no button
                        val hasText = textState.text.toString().trim().isNotEmpty()

                        if (hasText) {
                            // Green Send button — always available when there's text to send.
                            // During streaming: queues message (if queue mode) or steers (abort + send).
                            // During idle: triggers normal send.
                            Box(
                                modifier = Modifier
                                    .size(ChatTheme.dims.actionButtonSize)
                                    .clip(ChatTheme.shapes.actionButtonCornerRadius)
                                    .clickable(enabled = enabled) {
                                        val text = textState.text.toString().trim()
                                        if (text.isNotEmpty()) {
                                            onSend(text, attachedFiles)
                                            textState.edit { replace(0, length, "") }
                                            inHistoryMode = false
                                            historyIndex = -1
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    key = AllIconsKeys.Actions.Execute,
                                    contentDescription = "Send",
                                    modifier = Modifier.size(ChatTheme.dims.actionIconSize),
                                    tint = ChatTheme.colors.accent.greenLight,
                                )
                            }
                        } else if (isStreaming) {
                            // Red Stop button — only when empty input + streaming
                            Box(
                                modifier = Modifier
                                    .size(ChatTheme.dims.actionButtonSize)
                                    .clip(ChatTheme.shapes.actionButtonCornerRadius)
                                    .clickable(enabled = enabled) { onCancel() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    key = AllIconsKeys.Actions.Suspend,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(ChatTheme.dims.stopIconSize),
                                    tint = ChatTheme.colors.accent.red,
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Selector row: Agent | Model | Thinking — cohesive dark chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentSelector(controlState, onAgentChanged)

            // Model picker chip + popup
            Box {
                val modelDisplayText = controlState.selectedModel?.displayName?.substringAfter(" / ")?.trim() ?: "Model"
                val selectedProviderIconId = controlState.selectedModel?.providerIconId
                SelectorChip(
                    text = modelDisplayText,
                    onClick = { showModelPicker = !showModelPicker },
                    leadingIcon = if (selectedProviderIconId != null) {
                        { ProviderIcon(providerId = selectedProviderIconId, modifier = Modifier.size(14.dp), tint = ChatTheme.colors.component.inputText) }
                    } else null,
                )

                if (showModelPicker) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, -4),
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                        onDismissRequest = { showModelPicker = false },
                    ) {
                        ModelPickerPanel(
                            models = controlState.models,
                            selectedModel = controlState.selectedModel,
                            onModelSelected = { model ->
                                onModelChanged(model)
                                showModelPicker = false
                            },
                            onDismiss = { showModelPicker = false },
                        )
                    }
                }
            }

            // Thinking selector — only visible when model has thinking variants
            if (controlState.selectedModel?.variants?.isNotEmpty() == true) {
                ThinkingSelector(controlState, onThinkingChanged)
            }

            Spacer(modifier = Modifier.weight(1f))

            // NEW: Follow checkbox — always visible (local setting)
            CheckboxChip(
                label = "Follow Agent",
                tooltip = "When enabled, the editor follows the agent's tool calls — opens files and scrolls to active lines",
                enabled = isFollowEnabled,
                onToggle = onToggleFollow,
                color = ChatTheme.colors.accent.blue,
            )

            // NEW: Brave Mode checkbox — auto-approve all permission prompts
            CheckboxChip(
                label = "Brave Mode",
                tooltip = "When enabled, all tool permission prompts are auto-approved without asking. Explicit deny rules are still enforced.",
                enabled = isBraveModeEnabled,
                onToggle = onToggleBraveMode,
                color = ChatTheme.colors.accent.yellow,
            )

            // NEW: Separator + disconnect (only when connected or reconnecting)
            if (isConnected || isReconnecting) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(ChatTheme.colors.component.inputText.copy(alpha = 0.3f))
                )
                DisconnectButton(
                    isReconnecting = isReconnecting,
                    onClick = onDisconnect,
                )
            }

            // Context indicator — fillable circle showing context usage
            ContextIndicator(
                state = contextState,
                isStreaming = isStreaming,
                onShowDetails = onShowContextDetails,
                onRetry = onRetryContext
            )
        }
    }
}

/**
 * Green dot + "Connected" text (connected) or amber dot + "Reconnecting…" text (reconnecting).
 * Click to disconnect from the OpenCode server. Hidden when disconnected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DisconnectButton(isReconnecting: Boolean, onClick: () -> Unit) {
    val dotColor = if (isReconnecting) {
        ChatTheme.colors.accent.yellow
    } else {
        ChatTheme.colors.accent.green
    }
    val labelText = if (isReconnecting) "Reconnecting…" else "Connected"

    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .background(ChatTheme.colors.component.tooltipBg, RoundedCornerShape(4.dp))
                    .border(1.dp, ChatTheme.colors.component.tooltipBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "Disconnect from OpenCode",
                    color = ChatTheme.colors.component.tooltipText,
                    fontSize = ChatTheme.fonts.selectorChip,
                )
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(ChatTheme.shapes.chipCornerRadius)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = dotColor)
            }
            Text(
                text = labelText,
                fontSize = ChatTheme.fonts.selectorChip,
                color = ChatTheme.colors.text.secondary,
            )
        }
    }
}


