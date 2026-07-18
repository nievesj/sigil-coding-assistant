@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.component.Text

/**
 * Shared header for [ThinkingIndicator] and [CollapsibleThinkingPill].
 *
 * Renders the chevron + "Thought" label + spinner (when streaming).
 * Both components use this so the indicator→pill transition is visually
 * seamless — the header stays identical, only content appears below it.
 */
@Composable
private fun ThinkingHeader(
    expanded: Boolean,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        modifier.clickable { onClick() }.padding(vertical = 2.dp)
    } else {
        modifier.padding(vertical = 2.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            color = ChatTheme.colors.component.thinkingChevron,
            style = TextStyle(fontSize = ChatTheme.fonts.thinkingChevron)
        )
        Text(
            text = if (expanded) "Thought" else "\uD83E\uDDE0 Thought",
            fontStyle = FontStyle.Italic,
            color = ChatTheme.colors.component.thinkingChevron,
            style = TextStyle(fontSize = ChatTheme.fonts.thinkingLabel)
        )
        if (streaming) {
            ThrottledCircularProgressIndicator(modifier = Modifier.size(ChatTheme.dims.thinkingStreamingSpinnerSize))
        }
    }
}

@Composable
fun ThinkingIndicator(
    label: String = "Thinking…",
    modifier: Modifier = Modifier,
) {
    // Pinned status indicator at the bottom of the chat. Shows a spinner +
    // dynamic label reflecting what the LLM is currently doing. The label
    // is derived from the streaming message's parts by the caller.
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            ThrottledCircularProgressIndicator(modifier = Modifier.size(ChatTheme.dims.thinkingStreamingSpinnerSize))
            Text(
                text = label,
                fontStyle = FontStyle.Italic,
                color = ChatTheme.colors.component.thinkingChevron,
                style = TextStyle(fontSize = ChatTheme.fonts.thinkingLabel),
            )
        }
    }
}

@Composable
fun CollapsibleThinkingPill(
    content: String,
    state: PartState,
    modifier: Modifier = Modifier,
) {
    // Use settings default for THINK kind; auto-expand during streaming
    val defaultExpanded = OpenCodeSettingsState.getInstance().isToolKindDefaultExpanded(
        com.agentclientprotocol.model.ToolKind.THINK
    )
    var expanded by remember { mutableStateOf(defaultExpanded || state is PartState.Streaming) }

    // Auto-expand when streaming starts; keep expanded when completed so user can read content.
    LaunchedEffect(state) {
        if (state is PartState.Streaming) expanded = true
    }

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ThinkingHeader(
            expanded = expanded,
            streaming = state is PartState.Streaming,
            onClick = if (state is PartState.Completed) { { expanded = !expanded } } else null
        )

        // Content — only when expanded, rendered with full markdown
        if (expanded && content.isNotBlank()) {
            val markdownProcessor = remember { MarkdownProcessor() }
            val parsedBlocks = remember(content) {
                markdownProcessor.processMarkdownDocument(content)
            }
            Markdown(
                markdownBlocks = parsedBlocks,
                markdown = content,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp).graphicsLayer { alpha = 0.6f },
                selectable = false,
            )
        }
    }
}

/**
 * Throttled circular progress spinner — replaces Jewel's [CircularProgressIndicator]
 * which uses unthrottled [androidx.compose.animation.core.InfiniteTransition] at 60fps.
 * This uses [rememberThrottledInfiniteAnimation] (30fps default) to halve GPU frame
 * pressure during streaming. Visually identical: a rotating arc.
 */
@Composable
private fun ThrottledCircularProgressIndicator(
    modifier: Modifier = Modifier,
    tint: Color = ChatTheme.colors.component.thinkingChevron,
) {
    val rotationState = rememberThrottledInfiniteAnimation(
        active = true,
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 1000,
        repeatMode = RepeatMode.Restart,
        label = "thinkingSpinner",
    )
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val diameter = size.width
        val radius = diameter / 2f - stroke / 2f
        drawArc(
            color = tint.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = tint,
            startAngle = rotationState.value - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}