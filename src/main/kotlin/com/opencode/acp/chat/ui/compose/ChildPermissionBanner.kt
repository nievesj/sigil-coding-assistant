package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Non-blocking overlay banner for child session (sub-agent) permission prompts.
 * Renders above the input area. Does NOT block parent input — the parent session
 * stays in Streaming or Idle state.
 *
 * Shows one prompt at a time (FIFO). If multiple prompts exist for a child session,
 * shows the first with a "+N more" indicator.
 */
@Composable
fun ChildPermissionBanner(
    prompts: Map<String, List<ChildPermissionPrompt>>,
    onRespond: (childSessionId: String, response: PermissionResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return

    // Show the first prompt from the first child (FIFO).
    // NOTE: When the user responds to child A and child B's prompt is next,
    // the banner switches to child B with no animation. A future enhancement
    // could add a subtle highlight when the displayed child changes.
    val firstChildEntry = prompts.entries.firstOrNull() ?: return
    val childId = firstChildEntry.key
    val childPrompts = firstChildEntry.value
    val prompt = childPrompts.firstOrNull() ?: return
    val moreCount = childPrompts.size - 1

    // Disable buttons after click to prevent double-click duplicate POSTs.
    // Resets when the displayed child changes (new prompt set becomes active).
    var responding by remember(childId) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChatTheme.dims.permissionPaddingH, vertical = ChatTheme.dims.permissionPaddingV)
            .border(
                width = ChatTheme.dims.permissionBorderWidth,
                color = ChatTheme.colors.accent.permissionBorder,
                shape = ChatTheme.shapes.permissionCornerRadius
            )
            .background(
                color = ChatTheme.colors.accent.permissionBg,
                shape = ChatTheme.shapes.permissionCornerRadius
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sub-agent permission needed",
                    fontWeight = FontWeight.Bold,
                    color = ChatTheme.colors.accent.permissionBorder,
                )
                Text(
                    text = "From: ${prompt.subAgentLabel} — ${prompt.toolName}",
                    color = ChatTheme.colors.text.muted,
                    fontSize = ChatTheme.fonts.permissionDescription,
                )
                prompt.description?.let { desc ->
                    Text(
                        text = desc,
                        color = ChatTheme.colors.text.muted,
                        fontSize = ChatTheme.fonts.permissionDescription,
                    )
                }
                if (moreCount > 0) {
                    Text(
                        text = "+$moreCount more pending",
                        color = ChatTheme.colors.text.muted,
                        fontSize = ChatTheme.fonts.permissionDescription,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.End)
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { responding = true; onRespond(childId, PermissionResponse.ALLOW_ONCE) },
                enabled = !responding,
            ) {
                Text(if (moreCount > 0) "Allow" else "Allow Once")
            }
            OutlinedButton(
                onClick = { responding = true; onRespond(childId, PermissionResponse.REJECT_ONCE) },
                enabled = !responding,
            ) {
                Text(if (moreCount > 0) "Reject All" else "Reject")
            }
            DefaultButton(
                onClick = { responding = true; onRespond(childId, PermissionResponse.ALLOW_ALWAYS) },
                enabled = !responding,
            ) {
                Text("Always Allow")
            }
        }
    }
}