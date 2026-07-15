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

    // Late-binding reference for error surfacing — set after sessionManager is created.
    // This breaks the initialization circular dependency: scope's CoroutineExceptionHandler
    // needs sessionManager, but sessionManager needs scope.
    @Volatile private var errorSurfacer: ((String) -> Unit)? = null

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
        logger.error(throwable) { "[ACP] Uncaught coroutine exception in OpenCodeService scope" }
        // Surface critical errors to the UI via the global signals flow.
        // SessionManager._globalSignals is collected by the ViewModel and routed
        // to the SessionError handler, which resets _streamPhase and removes the
        // streaming session indicator. This prevents the UI from appearing stuck
        // when a background coroutine crashes.
        if (throwable !is CancellationException) {
            try {
                errorSurfacer?.invoke(throwable.message ?: throwable.javaClass.simpleName)
            } catch (e: Exception) {
                // Don't let the error-surfacing mechanism itself throw —
                // the original exception is already logged above.
                logger.warn(e) { "[ACP] Error surfacing mechanism failed — original exception was logged at error level above" }
            }
        }
        // RESOLVED: errorSurfacer is set immediately after SessionManager construction
        // (see the .also{} block below). During the brief window before it's set, early
        // failures are logged at error level above and swallowed — this is acceptable
        // because the connectionObserverJob will retry the connection. Once errorSurfacer
        // is wired, all subsequent exceptions surface to the UI.
    })

    // ── Sub-components ─────────────────────────────────────────────────────

    val connectionManager = ProcessManager(scope).apply {
        onMcpReset = { resetMcpOnServerRestart() }
    }
    val sessionManager = SessionManager(scope, project).also {
        // Wire the error surfer now that sessionManager exists.
        errorSurfacer = { msg -> it.emitGlobalError(msg) }
    }
    val commandManager = CommandManager(
        clientProvider = { connectionManager.client },
        sessionIdProvider = { sessionManager.activeSessionId.value }
    )
    private val mcpConfigWriter by lazy {
        val path = project.basePath?.let { java.nio.file.Path.of(it) }
            ?: java.nio.file.Path.of(".")
        McpConfigWriter(path, OpenCodeSettingsState.getInstance())
    }

    val permissionManager = PermissionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager,
        mcpConfigWriterProvider = { mcpConfigWriter }
    )
    val childPermissionRelay = ChildPermissionRelay(sessionManager)

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
    val hiddenChildSessionIds: StateFlow<Set<String>> = sessionManager.hiddenChildSessionIds
    val knownChildSessionIds: StateFlow<Set<String>> = sessionManager.knownChildSessionIds
    val childToParent: StateFlow<Map<String, String>> = sessionManager.childToParent
    val activeSessionId: StateFlow<String?> = sessionManager.activeSessionId
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
                    is UiSignal.PermissionRequested -> {
                        // Relay child permissions to parent (non-active sessions only)
                        if (sessionId != sessionManager.activeSessionId.value) {
                            val relayed = childPermissionRelay.relayChildPermission(sessionId, signal.prompt)
                            if (relayed != null) {
                                sessionManager.emitGlobalSignal(relayed)
                                sessionManager.markChildPendingPermission(sessionId)
                            }
                        }
                        // If sessionId == activeSessionId, it's already handled via activeSignals
                    }
                    is UiSignal.PermissionReplied -> {
                        // Confirm server processed the reply — clear child pending permission flag
                        // and forward to ViewModel via globalSignals. The ViewModel's activeSignals
                        // collector has a no-op `Unit` branch for PermissionReplied (it's handled
                        // here via globalSignals to avoid double-handling when the child IS the
                        // active session). Do NOT remove that no-op or add handling there.
                        sessionManager.clearChildPendingPermission(sessionId)
                        // Forward to ViewModel via globalSignals so the PermissionReplied handler
                        // (which clears prompts, checks failedPermissionPostSessions, and handles
                        // cascade rejection) actually executes. Without this, that handler is dead code.
                        sessionManager.emitGlobalSignal(signal)
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
        // RESOLVED: MCP is optional; failures are logged at warn level (see initializeMcp
        // catch block) and chat works without it. By design — initialize() returns true
        // even if MCP fails so the user can still chat. The settings panel exposes a
        // "Discover Tools" button to retry MCP initialization once servers are ready.
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
                // RESOLVED: Server is always launched on localhost by ProcessManager
                // (it binds to 127.0.0.1 only — never 0.0.0.0). The port is dynamic
                // (connectionManager.port), but the host is always 127.0.0.1. Hardcoding
                // the host here is correct and intentional.
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
            // Log the failure — the corrupted value will be overwritten naturally
            // when the user next saves settings. Don't destructively clear it here
            // because this parse function is called from background coroutines
            // (discoverToolsInBackground) where a transient race could destroy
            // valid saved permissions.
            logger.warn(e) { "[ACP] Failed to parse persisted tool permissions — value will be overwritten on next settings save" }
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

    /** Un-hide a child session (e.g., when switching to it via ToolPill "open child"). */
    fun unhideChildSession(sessionId: String) = sessionManager.unhideChildSession(sessionId)

    // ── Connection stop ─────────────────────────────────────────────────────

    /** Stop all connection activity: cancel SSE reconnection loop, cancel SSE
     *  subscription, and shut down the HTTP client + server process. Called by:
     *  - The "Stop" button on the splash screen (user wants to abort reconnection)
     *  - cancelInitialization() (user wants to abort the initial connection attempt)
     *  - The "Disconnect" button in the input area (user wants to disconnect)
     *
     *  In all cases, killing the server process is correct because the plugin
     *  owns the process — it was launched by ProcessManager.initialize(). The
     *  user can reconnect via the "Connect" button which re-launches the server. */
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
                    // Trigger reconnection explicitly — launchSseJob's post-catch
                    // logic checks isActive which is false after cancellation, so
                    // it won't call triggerGlobalSseReconnect() on its own.
                    triggerGlobalSseReconnect()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logger.warn { "[ACP] SSE health check failed (request error) after ${silenceMs}ms silence — triggering reconnection" }
                sseJob?.cancel()
                // Trigger reconnection explicitly — same reason as above.
                triggerGlobalSseReconnect()
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
                val exponential = minOf(base * (1L shl sseReconnectAttempt.coerceIn(0, MAX_BACKOFF_SHIFT)), max)
                val jitter = (exponential * kotlin.random.Random.nextDouble(0.0, 0.2)).toLong()
                val delayMs = (exponential + jitter).coerceAtMost(max)

                // Circuit breaker: after MAX_RECONNECT_ATTEMPTS, transition to ERROR state
                // to stop infinite reconnection loops on permanently-down servers.
                if (sseReconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                    logger.error { "[ACP] SSE reconnect: giving up after $MAX_RECONNECT_ATTEMPTS attempts — server appears permanently down" }
                    connectionManager.setConnectionError(ConnectionErrorReason.ServerUnreachable)
                    return@launch
                }

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
                // Capture the old jobs BEFORE reassigning sseJob/sseHealthCheckJob below.
                // The previous code called `sseJob?.join()` after `sseJob = launchSseJob(...)`,
                // which joined the NEW job instead of the old one — defeating the purpose
                // of waiting for the old job to finish before launching new ones.
                val oldSseJob = sseJob
                val oldHealthCheckJob = sseHealthCheckJob
                oldSseJob?.cancel()
                oldHealthCheckJob?.cancel()
                // Wait for old jobs to finish before launching new ones to prevent
                // stale StreamingCompleted signals from completing the new turn's deferred.
                // Use withTimeoutOrNull to avoid hanging if the old job is stuck on
                // a half-open TCP connection (no socket timeout per AGENTS.md).
                // If the join times out, the old job becomes a zombie — but it's harmless:
                // oldSseJob?.cancel() was already called above, and the scope will reclaim it
                // on dispose. The zombie can't process new events (its TCP stream is dead).
                withTimeoutOrNull(5000) { oldSseJob?.join() }
                withTimeoutOrNull(5000) { oldHealthCheckJob?.join() }
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

        // Use supervisorScope instead of coroutineScope so that one failed
        // async block (e.g., CancellationException from session eviction)
        // does NOT cancel the other recovery attempts. Each async block
        // catches its own exceptions internally, but supervisorScope ensures
        // structured concurrency without cross-cancellation.
        kotlinx.coroutines.supervisorScope {
            val deferreds = streamingSessions.map { session ->
                async {
                    val sessionId = session.sessionId
                    try {
                        // Fetch 20 messages (not 5) for the in-progress tool check.
                        // A ToolUse beyond a 5-message window (e.g., a long-running subtask
                        // that spawned its own messages) would be missed, causing premature
                        // finalization. 20 is a balance between safety and REST payload size.
                        val messages = client.listMessages(sessionId, limit = 20)
                        val lastMessage = messages.lastOrNull()
                        // RESOLVED (false positive): Current logic is correct — only
                        // finalize if the last message is assistant (generation completed).
                        // If the last is user, the assistant hasn't started responding yet,
                        // so skipping finalization is the correct behavior (the SSE
                        // reconnection will deliver the assistant's response normally).
                        // Using lastOrNull (not lastOrNull { role == "assistant" }) is
                        // intentional: we want to know the server's CURRENT last message,
                        // not the most recent assistant message.
                        val lastIsAssistant = lastMessage?.info?.role == "assistant"

                        if (lastIsAssistant) {
                            // Safety check: detect in-progress tool calls across ALL fetched messages,
                            // not just the last one. The server returns messages in chronological order
                            // in practice, but scanning all fetched messages is safer and bounded (limit=20).
                            // O(n) instead of O(n²): build a Set of completed tool IDs first,
                            // then check ToolUse membership. Single flatMap reused for both checks.
                            val allParts = messages.flatMap { it.parts }
                            val completedToolIds = allParts.filterIsInstance<com.opencode.acp.adapter.OpenCodePart.ToolResult>()
                                .map { it.toolUseId }
                                .toSet()
                            val hasInProgressTools = allParts.filterIsInstance<com.opencode.acp.adapter.OpenCodePart.ToolUse>()
                                .any { it.id !in completedToolIds }

                            if (hasInProgressTools) {
                                logger.info { "[ACP] recoverBackgroundSessions: session $sessionId has in-progress tools — skipping finalization (will recover via SSE)" }
                                return@async
                            }

                            val activeMsgId = session.activeMessageId
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
        // Capture the session reference at send time so the CancellationException
        // handler below finalizes the SAME session that started the send — not
        // whatever session happens to be active when cancellation occurs (the user
        // may have switched sessions mid-send).
        val sendSession = activeSession

        try {
            val parts = mutableListOf<com.opencode.acp.adapter.OpenCodePart>(com.opencode.acp.adapter.OpenCodePart.Text(text = text))
            // Track filenames actually added to `parts` so the summary log reflects
            // what was sent, not what was requested. Files can be skipped by the
            // guards below (blank path, unreadable, outside allowed dirs, denylist,
            // empty image, I/O failure) — listing the input count was misleading.
            val addedFileNames = mutableListOf<String>()
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
                // RESOLVED (false positive): File.separator IS appended to projectBase
                // before startsWith, so the path boundary is enforced. E.g.,
                // `C:\Project2\file`.startsWith(`C:\Project\`) is FALSE because
                // `C:\Project2` != `C:\Project\`. The separator prevents the
                // C:\Project → C:\Project2 prefix-confusion attack (CWE-22).
                val isInsideProject = projectBase != null && canonicalPath.startsWith(projectBase + java.io.File.separator)
                val isInsideProjectAttachments = projectBase != null && canonicalPath.startsWith(projectBase + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
                val isInsideUserHomeAttachments = userHome != null && canonicalPath.startsWith(userHome + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
                val isAllowed = isInsideProject || isInsideProjectAttachments || isInsideUserHomeAttachments
                if (!isAllowed) {
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — path escapes allowed directories: ${file.path}" }
                    return@forEach
                }
                // Denylist: reject known-sensitive path segments even if inside allowed directories.
                // Prevents accidental exfiltration of secrets, credentials, and VCS metadata
                // via prompt injection or social engineering.
                // Uses path-segment matching (not substring) to avoid false positives like
                // a project directory named "my.idea" or a source dir named "build/".
                val denylistSegments = setOf(
                    ".env", ".env.local", ".env.production",
                    ".git", ".hg", ".svn",
                    ".idea",
                    "node_modules",
                )
                val rootOnlyDenylist = setOf("build", "target", "out")
                val pathSegments = canonicalPath.lowercase()
                    .split(java.io.File.separatorChar, '/')
                    .filter { it.isNotEmpty() }
                // Root-level segments: on Windows, index 0 is the drive letter (e.g., "c:"),
                // so the first directory is at index 1. On Unix, index 0 is the first
                // directory. Check both to handle cross-platform paths correctly.
                val rootSegmentIndex = if (pathSegments.isNotEmpty() && pathSegments[0].endsWith(":")) 1 else 0
                val isDenylisted = pathSegments.any { it in denylistSegments } ||
                    (pathSegments.size > rootSegmentIndex && pathSegments[rootSegmentIndex] in rootOnlyDenylist)
                // Apply denylist to ALL paths regardless of location.
                // A symlink in .opencode/attachments/ pointing to .env would otherwise
                // bypass the denylist. The denylist must apply universally to prevent
                // exfiltration of secrets via symlink tricks.
                if (isDenylisted) {
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — path matches denylist segment (sensitive location): ${file.path}" }
                    return@forEach
                }
                // Image attachments: send as data: URI (base64-embedded) instead of file:// URL.
                // The OpenCode server's Image.normalize() requires data: URIs; file:// URLs are
                // fragile on Windows and silently fail. The OpenCode CLI itself sends data: URIs.
                // The wire part type remains "file" with mime "image/*" — do NOT use type "image"
                // (the server does not recognize outbound "image" parts).
                if (file.mime.startsWith("image/")) {
                    try {
                        val fileSize = fileObj.length()
                        if (fileSize > MAX_ATTACHMENT_SIZE_BYTES) {
                            logger.warn { "[ACP] Skipping attached image '${file.name}' — file too large (${fileSize / (1024*1024)}MB > ${MAX_ATTACHMENT_SIZE_BYTES / (1024*1024)}MB limit): ${file.path}" }
                            return@forEach
                        }
                        val bytes = fileObj.readBytes()
                        // Re-verify canonical path after read to detect TOCTOU symlink swap
                        val postReadCanonical = fileObj.canonicalPath
                        if (postReadCanonical != canonicalPath) {
                            logger.warn { "[ACP] Skipping attached image '${file.name}' — path changed during read (possible symlink swap): ${file.path}" }
                            return@forEach
                        }
                        // NOTE: This detects swaps that persist after the read, but NOT
                        // mid-read swaps that are reverted before this check. For high-security
                        // contexts, use Files.readAllBytes(Path) with a pre-opened FileChannel.
                        // Low-probability attack — documented as residual risk.
                        if (bytes.isEmpty()) {
                            logger.warn { "[ACP] Skipping attached image '${file.name}' — file is empty: ${file.path}" }
                            return@forEach
                        }
                        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                        val dataUri = "data:${file.mime};base64,$base64"
                        parts.add(com.opencode.acp.adapter.OpenCodePart.File(
                            mime = file.mime, url = dataUri, filename = file.name
                        ))
                        addedFileNames.add(file.name)
                    } catch (e: Exception) {
                        logger.warn { "[ACP] Skipping attached image '${file.name}' — failed to read for data URI: ${file.path}" }
                        return@forEach
                    }
                } else {
                    // Non-image attachments: use canonical path for the URL to prevent
                    // symlink-based exfiltration.
                    val url = com.opencode.acp.util.pathToFileUrl(canonicalPath)
                    if (url == null) {
                        logger.warn { "[ACP] Skipping attached file '${file.name}' — pathToFileUrl returned null for: ${file.path}" }
                        return@forEach
                    }
                    val wireMime = normalizeAttachmentMime(file.mime)
                    parts.add(com.opencode.acp.adapter.OpenCodePart.File(
                        mime = wireMime, url = url, filename = file.name
                    ))
                    addedFileNames.add(file.name)
                }
            }
            val filePartCount = parts.count { it is com.opencode.acp.adapter.OpenCodePart.File }
            logger.info { "[ACP] sendMessage: ${parts.size} parts (text + $filePartCount file attachments: ${addedFileNames.joinToString { it }})" }

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
                    // NOTE: Timeout is re-read each iteration — changes take effect mid-response.
                    // This is intentional but may cause unexpected aborts if the user lowers the timeout.
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
                    // Acquire stateLock for a consistent snapshot of tool states and pills.
                    // Without the lock, the event processing coroutine could mutate these maps
                    // mid-snapshot, producing an inconsistent view (e.g., a tool appears
                    // InProgress in partStates but Completed in pills). The lock is ReentrantLock
                    // and the read is O(n) — brief enough to not cause contention.
                    val (partStatesSnapshot, pillsSnapshot, statesSnapshot) = monitorSession.snapshotToolState()
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
                    val lastActivity = monitorSession.lastActivityTimeMs
                    // RESOLVED: lastActivityTimeMs is @Volatile in TurnLifecycleState,
                    // ensuring visibility across coroutines. It's written on the SSE event
                    // processing coroutine and read here on the activity monitor coroutine.
                    // @Volatile provides the necessary happens-before guarantee for a single
                    // Long field — no additional synchronization needed.
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
            val wasAborted = currentSession != null && !currentSession.isStreaming && currentSession.errorMessage != null
            return if (wasAborted) {
                SendMessageResult.Error(currentSession.errorMessage ?: "Response timed out")
            } else {
                SendMessageResult.Success(assistantMsgId)
            }
        } catch (e: CancellationException) {
            // Use the session captured at send time (sendSession) rather than
            // re-fetching the active session — the user may have switched sessions
            // between send and cancel, and we must finalize the original session.
            // Wrap in withTimeoutOrNull to avoid hanging the cancellation path if
            // the event processing coroutine is holding stateLock (e.g., inside
            // resegmentDirect's long markdown parse).
            try {
                withTimeoutOrNull(5000) { sendSession?.completeStreaming(assistantMsgId) }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.debug(ex) { "[ACP] completeStreaming failed during CancellationException handling" }
            }
            throw e
        } catch (e: Exception) {
            val errorMsg = when {
                e is kotlinx.coroutines.TimeoutCancellationException ->
                    "Request timed out. Check that the server is running."
                e is java.net.ConnectException ->
                    "Connection lost to server."
                e is java.net.SocketTimeoutException ->
                    "Request timed out. Check that the server is running."
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
                // Poll for the mutex instead of suspending on lock(). A suspended
                // lock() acquisition can be cancelled mid-suspend, leaving the mutex
                // in an inconsistent state (the cancellation resumes the suspended
                // coroutine but the lock acquisition may not complete cleanly). The
                // polling approach with tryLock() + delay() avoids this: tryLock()
                // is non-suspending and either succeeds or fails immediately.
                // Polling with 50ms interval — up to 50ms latency before detecting a released
                // mutex. Acceptable for steer use case (user is waiting for UI feedback).
                // A Channel-based signal would provide instant notification but adds complexity.
                while (isActive) {
                    if (sendMutex.tryLock()) {
                        sendMutex.unlock()
                        deferred.complete(Unit)
                        return@launch
                    }
                    delay(50)
                }
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

    suspend fun respondPermission(
        permissionId: String, toolCallId: String, sessionId: String,
        response: PermissionResponse,
        toolName: String = "",
        patterns: List<String> = emptyList(),
        agentName: String = "orchestrator",
    ) = permissionManager.respondPermission(permissionId, toolCallId, sessionId, response, toolName, patterns, agentName)

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

        /** Maximum file size for image attachments (10MB). Prevents OOM from large files
         *  (base64 encoding inflates size ~33%, plus the data URI string — 10MB → ~37MB heap). */
        private const val MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024

        /** Maximum bit shift for exponential backoff (prevents Long overflow). */
        private const val MAX_BACKOFF_SHIFT = 10

        /** Maximum SSE reconnection attempts before transitioning to ERROR state. */
        private const val MAX_RECONNECT_ATTEMPTS = 50
    }
}

/** Result of a sendMessage call. */
sealed interface SendMessageResult {
    data class Success(val messageId: String) : SendMessageResult
    data class Error(val message: String) : SendMessageResult
}
