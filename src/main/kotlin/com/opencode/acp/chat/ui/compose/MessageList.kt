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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SwingHelper
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.util.ChatColors
import com.opencode.acp.chat.util.renderMarkdownToHtml
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JEditorPane

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
            Text(message.content)
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

        // Message content (rendered HTML for completed, plain text for streaming)
        if (message.isStreaming && message.content.isBlank()) {
            ThinkingIndicator()
        } else if (message.isStreaming) {
            // Streaming: render as plain text to avoid expensive HTML re-parsing
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        } else {
            // Completed: render as HTML via SwingPanel
            val html = remember(message.id, message.renderedHtml, message.content) {
                message.renderedHtml
                    ?: ChatColors.buildThemedHtml(renderMarkdownToHtml(message.content))
            }
            SwingPanel(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                factory = { createHtmlPane(html) },
                update = { pane -> pane.text = html }
            )
        }
    }
}

private fun createHtmlPane(html: String): JEditorPane {
    val pane = SwingHelper.HtmlViewerBuilder()
        .setFont(JBUI.Fonts.label())
        .setBackground(ChatColors.toolWindowBg())
        .setForeground(ChatColors.textPrimary())
        .create()
    pane.editorKit = HTMLEditorKitBuilder.simple()
    pane.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    pane.text = html
    pane.border = JBUI.Borders.empty()
    (pane.document as? javax.swing.text.html.HTMLDocument)?.styleSheet?.addRule(
        "body { margin: 0; padding: 0; } p { margin: 0; }"
    )
    return pane
}