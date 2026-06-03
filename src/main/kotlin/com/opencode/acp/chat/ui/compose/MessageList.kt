@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageRole
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    project: Project? = null
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(
            count = messages.size,
            key = { index -> messages[index].id }
        ) { index ->
            MessageItem(messages[index], project)
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, project: Project? = null) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message)
        MessageRole.ASSISTANT -> AssistantMessage(message, project)
    }
}

@Composable
fun UserMessage(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = Color(0x15808080),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            org.jetbrains.jewel.ui.component.Text(message.content)
        }
    }
}

@Composable
fun AssistantMessage(message: ChatMessage, project: Project? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Tool pills
        message.toolCalls.forEach { pill ->
            ToolPill(pill)
        }

        // Thinking content
        if (message.thinkingContent.isNotEmpty()) {
            ThinkingPill(message.thinkingContent)
        }

        // Message content
        if (message.isStreaming && message.content.isBlank()) {
            ThinkingIndicator()
        } else if (message.content.isNotBlank()) {
            val currentProject = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            val markdownStyling = remember { MarkdownStyling.create() }
            val markdownProcessor = remember { MarkdownProcessor() }
            val codeHighlighter = remember(currentProject) {
                currentProject?.let {
                    try {
                        org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory.getInstance(it).createHighlighter()
                    } catch (_: Exception) { NoOpCodeHighlighter }
                } ?: NoOpCodeHighlighter
            }

            // Segment markdown into text and code blocks
            val segments = remember(message.content) {
                MarkdownSegmenter.segment(message.content)
            }

            ProvideMarkdownStyling(
                markdownStyling = markdownStyling,
                markdownProcessor = markdownProcessor,
                codeHighlighter = codeHighlighter,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    segments.forEach { segment ->
                        when (segment.type) {
                            MarkdownSegment.Type.TEXT -> {
                                // Render text with Jewel's default Markdown renderer
                                Markdown(
                                    markdown = segment.content,
                                    modifier = Modifier.fillMaxWidth(),
                                    selectable = true,
                                )
                            }
                            MarkdownSegment.Type.CODE -> {
                                // Render code block with our custom composable
                                ChatFencedCodeBlock(
                                    content = segment.content,
                                    language = segment.language,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}