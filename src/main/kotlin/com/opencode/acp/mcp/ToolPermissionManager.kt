package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val permissionLogger = KotlinLogging.logger {}

/**
 * Manages tool permission state: enable/disable, allow/ask/deny, persistence,
 * and batch operations.
 *
 * Extracted from [ToolRegistry] per TDD §4.2.5 (SRP: Split ToolRegistry).
 * Does NOT own the tool snapshot — that lives in [ToolRegistry], which
 * passes its mutex and snapshot reference so permission mutations swap atomically.
 */
class ToolPermissionManager(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    /**
     * Permissions saved before [disableAll], so [enableAll] can restore
     * ASK permissions that were active before the disable. Prevents ASK→ALLOW
     * promotion on a disable→enable cycle.
     */
    private val savedPermissionsBeforeDisable: MutableMap<String, ToolPermission> = mutableMapOf()

    /**
     * Update the permission for a specific tool by its ID.
     */
    suspend fun setToolPermission(
        toolId: String,
        permission: ToolPermission,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val tool = snapshotRef()[toolId] ?: return@withLock
        val newSnapshot = snapshotRef().toMutableMap()
        newSnapshot[toolId] = tool.copy(permission = permission)
        snapshotSetter(newSnapshot)
    }

    /**
     * Update the enabled state for a specific tool by its ID.
     */
    suspend fun setToolEnabled(
        toolId: String,
        enabled: Boolean,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val tool = snapshotRef()[toolId] ?: return@withLock
        val newSnapshot = snapshotRef().toMutableMap()
        // Enabled and permission are orthogonal: disabling a tool doesn't change
        // its permission — the user might want "ask" preserved for re-enable.
        newSnapshot[toolId] = tool.copy(enabled = enabled)
        snapshotSetter(newSnapshot)
    }

    /**
     * Enable all tools. Restores permissions from [savedPermissions] if provided
     * (preserving ASK settings). For tools not in [savedPermissions], only overrides
     * DENY to ALLOW (preserving existing ASK/ALLOW).
     *
     * @param savedPermissions Optional map of tool ID to permission, loaded from
     *   persistent settings. If null or empty, falls back to in-memory cache
     *   ([savedPermissionsBeforeDisable]) which does NOT survive IDE restarts.
     */
    suspend fun enableAll(
        savedPermissions: Map<String, ToolPermission>?,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val newSnapshot = snapshotRef().toMutableMap()
        val source = savedPermissions?.takeIf { it.isNotEmpty() } ?: savedPermissionsBeforeDisable
        for ((id, tool) in newSnapshot) {
            val restoredPermission = source[id]
                ?: if (tool.permission == ToolPermission.DENY) ToolPermission.ALLOW else tool.permission
            newSnapshot[id] = tool.copy(enabled = true, permission = restoredPermission)
        }
        snapshotSetter(newSnapshot)
    }

    /**
     * Disable all tools. Saves each tool's current permission so [enableAll]
     * can restore ASK settings that were active before the disable.
     *
     * @return The saved permissions map, which the caller should persist to
     *   settings (e.g., [OpenCodeSettingsState.savedToolPermissionsBeforeDisable])
     *   to survive IDE restarts.
     */
    suspend fun disableAll(
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ): Map<String, ToolPermission> = toolsMutex.withLock {
        val newSnapshot = snapshotRef().toMutableMap()
        val saved = mutableMapOf<String, ToolPermission>()
        for ((id, tool) in newSnapshot) {
            saved[id] = tool.permission
            savedPermissionsBeforeDisable[id] = tool.permission
            newSnapshot[id] = tool.copy(enabled = false, permission = ToolPermission.DENY)
        }
        snapshotSetter(newSnapshot)
        saved
    }

    /**
     * Batch-set enabled state for a specific set of tools (the panel's visible subset),
     * preserving the panel's filtered-batch semantics. Acquires [toolsMutex].
     *
     * Unlike [enableAll]/[disableAll], which operate on all tools, this only affects
     * the named tools — tools filtered out of the panel view are untouched.
     *
     * When enabling, preserves "ask" permission — only overrides "deny" to "allow"
     * (same semantics as [enableAll]).
     *
     * Matches by raw name OR compound id, applying to ALL matches. This handles
     * the case where two MCP servers expose tools with the same raw name (e.g.,
     * both server A and server B have "create_file") — both are updated.
     *
     * NOTE: The per-tool checkbox in the settings panel uses the compound id
     * (ToolInfo.id) as the map key, so individual toggles ARE server-specific.
     * Only this batch operation (Enable All / Disable All) has this limitation.
     */
    suspend fun syncEnabled(
        toolNames: Set<String>,
        enabled: Boolean,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val newSnapshot = snapshotRef().toMutableMap()
        for (name in toolNames) {
            // Match by raw name OR compound id — apply to ALL matches (handles duplicate names across servers)
            val matchingTools = newSnapshot.values.filter { it.name == name || it.id == name }
            for (tool in matchingTools) {
                newSnapshot[tool.id] = if (enabled) {
                    val newPermission = if (tool.permission == ToolPermission.DENY) ToolPermission.ALLOW else tool.permission
                    tool.copy(enabled = true, permission = newPermission)
                } else {
                    tool.copy(enabled = false, permission = ToolPermission.DENY)
                }
            }
        }
        snapshotSetter(newSnapshot)
    }

    /**
     * Load persisted permissions into the registry. Must be called after
     * [discoverAll] to preserve user customizations. Acquires the mutex
     * to prevent concurrent modification with discovery.
     *
     * The permissions map may be keyed by either:
     * - Raw tool names (e.g., "bash") — from toolPermissions settings field
     * - Compound IDs (e.g., "builtin_bash") — from discoveredToolsJson
     * Both formats are handled. Each key is matched against BOTH ToolInfo.id
     * AND ToolInfo.name, applying to ALL matching tools. This handles the case
     * where two MCP servers expose tools with the same raw name.
     *
     * Only restores [ToolPermission] — the [ToolInfo.enabled] flag is left at
     * its discovery default (true). Use [loadEnabledAndPermissions] to restore both.
     */
    suspend fun loadPermissions(
        permissions: Map<String, ToolPermission>,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val current = snapshotRef().toMutableMap()
        for ((key, permission) in permissions) {
            // Match by compound id first, then by raw name — apply to ALL matches
            val matchingTools = current.values.filter { it.id == key || it.name == key }
            for (tool in matchingTools) {
                current[tool.id] = tool.copy(permission = permission)
            }
        }
        snapshotSetter(current.toMap())  // Immutable snapshot
    }

    /**
     * Load persisted enabled state AND permissions into the registry. Must be
     * called after discovery to preserve user customizations. Acquires the
     * mutex to prevent concurrent modification with discovery.
     *
     * The map keys follow the same matching rules as [loadPermissions] — both
     * raw names and compound IDs are matched against [ToolInfo.id] and [ToolInfo.name].
     *
     * This is the preferred method over [loadPermissions] when the caller has
     * both the enabled flag and the permission level, because it preserves the
     * user's enable/disable selections across re-discovery.
     */
    suspend fun loadEnabledAndPermissions(
        state: Map<String, Pair<Boolean, ToolPermission>>,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ) = toolsMutex.withLock {
        val current = snapshotRef().toMutableMap()
        for ((key, pair) in state) {
            val (enabled, permission) = pair
            val matchingTools = current.values.filter { it.id == key || it.name == key }
            for (tool in matchingTools) {
                current[tool.id] = tool.copy(enabled = enabled, permission = permission)
            }
        }
        snapshotSetter(current.toMap())  // Immutable snapshot
    }

    /**
     * Export tool permissions as a JSON string for settings persistence.
     * Format: {"toolId":{"enabled":true,"permission":"allow"}, ...}
     * Uses buildJsonObject to construct the structure manually since
     * Map<String, Map<String, Any>> has no built-in kotlinx.serialization serializer.
     */
    fun exportPermissionsJsonFromSnapshot(snapshot: Map<String, ToolInfo>): String {
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
     * Serialize an explicit permissions map to the settings-persistence JSON format.
     * Overload of [exportPermissionsJson] that accepts an explicit map rather than
     * reading the registry's internal snapshot — lets the panel delegate JSON
     * generation without first pushing state into the registry.
     *
     * Keys are raw tool names (matching the panel's [generateToolPermissionsJson]
     * convention). Values are pairs of (enabled, permission).
     *
     * Format: {"toolName":{"enabled":true,"permission":"allow"}, ...}
     */
    fun exportPermissionsJson(permissions: Map<String, Pair<Boolean, ToolPermission>>): String {
        val root = buildJsonObject {
            for ((toolName, pair) in permissions) {
                val (enabled, permission) = pair
                put(toolName, buildJsonObject {
                    put("enabled", enabled)
                    put("permission", permission.toActionString())
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
     *
     * WARNING: When two tools across different servers share the same raw name,
     * only one entry survives (last-write-wins). This is a known limitation of
     * the MCP config format, which keys permissions by raw tool name. The compound
     * [ToolInfo.id] field exists for internal disambiguation but cannot be used in
     * the config file format.
     *
     * LIMITATION: Because the OpenCode config format keys permissions by raw tool
     * name (not server-qualified), users CANNOT set different permissions for
     * same-named tools on different servers. The last tool processed wins, and the
     * user's per-server permission setting for earlier tools is silently discarded
     * (a warning is logged at L274-276, but the data loss itself is silent from the
     * user's perspective).
     *
     * RECOMMENDATION: This limitation should be surfaced in the settings UI
     * (OpenCodeMcpPanel) so users are aware that same-named tools across servers
     * share a single permission entry. If the OpenCode config format ever supports
     * server-qualified tool names, switch to using [ToolInfo.id] as the key to
     * enable per-server permissions.
     */
    fun exportPermissions(snapshot: Map<String, ToolInfo>): Map<String, ToolPermission> {
        val result = mutableMapOf<String, ToolPermission>()
        for (tool in snapshot.values) {
            val existing = result.put(tool.name, tool.permission)
            if (existing != null && existing != tool.permission) {
                permissionLogger.warn { "[ACP] ToolPermissionManager: duplicate tool name '${tool.name}' from servers '${tool.serverName}' overwrites previous permission ($existing → ${tool.permission})" }
            }
        }
        return result
    }
}