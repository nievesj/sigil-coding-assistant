package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.opencode.acp.chat.model.CommandHistoryEntry
import com.opencode.acp.chat.model.ProviderModel

/**
 * Persistent settings for the OpenCode plugin.
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. Kotlin data classes can fail to deserialize because the
 * compiler-generated constructor uses default parameters that XStream can't
 * invoke via reflection.
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml", roamingType = RoamingType.DISABLED)])
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {

    var binaryPath: String = ""
    var permissionTimeoutSeconds: Int = 60
    /** Favorite model keys in format "providerID/modelID" (slash-separated for XML safety). */
    var favoriteModels: java.util.ArrayList<String> = java.util.ArrayList()
    /** Inline code text color as "#RRGGBB" */
    var inlineCodeColor: String = "#6BBE50"
    /** List number color as "#RRGGBB" */
    var listNumberColor: String = "#6BBE50"
    /** Last selected model key in format "providerID/modelID". */
    var lastSelectedModelKey: String = ""
    /** Whether the session sidebar is visible. Persisted across tool window reopens. */
    var sidebarVisible: Boolean = true
    /** Maximum number of entries kept in the input command history. */
    var commandHistorySize: Int = 15
    /** Maximum time (seconds) to wait for a response from the LLM before timing out.
     *  Controls the `withTimeout` on `deferred.await()` in `sendMessageInternal()`.
     *  Default 300 (5 minutes). The previous `sseSocketTimeoutSeconds` was misleading —
     *  `socketTimeoutMillis` is a no-op on the Java HTTP engine (see TDD §4.2, §7.1). */
    var responseTimeoutSeconds: Int = 300
    /** Buffer time (seconds) added to responseTimeoutSeconds for LONG-profile HTTP calls
     *  (e.g., executeCommand, compactSession). Accounts for server-side overhead beyond
     *  LLM generation time: request queuing, tool execution, network latency, and
     *  bookkeeping. Default 30, minimum 10. */
    var longTimeoutBufferSeconds: Int = 30
    /** @deprecated Migrated to [responseTimeoutSeconds]. Kept for XStream backward compatibility.
     *  Can be removed once all users have migrated (i.e., after 2+ release cycles).
     *  The migration logic in loadState() handles the transition from old to new setting. */
    @Deprecated("Migrated to responseTimeoutSeconds", ReplaceWith("responseTimeoutSeconds"))
    var sseSocketTimeoutSeconds: Int = 60
    /** Whether to automatically connect when the plugin opens. */
    var autoConnect: Boolean = true
    /** Port for the OpenCode server (default 4096). */
    var port: Int = 4096
    /** Whether to load all sessions at once (bypasses pagination). Shows performance warning. */
    var loadAllSessions: Boolean = false
    /** Persisted input command history (most recent first). Trimmed to [commandHistorySize] on save. */
    var commandHistory: java.util.ArrayList<CommandHistoryEntry> = java.util.ArrayList()
    /**
     * Tool kinds that default to expanded in the chat.
     * Stored as a comma-separated string of ToolKind names for XStream compatibility.
     * Default: EXECUTE,EDIT,READ,THINK
     */
    var expandedToolKinds: String = "EXECUTE,EDIT,READ,THINK"
    /** Whether task/subtask pills default to expanded in the chat. */
    var expandTaskPillsByDefault: Boolean = false
    /**
     * Whether to queue messages instead of steering (aborting) when sending during streaming.
     * true = queue mode: messages wait for the current response to complete, then auto-send.
     * false = steer mode: messages abort the current response and send immediately (legacy behavior).
     */
    var queueInsteadOfSteer: Boolean = true

    // ── MCP integration ────────────────────────────────────────────────
    /** Whether to enable IntelliJ MCP server integration. */
    var enableIntellijMcp: Boolean = false
    /** IntelliJ MCP server SSE URL. Copy from Settings → Tools → MCP Server → "Copy SSE Config". */
    var mcpServerUrl: String = ""
    /**
     * Additional MCP servers as JSON array: [{"name":"github","url":"http://127.0.0.1:8080/sse"}].
     * Stored as JSON string for XStream serialization compatibility.
     */
    var additionalMcpServers: String = ""

    // ── Tool Permissions ──────────────────────────────────────────────
    /**
     * Tool permission states as JSON string.
     * Format: {"toolName":{"enabled":true,"permission":"allow"},...}
     * Stored as JSON string for XStream serialization compatibility.
     */
    var toolPermissions: String = ""

    /**
     * Discovered tools cache as JSON string.
     * Format: [{"name":"bash","description":"...","source":"builtin","serverName":"builtin"},...]
     * Allows showing previously discovered tools without re-discovery.
     */
    var discoveredToolsJson: String = ""

    /** Whether to show a confirmation dialog before disconnecting from the server. */
    var showDisconnectConfirmation: Boolean = true

    // ── Follow Agent ──────────────────────────────────────────────────
    /**
     * Whether Follow Agent is enabled (auto-opens files on tool calls).
     * Default OFF — the feature is opt-in because it opens files in the
     * editor on every read tool call, which can be jarring for users who
     * have not opted in.
     */
    var followAgentEnabled: Boolean = false
    /** Highlight color for READ tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followReadColor: String = "#5078C888"
    /** Highlight color for EDIT tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followEditColor: String = "#50A05088"
    /** Highlight color for SEARCH tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followSearchColor: String = "#C8B43C88"
    /** Highlight color for EXECUTE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followExecuteColor: String = "#B4785088"
    /** Highlight color for DELETE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followDeleteColor: String = "#C8505088"
    /** Highlight color for MOVE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followMoveColor: String = "#A050C888"
    /** Highlight color for FETCH tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followFetchColor: String = "#50A0C888"
    /** Highlight color for OTHER tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followOtherColor: String = "#80808088"

    /**
     * Returns the persisted hex color for a [com.agentclientprotocol.model.ToolKind].
     * THINK and SWITCH_MODE have no persisted color and fall back to OTHER.
     */
    fun getFollowColor(kind: com.agentclientprotocol.model.ToolKind): String = when (kind) {
        com.agentclientprotocol.model.ToolKind.READ -> followReadColor
        com.agentclientprotocol.model.ToolKind.EDIT -> followEditColor
        com.agentclientprotocol.model.ToolKind.SEARCH -> followSearchColor
        com.agentclientprotocol.model.ToolKind.EXECUTE -> followExecuteColor
        com.agentclientprotocol.model.ToolKind.DELETE -> followDeleteColor
        com.agentclientprotocol.model.ToolKind.MOVE -> followMoveColor
        com.agentclientprotocol.model.ToolKind.FETCH -> followFetchColor
        com.agentclientprotocol.model.ToolKind.THINK,
        com.agentclientprotocol.model.ToolKind.SWITCH_MODE,
        com.agentclientprotocol.model.ToolKind.OTHER -> followOtherColor
    }

    /**
     * Persists the hex color for a [com.agentclientprotocol.model.ToolKind].
     * THINK and SWITCH_MODE have no persisted color — no-op.
     */
    fun setFollowColor(kind: com.agentclientprotocol.model.ToolKind, hex: String) {
        when (kind) {
            com.agentclientprotocol.model.ToolKind.READ -> followReadColor = hex
            com.agentclientprotocol.model.ToolKind.EDIT -> followEditColor = hex
            com.agentclientprotocol.model.ToolKind.SEARCH -> followSearchColor = hex
            com.agentclientprotocol.model.ToolKind.EXECUTE -> followExecuteColor = hex
            com.agentclientprotocol.model.ToolKind.DELETE -> followDeleteColor = hex
            com.agentclientprotocol.model.ToolKind.MOVE -> followMoveColor = hex
            com.agentclientprotocol.model.ToolKind.FETCH -> followFetchColor = hex
            com.agentclientprotocol.model.ToolKind.OTHER -> followOtherColor = hex
            com.agentclientprotocol.model.ToolKind.THINK,
            com.agentclientprotocol.model.ToolKind.SWITCH_MODE -> { /* no-op */ }
        }
    }

    /** Returns true if the given ToolKind should default to expanded. */
    fun isToolKindDefaultExpanded(kind: com.agentclientprotocol.model.ToolKind): Boolean {
        val expanded = expandedToolKinds.split(",").map { it.trim() }.toSet()
        return kind.name in expanded
    }

    /** Toggles a ToolKind in the expanded set. */
    fun toggleToolKindDefaultExpanded(kind: com.agentclientprotocol.model.ToolKind) {
        val expanded = expandedToolKinds.split(",").map { it.trim() }.toMutableSet()
        if (kind.name in expanded) expanded.remove(kind.name) else expanded.add(kind.name)
        expandedToolKinds = expanded.sorted().joinToString(",")
    }

    override fun getState(): OpenCodeSettingsState {
        return this
    }

    override fun loadState(state: OpenCodeSettingsState) {
        binaryPath = state.binaryPath
        permissionTimeoutSeconds = state.permissionTimeoutSeconds
        favoriteModels = state.favoriteModels
        inlineCodeColor = state.inlineCodeColor
        listNumberColor = state.listNumberColor
        lastSelectedModelKey = state.lastSelectedModelKey
        sidebarVisible = state.sidebarVisible
        commandHistorySize = if (state.commandHistorySize > 0) state.commandHistorySize else 15
        // Migrate legacy sseSocketTimeoutSeconds → responseTimeoutSeconds.
        // If responseTimeoutSeconds was explicitly set (not default 300), keep it.
        // Otherwise, if sseSocketTimeoutSeconds was customized (not default 60), use that
        // as the new responseTimeoutSeconds (coerced to minimum 60s for safety).
        // If neither was customized, use default 300.
        @Suppress("DEPRECATION")
        responseTimeoutSeconds = when {
            state.responseTimeoutSeconds != 300 -> state.responseTimeoutSeconds.coerceAtLeast(60)
            state.sseSocketTimeoutSeconds != 60 -> state.sseSocketTimeoutSeconds.coerceAtLeast(60)
            else -> 300
        }
        longTimeoutBufferSeconds = state.longTimeoutBufferSeconds.coerceAtLeast(10)
        @Suppress("DEPRECATION")
        sseSocketTimeoutSeconds = state.sseSocketTimeoutSeconds
        autoConnect = state.autoConnect
        port = if (state.port in 1024..65535) state.port else 4096
        loadAllSessions = state.loadAllSessions
        commandHistory = state.commandHistory
        expandedToolKinds = state.expandedToolKinds.ifBlank { "EXECUTE,EDIT,READ,THINK" }
        expandTaskPillsByDefault = state.expandTaskPillsByDefault
        queueInsteadOfSteer = state.queueInsteadOfSteer
        enableIntellijMcp = state.enableIntellijMcp
        mcpServerUrl = state.mcpServerUrl
        additionalMcpServers = state.additionalMcpServers
        toolPermissions = state.toolPermissions
        discoveredToolsJson = state.discoveredToolsJson
        followAgentEnabled = state.followAgentEnabled
        followReadColor = state.followReadColor
        followEditColor = state.followEditColor
        followSearchColor = state.followSearchColor
        followExecuteColor = state.followExecuteColor
        followDeleteColor = state.followDeleteColor
        followMoveColor = state.followMoveColor
        followFetchColor = state.followFetchColor
        followOtherColor = state.followOtherColor
        showDisconnectConfirmation = state.showDisconnectConfirmation
    }

    companion object {
        fun getInstance(): OpenCodeSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)

        /** Uses slash to separate provider and model IDs (safe for XML, unlike \x1F). */
        fun modelKey(providerID: String, modelID: String) = "$providerID/$modelID"
    }

    fun isFavoriteModel(providerID: String, modelID: String): Boolean {
        val key = modelKey(providerID, modelID)
        return favoriteModels.contains(key)
    }

    fun toggleFavoriteModel(providerID: String, modelID: String) {
        val key = modelKey(providerID, modelID)
        if (favoriteModels.contains(key)) {
            favoriteModels.remove(key)
        } else {
            favoriteModels.add(key)
        }
    }

    /** Remove stale favorites for models that no longer exist. Only runs when models are loaded. */
    fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
        val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
        favoriteModels.removeAll { it !in validKeys }
    }
}