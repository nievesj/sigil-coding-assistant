package com.opencode.acp.chat.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SwingHelper
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.util.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * JPanel with BoxLayout.Y_AXIS. Each message is a child component.
 * syncMessages() compares current children against desired state and
 * appends/replaces only changed components.
 */
class MessageListComponent(
    private val project: Project? = null
) : JPanel() {
    private val messageComponents = mutableMapOf<String, JComponent>()
    /** Track code editors per message so we can dispose them when messages are removed. */
    private val codeEditors = mutableMapOf<String, MutableList<ContentSegment.Code>>()
    private val scrollPane = JBScrollPane(this)

    val component: JBScrollPane get() = scrollPane

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(JBUI.scale(8))
    }

    fun syncMessages(messages: List<ChatMessage>) {
        val currentIds = messages.map { it.id }.toSet()

        // Remove components for messages no longer present — dispose their editors first
        messageComponents.keys.filter { it !in currentIds }.forEach { id ->
            disposeEditors(id)
            messageComponents.remove(id)?.let { remove(it) }
        }

        messages.forEach { msg ->
            val existing = messageComponents[msg.id]
            if (existing == null) {
                val component = createMessageComponent(msg)
                messageComponents[msg.id] = component
                add(component)
            } else {
                // Always rebuild — dispose old editors first, then recreate
                disposeEditors(msg.id)
                val newComponent = createMessageComponent(msg)
                remove(existing)
                messageComponents[msg.id] = newComponent
                add(newComponent)
            }
        }

        revalidate()
        repaint()
    }

    /** Dispose all code editors tracked for a given message. */
    private fun disposeEditors(messageId: String) {
        codeEditors.remove(messageId)?.forEach { it.dispose() }
    }

    /** Dispose all tracked editors — call when the component is destroyed. */
    fun disposeAll() {
        codeEditors.keys.toList().forEach { disposeEditors(it) }
    }

    private fun createMessageComponent(message: ChatMessage): JComponent =
        when (message.role) {
            MessageRole.USER -> createUserMessageComponent(message)
            MessageRole.ASSISTANT -> createAssistantMessageComponent(message)
        }

    private fun createUserMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))
        panel.background = ChatColors.editorBg()
        panel.isOpaque = true

        // Wrap in <html> for multi-line text wrapping in Swing labels
        val label = JBLabel("<html>${escapeHtml(message.content)}</html>").apply {
            font = JBUI.Fonts.label()
            foreground = ChatColors.textPrimary()
        }
        panel.add(label, BorderLayout.WEST)
        return panel
    }

    private fun createAssistantMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))
        panel.isOpaque = false

        // Tool pills
        message.toolCalls.forEach { pill ->
            panel.add(createToolPillComponent(pill))
        }

        // Thinking pill — show actual content if available
        if (message.thinkingContent.isNotEmpty()) {
            panel.add(createThinkingPillComponent(message.thinkingContent))
        }

        // For streaming messages, use simple HTML renderer (cheaper than creating editors per chunk)
        if (message.isStreaming) {
            val editorPane = createHtmlPane(message.renderedHtml
                ?: ChatColors.buildThemedHtml(renderMarkdownToHtml(message.content)))
            editorPane.maximumSize = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            panel.add(editorPane)
        } else {
            // For completed messages, use MarkdownSegmenter which creates real editor instances for code blocks
            val segments = MarkdownSegmenter.segment(message.content, project)
            val editors = mutableListOf<ContentSegment.Code>()

            for (segment in segments) {
                when (segment) {
                    is ContentSegment.Html -> {
                        val ep = createHtmlPane(segment.html)
                        ep.maximumSize = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        panel.add(ep)
                    }
                    is ContentSegment.Code -> {
                        editors.add(segment)
                        segment.component.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(0))
                        panel.add(segment.component)
                    }
                }
            }

            if (editors.isNotEmpty()) {
                codeEditors[message.id] = editors
            }
        }

        return panel
    }

    /** Create a themed HTML editor pane using IntelliJ's HtmlViewerBuilder. */
    private fun createHtmlPane(html: String): JEditorPane {
        return SwingHelper.HtmlViewerBuilder()
            .setFont(JBUI.Fonts.label())
            .setBackground(ChatColors.toolWindowBg())
            .setForeground(ChatColors.textPrimary())
            .create().apply {
                editorKit = HTMLEditorKitBuilder.simple()
                addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
                text = html
                border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(12))
            }
    }

    private fun createToolPillComponent(pill: ToolCallPill): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(16), JBUI.scale(2), JBUI.scale(8))
        panel.background = ChatColors.panelBg()

        val icon = ToolStatusDisplay.icon(pill.status)
        val label = JBLabel(
            "${ToolStatusDisplay.label(pill.kind)}: ${escapeHtml(pill.title)}",
            icon,
            SwingConstants.LEFT
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.textSecondary()
        }
        panel.add(label, BorderLayout.CENTER)
        panel.toolTipText = "Tool: ${escapeHtml(pill.toolName)} (${pill.status})"
        return panel
    }

    private fun createThinkingPillComponent(content: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(16), JBUI.scale(2), JBUI.scale(8))
        panel.background = ChatColors.editorBg()

        // Show actual thinking content, truncated for display
        val displayText = content.take(80) + if (content.length > 80) "..." else ""
        val label = JBLabel(escapeHtml(displayText)).apply {
            font = font.deriveFont(Font.ITALIC)
            foreground = ChatColors.textMuted()
        }
        panel.toolTipText = escapeHtml(content)
        panel.add(label, BorderLayout.WEST)
        return panel
    }
}
