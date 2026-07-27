package com.opencode.acp.intelligence

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JComboBox

/**
 * Child configurable for PSI code intelligence tools settings.
 * Appears as "PSI Tools" under the "Sigil" settings node.
 *
 * Settings:
 * - Enable PSI code intelligence tools (default: true)
 * - Allow scope: "all" (includes libraries) (default: false, with warning label)
 * - PSI tools log level (default: INFO)
 */
class OpenCodePsiToolsConfigurable : Configurable {

    private var panel: JPanel? = null

    private var enablePsiToolsCheckbox: JBCheckBox? = null
    private var allowScopeAllCheckbox: JBCheckBox? = null
    private var allowScopeAllWarningLabel: JBLabel? = null
    private var logLevelCombo: JComboBox<String>? = null

    override fun getDisplayName(): String = "PSI Tools"

    override fun createComponent(): JComponent? {
        enablePsiToolsCheckbox = JBCheckBox("Enable PSI code intelligence tools")
        allowScopeAllCheckbox = JBCheckBox("Allow scope: \"all\" (includes libraries)")
        allowScopeAllWarningLabel = JBLabel(
            "<html><i>Warning: Enabling this exposes library source code (including proprietary and JDK code) " +
            "to the AI agent, which may re-emit it in responses. Enable only if you have the rights to share that code.</i></html>"
        )
        logLevelCombo = JComboBox(arrayOf("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"))

        panel = FormBuilder.createFormBuilder()
            .addComponent(enablePsiToolsCheckbox!!)
            .addComponent(JBLabel("Exposes IntelliJ PSI code intelligence (symbol search, find usages, call hierarchy, " +
                "impact analysis, file structure, repo map) as MCP tools for the opencode agent."))
            .addComponent(allowScopeAllCheckbox!!)
            .addComponent(allowScopeAllWarningLabel!!)
            .addLabeledComponent("PSI tools log level:", logLevelCombo!!)
            .addComponentFillVertically(JBLabel(""), 0)
            .panel

        // Toggle warning label visibility based on checkbox state
        allowScopeAllCheckbox?.addItemListener { e ->
            allowScopeAllWarningLabel?.isVisible = e.stateChange == ItemEvent.SELECTED
        }

        return panel
    }

    override fun isModified(): Boolean {
        val settings = OpenCodePsiToolsSettingsState.getInstance()
        return enablePsiToolsCheckbox?.isSelected != settings.psiToolsEnabled ||
            allowScopeAllCheckbox?.isSelected != settings.allowScopeAll ||
            logLevelCombo?.selectedItem?.toString() != settings.psiToolsLogLevel
    }

    override fun apply() {
        val settings = OpenCodePsiToolsSettingsState.getInstance()
        settings.psiToolsEnabled = enablePsiToolsCheckbox?.isSelected ?: true
        settings.allowScopeAll = allowScopeAllCheckbox?.isSelected ?: false
        settings.psiToolsLogLevel = logLevelCombo?.selectedItem?.toString() ?: "INFO"
    }

    override fun reset() {
        val settings = OpenCodePsiToolsSettingsState.getInstance()
        enablePsiToolsCheckbox?.isSelected = settings.psiToolsEnabled
        allowScopeAllCheckbox?.isSelected = settings.allowScopeAll
        allowScopeAllWarningLabel?.isVisible = settings.allowScopeAll
        logLevelCombo?.selectedItem = settings.psiToolsLogLevel
    }

    override fun disposeUIResources() {
        panel = null
        enablePsiToolsCheckbox = null
        allowScopeAllCheckbox = null
        allowScopeAllWarningLabel = null
        logLevelCombo = null
    }
}