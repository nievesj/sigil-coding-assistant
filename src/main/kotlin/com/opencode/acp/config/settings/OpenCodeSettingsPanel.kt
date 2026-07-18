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
import javax.swing.JComboBox
import javax.swing.JPanel

class OpenCodeSettingsPanel {

    val binaryPathField: TextFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
        @Suppress("DEPRECATION")
        addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle("Select OpenCode Binary")
                    .withDescription("Choose the opencode executable"),
                null  // no project context needed (binary path is global)
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

    /** Tool stuck timeout — max seconds a tool can run with no SSE activity before abort. */
    val toolStuckTimeoutField: JBTextField = JBTextField("300", 5).apply {
        toolTipText = "Maximum time (in seconds) a single tool call can run with no SSE activity " +
            "before being considered stuck. Safety net for lost tool results. Default: 300 (5 min). " +
            "Range: 60-3600."
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

    /** Whether to show a confirmation dialog before disconnecting from the server. */
    val showDisconnectCheckbox: JBCheckBox = JBCheckBox("Show disconnect confirmation dialog").apply {
        toolTipText = "When enabled, clicking the disconnect button shows a confirmation dialog. " +
            "When disabled, disconnect happens immediately."
    }

    /** Plugin log level — controls verbosity of [ACP] logs in idea.log. */
    val logLevelCombo: JComboBox<String> = JComboBox(AcpLogLevel.entries.map { it.name }.toTypedArray()).apply {
        toolTipText = "Controls verbosity of [ACP] plugin logs in idea.log.\n" +
            "OFF = silent, ERROR = errors only, WARN = warnings+, INFO = normal (default),\n" +
            "DEBUG = verbose diagnostics (SSE events, tool calls, session state),\n" +
            "TRACE = everything, ALL = no filtering."
    }

    /** Animation throttle FPS — target frame rate for glow/pulse/spinner animations.
     *  Lower values reduce GPU pressure (DirectContextKt._nFlushAndSubmit stalls on Windows).
     *  60 = full vsync, 30 = half pressure (default), 15 = quarter pressure. */
    val animationThrottleFpsField: JBTextField = JBTextField("30", 3).apply {
        toolTipText = "Target FPS for streaming animations (glow, pulse, spinner).\n" +
            "60 = full vsync (original smoothness), 30 = half GPU pressure (default, visually identical),\n" +
            "15 = quarter pressure (may look slightly less smooth).\n" +
            "Lower this if you experience IDE freezes during streaming on Windows."
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
        .addLabeledComponent("Tool stuck timeout (seconds):", toolStuckTimeoutField, 5)
        .addSeparator(5)
        .addLabeledComponent("Inline code color:", inlineCodeColorField, 5)
        .addComponentToRightColumn(inlineCodeColorButton)
        .addLabeledComponent("List number color:", listNumberColorField, 5)
        .addComponentToRightColumn(listNumberColorButton)
        .addSeparator(5)
        .addComponent(loadAllSessionsCheckbox)
        .addComponent(queueInsteadOfSteerCheckbox)
        .addComponent(showDisconnectCheckbox)
        .addSeparator(5)
        .addLabeledComponent("Plugin log level:", logLevelCombo, 5)
        .addLabeledComponent("Animation FPS (GPU throttle):", animationThrottleFpsField, 5)
        .addSeparator(5)
        .addTooltip("Tool pills expanded by default:")
        .apply { 
            ToolKind.entries.forEach { kind ->
                addComponent(toolKindCheckboxes.getValue(kind))
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
        toolStuckTimeoutField.text = settings.toolStuckTimeoutSeconds.toString()
        inlineCodeColorField.text = settings.inlineCodeColor
        listNumberColorField.text = settings.listNumberColor
        loadAllSessionsCheckbox.isSelected = settings.loadAllSessions
        queueInsteadOfSteerCheckbox.isSelected = settings.queueInsteadOfSteer
        showDisconnectCheckbox.isSelected = settings.showDisconnectConfirmation
        logLevelCombo.selectedItem = AcpLogLevel.fromName(settings.logLevel).name
        animationThrottleFpsField.text = settings.animationThrottleFps.toString()
        // Initialize ToolKind checkboxes from settings
        ToolKind.entries.forEach { kind ->
            toolKindCheckboxes.getValue(kind).isSelected = settings.isToolKindDefaultExpanded(kind)
        }
        expandTaskPillsCheckbox.isSelected = settings.expandTaskPillsByDefault
    }

    fun applyTo(settings: OpenCodeSettingsState) {
        // Validate numeric fields and warn on invalid input.
        // Non-parseable values (e.g., "abc", "12.5" for an Int field) are detected
        // via toIntOrNull() returning null. Out-of-range values (e.g., 99999 for a
        // port) are handled by coerceIn() below. Invalid fields are reset to their
        // coerced/default values so the UI matches what will be applied.
        val invalidFields = mutableListOf<String>()
        // Detect both invalid (non-parseable) and blank fields. Blank fields
        // silently use defaults — surface them so the user knows their input was replaced.
        if (portField.text.trim().toIntOrNull() == null) invalidFields.add("Server port" + if (portField.text.trim().isBlank()) " (empty, using default)" else "")
        if (timeoutField.text.trim().toIntOrNull() == null) invalidFields.add("Permission timeout" + if (timeoutField.text.trim().isBlank()) " (empty, using default)" else "")
        if (commandHistorySizeField.text.trim().toIntOrNull() == null) invalidFields.add("Command history size" + if (commandHistorySizeField.text.trim().isBlank()) " (empty, using default)" else "")
        if (responseTimeoutField.text.trim().toIntOrNull() == null) invalidFields.add("Response timeout" + if (responseTimeoutField.text.trim().isBlank()) " (empty, using default)" else "")
        if (longTimeoutBufferField.text.trim().toIntOrNull() == null) invalidFields.add("Long timeout buffer" + if (longTimeoutBufferField.text.trim().isBlank()) " (empty, using default)" else "")
        if (toolStuckTimeoutField.text.trim().toIntOrNull() == null) invalidFields.add("Tool stuck timeout" + if (toolStuckTimeoutField.text.trim().isBlank()) " (empty, using default)" else "")
        if (animationThrottleFpsField.text.trim().toIntOrNull() == null) invalidFields.add("Animation FPS" + if (animationThrottleFpsField.text.trim().isBlank()) " (empty, using default)" else "")
        val port = portField.text.trim().toIntOrNull()
        if (port != null && port !in 1024..65535) invalidFields.add("Server port (must be 1024-65535)")
        if (invalidFields.isNotEmpty()) {
            showStatus("Invalid values in: ${invalidFields.joinToString(", ")} — using defaults", false)
            // Reset invalid fields to their coerced values so the UI matches what will be applied.
            // Without this, the invalid text remains in the field and isModified() produces
            // confusing results on the next check.
            portField.text = portField.text.trim().toIntOrNull()?.coerceIn(1024, 65535)?.toString() ?: "4096"
            timeoutField.text = timeoutField.text.trim().toIntOrNull()?.coerceIn(5, 300)?.toString() ?: "60"
            commandHistorySizeField.text = commandHistorySizeField.text.trim().toIntOrNull()?.coerceIn(1, 100)?.toString() ?: "15"
            responseTimeoutField.text = responseTimeoutField.text.trim().toIntOrNull()?.coerceIn(60, 3600)?.toString() ?: "300"
            longTimeoutBufferField.text = longTimeoutBufferField.text.trim().toIntOrNull()?.coerceAtLeast(10)?.toString() ?: "30"
            toolStuckTimeoutField.text = toolStuckTimeoutField.text.trim().toIntOrNull()?.coerceIn(60, 3600)?.toString() ?: "300"
            animationThrottleFpsField.text = animationThrottleFpsField.text.trim().toIntOrNull()?.coerceIn(15, 60)?.toString() ?: "30"
        }

        val binPath = binaryPathField.text.trim()
        if (binPath.isNotBlank()) {
            val file = java.io.File(binPath)
            if (!file.exists()) {
                // Reject non-existent paths entirely — don't set settings.binaryPath
                // to a path that will cause confusing runtime errors.
                showStatus("Error: '$binPath' does not exist — binary path not updated", false)
            } else {
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    // On Windows, canExecute() is unreliable (always true for readable files).
                    // Check the file extension instead.
                    val ext = file.extension.lowercase()
                    if (ext !in listOf("exe", "bat", "cmd", "ps1", "com", "vbs")) {
                        showStatus("Warning: '$binPath' may not be an executable (expected .exe, .bat, .cmd, .ps1, .com, or .vbs)", false)
                    }
                    settings.binaryPath = binPath
                } else if (!file.canExecute()) {
                    showStatus("Warning: '$binPath' may not be executable", false)
                    settings.binaryPath = binPath
                } else {
                    settings.binaryPath = binPath
                }
            }
        } else {
            settings.binaryPath = binPath
        }
        settings.port = portField.text.trim().toIntOrNull()?.coerceIn(1024, 65535) ?: 4096
        settings.permissionTimeoutSeconds = timeoutField.text.trim().toIntOrNull()?.coerceIn(5, 300) ?: 60
        settings.commandHistorySize = commandHistorySizeField.text.trim().toIntOrNull()?.coerceIn(1, 100) ?: 15
        settings.responseTimeoutSeconds = responseTimeoutField.text.trim().toIntOrNull()?.coerceIn(60, 3600) ?: 300
        settings.longTimeoutBufferSeconds = longTimeoutBufferField.text.trim().toIntOrNull()?.coerceAtLeast(10) ?: 30
        settings.toolStuckTimeoutSeconds = toolStuckTimeoutField.text.trim().toIntOrNull()?.coerceIn(60, 3600) ?: 300
        settings.inlineCodeColor = validateHexColor(inlineCodeColorField.text.trim(), settings.inlineCodeColor)
        settings.listNumberColor = validateHexColor(listNumberColorField.text.trim(), settings.listNumberColor)
        settings.loadAllSessions = loadAllSessionsCheckbox.isSelected
        settings.queueInsteadOfSteer = queueInsteadOfSteerCheckbox.isSelected
        settings.showDisconnectConfirmation = showDisconnectCheckbox.isSelected
        val selectedLevel = logLevelCombo.selectedItem as? String
        settings.logLevel = if (selectedLevel != null && AcpLogLevel.entries.any { it.name == selectedLevel }) selectedLevel else "INFO"
        settings.animationThrottleFps = animationThrottleFpsField.text.trim().toIntOrNull()?.coerceIn(15, 60) ?: 30
        // Persist ToolKind expansion defaults
        val expandedKinds = ToolKind.entries.filter { toolKindCheckboxes.getValue(it).isSelected }.map { it.name }
        settings.expandedToolKinds = expandedKinds.joinToString(",")
        settings.expandTaskPillsByDefault = expandTaskPillsCheckbox.isSelected
    }

    fun isModified(settings: OpenCodeSettingsState): Boolean {
        val expandedKinds = ToolKind.entries.filter { toolKindCheckboxes.getValue(it).isSelected }.map { it.name }.toSet()
        val currentExpandedKinds = settings.expandedToolKinds.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        // For numeric fields: a non-parseable value is always "modified" so that
        // Apply is enabled and applyTo can coerce/reset it.
        fun isNumericModified(field: JBTextField, current: Int, default: Int): Boolean {
            val parsed = field.text.trim().toIntOrNull()
            return if (parsed != null) parsed != current else field.text.trim().isNotEmpty()
        }
        return binaryPathField.text.trim() != settings.binaryPath ||
                isNumericModified(portField, settings.port, 4096) ||
                isNumericModified(timeoutField, settings.permissionTimeoutSeconds, 60) ||
                isNumericModified(commandHistorySizeField, settings.commandHistorySize, 15) ||
                isNumericModified(responseTimeoutField, settings.responseTimeoutSeconds, 300) ||
                isNumericModified(longTimeoutBufferField, settings.longTimeoutBufferSeconds, 30) ||
                isNumericModified(toolStuckTimeoutField, settings.toolStuckTimeoutSeconds, 300) ||
                inlineCodeColorField.text.trim() != settings.inlineCodeColor ||
                listNumberColorField.text.trim() != settings.listNumberColor ||
                loadAllSessionsCheckbox.isSelected != settings.loadAllSessions ||
                queueInsteadOfSteerCheckbox.isSelected != settings.queueInsteadOfSteer ||
                showDisconnectCheckbox.isSelected != settings.showDisconnectConfirmation ||
                (logLevelCombo.selectedItem as? String ?: "INFO") != settings.logLevel ||
                isNumericModified(animationThrottleFpsField, settings.animationThrottleFps, 30) ||
                expandedKinds != currentExpandedKinds ||
                expandTaskPillsCheckbox.isSelected != settings.expandTaskPillsByDefault
    }

    fun showStatus(msg: String, success: Boolean) {
        statusLabel.text = msg
        statusLabel.isVisible = true
        statusLabel.foreground = if (success) {
            JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))
        } else {
            JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
        }
    }

    companion object {
        private val panelLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        /** Validate hex color format. Returns [fallback] if invalid. */
        private fun validateHexColor(value: String, fallback: String): String =
            if (value.matches(HEX_COLOR_REGEX)) value else fallback

        private fun parseColor(hex: String): java.awt.Color {
            if (hex.isBlank()) return java.awt.Color(60, 60, 60)
            val clean = hex.removePrefix("#")
            if (clean.length != 6) return java.awt.Color(60, 60, 60)
            return try {
                java.awt.Color(clean.toInt(16))
            } catch (e: NumberFormatException) {
                panelLogger.debug(e) { "[ACP] Failed to parse hex color: $hex" }
                java.awt.Color(60, 60, 60)
            }
        }
    }
}