package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Shared checkbox chip composable — eliminates the structural duplication
 * between `FollowAgentCheckbox` and `BraveModeCheckbox` (116 lines of
 * near-identical code in InputArea.kt).
 *
 * Visual structure (preserved exactly from the originals):
 * - [TooltipArea] wrapping a [Row]
 * - The row contains a 14dp checkbox [Box] (filled when [enabled]) and a label [Text]
 * - The checkbox shows a ✓ checkmark when enabled
 * - The whole row is clickable to toggle
 *
 * @param label the chip label text (e.g. "Follow Agent", "Brave Mode")
 * @param tooltip the tooltip text shown on hover
 * @param enabled whether the feature is currently enabled (drives checkbox fill + checkmark)
 * @param onToggle called when the chip is clicked
 * @param color the accent color for the checkbox fill + border when enabled
 *   (`ChatTheme.colors.accent.blue` for FollowAgent, `Color(0xFFE8A030)` for BraveMode)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CheckboxChip(
    label: String,
    tooltip: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    color: Color,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .background(ChatTheme.colors.component.tooltipBg, RoundedCornerShape(4.dp))
                    .border(1.dp, ChatTheme.colors.component.tooltipBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    tooltip,
                    color = ChatTheme.colors.component.tooltipText,
                    fontSize = ChatTheme.fonts.selectorChip,
                )
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(ChatTheme.shapes.chipCornerRadius)
                .clickable(enabled = true) { onToggle() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (enabled) color
                        else Color.Transparent
                    )
                    .border(
                        width = 1.dp,
                        color = if (enabled) color
                                else ChatTheme.colors.component.inputText.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(3.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (enabled) {
                    Text(
                        text = "\u2713",
                        fontSize = 10.sp,
                        color = ChatTheme.colors.text.inverse,
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = ChatTheme.fonts.selectorChip,
                color = ChatTheme.colors.component.inputText,
            )
        }
    }
}