package com.opencode.acp.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import java.awt.*
import javax.swing.*

class PermissionPromptComponent(private val onRespond: (PermissionResponse) -> Unit) : JPanel(BorderLayout()) {

    private var currentPrompt: PermissionPrompt? = null

    private val iconLabel = JBLabel(AllIcons.Actions.Lightning)
    private val titleLabel = JBLabel()
    private val descriptionLabel = JBLabel()

    init {
        isVisible = false

        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(8, 8, 8, 8)
        )
        background = JBColor.namedColor("Panel.background", JBColor(0xF0F0F0, 0x3C3C3C))

        add(iconLabel, BorderLayout.WEST)

        val centerPanel = Box.createVerticalBox()
        centerPanel.isOpaque = false
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        centerPanel.add(titleLabel)
        centerPanel.add(Box.createVerticalStrut(2))
        descriptionLabel.foreground = JBColor.GRAY
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
        rejectButton.foreground = JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
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
        titleLabel.text = null
        descriptionLabel.text = null
        isVisible = false
        revalidate()
        repaint()
    }
}
