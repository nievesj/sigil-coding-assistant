package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.mcp.McpConfigWriter
import com.opencode.acp.mcp.McpToolDiscovery
import com.opencode.acp.mcp.ToolPermission
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
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
        panel?.applyTo(settings)

        // Write tool permissions to .opencode/opencode.json if tools were discovered
        val toolPermissions = panel?.getAllToolPermissions()
        if (toolPermissions != null && toolPermissions.isNotEmpty()) {
            val permissions = toolPermissions.mapValues { (_, pair) ->
                val (_, permissionStr) = pair
                when (permissionStr) {
                    "allow" -> ToolPermission.ALLOW
                    "ask" -> ToolPermission.ASK
                    "deny" -> ToolPermission.DENY
                    else -> ToolPermission.ALLOW
                }
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
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
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
     * Discover available tools from OpenCode and MCP servers.
     */
    private fun discoverTools() {
        val settings = OpenCodeSettingsState.getInstance()
        val p = this.panel ?: return
        if (p.isDiscovering) return

        logger.info { "[ACP] Starting tool discovery on port ${settings.port}" }
        p.isDiscovering = true
        p.discoverToolsButton.isEnabled = false
        p.discoverToolsButton.text = "Discovering..."

        val port = settings.port
        val enableIntellijMcp = settings.enableIntellijMcp
        val mcpServerUrl = settings.mcpServerUrl
        val additionalMcpServers = settings.additionalMcpServers
        val toolPermissions = settings.toolPermissions
        // Capture panel ref and modality at click time — safe for cross-thread use
        val panelRef = p
        val modality = ModalityState.stateForComponent(panelRef.panel)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val (tools, errors) = discoverToolsWithHttp(port, enableIntellijMcp, mcpServerUrl, additionalMcpServers, toolPermissions)
                ApplicationManager.getApplication().invokeLater({
                    logger.info { "[ACP] Tool discovery UI update: ${tools.size} tools, ${errors.size} errors" }
                    panelRef.updateToolPermissions(tools)
                    panelRef.discoverToolsButton.isEnabled = true
                    panelRef.discoverToolsButton.text = "Discover Tools"
                    panelRef.isDiscovering = false
                    // Cache discovered tools for next settings dialog open
                    settings.discoveredToolsJson = panelRef.generateDiscoveredToolsJson()
                    if (tools.isEmpty() && errors.isEmpty()) {
                        panelRef.showToolPermissionsStatus(
                            "No tools found. Ensure the OpenCode server is running on port $port.", false)
                    } else if (errors.isNotEmpty()) {
                        panelRef.showToolPermissionsStatus(
                            "Discovered ${tools.size} tools. Errors: ${errors.joinToString("; ")}", tools.isNotEmpty())
                    } else {
                        panelRef.showToolPermissionsStatus("Discovered ${tools.size} tools successfully.", true)
                    }
                }, modality)
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to discover tools" }
                ApplicationManager.getApplication().invokeLater({
                    panelRef.discoverToolsButton.isEnabled = true
                    panelRef.discoverToolsButton.text = "Discover Tools"
                    panelRef.isDiscovering = false
                    panelRef.showToolPermissionsStatus("Failed to discover tools: ${e.message}", false)
                }, modality)
            }
        }
    }

    /**
     * Discover tools using plain java.net.HttpURLConnection for built-in tools
     * and Ktor for MCP tools.
     */
    private fun discoverToolsWithHttp(
        port: Int,
        enableIntellijMcp: Boolean,
        mcpServerUrl: String,
        additionalMcpServers: String,
        toolPermissions: String
    ): Pair<Map<String, OpenCodeMcpPanel.ToolPermissionInfo>, List<String>> {
        val tools = mutableMapOf<String, OpenCodeMcpPanel.ToolPermissionInfo>()
        val errors = mutableListOf<String>()
        val baseUrl = "http://127.0.0.1:$port"
        val persisted = parsePersistedToolPermissions(toolPermissions)

        // Discover built-in tools from OpenCode
        try {
            logger.info { "[ACP] Fetching built-in tools from $baseUrl/experimental/tool/ids" }
            val conn = java.net.URI("$baseUrl/experimental/tool/ids").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            logger.info { "[ACP] OpenCode server responded with HTTP $responseCode" }

            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val body = reader.readText()
                reader.close()

                val toolIds = try {
                    val element = json.parseToJsonElement(body)
                    // The server returns a bare JSON array: ["bash", "read", ...]
                    val array = if (element is JsonArray) {
                        element
                    } else {
                        // Fallback: wrapped format {"value": [...]}
                        element.jsonObject["value"]?.jsonArray ?: buildJsonArray {}
                    }
                    array.mapNotNull { it.jsonPrimitive.contentOrNull }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Failed to parse tool IDs response" }
                    emptyList()
                }

                for (toolId in toolIds) {
                    val prev = persisted[toolId]
                    tools[toolId] = OpenCodeMcpPanel.ToolPermissionInfo(
                        description = getBuiltinToolDescription(toolId),
                        source = "builtin",
                        serverName = "builtin",
                        enabled = prev?.first ?: true,
                        permission = prev?.second ?: "allow"
                    )
                }
                logger.info { "[ACP] Discovered ${toolIds.size} built-in tools" }
            } else {
                errors.add("Built-in tools: HTTP $responseCode (expected 200)")
            }
            conn.disconnect()
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to connect to OpenCode server at $baseUrl" }
            errors.add("Cannot connect to OpenCode server at $baseUrl: ${e.message}")
        }

        // Discover MCP tools if enabled
        if (enableIntellijMcp && mcpServerUrl.isNotBlank()) {
            val (mcpTools, mcpError) = discoverMcpToolsWithHttp(mcpServerUrl, "intellij", persisted)
            tools.putAll(mcpTools)
            if (mcpError != null) errors.add(mcpError)
        }

        // Discover additional MCP servers
        if (additionalMcpServers.isNotBlank()) {
            try {
                val array = Json.parseToJsonElement(additionalMcpServers).jsonArray
                for (element in array) {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val (mcpTools, mcpError) = discoverMcpToolsWithHttp(url, name, persisted)
                        tools.putAll(mcpTools)
                        if (mcpError != null) errors.add(mcpError)
                    }
                }
            } catch (e: Exception) {
                errors.add("Additional MCP servers: ${e.message}")
            }
        }

        return Pair(tools, errors)
    }

    /**
     * Discover MCP tools using Ktor + McpToolDiscovery.
     */
    private fun discoverMcpToolsWithHttp(
        serverUrl: String,
        serverName: String,
        persisted: Map<String, Pair<Boolean, String>>
    ): Pair<Map<String, OpenCodeMcpPanel.ToolPermissionInfo>, String?> {
        return try {
            val client = HttpClient(Java) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 3000
                    requestTimeoutMillis = 5000
                    socketTimeoutMillis = 5000
                }
            }
            val discovery = McpToolDiscovery(client)
            val toolDescriptors = runBlocking { discovery.discoverTools(serverUrl) }
            val result = mutableMapOf<String, OpenCodeMcpPanel.ToolPermissionInfo>()
            for (tool in toolDescriptors) {
                val fullToolName = "${serverName}_${tool.name}"
                val prev = persisted[fullToolName]
                result[fullToolName] = OpenCodeMcpPanel.ToolPermissionInfo(
                    description = tool.description,
                    source = "mcp",
                    serverName = serverName,
                    enabled = prev?.first ?: true,
                    permission = prev?.second ?: "allow"
                )
            }
            client.close()
            Pair(result, null)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to discover MCP tools from $serverUrl" }
            Pair(emptyMap(), "MCP $serverName: ${e.message}")
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
            emptyMap()
        }
    }

    /**
     * Get a human-readable description for a built-in tool.
     */
    private fun getBuiltinToolDescription(toolId: String): String {
        return when (toolId) {
            "bash" -> "Execute shell commands"
            "read" -> "Read file contents"
            "glob" -> "Find files by pattern"
            "grep" -> "Search file contents with regex"
            "edit" -> "Edit files with string replacement"
            "write" -> "Write new files"
            "task" -> "Launch specialized agents"
            "webfetch" -> "Fetch URLs and extract content"
            "todowrite" -> "Manage task lists"
            "websearch" -> "Search the web"
            "skill" -> "Load specialized workflows"
            "apply_patch" -> "Apply patches to files"
            "council_session" -> "Multi-LLM consensus engine"
            "auto_continue" -> "Toggle auto-continuation"
            "ast_grep_search" -> "AST-aware code search"
            "ast_grep_replace" -> "AST-aware code replacement"
            "subtask" -> "Run child worker sessions"
            "read_session" -> "Read conversation transcripts"
            else -> "Built-in tool: $toolId"
        }
    }

    private fun getActiveProjectPath(): Path? {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return null
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
    }

}