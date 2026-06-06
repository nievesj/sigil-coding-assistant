package com.opencode.acp.chat.viewmodel

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodePart
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.MessageProcessorManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.config.AcpDefaults
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.Closeable

class ChatViewModel(
    private val scope: CoroutineScope
) : Closeable {

    private val logger = KotlinLogging.logger {}

    // --- State ---
    private val processor = MessageProcessorManager(scope)
    val messages: StateFlow<List<ChatMessage>> = processor.messages

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt.asStateFlow()

    private val _selectionPrompt = MutableStateFlow<SelectionPrompt?>(null)
    val selectionPrompt: StateFlow<SelectionPrompt?> = _selectionPrompt.asStateFlow()

    /** Signal emitted by the Ctrl+V IntelliJ action to trigger clipboard image paste in Compose. */
    private val _pasteImageSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pasteImageSignal = _pasteImageSignal.asSharedFlow()

    /** Signal emitted when Ctrl+V finds text on the clipboard. Carries the text to insert. */
    private val _pasteTextSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pasteTextSignal = _pasteTextSignal.asSharedFlow()

    init {
        // Collect processor signals and update UI state accordingly
        scope.launch {
            processor.signals.collect { signal ->
                when (signal) {
                    is UiSignal.StreamingStarted -> {
                        _isStreaming.value = true
                    }
                    is UiSignal.StreamingCompleted -> {
                        _isStreaming.value = false
                        responseDeferred?.complete(Unit)
                        responseDeferred = null
                        // Refresh context after each response completes.
                        // Todos arrive via SSE (UiSignal.TodoUpdated) — no REST poll needed here.
                        scope.launch {
                            computeSessionContext()
                        }
                        // Refresh session list to pick up any new child sessions (subagents)
                        scope.launch {
                            try {
                                loadSessions()
                                val children = _childSessionMap.value[sessionId] ?: emptyList()
                                if (children.isNotEmpty()) {
                                    processor.injectSubagentRefs(signal.messageId, children.map { child ->
                                        SubagentRef(
                                            sessionId = child.id,
                                            agentName = child.title.replaceFirstChar { it.uppercase() },
                                            taskDescription = child.title,
                                            status = if (child.outputTokens > 0) SubagentStatus.COMPLETED else SubagentStatus.RUNNING,
                                        )
                                    })
                                }
                            } catch (_: Exception) { /* best-effort refresh */ }
                        }
                    }
                    is UiSignal.PermissionRequested -> {
                        _permissionPrompt.value = signal.prompt
                        startPermissionTimeout()
                    }
                    is UiSignal.SelectionRequested -> {
                        _selectionPrompt.value = signal.prompt
                    }
                    is UiSignal.FileChanged -> {
                        _fileChangeSignal.tryEmit(Unit)
                    }
                    is UiSignal.TodoUpdated -> {
                        _todoItems.value = signal.todos
                    }
                    is UiSignal.SessionCreated -> {
                        scope.launch {
                            try {
                                loadSessions()
                            } catch (_: Exception) { /* best-effort refresh */ }
                        }
                    }
                    is UiSignal.Error -> {
                        _isStreaming.value = false
                        responseDeferred?.complete(Unit)
                        responseDeferred = null
                    }
                }
            }
        }
    }

    /** Session list state for the sidebar. */
    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    /** Child sessions keyed by parent session ID. Updated after loadSessions / switchSession. */
    private val _childSessionMap = MutableStateFlow<Map<String, List<SessionItem>>>(emptyMap())
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = _childSessionMap.asStateFlow()

    /** Whether the sidebar is visible. Persisted in settings. */
    private val _isSidebarVisible = MutableStateFlow(OpenCodeSettingsState.getInstance().sidebarVisible)
    val isSidebarVisible: StateFlow<Boolean> = _isSidebarVisible.asStateFlow()

    /** Context state for the active session — loading, loaded, or error. */
    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    /** Todo items for the current session, updated via SSE and initial fetch. */
    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    /** Command history — most recent first. */
    private val _commandHistory = MutableStateFlow<List<CommandHistoryEntry>>(
        OpenCodeSettingsState.getInstance().commandHistory.toList()
    )
    val commandHistory: StateFlow<List<CommandHistoryEntry>> = _commandHistory.asStateFlow()

    /** Available slash commands fetched from the server. */
    private val _availableCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val availableCommands: StateFlow<List<SlashCommand>> = _availableCommands.asStateFlow()

    /** Mutex to prevent concurrent session switches. NOT reentrant. */
    private val switchMutex = Mutex()

    /** Signal emitted whenever a tool call modifies a file, so the review panel can refresh immediately. */
    private val _fileChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fileChangeSignal: SharedFlow<Unit> = _fileChangeSignal.asSharedFlow()

    /** Emits a signal to check the clipboard for an image. Called by the IntelliJ Ctrl+V action. */
    fun requestImagePaste() {
        _pasteImageSignal.tryEmit(Unit)
    }

    /** Emits a signal to insert text into the input field. Called by the paste handler. */
    fun requestTextPaste(text: String) {
        _pasteTextSignal.tryEmit(text)
    }

    /**
     * Starts an SSE subscription for the given session, filtering events by sessionId.
     * Returns the Job so the caller can cancel it.
     * When the SSE stream ends (not cancelled), triggers automatic reconnection.
     */
    private fun startSseSubscription(client: OpenCodeClient, targetSessionId: String): Job {
        val capturedId = targetSessionId
        return scope.launch {
            try {
                client.subscribeGlobalEvents()
                    .filter { event ->
                        // Pass through events for our session, plus SessionCreated events
                        // which may come from child sessions (subagents) with different IDs
                        event.sessionId == capturedId || event is SseEvent.SessionCreated
                    }
                    .collect { event -> handleSseEvent(event) }
            } catch (e: CancellationException) {
                throw e // Don't reconnect on cancellation (user-initiated close/switch)
            } catch (_: Exception) {
                // SSE error — stream ended, will trigger reconnect below
            }
            // Stream ended without cancellation — trigger automatic reconnection
            if (isActive && initialized && sessionId == capturedId) {
                logger.info { "SSE stream ended for session $capturedId — triggering reconnection" }
                triggerReconnect()
            }
        }
    }

    private fun resetSessionState() {
        processor.resetSessionState()
        _isStreaming.value = false
        responseDeferred?.complete(Unit)
        responseDeferred = null
        _permissionPrompt.value = null
        _selectionPrompt.value = null
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
        _sessionContextState.value = SessionContextState.Loading
        _todoItems.value = emptyList()
    }

    // --- Internal: OpenCode engine connection ---
    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    private var sessionId: String? = null
    private var sseJob: Job? = null
    private var openCodeProcess: Process? = null
    private var shutdownHook: Thread? = null
    private var permissionTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0

    /** Deferred completed when the current response finishes (Stop event). */
    private var responseDeferred: CompletableDeferred<Unit>? = null

    // --- Configuration ---
    private var host: String = AcpDefaults.DEFAULT_OPENCODE_HOST
    private var port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT
    private var authToken: String? = null
    private var initialized = false
    private var projectBasePath: String = ""

    // --- Public API ---

    /** Check if the OpenCode server is already reachable (e.g., from a previous IDE session). */
    private fun isServerReachable(): Boolean {
        return try {
            val url = java.net.URL("http://$host:$port/global/health")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            val reachable = conn.responseCode == 200
            conn.disconnect()
            reachable
        } catch (_: Exception) {
            false
        }
    }

    /** Launch the OpenCode binary as a subprocess if the server isn't already running. */
    private fun launchOpenCodeBinary(binaryPath: String) {
        // Check if a server is already responding on this port (e.g., from previous IDE session)
        if (isServerReachable()) {
            logger.info { "OpenCode server already running at $host:$port — skipping launch" }
            return
        }

        try {
            logger.info { "Launching: $binaryPath serve --host $host --port $port" }
            val pb = ProcessBuilder(binaryPath, "serve", "--host", host, "--port", port.toString())
                .redirectErrorStream(true)
                .apply {
                    // Ensure the question tool is registered. OpenCode only enables
                    // it when OPENCODE_CLIENT is "app", "cli", or "desktop".
                    environment().putIfAbsent("OPENCODE_CLIENT", "cli")
                }
            openCodeProcess = pb.start()
            logger.info { "OpenCode process started (PID: ${openCodeProcess?.pid()})" }

            // Drain stdout/stderr to a background thread (non-blocking)
            Thread({
                openCodeProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        logger.debug { "[opencode] $line" }
                    }
                }
            }, "opencode-stdout-drain").apply {
                isDaemon = true
                start()
            }

            // Register shutdown hook to kill process on IDE exit
            // Only register once — reuse the same hook if re-initialized
            if (shutdownHook == null) {
                shutdownHook = Thread({
                    killProcess(openCodeProcess)
                }, "opencode-shutdown").apply { isDaemon = true }
                Runtime.getRuntime().addShutdownHook(shutdownHook)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to launch OpenCode binary at $binaryPath" }
        }
    }

    /** Kill a process and its children. Uses taskkill on Windows for reliable tree kill. */
    private fun killProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        try {
            val pid = process.pid()
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                // Windows: taskkill /F /T kills the process tree
                Runtime.getRuntime().exec("taskkill /F /T /PID $pid").waitFor()
            } else {
                // Unix: destroyForcibly works better here
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }

    /** Initialise the connection: launch binary, create client, health check, create session. */
    suspend fun initialize(projectBasePath: String) {
        if (initialized) {
            logger.info { "Already initialized — skipping" }
            return
        }

        logger.info { "Initializing connection to OpenCode at $host:$port" }
        _connectionState.value = ConnectionState.CONNECTING
        this.projectBasePath = projectBasePath
        try {
            // Always launch the OpenCode binary if a path is configured
            val settings = OpenCodeSettingsState.getInstance().state
            if (settings.binaryPath.isNotBlank()) {
                logger.info { "Launching OpenCode binary: ${settings.binaryPath}" }
                launchOpenCodeBinary(settings.binaryPath)
                // Give the server time to start
                delay(2000)
            } else {
                logger.warn { "No OpenCode binary path configured — cannot start server" }
                _connectionState.value = ConnectionState.ERROR
                return
            }

            // Create HTTP client and OpenCodeClient
            logger.info { "Creating HTTP client for $host:$port" }
            val client = HttpClient(Java) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                    })
                }
                install(SSE)
            }
            httpClient = client

            val opencodeClient = OpenCodeClient(
                baseUrl = "http://$host:$port",
                httpClient = client,
                authToken = authToken
            )
            openCodeClient = opencodeClient

            // Health check
            logger.info { "Running health check..." }
            if (!opencodeClient.healthCheck()) {
                logger.warn { "Health check failed — server not reachable at $host:$port" }
                _connectionState.value = ConnectionState.ERROR
                return
            }
            logger.info { "Health check passed" }

            // Load agents first so we can select the default
            var defaultAgent: String? = null
            try {
                val allAgents = opencodeClient.listAgents()
                val agents = allAgents.filter { it.mode != "subagent" && it.hidden != true }
                logger.info { "Loaded ${agents.size} user-facing agents (filtered from ${allAgents.size} total)" }
                _controlState.value = _controlState.value.copy(
                    agents = agents.map { info ->
                        OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                    }
                )
                // Default to "orchestrator" if available, otherwise first agent
                defaultAgent = agents.firstOrNull { it.name == "orchestrator" }?.name
                    ?: agents.firstOrNull()?.name
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load agents (non-fatal)" }
            }

            // Load existing sessions — restore most recent one if available
            loadSessions()

            when (val sessionState = _sessionListState.value) {
                is SessionListState.Loaded -> {
                    if (sessionState.sessions.isNotEmpty()) {
                        // Switch to the most recent session
                        switchSession(sessionState.sessions.first().id)
                    }
                    // If no sessions, stay disconnected — user can create one manually
                }
                is SessionListState.Error -> {
                    // Failed to load sessions — retry once
                    logger.warn { "Session list load failed, retrying..." }
                    loadSessions()
                    val retryState = _sessionListState.value as? SessionListState.Loaded
                    if (retryState != null && retryState.sessions.isNotEmpty()) {
                        switchSession(retryState.sessions.first().id)
                    }
                    // If still no sessions after retry, stay disconnected
                }
                is SessionListState.Loading -> {
                    // Shouldn't happen
                    logger.warn { "Session list still in Loading state after loadSessions()" }
                }
            }

            // Select the default agent in the control state
            if (defaultAgent != null) {
                val agentInfo = _controlState.value.agents.find { it.id == defaultAgent }
                if (agentInfo != null) {
                    _controlState.value = _controlState.value.copy(selectedAgent = agentInfo)
                }
            }

            // Load providers and models (only from connected/authenticated providers)
            try {
                val providerResponse = opencodeClient.listProviders()
                val connectedIds = providerResponse.connected.toSet()
                val models = providerResponse.all
                    .filter { it.id in connectedIds }
                    .flatMap { provider ->
                        provider.models.map { (_, modelData) ->
                            ProviderModel(
                                providerID = provider.id,
                                modelID = modelData.id,
                                displayName = "${provider.name} / ${modelData.name}",
                                reasoning = modelData.reasoning,
                                contextWindow = modelData.limit?.context ?: 0,
                                providerIconId = provider.id,
                                variants = modelData.variants?.keys?.toList() ?: emptyList()
                            )
                    }
                }
                // ALL models from ALL providers (including disconnected) — for context limit lookup
                val allModels = providerResponse.all
                    .flatMap { provider ->
                        provider.models.map { (_, modelData) ->
                            ProviderModel(
                                providerID = provider.id,
                                modelID = modelData.id,
                                displayName = "${provider.name} / ${modelData.name}",
                                reasoning = modelData.reasoning,
                                contextWindow = modelData.limit?.context ?: 0,
                                providerIconId = provider.id,
                                variants = modelData.variants?.keys?.toList() ?: emptyList()
                            )
                        }
                    }
                logger.info { "Loaded ${models.size} connected models, ${allModels.size} total models from ${providerResponse.all.size} providers" }
                // Restore last selected model, or fall back to first
                val savedKey = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance()
                    .lastSelectedModelKey
                val restoredModel = if (savedKey.isNotEmpty()) {
                    models.find {
                        com.opencode.acp.config.settings.OpenCodeSettingsState.modelKey(it.providerID, it.modelID) == savedKey
                    }
                } else null
                _controlState.value = _controlState.value.copy(
                    models = models,
                    allModels = allModels,
                    selectedModel = restoredModel ?: models.firstOrNull()
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load providers (non-fatal)" }
            }

            // Subscribe to global SSE events (long-lived background subscription)
            // Only subscribe if we have an active session
            if (sessionId != null) {
                logger.info { "Subscribing to SSE events for session $sessionId" }
                sseJob = startSseSubscription(opencodeClient, sessionId!!)

                // Fetch initial context and todos for the active session
                computeSessionContext()
                fetchTodos()

            } else {
                logger.info { "No active session on IDE load - skipping SSE subscription (user can create session manually)" }
            }

            // Fetch available slash commands from the server
            fetchAvailableCommands()

            _connectionState.value = ConnectionState.CONNECTED
            initialized = true
            logger.info { "Connected to OpenCode successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize connection" }
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun selectAgent(agent: OpenCodeAgentInfo) {
        _controlState.value = _controlState.value.copy(selectedAgent = agent)
    }

    fun selectModel(model: ProviderModel) {
        _controlState.value = _controlState.value.copy(selectedModel = model)
        // Persist selection across sessions
        val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance()
        settings.lastSelectedModelKey =
            com.opencode.acp.config.settings.OpenCodeSettingsState.modelKey(model.providerID, model.modelID)
    }

    fun selectThinkingEffort(effort: ThinkingEffort) {
        _controlState.value = _controlState.value.copy(thinkingEffort = effort)
    }

    fun toggleSidebar() {
        val newValue = !_isSidebarVisible.value
        _isSidebarVisible.value = newValue
        OpenCodeSettingsState.getInstance().sidebarVisible = newValue
    }

    suspend fun loadSessions() {
        val client = openCodeClient ?: return
        try {
            val sessionList = client.listSessions()
            val currentId = sessionId
            val items = sessionList
                .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                .map { it.toSessionItem() }
            _sessionListState.value = SessionListState.Loaded(
                sessions = items,
                selectedId = currentId
            )
            // Update child session map
            val children = items.filter { it.parentID != null }
                .groupBy { it.parentID!! }
            _childSessionMap.value = children
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sessions" }
            _sessionListState.value = SessionListState.Error(
                e.message ?: "Failed to load sessions"
            )
        }
    }

    suspend fun switchSession(targetSessionId: String) {
        switchMutex.withLock {
            val client = openCodeClient ?: return
            if (sessionId == targetSessionId) return

            val previousSessionId = sessionId

            try {
                reconnectJob?.cancel()
                reconnectJob = null
                sseJob?.cancel()
                sseJob = null
                // Complete any pending deferred so sendMessage() unblocks,
                // but do NOT abort the server-side session — let it keep running.
                responseDeferred?.complete(Unit)
                responseDeferred = null
                _isStreaming.value = false
                resetSessionState()

                val messages = client.listMessages(targetSessionId, limit = null)
                sessionId = targetSessionId

                // Inject subagent refs into the latest assistant message
                val children = _childSessionMap.value[targetSessionId] ?: emptyList()
                val chatMessages = if (children.isNotEmpty()) {
                    injectSubagentRefs(messages.map { it.toChatMessage() }, children)
                } else {
                    messages.map { it.toChatMessage() }
                }
                chatMessages.forEach { processor.addMessage(it) }

                sseJob = startSseSubscription(client, targetSessionId)
                updateSessionSelection(targetSessionId)
                // Fetch context and todos for the new session
                computeSessionContext()
                fetchTodos()
                fetchAvailableCommands()
                logger.info { "Switched to session $targetSessionId (${chatMessages.size} messages loaded)" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to switch session $targetSessionId" }
                sessionId = previousSessionId
                updateSessionSelection(previousSessionId)
                if (previousSessionId != null) {
                    try {
                        val msgs = client.listMessages(previousSessionId, limit = null)
                        val chatMsgs = msgs.map { it.toChatMessage() }
                        chatMsgs.forEach { processor.addMessage(it) }
                        sseJob = startSseSubscription(client, previousSessionId)
                    } catch (_: Exception) {
                        // Revert failed too — leave empty state
                    }
                }
            }
        }
    }

    suspend fun createAndSwitchSession(title: String? = null) {
        val client = openCodeClient ?: return

        val session = try {
            client.createSession(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to create session" }
            return
        }

        switchMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            try { cancel() } catch (_: Exception) { /* best-effort */ }
            sseJob?.cancel()
            sseJob = null
            resetSessionState()
            sessionId = session.id
            sseJob = startSseSubscription(client, session.id)
        }

        loadSessions()
        updateSessionSelection(session.id)
        // Fetch context and todos for the new session
        computeSessionContext()
        fetchTodos()
        logger.info { "Created and switched to new session: ${session.id}" }
    }

    suspend fun archiveSession(targetSessionId: String) {
        val client = openCodeClient ?: return
        try {
            val success = client.deleteSession(targetSessionId)
            if (!success) {
                logger.warn { "Server returned false for deleteSession($targetSessionId)" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to archive session $targetSessionId" }
            try { loadSessions() } catch (e2: Exception) { logger.warn(e2) { "Also failed to refresh sessions" } }
            return
        }

        if (targetSessionId == sessionId) {
            val previousSessionId = sessionId
            createAndSwitchSession()
            if (sessionId == previousSessionId) {
                logger.error { "Failed to create replacement session after archiving active session" }
                sessionId = null
                resetSessionState()
                updateSessionSelection(null)
            }
        } else {
            loadSessions()
        }

        logger.info { "Archived session $targetSessionId" }
    }

    private fun updateSessionSelection(selectedId: String?) {
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(selectedId = selectedId)
        }
    }

    /** Send a user message to the OpenCode engine. Suspends until the response is complete. */
    suspend fun sendMessage(text: String, files: List<AttachedFile> = emptyList()) {
        val client = openCodeClient ?: return
        val currentSessionId = sessionId ?: return

        // Record in history before sending (so it's available even if send fails)
        recordCommand(text, files)

        // Add user message to chat for immediate display
        val userMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            attachedFiles = files
        )
        processor.addMessage(userMsg)

        // Set echo stripping text before creating assistant message
        processor.setLastUserText(text)

        // Create streaming assistant message via processor
        val state = _controlState.value
        val assistantMsgId = processor.createAssistantMessage(
            modelID = state.selectedModel?.modelID,
            providerID = state.selectedModel?.providerID
        )

        _isStreaming.value = true
        val deferred = CompletableDeferred<Unit>()
        responseDeferred = deferred

        try {
            // Build message parts
            val parts = mutableListOf<OpenCodePart>(OpenCodePart.Text(text = text))
            files.forEach { file ->
                parts.add(OpenCodePart.File(mime = file.mime, url = file.dataUri, filename = file.name))
            }

            // Derive optional request fields from control bar state
            val variant = state.thinkingEffort.variant // null for DEFAULT
            val agent = state.selectedAgent?.id
            val model = state.selectedModel?.let {
                OpenCodeClient.MessageModel(providerID = it.providerID, modelID = it.modelID)
            }

            // Send via OpenCodeClient (returns immediately with correlationId)
            client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)

            // Suspend until the SSE subscription delivers a Stop event
            deferred.await()
        } catch (e: CancellationException) {
            processor.completeStreaming(assistantMsgId)
        } catch (e: Exception) {
            // Route error to chat as a descriptive assistant message
            val errorMsg = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Request timed out. The OpenCode server at $host:$port did not respond in time. " +
                    "Check that the server is running and responsive."
                e.message?.contains("connect", ignoreCase = true) == true ->
                    "Connection lost to OpenCode server at $host:$port. " +
                    "The server may have stopped or become unreachable."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    "Connection refused by OpenCode server at $host:$port. " +
                    "Ensure the server is running on the correct port."
                else ->
                    "An error occurred while sending the message: ${e.message ?: e.javaClass.simpleName}"
            }
            processor.abortStreaming(errorMsg)
        } finally {
            _isStreaming.value = false
            responseDeferred = null
        }
    }

    /** Abort the current in-progress response. */
    suspend fun cancel() {
        val client = openCodeClient
        val currentSessionId = sessionId
        if (client != null && currentSessionId != null) {
            client.abortSession(currentSessionId)
        }
        // Complete any pending deferred so sendMessage() unblocks
        responseDeferred?.complete(Unit)
        responseDeferred = null
        _isStreaming.value = false
    }

    /** Respond to a permission prompt from the OpenCode engine. */
    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        val client = openCodeClient
        if (client != null) {
            try {
                client.respondPermission(
                    permissionId = prompt.permissionId,
                    response = response.optionId
                )
                when (response) {
                    PermissionResponse.REJECT_ONCE ->
                        processor.updateToolCallStatus(prompt.toolCallId, ToolCallStatus.FAILED)
                    PermissionResponse.ALLOW_ONCE,
                    PermissionResponse.ALLOW_ALWAYS ->
                        processor.updateToolCallStatus(prompt.toolCallId, ToolCallStatus.IN_PROGRESS)
                }
                _permissionPrompt.value = null
            } catch (_: Exception) {
                // Network error — keep prompt open for retry
            }
        }
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
    }

    fun respondSelection(response: SelectionResponse) {
        val prompt = _selectionPrompt.value ?: return
        val client = openCodeClient ?: return
        scope.launch {
            try {
                // Build answers array: one inner list per question.
                // The server expects the selected option *labels*, not indices.
                val selectedLabels = response.selectedIndices.mapNotNull { idx ->
                    prompt.options.getOrNull(idx)?.label
                }
                val answers = mutableListOf<List<String>>()
                if (selectedLabels.isNotEmpty()) {
                    answers.add(selectedLabels)
                }
                // Custom input is added as a separate answer entry if provided
                response.customInput?.let { answers.add(listOf(it)) }
                if (answers.isEmpty()) {
                    // Nothing selected and no custom input — reject the question
                    client.rejectQuestion(prompt.promptId)
                } else {
                    client.respondQuestion(prompt.promptId, answers)
                }
                _selectionPrompt.value = null
            } catch (e: Exception) {
                logger.warn(e) { "Failed to respond to question ${prompt.promptId}" }
            }
        }
    }



    override fun close() {
        reconnectJob?.cancel()
        reconnectJob = null
        sseJob?.cancel()
        sseJob = null
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
        responseDeferred?.complete(Unit)
        responseDeferred = null
        processor.close()
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        sessionId = null
        initialized = false
        // Kill the launched OpenCode process if we own it
        killProcess(openCodeProcess)
        openCodeProcess = null
    }

    // --- Reconnection ---

    /**
     * Triggers automatic reconnection with exponential backoff.
     * Called when the SSE stream ends unexpectedly.
     *
     * Strategy:
     * 1. Set state to RECONNECTING
     * 2. Health check with exponential backoff (1s → 2s → 4s → ... → 30s cap)
     * 3. If healthy, re-subscribe SSE and set state to CONNECTED
     * 4. If scope cancelled or client lost, set state to ERROR
     */
    private fun triggerReconnect() {
        reconnectJob?.cancel()
        reconnectAttempt = 0

        // Abort any in-flight streaming response — the SSE stream is gone
        processor.abortStreaming("Connection lost — reconnecting")
        responseDeferred?.complete(Unit)
        responseDeferred = null
        _isStreaming.value = false

        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            logger.info { "Starting reconnection..." }

            while (isActive && initialized) {
                val delayMs = calculateBackoff(reconnectAttempt)
                if (reconnectAttempt > 0) {
                    logger.info { "Reconnect attempt ${reconnectAttempt + 1}, waiting ${delayMs}ms" }
                    delay(delayMs)
                }

                val client = openCodeClient
                if (client == null) {
                    logger.warn { "No client available for reconnection" }
                    _connectionState.value = ConnectionState.ERROR
                    initialized = false
                    return@launch
                }

                // Health check
                try {
                    if (!client.healthCheck()) {
                        reconnectAttempt++
                        logger.warn { "Health check failed on attempt $reconnectAttempt" }
                        continue
                    }
                } catch (_: Exception) {
                    reconnectAttempt++
                    logger.warn { "Health check error on attempt $reconnectAttempt" }
                    continue
                }

                // Server is healthy — re-subscribe SSE
                val currentSessionId = sessionId
                if (currentSessionId != null) {
                    sseJob = startSseSubscription(client, currentSessionId)
                    logger.info { "SSE re-subscribed for session $currentSessionId" }
                }

                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
                logger.info { "Reconnected successfully" }
                return@launch
            }

            // Exited loop — scope cancelled or no longer initialized
            if (initialized && _connectionState.value == ConnectionState.RECONNECTING) {
                _connectionState.value = ConnectionState.ERROR
                initialized = false
            }
        }
    }

    /**
     * Calculates exponential backoff delay with jitter.
     * Base: 1s, doubles each attempt, capped at 30s.
     * Adds ±20% random jitter to prevent synchronization.
     */
    private fun calculateBackoff(attempt: Int): Long {
        val base = ChatConstants.RECONNECT_DELAY_MS
        val max = ChatConstants.RECONNECT_MAX_DELAY_MS
        val exponential = (base * (1L shl attempt.coerceAtMost(10))).coerceAtMost(max)
        // Add 0-20% random jitter
        val jitter = (exponential * kotlin.random.Random.nextDouble(0.0, 0.2)).toLong()
        return (exponential + jitter).coerceAtMost(max)
    }

    /**
     * Full re-initialization after connection failure.
     * Called by the "Retry" button in the ERROR connection banner.
     * Closes everything and starts fresh.
     */
    suspend fun retryConnection(projectBasePath: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        close()
        initialize(projectBasePath)
    }

    // --- Context Fetching ---

    /** Computes session context from in-memory messages + providers (like TUI's getSessionContextMetrics). */
    private suspend fun computeSessionContext() {
        val currentSessionId = sessionId ?: return
        val client = openCodeClient ?: return
        val messages = processor.messages.value

        // Fetch session details for summary (additions/deletions/filesModified) and timestamps
        val session = try {
            client.getSession(currentSessionId)
        } catch (_: Exception) {
            null
        }

        // Find last assistant message with non-zero tokens (matches TUI's lastAssistantWithTokens)
        val lastAssistant = messages.findLast {
            it.role == MessageRole.ASSISTANT && (it.inputTokens + it.outputTokens + it.reasoningTokens + it.cacheReadTokens + it.cacheWriteTokens) > 0
        }

        // Total cost across all assistant messages
        val totalCost = messages.filter { it.role == MessageRole.ASSISTANT }.sumOf { it.cost }

        // Resolve model + provider from last assistant message, then session, then selected model
        val modelId = lastAssistant?.modelID
            ?: _controlState.value.selectedModel?.modelID
        val providerId = lastAssistant?.providerID
            ?: _controlState.value.selectedModel?.providerID

        val (providerName, modelName) = resolveModelNames(modelId, providerId)
        val contextLimit = resolveContextLimit(providerId, modelId)

        // Token counts from last assistant message
        val inputTokens = lastAssistant?.inputTokens ?: 0L
        val outputTokens = lastAssistant?.outputTokens ?: 0L
        val reasoningTokens = lastAssistant?.reasoningTokens ?: 0L
        val cacheReadTokens = lastAssistant?.cacheReadTokens ?: 0L
        val cacheWriteTokens = lastAssistant?.cacheWriteTokens ?: 0L
        val totalTokens = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens

        val usagePercent = if (contextLimit > 0L) {
            (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        // Session title from sidebar list
        val sessionTitle = (_sessionListState.value as? SessionListState.Loaded)
            ?.sessions?.find { it.id == currentSessionId }?.title ?: "Untitled"

        _sessionContextState.value = SessionContextState.Loaded(
            context = SessionContext(
                sessionId = currentSessionId,
                title = sessionTitle,
                providerID = providerId ?: "",
                modelID = modelId ?: "",
                providerName = providerName,
                modelName = modelName,
                contextLimit = contextLimit,
                totalTokens = totalTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                usagePercent = usagePercent,
                totalCost = totalCost,
                messageCount = messages.size,
                userMessageCount = messages.count { it.role == MessageRole.USER },
                assistantMessageCount = messages.count { it.role == MessageRole.ASSISTANT },
                additions = session?.summary?.additions ?: 0,
                deletions = session?.summary?.deletions ?: 0,
                filesModified = session?.summary?.files ?: 0,
                sessionCreated = session?.time?.created ?: 0L,
                lastUpdated = session?.time?.updated ?: 0L
            )
        )
    }

    /** Recomputes session context from in-memory data. */
    fun retryContextFetch() {
        scope.launch { computeSessionContext() }
    }

    /**
     * Fetches the todo list for the current session from the server.
     * Called on session initialization and after each response completes.
     */
    private fun fetchTodos() {
        val currentSessionId = sessionId ?: return
        val client = openCodeClient ?: return
        scope.launch {
            try {
                val todos = client.getTodos(currentSessionId)
                _todoItems.value = todos.map { TodoItem(content = it.content, status = it.status, priority = it.priority) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silently fail — todos are not critical
            }
        }
    }

    /**
     * Records a sent command in the persistent history.
     * Called at the top of [sendMessage] before any network activity.
     * Deduplicates by text, moving the matching entry to the front.
     */
    private fun recordCommand(text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        val entry = CommandHistoryEntry(text = text, files = files)
        val maxSize = OpenCodeSettingsState.getInstance().commandHistorySize.coerceIn(1, 100)
        val current = _commandHistory.value.toMutableList()
        // Remove any existing duplicate (same text + same file paths + same data URIs) to move it to front
        current.removeAll { existing ->
            existing.text == text &&
                existing.attachedFilePaths.size == files.size &&
                existing.attachedFilePaths.zip(files.map { it.path }).all { (a, b) -> a == b } &&
                existing.attachedFileDataUris.size == files.size &&
                existing.attachedFileDataUris.zip(files.map { it.dataUri }).all { (a, b) -> a == b }
        }
        current.add(0, entry)
        val trimmed = if (current.size > maxSize) current.take(maxSize) else current
        _commandHistory.value = trimmed
        // Persist
        val settings = OpenCodeSettingsState.getInstance()
        settings.commandHistory = java.util.ArrayList(trimmed)
    }

    /**
     * Clears the input command history entirely.
     */
    fun clearCommandHistory() {
        _commandHistory.value = emptyList()
        OpenCodeSettingsState.getInstance().commandHistory = java.util.ArrayList()
    }

    /**
     * Fetches available slash commands from the OpenCode server.
     * Called after client initialization and after session switch.
     */
    fun fetchAvailableCommands() {
        val client = openCodeClient ?: return
        scope.launch {
            try {
                val commands = client.listCommands()
                _availableCommands.value = commands.map { cmd ->
                    SlashCommand(
                        name = cmd.id,
                        description = cmd.description ?: cmd.name,
                        iconKey = null,
                        isServerCommand = true
                    )
                }
                logger.info { "Loaded ${commands.size} server commands" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch available commands" }
            }
        }
    }

    /**
     * Executes a server-side slash command.
     */
    fun executeServerCommand(commandName: String, args: String = "") {
        val currentSessionId = sessionId ?: return
        val client = openCodeClient ?: return
        scope.launch {
            try {
                client.executeCommand(currentSessionId, commandName, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to execute command: $commandName" }
            }
        }
    }

    /**
     * Resolves provider + model IDs to display names using the loaded provider list.
     * Falls back to raw IDs if provider data is unavailable.
     */
    private fun resolveModelNames(modelId: String?, providerId: String?): Pair<String, String> {
        if (modelId.isNullOrBlank() && providerId.isNullOrBlank()) return Pair("Unknown", "Unknown")

        val models = _controlState.value.models
        if (models.isEmpty()) return Pair(providerId ?: "Unknown", modelId ?: "Unknown")

        // Try exact match first (both provider and model must match)
        val exactMatch = models.find {
            it.providerID == providerId && it.modelID == modelId
        }
        if (exactMatch != null) {
            val parts = exactMatch.displayName.split(" / ", limit = 2)
            return Pair(parts.getOrElse(0) { exactMatch.providerID }, parts.getOrElse(1) { exactMatch.modelID })
        }

        // Try exact match by model ID only (provider may differ)
        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null) {
            val parts = modelOnlyMatch.displayName.split(" / ", limit = 2)
            return Pair(parts.getOrElse(0) { modelOnlyMatch.providerID }, parts.getOrElse(1) { modelOnlyMatch.modelID })
        }

        // Fall back to raw IDs
        return Pair(providerId ?: "Unknown", modelId ?: "Unknown")
    }

    /**
     * Resolves the context window limit from the loaded provider model data.
     * Uses allModels (including disconnected providers) for context limit lookup.
     * Returns 0 to indicate "unknown" if the model isn't found.
     */
    private fun resolveContextLimit(providerId: String?, modelId: String?): Long {
        if (modelId.isNullOrBlank()) return 0L
        // Use allModels (includes disconnected providers) for context limit lookup
        val models = _controlState.value.allModels.ifEmpty { _controlState.value.models }
        if (models.isEmpty()) return 0L

        // Try exact match first (both provider and model)
        val exactMatch = models.find {
            it.providerID == providerId && it.modelID == modelId
        }
        if (exactMatch != null && exactMatch.contextWindow > 0) {
            return exactMatch.contextWindow.toLong()
        }

        // Try match by model ID only
        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null && modelOnlyMatch.contextWindow > 0) {
            return modelOnlyMatch.contextWindow.toLong()
        }

        return 0L
    }

    // --- SSE Event Handling ---

    private fun handleSseEvent(event: SseEvent) {
        processor.process(event)
    }

    // --- State Mutations ---

    /**
     * Injects [SubagentRef] objects into the most recent assistant message
     * that precedes any child sessions. Child sessions are mapped to the latest
     * assistant message in the conversation flow.
     */
    private fun injectSubagentRefs(
        messages: List<ChatMessage>,
        children: List<SessionItem>
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val lastAssistantIdx = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIdx == -1) return messages
        val refs = children.map { child ->
            SubagentRef(
                sessionId = child.id,
                agentName = child.title.replaceFirstChar { it.uppercase() },
                taskDescription = child.title,
                status = if (child.outputTokens > 0) SubagentStatus.COMPLETED else SubagentStatus.RUNNING,
            )
        }
        val updated = messages.toMutableList()
        val msg = updated[lastAssistantIdx]
        val nonSubagentParts = msg.parts.filter { it !is MessagePart.Subagent }
        val subagentParts = refs.map { MessagePart.Subagent(it) }
        updated[lastAssistantIdx] = msg.copy(parts = nonSubagentParts + subagentParts)
        return updated
    }

    private fun startPermissionTimeout() {
        permissionTimeoutJob?.cancel()
        val timeoutSeconds = OpenCodeSettingsState.getInstance().state.permissionTimeoutSeconds
        if (timeoutSeconds <= 0) return
        permissionTimeoutJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            _permissionPrompt.value = null
        }
    }
}
