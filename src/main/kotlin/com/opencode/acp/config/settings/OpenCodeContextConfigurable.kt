package com.opencode.acp.config.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JComboBox

/**
 * Child configurable for Context & Compaction settings.
 * Appears as "Context" under the "Sigil" settings node.
 *
 * NOTE: The OpenCode server's `POST /session/{id}/summarize` endpoint does NOT
 * support a `guidance` field — the request body is only `{ providerID, modelID, auto }`.
 * The TDD originally specified guidance features, but they were dropped after
 * librarian research confirmed the server ignores unknown fields.
 */
class OpenCodeContextConfigurable : Configurable {

    private var panel: JPanel? = null

    // Context Display
    private var showContextBreakdownCheckbox: JBCheckBox? = null
    private var pressureNotificationCombo: JComboBox<String>? = null

    // Tool Output Management
    private var truncateToolOutputCheckbox: JBCheckBox? = null
    private var toolOutputCharLimitField: JBTextField? = null
    private var detectDuplicateReadsCheckbox: JBCheckBox? = null

    // Background Compaction
    private var enableBackgroundCompactionCheckbox: JBCheckBox? = null
    private var checkpointThresholdField: JBTextField? = null
    private var swapThresholdField: JBTextField? = null

    // Manual Compaction
    private var compactConfirmationCheckbox: JBCheckBox? = null

    // Context Pruner (server-side)
    private var enableContextPrunerCheckbox: JBCheckBox? = null
    private var prunerMaxToolOutputMessagesField: JBTextField? = null
    private var prunerErroredToolTurnsField: JBTextField? = null
    private var prunerCompressEnabledCheckbox: JBCheckBox? = null
    private var prunerCompressModeCombo: JComboBox<String>? = null

    // Context Pruner: Nudge
    private var prunerNudgeEnabledCheckbox: JBCheckBox? = null
    private var prunerNudgeThresholdField: JBTextField? = null
    private var prunerNudgeUrgentField: JBTextField? = null
    private var prunerNudgeCooldownField: JBTextField? = null
    private var prunerDefaultContextLimitField: JBTextField? = null

    override fun getDisplayName(): String = "Context"

    override fun createComponent(): JComponent {
        val settings = OpenCodeContextSettingsState.getInstance()

        showContextBreakdownCheckbox = JBCheckBox("Show context breakdown bar", settings.showContextBreakdown)
        pressureNotificationCombo = JComboBox(arrayOf("NEVER", "ELEVATED (50%)", "HIGH (70%)", "CRITICAL (85%)")).apply {
            // Map stored value to display label
            val label = when (settings.pressureNotificationThreshold) {
                "NEVER" -> "NEVER"
                "ELEVATED" -> "ELEVATED (50%)"
                "CRITICAL" -> "CRITICAL (85%)"
                else -> "HIGH (70%)"
            }
            selectedItem = label
            toolTipText = "When to show pressure warnings on the context indicator"
        }

        truncateToolOutputCheckbox = JBCheckBox("Truncate tool output", settings.truncateToolOutput).apply {
            toolTipText = "When on, tool results exceeding the char limit are truncated at insertion time"
        }
        toolOutputCharLimitField = JBTextField(settings.toolOutputCharLimit.toString(), 8).apply {
            toolTipText = "Max chars per tool output before truncation (10000-200000). Default: 50000 (~12.5K tokens)"
        }
        detectDuplicateReadsCheckbox = JBCheckBox("Detect duplicate file reads", settings.detectDuplicateReads).apply {
            toolTipText = "When on, repeated reads of unchanged files emit [unchanged] instead of re-emitting content"
        }

        enableBackgroundCompactionCheckbox = JBCheckBox(
            "Enable background compaction", settings.enableBackgroundCompaction
        ).apply {
            toolTipText = "Currently disabled — the server's /summarize endpoint performs actual " +
                "compaction, not a preview. Auto-triggering would compact the session immediately. " +
                "Use manual compaction (/compact command or Compact Now button) instead."
        }
        checkpointThresholdField = JBTextField(settings.checkpointThresholdPercent.toInt().toString(), 4).apply {
            toolTipText = "Context usage % at which background checkpointing starts (40-80). Default: 60"
        }
        swapThresholdField = JBTextField(settings.swapThresholdPercent.toInt().toString(), 4).apply {
            toolTipText = "Context usage % at which pre-computed summary is ready for instant swap (60-95). Default: 80"
        }

        compactConfirmationCheckbox = JBCheckBox("Ask for confirmation before compaction", settings.compactConfirmation)

        enableContextPrunerCheckbox = JBCheckBox("Enable context pruner (server-side)", settings.enableContextPruner).apply {
            toolTipText = "When on, a TypeScript plugin is extracted to .opencode/plugins/ and loaded by the " +
                "OpenCode server. It performs deterministic pruning (dedup, old tool output pruning, errored " +
                "tool input pruning) and LLM-driven compression via a compress tool."
        }
        prunerMaxToolOutputMessagesField = JBTextField(settings.prunerMaxToolOutputMessages.toString(), 4).apply {
            toolTipText = "Prune tool outputs older than N messages (5-100). Default: 20"
        }
        prunerErroredToolTurnsField = JBTextField(settings.prunerErroredToolTurns.toString(), 4).apply {
            toolTipText = "Prune errored tool inputs after N turns (1-20). Default: 4"
        }
        prunerCompressEnabledCheckbox = JBCheckBox("Enable LLM-driven compression (compress tool)", settings.prunerCompressEnabled).apply {
            toolTipText = "When on, the LLM can call a compress tool to summarize completed conversation sections " +
                "via a dedicated sub-agent. The summary replaces the verbatim messages on the next LLM call."
        }
        prunerCompressModeCombo = JComboBox(arrayOf("range", "message")).apply {
            selectedItem = settings.prunerCompressMode
            toolTipText = "Compression mode: 'range' compresses message ranges, 'message' compresses individual messages"
        }
        prunerNudgeEnabledCheckbox = JBCheckBox("Enable compress nudge (system reminder when context is high)", settings.prunerNudgeEnabled).apply {
            toolTipText = "When on, injects a system reminder prompting the model to call the compress tool " +
                "when context usage exceeds the threshold. Two levels: gentle and urgent."
        }
        prunerNudgeThresholdField = JBTextField(settings.prunerNudgeThresholdPercent.toString(), 4).apply {
            toolTipText = "Gentle nudge threshold % (30-90). Default: 60"
        }
        prunerNudgeUrgentField = JBTextField(settings.prunerNudgeUrgentPercent.toString(), 4).apply {
            toolTipText = "Urgent nudge threshold % (50-99). Default: 80"
        }
        prunerNudgeCooldownField = JBTextField(settings.prunerNudgeCooldownTurns.toString(), 4).apply {
            toolTipText = "Minimum turns between nudges (1-10). Default: 3"
        }
        prunerDefaultContextLimitField = JBTextField(settings.prunerDefaultContextLimit.toString(), 8).apply {
            toolTipText = "Fallback context limit when the model object doesn't expose one (1000-2000000). Default: 128000"
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Context Display").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            .addComponent(showContextBreakdownCheckbox!!)
            .addLabeledComponent("Pressure notification:", pressureNotificationCombo!!)
            .addSeparator(8)
            .addComponent(JBLabel("Tool Output Management").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            .addComponent(truncateToolOutputCheckbox!!)
            .addLabeledComponent("Tool output char limit:", toolOutputCharLimitField!!)
            .addComponent(detectDuplicateReadsCheckbox!!)
            .addSeparator(8)
            .addComponent(JBLabel("Background Compaction").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            .addComponent(enableBackgroundCompactionCheckbox!!)
            .addLabeledComponent("Checkpoint threshold (%):", checkpointThresholdField!!)
            .addLabeledComponent("Swap threshold (%):", swapThresholdField!!)
            .addSeparator(8)
            .addComponent(JBLabel("Manual Compaction").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            .addComponent(compactConfirmationCheckbox!!)
            .addSeparator(8)
            .addComponent(JBLabel("Context Pruner (Server-Side)").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            .addComponent(enableContextPrunerCheckbox!!)
            .addLabeledComponent("Prune tool outputs older than (messages):", prunerMaxToolOutputMessagesField!!)
            .addLabeledComponent("Prune errored tool inputs after (turns):", prunerErroredToolTurnsField!!)
            .addComponent(prunerCompressEnabledCheckbox!!)
            .addLabeledComponent("Compression mode:", prunerCompressModeCombo!!)
            .addSeparator(4)
            .addComponent(prunerNudgeEnabledCheckbox!!)
            .addLabeledComponent("Gentle nudge threshold (%):", prunerNudgeThresholdField!!)
            .addLabeledComponent("Urgent nudge threshold (%):", prunerNudgeUrgentField!!)
            .addLabeledComponent("Nudge cooldown (turns):", prunerNudgeCooldownField!!)
            .addLabeledComponent("Default context limit:", prunerDefaultContextLimitField!!)
            .panel

        // Wire conditional visibility: child fields are enabled only when their parent checkbox is checked.
        truncateToolOutputCheckbox?.addItemListener { updateConditionalVisibility() }
        enableBackgroundCompactionCheckbox?.addItemListener { updateConditionalVisibility() }
        enableContextPrunerCheckbox?.addItemListener { updateConditionalVisibility() }
        prunerNudgeEnabledCheckbox?.addItemListener { updateConditionalVisibility() }
        updateConditionalVisibility()

        return panel!!
    }

    /**
     * Toggle enabled state of child fields based on their parent checkbox.
     * Uses isEnabled (grays out) rather than isVisible to avoid layout shifts —
     * standard IntelliJ pattern for conditional settings.
     */
    private fun updateConditionalVisibility() {
        val truncateEnabled = truncateToolOutputCheckbox?.isSelected == true
        toolOutputCharLimitField?.isEnabled = truncateEnabled

        val bgCompactionEnabled = enableBackgroundCompactionCheckbox?.isSelected == true
        checkpointThresholdField?.isEnabled = bgCompactionEnabled
        swapThresholdField?.isEnabled = bgCompactionEnabled

        val prunerEnabled = enableContextPrunerCheckbox?.isSelected == true
        prunerMaxToolOutputMessagesField?.isEnabled = prunerEnabled
        prunerErroredToolTurnsField?.isEnabled = prunerEnabled
        prunerCompressEnabledCheckbox?.isEnabled = prunerEnabled
        prunerCompressModeCombo?.isEnabled = prunerEnabled
        prunerNudgeEnabledCheckbox?.isEnabled = prunerEnabled
        val nudgeEnabled = prunerEnabled && prunerNudgeEnabledCheckbox?.isSelected == true
        prunerNudgeThresholdField?.isEnabled = nudgeEnabled
        prunerNudgeUrgentField?.isEnabled = nudgeEnabled
        prunerNudgeCooldownField?.isEnabled = nudgeEnabled
        prunerDefaultContextLimitField?.isEnabled = nudgeEnabled
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeContextSettingsState.getInstance()
        return showContextBreakdownCheckbox?.isSelected != settings.showContextBreakdown ||
            pressureNotificationCombo?.selectedItem?.toString()?.substringBefore(" (") != settings.pressureNotificationThreshold ||
            truncateToolOutputCheckbox?.isSelected != settings.truncateToolOutput ||
            toolOutputCharLimitField?.text?.trim()?.toIntOrNull() != settings.toolOutputCharLimit ||
            detectDuplicateReadsCheckbox?.isSelected != settings.detectDuplicateReads ||
            enableBackgroundCompactionCheckbox?.isSelected != settings.enableBackgroundCompaction ||
            checkpointThresholdField?.text?.trim()?.toIntOrNull() != settings.checkpointThresholdPercent.toInt() ||
            swapThresholdField?.text?.trim()?.toIntOrNull() != settings.swapThresholdPercent.toInt() ||
            compactConfirmationCheckbox?.isSelected != settings.compactConfirmation ||
            enableContextPrunerCheckbox?.isSelected != settings.enableContextPruner ||
            prunerMaxToolOutputMessagesField?.text?.trim()?.toIntOrNull() != settings.prunerMaxToolOutputMessages ||
            prunerErroredToolTurnsField?.text?.trim()?.toIntOrNull() != settings.prunerErroredToolTurns ||
            prunerCompressEnabledCheckbox?.isSelected != settings.prunerCompressEnabled ||
            prunerCompressModeCombo?.selectedItem?.toString() != settings.prunerCompressMode ||
            prunerNudgeEnabledCheckbox?.isSelected != settings.prunerNudgeEnabled ||
            prunerNudgeThresholdField?.text?.trim()?.toIntOrNull() != settings.prunerNudgeThresholdPercent ||
            prunerNudgeUrgentField?.text?.trim()?.toIntOrNull() != settings.prunerNudgeUrgentPercent ||
            prunerNudgeCooldownField?.text?.trim()?.toIntOrNull() != settings.prunerNudgeCooldownTurns ||
            prunerDefaultContextLimitField?.text?.trim()?.toIntOrNull() != settings.prunerDefaultContextLimit
    }

    override fun apply() {
        val settings = OpenCodeContextSettingsState.getInstance()
        settings.showContextBreakdown = showContextBreakdownCheckbox?.isSelected ?: true
        val pressureLabel = pressureNotificationCombo?.selectedItem?.toString() ?: "HIGH (70%)"
        settings.pressureNotificationThreshold = pressureLabel.substringBefore(" (")
        settings.truncateToolOutput = truncateToolOutputCheckbox?.isSelected ?: false
        settings.toolOutputCharLimit = toolOutputCharLimitField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(10_000, 200_000) ?: 50_000
        settings.detectDuplicateReads = detectDuplicateReadsCheckbox?.isSelected ?: false
        settings.enableBackgroundCompaction = enableBackgroundCompactionCheckbox?.isSelected ?: true
        settings.checkpointThresholdPercent = checkpointThresholdField?.text?.trim()?.toIntOrNull()
            ?.toFloat()?.coerceIn(40f, 80f) ?: 60f
        settings.swapThresholdPercent = swapThresholdField?.text?.trim()?.toIntOrNull()
            ?.toFloat()?.coerceIn(60f, 95f) ?: 80f
        settings.compactConfirmation = compactConfirmationCheckbox?.isSelected ?: true
        // Context Pruner settings
        settings.enableContextPruner = enableContextPrunerCheckbox?.isSelected ?: false
        settings.prunerMaxToolOutputMessages = prunerMaxToolOutputMessagesField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(5, 100) ?: 20
        settings.prunerErroredToolTurns = prunerErroredToolTurnsField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(1, 20) ?: 4
        settings.prunerCompressEnabled = prunerCompressEnabledCheckbox?.isSelected ?: true
        settings.prunerCompressMode = prunerCompressModeCombo?.selectedItem?.toString()
            ?.takeIf { it in listOf("range", "message") } ?: "range"
        // Nudge settings
        settings.prunerNudgeEnabled = prunerNudgeEnabledCheckbox?.isSelected ?: true
        settings.prunerNudgeThresholdPercent = prunerNudgeThresholdField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(30, 90) ?: 60
        settings.prunerNudgeUrgentPercent = prunerNudgeUrgentField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(50, 99) ?: 80
        settings.prunerNudgeCooldownTurns = prunerNudgeCooldownField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(1, 10) ?: 3
        settings.prunerDefaultContextLimit = prunerDefaultContextLimitField?.text?.trim()?.toIntOrNull()
            ?.coerceIn(1000, 2_000_000) ?: 128000
    }

    override fun reset() {
        val settings = OpenCodeContextSettingsState.getInstance()
        showContextBreakdownCheckbox?.isSelected = settings.showContextBreakdown
        val label = when (settings.pressureNotificationThreshold) {
            "NEVER" -> "NEVER"
            "ELEVATED" -> "ELEVATED (50%)"
            "CRITICAL" -> "CRITICAL (85%)"
            else -> "HIGH (70%)"
        }
        pressureNotificationCombo?.selectedItem = label
        truncateToolOutputCheckbox?.isSelected = settings.truncateToolOutput
        toolOutputCharLimitField?.text = settings.toolOutputCharLimit.toString()
        detectDuplicateReadsCheckbox?.isSelected = settings.detectDuplicateReads
        enableBackgroundCompactionCheckbox?.isSelected = settings.enableBackgroundCompaction
        checkpointThresholdField?.text = settings.checkpointThresholdPercent.toInt().toString()
        swapThresholdField?.text = settings.swapThresholdPercent.toInt().toString()
        compactConfirmationCheckbox?.isSelected = settings.compactConfirmation
        // Context Pruner settings
        enableContextPrunerCheckbox?.isSelected = settings.enableContextPruner
        prunerMaxToolOutputMessagesField?.text = settings.prunerMaxToolOutputMessages.toString()
        prunerErroredToolTurnsField?.text = settings.prunerErroredToolTurns.toString()
        prunerCompressEnabledCheckbox?.isSelected = settings.prunerCompressEnabled
        prunerCompressModeCombo?.selectedItem = settings.prunerCompressMode
        // Nudge settings
        prunerNudgeEnabledCheckbox?.isSelected = settings.prunerNudgeEnabled
        prunerNudgeThresholdField?.text = settings.prunerNudgeThresholdPercent.toString()
        prunerNudgeUrgentField?.text = settings.prunerNudgeUrgentPercent.toString()
        prunerNudgeCooldownField?.text = settings.prunerNudgeCooldownTurns.toString()
        prunerDefaultContextLimitField?.text = settings.prunerDefaultContextLimit.toString()
        updateConditionalVisibility()
    }
}