package com.opencode.acp.config.settings

import com.agentclientprotocol.model.ToolKind
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
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
                FileChooserDescriptor(true, false, false, false, false, false)
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

    /** Server port — which port the opencode server listens on. */
    val portField: JBTextField = JBTextField("4096", 6)

    /** Command history size — how many past messages to remember. */
    val commandHistorySizeField: JBTextField = JBTextField("15", 5)

    /** Response timeout — max seconds to wait for LLM response before timing out. */
    val responseTimeoutField: JBTextField = JBTextField("300", 5).apply {
        toolTipText = "Maximum time (in seconds) to wait for an LLM response. Default: 300 (5 min). " +
            "Increase for slow models or complex tool calls. Minimum: 60s."
    }

    /** Long timeout buffer — extra seconds beyond response timeout for LLM-backed commands. */
    val longTimeoutBufferField: JBTextField = JBTextField("30", 5).apply {
        toolTipText = "Buffer time (in seconds) added to response timeout for LLM-backed commands " +
            "(e.g., /review, /init). Accounts for server overhead. Default: 30. Minimum: 10."
    }

    /** Inline code text color — hex string like "#6BBE50" */
    val inlineCodeColorField: JBTextField = JBTextField("#6BBE50", 8)

    val inlineCodeColorButton: JButton = JButton("\u25BC").apply {
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

    /** Whether to load all sessions at once (bypasses pagination). */
    val loadAllSessionsCheckbox: JBCheckBox = JBCheckBox("Load all sessions at startup").apply {
        toolTipText = "Warning: Loading all sessions at once may cause slow startup " +
            "with 100+ sessions. Default loads 10 most recent."
    }

    /** Checkboxes for which ToolKinds default to expanded in the chat. */
    private val toolKindCheckboxes: Map<ToolKind, JBCheckBox> = ToolKind.entries.associateWith { kind ->
        JBCheckBox(toolKindLabel(kind)).apply {
            toolTipText = "Default: expanded for ${toolKindLabel(kind)} pills"
        }
    }

    /** Whether task/subtask pills default to expanded. */
    val expandTaskPillsCheckbox: JBCheckBox = JBCheckBox("Task (Subtask)").apply {
        toolTipText = "Default: collapsed for task/subtask pills"
    }

    /** Whether to queue messages instead of steering (aborting) when sending during streaming. */
    val queueInsteadOfSteerCheckbox: JBCheckBox = JBCheckBox("Queue messages instead of steering").apply {
        toolTipText = "When enabled, sending a message while the AI is streaming will queue it " +
            "and auto-send when the current response completes. Tools and subtasks keep running. " +
            "When disabled, sending aborts the current response (legacy behavior)."
    }

    private fun toolKindLabel(kind: ToolKind): String = when (kind) {
        ToolKind.EXECUTE -> "Shell (Execute)"
        ToolKind.EDIT -> "Edit (Write)"
        ToolKind.READ -> "Read"
        ToolKind.SEARCH -> "Search"
        ToolKind.DELETE -> "Delete"
        ToolKind.MOVE -> "Move"
        ToolKind.FETCH -> "Fetch"
        ToolKind.THINK -> "Think"
        ToolKind.SWITCH_MODE -> "Switch Mode"
        ToolKind.OTHER -> "Other"
    }

    val listNumberColorButton: JButton = JButton("\u25BC").apply {
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
        .addLabeledComponent("Server port:", portField, 5)
        .addLabeledComponent("Permission timeout (seconds):", timeoutField, 5)
        .addLabeledComponent("Command history size:", commandHistorySizeField, 5)
        .addLabeledComponent("Response timeout (seconds):", responseTimeoutField, 5)
        .addLabeledComponent("Long timeout buffer (seconds):", longTimeoutBufferField, 5)
        .addSeparator(5)
        .addLabeledComponent("Inline code color:", inlineCodeColorField, 5)
        .addComponentToRightColumn(inlineCodeColorButton)
        .addLabeledComponent("List number color:", listNumberColorField, 5)
        .addComponentToRightColumn(listNumberColorButton)
        .addSeparator(5)
        .addComponent(loadAllSessionsCheckbox)
        .addComponent(queueInsteadOfSteerCheckbox)
        .addSeparator(5)
        .addTooltip("Tool pills expanded by default:")
        .apply { 
            ToolKind.entries.forEach { kind ->
                addComponent(toolKindCheckboxes[kind]!!)
            }
            addComponent(expandTaskPillsCheckbox)
        }
        .addComponent(statusLabel)
        .panel

    fun setState(settings: OpenCodeSettingsState) {
        binaryPathField.text = settings.binaryPath
        portField.text = settings.port.toString()
        timeoutField.text = settings.permissionTimeoutSeconds.toString()
        commandHistorySizeField.text = settings.commandHistorySize.toString()
        responseTimeoutField.text = settings.responseTimeoutSeconds.toString()
        longTimeoutBufferField.text = settings.longTimeoutBufferSeconds.toString()
        inlineCodeColorField.text = settings.inlineCodeColor
        listNumberColorField.text = settings.listNumberColor
        loadAllSessionsCheckbox.isSelected = settings.loadAllSessions
        queueInsteadOfSteerCheckbox.isSelected = settings.queueInsteadOfSteer
        // Initialize ToolKind checkboxes from settings
        ToolKind.entries.forEach { kind ->
            toolKindCheckboxes[kind]!!.isSelected = settings.isToolKindDefaultExpanded(kind)
        }
        expandTaskPillsCheckbox.isSelected = settings.expandTaskPillsByDefault
    }

    fun applyTo(settings: OpenCodeSettingsState) {
        settings.binaryPath = binaryPathField.text.trim()
        settings.port = portField.text.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 4096
        settings.permissionTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: 60
        settings.commandHistorySize = commandHistorySizeField.text.trim().toIntOrNull()?.coerceIn(1, 100) ?: 15
        settings.responseTimeoutSeconds = responseTimeoutField.text.trim().toIntOrNull()?.coerceIn(60, 3600) ?: 300
        settings.longTimeoutBufferSeconds = longTimeoutBufferField.text.trim().toIntOrNull()?.coerceAtLeast(10) ?: 30
        settings.inlineCodeColor = inlineCodeColorField.text.trim()
        settings.listNumberColor = listNumberColorField.text.trim()
        settings.loadAllSessions = loadAllSessionsCheckbox.isSelected
        settings.queueInsteadOfSteer = queueInsteadOfSteerCheckbox.isSelected
        // Persist ToolKind expansion defaults
        val expandedKinds = ToolKind.entries.filter { toolKindCheckboxes[it]!!.isSelected }.map { it.name }
        settings.expandedToolKinds = expandedKinds.joinToString(",")
        settings.expandTaskPillsByDefault = expandTaskPillsCheckbox.isSelected
    }

    fun isModified(settings: OpenCodeSettingsState): Boolean {
        val expandedKinds = ToolKind.entries.filter { toolKindCheckboxes[it]!!.isSelected }.map { it.name }.toSet()
        val currentExpandedKinds = settings.expandedToolKinds.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return binaryPathField.text.trim() != settings.binaryPath ||
                (portField.text.trim().toIntOrNull() ?: 4096) != settings.port ||
                (timeoutField.text.trim().toIntOrNull() ?: 60) != settings.permissionTimeoutSeconds ||
                (commandHistorySizeField.text.trim().toIntOrNull() ?: 15) != settings.commandHistorySize ||
                (responseTimeoutField.text.trim().toIntOrNull() ?: 300) != settings.responseTimeoutSeconds ||
                (longTimeoutBufferField.text.trim().toIntOrNull() ?: 30) != settings.longTimeoutBufferSeconds ||
                inlineCodeColorField.text.trim() != settings.inlineCodeColor ||
                listNumberColorField.text.trim() != settings.listNumberColor ||
                loadAllSessionsCheckbox.isSelected != settings.loadAllSessions ||
                queueInsteadOfSteerCheckbox.isSelected != settings.queueInsteadOfSteer ||
                expandedKinds != currentExpandedKinds ||
                expandTaskPillsCheckbox.isSelected != settings.expandTaskPillsByDefault
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