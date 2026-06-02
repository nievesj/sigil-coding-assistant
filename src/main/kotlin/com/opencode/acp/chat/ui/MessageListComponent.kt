package com.opencode.acp.chat.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBEmptyBorder
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.util.ToolStatusDisplay
import com.opencode.acp.chat.util.escapeHtml
import com.opencode.acp.chat.util.renderMarkdownToHtml
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.html.HTMLEditorKit

/**
 * JPanel with BoxLayout.Y_AXIS. Each message is a child component.
 * syncMessages() compares current children against desired state and
 * appends/replaces only changed components.
 */
class MessageListComponent : JPanel() {
    private val messageComponents = mutableMapOf<String, JComponent>()
    private val scrollPane = JBScrollPane(this)

    val component: JBScrollPane get() = scrollPane

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBEmptyBorder(8)
        background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))
    }

    fun syncMessages(messages: List<ChatMessage>) {
        // Remove components for messages no longer in the list
        val currentIds = messages.map { it.id }.toSet()
        messageComponents.keys.filter { it !in currentIds }.forEach { id ->
            messageComponents.remove(id)?.let { remove(it) }
        }

        // For each message in order, create or update component
        messages.forEach { msg ->
            val existing = messageComponents[msg.id]
            if (existing == null) {
                val component = createMessageComponent(msg)
                messageComponents[msg.id] = component
                add(component)
            } else if (msg.isStreaming) {
                updateStreamingMessage(existing, msg)
            }
        }

        revalidate()
        repaint()
    }

    private fun createMessageComponent(message: ChatMessage): JComponent =
        when (message.role) {
            MessageRole.USER -> createUserMessageComponent(message)
            MessageRole.ASSISTANT -> createAssistantMessageComponent(message)
        }

    private fun createUserMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = EmptyBorder(6, 10, 6, 10)
        panel.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))

        val label = JBLabel(
            "<html><body style='color:#dbdee1; font-family:Segoe UI,sans-serif; font-size:13px;'>" +
            escapeHtml(message.content) +
            "</body></html>"
        )
        label.isOpaque = false
        panel.add(label, BorderLayout.WEST)
        return panel
    }

    private fun createAssistantMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(6, 10, 6, 10)
        panel.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))

        // Tool pills
        message.toolCalls.forEach { pill ->
            panel.add(createToolPillComponent(pill))
        }

        // Thinking pill
        if (message.thinkingContent.isNotEmpty()) {
            panel.add(createThinkingPillComponent(message.thinkingContent))
        }

        // Message text with proper HTML styling
        val editorPane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            text = buildAssistantHtml(message.renderedHtml ?: renderMarkdownToHtml(message.content))
            border = EmptyBorder(4, 8, 4, 8)
            background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))
            // Disable default CSS that Swing applies
            putClientProperty(
                HTMLEditorKit.DEFAULT_CSS,
                "body { background: #2b2d31; color: #dbdee1; font-family: 'Segoe UI', sans-serif; font-size: 13px; }"
            )
        }
        editorPane.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        panel.add(editorPane)

        return panel
    }

    private fun createToolPillComponent(pill: ToolCallPill): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBEmptyBorder(2, 16, 2, 8)
        panel.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))

        val icon = ToolStatusDisplay.icon(pill.status)
        val label = JBLabel("${ToolStatusDisplay.label(pill.kind)}: ${pill.title}", icon, SwingConstants.LEFT)
        label.foreground = JBColor(0x7a838b, 0x7a838b)
        label.font = label.font.deriveFont(Font.PLAIN, 11f)
        panel.add(label, BorderLayout.CENTER)
        panel.toolTipText = "Tool: ${pill.toolName} (${pill.status})"
        return panel
    }

    private fun createThinkingPillComponent(content: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBEmptyBorder(2, 16, 2, 8)
        panel.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))

        val icon = JBLabel("Thinking...")
        icon.font = icon.font.deriveFont(Font.ITALIC)
        icon.foreground = JBColor(0x7a838b, 0x7a838b)
        panel.add(icon, BorderLayout.WEST)
        return panel
    }

    private fun updateStreamingMessage(component: JComponent, message: ChatMessage) {
        // Walk component tree to find JEditorPane and update its text
        val editorPane = findEditorPane(component) ?: return
        editorPane.text = buildAssistantHtml(renderMarkdownToHtml(message.content))
    }

    private fun findEditorPane(component: Component): JEditorPane? {
        if (component is JEditorPane) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                val result = findEditorPane(child)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * Wraps rendered HTML with dark theme styles.
     */
    private fun buildAssistantHtml(renderedHtml: String): String {
        return """
            <html>
            <head>
                <style>
                    body {
                        background: #2b2d31;
                        color: #dbdee1;
                        font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;
                        font-size: 13px;
                        line-height: 1.5;
                        margin: 0;
                        padding: 0;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        color: #e8e8e8;
                        margin-top: 16px;
                        margin-bottom: 8px;
                        font-weight: bold;
                    }
                    h1 { font-size: 18px; }
                    h2 { font-size: 16px; }
                    h3 { font-size: 14px; }
                    p {
                        margin-top: 4px;
                        margin-bottom: 8px;
                    }
                    pre {
                        background: #1e1e1e;
                        color: #a9b7c6;
                        padding: 8px 12px;
                        border: 1px solid #3a3c42;
                        font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
                        font-size: 12px;
                        line-height: 1.4;
                        white-space: pre-wrap;
                        word-wrap: break-word;
                        margin: 8px 0;
                    }
                    code {
                        background: #1a1b1e;
                        color: #a9b7c6;
                        padding: 1px 4px;
                        font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
                        font-size: 12px;
                        border: 1px solid #3a3c42;
                    }
                    ul, ol {
                        margin: 8px 0;
                        padding-left: 24px;
                    }
                    li {
                        margin-bottom: 4px;
                    }
                    a {
                        color: #589df6;
                        text-decoration: none;
                    }
                    a:hover {
                        text-decoration: underline;
                    }
                    strong, b {
                        color: #e8e8e8;
                    }
                    em, i {
                        color: #b8b8b8;
                    }
                    hr {
                        border: none;
                        border-top: 1px solid #3a3c42;
                        margin: 16px 0;
                    }
                    table {
                        border-collapse: collapse;
                        margin: 8px 0;
                    }
                    th, td {
                        border: 1px solid #3a3c42;
                        padding: 4px 8px;
                    }
                    th {
                        background: #1e1e1e;
                    }
                    blockquote {
                        border-left: 3px solid #3a3c42;
                        padding-left: 12px;
                        color: #9a9a9a;
                        margin: 8px 0;
                    }
                </style>
            </head>
            <body>$renderedHtml</body>
            </html>
        """.trimIndent()
    }
}
