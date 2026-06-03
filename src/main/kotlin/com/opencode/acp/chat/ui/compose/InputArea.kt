package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import androidx.compose.foundation.text.input.TextFieldState

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
    onThinkingChanged: (ThinkingEffort) -> Unit
) {
    val textState = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }

    // Auto-focus on composition
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Text area + send/cancel buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button (circular) - placeholder
            IconButton(
                onClick = { /* TODO: file attachment */ },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Add),
                    contentDescription = "Attach",
                    modifier = Modifier.size(16.dp)
                )
            }

            // Text area with keyboard shortcuts
            TextArea(
                state = textState,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                // Enter → send (no Shift)
                                event.key == Key.Enter && !event.isShiftPressed -> {
                                    val text = textState.text.toString().trim()
                                    if (text.isNotEmpty()) {
                                        onSend(text)
                                        textState.edit { replace(0, length, "") }
                                    }
                                    true
                                }
                                // Shift+Enter → newline (default behavior)
                                event.key == Key.Enter && event.isShiftPressed -> false
                                // Escape → cancel
                                event.key == Key.Escape -> {
                                    onCancel()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                enabled = enabled,
                placeholder = { Text("Type a message...") }
            )

            // Send / Cancel button
            if (isStreaming) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Suspend),
                        contentDescription = "Cancel",
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        val text = textState.text.toString().trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            textState.edit { replace(0, length, "") }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                    enabled = enabled
                ) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.MoveUp),
                        contentDescription = "Send",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Selector row: Agent ✦ Model ✦ Thinking
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AgentSelector(controlState, onAgentChanged)
            Text(
                text = "✦",
                color = Color.Gray,
                fontSize = 12.sp
            )
            ModelSelector(controlState, onModelChanged)
            ThinkingSelector(controlState, onThinkingChanged)
        }
    }
}