package com.opencode.acp.chat.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.MessageProcessorManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.chat.model.ConnectionState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Project-level service that owns the OpenCode connection, session management,
 * and message processor. Survives tool window disposal/recreation.
 *
 * Architecture:
 * - [OpenCodeConnectionManager] — HTTP/SSE connection lifecycle
 * - [SessionManager] — session CRUD, switching, context
 * - [MessageProcessorManager] — event processing, message accumulation
 * - This service coordinates between them and exposes the public API
 *
 * The [ChatViewModel] is a thin UI wrapper that delegates here.
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Sub-components ─────────────────────────────────────────────────────

    val connectionManager = OpenCodeConnectionManager(scope)
    val processor = MessageProcessorManager(scope)
    val sessionManager: SessionManager = createSessionManager()
    val commandManager = CommandManager(
        clientProvider = { connectionManager.client },
        sessionIdProvider = { sessionManager.sessionId }
    )
    val permissionManager = PermissionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        processor = processor
    )

    // ── State flows ────────────────────────────────────────────────────────

    val messages: StateFlow<Map<String, ChatMessage>> = processor.messages
    val signals: SharedFlow<UiSignal> = processor.signals
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val sessionListState: StateFlow<SessionListState> = sessionManager.sessionListState
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = sessionManager.childSessionMap
    val todoItems: StateFlow<List<TodoItem>> = sessionManager.todoItems
    val sessionContextState: StateFlow<SessionContextState> = sessionManager.sessionContextState

    // ── Internal state ─────────────────────────────────────────────────────

    private var responseDeferred: CompletableDeferred<Unit>? = null
    private var signalCollectionJob: Job? = null

    /**
     * Serializes sendMessage() calls. Without this, a rapid double-send (e.g. user
     * double-clicks send, or sends a message before the first response completes)
     * would cause the second call to overwrite [responseDeferred], orphaning the
     * first call's deferred which then hangs in `deferred.await()` until the
     * 5-minute timeout.
     */
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    // ── Wires ──────────────────────────────────────────────────────────────

    /** Build the SessionManager with callbacks into this service. */
    private fun createSessionManager(): SessionManager {
        return SessionManager(
            scope = scope,
            clientProvider = { connectionManager.client },
            processor = processor,
            onBeforeReset = { targetId ->
                connectionManager.resetReconnectState()
                connectionManager.cancelSseSubscription()
                // Clear the active session marker before resetting processor state.
                // This ensures any in-flight reconnect check for the old session
                // sees it as inactive and bails out. The new session ID is set
                // in onAfterSseSetup just before the new subscription starts.
                connectionManager.setActiveSseSession(null)
                responseDeferred?.complete(Unit)
                responseDeferred = null
            },
            onAfterSseSetup = { targetId ->
                connectionManager.cancelSseSubscription()
                // Mark the new active session before starting the new subscription
                // so isActiveSession() returns true for the new session during the
                // connection setup, and false for the old session (if any) during
                // its pending teardown.
                connectionManager.setActiveSseSession(targetId)
                startSseSubscription(targetId)
            }
        )
    }

    /** Session ID convenience accessor. */
    val sessionId: String? get() = sessionManager.sessionId

    // ── Signal collection (completes responseDeferred on Stop) ──────────

    private fun startSignalCollection() {
        signalCollectionJob?.cancel()
        signalCollectionJob = scope.launch {
            processor.signals.collect { signal ->
                when (signal) {
                    is UiSignal.StreamingCompleted -> {
                        responseDeferred?.complete(Unit)
                        responseDeferred = null
                    }
                    else -> { /* other signals handled by ViewModel */ }
                }
            }
        }
    }

    // ── Initialization ─────────────────────────────────────────────────────

    /**
     * Initialize the connection and load sessions.
     * Called once when the service is first accessed.
     */
    suspend fun initialize(projectBasePath: String = "."): Boolean {
        logger.info { "[ACP] OpenCodeService.initialize: START (projectBasePath=$projectBasePath)" }
        val connected = connectionManager.initialize(projectBasePath)
        if (!connected) {
            logger.warn { "[ACP] OpenCodeService.initialize: connectionManager.initialize() returned false" }
            return false
        }
        logger.info { "[ACP] OpenCodeService.initialize: connection established" }

        startSignalCollection()

        logger.info { "[ACP] OpenCodeService.initialize: loading sessions..." }
        sessionManager.loadSessions()
        logger.info { "[ACP] OpenCodeService.initialize: sessions state = ${sessionListState.value::class.simpleName}" }

        when (val state = sessionListState.value) {
            is SessionListState.Loaded -> {
                if (state.sessions.isNotEmpty()) {
                    sessionManager.switchSession(state.sessions.first().id)
                }
            }
            is SessionListState.Error -> {
                sessionManager.loadSessions()
                val retry = sessionListState.value as? SessionListState.Loaded
                if (retry != null && retry.sessions.isNotEmpty()) {
                    sessionManager.switchSession(retry.sessions.first().id)
                }
            }
            is SessionListState.Loading -> { }
        }

        return true
    }

    // ── Session management (delegated) ─────────────────────────────────────

    suspend fun loadSessions() = sessionManager.loadSessions()
    suspend fun switchSession(sessionId: String) = sessionManager.switchSession(sessionId)
    suspend fun createAndSwitchSession(title: String? = null) = sessionManager.createAndSwitchSession(title)
    suspend fun archiveSession(sessionId: String) = sessionManager.archiveSession(sessionId)

    // ── SSE subscription ──────────────────────────────────────────────────

    private fun startSseSubscription(targetSessionId: String) {
        connectionManager.startSseSubscription(targetSessionId) { event ->
            handleSseEvent(event)
        }
    }

    private suspend fun handleSseEvent(event: SseEvent) {
        val summary = when (event) {
            is SseEvent.TextChunk -> "TextChunk(sid=${event.sessionId}, mid=${event.messageId}, text=${event.text.take(30)})"
            is SseEvent.ThinkingChunk -> "ThinkingChunk(sid=${event.sessionId}, mid=${event.messageId}, text=${event.text.take(30)})"
            is SseEvent.Stop -> "Stop(sid=${event.sessionId}, mid=${event.messageId}, reason=${event.stopReason})"
            is SseEvent.Error -> "Error(sid=${event.sessionId}, mid=${event.messageId}, msg=${event.message.take(50)})"
            is SseEvent.ToolUse -> "ToolUse(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.ToolResult -> "ToolResult(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.Permission -> "Permission(sid=${event.sessionId}, mid=${event.messageId}, tool=${event.toolCallId})"
            is SseEvent.Ignored -> "Ignored(type=${event.eventType}, reason=${event.reason})"
            else -> "${event::class.simpleName}(sid=${event.sessionId}, mid=${event.messageId})"
        }
        logger.info { "[ACP] handleSseEvent: $summary" }
        processor.process(event)
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
        // Serialize concurrent sends. `tryLock` returns false immediately if another
        // send is in progress, avoiding both:
        //   1. The double-click race that orphans the first responseDeferred.
        //   2. A second coroutine blocking on sendMutex while the user is mid-send.
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
        val currentSessionId = sessionManager.sessionId ?: return SendMessageResult.Error("No session")
        logger.info { "[ACP] sendMessage: START session=$currentSessionId text='${text.take(50)}'" }

        val userMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.USER,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            attachedFiles = files
        )
        processor.addMessage(userMsg)
        processor.setLastUserText(text)

        connectionManager.client?.resetReasoningTracking()
        val assistantMsgId = processor.createAssistantMessage(
            modelID = modelID,
            providerID = providerID,
            serverMessageId = null
        )

        val deferred = CompletableDeferred<Unit>()
        responseDeferred = deferred

        try {
            val parts = mutableListOf<com.opencode.acp.adapter.OpenCodePart>(com.opencode.acp.adapter.OpenCodePart.Text(text = text))
            files.forEach { file ->
                // Read file content for source.text so the LLM knows the file content and path
                val fileText = try {
                    java.io.File(file.path).readText(Charsets.UTF_8)
                } catch (_: Exception) { null }
                val sourceText = fileText?.let { txt ->
                    com.opencode.acp.adapter.OpenCodePart.FileSourceText(value = txt, start = 0, end = txt.length)
                }
                parts.add(com.opencode.acp.adapter.OpenCodePart.File(
                    mime = file.mime, url = file.dataUri, filename = file.name,
                    source = com.opencode.acp.adapter.OpenCodePart.FileSource(path = file.path, text = sourceText)
                ))
            }
            logger.info { "[ACP] sendMessage: ${parts.size} parts (text + ${files.size} file attachments: ${files.joinToString { it.name }})" }

            val serverMessageId = client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)
            logger.info { "[ACP] sendMessage: got serverMessageId=$serverMessageId" }

            processor.updateServerMessageId(assistantMsgId, serverMessageId)

            withTimeout(300_000L) {
                deferred.await()
            }
            return SendMessageResult.Success(assistantMsgId)
        } catch (e: TimeoutCancellationException) {
            logger.error(e) { "[ACP] sendMessage: SSE response timed out after 5 minutes" }
            processor.abortStreaming("Response timed out after 5 minutes.")
            return SendMessageResult.Error("Response timed out")
        } catch (e: CancellationException) {
            processor.completeStreaming(assistantMsgId)
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
            processor.abortStreaming(errorMsg)
            return SendMessageResult.Error(errorMsg)
        } finally {
            responseDeferred = null
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    suspend fun cancel() {
        val client = connectionManager.client
        val currentSessionId = sessionManager.sessionId
        if (client != null && currentSessionId != null) {
            client.abortSession(currentSessionId)
        }
        responseDeferred?.complete(Unit)
        responseDeferred = null
    }

    suspend fun respondPermission(permissionId: String, toolCallId: String, response: PermissionResponse) =
        permissionManager.respondPermission(permissionId, toolCallId, response)

    suspend fun respondQuestion(promptId: String, answers: List<List<String>>) =
        permissionManager.respondQuestion(promptId, answers)

    suspend fun rejectQuestion(promptId: String) =
        permissionManager.rejectQuestion(promptId)

    // ── Data fetching ──────────────────────────────────────────────────────

    suspend fun computeSessionContext(controlState: ControlBarState? = null) {
        sessionManager.computeSessionContext(controlState)
    }

    suspend fun fetchTodos() = sessionManager.fetchTodos()

    suspend fun fetchAvailableCommands(): List<SlashCommand> =
        commandManager.fetchAvailableCommands()

    suspend fun executeServerCommand(commandName: String, args: String = "") =
        commandManager.executeServerCommand(commandName, args)

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

    suspend fun retryConnection() {
        connectionManager.disconnect()
        initialize()
    }

    override fun dispose() {
        logger.info { "[ACP] OpenCodeService.dispose() called — project closing" }
        permissionManager.dispose()
        responseDeferred?.complete(Unit)
        responseDeferred = null
        connectionManager.shutdown()
        processor.close()
        scope.cancel()
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
