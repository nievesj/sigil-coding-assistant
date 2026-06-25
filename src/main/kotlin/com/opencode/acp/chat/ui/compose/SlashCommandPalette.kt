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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import com.opencode.acp.chat.ui.theme.ChatTheme

/**
 * A slash command definition.
 * @param name the command name without `/` prefix (e.g. "compact")
 * @param description short description shown in the palette
 * @param icon optional platform icon key
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val iconKey: org.jetbrains.jewel.ui.icon.IconKey? = null,
    val isServerCommand: Boolean = false,
    /** Trailing arguments typed after the command name (e.g. for
     *  `/review-perform glm5.2 claude-sonnet`, args = "glm5.2 claude-sonnet").
     *  Populated at invocation time by InputArea; empty for palette-selected
     *  commands with no trailing text. */
    val args: String = ""
)

/**
 * Slash command palette popup. Shown when the user types `/` at the start of the input.
 * Filtering and selection are owned by the caller (InputArea); this composable only renders.
 *
 * @param filtered the pre-filtered list of commands to display
 * @param selectedIndex the index of the currently highlighted command
 * @param onSelectedIndexChange called when hover or keyboard changes the highlighted index
 * @param onCommandSelected callback with the selected SlashCommand (click or Enter)
 * @param onDismiss callback when the palette should close (Escape, click outside)
 */
@Composable
fun SlashCommandPalette(
    filtered: List<SlashCommand>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onCommandSelected: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val shapes = ChatTheme.shapes
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val fontWeights = ChatTheme.fontWeights

    if (filtered.isEmpty()) {
        // No matches — show "No matching commands"
        Column(
            modifier = modifier
                .clip(shapes.paletteCornerRadius)
                .background(colors.surface.dark)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "No matching commands",
                fontSize = fonts.paletteEmpty,
                color = colors.text.muted,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .clip(shapes.paletteCornerRadius)
            .background(colors.surface.dark)
            .widthIn(max = dims.paletteMaxWidth)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        filtered.forEachIndexed { index, command ->
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            LaunchedEffect(isHovered) {
                if (isHovered) onSelectedIndexChange(index)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.paletteRowCornerRadius)
                    .background(if (index == selectedIndex) colors.component.paletteHoverBg else Color.Transparent)
                    .hoverable(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null) { onCommandSelected(command) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon column — fixed width, center-aligned
                Box(
                    modifier = Modifier.width(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (command.iconKey != null) {
                        Icon(
                            key = command.iconKey,
                            contentDescription = command.name,
                            modifier = Modifier.size(14.dp),
                            tint = colors.accent.blue,
                        )
                    }
                }

                // Spacer
                Spacer(modifier = Modifier.width(8.dp))

                // Name column — fixed min/max width
                Box(
                    modifier = Modifier.widthIn(min = 100.dp, max = 180.dp),
                ) {
                    Text(
                        text = "/${command.name}",
                        fontSize = fonts.paletteCommand,
                        fontWeight = fontWeights.commandName,
                        color = colors.accent.blue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Spacer
                Spacer(modifier = Modifier.width(8.dp))

                // Description column — takes remaining space
                Text(
                    text = command.description,
                    fontSize = fonts.paletteDescription,
                    color = colors.text.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
