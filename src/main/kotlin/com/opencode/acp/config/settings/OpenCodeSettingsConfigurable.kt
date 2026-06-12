package com.opencode.acp.config.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.opencode.acp.chat.service.OpenCodeService
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.JComponent

private val logger = KotlinLogging.logger {}

class OpenCodeSettingsConfigurable : Configurable {

    private var panel: OpenCodeSettingsPanel? = null

    /** Snapshot of MCP settings at panel creation, used to detect changes in apply(). */
    private var prevEnableIntellijMcp: Boolean = false
    private var prevMcpServerUrl: String = ""
    private var prevAdditionalMcpServers: String = ""

    override fun getDisplayName(): String = "OpenCode"

    override fun createComponent(): JComponent {
        val settings = OpenCodeSettingsState.getInstance()
        val settingsPanel = OpenCodeSettingsPanel()
        settingsPanel.setState(settings)
        panel = settingsPanel
        // Snapshot current MCP settings for change detection
        prevEnableIntellijMcp = settings.enableIntellijMcp
        prevMcpServerUrl = settings.mcpServerUrl
        prevAdditionalMcpServers = settings.additionalMcpServers

        // Wire retry button
        settingsPanel.mcpRetryButton.addActionListener {
            triggerMcpReinitialize(OpenCodeSettingsState.getInstance())
        }

        return settingsPanel.panel
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        return panel?.isModified(settings) ?: false
    }

    override fun apply() {
        val settings = OpenCodeSettingsState.getInstance()
        panel?.applyTo(settings)

        // Detect MCP setting changes and trigger re-initialization
        val mcpChanged = settings.enableIntellijMcp != prevEnableIntellijMcp ||
            settings.mcpServerUrl != prevMcpServerUrl ||
            settings.additionalMcpServers != prevAdditionalMcpServers

        if (mcpChanged) {
            triggerMcpReinitialize(settings)
            prevEnableIntellijMcp = settings.enableIntellijMcp
            prevMcpServerUrl = settings.mcpServerUrl
            prevAdditionalMcpServers = settings.additionalMcpServers
        }
    }

    override fun reset() {
        val settings = OpenCodeSettingsState.getInstance()
        panel?.setState(settings)
        prevEnableIntellijMcp = settings.enableIntellijMcp
        prevMcpServerUrl = settings.mcpServerUrl
        prevAdditionalMcpServers = settings.additionalMcpServers
    }

    /**
     * Trigger MCP re-initialization on the active project's OpenCodeService.
     * Since this is an applicationConfigurable (no project reference), we find
     * the first open project that has an OpenCodeService.
     * Uses the non-suspend wrapper since apply() runs on EDT.
     */
    private fun triggerMcpReinitialize(settings: OpenCodeSettingsState) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            logger.warn { "[ACP] MCP settings changed but no open project found — changes will take effect on next IDE start" }
            return
        }
        try {
            val service = project.service<OpenCodeService>()
            service.reinitializeMcpFromSettings()
            logger.info { "[ACP] MCP settings changed — re-initializing MCP integration" }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to re-initialize MCP after settings change" }
        }
    }
}