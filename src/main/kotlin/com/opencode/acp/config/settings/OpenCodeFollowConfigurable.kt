package com.opencode.acp.config.settings

import com.agentclientprotocol.model.ToolKind
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Child configurable for Follow Agent settings.
 * Appears as "Follow Agent" under the "OpenCode" settings node.
 *
 * The configurable builds its rows from [FollowColorProvider.highlightableKinds]
 * and the matching [FollowColorProvider.getDefaultHex] / [getFollowColor] / [setFollowColor]
 * triple. Adding a new ToolKind to [FollowColorProvider] is the only change required
 * for the configurable to expose it — the row order is derived from the registry.
 */
class OpenCodeFollowConfigurable : Configurable {

    private var panel: JPanel? = null
    private var followEnabledCheckBox: JBCheckBox? = null
    private var followCommandsInConsoleCheckBox: JBCheckBox? = null
    private var followSearchesInFindWindowCheckBox: JBCheckBox? = null
    private var braveModeCheckBox: JBCheckBox? = null

    /** Per-row model: holds the text field and remembers the ToolKind it edits. */
    private data class ColorRow(val kind: ToolKind, val textField: JBTextField)
    private val colorRows = mutableListOf<ColorRow>()

    override fun getDisplayName(): String = "Follow Agent"

    override fun createComponent(): JComponent {
        val settings = OpenCodeFollowSettingsState.getInstance()

        followEnabledCheckBox = JBCheckBox(
            "Enable Follow Agent (auto-open files on tool calls)",
            settings.followAgentEnabled
        )

        followCommandsInConsoleCheckBox = JBCheckBox(
            "Show agent commands in Run console",
            settings.followCommandsInConsole
        )
        followCommandsInConsoleCheckBox?.toolTipText = "When Follow Agent is on, display agent-executed commands and their output in a read-only Run console"

        followSearchesInFindWindowCheckBox = JBCheckBox(
            "Open Find in Files for agent searches",
            settings.followSearchesInFindWindow
        )
        followSearchesInFindWindowCheckBox?.toolTipText = "When Follow Agent is on, open IntelliJ's native Find in Files when the agent searches"

        braveModeCheckBox = JBCheckBox(
            "Enable Brave Mode (auto-approve all permission prompts)",
            settings.braveModeEnabled
        )
        braveModeCheckBox?.toolTipText = "When enabled, all tool permission prompts are auto-approved with ALLOW_ONCE without showing the UI dialog. Explicit deny rules in opencode.json are still enforced. Note: if the auto-approve POST fails (network error), the prompt will fall back to showing in the UI for manual approval."

        val builder = FormBuilder()
            .addComponent(followEnabledCheckBox!!)
            .addComponent(followCommandsInConsoleCheckBox!!)
            .addComponent(followSearchesInFindWindowCheckBox!!)
            .addComponent(braveModeCheckBox!!)
            .addVerticalGap(8)
            .addSeparator()
            .addVerticalGap(4)
            .addComponent(JBLabel("Highlight colors (hex #RRGGBBAA — alpha required):"))
            .addVerticalGap(4)

        // Build rows from the registry so the row order, defaults, and labels stay
        // in sync with FollowColorProvider. THINK and SWITCH_MODE are excluded by
        // highlightableKinds() — they have no file path, so no highlight.
        for (kind in com.opencode.acp.follow.FollowColorProvider.highlightableKinds()) {
            val defaultHex = com.opencode.acp.follow.FollowColorProvider.getDefaultHex(kind) ?: continue
            val label = com.opencode.acp.follow.FollowColorProvider.getInlayLabel(kind) ?: continue
            val textField = JBTextField(settings.getFollowColor(kind), 10)
            textField.toolTipText = "$label (default: $defaultHex). Format: #RRGGBB (alpha defaults to FF) or #RRGGBBAA where AA is alpha (00=transparent, FF=opaque)."
            // Mark the field invalid on bad input so the user gets immediate feedback.
            // We do not block apply() — the setter stores whatever the user types, but
            // FollowColorProvider.getColor() will fall back to "no highlight" on bad
            // input, so the navigation is silently skipped rather than crashing.
            textField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) { updateColor(textField) }
                override fun removeUpdate(e: DocumentEvent) { updateColor(textField) }
                override fun changedUpdate(e: DocumentEvent) { updateColor(textField) }
            })
            colorRows.add(ColorRow(kind, textField))
            builder.addLabeledComponent("$label:", textField)
        }

        panel = builder.panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeFollowSettingsState.getInstance()
        if (followEnabledCheckBox?.isSelected != settings.followAgentEnabled) return true
        if (followCommandsInConsoleCheckBox?.isSelected != settings.followCommandsInConsole) return true
        if (followSearchesInFindWindowCheckBox?.isSelected != settings.followSearchesInFindWindow) return true
        if (braveModeCheckBox?.isSelected != settings.braveModeEnabled) return true
        return colorRows.any { (kind, field) -> field.text.trim() != settings.getFollowColor(kind) }
    }

    override fun apply() {
        val settings = OpenCodeFollowSettingsState.getInstance()
        settings.followAgentEnabled = followEnabledCheckBox?.isSelected ?: false
        settings.followCommandsInConsole = followCommandsInConsoleCheckBox?.isSelected ?: settings.followCommandsInConsole
        settings.followSearchesInFindWindow = followSearchesInFindWindowCheckBox?.isSelected ?: settings.followSearchesInFindWindow
        settings.braveModeEnabled = braveModeCheckBox?.isSelected ?: false
        for ((kind, field) in colorRows) {
            val trimmed = field.text.trim()
            // Invalid hex values are silently reset to defaults here (no error dialog);
            // the user gets visual feedback via the pink background on the field itself.
            val hexToPersist = if (isValidHex(trimmed)) trimmed else {
                val defaultHex = com.opencode.acp.follow.FollowColorProvider.getDefaultHex(kind) ?: trimmed
                // Reset the field to the default so the UI shows the corrected value
                field.text = defaultHex
                defaultHex
            }
            settings.setFollowColor(kind, hexToPersist)
        }
    }

    override fun reset() {
        val settings = OpenCodeFollowSettingsState.getInstance()
        followEnabledCheckBox?.isSelected = settings.followAgentEnabled
        followCommandsInConsoleCheckBox?.isSelected = settings.followCommandsInConsole
        followSearchesInFindWindowCheckBox?.isSelected = settings.followSearchesInFindWindow
        braveModeCheckBox?.isSelected = settings.braveModeEnabled
        for ((kind, field) in colorRows) {
            field.text = settings.getFollowColor(kind)
        }
    }

    private fun isValidHex(hex: String): Boolean {
        val h = hex.removePrefix("#")
        // Accept #RRGGBB (6 chars, alpha defaults to FF) or #RRGGBBAA (8 chars, alpha required)
        if (h.length != 6 && h.length != 8) return false
        return h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun updateColor(textField: JBTextField) {
        val text = textField.text.trim()
        textField.background = if (isValidHex(text)) null /* default background */
        else java.awt.Color(0xFFEEEE)  // light pink
    }
}
