package com.opencode.acp.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.util.ChatColors
import java.awt.*
import javax.swing.*

class PermissionPromptComponent(private val onRespond: (PermissionResponse) -> Unit) : JPanel(BorderLayout()) {

    private var currentPrompt: PermissionPrompt? = null

    private val iconLabel = JBLabel(AllIcons.Actions.Lightning)
    private val titleLabel = JBLabel()
    private val descriptionLabel = JBLabel()

    init {
        isVisible = false

        // JBUI.Borders per DESIGN.md — no BorderFactory, no MatteBorder
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(ChatColors.border()),
            JBUI.Borders.customLineBottom(ChatColors.border()),
            JBUI.Borders.empty(8, 8, 8, 8)
        )
        background = ChatColors.panelBg()

        add(iconLabel, BorderLayout.WEST)

        val centerPanel = Box.createVerticalBox()
        centerPanel.isOpaque = false
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.foreground = ChatColors.textPrimary()
        centerPanel.add(titleLabel)
        centerPanel.add(Box.createVerticalStrut(2))
        descriptionLabel.foreground = ChatColors.textMuted()
        centerPanel.add(descriptionLabel)
        add(centerPanel, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        buttonPanel.isOpaque = false

        val allowOnceButton = JButton("Allow Once")
        allowOnceButton.addActionListener {
            onRespond(PermissionResponse.ALLOW_ONCE)
            hidePrompt()
        }

        val rejectButton = JButton("Reject")
        rejectButton.foreground = ChatColors.error()
        rejectButton.addActionListener {
            onRespond(PermissionResponse.REJECT_ONCE)
            hidePrompt()
        }

        val allowAlwaysButton = JButton("Always Allow")
        allowAlwaysButton.addActionListener {
            onRespond(PermissionResponse.ALLOW_ALWAYS)
            hidePrompt()
        }

        buttonPanel.add(allowOnceButton)
        buttonPanel.add(rejectButton)
        buttonPanel.add(allowAlwaysButton)

        add(buttonPanel, BorderLayout.SOUTH)
    }

    fun showPrompt(prompt: PermissionPrompt) {
        currentPrompt = prompt
        titleLabel.text = prompt.toolName
        descriptionLabel.text = prompt.description ?: "This tool requires permission."
        isVisible = true
        revalidate()
        repaint()
    }

    fun hidePrompt() {
        currentPrompt = null
        titleLabel.text = ""
        descriptionLabel.text = ""
        isVisible = false
        revalidate()
        repaint()
    }
}
