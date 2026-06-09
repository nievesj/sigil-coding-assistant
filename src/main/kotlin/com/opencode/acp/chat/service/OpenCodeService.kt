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
import com.opencode.acp.chat.util.EDT
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
 * - [SessionManager] — per-session state ownership, SSE routing, session lifecycle
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
    val sessionManager = SessionManager(scope)
    val commandManager = CommandManager(
        clientProvider = { connectionManager.client },
        sessionIdProvider = { sessionManager.activeSessionId.value }
    )
    val permissionManager = PermissionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager
    )

    // ── State flows ────────────────────────────────────────────────────────

    val messages: StateFlow<Map<String, ChatMessage>> = sessionManager.activeMessages
    val signals: SharedFlow<UiSignal> = sessionManager.activeSignals
    val globalSignals: SharedFlow<UiSignal> = sessionManager.globalSignals
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val sessionListState: StateFlow<SessionListState> = sessionManager.sessionListState
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = sessionManager.childSessionMap
    val todoItems: StateFlow<List<TodoItem>> = sessionManager.todoItems
    val sessionContextState: StateFlow<SessionContextState> = sessionManager.sessionContextState
    val streamingSessionIds: StateFlow<Set<String>> = sessionManager.streamingSessionIds
    val pendingCreationSessionIds: StateFlow<Set<String>> = sessionManager.pendingCreationSessionIds
    val sessionCachedFlow: kotlinx.coroutines.flow.SharedFlow<String> = sessionManager.sessionCachedFlow

    // ── Internal state ─────────────────────────────────────────────────────

    private var signalCollectionJob: Job? = null

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

        return true
    }

    // ── Session management (delegated) ─────────────────────────────────────

    suspend fun loadSessions() = sessionManager.loadSessions()
    suspend fun switchSession(sessionId: String) = sessionManager.switchSession(sessionId)
    suspend fun createAndSwitchSession(title: String? = null) = sessionManager.createAndSwitchSession(title)
    suspend fun archiveSession(sessionId: String) = sessionManager.archiveSession(sessionId)

    // ── Connection stop ─────────────────────────────────────────────────────

    /** Stop all connection activity: cancel SSE reconnection loop, cancel SSE
     *  subscription, and disconnect the HTTP client. Called by the "Stop" button
     *  on the splash screen when the user wants to abort reconnection. */
    fun stopConnection() {
        sseReconnectJob?.cancel()
        sseReconnectJob = null
        sseJob?.cancel()
        sseJob = null
        connectionManager.disconnect()
    }

    // ── SSE subscription (single global, routes by sessionId) ───────────────

    private var sseJob: Job? = null
    private var sseReconnectJob: Job? = null
    private var sseReconnectAttempt: Int = 0

    /** Single global SSE subscription — all events routed to SessionManager.processEvent().
     *  Includes automatic reconnection with exponential backoff on stream end. */
    private fun startGlobalSseSubscription() {
        sseJob?.cancel()
        sseReconnectJob?.cancel()
        val client = connectionManager.client ?: return
        sseJob = scope.launch {
            try {
                client.subscribeGlobalEvents().collect { event ->
                    handleSseEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // SSE error — stream ended
            }
            // Stream ended without cancellation — trigger reconnection
            if (isActive) {
                logger.info { "[ACP] Global SSE stream ended — triggering reconnection" }
                triggerGlobalSseReconnect()
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
                val base = com.opencode.acp.chat.model.ChatConstants.RECONNECT_DELAY_MS
                val max = com.opencode.acp.chat.model.ChatConstants.RECONNECT_MAX_DELAY_MS
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
                sseJob?.cancel()  // Cancel old job before creating new one to prevent duplicate event processing
                sseJob = scope.launch {
                    try {
                        client.subscribeGlobalEvents().collect { event ->
                            handleSseEvent(event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (isActive) {
                            logger.info { "[ACP] Global SSE stream ended again — triggering reconnection" }
                            triggerGlobalSseReconnect()
                        }
                    }
                }

                // Recover background sessions that may have completed during disconnection
                recoverBackgroundSessions()

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
     *  5-minute timeout.
     *
     *  ASSUMPTION: The server's REST API (listMessages) only returns completed
     *  messages — in-progress messages are streamed via SSE, not available via
     *  REST. If this assumption is wrong, a still-generating session could be
     *  incorrectly finalized. A per-session status endpoint (GET /session/status)
     *  would be more reliable if available. */
    private suspend fun recoverBackgroundSessions() {
        val client = connectionManager.client ?: return
        val streamingSessions = sessionManager.getStreamingSessions()
        if (streamingSessions.isEmpty()) return

        logger.info { "[ACP] recoverBackgroundSessions: checking ${streamingSessions.size} streaming sessions" }

        for (session in streamingSessions) {
            val sessionId = session.sessionId
            try {
                // Re-fetch recent messages to check if the session completed
                val messages = client.listMessages(sessionId, limit = 5)
                val lastMessage = messages.lastOrNull()
                val lastIsAssistant = lastMessage?.info?.role == "assistant"

                if (lastIsAssistant) {
                    // Server has an assistant message as the last message —
                    // generation completed while we were disconnected.
                    val activeMsgId = session.ctx.activeMessageId
                    if (activeMsgId != null) {
                        // completeStreaming() acquires stateLock (a JVM ReentrantLock) and
                        // does CPU-intensive markdown segmentation. Must run on EDT to avoid
                        // blocking the EDT event processing coroutine that also acquires stateLock.
                        withContext(Dispatchers.EDT) {
                            session.completeStreaming(activeMsgId)
                        }
                    }
                    session.responseDeferred?.complete(Unit)
                    session.responseDeferred = null
                    logger.info { "[ACP] Recovered background session $sessionId after SSE reconnection" }
                }
                // If last message is not assistant, session may still be generating — leave it
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] Failed to recover session $sessionId" }
            }
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
        logger.info { "[ACP] sendMessage: START session=$currentSessionId text='${text.take(50)}'" }

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
                // source is only included when we have a valid file path AND readable content.
                // Server requires source.text when source is present, but source itself is optional.
                // Clipboard images have path="" — skip source entirely.
                val source = if (file.path.isNotBlank()) {
                    val fileText = try {
                        java.io.File(file.path).readText(Charsets.UTF_8)
                    } catch (_: Exception) { null }
                    val sourceText = fileText?.let { txt ->
                        com.opencode.acp.adapter.OpenCodePart.FileSourceText(value = txt, start = 0, end = txt.length)
                    }
                    if (sourceText != null) {
                        com.opencode.acp.adapter.OpenCodePart.FileSource(path = file.path, text = sourceText)
                    } else null
                } else null
                parts.add(com.opencode.acp.adapter.OpenCodePart.File(
                    mime = file.mime, url = file.dataUri, filename = file.name,
                    source = source
                ))
            }
            logger.info { "[ACP] sendMessage: ${parts.size} parts (text + ${files.size} file attachments: ${files.joinToString { it.name }})" }

            val serverMessageId = client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)
            logger.info { "[ACP] sendMessage: got serverMessageId=$serverMessageId" }

            sessionManager.updateServerMessageId(assistantMsgId, serverMessageId)

            withTimeout(300_000L) {
                deferred.await()
            }
            return SendMessageResult.Success(assistantMsgId)
        } catch (e: TimeoutCancellationException) {
            logger.error(e) { "[ACP] sendMessage: SSE response timed out after 5 minutes" }
            sessionManager.abortStreaming("Response timed out after 5 minutes.")
            return SendMessageResult.Error("Response timed out")
        } catch (e: CancellationException) {
            sessionManager.completeStreaming(assistantMsgId)
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
            activeSession?.responseDeferred = null
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    suspend fun cancel() {
        val client = connectionManager.client
        val currentSessionId = sessionManager.activeSessionId.value
        if (client != null && currentSessionId != null) {
            client.abortSession(currentSessionId)
        }
        sessionManager.getActiveSession()?.let { session ->
            session.responseDeferred?.complete(Unit)
            session.responseDeferred = null
        }
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

    suspend fun refreshActiveSessionMessages() {
        sessionManager.refreshActiveSessionMessages()
    }

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
        permissionManager.dispose()
        sessionManager.close()
        connectionManager.shutdown()
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
