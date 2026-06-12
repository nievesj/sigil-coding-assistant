package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Represents a tool available to the LLM, either built-in or from an MCP server.
 */
data class ToolInfo(
    val name: String,
    val description: String,
    val source: ToolSource,
    val serverName: String = "builtin",  // "builtin", "intellij", "github", etc.
    val enabled: Boolean = true,
    val permission: ToolPermission = ToolPermission.ALLOW
)

/**
 * Where a tool comes from.
 */
enum class ToolSource {
    BUILTIN,      // OpenCode's built-in tools (read, edit, write, grep, glob, bash, etc.)
    MCP           // Tools from MCP servers (intellij_read_file, intellij_edit_file, etc.)
}

/**
 * Registry that aggregates tools from all sources (built-in + MCP servers).
 *
 * Provides a unified view of all available tools with their current permission settings.
 * Used by the settings UI to display tool toggles and by the config writer to generate
 * permission rules.
 */
class ToolRegistry(
    private val httpClient: HttpClient,
    private val mcpToolDiscovery: McpToolDiscovery,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val _tools = mutableMapOf<String, ToolInfo>()
    val tools: Map<String, ToolInfo> get() = _tools.toMap()

    /**
     * Discover all available tools from OpenCode and MCP servers.
     *
     * @param opencodeBaseUrl The base URL of the OpenCode server (e.g., "http://127.0.0.1:4096")
     * @param mcpServerUrls Map of MCP server name to SSE URL
     */
    suspend fun discoverAll(
        opencodeBaseUrl: String,
        mcpServerUrls: Map<String, String>
    ) {
        withContext(Dispatchers.IO) {
            // Discover built-in tools from OpenCode
            discoverBuiltinTools(opencodeBaseUrl)

            // Discover MCP tools from each server
            discoverMcpTools(mcpServerUrls)

            logger.info { "[ACP] ToolRegistry: discovered ${_tools.size} total tools" }
        }
    }

    /**
     * Discover built-in tools from OpenCode's /experimental/tool/ids endpoint.
     */
    private suspend fun discoverBuiltinTools(opencodeBaseUrl: String) {
        try {
            val response = httpClient.get("$opencodeBaseUrl/experimental/tool/ids")
            if (!response.status.isSuccess()) {
                logger.warn { "[ACP] ToolRegistry: /experimental/tool/ids returned ${response.status}" }
                return
            }

            val body = response.bodyAsText()
            val toolIds = parseToolIds(body)

            for (toolId in toolIds) {
                _tools[toolId] = ToolInfo(
                    name = toolId,
                    description = getBuiltinToolDescription(toolId),
                    source = ToolSource.BUILTIN,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
            }

            logger.info { "[ACP] ToolRegistry: discovered ${toolIds.size} built-in tools" }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] ToolRegistry: failed to discover built-in tools" }
        }
    }

    /**
     * Parse the response from /experimental/tool/ids.
     *
     * The server returns a bare JSON array: ["bash", "read", ...]
     * Fallback: wrapped format {"value": [...]}
     */
    private fun parseToolIds(body: String): List<String> {
        return try {
            val element = json.parseToJsonElement(body)
            val array = if (element is kotlinx.serialization.json.JsonArray) {
                element
            } else {
                element.jsonObject["value"]?.jsonArray ?: return emptyList()
            }
            array.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] ToolRegistry: failed to parse tool IDs" }
            emptyList()
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

    /**
     * Discover tools from MCP servers using the MCP protocol.
     */
    private suspend fun discoverMcpTools(mcpServerUrls: Map<String, String>) {
        val mcpTools = mcpToolDiscovery.discoverAllTools(mcpServerUrls)

        for ((serverName, toolDescriptors) in mcpTools) {
            for (tool in toolDescriptors) {
                val fullToolName = if (serverName == "intellij") {
                    "intellij_${tool.name}"
                } else {
                    "${serverName}_${tool.name}"
                }

                _tools[fullToolName] = ToolInfo(
                    name = fullToolName,
                    description = tool.description,
                    source = ToolSource.MCP,
                    serverName = serverName,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
            }
        }
    }

    /**
     * Update the permission for a specific tool.
     */
    fun setToolPermission(toolName: String, permission: ToolPermission) {
        val tool = _tools[toolName] ?: return
        _tools[toolName] = tool.copy(permission = permission)
    }

    /**
     * Update the enabled state for a specific tool.
     */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        val tool = _tools[toolName] ?: return
        _tools[toolName] = tool.copy(
            enabled = enabled,
            permission = if (enabled) ToolPermission.ALLOW else ToolPermission.DENY
        )
    }

    /**
     * Get tools grouped by source.
     */
    fun getToolsBySource(): Map<ToolSource, List<ToolInfo>> {
        return _tools.values.groupBy { it.source }
    }

    /**
     * Get MCP tools grouped by server name.
     */
    fun getMcpToolsByServer(): Map<String, List<ToolInfo>> {
        return _tools.values
            .filter { it.source == ToolSource.MCP }
            .groupBy { it.serverName }
    }

    /**
     * Get all tools as a list, sorted by source then name.
     */
    fun getAllTools(): List<ToolInfo> {
        return _tools.values.sortedWith(compareBy<ToolInfo> { it.source }.thenBy { it.name })
    }

    /**
     * Get enabled tool count.
     */
    fun getEnabledCount(): Int = _tools.values.count { it.enabled }

    /**
     * Get total tool count.
     */
    fun getTotalCount(): Int = _tools.size

    /**
     * Enable all tools. Preserves "ask" permission — only overrides "deny" to "allow".
     */
    fun enableAll() {
        for ((name, tool) in _tools) {
            val newPermission = if (tool.permission == ToolPermission.DENY) ToolPermission.ALLOW else tool.permission
            _tools[name] = tool.copy(enabled = true, permission = newPermission)
        }
    }

    /**
     * Disable all tools.
     */
    fun disableAll() {
        for ((name, tool) in _tools) {
            _tools[name] = tool.copy(enabled = false, permission = ToolPermission.DENY)
        }
    }

    /**
     * Filter tools by name or description.
     */
    fun filterTools(query: String): List<ToolInfo> {
        val lowerQuery = query.lowercase()
        return _tools.values.filter {
            it.name.lowercase().contains(lowerQuery) ||
                it.description.lowercase().contains(lowerQuery)
        }.sortedWith(compareBy<ToolInfo> { it.source }.thenBy { it.name })
    }

    /**
     * Filter tools by source.
     */
    fun filterBySource(source: ToolSource): List<ToolInfo> {
        return _tools.values.filter { it.source == source }
            .sortedBy { it.name }
    }

    /**
     * Filter tools by server name (for MCP tools).
     */
    fun filterByServer(serverName: String): List<ToolInfo> {
        return _tools.values.filter { it.serverName == serverName }
            .sortedBy { it.name }
    }

    /**
     * Load saved permission settings from a map.
     */
    fun loadPermissions(permissions: Map<String, ToolPermission>) {
        for ((toolName, permission) in permissions) {
            setToolPermission(toolName, permission)
        }
    }

    /**
     * Export current permission settings as a map.
     */
    fun exportPermissions(): Map<String, ToolPermission> {
        return _tools.mapValues { it.value.permission }
    }
}
