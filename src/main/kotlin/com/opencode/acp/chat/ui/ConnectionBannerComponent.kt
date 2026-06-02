package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBEmptyBorder
import com.opencode.acp.chat.model.ConnectionState
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class ConnectionBannerComponent(
    private val onRetry: () -> Unit = {}
) : JPanel(BorderLayout()) {
    private val label = JBLabel()
    private val retryButton = JButton("Retry")

    init {
        isVisible = false
        border = JBEmptyBorder(4, 8, 4, 8)
        add(label, BorderLayout.CENTER)
        retryButton.addActionListener { onRetry() }
        add(retryButton, BorderLayout.EAST)
    }

    fun updateState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                isVisible = true
                label.text = "Not connected to OpenCode"
                retryButton.isVisible = true
            }
            ConnectionState.CONNECTING -> {
                isVisible = true
                label.text = "Connecting..."
                retryButton.isVisible = false
            }
            ConnectionState.CONNECTED -> {
                isVisible = false
            }
            ConnectionState.RECONNECTING -> {
                isVisible = true
                label.text = "Reconnecting..."
                retryButton.isVisible = false
            }
            ConnectionState.ERROR -> {
                isVisible = true
                label.text = "Connection failed"
                retryButton.isVisible = true
            }
        }
    }
}
