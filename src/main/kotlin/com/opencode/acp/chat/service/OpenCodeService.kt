package com.opencode.acp.chat.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.chat.util.normalizeAttachmentMime
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.mcp.McpConfigWriter
import com.opencode.acp.mcp.McpConnectionStatus
import com.opencode.acp.mcp.McpManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level service that owns the OpenCode connection, session management,
 * and message processor. Survives tool window disposal/recreation.
 *
 * Architecture:
 * - [ProcessManager] — server process lifecycle and connection state
 * - [SessionManager] — per-session state ownership, SSE routing, session lifecycle
 * - This service coordinates between them and exposes the public API
 *
 * The [ChatViewModel] is a thin UI wrapper that delegates here.
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
        logger.error(throwable) { "[ACP] Uncaught coroutine exception in OpenCodeService scope" }
        // Emit an error signal so the UI can surface "internal error" instead of
        // silently hanging. SessionManager._globalSignals (the flow the ViewModel
        // collects for cross-session errors) is private with no public/internal
        // emitter, and emitSessionSignal routes to a different flow (_allSessionSignals)
        // that only handles StreamingCompleted. Routing through it would not reach
        // the UI error display, so we rely on logging alone — the critical fix that
        // ensures exceptions are visible in idea.log with full stack traces instead
        // of being swallowed by the JVM default handler.
    })

    // ── Sub-components ─────────────────────────────────────────────────────

    val connectionManager = ProcessManager(scope).apply {
        onMcpReset = { resetMcpOnServerRestart() }
    }
    val sessionManager = SessionManager(scope, project)
    val commandManager = CommandManager(
        clientProvider = { connectionManager.client },
        sessionIdProvider = { sessionManager.activeSessionId.value }
    )
    val permissionManager = PermissionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager
    )

    /** ToolRegistry singleton, created during initializeMcp(). Settings panel reads this. */
    var toolRegistry: com.opencode.acp.mcp.ToolRegistry? = null
        private set

    // ── State flows ────────────────────────────────────────────────────────

    val messages: StateFlow<Map<String, ChatMessage>> = sessionManager.activeMessages
    val signals: SharedFlow<UiSignal> = sessionManager.activeSignals
    val globalSignals: SharedFlow<UiSignal> = sessionManager.globalSignals
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val connectionErrorReason: StateFlow<ConnectionErrorReason?> = connectionManager.connectionErrorReason
    val sessionListState: StateFlow<SessionListState> = sessionManager.sessionListState
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = sessionManager.childSessionMap
    val todoItems: StateFlow<List<TodoItem>> = sessionManager.todoItems
    val sessionContextState: StateFlow<SessionContextState> = sessionManager.sessionContextState
    val streamingSessionIds: StateFlow<Set<String>> = sessionManager.streamingSessionIds
    val pendingCreationSessionIds: StateFlow<Set<String>> = sessionManager.pendingCreationSessionIds
    val sessionCachedFlow: kotlinx.coroutines.flow.SharedFlow<String> = sessionManager.sessionCachedFlow

    // ── Internal state ─────────────────────────────────────────────────────

    private var signalCollectionJob: Job? = null
    internal var mcpManager: McpManager? = null

    /**
     * Serializes sendMessage() calls.
     */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** Session ID convenience accessor. */
    val sessionId: String? get() = sessionManager.activeSessionId.value

    // ── Global signal collection (completes responseDeferred on background session Stop) ──

    private fun startGlobalSignalCollection() {
        signalCollectionJob?.cancel()
        signalCollectionJob = scope.launch {
            sessionManager.allSessionSignals.collect { (sessionId, signal) ->
                when (signal) {
                    is UiSignal.StreamingCompleted -> {
                        val session = sessionManager.getSession(sessionId)
                        session?.responseDeferred?.complete(Unit)
                        session?.responseDeferred = null
                    }
                    else -> { /* other signals handled by ViewModel via activeSignals */ }
                }
            }
        }
    }

    // ── Initialization ─────────────────────────────────────────────────────

    /**
     * Initialize the connection and load sessions.
     * Called once when the service is first accessed.
     *
     * @param projectBasePath the IDE project root directory.  Passed as
     *   `?directory=` to the server for session filtering.  `null` = no
     *   filter (server returns all sessions).  The connection manager
     *   receives a non-null CWD for ProcessBuilder (falls back to ".").
     */
    suspend fun initialize(projectBasePath: String? = null): Boolean {
        logger.info { "[ACP] OpenCodeService.initialize: START (projectBasePath=$projectBasePath)" }
        val connectionPath = projectBasePath ?: "."
        val connected = connectionManager.initialize(connectionPath)
        if (!connected) {
            logger.warn { "[ACP] OpenCodeService.initialize: connectionManager.initialize() returned false" }
            return false
        }
        logger.info { "[ACP] OpenCodeService.initialize: connection established" }

        sessionManager.client = connectionManager.client
        sessionManager.projectBasePath = projectBasePath
        startGlobalSignalCollection()

        // Start global SSE subscription — routes events to correct SessionState by sessionId
        startGlobalSseSubscription()

        logger.info { "[ACP] OpenCodeService.initialize: loading sessions..." }
        sessionManager.resetDisplayLimit()
        sessionManager.loadSessions()
        logger.info { "[ACP] OpenCodeService.initialize: sessions state = ${sessionListState.value::class.simpleName}" }

        when (val state = sessionListState.value) {
            is SessionListState.Loaded -> {
                if (state.sessions.isNotEmpty()) {
                    sessionManager.switchSession(state.sessions.first().id)
                }
            }
            is SessionListState.Error -> {
                // Retry without directory filter — the filter may have caused the error
                // (e.g., server doesn't support ?directory= or path mismatch).
                sessionManager.loadSessions(directory = null)
                val retry = sessionListState.value as? SessionListState.Loaded
                if (retry != null && retry.sessions.isNotEmpty()) {
                    sessionManager.switchSession(retry.sessions.first().id)
                }
            }
            is SessionListState.Loading -> { }
        }

        // ── Initialize MCP integration ──────────────────────────────────────
        initializeMcp()

        return true
    }

    // ── MCP integration ────────────────────────────────────────────────────

    /** Initialize MCP manager and register all enabled servers. */
    private suspend fun initializeMcp() {
        try {
            val settings = OpenCodeSettingsState.getInstance()
            val client = connectionManager.client
            if (client == null) {
                logger.warn { "[ACP] MCP: skipping initialization — no client available" }
                return
            }
            mcpManager = McpManager(client, settings, scope, client.mcpHttpClient)
            val configs = mcpManager!!.resolveConfigs()
            if (configs.isEmpty()) {
                logger.info { "[ACP] MCP: no servers configured (enableIntellijMcp=${settings.enableIntellijMcp}, additionalMcpServersLen=${settings.additionalMcpServers.length})" }
                return
            }
            logger.info { "[ACP] MCP: initializing ${configs.size} server(s): ${configs.map { it.name }}" }
            mcpManager!!.initialize()

            // Wire ToolRegistry after MCP is initialized (McpManager has server URLs)
            toolRegistry = com.opencode.acp.mcp.ToolRegistry(
                httpClient = client.mcpHttpClient,
                mcpToolDiscovery = com.opencode.acp.mcp.McpToolDiscovery(client.mcpHttpClient)
            )
            logger.info { "[ACP] ToolRegistry created" }

            // Auto-discover tools in the background so the settings panel has
            // real tool data when opened. MCP servers may not have been ready
            // during the first initialize() call (JetBrains MCP Server starts
            // asynchronously), so this also serves as a retry.
            discoverToolsInBackground()
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] MCP initialization failed — chat will work without MCP" }
        }
    }

    /**
     * Disconnect all MCP servers. Called before re-initialization when settings change.
     * Also called when the user toggles MCP off entirely.
     */
    suspend fun disconnectAllMcp() {
        mcpManager?.let { mgr ->
            val statuses = mgr.serverStatuses.value
            for (name in statuses.keys) {
                mgr.disconnect(name)
            }
        }
    }

    /**
     * Re-initialize MCP with current settings. Called after settings change.
     * Creates a fresh McpManager and runs discovery/registration.
     */
    suspend fun reinitializeMcp() {
        // Disconnect the existing manager before replacing it to avoid leaking
        // SSE connections, HTTP clients, and in-flight registrations.
        disconnectAllMcp()
        initializeMcp()
    }

    /**
     * Discover tools from OpenCode and all connected MCP servers in the background.
     * Called after MCP initialization (both initial and re-init) to populate the
     * ToolRegistry with real tool data. Also loads persisted permissions so the
     * settings panel shows correct enabled/permission state when opened.
     *
     * This runs on the service scope — callers don't need to wait for it.
     * If MCP servers aren't connected yet (e.g., JetBrains MCP Server still
     * starting), discovery will find only built-in tools. The user can click
     * "Discover Tools" in settings to retry once the server is ready.
     */
    private fun discoverToolsInBackground() {
        val registry = toolRegistry ?: return
        val client = connectionManager.client ?: return
        val mcpMgr = mcpManager ?: return
        scope.launch {
            try {
                val baseUrl = "http://127.0.0.1:${connectionManager.port}"
                val mcpUrls = mcpMgr.getServerUrls()
                logger.info { "[ACP] discoverToolsInBackground: starting discovery (${mcpUrls.size} MCP servers connected)" }
                val tools = registry.discoverAll(baseUrl, mcpUrls)
                logger.info { "[ACP] discoverToolsInBackground: discovered ${tools.size} tools" }

                // Load persisted permissions so the registry has the user's
                // saved enabled/permission state. The settings panel reads
                // from the registry when opened.
                val settings = OpenCodeSettingsState.getInstance()
                if (settings.toolPermissions.isNotBlank()) {
                    val persisted = parsePersistedToolPermissions(settings.toolPermissions)
                    if (persisted.isNotEmpty()) {
                        registry.loadEnabledAndPermissions(persisted.mapValues { (_, pair) ->
                            Pair(pair.first, com.opencode.acp.mcp.ToolPermission.fromActionString(pair.second))
                        })
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] discoverToolsInBackground: failed" }
            }
        }
    }

    /**
     * Parse persisted tool permissions JSON into a map of toolName → (enabled, permission).
     * Internal so ChatViewModel can delegate to it instead of duplicating the logic.
     */
    internal fun parsePersistedToolPermissions(perms: String): Map<String, Pair<Boolean, String>> {
        if (perms.isBlank()) return emptyMap()
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(perms).jsonObject
            obj.entries.associate { (toolName, element) ->
                val toolObj = element.jsonObject
                val enabled = toolObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val permission = toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow"
                toolName to Pair(enabled, permission)
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to parse persisted tool permissions — clearing corrupted value" }
            try {
                OpenCodeSettingsState.getInstance().toolPermissions = ""
            } catch (_: Exception) { /* don't let clearing fail the parse call */ }
            emptyMap()
        }
    }

    /**
     * Non-suspend wrapper for settings panel (runs on EDT).
     * Launches disconnect + config write + reinitialize on the service scope.
     */
    fun reinitializeMcpFromSettings() {
        scope.launch {
            try {
                disconnectAllMcp()
                val settings = OpenCodeSettingsState.getInstance()
                // Write MCP config file — OpenCode reads it on next startup.
                // For immediate effect (without restart), POST /mcp is also called
                // by McpManager.initialize() below.
                val projectPath = project.basePath?.let { java.nio.file.Path.of(it) }
                    ?: java.nio.file.Path.of(".")
                val configWriter = McpConfigWriter(projectPath, settings)
                if (settings.enableIntellijMcp || settings.additionalMcpServers.isNotBlank()) {
                    configWriter.write()
                    reinitializeMcp()
                } else {
                    configWriter.clearAllEntries()
                }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] Failed to reinitialize MCP from settings" }
            }
        }
    }

    /** Reset MCP state on OpenCode server restart. Called by ProcessManager. */
    fun resetMcpOnServerRestart() {
        scope.launch {
            try {
                mcpManager?.resetOnServerRestart()
                // Re-write config file in case it was stale, then re-register via POST /mcp
                val settings = OpenCodeSettingsState.getInstance()
                val projectPath = project.basePath?.let { java.nio.file.Path.of(it) }
                    ?: java.nio.file.Path.of(".")
                val configWriter = McpConfigWriter(projectPath, settings)
                if (settings.enableIntellijMcp || settings.additionalMcpServers.isNotBlank()) {
                    configWriter.write()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] resetMcpOnServerRestart failed" }
            }
        }
    }

    private val emptyMcpStatuses = MutableStateFlow<Map<String, McpConnectionStatus>>(emptyMap())

    /** MCP server connection statuses (empty if MCP not initialized). */
    val mcpServerStatuses: StateFlow<Map<String, McpConnectionStatus>>
        get() = mcpManager?.serverStatuses ?: emptyMcpStatuses.asStateFlow()

    // ── Session management (delegated) ─────────────────────────────────────

    suspend fun loadSessions() = sessionManager.loadSessions()
    fun loadMoreSessions() = sessionManager.loadMoreSessions()
    suspend fun switchSession(sessionId: String) = sessionManager.switchSession(sessionId)
    suspend fun createAndSwitchSession(title: String? = null) = sessionManager.createAndSwitchSession(title)
    suspend fun archiveSession(sessionId: String) = sessionManager.archiveSession(sessionId)
    suspend fun clearAllSessions() = sessionManager.clearAllSessions()

    // ── Streaming session tracking (sidebar shimmer) ─────────────────────────

    /** Imperatively add a session ID to the streaming set (activates sidebar shimmer).
     *  Used by ChatViewModel.sendMessage() before the suspend call so the shimmer
     *  appears immediately on send. Idempotent. */
    fun addStreamingSession(sessionId: String) = sessionManager.addStreamingSession(sessionId)

    /** Imperatively remove a session ID from the streaming set (deactivates sidebar shimmer).
     *  Used by ChatViewModel on cancel/switch/error. Idempotent. */
    fun removeStreamingSession(sessionId: String) = sessionManager.removeStreamingSession(sessionId)

    // ── Connection stop ─────────────────────────────────────────────────────

    /** Stop all connection activity: cancel SSE reconnection loop, cancel SSE
     *  subscription, and disconnect the HTTP client. Called by the "Stop" button
     *  on the splash screen when the user wants to abort reconnection. */
    fun stopConnection() {
        sseReconnectJob?.cancel()
        sseReconnectJob = null
        sseHealthCheckJob?.cancel()
        sseHealthCheckJob = null
        sseJob?.cancel()
        sseJob = null
        // Use shutdown() (not disconnect()) to also kill the launched opencode process.
        // The user clicked "Stop" — they expect everything to stop, including the process
        // we own. disconnect() would leave the process running as a zombie on the port.
        connectionManager.shutdown()
    }

    // ── SSE subscription (single global, routes by sessionId) ───────────────

    @Volatile private var sseJob: Job? = null
    @Volatile private var sseReconnectJob: Job? = null
    @Volatile private var sseReconnectAttempt: Int = 0
    /** Timestamp (epoch ms) of the last SSE event received. Used for health-check timing. */
    private val sseLastEventTimeMs = AtomicLong(0L)
    /** Job that periodically health-checks the server when SSE is silent. */
    @Volatile private var sseHealthCheckJob: Job? = null

    /** Single global SSE subscription — all events routed to SessionManager.processEvent().
     *  Includes automatic reconnection with exponential backoff on stream end,
     *  and periodic health-check probes when the connection is silent (the Java
     *  HTTP engine has no socket-level idle timeout — see TDD §4.2.1). */
    private fun startGlobalSseSubscription() {
        sseJob?.cancel()
        sseReconnectJob?.cancel()
        sseHealthCheckJob?.cancel()
        val client = connectionManager.client ?: return
        val connectTime = System.currentTimeMillis()
        sseLastEventTimeMs.set(connectTime)
        logger.info { "[ACP] SSE connected at $connectTime" }

        sseJob = launchSseJob(client, connectTime)
        sseHealthCheckJob = launchHealthCheck(client, connectTime)
    }

    /** Launch the SSE event collection job. Shared between initial subscription
     *  and reconnection to prevent divergence. Handles stream end by triggering
     *  reconnection — works correctly for both user-initiated cancellation and
     *  unexpected errors. */
    private fun launchSseJob(client: OpenCodeClient, connectTime: Long): Job = scope.launch {
        try {
            client.subscribeGlobalEvents().collect { event ->
                sseLastEventTimeMs.set(System.currentTimeMillis())
                handleSseEvent(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] SSE collection error: ${e.message}" }
        }
        // Stream ended — trigger reconnection if this wasn't a user-initiated stop.
        // CancellationException from stopConnection() is caught above; after it,
        // scope is cancelled so isActive=false and we skip reconnection.
        if (isActive) {
            val uptimeSec = (System.currentTimeMillis() - connectTime) / 1000
            logger.info { "[ACP] SSE stream ended after ${uptimeSec}s — triggering reconnection" }
            triggerGlobalSseReconnect()
        }
    }

    /** Launch a health-check probe coroutine. When the SSE connection has been
     *  silent for [ChatConstants.SSE_HEALTH_CHECK_INTERVAL_MS], sends a lightweight
     *  GET /global/health to verify the server and connection are alive. If the
     *  health check fails, cancels [sseJob] which triggers reconnection via
     *  [launchSseJob]'s post-catch logic.
     *
     *  This replaces the old idle-detection approach that killed healthy
     *  connections during normal user thinking time. */
    private fun launchHealthCheck(client: OpenCodeClient, connectTime: Long): Job = scope.launch {
        val checkInterval = ChatConstants.SSE_HEALTH_CHECK_INTERVAL_MS
        val checkTimeout = ChatConstants.SSE_HEALTH_CHECK_TIMEOUT_MS
        while (isActive) {
            delay(checkInterval)
            val lastEvent = sseLastEventTimeMs.get()
            val silenceMs = System.currentTimeMillis() - lastEvent
            if (silenceMs < checkInterval) continue  // Recent activity — no probe needed

            try {
                val healthy = withTimeout(checkTimeout) { client.healthCheck() }
                if (healthy) {
                    // Server is alive and responding — reset the timer so the SSE
                    // connection stays open. Silence doesn't mean death.
                    sseLastEventTimeMs.set(System.currentTimeMillis())
                } else {
                    logger.warn { "[ACP] SSE health check failed (server unhealthy) after ${silenceMs}ms silence — triggering reconnection" }
                    sseJob?.cancel()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logger.warn { "[ACP] SSE health check failed (request error) after ${silenceMs}ms silence — triggering reconnection" }
                sseJob?.cancel()
                break
            }
        }
    }

    /** Reconnect the global SSE stream with exponential backoff.
     *  On success, recovers background sessions that may have completed during disconnection. */
    private fun triggerGlobalSseReconnect() {
        sseReconnectJob?.cancel()
        sseReconnectAttempt = 0

        sseReconnectJob = scope.launch {
            while (isActive) {
                val base = ChatConstants.RECONNECT_DELAY_MS
                val max = ChatConstants.RECONNECT_MAX_DELAY_MS
                val exponential = (base * (1L shl sseReconnectAttempt.coerceAtMost(10))).coerceAtMost(max)
                val jitter = (exponential * kotlin.random.Random.nextDouble(0.0, 0.2)).toLong()
                val delayMs = (exponential + jitter).coerceAtMost(max)

                if (sseReconnectAttempt > 0) {
                    logger.info { "[ACP] SSE reconnect attempt ${sseReconnectAttempt + 1}, waiting ${delayMs}ms" }
                    delay(delayMs)
                }

                val client = connectionManager.client
                if (client == null) {
                    logger.warn { "[ACP] SSE reconnect: client is null, giving up" }
                    return@launch
                }

                try {
                    if (!client.healthCheck()) {
                        sseReconnectAttempt++
                        continue
                    }
                } catch (_: Exception) {
                    sseReconnectAttempt++
                    continue
                }

                // Server healthy — re-subscribe global SSE
                logger.info { "[ACP] SSE reconnect: server healthy, re-subscribing" }
                sseJob?.cancel()
                sseHealthCheckJob?.cancel()
                // Wait for old jobs to finish before launching new ones to prevent
                // stale StreamingCompleted signals from completing the new turn's deferred.
                sseJob?.join()
                sseHealthCheckJob?.join()
                val reconnectTime = System.currentTimeMillis()
                sseLastEventTimeMs.set(reconnectTime)
                logger.info { "[ACP] SSE reconnected at $reconnectTime" }

                sseJob = launchSseJob(client, reconnectTime)
                sseHealthCheckJob = launchHealthCheck(client, reconnectTime)

                // Recover background sessions that may have completed during disconnection
                recoverBackgroundSessions()

                // Refresh state that went stale during the disconnect — the server
                // may have updated todos or context while we weren't listening to SSE.
                try {
                    sessionManager.fetchTodos()
                    sessionManager.computeSessionContext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] SSE reconnect: failed to refresh todos/context" }
                }

                sseReconnectAttempt = 0
                logger.info { "[ACP] SSE reconnected successfully" }
                return@launch
            }
        }
    }

    /** Check all cached sessions that were streaming when SSE dropped.
     *  Re-fetches recent messages for each streaming session. If the server's
     *  last message is an assistant message (indicating generation completed),
     *  finalize it locally. Prevents responseDeferred from hanging until the
     *  configurable response timeout.
     *
     *  SAFETY: Before finalizing, checks for in-progress tool calls. If the
     *  last assistant message has ToolUse parts without matching ToolResult
     *  parts, the session is likely still generating — we skip finalization
     *  and let the SSE reconnection deliver the remaining events. This
     *  prevents incorrectly finalizing a session that's mid-tool-execution
     *  (TDD §11 Risk 3).
     *
     *  ASSUMPTION: The server's REST API (listMessages) returns the current
     *  state of messages, including in-progress tool parts. If this assumption
     *  is wrong, the safety check may not catch all cases. */
    private suspend fun recoverBackgroundSessions() {
        val client = connectionManager.client ?: return
        val streamingSessions = sessionManager.getStreamingSessions()
        if (streamingSessions.isEmpty()) return

        logger.info { "[ACP] recoverBackgroundSessions: checking ${streamingSessions.size} streaming sessions" }

        // Parallelize REST fetches — each session needs a separate listMessages call.
        // Sequential fetches would take N * RTT; parallel fetches take ~1 * RTT.
        coroutineScope {
            val deferreds = streamingSessions.map { session ->
                async {
                    val sessionId = session.sessionId
                    try {
                        val messages = client.listMessages(sessionId, limit = 5)
                        val lastMessage = messages.lastOrNull()
                        val lastIsAssistant = lastMessage?.info?.role == "assistant"

                        if (lastIsAssistant) {
                            // Safety check: detect in-progress tool calls across ALL fetched messages,
                            // not just the last one. The server returns messages in chronological order
                            // in practice, but scanning all fetched messages is safer and bounded (limit=5).
                            val hasInProgressTools = messages.flatMap { it.parts }
                                .filterIsInstance<com.opencode.acp.adapter.OpenCodePart.ToolUse>()
                                .any { toolUse ->
                                    messages.flatMap { it.parts }
                                        .filterIsInstance<com.opencode.acp.adapter.OpenCodePart.ToolResult>()
                                        .none { it.toolUseId == toolUse.id }
                                }

                            if (hasInProgressTools) {
                                logger.info { "[ACP] recoverBackgroundSessions: session $sessionId has in-progress tools — skipping finalization (will recover via SSE)" }
                                return@async
                            }

                            val activeMsgId = session.ctx.activeMessageId
                            if (activeMsgId != null) {
                                session.completeStreaming(activeMsgId)
                            } else {
                                logger.warn { "[ACP] recoverBackgroundSessions: session $sessionId has no activeMessageId — cannot finalize, session may remain in streaming state" }
                            }
                            session.responseDeferred?.complete(Unit)
                            session.responseDeferred = null
                            logger.info { "[ACP] Recovered background session $sessionId after SSE reconnection" }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "[ACP] Failed to recover session $sessionId" }
                    }
                }
            }
            deferreds.awaitAll()
        }
    }

    private suspend fun handleSseEvent(event: SseEvent) {
        val summary = when (event) {
            is SseEvent.TextChunk -> "TextChunk(sid=${event.sessionId}, mid=${event.messageId}, text.len=${event.text.length})"
            is SseEvent.ThinkingChunk -> "ThinkingChunk(sid=${event.sessionId}, mid=${event.messageId}, text.len=${event.text.length})"
            is SseEvent.Stop -> "Stop(sid=${event.sessionId}, mid=${event.messageId}, reason=${event.stopReason})"
            is SseEvent.Error -> "Error(sid=${event.sessionId}, mid=${event.messageId}, msg.len=${event.message.length})"
            is SseEvent.ToolUse -> "ToolUse(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.ToolResult -> "ToolResult(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.Permission -> "Permission(sid=${event.sessionId}, mid=${event.messageId}, tool=${event.toolCallId})"
            is SseEvent.Ignored -> "Ignored(type=${event.eventType}, reason=${event.reason})"
            else -> "${event::class.simpleName}(sid=${event.sessionId}, mid=${event.messageId})"
        }
        logger.debug { "[ACP] handleSseEvent: $summary" }
        sessionManager.processEvent(event)
    }

    // ── Message sending ────────────────────────────────────────────────────

    suspend fun sendMessage(
        text: String,
        files: List<AttachedFile> = emptyList(),
        modelID: String? = null,
        providerID: String? = null,
        variant: String? = null,
        agent: String? = null,
        model: OpenCodeClient.MessageModel? = null
    ): SendMessageResult {
        if (!sendMutex.tryLock()) {
            logger.warn { "[ACP] sendMessage: rejected — another send is already in progress" }
            return SendMessageResult.Error("Another message is already being sent. Please wait for it to complete.")
        }
        try {
            return sendMessageInternal(text, files, modelID, providerID, variant, agent, model)
        } finally {
            sendMutex.unlock()
        }
    }

    private suspend fun sendMessageInternal(
        text: String,
        files: List<AttachedFile>,
        modelID: String?,
        providerID: String?,
        variant: String?,
        agent: String?,
        model: OpenCodeClient.MessageModel?
    ): SendMessageResult {
        val client = connectionManager.client ?: return SendMessageResult.Error("No client")
        val currentSessionId = sessionManager.activeSessionId.value ?: return SendMessageResult.Error("No session")
        logger.debug { "[ACP] sendMessage: START session=$currentSessionId textLength=${text.length}" }

        val userMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.USER,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            attachedFiles = files
        )
        sessionManager.addMessage(userMsg)
        sessionManager.setLastUserText(text)

        client.resetReasoningTracking()
        val assistantMsgId = sessionManager.createAssistantMessage(
            modelID = modelID,
            providerID = providerID,
            serverMessageId = null
        )

        val deferred = CompletableDeferred<Unit>()
        val activeSession = sessionManager.getActiveSession()
        activeSession?.responseDeferred = deferred

        try {
            val parts = mutableListOf<com.opencode.acp.adapter.OpenCodePart>(com.opencode.acp.adapter.OpenCodePart.Text(text = text))
            files.forEach { file ->
                if (file.path.isBlank()) {
                    // Pre-rev2 history entries: clipboard images stored path="".
                    // Cannot send an empty URL — skip this file and log a warning.
                    logger.warn { "[ACP] Skipping attached file '${file.name}' with blank path (pre-rev2 legacy)" }
                    return@forEach
                }
                val fileObj = java.io.File(file.path)
                if (!fileObj.exists() || !fileObj.canRead()) {
                    // File was deleted or unreadable (e.g., auto-cleaned clipboard image).
                    // Skip with warning instead of sending a file:// URL the server can't read.
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — file not found or unreadable: ${file.path}" }
                    return@forEach
                }
                // CWE-22 path traversal guard: reject paths with .. sequences that escape
                // known-safe locations. Clipboard images are stored in
                // <projectDir>/.opencode/attachments/ or user.home/.opencode/attachments/.
                // Project files are under basePath. System temp directory is NOT allowed
                // (too broad — any process can write there on shared machines).
                val canonicalPath = fileObj.canonicalPath
                val projectBase = project.basePath?.let { java.io.File(it).canonicalPath }
                val userHome = System.getProperty("user.home")?.let { java.io.File(it).canonicalPath }
                val isInsideProject = projectBase != null && canonicalPath.startsWith(projectBase + java.io.File.separator)
                val isInsideProjectAttachments = projectBase != null && canonicalPath.startsWith(projectBase + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
                val isInsideUserHomeAttachments = userHome != null && canonicalPath.startsWith(userHome + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
                val isAllowed = isInsideProject || isInsideProjectAttachments || isInsideUserHomeAttachments
                if (!isAllowed) {
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — path escapes allowed directories: ${file.path}" }
                    return@forEach
                }
                // Use canonical path for the URL to prevent symlink-based exfiltration
                val url = com.opencode.acp.util.pathToFileUrl(canonicalPath)
                if (url == null) {
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — pathToFileUrl returned null for: ${file.path}" }
                    return@forEach
                }
                val wireMime = normalizeAttachmentMime(file.mime)
                parts.add(com.opencode.acp.adapter.OpenCodePart.File(
                    mime = wireMime, url = url, filename = file.name
                ))
            }
            logger.info { "[ACP] sendMessage: ${parts.size} parts (text + ${files.size} file attachments: ${files.joinToString { it.name }})" }

            val serverMessageId = client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)
            logger.info { "[ACP] sendMessage: got serverMessageId=$serverMessageId" }

            sessionManager.updateServerMessageId(assistantMsgId, serverMessageId)

            // Activity-aware response timeout: resets whenever SSE events arrive.
            // Prevents false timeouts during long-running generations (subtasks, tool chains)
            // where the LLM is actively producing output but total wall-clock time
            // exceeds responseTimeoutSeconds.
            //
            // IMPORTANT: During tool execution (especially subtasks/subagents), the server
            // sends SSE events for the CHILD session, not the parent. The parent session
            // receives no events between tool.start and tool.result. Without the running-tool
            // guard, the activity monitor would false-positive after responseTimeoutSeconds
            // even though the server is actively working.
            val activityMonitorJob = scope.launch {
                while (isActive) {
                    delay(ACTIVITY_CHECK_INTERVAL_MS)
                    val responseTimeoutMs = OpenCodeSettingsState.getInstance().state.responseTimeoutSeconds.coerceIn(10, 3600) * 1000L
                    // Re-fetch the active session on each iteration instead of using the
                    // captured reference. The session could be evicted from the cache
                    // during the long-running send (e.g., user switches sessions and the
                    // old session is LRU-evicted). Reading from an evicted SessionState
                    // would give stale toolPartStates and lastActivityTimeMs values.
                    val monitorSession = sessionManager.getActiveSession()
                    // If the session was evicted or switched, fall back to current time
                    // so we don't false-positive timeout on stale data.
                    if (monitorSession == null) {
                        logger.debug { "[ACP] sendMessage: active session no longer cached, skipping activity check" }
                        continue
                    }
                    // If any tool is actively running, the server is busy — don't timeout.
                    // Tool events (ToolUse→ToolResult) can take arbitrarily long (subtasks,
                    // file writes, network calls). During this time the parent session gets
                    // no SSE events, so lastActivityTimeMs goes stale even though work is
                    // happening server-side.
                    // PartState.Pending (waiting for user permission) also counts as active —
                    // the server IS working, just blocked on user input. Without this, the
                    // monitor would false-positive timeout while the user reads the permission prompt.
                    // Snapshot the values list to avoid ConcurrentModificationException
                    // if the event processing coroutine mutates the map during iteration.
                    // This is a best-effort read — exact correctness is not required (per isStreaming KDoc).
                    val partStatesSnapshot = monitorSession.ctx.toolPartStates.values.toList()
                    val hasRunningTools = partStatesSnapshot.any {
                        it is PartState.InProgress || it is PartState.Pending
                    }
                    if (hasRunningTools) {
                        // Tools are running — skip the normal activity timeout, BUT enforce
                        // a hard ceiling based on tool START TIME (not lastActivityTimeMs,
                        // which is reset by metadata events and thus unreliable for detecting
                        // truly stuck tools). If a tool's ToolResult is lost (child crash, SSE
                        // reconnect gap), the tool stays InProgress forever. This ceiling
                        // ensures recovery.
                        val toolStuckTimeoutSec = OpenCodeSettingsState.getInstance().state.toolStuckTimeoutSeconds
                        val toolStuckTimeoutMs = toolStuckTimeoutSec.coerceIn(60, 3600) * 1000L
                        // Snapshot entries to avoid ConcurrentModificationException.
                        val pillsSnapshot = monitorSession.ctx.toolCallPills.entries.toList()
                        val statesSnapshot = monitorSession.ctx.toolPartStates.toMap()
                        val oldestToolStartMs = pillsSnapshot
                            .filter {
                                val state = statesSnapshot[it.key]
                                state is PartState.InProgress || state is PartState.Pending
                            }
                            .mapNotNull { it.value.startTimeMs }
                            .minOrNull()
                        if (oldestToolStartMs != null) {
                            val toolElapsed = System.currentTimeMillis() - oldestToolStartMs
                            if (toolElapsed > toolStuckTimeoutMs) {
                                logger.error { "[ACP] sendMessage: tool stuck for ${toolElapsed}ms (> ${toolStuckTimeoutSec}s) — aborting" }
                                sessionManager.abortStreaming("Tool stuck for >${toolStuckTimeoutSec}s.")
                                break
                            }
                        }
                        logger.debug { "[ACP] sendMessage: tools still running, skipping activity check" }
                        continue
                    }
                    val lastActivity = monitorSession.ctx.lastActivityTimeMs
                    val elapsed = System.currentTimeMillis() - lastActivity
                    if (elapsed > responseTimeoutMs) {
                        val timeoutSec = OpenCodeSettingsState.getInstance().state.responseTimeoutSeconds
                        logger.error { "[ACP] sendMessage: no SSE activity for ${elapsed}ms (> ${timeoutSec}s) — aborting" }
                        sessionManager.abortStreaming("No activity for ${timeoutSec}s.")
                        // abortStreaming() emits StreamingCompleted which completes
                        // responseDeferred via the global signal collector. Don't call
                        // deferred.complete() again — it would throw IllegalStateException.
                        break
                    }
                }
            }
            try {
                deferred.await()
            } finally {
                activityMonitorJob.cancel()
            }
            // If the monitor completed the deferred (timeout), isStreaming is now false
            // and the message was aborted. Check for that case.
            // Re-fetch the active session — the reference captured at L680 may be stale
            // if the session was evicted/switched during the long-running send.
            val currentSession = sessionManager.getActiveSession()
            val wasAborted = currentSession != null && !currentSession.ctx.isStreaming && currentSession.ctx.errorMessage != null
            return if (wasAborted && currentSession != null) {
                SendMessageResult.Error(currentSession.ctx.errorMessage ?: "Response timed out")
            } else {
                SendMessageResult.Success(assistantMsgId)
            }
        } catch (e: CancellationException) {
            try { sessionManager.completeStreaming(assistantMsgId) } catch (ex: Exception) {
                logger.debug(ex) { "[ACP] completeStreaming failed during CancellationException handling" }
            }
            throw e
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Request timed out. Check that the server is running."
                e.message?.contains("connect", ignoreCase = true) == true ->
                    "Connection lost to server."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "Connection refused by server."
                else -> "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            sessionManager.abortStreaming(errorMsg)
            return SendMessageResult.Error(errorMsg)
        } finally {
            // Re-fetch the active session — the reference captured at L680 may be stale
            // if the session was evicted/switched during the long-running send.
            sessionManager.getActiveSession()?.responseDeferred = null
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /** Inject a local-only assistant message into the active session's chat
     *  history WITHOUT sending it to the server. Used for plugin-side info
     *  messages (e.g. unresolved model args in `/review-perform`, review
     *  failure notices) that should appear in the chat but don't need an LLM
     *  response. The message is marked [MessageState.Completed] immediately. */
    fun injectLocalMessage(text: String) {
        val msg = ChatMessage(
            id = generateId(),
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            state = MessageState.Completed,
        )
        sessionManager.addMessage(msg)
    }

    suspend fun cancel() {
        val client = connectionManager.client
        val currentSessionId = sessionManager.activeSessionId.value
        // Capture the active session reference at the same time as the session ID so
        // the deferred we complete belongs to the session that was active when cancel
        // started — not a potentially different session if the user switched mid-cancel.
        val activeSession = sessionManager.getActiveSession()
        if (client != null && currentSessionId != null) {
            client.abortSession(currentSessionId)
        }
        activeSession?.let { session ->
            session.responseDeferred?.complete(Unit)
            session.responseDeferred = null
        }
    }

    /**
     * Cancel the in-progress response AND return a [CompletableDeferred] that
     * completes when the send mutex is released.
     *
     * Used by [ChatViewModel.steerMessage] to wait for the abort to complete
     * before sending a new message.
     *
     * If the mutex is not held (no send in progress), returns an already-completed
     * deferred without calling cancel().
     */
    suspend fun steerCancel(): CompletableDeferred<Unit> {
        // Always call cancel() — it's idempotent (abortSession is a no-op if
        // nothing is streaming).
        cancel()

        // Always wait for the mutex to be released — this ensures the old
        // sendMessage()'s finally block has completed before the caller proceeds.
        val deferred = CompletableDeferred<Unit>()

        // Guard against scope cancellation: if the scope is already cancelled
        // when we try to launch, the coroutine never starts and deferred
        // would never complete. Complete it immediately in that case.
        if (!scope.isActive) {
            deferred.complete(Unit)
            return deferred
        }

        val job = scope.launch {
            try {
                // Acquire the mutex (suspends until the old sendMessage()'s
                // finally block releases it), then immediately release it —
                // we just want to know it's available, not hold it.
                sendMutex.lock()
                sendMutex.unlock()
                deferred.complete(Unit)
            } catch (e: CancellationException) {
                // Scope cancelled — complete deferred so caller unblocks
                deferred.complete(Unit)
                throw e
            } catch (_: Exception) {
                // Mutex acquisition failed — complete deferred to unblock caller
                deferred.complete(Unit)
            }
        }
        // If the caller times out, cancel the waiting coroutine so it doesn't leak
        deferred.invokeOnCompletion { if (it != null) job.cancel() }
        return deferred
    }

    suspend fun respondPermission(permissionId: String, toolCallId: String, sessionId: String, response: PermissionResponse) =
        permissionManager.respondPermission(permissionId, toolCallId, sessionId, response)

    suspend fun respondQuestion(promptId: String, answers: List<List<String>>, sessionId: String) =
        permissionManager.respondQuestion(promptId, answers, sessionId)

    suspend fun rejectQuestion(promptId: String, sessionId: String) =
        permissionManager.rejectQuestion(promptId, sessionId)

    // ── Data fetching ──────────────────────────────────────────────────────

    suspend fun computeSessionContext(controlState: ControlBarState? = null) {
        sessionManager.computeSessionContext(controlState)
    }

    suspend fun computeSessionContextLocal(controlState: ControlBarState? = null) {
        sessionManager.computeSessionContextLocal(controlState)
    }

    suspend fun refreshActiveSessionMessages() {
        sessionManager.refreshActiveSessionMessages()
    }

    /** Whether the background compactor has a valid checkpoint for the active session. */
    fun isCheckpointReady(): Boolean = sessionManager.isCheckpointReady()

    suspend fun fetchTodos() = sessionManager.fetchTodos()

    suspend fun fetchAvailableCommands(): List<SlashCommand> =
        commandManager.fetchAvailableCommands()

    suspend fun executeServerCommand(commandName: String, args: String = "") =
        commandManager.executeServerCommand(commandName, args)

    /** Get messages StateFlow for a cached session (returns null if not cached). */
    fun getSessionMessages(sessionId: String) = sessionManager.getSessionMessages(sessionId)

    fun getStreamingText(sessionId: String) = sessionManager.getStreamingText(sessionId)

    // ── Data access (delegate methods for ViewModel) ───────────────────────

    suspend fun listAgents(): List<com.opencode.acp.adapter.AgentInfo> {
        val client = connectionManager.client
        if (client == null) {
            logger.warn { "[ACP] listAgents: client is null" }
            return emptyList()
        }
        logger.info { "[ACP] listAgents: calling /agent..." }
        return client.listAgents()
    }

    suspend fun listProviders(): com.opencode.acp.adapter.ProviderResponse? {
        val client = connectionManager.client
        if (client == null) {
            logger.warn { "[ACP] listProviders: client is null" }
            return null
        }
        logger.info { "[ACP] listProviders: calling /provider..." }
        return client.listProviders()
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    override fun dispose() {
        logger.info { "[ACP] OpenCodeService.dispose() called — project closing" }
        connectionManager.shutdown()
        // Close sessions BEFORE cancelling scope — SessionManager.close() uses
        // tryLock() to avoid blocking EDT, and scope.cancel() will interrupt any
        // coroutines still holding the lock (e.g., ensureSessionCached blocking on
        // HTTP). This ordering gives clean sessions a chance to close normally
        // before cancellation forces them shut.
        sessionManager.close()
        scope.cancel()
        sseJob = null
        sseReconnectJob = null
        sseHealthCheckJob = null
        permissionManager.dispose()
        com.opencode.acp.chat.ChatToolWindowFactory.disposeActiveComposePanelAsync()
    }

    companion object {
        fun getInstance(project: Project): OpenCodeService = project.service()

        /** How often the activity-aware timeout monitor checks for SSE activity.
         *  5s interval balances responsiveness (detects dead connections within
         *  responseTimeoutSeconds + 5s) against overhead (one timestamp read per check). */
        private const val ACTIVITY_CHECK_INTERVAL_MS = 5_000L
    }
}

/** Result of a sendMessage call. */
sealed interface SendMessageResult {
    data class Success(val messageId: String) : SendMessageResult
    data class Error(val message: String) : SendMessageResult
}
