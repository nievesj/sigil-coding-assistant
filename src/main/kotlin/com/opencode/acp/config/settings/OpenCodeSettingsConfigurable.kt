package com.opencode.acp.config.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class OpenCodeSettingsConfigurable : Configurable {

    private var panel: OpenCodeSettingsPanel? = null

    override fun getDisplayName(): String = "OpenCode"

    override fun createComponent(): JComponent {
        val settings = OpenCodeSettingsState.getInstance()
        val settingsPanel = OpenCodeSettingsPanel()
        settingsPanel.setState(settings)
        panel = settingsPanel
        return settingsPanel.panel
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        return panel?.isModified(settings) ?: false
    }

    override fun apply() {
        val settings = OpenCodeSettingsState.getInstance()
        panel?.applyTo(settings)
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        panel?.setState(settings)
    }
}