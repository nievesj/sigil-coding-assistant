package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intellij.icons.AllIcons
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
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

data class AttachedFile(
    val name: String,
    val path: String,
    val mime: String,
    val dataUri: String
)

/**
 * Reads an image from the system clipboard.
 * Returns an AttachedFile with a data URI if an image is present, null otherwise.
 */
suspend fun readClipboardImage(): AttachedFile? = withContext(Dispatchers.IO) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = clipboard.getContents(null) ?: return@withContext null

        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            return@withContext null
        }

        val image = transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
            ?: return@withContext null

        val bufferedImage = image.toBufferedImage()

        // Convert to PNG bytes
        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "png", baos)
        val bytes = baos.toByteArray()

        // Create data URI
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val dataUri = "data:image/png;base64,$base64"

        AttachedFile(
            name = "clipboard-image.png",
            path = "",
            mime = "image/png",
            dataUri = dataUri
        )
    } catch (_: Exception) {
        null
    }
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

@OptIn(ExperimentalJewelApi::class)
@Composable
fun InputArea(
    enabled: Boolean,
    isStreaming: Boolean,
    controlState: ControlBarState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onAgentChanged: (OpenCodeAgentInfo) -> Unit,
    onModelChanged: (ProviderModel) -> Unit,
    onThinkingChanged: (ThinkingEffort) -> Unit,
    attachedFiles: List<AttachedFile> = emptyList(),
    onAttachFile: (AttachedFile) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onImagePasted: (AttachedFile) -> Unit = {},
    recentFiles: List<RecentFile> = emptyList(),
    searchResults: List<RecentFile> = emptyList(),
    onSearch: (String) -> Unit = {},
    onFilesAndFolders: () -> Unit = {},
    onImage: () -> Unit = {},
    onRecentFileClick: (RecentFile) -> Unit = {}
) {
    val textState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var showAttachMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val inputBg = Color(0xFF2B2B2B)
    val inputBorder = Color(0xFF3E3E3E)
    val mutedText = Color(0xFF808080)
    val accentGreen = Color(0xFF6BBE50)
    val stopRed = Color(0xFFE5534B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Text area container with rounded corners and border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(inputBg)
                .border(
                    width = 1.dp,
                    color = inputBorder,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
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

                // Text area
                TextArea(
                    state = textState,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when {
                                    event.key == Key.Enter && !event.isShiftPressed -> {
                                        val text = textState.text.toString().trim()
                                        if (text.isNotEmpty()) {
                                            onSend(text)
                                            textState.edit { replace(0, length, "") }
                                        }
                                        true
                                    }
                                    event.key == Key.Enter && event.isShiftPressed -> false
                                    event.key == Key.Escape -> {
                                        if (showAttachMenu) {
                                            showAttachMenu = false
                                            true
                                        } else {
                                            onCancel()
                                            true
                                        }
                                    }
                                    // Ctrl+V / Cmd+V — paste image from clipboard
                                    (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.V -> {
                                        coroutineScope.launch {
                                            readClipboardImage()?.let { file ->
                                                onImagePasted(file)
                                            }
                                        }
                                        false // Let normal text paste still work
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    enabled = enabled,
                    placeholder = { Text("Type a message...", color = mutedText) },
                )

                // Send / Stop button — green rounded rect (play) or red square (stop)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isStreaming) stopRed else accentGreen
                        )
                        .clickable(enabled = enabled) {
                            if (isStreaming) {
                                onCancel()
                            } else {
                                val text = textState.text.toString().trim()
                                if (text.isNotEmpty()) {
                                    onSend(text)
                                    textState.edit { replace(0, length, "") }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(
                            if (isStreaming) AllIcons.Actions.Suspend
                            else AllIcons.Actions.MoveUp
                        ),
                        contentDescription = if (isStreaming) "Stop" else "Send",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        // Attached file pills
        if (attachedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachedFiles.forEachIndexed { index, file ->
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

        Spacer(modifier = Modifier.height(6.dp))

        // Selector row: Agent | Model | Thinking — compact, no separators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentSelector(controlState, onAgentChanged)
            ModelSelector(controlState, onModelChanged)
            Spacer(modifier = Modifier.weight(1f))
            ThinkingSelector(controlState, onThinkingChanged)
        }
    }
}
