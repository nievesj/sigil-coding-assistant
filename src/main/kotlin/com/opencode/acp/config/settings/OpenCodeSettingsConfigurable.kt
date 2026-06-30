package com.opencode.acp.config.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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
        // Apply the log level FIRST so that any side effects triggered by
        // applyTo() (e.g., MCP reinitialization) run under the new log level.
        // Otherwise, switching the log level to ERROR to silence noisy logs
        // wouldn't take effect for the very reinitialization it triggered.
        DebugLogConfig.apply(settings.logLevel)
        panel?.applyTo(settings)
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        panel?.setState(settings)
    }

    companion object {
        /** Opens the IDE Settings dialog on this configurable. */
        fun showSettingsDialog(project: Project) {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                OpenCodeSettingsConfigurable::class.java
            )
        }
    }
}
