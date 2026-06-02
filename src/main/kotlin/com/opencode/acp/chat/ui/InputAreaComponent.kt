package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.util.ChatColors
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
        textArea.font = JBUI.Fonts.label()
        textArea.background = ChatColors.editorBg()
        textArea.foreground = ChatColors.textPrimary()

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
            font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(12f).toFloat())
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
            margin = Insets(0, 0, 0, 0)
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6))
            background = ChatColors.buttonBg()
            foreground = ChatColors.textPrimary()
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
            font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(12f).toFloat())
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
            margin = Insets(0, 0, 0, 0)
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6))
            background = ChatColors.buttonBg()
            foreground = ChatColors.error()
            isFocusPainted = false
        }
        cancelButton.addActionListener { onCancel() }
        cancelButton.isVisible = false

        // Text area with send button at bottom-right
        val textPanel = JPanel(BorderLayout())
        textPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(ChatColors.border()),
            JBUI.Borders.empty(JBUI.scale(4))
        )
        textPanel.background = ChatColors.editorBg()

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(0, JBUI.scale(60))
            border = JBUI.Borders.empty()  // JBUI.Borders (DPI-aware) per DESIGN.md
            viewport.background = ChatColors.editorBg()
        }
        textPanel.add(scrollPane, BorderLayout.CENTER)

        // Button overlay at bottom-right
        val buttonOverlay = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        buttonOverlay.isOpaque = false
        buttonOverlay.border = JBUI.Borders.empty()
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
