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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class AttachedFile(
    val name: String,
    val path: String,
    val mime: String,
    val dataUri: String
)

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
    onAttach: () -> Unit = {},
    onRemoveFile: (Int) -> Unit = {}
) {
    val textState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val inputBg = Color(0xFF2B2B2B)
    val inputBorder = Color(0xFF3E3E3E)
    val inputBorderFocused = Color(0xFF4A90D9)
    val mutedText = Color(0xFF808080)
    val accentGreen = Color(0xFF6BBE50)
    val surfaceColor = Color(0xFF1E1E1E)

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
                // Attach button (circular, subtle)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled) { onAttach() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Add),
                        contentDescription = "Attach",
                        modifier = Modifier.size(16.dp),
                        tint = mutedText,
                    )
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
                                        onCancel()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        },
                    enabled = enabled,
                    placeholder = { Text("Type a message...", color = mutedText) },
                )

                // Send / Cancel button (circular)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isStreaming) Color(0xFF3E3E3E)
                            else if (enabled && textState.text.isNotBlank()) accentGreen
                            else Color(0xFF3E3E3E)
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
                        contentDescription = if (isStreaming) "Cancel" else "Send",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            }
        }

        // Attached file pills
        if (attachedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachedFiles.forEachIndexed { index, file ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3E3E3E))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = file.name,
                            fontSize = 11.sp,
                            color = Color(0xFFBBBBBB),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .clickable { onRemoveFile(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Close),
                                contentDescription = "Remove",
                                modifier = Modifier.size(12.dp),
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
