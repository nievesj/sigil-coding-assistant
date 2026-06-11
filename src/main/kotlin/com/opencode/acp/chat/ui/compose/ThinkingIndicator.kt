@file:OptIn(org.jetbrains.jewel.foundation.ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.config.settings.OpenCodeSettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(ChatTheme.dims.thinkingSpinnerSize))
        Text(
            text = " Thinking...",
            fontStyle = FontStyle.Italic,
            color = ChatTheme.colors.component.thinkingText
        )
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

    androidx.compose.foundation.layout.Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Header — always visible, clickable
        Row(
            modifier = Modifier.clickable { if (state is PartState.Completed) expanded = !expanded }
                .padding(vertical = 2.dp),
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
            if (state is PartState.Streaming) {
                CircularProgressIndicator(modifier = Modifier.size(ChatTheme.dims.thinkingStreamingSpinnerSize))
            }
        }

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
