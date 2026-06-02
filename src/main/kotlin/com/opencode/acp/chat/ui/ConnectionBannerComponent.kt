package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.util.ChatColors
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
        background = ChatColors.toolWindowBg()
        border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))
        add(label, BorderLayout.CENTER)
        retryButton.addActionListener { onRetry() }
        add(retryButton, BorderLayout.EAST)
    }

    fun updateState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                isVisible = true
                label.text = "Not connected to OpenCode"
                label.foreground = ChatColors.textMuted()
                retryButton.isVisible = true
            }
            ConnectionState.CONNECTING -> {
                isVisible = true
                label.text = "Connecting..."
                label.foreground = ChatColors.textMuted()
                retryButton.isVisible = false
            }
            ConnectionState.CONNECTED -> {
                isVisible = false
            }
            ConnectionState.RECONNECTING -> {
                isVisible = true
                label.text = "Reconnecting..."
                label.foreground = ChatColors.textMuted()
                retryButton.isVisible = false
            }
            ConnectionState.ERROR -> {
                isVisible = true
                label.text = "Connection failed"
                label.foreground = ChatColors.error()
                retryButton.isVisible = true
            }
        }
    }
}
