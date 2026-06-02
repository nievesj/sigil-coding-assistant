package com.opencode.acp.config.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class OpenCodeSettingsConfigurable : Configurable {

    private var panel: OpenCodeSettingsPanel? = null

    override fun getDisplayName(): String = "OpenCode"

    override fun createComponent(): JComponent {
        val settingsPanel = OpenCodeSettingsPanel()
        settingsPanel.setState(OpenCodeSettingsState.getInstance().state)
        panel = settingsPanel
        return settingsPanel.panel
    }

    override fun isModified(): Boolean {
        val currentState = OpenCodeSettingsState.getInstance().state
        return panel?.isModified(currentState) ?: false
    }

    override fun apply() {
        val newState = panel?.getState() ?: return
        OpenCodeSettingsState.getInstance().loadState(newState)
    }

    override fun reset() {
        val currentState = OpenCodeSettingsState.getInstance().state
        panel?.setState(currentState)
    }
}
