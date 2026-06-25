package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.mcp.McpConfigWriter
import com.opencode.acp.mcp.ToolInfo
import com.opencode.acp.mcp.ToolPermission
import com.opencode.acp.mcp.ToolSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Child configurable for MCP Integration settings.
 * Appears as "MCP" under the "OpenCode" settings node in the Settings tree.
 *
 * Contains:
 * - MCP server configuration (enable, URL, additional servers)
 * - Tool Permissions (discovery, enable/disable, filter, apply)
 */
class OpenCodeMcpConfigurable : Configurable {

    private var panel: OpenCodeMcpPanel? = null

    /** Snapshot of MCP settings at panel creation, used to detect changes in apply(). */
    private var prevEnableIntellijMcp: Boolean = false
    private var prevMcpServerUrl: String = ""
    private var prevAdditionalMcpServers: String = ""

    override fun getDisplayName(): String = "MCP"

    override fun createComponent(): JComponent {
        val settings = OpenCodeSettingsState.getInstance()
        val mcpPanel = OpenCodeMcpPanel()
        mcpPanel.setState(settings)
        panel = mcpPanel

        // Snapshot current MCP settings for change detection
        prevEnableIntellijMcp = settings.enableIntellijMcp
        prevMcpServerUrl = settings.mcpServerUrl
        prevAdditionalMcpServers = settings.additionalMcpServers

        // Wire retry button
        mcpPanel.mcpRetryButton.addActionListener {
            triggerMcpReinitialize(OpenCodeSettingsState.getInstance())
        }

        // Wire discover tools button
        mcpPanel.discoverToolsButton.addActionListener {
            discoverTools()
        }

        return mcpPanel.panel
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettingsState.getInstance()
        return panel?.isModified(settings) ?: false
    }

    override fun apply() {
        val settings = OpenCodeSettingsState.getInstance()
        // Save old permissions before applyTo overwrites them
        val oldPermissionsJson = settings.toolPermissions
        panel?.applyTo(settings)

        // Write tool permissions to .opencode/opencode.json only when permissions changed
        val toolPermissions = panel?.getAllToolPermissions()
        if (toolPermissions != null && toolPermissions.isNotEmpty() && settings.toolPermissions != oldPermissionsJson) {
            val permissions = toolPermissions.mapValues { (_, pair) ->
                pair.second
            }
            val projectPath = getActiveProjectPath()
            if (projectPath != null) {
                try {
                    val configWriter = McpConfigWriter(projectPath, settings)
                    val success = configWriter.writeToolPermissions(permissions)
                    if (success) {
                        logger.info { "[ACP] Tool permissions written to .opencode/opencode.json" }
                    } else {
                        logger.warn { "[ACP] Failed to write tool permissions to config file" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[ACP] Failed to write tool permissions" }
                }
            }
        }

        // Detect MCP setting changes and trigger re-initialization
        val mcpChanged = settings.enableIntellijMcp != prevEnableIntellijMcp ||
            settings.mcpServerUrl != prevMcpServerUrl ||
            settings.additionalMcpServers != prevAdditionalMcpServers

        if (mcpChanged) {
            triggerMcpReinitialize(settings)
            // Inform the user that a restart may be needed for full effect
            com.opencode.acp.chat.OpenCodeNotifications.showRestartWarning(
                "OpenCode MCP settings changed. The OpenCode server is being re-initialized; " +
                "restart the IDE (or the OpenCode server) for changes to take full effect."
            )
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
     */
    private fun triggerMcpReinitialize(settings: OpenCodeSettingsState) {
        val project = getActiveProject()
        if (project == null) {
            logger.warn { "[ACP] MCP settings changed but no open project found" }
            return
        }
        try {
            val service = project.service<OpenCodeService>()
            service.reinitializeMcpFromSettings()
            logger.info { "[ACP] MCP settings changed \u2014 re-initializing MCP integration" }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to re-initialize MCP after settings change" }
        }
    }

    /**
     * Discover available tools using ToolRegistry (single discovery path).
     */
    private fun discoverTools() {
        val settings = OpenCodeSettingsState.getInstance()
        val p = this.panel ?: return
        if (p.isDiscovering) return

        // Find the OpenCodeService to access toolRegistry
        val project = getActiveProject()
        if (project == null) {
            p.showToolPermissionsStatus("No open project found.", false)
            return
        }
        val service = try {
            project.service<OpenCodeService>()
        } catch (e: Exception) {
            p.showToolPermissionsStatus("OpenCode service not available.", false)
            return
        }

        val registry = service.toolRegistry
        if (registry == null) {
            p.showToolPermissionsStatus("OpenCode server is not running. Start it first.", false)
            return
        }

        logger.info { "[ACP] Starting tool discovery via ToolRegistry" }
        p.isDiscovering = true
        p.discoverToolsButton.isEnabled = false
        p.discoverToolsButton.text = "Discovering..."

        val panelRef = p
        val modality = ModalityState.stateForComponent(panelRef.panel)

        service.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val baseUrl = "http://127.0.0.1:${service.connectionManager.port}"
                val mcpUrls = service.mcpManager?.getServerUrls() ?: emptyMap()
                val tools = registry.discoverAll(baseUrl, mcpUrls)

                // Merge persisted permissions after discovery
                val persisted = parsePersistedToolPermissions(settings.toolPermissions)
                if (persisted.isNotEmpty()) {
                    registry.loadPermissions(persisted.mapValues { (_, pair) ->
                        val (_, permissionStr) = pair
                        ToolPermission.fromActionString(permissionStr)
                    })
                }

                val allTools = registry.getAllTools()

                ApplicationManager.getApplication().invokeLater({
                    // Guard: skip if the settings dialog was disposed while discovery was in flight
                    if (!panelRef.panel.isDisplayable) return@invokeLater
                    // Pass ToolInfo directly — no conversion needed
                    val toolMap = allTools.associate { tool -> tool.name to tool }
                    panelRef.updateToolPermissions(toolMap)
                    panelRef.discoverToolsButton.isEnabled = true
                    panelRef.discoverToolsButton.text = "Discover Tools"
                    panelRef.isDiscovering = false
                    // Cache discovered tools for next settings dialog open
                    settings.discoveredToolsJson = panelRef.generateDiscoveredToolsJson()
                    if (allTools.isEmpty()) {
                        panelRef.showToolPermissionsStatus(
                            "No tools found. Ensure the OpenCode server is running on port ${settings.port}.", false)
                    } else {
                        panelRef.showToolPermissionsStatus("Discovered ${allTools.size} tools successfully.", true)
                    }
                }, modality)
            } catch (e: CancellationException) {
                // Re-throw CancellationException to preserve structured concurrency.
                // The service scope may be cancelled during IDE dispose — propagating
                // the cancellation ensures proper coroutine cleanup.
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to discover tools via ToolRegistry" }
                ApplicationManager.getApplication().invokeLater({
                    if (!panelRef.panel.isDisplayable) return@invokeLater
                    panelRef.discoverToolsButton.isEnabled = true
                    panelRef.discoverToolsButton.text = "Discover Tools"
                    panelRef.isDiscovering = false
                    panelRef.showToolPermissionsStatus("Failed to discover tools: ${e.message}", false)
                }, modality)
            }
        }
    }

    /**
     * Parse persisted tool permissions JSON into a map.
     */
    private fun parsePersistedToolPermissions(perms: String): Map<String, Pair<Boolean, String>> {
        if (perms.isBlank()) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(perms).jsonObject
            obj.entries.associate { (toolName, element) ->
                val toolObj = element.jsonObject
                val enabled = toolObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val permission = toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow"
                toolName to Pair(enabled, permission)
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to parse persisted tool permissions — reverting to defaults" }
            com.opencode.acp.chat.OpenCodeNotifications.showRestartWarning(
                "Tool permissions data was corrupted and has been reset to defaults."
            )
            emptyMap()
        }
    }

    private fun getActiveProjectPath(): Path? {
        val project = getActiveProject() ?: return null
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
    }

    /**
     * Get the active project. Uses [ProjectManager.openProjects.firstOrNull]
     * which returns the most recently opened project. In multi-project windows,
     * this may not match the focused project — the Configurable API does not
     * carry a project reference, and WindowManager does not expose a focused-project
     * accessor. For this settings dialog, the first open project is the best
     * available approximation.
     */
    private fun getActiveProject(): com.intellij.openapi.project.Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }

}
