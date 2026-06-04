package com.opencode.acp.chat.viewmodel

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodePart
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.ui.compose.AttachedFile
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.Closeable

class ChatViewModel(
    private val scope: CoroutineScope
) : Closeable {

    private val logger = KotlinLogging.logger {}

    // --- State ---
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt.asStateFlow()

    /** Signal emitted by the Ctrl+V IntelliJ action to trigger clipboard image paste in Compose. */
    private val _pasteImageSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pasteImageSignal = _pasteImageSignal.asSharedFlow()

    /** Signal emitted when Ctrl+V finds text on the clipboard. Carries the text to insert. */
    private val _pasteTextSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pasteTextSignal = _pasteTextSignal.asSharedFlow()

    /** Emits a signal to check the clipboard for an image. Called by the IntelliJ Ctrl+V action. */
    fun requestImagePaste() {
        _pasteImageSignal.tryEmit(Unit)
    }

    /** Emits a signal to insert text into the input field. Called by the paste handler. */
    fun requestTextPaste(text: String) {
        _pasteTextSignal.tryEmit(text)
    }

    // --- Internal: OpenCode engine connection ---
    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    private var sessionId: String? = null
    private var sseJob: Job? = null
    private var openCodeProcess: Process? = null
    private var permissionTimeoutJob: Job? = null

    /** Tracks the assistant message currently being streamed. */
    private var activeAssistantMessageId: String? = null

    /** Text of the last user message — used to strip echo from assistant response. */
    private var lastUserText: String? = null

    /** Deferred completed when the current response finishes (Stop event). */
    private var responseDeferred: CompletableDeferred<Unit>? = null

    // --- Index maps for O(1) message/tool lookups ---
    private val toolCallIndex = mutableMapOf<String, String>()  // toolCallId → messageId
    private val messageIndex = mutableMapOf<String, Int>()       // messageId → position

    // --- Configuration ---
    private var host: String = AcpDefaults.DEFAULT_OPENCODE_HOST
    private var port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT
    private var authToken: String? = null

    // --- Public API ---

    /** Read settings from persistent state and update local fields. */
    private fun loadSettings() {
        // host, port, authToken use AcpDefaults — no user configuration needed
    }

    /** Launch the OpenCode binary as a subprocess if auto-launch is enabled. */
    private fun launchOpenCodeBinary(binaryPath: String) {
        if (openCodeProcess?.isAlive == true) {
            logger.info { "OpenCode process already running, skipping launch" }
            return
        }

        try {
            logger.info { "Launching: $binaryPath serve --host $host --port $port" }
            val pb = ProcessBuilder(binaryPath, "serve", "--host", host, "--port", port.toString())
                .redirectErrorStream(true)
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to launch OpenCode binary at $binaryPath" }
        }
    }

    /** Initialise the connection: launch binary, create client, health check, create session. */
    suspend fun initialize(projectBasePath: String) {
        logger.info { "Initializing connection to OpenCode at $host:$port" }
        _connectionState.value = ConnectionState.CONNECTING
        try {
            // Load persisted settings
            loadSettings()

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

            // Create session (agent is per-message, not per-session)
            logger.info { "Creating session" }
            val session = opencodeClient.createSession()
            sessionId = session.id
            logger.info { "Session created: ${session.id}" }

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
                logger.info { "Loaded ${models.size} models from ${providerResponse.all.size} providers" }
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
                    selectedModel = restoredModel ?: models.firstOrNull()
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load providers (non-fatal)" }
            }

            // Subscribe to global SSE events (long-lived background subscription)
            val currentSessionId = sessionId ?: return
            logger.info { "Subscribing to SSE events for session $currentSessionId" }
            sseJob = scope.launch {
                opencodeClient.subscribeGlobalEvents()
                    .filter { event -> event.sessionId == currentSessionId }
                    .collect { event -> handleSseEvent(event) }
            }

            _connectionState.value = ConnectionState.CONNECTED
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

    /** Send a user message to the OpenCode engine. Suspends until the response is complete. */
    suspend fun sendMessage(text: String, files: List<AttachedFile> = emptyList()) {
        val client = openCodeClient ?: return
        val currentSessionId = sessionId ?: return

        // Add user message to chat
        val userMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.USER,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        addMessage(userMsg)
        lastUserText = text

        // Create streaming assistant message (initially empty)
        val assistantMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            timestamp = System.currentTimeMillis()
        )
        addMessage(assistantMsg)
        activeAssistantMessageId = assistantMsg.id
        firstTextChunkReceived = false

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
            val state = _controlState.value
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
            markStreamingComplete(assistantMsg.id)
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
            appendTextToMessage(assistantMsg.id, "\n\n**Error:** $errorMsg")
            markStreamingComplete(assistantMsg.id)
        } finally {
            _isStreaming.value = false
            responseDeferred = null
            activeAssistantMessageId = null
            firstTextChunkReceived = false
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
        firstTextChunkReceived = false
    }

    /** Respond to a permission prompt from the OpenCode engine. */
    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        val client = openCodeClient
        val currentSessionId = sessionId
        if (client != null && currentSessionId != null) {
            try {
                client.respondPermission(
                    sessionId = currentSessionId,
                    permissionId = prompt.permissionId,
                    response = response.optionId
                )
                when (response) {
                    PermissionResponse.REJECT_ONCE ->
                        updateToolCallStatus(prompt.toolCallId, ToolCallStatus.FAILED)
                    PermissionResponse.ALLOW_ONCE,
                    PermissionResponse.ALLOW_ALWAYS ->
                        updateToolCallStatus(prompt.toolCallId, ToolCallStatus.IN_PROGRESS)
                }
                _permissionPrompt.value = null
            } catch (_: Exception) {
                // Network error — keep prompt open for retry
            }
        }
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
    }

    override fun close() {
        sseJob?.cancel()
        sseJob = null
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
        responseDeferred?.complete(Unit)
        responseDeferred = null
        activeAssistantMessageId = null
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        sessionId = null
        // Kill the launched OpenCode process if we own it
        openCodeProcess?.destroyForcibly()
        openCodeProcess = null
    }

    // --- SSE Event Handling ---

    private fun handleSseEvent(event: SseEvent) {
        val msgId = activeAssistantMessageId ?: return

        when (event) {
            is SseEvent.TextChunk -> {
                appendTextToMessage(msgId, event.text)
            }

            is SseEvent.ThinkingChunk -> {
                appendThinkingContent(msgId, event.text)
            }

            is SseEvent.ToolUse -> {
                val pill = ToolCallPill(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    title = event.title ?: event.toolName,
                    kind = ToolKind.OTHER,
                    status = ToolCallStatus.IN_PROGRESS
                )
                toolCallIndex[event.toolCallId] = msgId
                addToolCallPill(msgId, pill)
            }

            is SseEvent.ToolResult -> {
                val targetMessageId = toolCallIndex[event.toolCallId] ?: msgId
                val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
                updateToolCallStatus(targetMessageId, event.toolCallId, resolvedStatus)
            }

            is SseEvent.Stop -> {
                markStreamingComplete(msgId)
                responseDeferred?.complete(Unit)
            }

            is SseEvent.Permission -> {
                updateToolCallStatus(event.toolCallId, ToolCallStatus.PENDING)
                val prompt = PermissionPrompt(
                    permissionId = event.toolCallId,
                    toolCallId = event.toolCallId,
                    toolName = event.action,
                    description = event.description
                )
                _permissionPrompt.value = prompt
                startPermissionTimeout()
            }

            is SseEvent.Error -> {
                appendTextToMessage(msgId, "\n\n**Error:** ${event.message}")
                markStreamingComplete(msgId)
                responseDeferred?.complete(Unit)
            }

            is SseEvent.Plan -> {
                // Plan events are informational; could be surfaced in UI later
            }

            is SseEvent.SessionCreated -> {
                // Already handled during initialization
            }

            is SseEvent.MessageComplete -> {
                // Message fully persisted on the server side
            }
        }
    }

    // --- State Mutations ---

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
        messageIndex[message.id] = _messages.value.size - 1
        // FIFO eviction
        if (_messages.value.size > ChatConstants.MAX_MESSAGE_HISTORY) {
            val evictCount = _messages.value.size - ChatConstants.MAX_MESSAGE_HISTORY
            val evicted = _messages.value.take(evictCount)
            val remaining = _messages.value.drop(evictCount)
            evicted.forEach { messageIndex.remove(it.id) }
            _messages.value = remaining
            messageIndex.clear()
            _messages.value.forEachIndexed { i, msg -> messageIndex[msg.id] = i }
        }
    }

    /** Whether the first non-empty text chunk for the current assistant response has been received. */
    private var firstTextChunkReceived = false

    private fun appendTextToMessage(messageId: String, text: String) {
        val toAppend = if (!firstTextChunkReceived && text.isNotBlank()) {
            firstTextChunkReceived = true
            val userText = lastUserText
            if (userText != null && text.startsWith(userText, ignoreCase = true)) {
                text.substring(userText.length)
            } else {
                text
            }
        } else {
            text
        }
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(content = msg.content + toAppend)
        _messages.value = messages
    }

    private fun appendThinkingContent(messageId: String, text: String) {
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(thinkingContent = msg.thinkingContent + text)
        _messages.value = messages
    }

    private fun markStreamingComplete(messageId: String) {
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(isStreaming = false)
        _messages.value = messages
    }

    private fun addToolCallPill(messageId: String, pill: ToolCallPill) {
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(toolCalls = msg.toolCalls + pill)
        _messages.value = messages
    }

    private fun updateToolCallStatus(messageId: String, toolCallId: String, status: ToolCallStatus?) {
        if (status == null) return
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        val updatedPills = msg.toolCalls.map { pill ->
            if (pill.toolCallId == toolCallId) pill.copy(status = status) else pill
        }
        messages[index] = msg.copy(toolCalls = updatedPills)
        _messages.value = messages
    }

    private fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus) {
        val messageId = toolCallIndex[toolCallId] ?: return
        updateToolCallStatus(messageId, toolCallId, status)
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
