package com.opencode.acp.chat.ui

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
        panel.border = EmptyBorder(4, 8, 4, 8)
        val html = "<html><div style='margin-left: 20px; padding: 8px; background: #2b2b2b; border-radius: 8px;'>${escapeHtml(message.content)}</div></html>"
        val label = JBLabel(html)
        panel.add(label, BorderLayout.EAST)
        return panel
    }

    private fun createAssistantMessageComponent(message: ChatMessage): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(4, 8, 4, 8)

        // Tool pills
        message.toolCalls.forEach { pill ->
            panel.add(createToolPillComponent(pill))
        }

        // Thinking pill
        if (message.thinkingContent.isNotEmpty()) {
            panel.add(createThinkingPillComponent(message.thinkingContent))
        }

        // Message text
        val editorPane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            text = message.renderedHtml ?: renderMarkdownToHtml(message.content)
            border = EmptyBorder(4, 8, 4, 8)
        }
        editorPane.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        panel.add(editorPane)

        return panel
    }

    private fun createToolPillComponent(pill: ToolCallPill): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBEmptyBorder(2, 16, 2, 8)
        val icon = ToolStatusDisplay.icon(pill.status)
        val label = JBLabel("${ToolStatusDisplay.label(pill.kind)}: ${pill.title}", icon, SwingConstants.LEFT)
        panel.add(label, BorderLayout.CENTER)
        panel.toolTipText = "Tool: ${pill.toolName} (${pill.status})"
        return panel
    }

    private fun createThinkingPillComponent(content: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBEmptyBorder(2, 16, 2, 8)
        val icon = JBLabel("Thinking...")
        icon.font = icon.font.deriveFont(Font.ITALIC)
        panel.add(icon, BorderLayout.WEST)
        return panel
    }

    private fun updateStreamingMessage(component: JComponent, message: ChatMessage) {
        // Walk component tree to find JEditorPane and update its text
        val editorPane = findEditorPane(component) ?: return
        editorPane.text = renderMarkdownToHtml(message.content)
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
}
