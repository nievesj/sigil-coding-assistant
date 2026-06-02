package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

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

        sendButton = JButton("Send")
        sendButton.addActionListener {
            val text = textArea.text.trim()
            if (text.isNotEmpty()) {
                onSend(text)
                textArea.text = ""
            }
        }

        cancelButton = JButton("Stop")
        cancelButton.addActionListener { onCancel() }
        cancelButton.isVisible = false

        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(0, 80)
        add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel()
        buttonPanel.add(sendButton)
        buttonPanel.add(cancelButton)
        add(buttonPanel, BorderLayout.EAST)
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
