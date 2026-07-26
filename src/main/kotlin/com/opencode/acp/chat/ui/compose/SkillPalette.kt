package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import com.intellij.icons.AllIcons
import com.opencode.acp.adapter.SkillInfo
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

// First non-escape char determines palette: '/' → slash, '$' → skill. See TDD §10 Q7.

/**
 * Skill palette popup. Shown when the user types `$` at the start of the input.
 * Mirrors SlashCommandPalette in structure and behavior.
 *
 * Displays skill name + size indicator + description, filtered by user input.
 * Keyboard: Up/Down to navigate, Enter to select, Escape to dismiss.
 *
 * Size indicator: shows "~Nk" next to the skill name so the user can gauge
 * context-window cost before injecting. Computed from content.length.
 *
 * @param filtered the pre-filtered list of skills to display
 * @param selectedIndex the index of the currently highlighted skill
 * @param onSelectedIndexChange called when hover or keyboard changes the highlighted index
 * @param onSkillSelected callback with the selected SkillInfo (click or Enter)
 * @param onDismiss callback when the palette should close (Escape, click outside)
 */
@Composable
fun SkillPalette(
    filtered: List<SkillInfo>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onSkillSelected: (SkillInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val shapes = ChatTheme.shapes
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val fontWeights = ChatTheme.fontWeights

    if (filtered.isEmpty()) {
        Column(
            modifier = modifier
                .clip(shapes.paletteCornerRadius)
                .background(colors.surface.dark)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "No matching skills",
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
        filtered.forEachIndexed { index, skill ->
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
                    .clickable(interactionSource = interactionSource, indication = null) { onSkillSelected(skill) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon column — lightning bolt
                Box(
                    modifier = Modifier.width(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Lightning),
                        contentDescription = skill.name,
                        modifier = Modifier.size(14.dp),
                        tint = colors.accent.blue,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Name column
                Box(
                    modifier = Modifier.widthIn(min = 100.dp, max = 180.dp),
                ) {
                    Text(
                        text = "$${skill.name}",
                        fontSize = fonts.paletteCommand,
                        fontWeight = fontWeights.commandName,
                        color = colors.accent.blue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Size indicator — only for skills > 1KB
                if (skill.content.length > 1024) {
                    val sizeK = (skill.content.length / 1024).coerceAtLeast(1)
                    Text(
                        text = "~${sizeK}k",
                        fontSize = fonts.paletteDescription,
                        color = colors.text.muted,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Description column — takes remaining space
                Text(
                    text = skill.description,
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