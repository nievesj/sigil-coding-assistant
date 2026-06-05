package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.Image as ComposeImage
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intellij.icons.AllIcons
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.CommandHistoryEntry
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.model.TodoItem
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of reading the clipboard — either an image/file attachment or plain text. */
sealed class ClipboardResult {
    data class FileResult(val file: AttachedFile) : ClipboardResult()
    data class TextResult(val text: String) : ClipboardResult()
}

/**
 * Reads the system clipboard for images, files, or text.
 * Returns [ClipboardResult] if content is present, null otherwise.
 *
 * AWT clipboard access MUST happen on the Event Dispatch Thread.
 */
suspend fun readClipboardContent(): ClipboardResult? {
    val clipResult: Any? = if (java.awt.EventQueue.isDispatchThread()) {
        readClipboardOnEdt()
    } else {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var result: Any? = null
                java.awt.EventQueue.invokeAndWait {
                    result = readClipboardOnEdt()
                }
                result
            } catch (e: Exception) {
                println("[ClipboardPaste] invokeAndWait failed: ${e.message}")
                null
            }
        }
    }

    return withContext(Dispatchers.IO) {
        try {
            when (clipResult) {
                is java.awt.Image -> {
                    val bufferedImage = clipResult.toBufferedImage()
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(bufferedImage, "png", baos)
                    val bytes = baos.toByteArray()
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    val dataUri = "data:image/png;base64,$base64"
                    println("[ClipboardPaste] Created data URI from image, size=${bytes.size} bytes")
                    ClipboardResult.FileResult(AttachedFile(name = "clipboard-image.png", path = "", mime = "image/png", dataUri = dataUri))
                }
                is List<*> -> {
                    val files = clipResult.filterIsInstance<java.io.File>()
                    if (files.isEmpty()) return@withContext null
                    ClipboardResult.FileResult(files.first().toAttachedFile())
                }
                is String -> {
                    if (clipResult.isNotBlank()) {
                        println("[ClipboardPaste] Got text from clipboard, length=${clipResult.length}")
                        ClipboardResult.TextResult(clipResult)
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            println("[ClipboardPaste] Processing failed: ${e.message}")
            null
        }
    }
}

/**
 * Reads clipboard content on the EDT. Returns java.awt.Image, List<java.io.File>, String (text), or null.
 */
private fun readClipboardOnEdt(): Any? {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = clipboard.getContents(null)
        if (transferable == null) {
            println("[ClipboardPaste] Clipboard contents are null")
            return null
        }

        // Log available flavors for debugging
        val flavors = transferable.transferDataFlavors
        println("[ClipboardPaste] Available flavors: ${flavors.map { it.humanPresentableName }}")

        // 1. Try imageFlavor (screenshot, copied image from image editor)
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
            if (image != null) {
                println("[ClipboardPaste] Got image from imageFlavor")
                return image
            }
        }

        // 2. Try javaFileListFlavor (copied image file from OS file manager)
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
            if (files != null && files.isNotEmpty()) {
                // Check if any file is an image
                val imageExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")
                val imageFiles = files.filter { f ->
                    f.extension.lowercase() in imageExts
                }
                if (imageFiles.isNotEmpty()) {
                    println("[ClipboardPaste] Got ${imageFiles.size} image file(s) from javaFileListFlavor: ${imageFiles.map { it.name }}")
                    return imageFiles
                }
            }
        }

        // 3. Try stringFlavor / plain text (text paste from editors, browsers, etc.)
        for (flavor in flavors) {
            if (flavor.mimeType.startsWith("text/plain") && flavor.isRepresentationClassReader) {
                try {
                    val reader = transferable.getTransferData(flavor) as? java.io.Reader
                    if (reader != null) {
                        val text = reader.readText()
                        if (text.isNotBlank()) {
                            println("[ClipboardPaste] Got plain text from flavor: ${flavor.humanPresentableName}, length=${text.length}")
                            return text
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            if (!text.isNullOrBlank()) {
                println("[ClipboardPaste] Got text from stringFlavor, length=${text.length}")
                return text
            }
        }

        println("[ClipboardPaste] No image, file, or text found in clipboard")
    } catch (e: Exception) {
        println("[ClipboardPaste] EDT clipboard read failed: ${e.message}")
    }
    return null
}

/**
 * Converts a java.awt.Image to a BufferedImage.
 */
private fun java.awt.Image.toBufferedImage(): BufferedImage {
    if (this is BufferedImage) return this
    val width = getWidth(null)
    val height = getHeight(null)
    if (width <= 0 || height <= 0) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = bufferedImage.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return bufferedImage
}

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DropTargetElement) return false
        return target === other.target && shouldStartDragAndDrop == other.shouldStartDragAndDrop
    }

    override fun hashCode(): Int {
        var result = target.hashCode()
        result = 31 * result + shouldStartDragAndDrop.hashCode()
        return result
    }
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
    todos: List<TodoItem> = emptyList(),
    commandHistory: List<CommandHistoryEntry> = emptyList(),
    onLoadHistoryEntry: (CommandHistoryEntry) -> Unit = {},
    ) {
    val textState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Slash command palette state: shown when text starts with "/"
    var showSlashPalette by remember { mutableStateOf(false) }
    val currentText = textState.text.toString()
    val slashQuery = if (currentText.startsWith("/")) currentText.substring(1) else ""

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
                                java.io.File(java.net.URI(uri)).toAttachedFile()
                            } catch (e: Exception) {
                                println("[InputArea] Failed to read dropped file: $uri — ${e.message}")
                                null
                            }
                        }.filterNotNull().forEach { file ->
                            onAttachFile(file)
                        }
                        return uris.isNotEmpty()
                    }
                    is DragData.Image -> {
                        try {
                            // DragData.Image.readImage() returns Painter (CMP 1.10),
                            // so we fall through to the AWT transferable approach below.
                            // Drag through to else branch for AWT image extraction.
                            null
                        } catch (_: Exception) {
                            null
                        }
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
                                val baos = ByteArrayOutputStream()
                                ImageIO.write(bufferedImage, "png", baos)
                                val bytes = baos.toByteArray()
                                val base64 = Base64.getEncoder().encodeToString(bytes)
                                val dataUri = "data:image/png;base64,$base64"
                                val attached = AttachedFile(
                                    name = "dropped-image.png",
                                    path = "",
                                    mime = "image/png",
                                    dataUri = dataUri,
                                )
                                onImagePasted(attached)
                                return true
                            }
                        } catch (e: Exception) {
                            println("[InputArea] Failed to extract image from AWT transferable: ${e.message}")
                        }
                    }

                    // Try file list
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                        files?.forEach { file ->
                            try {
                                onAttachFile(file.toAttachedFile())
                            } catch (e: Exception) {
                                println("[InputArea] Failed to process dropped AWT file: ${file.name} — ${e.message}")
                            }
                        }
                        return !files.isNullOrEmpty()
                    }
                } catch (e: Exception) {
                    println("[InputArea] AWT drag-and-drop fallback failed: ${e.message}")
                }

                return false
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Collect text paste signals and insert into text field
    if (pasteTextSignal != null) {
        LaunchedEffect(pasteTextSignal) {
            pasteTextSignal.collect { text ->
                val cursorPos = textState.selection.start
                textState.edit {
                    replace(cursorPos, cursorPos, text)
                }
                println("[InputArea] Pasted text from clipboard, length=${text.length}")
            }
        }
    }

    val inputBg = Color(0xFF2B2B2B)
    val inputBorder = Color(0xFF3E3E3E)
    val mutedText = Color(0xFF808080)
    val accentGreen = Color(0xFF6BBE50)
    val stopRed = Color(0xFFE5534B)

    // Watch text changes to show/hide slash palette
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { textState.text.toString() }
            .collect { text ->
                showSlashPalette = text.startsWith("/") && text.length < 30 && !text.contains("\n")
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        commands = commands,
                        query = slashQuery,
                        onCommandSelected = { command ->
                            showSlashPalette = false
                            textState.edit { replace(0, length, "") }
                            onSlashCommand(command)
                        },
                        onDismiss = { showSlashPalette = false },
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
            // Animated blue glow when LLM is streaming
            if (isStreaming) {
                val infiniteTransition = rememberInfiniteTransition(label = "glow")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2500, easing = LinearEasing)
                    ),
                    label = "rotation"
                )
                val density = LocalDensity.current
                val cornerRadiusPx = with(density) { 12.dp.toPx() }
                val glowPath = remember { androidx.compose.ui.graphics.Path() }

                // Shift gradient stops based on rotation to animate the bright spot.
                // Single bright spot with wide, smooth fade — avoids jumpy corners.
                val offset = rotation / 360f
                val baseStops = floatArrayOf(
                    0.00f, 0.10f, 0.18f, 0.22f, 0.25f, 0.28f, 0.32f, 0.40f, 1.00f
                )
                val baseColors = arrayOf(
                    Color.Transparent,                              // 0.00
                    Color.Transparent,                              // 0.10
                    Color(0xFF4A9EFF).copy(alpha = 0.15f),          // 0.18
                    Color(0xFF4A9EFF).copy(alpha = 0.45f),          // 0.22
                    Color(0xFF00D4FF).copy(alpha = 0.85f),          // 0.25  ← peak
                    Color(0xFF4A9EFF).copy(alpha = 0.45f),          // 0.28
                    Color(0xFF4A9EFF).copy(alpha = 0.15f),          // 0.32
                    Color.Transparent,                              // 0.40
                    Color.Transparent,                              // 1.00
                )
                val shiftedStops = FloatArray(baseStops.size) { i ->
                    ((baseStops[i] + offset) % 1f)
                }
                val shiftedColors = Array(baseColors.size) { i ->
                    baseColors[(i - 0 + baseColors.size) % baseColors.size]
                }
                // Re-sort by stop position to keep sweepGradient happy
                val paired = shiftedStops.indices.map { shiftedStops[it] to baseColors[it] }
                val sorted = paired.sortedBy { it.first }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            val glowPx = 3.dp.toPx()
                            glowPath.reset()
                            glowPath.addRoundRect(
                                RoundRect(
                                    left = 0f,
                                    top = 0f,
                                    right = size.width,
                                    bottom = size.height,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
                                )
                            )
                            drawPath(
                                path = glowPath,
                                brush = Brush.sweepGradient(
                                    colorStops = sorted.toTypedArray(),
                                    center = center
                                ),
                                style = Stroke(width = glowPx)
                            )
                        }
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDragging) Color(0xFF2E3A2E) else inputBg)
                    .border(
                        width = if (isDragging) 2.dp else 1.dp,
                        color = if (isDragging) accentGreen else inputBorder,
                        shape = RoundedCornerShape(12.dp),
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
                                val bitmap = remember(file.dataUri) {
                                    decodeDataUriToBitmap(file.dataUri)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF3E3E3E))
                                        .clickable { onImagePreview(file.dataUri) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bitmap != null) {
                                        ComposeImage(
                                            bitmap = bitmap,
                                            contentDescription = file.name,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Icon(
                                            key = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Image),
                                            contentDescription = file.name,
                                            modifier = Modifier.size(20.dp),
                                            tint = Color(0xFFBBBBBB),
                                        )
                                    }
                                    // Remove button overlay
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF555555))
                                            .clickable { onRemoveFile(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Close),
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(10.dp),
                                            tint = Color(0xFFCCCCCC),
                                        )
                                    }
                                }
                            } else {
                                // Non-image file pill
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF3E3E3E))
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.FileTypes.Text),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFFBBBBBB),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = file.name,
                                        fontSize = 11.sp,
                                        color = Color(0xFFBBBBBB),
                                        maxLines = 1,
                                        modifier = Modifier.widthIn(max = 120.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .clickable { onRemoveFile(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Close),
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(10.dp),
                                            tint = Color(0xFF808080),
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
                    val lineHeight = 20.dp
                    val targetHeight = (lineCount.coerceAtMost(maxLines) * lineHeight.value).dp + 16.dp // 16dp for vertical padding
                    val textFieldHeight = targetHeight.coerceIn(56.dp, 156.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(textFieldHeight)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            state = textState,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when {
                                            // Up arrow — navigate command history (older)
                                            event.key == Key.DirectionUp && !event.isShiftPressed && !showSlashPalette && commandHistory.isNotEmpty() -> {
                                                saveDraftIfNeeded(attachedFiles)
                                                val newIndex = if (historyIndex < 0) 0 else (historyIndex + 1).coerceAtMost(commandHistory.size - 1)
                                                if (newIndex != historyIndex || historyIndex < 0) {
                                                    loadHistoryEntry(newIndex)
                                                }
                                                true
                                            }
                                            // Down arrow — navigate command history (newer) or restore draft
                                            event.key == Key.DirectionDown && !event.isShiftPressed && !showSlashPalette && inHistoryMode -> {
                                                val newIndex = historyIndex - 1
                                                if (newIndex < 0) {
                                                    // Restore draft
                                                    textState.edit { replace(0, length, draftText) }
                                                    historyIndex = -1
                                                    inHistoryMode = false
                                                    onLoadHistoryEntry(CommandHistoryEntry(draftText, draftFiles))
                                                } else {
                                                    loadHistoryEntry(newIndex)
                                                }
                                                true
                                            }
                                            // Enter with slash palette: execute first matching command
                                            event.key == Key.Enter && !event.isShiftPressed && showSlashPalette -> {
                                                val match = commands.filter { it.name.startsWith(slashQuery, ignoreCase = true) }
                                                if (match.isNotEmpty()) {
                                                    showSlashPalette = false
                                                    textState.edit { replace(0, length, "") }
                                                    onSlashCommand(match.first())
                                                }
                                                true
                                            }
                                            event.key == Key.Enter && !event.isShiftPressed -> {
                                                val text = textState.text.toString().trim()
                                                if (text.isNotEmpty()) {
                                                    onSend(text, attachedFiles)
                                                    textState.edit { replace(0, length, "") }
                                                }
                                                showSlashPalette = false
                                                inHistoryMode = false
                                                historyIndex = -1
                                                true
                                            }
                                            event.key == Key.Enter && event.isShiftPressed -> {
                                                // Explicitly insert newline — CMP BasicTextField
                                                // doesn't always handle this via pass-through
                                                val pos = textState.selection.start
                                                textState.edit { replace(pos, pos, "\n") }
                                                showSlashPalette = false
                                                true
                                            }
                                            event.key == Key.Escape -> {
                                                if (showSlashPalette) {
                                                    showSlashPalette = false
                                                    true
                                                } else if (showAttachMenu) {
                                                    showAttachMenu = false
                                                    true
                                                } else if (inHistoryMode) {
                                                    // Cancel history navigation and restore draft
                                                    textState.edit { replace(0, length, draftText) }
                                                    onLoadHistoryEntry(CommandHistoryEntry(draftText, draftFiles))
                                                    inHistoryMode = false
                                                    historyIndex = -1
                                                    true
                                                } else {
                                                    onCancel()
                                                    true
                                                }
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            enabled = enabled,
                            cursorBrush = SolidColor(Color.White),
                            textStyle = TextStyle(
                                color = Color(0xFFCCCCCC),
                                fontSize = 13.sp,
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
                                            text = "Type a message...",
                                            color = mutedText,
                                            fontSize = 13.sp,
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
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable(enabled = enabled) {
                                    showAttachMenu = !showAttachMenu
                                    println("[InputArea] Attach button clicked, showAttachMenu=$showAttachMenu, recentFiles=${recentFiles.size}")
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Add),
                                contentDescription = "Attach",
                                modifier = Modifier.size(16.dp),
                                tint = mutedText,
                            )

                            // Attach menu popup anchored to the + button
                            if (showAttachMenu) {
                                println("[InputArea] Showing AttachMenu popup with ${recentFiles.size} recentFiles")
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

                        // Send / Stop button
                        if (isStreaming) {
                            // Streaming: red square
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = enabled) { onCancel() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(stopRed),
                                )
                            }
                        } else {
                            // Idle: green run triangle
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
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
                                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Execute),
                                    contentDescription = "Send",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF4EAF4E),
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
                        { ProviderIcon(providerId = selectedProviderIconId, modifier = Modifier.size(14.dp), tint = Color(0xFFCCCCCC)) }
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
 * Decodes a data URI string into an AWT [BufferedImage], or returns null if decoding fails.
 * Supports data URIs in the format: data:<mime>;base64,<encoded>
 */
internal fun decodeDataUriToImage(dataUri: String): BufferedImage? {
    try {
        val base64Data = if (dataUri.contains(",")) dataUri.substringAfter(",") else dataUri
        val bytes = Base64.getDecoder().decode(base64Data)
        val inputStream = bytes.inputStream()
        return ImageIO.read(inputStream)
    } catch (e: Exception) {
        return null
    }
}

/**
 * Decodes a data URI into a Compose [ImageBitmap], or null if not an image or decoding fails.
 */
internal fun decodeDataUriToBitmap(dataUri: String): ImageBitmap? {
    val bufferedImage = decodeDataUriToImage(dataUri) ?: return null
    return bufferedImage.toComposeImageBitmap()
}

/**
 * Converts a java.io.File into an AttachedFile by reading its bytes and encoding as a data URI.
 */
private fun java.io.File.toAttachedFile(): AttachedFile {
    val bytes = readBytes()
    val base64 = Base64.getEncoder().encodeToString(bytes)
    val mime = com.opencode.acp.util.MimeTypes.guessFromFileName(name)
    val dataUri = "data:$mime;base64,$base64"
    return AttachedFile(name = name, path = absolutePath, mime = mime, dataUri = dataUri)
}
