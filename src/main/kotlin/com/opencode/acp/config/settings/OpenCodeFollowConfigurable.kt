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

    /**
     * Flag set while apply() mutates field.text programmatically (to reset invalid hex
     * fields to defaults). Disables the DocumentListener's updateColor callback during
     * programmatic updates to avoid re-entrant listener firing.
     */
    private var applyingProgrammatically = false

    override fun getDisplayName(): String = "Follow Agent"

    override fun createComponent(): JComponent {
        // Clear any rows from a previous createComponent call — the IDE may
        // call createComponent multiple times without disposing the prior panel,
        // which would otherwise accumulate duplicate ColorRow entries.
        // Note: the old panel's text fields are orphaned (held by the IDE until
        // disposed) but are no longer referenced by colorRows, so they won't
        // receive apply()/reset() calls.
        colorRows.clear()
        val settings = OpenCodeFollowSettingsState.getInstance()

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
            textField.toolTipText = "$label (default: $defaultHex). Format: #RRGGBB (alpha defaults to FF = fully opaque) or #RRGGBBAA where AA is alpha (00=transparent, FF=opaque). Note: defaults use 0x88 alpha (semi-transparent); entering 6-char hex makes the highlight fully opaque."
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
        return colorRows.any { (kind, field) -> field.text.trim() != settings.getFollowColor(kind) }
    }

    override fun apply() {
        val settings = OpenCodeFollowSettingsState.getInstance()
        settings.followAgentEnabled = followEnabledCheckBox?.isSelected ?: false
        // Collect fields that need resetting to defaults (invalid hex).
        // We defer the field.text mutation until after the loop to avoid
        // re-entrant DocumentListener callbacks during iteration.
        val fieldsToReset = mutableListOf<Pair<ColorRow, String>>()
        for ((kind, field) in colorRows) {
            val trimmed = field.text.trim()
            // Invalid hex values are silently reset to defaults here (no error dialog);
            // the user gets visual feedback via the pink background on the field itself.
            val hexToPersist = if (isValidHex(trimmed)) trimmed else {
                val defaultHex = com.opencode.acp.follow.FollowColorProvider.getDefaultHex(kind) ?: trimmed
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn { "[ACP] Follow Agent: invalid hex '$trimmed' for $kind, resetting to default $defaultHex" }
                fieldsToReset.add(ColorRow(kind, field) to defaultHex)
                defaultHex
            }
            settings.setFollowColor(kind, hexToPersist)
        }
        // Now reset the invalid fields outside the iteration loop. Disable the
        // DocumentListener during programmatic text updates to avoid re-entrant
        // callbacks (updateColor would otherwise fire on each text mutation).
        applyingProgrammatically = true
        try {
            for ((row, defaultHex) in fieldsToReset) {
                row.textField.text = defaultHex
            }
        } finally {
            applyingProgrammatically = false
        }
    }

    override fun reset() {
        val settings = OpenCodeFollowSettingsState.getInstance()
        followEnabledCheckBox?.isSelected = settings.followAgentEnabled
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
        if (applyingProgrammatically) return
        val text = textField.text.trim()
        textField.background = if (isValidHex(text)) null /* default background */
        else java.awt.Color(0xFFEEEE)  // light pink
    }
}
