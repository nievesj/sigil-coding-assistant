package com.opencode.acp.chat.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
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
class OpenCodeService(private val project: Project) : OpenCodeServiceApi, Disposable {

    private val logger = KotlinLogging.logger {}

    // Late-binding reference for error surfacing — set after sessionManager is created.
    // This breaks the initialization circular dependency: scope's CoroutineExceptionHandler
    // needs sessionManager, but sessionManager needs scope.
    @Volatile private var errorSurfacer: ((String) -> Unit)? = null

    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
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

    override val connectionManager = ProcessManager(scope).apply {
        onMcpReset = { resetMcpOnServerRestart() }
    }
    override val sessionManager = SessionManager(scope, project).also {
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
        McpConfigWriter(path, OpenCodeMcpSettingsState.getInstance())
    }

    override val permissionManager = PermissionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager,
        mcpConfigWriterProvider = { mcpConfigWriter }
    )
    val childPermissionRelay = ChildPermissionRelay(sessionManager)

    // Extracted subsystem — handles child-session permission requests from the
    // global allSessionSignals flow. Encapsulates Brave Mode relay-point
    // auto-approve and orphan-retry fallback logic. See [PermissionRelayHandler].
    private val permissionRelayHandler = PermissionRelayHandler(
        scope = scope,
        sessionManager = sessionManager,
        permissionManager = permissionManager,
        childPermissionRelay = childPermissionRelay,
    )

    // NEW: extracted subsystem — attachment security validation + encoding.
    // projectBasePath and userHomePath MUST be canonicalized — the startsWith
    // boundary check depends on canonical form to handle symlinked project roots.
    private val attachmentValidator = AttachmentValidator(
        projectBasePath = project.basePath?.let {
            try { java.io.File(it).canonicalPath }
            catch (e: java.io.IOException) {
                logger.warn(e) { "[ACP] Failed to canonicalize project base path: $it — using non-canonical form" }
                it
            }
        },
        userHomePath = System.getProperty("user.home")?.let {
            try { java.io.File(it).canonicalPath }
            catch (e: java.io.IOException) {
                logger.warn(e) { "[ACP] Failed to canonicalize user home path: $it — using non-canonical form" }
                it
            }
        },
    )
    private val responseTimeoutMonitor = ResponseTimeoutMonitor(
        scope = scope,
        sessionManager = sessionManager,
        responseTimeoutSecondsProvider = { OpenCodeSettingsState.getInstance().state.responseTimeoutSeconds },
        toolStuckTimeoutSecondsProvider = { OpenCodeSettingsState.getInstance().state.toolStuckTimeoutSeconds },
    )
    private val sseConnectionManager = SseConnectionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager,
        onConnectionError = { connectionManager.setConnectionError(ConnectionErrorReason.ServerUnreachable) },
        onReconnectSuccess = {
            // All three post-reconnect calls stay in OpenCodeService — SseConnectionManager
            // signals success via this callback and stays focused on transport.
            // Each call is independently wrapped so a failure in one doesn't skip the others.
            try { sessionManager.recoverBackgroundSessions(connectionManager.client) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { logger.warn(e) { "[ACP] recoverBackgroundSessions failed on reconnect" } }
            try { sessionManager.fetchTodos() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { logger.warn(e) { "[ACP] fetchTodos failed on reconnect" } }
            try { sessionManager.computeSessionContext() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { logger.warn(e) { "[ACP] computeSessionContext failed on reconnect" } }
        },
    )

    /** ToolRegistry singleton, created during initializeMcp(). Settings panel reads this. */
    override var toolRegistry: com.opencode.acp.mcp.ToolRegistry? = null
        private set

    // ── State flows ────────────────────────────────────────────────────────

    override val messages: StateFlow<Map<String, ChatMessage>> = sessionManager.activeMessages
    override val signals: SharedFlow<UiSignal> = sessionManager.activeSignals
    override val globalSignals: SharedFlow<UiSignal> = sessionManager.globalSignals
    override val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    override val connectionErrorReason: StateFlow<ConnectionErrorReason?> = connectionManager.connectionErrorReason
    override val sessionListState: StateFlow<SessionListState> = sessionManager.sessionListState
    override val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = sessionManager.childSessionMap
    override val todoItems: StateFlow<List<TodoItem>> = sessionManager.todoItems
    override val sessionContextState: StateFlow<SessionContextState> = sessionManager.sessionContextState
    override val streamingSessionIds: StateFlow<Set<String>> = sessionManager.streamingSessionIds
    override val pendingCreationSessionIds: StateFlow<Set<String>> = sessionManager.pendingCreationSessionIds
    override val hiddenChildSessionIds: StateFlow<Set<String>> = sessionManager.hiddenChildSessionIds
    override val knownChildSessionIds: StateFlow<Set<String>> = sessionManager.knownChildSessionIds
    override val childToParent: StateFlow<Map<String, String>> = sessionManager.childToParent
    override val activeSessionId: StateFlow<String?> = sessionManager.activeSessionId
    override val sessionCachedFlow: kotlinx.coroutines.flow.SharedFlow<String> = sessionManager.sessionCachedFlow

    // ── Internal state ─────────────────────────────────────────────────────

    private var signalCollectionJob: Job? = null
    override var mcpManager: McpManager? = null

    /**
     * Serializes sendMessage() calls.
     */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    /** Session ID convenience accessor. */
    override val sessionId: String? get() = sessionManager.activeSessionId.value

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
                        // Delegate to PermissionRelayHandler — handles Brave Mode
                        // relay-point auto-approve and orphan-retry fallback. Returns
                        // false for active-session requests (handled via activeSignals).
                        permissionRelayHandler.handlePermissionRequested(sessionId, signal)
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
    override suspend fun initialize(projectBasePath: String?): Boolean {
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
        sseConnectionManager.start()

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
            val settings = OpenCodeMcpSettingsState.getInstance()
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
            // Pass a callback that re-runs tool discovery when a BUILTIN_IDE server
            // connects via background retry. The JetBrains MCP Server starts
            // asynchronously and may not be ready when initialize() runs. The
            // background retry in McpManager keeps trying for 60s; when it
            // connects, this callback re-discovers tools so MCP tools become
            // available without restarting the session.
            mcpManager!!.initialize(onServerConnected = {
                logger.info { "[ACP] MCP: background retry connected — re-running tool discovery" }
                discoverToolsInBackground()
            })

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
    override suspend fun disconnectAllMcp() {
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
    override suspend fun reinitializeMcp() {
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
                // Host comes from ProcessManager (which binds to AcpDefaults.DEFAULT_OPENCODE_HOST,
                // i.e. 127.0.0.1). Reading connectionManager.host keeps this in sync if
                // ProcessManager ever supports a configurable binding address — no hardcoding.
                val baseUrl = "http://${connectionManager.host}:${connectionManager.port}"
                val mcpUrls = mcpMgr.getServerUrls()
                logger.info { "[ACP] discoverToolsInBackground: starting discovery (${mcpUrls.size} MCP servers connected)" }
                val tools = registry.discoverAll(baseUrl, mcpUrls)
                logger.info { "[ACP] discoverToolsInBackground: discovered ${tools.size} tools" }

                // Load persisted permissions so the registry has the user's
                // saved enabled/permission state. The settings panel reads
                // from the registry when opened.
                val settings = OpenCodeMcpSettingsState.getInstance()
                if (settings.toolPermissions.isNotBlank()) {
                    var permissionsCorrupted = false
                    val persisted: Map<String, Pair<Boolean, String>> = try {
                        parsePersistedToolPermissions(settings.toolPermissions)
                    } catch (parseError: Exception) {
                        // FAIL-CLOSED: On corrupted tool-permissions JSON, do NOT leave
                        // all tools at their discovery default (ALLOW). Instead, set
                        // every discovered tool to ASK — the safest interactive default,
                        // which requires explicit user approval per call. This preserves
                        // safety while the user re-saves their settings. Without this,
                        // a corrupted settings file (disk error, partial write, encoding
                        // issue) would silently downgrade all tool permissions to ALLOW,
                        // which is security-relevant (an attacker who can corrupt the
                        // settings file would gain auto-approval for all tools).
                        permissionsCorrupted = true
                        logger.error(parseError) { "[ACP] Corrupted tool permissions JSON — failing closed (all tools set to ASK) until settings are re-saved" }
                        val allDiscovered = registry.tools.values
                        if (allDiscovered.isEmpty()) {
                            // No tools discovered yet — nothing to fail-close over.
                            emptyMap()
                        } else {
                            // Map every discovered tool to (enabled=true, permission="ask").
                            // Keying by both id and name ensures loadEnabledAndPermissions
                            // matches every tool (it matches by id OR raw name).
                            val failClosed = mutableMapOf<String, Pair<Boolean, String>>()
                            for (tool in allDiscovered) {
                                failClosed[tool.id] = Pair(true, "ask")
                                failClosed[tool.name] = Pair(true, "ask")
                            }
                            failClosed.toMap()
                        }
                    }
                    if (persisted.isNotEmpty()) {
                        registry.loadEnabledAndPermissions(persisted.mapValues { (_, pair) ->
                            Pair(pair.first, com.opencode.acp.mcp.ToolPermission.fromActionString(pair.second))
                        })
                    }
                    // Surface a user-visible ERROR notification so the user knows their
                    // permission choices were lost and they should re-save settings.
                    if (permissionsCorrupted) {
                        com.opencode.acp.chat.OpenCodeNotifications.showRestartWarning(
                            "[ACP] Tool permissions settings file is corrupted. All tools have been set to ASK for safety. Please re-save your tool permissions in Settings → Tools → Sigil → MCP."
                        )
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
    override fun parsePersistedToolPermissions(perms: String): Map<String, Pair<Boolean, String>> {
        if (perms.isBlank()) return emptyMap()
        // On parse failure, this method THROWS. Callers are responsible for
        // applying fail-closed behavior (set all discovered tools to ASK) and
        // surfacing a user-visible notification. Returning emptyMap() here would
        // be a fail-open pattern — the user's ASK/DENY choices would be silently
        // lost and every tool would revert to its discovery default (ALLOW),
        // which is a security risk (a corrupted settings file would auto-approve
        // all tools). See discoverToolsInBackground for the fail-closed handler.
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(perms).jsonObject
        return obj.entries.associate { (toolName, element) ->
            val toolObj = element.jsonObject
            val enabled = toolObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val permission = toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow"
            toolName to Pair(enabled, permission)
        }
    }

    /**
     * Non-suspend wrapper for settings panel (runs on EDT).
     * Launches disconnect + config write + reinitialize on the service scope.
     */
    override fun reinitializeMcpFromSettings() {
        scope.launch {
            try {
                disconnectAllMcp()
                val settings = OpenCodeMcpSettingsState.getInstance()
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
    override fun resetMcpOnServerRestart() {
        scope.launch {
            try {
                mcpManager?.resetOnServerRestart()
                // Re-write config file in case it was stale, then re-register via POST /mcp
                val settings = OpenCodeMcpSettingsState.getInstance()
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
    override val mcpServerStatuses: StateFlow<Map<String, McpConnectionStatus>>
        get() = mcpManager?.serverStatuses ?: emptyMcpStatuses.asStateFlow()

    // ── Session management (delegated) ─────────────────────────────────────

    override suspend fun loadSessions() = sessionManager.loadSessions()
    override fun loadMoreSessions() = sessionManager.loadMoreSessions()
    override suspend fun switchSession(sessionId: String) = sessionManager.switchSession(sessionId)
    override suspend fun createAndSwitchSession(title: String?) = sessionManager.createAndSwitchSession(title)
    override suspend fun archiveSession(sessionId: String) = sessionManager.archiveSession(sessionId)
    override suspend fun clearAllSessions() = sessionManager.clearAllSessions()

    // ── Streaming session tracking (sidebar spinner) ──────────────────────────

    /** Imperatively add a session ID to the streaming set (activates sidebar spinner).
     *  Used by ChatViewModel.sendMessage() before the suspend call so the spinner
     *  appears immediately on send. Idempotent. */
    override fun addStreamingSession(sessionId: String) = sessionManager.addStreamingSession(sessionId)

    /** Imperatively remove a session ID from the streaming set (deactivates sidebar spinner).
     *  Used by ChatViewModel on cancel/switch/error. Idempotent. */
    override fun removeStreamingSession(sessionId: String) = sessionManager.removeStreamingSession(sessionId)

    /** Un-hide a child session (e.g., when switching to it via ToolPill "open child"). */
    override fun unhideChildSession(sessionId: String) = sessionManager.unhideChildSession(sessionId)

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
    override fun stopConnection() {
        sseConnectionManager.stop()
        // Use shutdown() (not disconnect()) to also kill the launched opencode process.
        // The user clicked "Stop" — they expect everything to stop, including the process
        // we own. disconnect() would leave the process running as a zombie on the port.
        connectionManager.shutdown()
    }

    // ── Message sending ────────────────────────────────────────────────────

    override suspend fun sendMessage(
        text: String,
        files: List<AttachedFile>,
        modelID: String?,
        providerID: String?,
        variant: String?,
        agent: String?,
        model: OpenCodeClient.MessageModel?
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
            val addedFileNames: List<String>
            if (files.isNotEmpty()) {
                val result = attachmentValidator.validateAndEncode(files)
                parts.addAll(result.parts)
                addedFileNames = result.acceptedFileNames
                result.rejectedFiles.forEach { r ->
                    logger.warn { "[ACP] Rejected attachment '${r.name}': ${r.reason} (${r.path})" }
                }
            } else {
                addedFileNames = emptyList()
            }
            val filePartCount = parts.count { it is com.opencode.acp.adapter.OpenCodePart.File }
            logger.info { "[ACP] sendMessage: ${parts.size} parts (text + $filePartCount file attachments: ${addedFileNames.joinToString { it }})" }

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
            //
            // The monitor is started BEFORE sendMessageAsync so it also covers the send phase.
            // lastActivityTimeMs is reset here so the send-phase timeout is measured from this point.
            // sendMessageAsync uses TimeoutProfile.INFINITE (the POST blocks until generation
            // finishes) — do NOT wrap it in withTimeoutOrNull, as that would cancel the POST
            // client-side while the server is still actively generating.
            sendSession?.let { it.turnLifecycleState.lastActivityTimeMs = System.currentTimeMillis() }
            val activityMonitorJob = responseTimeoutMonitor.startMonitoring(
                onTimeout = { msg -> sessionManager.abortStreamingWithFallback(msg, assistantMsgId) },
                onToolStuck = { msg -> sessionManager.abortStreamingWithFallback(msg, assistantMsgId) },
                effectiveLastActivityMsProvider = { sessionManager.getEffectiveLastActivityMs() },
                childActivityProvider = { sessionManager.isAnyChildActivelyGenerating() },
            )
            try {
                // sessionId validation is handled by OpenCodeClient.sendMessageAsync (validatePathId)
                // sendMessageAsync uses TimeoutProfile.INFINITE because the server POST blocks
                // until the LLM finishes generating (can be minutes for complex tool chains).
                // The activity monitor (started above) handles generation timeouts with its
                // tool-running guard — do NOT wrap in withTimeoutOrNull here, as that would
                // cancel the POST client-side while the server is still actively generating.
                val serverMessageId = client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)
                logger.info { "[ACP] sendMessage: got serverMessageId=$serverMessageId" }
                sessionManager.updateServerMessageId(assistantMsgId, serverMessageId)
                deferred.await()
            } finally {
                activityMonitorJob.cancel()
            }
            // If the monitor completed the deferred (timeout), isStreaming is now false
            // and the message was aborted. Check for that case.
            // Use the session captured at send time (sendSession) rather than re-fetching
            // the active session — the user may have switched sessions during the send,
            // and we must check the streaming/error state of the session that was sending,
            // not whatever session is now active.
            val wasAborted = sendSession != null && !sendSession.isStreaming && sendSession.errorMessage != null
            return if (wasAborted) {
                SendMessageResult.Error(sendSession?.errorMessage ?: "Response timed out")
            } else {
                SendMessageResult.Success(assistantMsgId)
            }
        } catch (e: CancellationException) {
            // Use sendSession only if it's still the active session and not closed.
            // If the session was evicted during the send, completeStreaming is a no-op
            // on a closed SessionState — re-fetch to ensure we finalize the right session.
            val currentSession = sessionManager.getActiveSession()
            val sessionToFinalize = if (currentSession != null && !currentSession.isClosed && currentSession === sendSession) {
                currentSession
            } else {
                sendSession?.takeIf { it.isClosed.not() }
            }
            // Wrap in withTimeoutOrNull to avoid hanging the cancellation path if
            // the event processing coroutine is holding stateLock (e.g., inside
            // resegmentDirect's long markdown parse).
            try {
                val finalized = withTimeoutOrNull(5000) { sessionToFinalize?.completeStreaming(assistantMsgId) }
                if (finalized == null && sessionToFinalize != null) {
                    // completeStreaming timed out — force-set isStreaming=false to prevent
                    // a perpetual streaming indicator (ghost message). The user must restart
                    // the session for full recovery, but at least the UI isn't stuck.
                    logger.warn { "[ACP] completeStreaming timed out after 5s during CancellationException handling — force-finalizing message. msgId=$assistantMsgId" }
                    try {
                        sessionManager.forceFinalizeMessage(assistantMsgId)
                    } catch (ex: Exception) {
                        logger.debug(ex) { "[ACP] forceFinalizeMessage also failed" }
                    }
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.debug(ex) { "[ACP] completeStreaming failed during CancellationException handling" }
            }
            throw e
        } catch (e: Exception) {
            // Diagnostic logging for timeout investigation: capture the exception
            // type, whether any sessions were streaming (subagent activity), and
            // the streaming session IDs. This helps distinguish:
            // - SHORT-profile POST timeout (60s) during active generation
            // - Permission timeout (60s) interrupting subagent
            // - Genuine network timeout with no streaming activity
            val streamingIds = sessionManager.streamingSessionIds.value
            when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    logger.error { "[ACP] sendMessage: TimeoutCancellationException — streamingSessions=$streamingIds activeSessionStreaming=${sendSession?.isStreaming} childActive=${sessionManager.isAnyChildActivelyGenerating()}" }
                }
                is java.net.SocketTimeoutException -> {
                    logger.error { "[ACP] sendMessage: SocketTimeoutException — streamingSessions=$streamingIds activeSessionStreaming=${sendSession?.isStreaming} childActive=${sessionManager.isAnyChildActivelyGenerating()}" }
                }
            }
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
            sessionManager.abortStreamingWithFallback(errorMsg, assistantMsgId)
            return SendMessageResult.Error(errorMsg)
        } finally {
            // No responseDeferred cleanup needed here. The send session's deferred is
            // already completed and nulled by the StreamingCompleted handler
            // (startGlobalSignalCollection, line ~203). The sendMutex guarantees no
            // concurrent send can run on the same session, so there is no race that
            // could leave a dangling deferred. Re-fetching the *current* active session
            // (which may have been switched/evicted during the long-running send) and
            // nulling its deferred would be misleading — it targets a session that was
            // never the sender. Intentionally a no-op.
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /** Inject a local-only assistant message into the active session's chat
     *  history WITHOUT sending it to the server. Used for plugin-side info
     *  messages (e.g. unresolved model args in `/review-perform`, review
     *  failure notices) that should appear in the chat but don't need an LLM
     *  response. The message is marked [MessageState.Completed] immediately. */
    override fun injectLocalMessage(text: String) {
        val msg = ChatMessage(
            id = generateId(),
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            state = MessageState.Completed,
        )
        sessionManager.addMessage(msg)
    }

    override suspend fun cancel() {
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
    override suspend fun steerCancel(): CompletableDeferred<Unit> {
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
                // A Channel-based signal would be better (instant notification, no polling
                // latency) but adds complexity; the 50ms latency is acceptable here.
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
            } catch (e: Exception) {
                // Mutex acquisition failed — complete deferred to unblock caller.
                // Log the exception so unexpected failures (e.g., IllegalMonitorStateException)
                // are visible rather than silently masked.
                logger.warn(e) { "[ACP] steerCancel: unexpected exception during mutex poll — completing deferred" }
                deferred.complete(Unit)
            }
        }
        // If the caller times out, cancel the waiting coroutine so it doesn't leak
        deferred.invokeOnCompletion { if (it != null) job.cancel() }
        return deferred
    }

    override suspend fun respondPermission(
        permissionId: String, toolCallId: String, sessionId: String,
        response: PermissionResponse,
        toolName: String,
        patterns: List<String>,
        agentName: String,
    ) = permissionManager.respondPermission(permissionId, toolCallId, sessionId, response, toolName, patterns, agentName)

    override suspend fun respondQuestion(promptId: String, answers: List<List<String>>, sessionId: String) =
        permissionManager.respondQuestion(promptId, answers, sessionId)

    override suspend fun rejectQuestion(promptId: String, sessionId: String) =
        permissionManager.rejectQuestion(promptId, sessionId)

    // ── Data fetching ──────────────────────────────────────────────────────

    override suspend fun computeSessionContext(controlState: ControlBarState?) {
        sessionManager.computeSessionContext(controlState)
    }

    override suspend fun computeSessionContextLocal(controlState: ControlBarState?) {
        sessionManager.computeSessionContextLocal(controlState)
    }

    override suspend fun refreshActiveSessionMessages() {
        sessionManager.refreshActiveSessionMessages()
    }

    /** Whether the background compactor has a valid checkpoint for the active session. */
    override fun isCheckpointReady(): Boolean = sessionManager.isCheckpointReady()

    override suspend fun fetchTodos() = sessionManager.fetchTodos()

    override suspend fun fetchAvailableCommands(): List<SlashCommand> =
        commandManager.fetchAvailableCommands()

    override suspend fun executeServerCommand(commandName: String, args: String) =
        commandManager.executeServerCommand(commandName, args)

    /** Get messages StateFlow for a cached session (returns null if not cached). */
    override fun getSessionMessages(sessionId: String) = sessionManager.getSessionMessages(sessionId)

    override fun getStreamingText(sessionId: String) = sessionManager.getStreamingText(sessionId)

    // ── Data access (delegate methods for ViewModel) ───────────────────────

    override suspend fun listAgents(): List<com.opencode.acp.adapter.AgentInfo> {
        val client = connectionManager.client
        if (client == null) {
            logger.warn { "[ACP] listAgents: client is null" }
            return emptyList()
        }
        logger.info { "[ACP] listAgents: calling /agent..." }
        return client.listAgents()
    }

    override suspend fun listProviders(): com.opencode.acp.adapter.ProviderResponse? {
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
        sseConnectionManager.stop()
        permissionManager.dispose()
        com.opencode.acp.chat.ChatToolWindowFactory.disposeActiveComposePanelAsync()
    }

    companion object {
        fun getInstance(project: Project): OpenCodeService = project.service()
    }
}

/** Result of a sendMessage call. */
sealed interface SendMessageResult {
    data class Success(val messageId: String) : SendMessageResult
    data class Error(val message: String) : SendMessageResult
}
