package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Represents a tool available to the LLM, either built-in or from an MCP server.
 *
 * @param id Stable unique key: "${serverName}_${name}" (e.g., "intellij_create_file").
 *   Used for disambiguation within [ToolRegistry._toolsSnapshot] — prevents collisions
 *   when two MCP servers expose a tool with the same name.
 * @param name Tool name (e.g., "create_file") — raw name used by McpConfigWriter.
 * @param description Human-readable description.
 * @param source Where the tool comes from (BUILTIN or MCP).
 * @param serverName Server name ("builtin" for built-in tools).
 * @param enabled Whether the tool is enabled.
 * @param permission Current permission setting.
 */
data class ToolInfo(
    val id: String,
    val name: String,
    val description: String,
    val source: ToolSource,
    val serverName: String = "builtin",
    val enabled: Boolean = true,
    val permission: ToolPermission = ToolPermission.ALLOW
) {
    companion object {
        /**
         * Create a ToolInfo with a computed [id] from the given parameters.
         */
        fun create(
            name: String,
            description: String,
            source: ToolSource,
            serverName: String = "builtin",
            enabled: Boolean = true,
            permission: ToolPermission = ToolPermission.ALLOW
        ): ToolInfo {
            val id = "${serverName}_${name}"
            return ToolInfo(
                id = id,
                name = name,
                description = description,
                source = source,
                serverName = serverName,
                enabled = enabled,
                permission = permission
            )
        }
    }
}

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
 *
 * Thread safety: All writes ([discoverAll], [loadPermissions], [setToolPermission],
 * [setToolEnabled], [enableAll], [disableAll]) produce a new immutable map under
 * [toolsMutex], then assign it atomically to [_toolsSnapshot]. Reads via [tools]
 * are lock-free (reads a volatile reference to an immutable map).
 */
class ToolRegistry(
    private val httpClient: HttpClient,
    private val mcpToolDiscovery: McpToolDiscovery,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val toolsMutex = Mutex()

    /**
     * Snapshot of current tools. Uses CopyOnWriteArrayList-style approach:
     * all writes (discoverAll, loadPermissions) produce a new immutable map
     * under the mutex, then assign it atomically. Reads are lock-free.
     */
    @Volatile
    private var _toolsSnapshot: Map<String, ToolInfo> = emptyMap()

    /** Lock-free read of the current tool snapshot. */
    val tools: Map<String, ToolInfo>
        get() = _toolsSnapshot

    /**
     * Discover all available tools from OpenCode and MCP servers.
     * Uses a two-phase approach: discover into a temporary map, then swap atomically.
     * This prevents partial data loss if discovery fails partway — the previous
     * full set is preserved until the new set is complete.
     *
     * @param opencodeBaseUrl The base URL of the OpenCode server (e.g., "http://127.0.0.1:4096")
     * @param mcpServerUrls Map of MCP server name to SSE URL
     * @return the list of discovered tools
     */
    suspend fun discoverAll(
        opencodeBaseUrl: String,
        mcpServerUrls: Map<String, String>
    ): List<ToolInfo> {
        // Phase 1: Discover into a temporary map (no lock needed)
        val newTools = mutableMapOf<String, ToolInfo>()
        discoverBuiltinTools(opencodeBaseUrl, newTools)
        discoverMcpTools(mcpServerUrls, newTools)

        // Phase 2: Atomic swap — build new snapshot, assign under lock
        toolsMutex.withLock {
            _toolsSnapshot = newTools.toMap()  // Immutable snapshot
        }

        logger.info { "[ACP] ToolRegistry: discovered ${newTools.size} total tools" }
        return newTools.values.toList()
    }

    /**
     * Discover built-in tools from OpenCode's /experimental/tool/ids endpoint.
     */
    private suspend fun discoverBuiltinTools(opencodeBaseUrl: String, newTools: MutableMap<String, ToolInfo>) {
        try {
            val response = httpClient.get("$opencodeBaseUrl/experimental/tool/ids")
            if (!response.status.isSuccess()) {
                logger.warn { "[ACP] ToolRegistry: /experimental/tool/ids returned ${response.status}" }
                return
            }

            val body = response.bodyAsText()
            val toolIds = parseToolIds(body)

            for (toolId in toolIds) {
                val tool = ToolInfo.create(
                    name = toolId,
                    description = getBuiltinToolDescription(toolId),
                    source = ToolSource.BUILTIN,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
                newTools[tool.id] = tool
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
    fun getBuiltinToolDescription(toolId: String): String {
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
    private suspend fun discoverMcpTools(mcpServerUrls: Map<String, String>, newTools: MutableMap<String, ToolInfo>) {
        val mcpTools = mcpToolDiscovery.discoverAllTools(mcpServerUrls)

        for ((serverName, toolDescriptors) in mcpTools) {
            for (tool in toolDescriptors) {
                val fullToolName = "${serverName}_${tool.name}"
                val toolInfo = ToolInfo.create(
                    name = tool.name,
                    description = tool.description,
                    source = ToolSource.MCP,
                    serverName = serverName,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
                newTools[toolInfo.id] = toolInfo
            }
        }
    }

    /**
     * Update the permission for a specific tool by its ID.
     */
    suspend fun setToolPermission(toolId: String, permission: ToolPermission) = toolsMutex.withLock {
        val tool = _toolsSnapshot[toolId] ?: return@withLock
        val newSnapshot = _toolsSnapshot.toMutableMap()
        newSnapshot[toolId] = tool.copy(permission = permission)
        _toolsSnapshot = newSnapshot
    }

    /**
     * Update the enabled state for a specific tool by its ID.
     */
    suspend fun setToolEnabled(toolId: String, enabled: Boolean) = toolsMutex.withLock {
        val tool = _toolsSnapshot[toolId] ?: return@withLock
        val newSnapshot = _toolsSnapshot.toMutableMap()
        newSnapshot[toolId] = tool.copy(
            enabled = enabled,
            permission = if (enabled) ToolPermission.ALLOW else ToolPermission.DENY
        )
        _toolsSnapshot = newSnapshot
    }

    /**
     * Get tools grouped by source.
     */
    fun getToolsBySource(): Map<ToolSource, List<ToolInfo>> {
        return _toolsSnapshot.values.groupBy { it.source }
    }

    /**
     * Get MCP tools grouped by server name.
     */
    fun getMcpToolsByServer(): Map<String, List<ToolInfo>> {
        return _toolsSnapshot.values
            .filter { it.source == ToolSource.MCP }
            .groupBy { it.serverName }
    }

    /**
     * Get all tools as a list, sorted by source then name.
     */
    fun getAllTools(): List<ToolInfo> {
        return _toolsSnapshot.values.sortedWith(compareBy<ToolInfo> { it.source }.thenBy { it.name })
    }

    /**
     * Get enabled tool count.
     */
    fun getEnabledCount(): Int = _toolsSnapshot.values.count { it.enabled }

    /**
     * Get total tool count.
     */
    fun getTotalCount(): Int = _toolsSnapshot.size

    /**
     * Enable all tools. Preserves "ask" permission — only overrides "deny" to "allow".
     */
    suspend fun enableAll() = toolsMutex.withLock {
        val newSnapshot = _toolsSnapshot.toMutableMap()
        for ((id, tool) in newSnapshot) {
            val newPermission = if (tool.permission == ToolPermission.DENY) ToolPermission.ALLOW else tool.permission
            newSnapshot[id] = tool.copy(enabled = true, permission = newPermission)
        }
        _toolsSnapshot = newSnapshot
    }

    /**
     * Disable all tools.
     */
    suspend fun disableAll() = toolsMutex.withLock {
        val newSnapshot = _toolsSnapshot.toMutableMap()
        for ((id, tool) in newSnapshot) {
            newSnapshot[id] = tool.copy(enabled = false, permission = ToolPermission.DENY)
        }
        _toolsSnapshot = newSnapshot
    }

    /**
     * Filter tools by name or description.
     */
    fun filterTools(query: String): List<ToolInfo> {
        val lowerQuery = query.lowercase()
        return _toolsSnapshot.values.filter {
            it.name.lowercase().contains(lowerQuery) ||
                it.description.lowercase().contains(lowerQuery)
        }.sortedWith(compareBy<ToolInfo> { it.source }.thenBy { it.name })
    }

    /**
     * Filter tools by source.
     */
    fun filterBySource(source: ToolSource): List<ToolInfo> {
        return _toolsSnapshot.values.filter { it.source == source }
            .sortedBy { it.name }
    }

    /**
     * Filter tools by server name (for MCP tools).
     */
    fun filterByServer(serverName: String): List<ToolInfo> {
        return _toolsSnapshot.values.filter { it.serverName == serverName }
            .sortedBy { it.name }
    }

    /**
     * Load persisted permissions into the registry. Must be called after
     * [discoverAll] to preserve user customizations. Acquires the mutex
     * to prevent concurrent modification with [discoverAll].
     *
     * The permissions map may be keyed by either:
     * - Raw tool names (e.g., "bash") — from toolPermissions settings field
     * - Compound IDs (e.g., "builtin_bash") — from discoveredToolsJson
     * Both formats are handled: if a key doesn't match a ToolInfo.id directly,
     * it's matched against ToolInfo.name as a fallback.
     */
    suspend fun loadPermissions(permissions: Map<String, ToolPermission>) = toolsMutex.withLock {
        val current = _toolsSnapshot.toMutableMap()
        // Build a reverse index: raw name → compound id
        val nameToId = current.values.associate { it.name to it.id }
        for ((key, permission) in permissions) {
            // Try compound ID first, then raw name fallback
            val resolvedId = if (current.containsKey(key)) key else nameToId[key]
            if (resolvedId != null) {
                current[resolvedId]?.let { existing ->
                    current[resolvedId] = existing.copy(permission = permission)
                }
            }
        }
        _toolsSnapshot = current.toMap()  // Immutable snapshot
    }

    /**
     * Export tool permissions as a JSON string for settings persistence.
     * Format: {"toolId":{"enabled":true,"permission":"allow"}, ...}
     * Uses buildJsonObject to construct the structure manually since
     * Map<String, Map<String, Any>> has no built-in kotlinx.serialization serializer.
     */
    fun exportPermissionsJson(): String {
        val snapshot = _toolsSnapshot  // Lock-free read
        val root = buildJsonObject {
            for (tool in snapshot.values) {
                put(tool.id, buildJsonObject {
                    put("enabled", tool.enabled)
                    put("permission", tool.permission.toActionString())
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Export tool permissions as a Map for McpConfigWriter.
     * Keys are raw tool names (matching McpConfigWriter convention).
     * Uses [ToolInfo.name] directly to produce raw names compatible with
     * McpConfigWriter (e.g., "bash", "intellij_read_file").
     */
    fun exportPermissions(): Map<String, ToolPermission> {
        val snapshot = _toolsSnapshot  // Lock-free read
        return snapshot.values.associate { it.name to it.permission }
    }
}
