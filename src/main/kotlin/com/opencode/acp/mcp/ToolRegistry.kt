package com.opencode.acp.mcp

import kotlinx.coroutines.sync.Mutex
import io.ktor.client.HttpClient

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
 * This class is the storage layer only — discovery logic is delegated to [ToolDiscoverer]
 * and permission management to [ToolPermissionManager] (TDD §4.2.5 SRP split).
 *
 * Thread safety: All writes (discoverAll, loadPermissions, setToolPermission,
 * setToolEnabled, enableAll, disableAll) produce a new immutable map under
 * [toolsMutex], then assign it atomically to [_toolsSnapshot]. Reads via [tools]
 * are lock-free (reads a volatile reference to an immutable map).
 */
class ToolRegistry(
    httpClient: HttpClient,
    mcpToolDiscovery: McpToolDiscovery,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
) {
    private val toolsMutex = Mutex()

    private val discoverer = ToolDiscoverer(httpClient, mcpToolDiscovery, json)
    private val permissionManager = ToolPermissionManager(json)

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

    // --- Discovery (delegated to ToolDiscoverer) ---

    /**
     * Discover all available tools from OpenCode and MCP servers.
     * Delegates to [ToolDiscoverer.discoverAll].
     *
     * @param opencodeBaseUrl The base URL of the OpenCode server (e.g., "http://127.0.0.1:4096")
     * @param mcpServerUrls Map of MCP server name to SSE URL
     * @return the list of discovered tools
     */
    suspend fun discoverAll(
        opencodeBaseUrl: String,
        mcpServerUrls: Map<String, String>
    ): List<ToolInfo> = discoverer.discoverAll(
        opencodeBaseUrl = opencodeBaseUrl,
        mcpServerUrls = mcpServerUrls,
        toolsMutex = toolsMutex,
        snapshotRef = { _toolsSnapshot },
        snapshotSetter = { _toolsSnapshot = it }
    )

    /** Delegate access to the built-in tool description catalog. */
    fun getBuiltinToolDescription(toolId: String): String =
        discoverer.getBuiltinToolDescription(toolId)

    // --- Permission management (delegated to ToolPermissionManager) ---

    /**
     * Update the permission for a specific tool by its ID.
     */
    suspend fun setToolPermission(toolId: String, permission: ToolPermission) =
        permissionManager.setToolPermission(toolId, permission, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Update the enabled state for a specific tool by its ID.
     */
    suspend fun setToolEnabled(toolId: String, enabled: Boolean) =
        permissionManager.setToolEnabled(toolId, enabled, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Enable all tools. Restores permissions from [savedPermissions] if provided
     * (preserving ASK settings). For tools not in [savedPermissions], only overrides
     * DENY to ALLOW (preserving existing ASK/ALLOW).
     *
     * @param savedPermissions Optional map of tool ID to permission, loaded from
     *   persistent settings. If null or empty, falls back to in-memory cache
     *   which does NOT survive IDE restarts.
     */
    suspend fun enableAll(savedPermissions: Map<String, ToolPermission>? = null) =
        permissionManager.enableAll(savedPermissions, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Disable all tools. Saves each tool's current permission so [enableAll]
     * can restore ASK settings that were active before the disable.
     *
     * @return The saved permissions map, which the caller should persist to
     *   settings to survive IDE restarts.
     */
    suspend fun disableAll(): Map<String, ToolPermission> =
        permissionManager.disableAll(toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Batch-set enabled state for a specific set of tools (the panel's visible subset),
     * preserving the panel's filtered-batch semantics. Acquires [toolsMutex].
     *
     * Matches by raw name OR compound id, applying to ALL matches.
     */
    suspend fun syncEnabled(toolNames: Set<String>, enabled: Boolean) =
        permissionManager.syncEnabled(toolNames, enabled, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Load persisted permissions into the registry. Must be called after
     * [discoverAll] to preserve user customizations.
     */
    suspend fun loadPermissions(permissions: Map<String, ToolPermission>) =
        permissionManager.loadPermissions(permissions, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    /**
     * Load persisted enabled state AND permissions into the registry. Must be
     * called after [discoverAll] to preserve user customizations.
     *
     * This is the preferred method over [loadPermissions] when the caller has
     * both the enabled flag and the permission level.
     */
    suspend fun loadEnabledAndPermissions(state: Map<String, Pair<Boolean, ToolPermission>>) =
        permissionManager.loadEnabledAndPermissions(state, toolsMutex, { _toolsSnapshot }, { _toolsSnapshot = it })

    // --- Serialization (delegated to ToolPermissionManager) ---

    /**
     * Export tool permissions as a JSON string for settings persistence.
     * Format: {"toolId":{"enabled":true,"permission":"allow"}, ...}
     */
    fun exportPermissionsJson(): String =
        permissionManager.exportPermissionsJsonFromSnapshot(_toolsSnapshot)

    /**
     * Serialize an explicit permissions map to the settings-persistence JSON format.
     */
    fun exportPermissionsJson(permissions: Map<String, Pair<Boolean, ToolPermission>>): String =
        permissionManager.exportPermissionsJson(permissions)

    /**
     * Export tool permissions as a Map for McpConfigWriter.
     * Keys are raw tool names (matching McpConfigWriter convention).
     */
    fun exportPermissions(): Map<String, ToolPermission> =
        permissionManager.exportPermissions(_toolsSnapshot)

    // --- Read-only queries (storage layer) ---

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
}