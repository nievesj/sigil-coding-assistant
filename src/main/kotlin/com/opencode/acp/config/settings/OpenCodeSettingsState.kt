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
    /** Last selected agent ID. Persisted across IDE restarts. */
    var lastSelectedAgent: String = ""
    /** Last selected thinking effort name (ThinkingEffort enum name). Persisted across IDE restarts. */
    var lastSelectedThinkingEffort: String = ""
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
    /** Maximum time (seconds) a single tool call can run with no SSE activity before
     *  being considered stuck. This is a safety net for lost ToolResult events — if a
     *  tool's result is never received (e.g., child session crash, SSE event lost during
     *  reconnect), the tool stays InProgress forever and blocks the activity monitor's
     *  normal timeout. This ceiling fires regardless of hasRunningTools.
     *  Default 300 (5 minutes). Range: 60-3600. */
    var toolStuckTimeoutSeconds: Int = 300
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
    /** Persisted input command history (most recent first). Trimmed to [commandHistorySize] on save.
     *  THREAD SAFETY: Write operations (recordCommand, clearCommandHistory) replace the ArrayList
     *  reference atomically via `settings.commandHistory = ArrayList(...)`. XStream serialization
     *  (via getState()) reads the current reference — it always sees a consistent list (old or new),
     *  never a partially-constructed one, because JVM reference assignment is atomic. Reads from
     *  Compose UI observe the StateFlow, which is thread-safe. Do NOT mutate the list in-place
     *  from background threads — always replace the entire reference. */
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
     * Permissions saved before Disable All, so Enable All can restore ASK
     * settings that were active before the disable. Prevents ASK→ALLOW
     * promotion on a disable→enable cycle across IDE restarts.
     * Format: {"toolId":"allow",...} (tool ID → permission action string).
     * Empty when no Disable All has been performed.
     */
    var savedToolPermissionsBeforeDisable: String = ""

    /**
     * Discovered tools cache as JSON string.
     * Format: [{"name":"bash","description":"...","source":"builtin","serverName":"builtin"},...]
     * Allows showing previously discovered tools without re-discovery.
     */
    var discoveredToolsJson: String = ""

    /** Whether to show a confirmation dialog before disconnecting from the server. */
    var showDisconnectConfirmation: Boolean = true

    // ── Context & Compaction settings (Tools → Sigil → Context) ─────────────
    /** When on, tool results exceeding the char limit are truncated at insertion time. */
    var truncateToolOutput: Boolean = false
    /** Max chars per tool output before truncation. Clamped to 10_000..200_000 on set. */
    var toolOutputCharLimit: Int = 50_000
    /** When on, repeated reads of unchanged files emit [unchanged] instead of re-emitting content. */
    var detectDuplicateReads: Boolean = false
    /** When on, pre-computes compaction summaries in the background for instant swap.
     *  OFF by default — the server's /summarize endpoint performs ACTUAL compaction
     *  (not a preview), so auto-triggering it compacts the session immediately.
     *  Retained as a setting in case a preview API is added in the future. */
    var enableBackgroundCompaction: Boolean = false
    /** Context usage % at which background checkpointing starts. Clamped to 40f..80f on set. */
    var checkpointThresholdPercent: Float = 60f
    /** Context usage % at which pre-computed summary is ready for instant swap. Clamped to 60f..95f on set. */
    var swapThresholdPercent: Float = 80f
    /** Show the 5-category proportional bar in Context tab. */
    var showContextBreakdown: Boolean = true
    /** When to show pressure warnings on the context indicator. One of NEVER / ELEVATED / HIGH / CRITICAL. */
    var pressureNotificationThreshold: String = "HIGH"
    /** Ask for confirmation before triggering manual compaction. */
    var compactConfirmation: Boolean = true

    // ── Context Pruner settings (Tools → Sigil → Context) ─────────────
    /** When on, the sigil-pruner.ts plugin is extracted and loaded by the OpenCode server.
     *  Performs server-side deterministic pruning (dedup, old tool output pruning, errored
     *  tool input pruning) and LLM-driven compression via a compress tool. */
    var enableContextPruner: Boolean = false
    /** Prune tool outputs older than N messages. Clamped to 5..100 on set. */
    var prunerMaxToolOutputMessages: Int = 20
    /** Prune errored tool inputs after N turns. Clamped to 1..20 on set. */
    var prunerErroredToolTurns: Int = 4
    /** Enable LLM-driven compression (compress tool). */
    var prunerCompressEnabled: Boolean = true
    /** Compression mode: "range" or "message". Whitelisted on set. */
    var prunerCompressMode: String = "range"

    // ── Context Pruner: Nudge settings ────────────────────────────────
    /** When on, injects a system reminder prompting the model to call the compress
     *  tool when context usage exceeds the threshold. Two levels: gentle (threshold)
     *  and urgent (urgentPercent). Cooldown prevents nagging every turn. */
    var prunerNudgeEnabled: Boolean = true
    /** Gentle nudge threshold (% of context limit). Clamped to 30..90 on set. */
    var prunerNudgeThresholdPercent: Int = 60
    /** Urgent nudge threshold (% of context limit). Clamped to 50..99 on set. */
    var prunerNudgeUrgentPercent: Int = 80
    /** Minimum turns between nudges. Clamped to 1..10 on set. */
    var prunerNudgeCooldownTurns: Int = 3
    /** Fallback context limit when the model object doesn't expose one. Clamped to 1000..2_000_000 on set. */
    var prunerDefaultContextLimit: Int = 128000

    /** Target FPS for throttled infinite animations (glow, pulse, shimmer).
     *  Lower values reduce GPU command flush pressure (DirectContextKt._nFlushAndSubmit stalls)
     *  by generating fewer Skiko frames per second. 60 = full vsync (original behavior),
     *  30 = half pressure (default, visually identical for slow animations), 15 = quarter pressure.
     *  Clamped to 15..60 on load. */
    var animationThrottleFps: Int = 30

    /** Plugin log level for idea.log. One of OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL.
     *  Default INFO — shows startup/connection/error logs. Set DEBUG/ALL for troubleshooting.
     *  Applied at startup via [com.opencode.acp.config.settings.StartupLogConfigListener]
     *  and on settings Apply via [OpenCodeSettingsConfigurable]. */
    var logLevel: String = "INFO"

    // ── Follow Agent ──────────────────────────────────────────────────
    /**
     * Whether Follow Agent is enabled (auto-opens files on tool calls).
     * Default OFF — the feature is opt-in because it opens files in the
     * editor on every read tool call, which can be jarring for users who
     * have not opted in.
     */
    var followAgentEnabled: Boolean = false
    /**
     * When Follow Agent is enabled, also show agent-executed commands in a read-only
     * console in the Run tool window. Default true — if the user opted into Follow Agent,
     * they want to see command output too.
     */
    var followCommandsInConsole: Boolean = true

    /**
     * When Follow Agent is enabled, also open IntelliJ's native Find in Files when the
     * agent performs a search. Default true — gives the user an interactive result set
     * they can navigate, filter, and group.
     */
    var followSearchesInFindWindow: Boolean = true
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
        // Copy ArrayLists to avoid shared references — if the persistence framework
        // reuses the state parameter, mutations on the loaded instance would affect it.
        favoriteModels = java.util.ArrayList(state.favoriteModels)
        inlineCodeColor = state.inlineCodeColor
        listNumberColor = state.listNumberColor
        lastSelectedModelKey = state.lastSelectedModelKey
        sidebarVisible = state.sidebarVisible
        commandHistorySize = state.commandHistorySize.coerceIn(1, 100)
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
        toolStuckTimeoutSeconds = state.toolStuckTimeoutSeconds.coerceIn(60, 3600)
        // Clear deprecated field after migration to prevent it from being
        // re-persisted to XML on every IDE restart (reduces settings churn).
        @Suppress("DEPRECATION")
        sseSocketTimeoutSeconds = 0
        autoConnect = state.autoConnect
        port = if (state.port in 1024..65535) state.port else {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn { "[ACP] Invalid port ${state.port} in settings, resetting to 4096" }
            4096
        }
        loadAllSessions = state.loadAllSessions
        commandHistory = java.util.ArrayList(state.commandHistory)
        val validKinds = com.agentclientprotocol.model.ToolKind.entries.map { it.name }.toSet()
        expandedToolKinds = state.expandedToolKinds.split(',')
            .map { it.trim() }
            .filter { it in validKinds }
            .ifEmpty { listOf("EXECUTE", "EDIT", "READ", "THINK") }
            .joinToString(",")
        expandTaskPillsByDefault = state.expandTaskPillsByDefault
        queueInsteadOfSteer = state.queueInsteadOfSteer
        enableIntellijMcp = state.enableIntellijMcp
        mcpServerUrl = state.mcpServerUrl
        additionalMcpServers = try {
            if (state.additionalMcpServers.isNotBlank()) {
                kotlinx.serialization.json.Json.parseToJsonElement(state.additionalMcpServers)
                state.additionalMcpServers
            } else ""
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn(e) { "[ACP] Invalid additionalMcpServers in settings, clearing" }
            ""
        }
        toolPermissions = state.toolPermissions
        savedToolPermissionsBeforeDisable = state.savedToolPermissionsBeforeDisable
        discoveredToolsJson = state.discoveredToolsJson
        followAgentEnabled = state.followAgentEnabled
        followCommandsInConsole = state.followCommandsInConsole
        followReadColor = state.followReadColor
        followEditColor = state.followEditColor
        followSearchColor = state.followSearchColor
        followExecuteColor = state.followExecuteColor
        followDeleteColor = state.followDeleteColor
        followMoveColor = state.followMoveColor
        followFetchColor = state.followFetchColor
        followOtherColor = state.followOtherColor
        followSearchesInFindWindow = state.followSearchesInFindWindow
        showDisconnectConfirmation = state.showDisconnectConfirmation
        // Context & Compaction settings (with clamping for corrupt/out-of-range values)
        truncateToolOutput = state.truncateToolOutput
        toolOutputCharLimit = state.toolOutputCharLimit.coerceIn(10_000, 200_000)
        detectDuplicateReads = state.detectDuplicateReads
        enableBackgroundCompaction = state.enableBackgroundCompaction
        checkpointThresholdPercent = state.checkpointThresholdPercent.coerceIn(40f, 80f)
        swapThresholdPercent = state.swapThresholdPercent.coerceIn(60f, 95f)
        showContextBreakdown = state.showContextBreakdown
        pressureNotificationThreshold = when (state.pressureNotificationThreshold) {
            "NEVER", "ELEVATED", "HIGH", "CRITICAL" -> state.pressureNotificationThreshold
            else -> "HIGH"
        }
        compactConfirmation = state.compactConfirmation
        // Context Pruner settings (with clamping for corrupt/out-of-range values)
        enableContextPruner = state.enableContextPruner
        prunerMaxToolOutputMessages = state.prunerMaxToolOutputMessages.coerceIn(5, 100)
        prunerErroredToolTurns = state.prunerErroredToolTurns.coerceIn(1, 20)
        prunerCompressEnabled = state.prunerCompressEnabled
        prunerCompressMode = state.prunerCompressMode.takeIf { it in listOf("range", "message") } ?: "range"
        prunerNudgeEnabled = state.prunerNudgeEnabled
        prunerNudgeThresholdPercent = state.prunerNudgeThresholdPercent.coerceIn(30, 90)
        prunerNudgeUrgentPercent = state.prunerNudgeUrgentPercent.coerceIn(50, 99)
        prunerNudgeCooldownTurns = state.prunerNudgeCooldownTurns.coerceIn(1, 10)
        prunerDefaultContextLimit = state.prunerDefaultContextLimit.coerceIn(1000, 2_000_000)
        animationThrottleFps = state.animationThrottleFps.coerceIn(15, 60)
        logLevel = AcpLogLevel.fromName(state.logLevel).name
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
        // Replace the ArrayList reference atomically (per project convention for
        // commandHistory) — never mutate in-place to avoid XStream serialization races.
        favoriteModels = java.util.ArrayList(favoriteModels).apply {
            if (contains(key)) remove(key) else add(key)
        }
    }

    /** Remove stale favorites for models that no longer exist. Only runs when models are loaded. */
    fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
        val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
        favoriteModels = java.util.ArrayList(favoriteModels.filter { it in validKeys })
    }

    /**
     * Reorder a favorite by moving it from [fromIndex] to [toIndex] within [favoriteModels].
     * No-op if indices are equal or out of bounds. The list reference is replaced atomically; the
     * PersistentStateComponent persists the new order on IDE state flush.
     */
    fun reorderFavoriteModel(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in favoriteModels.indices) return
        // Replace the ArrayList reference atomically — never mutate in-place.
        favoriteModels = java.util.ArrayList(favoriteModels).apply {
            val key = removeAt(fromIndex)
            val clamped = toIndex.coerceIn(0, size)
            add(clamped, key)
        }
    }

    /**
     * Move a favorite identified by [key] to [toIndex] within [favoriteModels].
     * Used by the search-filtered drag path where visible indices differ from
     * persisted indices. No-op if the key is not found.
     */
    fun moveFavoriteToIndex(key: String, toIndex: Int) {
        val fromIndex = favoriteModels.indexOf(key)
        if (fromIndex < 0) return
        reorderFavoriteModel(fromIndex, toIndex)
    }
}