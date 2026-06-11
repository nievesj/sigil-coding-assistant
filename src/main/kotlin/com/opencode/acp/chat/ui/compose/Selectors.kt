package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// ── Agent Selector ──────────────────────────────────────────────────────────

@Composable
fun AgentSelector(
    controlState: ControlBarState,
    onAgentChanged: (OpenCodeAgentInfo) -> Unit,
) {
    val selected = controlState.selectedAgent
    val agents = controlState.agents
    val displayText = selected?.name?.replaceFirstChar { it.uppercase() } ?: "Agent"
    var showPopup by remember { mutableStateOf(false) }

    Box {
        SelectorChip(
            text = displayText,
            onClick = { showPopup = !showPopup },
        )

        if (showPopup && agents.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, -4),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
                onDismissRequest = { showPopup = false },
            ) {
                SimplePickerPanel(
                    title = "AGENT",
                    items = agents.map { agent ->
                        PickerItem(
                            label = agent.name.replaceFirstChar { it.uppercase() },
                            isSelected = agent == selected,
                            onClick = {
                                onAgentChanged(agent)
                                showPopup = false
                            },
                        )
                    },
                    onDismiss = { showPopup = false },
                )
            }
        }
    }
}

// ── Thinking Selector ───────────────────────────────────────────────────────

@Composable
fun ThinkingSelector(
    controlState: ControlBarState,
    onThinkingChanged: (ThinkingEffort) -> Unit,
) {
    val variants = controlState.selectedModel?.variants ?: emptyList()
    val displayText = controlState.thinkingEffort.label
    var showPopup by remember { mutableStateOf(false) }

    // Build available efforts: Default + model's actual variants
    val availableEfforts = remember(variants) {
        val matched = variants.mapNotNull { variantName ->
            ThinkingEffort.entries.find { it.variant == variantName }
        }
        listOf(ThinkingEffort.DEFAULT) + matched
    }

    Box {
        SelectorChip(
            text = displayText,
            onClick = { showPopup = !showPopup },
        )

        if (showPopup) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, -4),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
                onDismissRequest = { showPopup = false },
            ) {
                SimplePickerPanel(
                    title = "THINKING",
                    items = availableEfforts.map { effort ->
                        PickerItem(
                            label = effort.label,
                            isSelected = effort == controlState.thinkingEffort,
                            onClick = {
                                onThinkingChanged(effort)
                                showPopup = false
                            },
                        )
                    },
                    onDismiss = { showPopup = false },
                )
            }
        }
    }
}

// ── Shared SelectorChip ─────────────────────────────────────────────────────

/**
 * Chip-style selector: transparent background, blue tint fades in on hover.
 * @param leadingIcon optional composable shown before the text (e.g. provider icon)
 */
@Composable
internal fun SelectorChip(
    text: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val textColor = if (enabled) ChatTheme.colors.text.secondary else ChatTheme.colors.component.starMuted
    val borderColor = if (enabled) ChatTheme.colors.component.inputBorder else ChatTheme.colors.border.subtle

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverAlpha by animateFloatAsState(
        targetValue = if (isHovered && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "chipHoverAlpha",
    )

    val tintBase = ChatTheme.colors.accent.blue
    val bgColor = tintBase.copy(alpha = 0.12f * hoverAlpha)

    val modifier = Modifier
        .clip(ChatTheme.shapes.chipCornerRadius)
        .background(bgColor)
        .border(1.dp, if (isHovered && enabled) tintBase.copy(alpha = 0.3f) else borderColor, ChatTheme.shapes.chipCornerRadius)
        .padding(horizontal = 10.dp, vertical = 5.dp)
        .hoverable(interactionSource)

    val finalModifier = if (onClick != null && enabled) {
        modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else {
        modifier
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = finalModifier,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        Text(
            text = text,
            fontSize = ChatTheme.fonts.selectorChip,
            color = textColor,
        )
        Icon(
            key = AllIconsKeys.General.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = textColor.copy(alpha = 0.7f),
        )
    }
}

// ── Simple Picker Panel (used by Agent + Thinking) ──────────────────────────

private data class PickerItem(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)

/**
 * Lightweight picker panel matching ModelPickerPanel's visual style.
 * Uses the same SectionHeader, row height, and colors.
 */
@Composable
private fun SimplePickerPanel(
    title: String,
    items: List<PickerItem>,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 240.dp)
            .heightIn(max = 320.dp)
            .clip(ChatTheme.shapes.pickerCornerRadius)
            .background(ChatTheme.colors.component.inputBg)
            .border(1.dp, ChatTheme.colors.component.inputBorder, ChatTheme.shapes.pickerCornerRadius),
    ) {
        // Section header — same as ModelPickerPanel
        SectionHeader(
            title = title,
            expanded = true,
            onToggle = {},
        )

        // Items
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 0.dp),
        ) {
            items(
                count = items.size,
                key = { items[it].label },
            ) { index ->
                val item = items[index]
                PickerItemRow(
                    label = item.label,
                    isSelected = item.isSelected,
                    onClick = item.onClick,
                )
            }
        }
    }
}

@Composable
private fun PickerItemRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isSelected -> ChatTheme.colors.component.selectedRowBg
        isHovered -> ChatTheme.colors.component.hoverBg
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(ChatTheme.shapes.pickerRowCornerRadius)
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = ChatTheme.fonts.selectorItem,
            color = if (isSelected) Color.White else ChatTheme.colors.text.secondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
