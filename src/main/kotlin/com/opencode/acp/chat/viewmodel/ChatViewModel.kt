package com.opencode.acp.chat.viewmodel

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodePart
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.chat.util.renderMarkdownToHtml
import com.opencode.acp.config.AcpDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.Closeable

class ChatViewModel(
    private val scope: CoroutineScope
) : Closeable {

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

    // --- Internal: OpenCode engine connection ---
    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    private var sessionId: String? = null
    private var sseJob: Job? = null

    /** Tracks the assistant message currently being streamed. */
    private var activeAssistantMessageId: String? = null

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

    /** Configure the OpenCode server connection parameters before initialize(). */
    fun configure(
        host: String = AcpDefaults.DEFAULT_OPENCODE_HOST,
        port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT,
        authToken: String? = null
    ) {
        this.host = host
        this.port = port
        this.authToken = authToken
    }

    /** Initialise the connection: create client, health check, create session, load agents/providers. */
    suspend fun initialize(projectBasePath: String) {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            // Create HTTP client and OpenCodeClient
            val client = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                    })
                }
            }
            httpClient = client

            val opencodeClient = OpenCodeClient(
                baseUrl = "http://$host:$port",
                httpClient = client,
                authToken = authToken
            )
            openCodeClient = opencodeClient

            // Health check
            if (!opencodeClient.healthCheck()) {
                _connectionState.value = ConnectionState.ERROR
                return
            }

            // Create session
            val session = opencodeClient.createSession(cwd = projectBasePath)
            sessionId = session.id

            // Load agents
            try {
                val agents = opencodeClient.listAgents()
                _controlState.value = _controlState.value.copy(
                    agents = agents.map { info ->
                        OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                    }
                )
            } catch (_: Exception) {
                // Non-fatal: agents list is optional
            }

            // Load providers and models
            try {
                val providerResponse = opencodeClient.listProviders()
                val models = providerResponse.all.flatMap { provider ->
                    provider.models.map { (_, modelData) ->
                        ProviderModel(
                            providerID = provider.id,
                            modelID = modelData.id,
                            displayName = "${provider.name} / ${modelData.name}",
                            reasoning = modelData.reasoning
                        )
                    }
                }
                _controlState.value = _controlState.value.copy(
                    models = models,
                    selectedModel = models.firstOrNull()
                )
            } catch (_: Exception) {
                // Non-fatal: providers list is optional
            }

            // Subscribe to global SSE events (long-lived background subscription)
            val currentSessionId = sessionId ?: return
            sseJob = scope.launch {
                opencodeClient.subscribeGlobalEvents()
                    .filter { event -> event.sessionId == currentSessionId }
                    .collect { event -> handleSseEvent(event) }
            }

            _connectionState.value = ConnectionState.CONNECTED
        } catch (_: Exception) {
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun selectAgent(agent: OpenCodeAgentInfo) {
        _controlState.value = _controlState.value.copy(selectedAgent = agent)
    }

    fun selectModel(model: ProviderModel) {
        _controlState.value = _controlState.value.copy(selectedModel = model)
    }

    fun selectThinkingEffort(effort: ThinkingEffort) {
        _controlState.value = _controlState.value.copy(thinkingEffort = effort)
    }

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Send a user message to the OpenCode engine. Suspends until the response is complete. */
    suspend fun sendMessage(text: String) {
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

        _isStreaming.value = true
        val deferred = CompletableDeferred<Unit>()
        responseDeferred = deferred

        try {
            // Build message parts
            val parts = listOf(OpenCodePart.Text(text = text))

            // Send via OpenCodeClient (returns immediately with correlationId)
            client.sendMessageAsync(currentSessionId, parts)

            // Suspend until the SSE subscription delivers a Stop event
            deferred.await()
        } catch (_: CancellationException) {
            markStreamingComplete(assistantMsg.id)
        } finally {
            _isStreaming.value = false
            responseDeferred = null
            activeAssistantMessageId = null
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
        val currentSessionId = sessionId
        if (client != null && currentSessionId != null) {
            client.respondPermission(
                sessionId = currentSessionId,
                permissionId = prompt.permissionId,
                response = response.optionId
            )
        }
        _permissionPrompt.value = null
    }

    override fun close() {
        sseJob?.cancel()
        sseJob = null
        responseDeferred?.complete(Unit)
        responseDeferred = null
        activeAssistantMessageId = null
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        sessionId = null
    }

    // --- SSE Event Handling ---

    private fun handleSseEvent(event: SseEvent) {
        val msgId = activeAssistantMessageId ?: return

        when (event) {
            is SseEvent.TextChunk -> {
                appendTextToMessage(msgId, event.text)
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
                // Create a permission prompt for the user to review
                val prompt = PermissionPrompt(
                    permissionId = event.toolCallId,  // Use toolCallId as the permission identifier
                    toolCallId = event.toolCallId,
                    toolName = event.action,
                    description = event.description,
                    options = emptyList()
                )
                _permissionPrompt.value = prompt
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

    private fun appendTextToMessage(messageId: String, text: String) {
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(content = msg.content + text)
        _messages.value = messages
    }

    private fun markStreamingComplete(messageId: String) {
        val messages = _messages.value.toMutableList()
        val index = messageIndex[messageId] ?: return
        val msg = messages[index]
        messages[index] = msg.copy(
            isStreaming = false,
            renderedHtml = renderMarkdownToHtml(msg.content)
        )
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
}
