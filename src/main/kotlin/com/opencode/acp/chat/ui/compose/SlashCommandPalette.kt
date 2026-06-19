package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
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
    val iconKey: IconKey? = null,
    val isServerCommand: Boolean = false,
    /** Trailing arguments typed after the command name (e.g. for
     *  `/review-perform glm5.2 claude-sonnet`, args = "glm5.2 claude-sonnet").
     *  Populated at invocation time by InputArea; empty for palette-selected
     *  commands with no trailing text. */
    val args: String = ""
)

/**
 * Slash command palette popup. Shown when the user types `/` at the start of the input.
 * Filters commands by the text after `/`.
 *
 * @param commands the list of available slash commands to display
 * @param query the text after `/` (e.g. "co" for `/co`) — used for filtering
 * @param onCommandSelected callback with the selected SlashCommand
 * @param onDismiss callback when the palette should close (Escape, click outside)
 */
@Composable
fun SlashCommandPalette(
    commands: List<SlashCommand>,
    query: String,
    onCommandSelected: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChatTheme.colors
    val shapes = ChatTheme.shapes
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val fontWeights = ChatTheme.fontWeights

    val filtered = remember(query, commands) {
        if (query.isBlank()) commands
        else commands.filter { it.name.startsWith(query, ignoreCase = true) }
    }

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
    ) {
        filtered.forEach { command ->
            var isHovered by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.paletteRowCornerRadius)
                    .background(if (isHovered) colors.component.paletteHoverBg else Color.Transparent)
                    .clickable {
                        onCommandSelected(command)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Command icon
                if (command.iconKey != null) {
                    Icon(
                        key = command.iconKey,
                        contentDescription = command.name,
                        modifier = Modifier.size(14.dp),
                        tint = colors.accent.blue,
                    )
                }

                // Command name with `/` prefix highlighted
                Text(
                    text = "/${command.name}",
                    fontSize = fonts.paletteCommand,
                    fontWeight = fontWeights.commandName,
                    color = colors.accent.blue,
                )

                Text(
                    text = command.description,
                    fontSize = fonts.paletteDescription,
                    color = colors.text.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}