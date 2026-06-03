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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageRole
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    project: Project? = null
) {
    val listState = rememberLazyListState()

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
        message.toolCalls.forEach { pill ->
            ToolPill(pill)
        }

        if (message.thinkingContent.isNotEmpty()) {
            ThinkingPill(message.thinkingContent)
        }

        if (message.isStreaming && message.content.isBlank()) {
            ThinkingIndicator()
        } else if (message.content.isNotBlank()) {
            val currentProject = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()

            val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().state
            val defaultGreen = Color(0xFF6BBE50)
            val inlineCodeColor = parseColorOrDefault(settings.inlineCodeColor, defaultGreen)
            val listNumberColor = parseColorOrDefault(settings.listNumberColor, defaultGreen)

            val customInlinesStyling = remember(inlineCodeColor) {
                InlinesStyling.create(
                    inlineCode = SpanStyle(
                        color = inlineCodeColor,
                        background = Color.Transparent,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }

            val markdownStyling = remember(customInlinesStyling, listNumberColor) {
                val baseTextStyle = org.jetbrains.jewel.bridge.theme.retrieveDefaultTextStyle()
                MarkdownStyling.create(
                    inlinesStyling = customInlinesStyling,
                    heading = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.create(
                        h1 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H1.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                        h2 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H2.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                        h3 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H3.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                        h4 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H4.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                        h5 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H5.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                        h6 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H6.create(
                            inlinesStyling = customInlinesStyling,
                        ),
                    ),
                    list = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.create(
                        ordered = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.create(
                            numberStyle = TextStyle(color = listNumberColor, fontSize = 14.sp),
                        ),
                    ),
                )
            }
            val markdownProcessor = remember { MarkdownProcessor() }
            val codeHighlighter = remember(currentProject) {
                currentProject?.let {
                    try {
                        org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory.getInstance(it).createHighlighter()
                    } catch (_: Exception) { NoOpCodeHighlighter }
                } ?: NoOpCodeHighlighter
            }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    segments.forEach { segment ->
                        when (segment.type) {
                            MarkdownSegment.Type.TEXT -> {
                                Markdown(
                                    markdown = segment.content,
                                    modifier = Modifier.fillMaxWidth(),
                                    selectable = true,
                                )
                            }
                            MarkdownSegment.Type.CODE -> {
                                ChatFencedCodeBlock(
                                    content = segment.content,
                                    language = segment.language,
                                )
                            }
                            MarkdownSegment.Type.TABLE -> {
                                ChatTable(
                                    rawMarkdown = segment.content,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parse a hex color string ("#RRGGBB" or "RRGGBB") to Compose Color.
 * Falls back to defaultColor if invalid or blank.
 */
private fun parseColorOrDefault(hex: String, defaultColor: Color): Color {
    if (hex.isBlank()) return defaultColor
    val clean = hex.removePrefix("#")
    return try {
        val argb = clean.toLong(16) or 0xFF000000
        Color(argb.toInt())
    } catch (_: Exception) {
        defaultColor
    }
}
