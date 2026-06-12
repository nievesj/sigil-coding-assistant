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

    /** Per-row model: holds the text field and remembers the ToolKind it edits. */
    private data class ColorRow(val kind: ToolKind, val textField: JBTextField)
    private val colorRows = mutableListOf<ColorRow>()

    override fun getDisplayName(): String = "Follow Agent"

    override fun createComponent(): JComponent {
        val settings = OpenCodeSettingsState.getInstance()

        followEnabledCheckBox = JBCheckBox(
            "Enable Follow Agent (auto-open files on tool calls)",
            settings.followAgentEnabled
        )

        val builder = FormBuilder()
            .addComponent(followEnabledCheckBox!!)
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
            textField.toolTipText = "$label (default: $defaultHex)"
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
        val settings = OpenCodeSettingsState.getInstance()
        if (followEnabledCheckBox?.isSelected != settings.followAgentEnabled) return true
        return colorRows.any { (kind, field) -> field.text.trim() != settings.getFollowColor(kind) }
    }

    override fun apply() {
        val settings = OpenCodeSettingsState.getInstance()
        settings.followAgentEnabled = followEnabledCheckBox?.isSelected ?: false
        for ((kind, field) in colorRows) {
            // Persist whatever the user typed; FollowColorProvider.getColor() falls
            // back to "no highlight" on bad input. We do not coerce to default here —
            // that would silently lose user input on apply.
            settings.setFollowColor(kind, field.text.trim())
        }
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        followEnabledCheckBox?.isSelected = settings.followAgentEnabled
        for ((kind, field) in colorRows) {
            field.text = settings.getFollowColor(kind)
        }
    }

    private fun isValidHex(hex: String): Boolean {
        val h = hex.removePrefix("#")
        if (h.length != 6 && h.length != 8) return false
        return h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun updateColor(textField: JBTextField) {
        val text = textField.text.trim()
        textField.background = if (isValidHex(text)) null /* default background */
        else java.awt.Color(0xFFEEEE)  // light pink
    }
}
