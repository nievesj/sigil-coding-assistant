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

    /** Inline code text color — hex string like "#6BBE50" */
    val inlineCodeColorField: JBTextField = JBTextField("#6BBE50", 8)

    val inlineCodeColorButton: JButton = JButton("Choose...").apply {
        addActionListener(ActionListener {
            val currentColor = parseColor(inlineCodeColorField.text)
            val chooser = javax.swing.JColorChooser(currentColor)
            val result = javax.swing.JOptionPane.showConfirmDialog(
                panel,
                chooser,
                "Choose Inline Code Color",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE,
            )
            if (result == javax.swing.JOptionPane.OK_OPTION) {
                val c = chooser.color
                inlineCodeColorField.text = String.format("#%02X%02X%02X", c.red, c.green, c.blue)
            }
        })
    }

    /** List number color — hex string like "#6BBE50" */
    val listNumberColorField: JBTextField = JBTextField("#6BBE50", 8)

    val listNumberColorButton: JButton = JButton("Choose...").apply {
        addActionListener(ActionListener {
            val currentColor = parseColor(listNumberColorField.text)
            val chooser = javax.swing.JColorChooser(currentColor)
            val result = javax.swing.JOptionPane.showConfirmDialog(
                panel,
                chooser,
                "Choose List Number Color",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE,
            )
            if (result == javax.swing.JOptionPane.OK_OPTION) {
                val c = chooser.color
                listNumberColorField.text = String.format("#%02X%02X%02X", c.red, c.green, c.blue)
            }
        })
    }

    val statusLabel: JBLabel = JBLabel().apply {
        isVisible = false
    }

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("OpenCode binary:", binaryPathField, 5)
        .addComponentToRightColumn(discoverButton)
        .addLabeledComponent("Permission timeout (seconds):", timeoutField, 5)
        .addSeparator(5)
        .addLabeledComponent("Inline code color:", inlineCodeColorField, 5)
        .addComponentToRightColumn(inlineCodeColorButton)
        .addLabeledComponent("List number color:", listNumberColorField, 5)
        .addComponentToRightColumn(listNumberColorButton)
        .addComponent(statusLabel)
        .panel

    fun setState(state: OpenCodeSettingsState.State) {
        binaryPathField.text = state.binaryPath
        timeoutField.text = state.permissionTimeoutSeconds.toString()
        inlineCodeColorField.text = state.inlineCodeColor
        listNumberColorField.text = state.listNumberColor
    }

    fun getState(): OpenCodeSettingsState.State {
        return OpenCodeSettingsState.State(
            binaryPath = binaryPathField.text.trim(),
            permissionTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: 60,
            inlineCodeColor = inlineCodeColorField.text.trim(),
            listNumberColor = listNumberColorField.text.trim(),
        )
    }

    fun isModified(state: OpenCodeSettingsState.State): Boolean {
        val currentState = getState()
        return currentState.binaryPath != state.binaryPath ||
                currentState.permissionTimeoutSeconds != state.permissionTimeoutSeconds ||
                currentState.inlineCodeColor != state.inlineCodeColor ||
                currentState.listNumberColor != state.listNumberColor
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

    companion object {
        private fun parseColor(hex: String): java.awt.Color {
            if (hex.isBlank()) return java.awt.Color(60, 60, 60)
            val clean = hex.removePrefix("#")
            return try {
                java.awt.Color(clean.toInt(16))
            } catch (_: Exception) {
                java.awt.Color(60, 60, 60)
            }
        }
    }
}
