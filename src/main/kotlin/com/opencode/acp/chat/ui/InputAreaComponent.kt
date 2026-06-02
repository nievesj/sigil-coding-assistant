package com.opencode.acp.chat.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*

class InputAreaComponent(
    private val onSend: (String) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout()) {
    private val textArea: JBTextArea = JBTextArea(3, 50)
    private val sendButton: JButton
    private val cancelButton: JButton

    init {
        textArea.lineWrap = true
        textArea.wrapStyleWord = true

        // Enter -> send
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        textArea.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val text = textArea.text.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    textArea.text = ""
                }
            }
        })

        // Shift+Enter -> newline
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        textArea.actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                textArea.insert("\n", textArea.caretPosition)
            }
        })

        // Escape -> cancel
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel")
        textArea.actionMap.put("cancel", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onCancel()
        })

        sendButton = JButton("▶").apply {
            toolTipText = "Send (Enter)"
            font = font.deriveFont(Font.BOLD, 12f)
            preferredSize = Dimension(32, 24)
            margin = Insets(0, 0, 0, 0)
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            background = JBColor.namedColor("Button.background", JBColor(0x3a3c42, 0x3a3c42))
            foreground = JBColor(0x589df6, 0x589df6)
            isFocusPainted = false
        }
        sendButton.addActionListener {
            val text = textArea.text.trim()
            if (text.isNotEmpty()) {
                onSend(text)
                textArea.text = ""
            }
        }

        cancelButton = JButton("⏹").apply {
            toolTipText = "Stop (Escape)"
            font = font.deriveFont(Font.BOLD, 12f)
            preferredSize = Dimension(32, 24)
            margin = Insets(0, 0, 0, 0)
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            background = JBColor.namedColor("Button.background", JBColor(0x3a3c42, 0x3a3c42))
            foreground = JBColor(0xdb4437, 0xdb4437)
            isFocusPainted = false
        }
        cancelButton.addActionListener { onCancel() }
        cancelButton.isVisible = false

        // Text area with send button overlaid at bottom-right
        val textPanel = JPanel(BorderLayout())
        textPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        )
        textPanel.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(0, 60)
            border = BorderFactory.createEmptyBorder()
            viewport.background = JBColor.namedColor("Editor.background", JBColor(0x2b2d31, 0x2b2d31))
        }
        textPanel.add(scrollPane, BorderLayout.CENTER)

        // Button panel overlaid at bottom-right
        val buttonOverlay = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        buttonOverlay.isOpaque = false
        buttonOverlay.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        buttonOverlay.add(sendButton)
        buttonOverlay.add(cancelButton)
        textPanel.add(buttonOverlay, BorderLayout.SOUTH)

        add(textPanel, BorderLayout.CENTER)
    }

    fun clear() { textArea.text = "" }

    fun setInputEnabled(enabled: Boolean) {
        textArea.isEnabled = enabled
        sendButton.isEnabled = enabled
    }

    fun showCancelMode(isStreaming: Boolean) {
        sendButton.isVisible = !isStreaming
        cancelButton.isVisible = isStreaming
    }
}
