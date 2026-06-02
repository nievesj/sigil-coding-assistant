package com.opencode.acp.config.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.FormBuilder
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.JPanel

class OpenCodeSettingsPanel {

    val binaryPathField: TextFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle("Select OpenCode Binary")
                    .withDescription("Choose the opencode executable")
            )
        )
    }

    val discoverButton: JButton = JButton("Discover").apply {
        addActionListener(ActionListener {
            val result = BinaryDiscovery.discover()
            if (result != null) {
                binaryPathField.text = result.toString()
                showStatus("Binary found: $result", true)
            } else {
                showStatus("Binary not found. Please select manually.", false)
            }
        })
    }

    val timeoutField: JBTextField = JBTextField("60", 5)

    val statusLabel: JBLabel = JBLabel().apply {
        isVisible = false
    }

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("OpenCode binary:", binaryPathField, 5)
        .addComponentToRightColumn(discoverButton)
        .addLabeledComponent("Permission timeout (seconds):", timeoutField, 5)
        .addComponent(statusLabel)
        .panel

    fun setState(state: OpenCodeSettingsState.State) {
        binaryPathField.text = state.binaryPath
        timeoutField.text = state.permissionTimeoutSeconds.toString()
    }

    fun getState(): OpenCodeSettingsState.State {
        return OpenCodeSettingsState.State(
            binaryPath = binaryPathField.text.trim(),
            permissionTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: 60
        )
    }

    fun isModified(state: OpenCodeSettingsState.State): Boolean {
        val currentState = getState()
        return currentState.binaryPath != state.binaryPath ||
                currentState.permissionTimeoutSeconds != state.permissionTimeoutSeconds
    }

    private fun showStatus(msg: String, success: Boolean) {
        statusLabel.text = msg
        statusLabel.isVisible = true
        statusLabel.foreground = if (success) {
            JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))
        } else {
            JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
        }
    }
}
