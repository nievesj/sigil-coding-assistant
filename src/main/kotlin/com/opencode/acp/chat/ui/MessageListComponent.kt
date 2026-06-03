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
    private val scrollPane = JBScrollPane(this)

    val component: JBScrollPane get() = scrollPane

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(JBUI.scale(8))
        // Never show horizontal scrollbar — content should wrap, not scroll
        scrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    fun syncMessages(messages: List<ChatMessage>) {
        val currentIds = messages.map { it.id }.toSet()

        // Remove components for messages no longer present
        messageComponents.keys.filter { it !in currentIds }.forEach { id ->
            messageComponents.remove(id)?.let { remove(it) }
        }

        // Rebuild all with proper spacing between messages
        removeAll()
        messages.forEachIndexed { idx, msg ->
            val newComponent = createMessageComponent(msg)
            messageComponents[msg.id] = newComponent
            add(newComponent)
            // DESIGN.md: message-vertical spacing 6px between messages
            if (idx < messages.size - 1) {
                add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
        // Push all messages to the top — glue absorbs extra vertical space
        add(Box.createVerticalGlue())

        revalidate()
        repaint()
    }

    private fun createMessageComponent(message: ChatMessage): JComponent =
        when (message.role) {
            MessageRole.USER -> createUserMessageComponent(message)
            MessageRole.ASSISTANT -> createAssistantMessageComponent(message)
        }

    private fun createUserMessageComponent(message: ChatMessage): JComponent {
        // User messages are right-aligned chat bubbles per DESIGN.md
        val bubblePanel = JPanel(BorderLayout())
        bubblePanel.border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))
        bubblePanel.background = ChatColors.editorBg()
        bubblePanel.isOpaque = true

        val label = JBLabel("<html>${escapeHtml(message.content)}</html>").apply {
            font = JBUI.Fonts.label()
            foreground = ChatColors.textPrimary()
        }
        bubblePanel.add(label, BorderLayout.CENTER)

        // Wrap in a right-aligned container so the bubble sits on the right side
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(0))
        wrapper.add(bubblePanel, BorderLayout.EAST)
        return wrapper
    }

    private fun createAssistantMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        // Only vertical padding between message sections; no horizontal indent
        panel.border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(0))
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
            // If no content yet, show thinking indicator; otherwise show rendered content
            if (message.content.isBlank()) {
                panel.add(createThinkingIndicatorComponent())
            } else {
                val editorPane = createHtmlPane(message.renderedHtml
                    ?: ChatColors.buildThemedHtml(renderMarkdownToHtml(message.content)))
                panel.add(editorPane)
            }
        } else {
            // For completed messages, use MarkdownSegmenter which creates editor instances for code blocks
            val segments = MarkdownSegmenter.segment(message.content, project)

            for (segment in segments) {
                when (segment) {
                    is ContentSegment.Html -> {
                        val ep = createHtmlPane(segment.html)
                        ep.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(12))
                        panel.add(ep)
                    }
                    is ContentSegment.Code -> {
                        // DESIGN.md: code-block padding uses JBUI.scale(12) horizontal
                        segment.component.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(12))
                        panel.add(segment.component)
                    }
                }
            }
        }

        return panel
    }

    private fun createHtmlPane(html: String): JEditorPane {
        return SwingHelper.HtmlViewerBuilder()
            .setFont(JBUI.Fonts.label())
            .setBackground(ChatColors.toolWindowBg())
            .setForeground(ChatColors.textPrimary())
            .create().apply {
                editorKit = HTMLEditorKitBuilder.simple()
                addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
                text = html
                border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(0))
                // Remove Swing's default 8px HTML body inset
                (document as? javax.swing.text.html.HTMLDocument)?.styleSheet?.addRule(
                    "body { margin: 0; padding: 0; } p { margin: 0; }"
                )
            }
    }

    private fun createToolPillComponent(pill: ToolCallPill): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ChatColors.border(), JBUI.scale(1)),
            JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(8))
        )
        panel.background = ChatColors.panelBg()
        panel.isOpaque = true

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
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ChatColors.border(), JBUI.scale(1)),
            JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(8))
        )
        panel.background = ChatColors.editorBg()
        panel.isOpaque = true

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

    private fun createThinkingIndicatorComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(8))
        panel.isOpaque = false
        val label = JBLabel("Thinking...").apply {
            font = font.deriveFont(Font.ITALIC)
            foreground = ChatColors.textMuted()
        }
        panel.add(label, BorderLayout.WEST)
        return panel
    }
}
