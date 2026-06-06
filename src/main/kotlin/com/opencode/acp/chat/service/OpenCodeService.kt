package com.opencode.acp.chat.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.MessageProcessorManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.chat.model.ConnectionState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Project-level service that owns the OpenCode connection and message processor.
 * Survives tool window disposal/recreation. Only disposed when the project closes.
 *
 * Architecture:
 * - [OpenCodeConnectionManager] handles SSE/HTTP connection lifecycle
 * - [MessageProcessorManager] handles message processing and the event channel
 * - This service coordinates between them and exposes the public API
 *
 * The [ChatViewModel] is a thin UI wrapper that delegates to this service.
 */
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Sub-components ---
    val connectionManager = OpenCodeConnectionManager(scope)
    val processor = MessageProcessorManager(scope)

    // --- Session state ---
    private var sessionId: String? = null
    private val switchMutex = Mutex()
    private var responseDeferred: CompletableDeferred<Unit>? = null
    private var permissionTimeoutJob: Job? = null

    // --- State flows (forwarded from sub-components) ---
    val messages: StateFlow<Map<String, ChatMessage>> = processor.messages
    val signals: SharedFlow<UiSignal> = processor.signals
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    // --- Session management state ---
    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    private val _childSessionMap = MutableStateFlow<Map<String, List<SessionItem>>>(emptyMap())
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = _childSessionMap.asStateFlow()

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    // --- Signal collection (completes responseDeferred on Stop) ---
    private var signalCollectionJob: Job? = null

    private fun startSignalCollection() {
        signalCollectionJob?.cancel()
        signalCollectionJob = scope.launch {
            processor.signals.collect { signal ->
                when (signal) {
                    is UiSignal.StreamingCompleted -> {
                        // Complete the pending responseDeferred so sendMessage() unblocks
                        responseDeferred?.complete(Unit)
                        responseDeferred = null
                    }
                    else -> { /* other signals handled by ViewModel */ }
                }
            }
        }
    }

    // --- Initialization ---

    /**
     * Initialize the connection and load sessions.
     * Called once when the service is first accessed.
     * Returns true if connection succeeded.
     */
    suspend fun initialize(): Boolean {
        logger.info { "[ACP] OpenCodeService.initialize: START" }
        val connected = connectionManager.initialize()
        if (!connected) {
            logger.warn { "[ACP] OpenCodeService.initialize: connectionManager.initialize() returned false" }
            return false
        }
        logger.info { "[ACP] OpenCodeService.initialize: connection established" }

        // Start collecting processor signals to complete responseDeferred
        startSignalCollection()

        // Load sessions
        logger.info { "[ACP] OpenCodeService.initialize: loading sessions..." }
        loadSessions()
        logger.info { "[ACP] OpenCodeService.initialize: sessions state = ${_sessionListState.value::class.simpleName}" }

        // Switch to most recent session if available
        when (val state = _sessionListState.value) {
            is SessionListState.Loaded -> {
                if (state.sessions.isNotEmpty()) {
                    switchSession(state.sessions.first().id)
                }
            }
            is SessionListState.Error -> {
                loadSessions()
                val retry = _sessionListState.value as? SessionListState.Loaded
                if (retry != null && retry.sessions.isNotEmpty()) {
                    switchSession(retry.sessions.first().id)
                }
            }
            is SessionListState.Loading -> { /* shouldn't happen */ }
        }

        return true
    }

    // --- Session management ---

    suspend fun loadSessions() {
        val client = connectionManager.client ?: run {
            logger.warn { "[ACP] loadSessions: client is null" }
            return
        }
        try {
            logger.info { "[ACP] loadSessions: fetching session list..." }
            val sessionList = client.listSessions()
            val currentId = sessionId
            val items = sessionList
                .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                .map { it.toSessionItem() }
            _sessionListState.value = SessionListState.Loaded(
                sessions = items,
                selectedId = currentId
            )
            val children = items.filter { it.parentID != null }
                .groupBy { it.parentID!! }
            _childSessionMap.value = children
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sessions" }
            _sessionListState.value = SessionListState.Error(e.message ?: "Failed to load sessions")
        }
    }

    suspend fun switchSession(targetSessionId: String) {
        switchMutex.withLock {
            val client = connectionManager.client ?: return
            if (sessionId == targetSessionId) return

            val previousSessionId = sessionId
            logger.info { "[ACP] switchSession: START target=$targetSessionId, previous=$previousSessionId" }

            try {
                connectionManager.resetReconnectState()
                connectionManager.cancelSseSubscription()
                responseDeferred?.complete(Unit)
                responseDeferred = null
                processor.resetSessionState()
                connectionManager.client?.resetReasoningTracking()

                logger.info { "[ACP] switchSession: fetching messages for $targetSessionId ..." }
                val messages = client.listMessages(targetSessionId, limit = null)
                logger.info { "[ACP] switchSession: got ${messages.size} raw messages" }
                sessionId = targetSessionId

                val children = _childSessionMap.value[targetSessionId] ?: emptyList()
                logger.info { "[ACP] switchSession: converting ${messages.size} messages to chat, children=${children.size}" }
                val chatMessages = try {
                    if (children.isNotEmpty()) {
                        injectSubagentRefs(messages.map { it.toChatMessage() }, children)
                    } else {
                        messages.map { it.toChatMessage() }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[ACP] switchSession: ERROR in toChatMessage: ${e.message}" }
                    throw e
                }
                logger.info { "[ACP] switchSession: ${chatMessages.size} chat messages ready, adding to processor" }
                chatMessages.forEach { processor.addMessage(it) }

                startSseSubscription(targetSessionId)
                updateSessionSelection(targetSessionId)
                computeSessionContext()
                fetchTodos()

                logger.info { "[ACP] switchSession: DONE — ${chatMessages.size} messages loaded" }
            } catch (e: CancellationException) {
                logger.info { "[ACP] switchSession: CANCELLED" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] switchSession: FAILED — ${e::class.simpleName}: ${e.message}" }
                sessionId = previousSessionId
                updateSessionSelection(previousSessionId)
                if (previousSessionId != null) {
                    try {
                        val msgs = client.listMessages(previousSessionId, limit = null)
                        val chatMsgs = msgs.map { it.toChatMessage() }
                        chatMsgs.forEach { processor.addMessage(it) }
                        startSseSubscription(previousSessionId)
                    } catch (e2: Exception) {
                        logger.error(e2) { "[ACP] switchSession: revert also FAILED" }
                    }
                }
            }
        }
    }

    suspend fun createAndSwitchSession(title: String? = null) {
        val client = connectionManager.client ?: return

        val session = try {
            client.createSession(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to create session" }
            return
        }

        switchMutex.withLock {
            connectionManager.resetReconnectState()
            try { cancel() } catch (_: Exception) { /* best-effort */ }
            connectionManager.cancelSseSubscription()
            processor.resetSessionState()
            sessionId = session.id
            startSseSubscription(session.id)
        }

        loadSessions()
        updateSessionSelection(session.id)
        computeSessionContext()
        fetchTodos()
        logger.info { "Created and switched to new session: ${session.id}" }
    }

    suspend fun archiveSession(targetSessionId: String) {
        val client = connectionManager.client ?: return
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
                processor.resetSessionState()
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

    // --- SSE subscription ---

    private fun startSseSubscription(targetSessionId: String) {
        connectionManager.cancelSseSubscription()
        val job = connectionManager.startSseSubscription(targetSessionId) { event ->
            handleSseEvent(event)
        }
        // Store the job reference in the connection manager for cancellation
        // (it's already stored internally by startSseSubscription)
    }

    private fun handleSseEvent(event: SseEvent) {
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

    // --- Message sending ---

    suspend fun sendMessage(
        text: String,
        files: List<AttachedFile> = emptyList(),
        modelID: String? = null,
        providerID: String? = null,
        variant: String? = null,
        agent: String? = null,
        model: OpenCodeClient.MessageModel? = null
    ): SendMessageResult {
        val client = connectionManager.client ?: return SendMessageResult.Error("No client")
        val currentSessionId = sessionId ?: return SendMessageResult.Error("No session")
        logger.info { "[ACP] sendMessage: START session=$currentSessionId text='${text.take(50)}'" }

        // Add user message
        val userMsg = ChatMessage(
            id = generateId(),
            role = MessageRole.USER,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            attachedFiles = files
        )
        processor.addMessage(userMsg)
        processor.setLastUserText(text)

        // Create the assistant message BEFORE the HTTP call so that SSE events
        // arriving while the HTTP request is in-flight can be routed immediately.
        // The server-assigned messageID is patched in after sendMessageAsync returns.
        // Also clear stale reasoning tracking from the previous turn to prevent
        // misrouting text chunks as thinking content.
        connectionManager.client?.resetReasoningTracking()
        val assistantMsgId = processor.createAssistantMessage(
            modelID = modelID,
            providerID = providerID,
            serverMessageId = null  // will be updated after HTTP call
        )

        val deferred = CompletableDeferred<Unit>()
        responseDeferred = deferred

        try {
            val parts = mutableListOf<com.opencode.acp.adapter.OpenCodePart>(com.opencode.acp.adapter.OpenCodePart.Text(text = text))
            files.forEach { file ->
                parts.add(com.opencode.acp.adapter.OpenCodePart.File(mime = file.mime, url = file.dataUri, filename = file.name))
            }

            val serverMessageId = client.sendMessageAsync(currentSessionId, parts, variant = variant, agent = agent, model = model)
            logger.info { "[ACP] sendMessage: got serverMessageId=$serverMessageId" }

            // Patch the server-assigned messageID into the already-created assistant message
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

    // --- Actions ---

    suspend fun cancel() {
        val client = connectionManager.client
        val currentSessionId = sessionId
        if (client != null && currentSessionId != null) {
            client.abortSession(currentSessionId)
        }
        responseDeferred?.complete(Unit)
        responseDeferred = null
    }

    suspend fun respondPermission(permissionId: String, toolCallId: String, response: com.opencode.acp.chat.model.PermissionResponse) {
        val client = connectionManager.client ?: return
        try {
            client.respondPermission(permissionId = permissionId, response = response.optionId)
            when (response) {
                PermissionResponse.REJECT_ONCE ->
                    processor.setToolPartState(toolCallId, PartState.Rejected)
                PermissionResponse.ALLOW_ONCE,
                PermissionResponse.ALLOW_ALWAYS ->
                    processor.updateToolCallStatus(toolCallId, com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS)
            }
        } catch (_: Exception) {
            // Network error — keep prompt open for retry
        }
    }

    suspend fun respondQuestion(promptId: String, answers: List<List<String>>) {
        val client = connectionManager.client ?: return
        client.respondQuestion(promptId, answers)
    }

    suspend fun rejectQuestion(promptId: String) {
        val client = connectionManager.client ?: return
        client.rejectQuestion(promptId)
    }

    // --- Data fetching ---

    suspend fun computeSessionContext(controlState: ControlBarState? = null): SessionContextState {
        val currentSessionId = sessionId ?: return SessionContextState.Loading
        val client = connectionManager.client ?: return SessionContextState.Loading
        val messages = processor.messages.value

        val session = try {
            client.getSession(currentSessionId)
        } catch (_: Exception) {
            null
        }

        val lastAssistant = messages.values.findLast {
            it.role == MessageRole.ASSISTANT && (it.inputTokens + it.outputTokens + it.reasoningTokens + it.cacheReadTokens + it.cacheWriteTokens) > 0
        }

        val totalCost = messages.values.filter { it.role == MessageRole.ASSISTANT }.sumOf { it.cost }

        val modelId = lastAssistant?.modelID ?: controlState?.selectedModel?.modelID
        val providerId = lastAssistant?.providerID ?: controlState?.selectedModel?.providerID

        val (providerName, modelName) = resolveModelNames(controlState?.models ?: emptyList(), modelId, providerId)
        val contextLimit = resolveContextLimit(controlState?.allModels?.ifEmpty { controlState.models } ?: emptyList(), providerId, modelId)

        val inputTokens = lastAssistant?.inputTokens ?: 0L
        val outputTokens = lastAssistant?.outputTokens ?: 0L
        val reasoningTokens = lastAssistant?.reasoningTokens ?: 0L
        val cacheReadTokens = lastAssistant?.cacheReadTokens ?: 0L
        val cacheWriteTokens = lastAssistant?.cacheWriteTokens ?: 0L
        val totalTokens = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens

        val usagePercent = if (contextLimit > 0L) {
            (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        val sessionTitle = (_sessionListState.value as? SessionListState.Loaded)
            ?.sessions?.find { it.id == currentSessionId }?.title ?: "Untitled"

        val result = SessionContextState.Loaded(
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
                userMessageCount = messages.values.count { it.role == MessageRole.USER },
                assistantMessageCount = messages.values.count { it.role == MessageRole.ASSISTANT },
                additions = session?.summary?.additions ?: 0,
                deletions = session?.summary?.deletions ?: 0,
                filesModified = session?.summary?.files ?: 0,
                sessionCreated = session?.time?.created ?: 0L,
                lastUpdated = session?.time?.updated ?: 0L
            )
        )
        _sessionContextState.value = result
        return result
    }

    suspend fun fetchTodos() {
        val currentSessionId = sessionId ?: return
        val client = connectionManager.client ?: return
        try {
            val todos = client.getTodos(currentSessionId)
            _todoItems.value = todos.map { TodoItem(content = it.content, status = it.status, priority = it.priority) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silently fail
        }
    }

    suspend fun fetchAvailableCommands(): List<SlashCommand> {
        val client = connectionManager.client ?: return emptyList()
        return try {
            val commands = client.listCommands()
            commands.map { cmd ->
                SlashCommand(
                    name = cmd.id ?: cmd.name,
                    description = cmd.description ?: cmd.name,
                    iconKey = null,
                    isServerCommand = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch available commands" }
            emptyList()
        }
    }

    suspend fun executeServerCommand(commandName: String, args: String = "") {
        val currentSessionId = sessionId ?: return
        val client = connectionManager.client ?: return
        try {
            client.executeCommand(currentSessionId, commandName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to execute command: $commandName" }
        }
    }

    // --- Data access (delegate methods for ViewModel) ---
    // Exceptions propagate to ViewModel's existing catch blocks which log warnings.
    // Do NOT catch silently here — the ViewModel needs visibility into failures.

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

    // --- Helpers ---

    private fun resolveModelNames(models: List<ProviderModel>, modelId: String?, providerId: String?): Pair<String, String> {
        if (modelId.isNullOrBlank() && providerId.isNullOrBlank()) return Pair("Unknown", "Unknown")
        if (models.isEmpty()) return Pair(providerId ?: "Unknown", modelId ?: "Unknown")

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null) {
            val parts = exactMatch.displayName.split(" / ", limit = 2)
            return Pair(parts.getOrElse(0) { exactMatch.providerID }, parts.getOrElse(1) { exactMatch.modelID })
        }

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null) {
            val parts = modelOnlyMatch.displayName.split(" / ", limit = 2)
            return Pair(parts.getOrElse(0) { modelOnlyMatch.providerID }, parts.getOrElse(1) { modelOnlyMatch.modelID })
        }

        return Pair(providerId ?: "Unknown", modelId ?: "Unknown")
    }

    private fun resolveContextLimit(models: List<ProviderModel>, providerId: String?, modelId: String?): Long {
        if (modelId.isNullOrBlank()) return 0L
        if (models.isEmpty()) return 0L

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null && exactMatch.contextWindow > 0) return exactMatch.contextWindow.toLong()

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null && modelOnlyMatch.contextWindow > 0) return modelOnlyMatch.contextWindow.toLong()

        return 0L
    }

    private fun injectSubagentRefs(messages: List<ChatMessage>, children: List<SessionItem>): List<ChatMessage> {
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
        val partsWithoutSubagents = LinkedHashMap(msg.parts.filterValues { it !is MessagePart.Subagent })
        refs.forEach { ref ->
            partsWithoutSubagents[ref.sessionId] = MessagePart.Subagent(ref)
        }
        updated[lastAssistantIdx] = msg.copy(parts = partsWithoutSubagents)
        return updated
    }

    // --- Cleanup ---

    /**
     * Full re-initialization after connection failure.
     * Does NOT hold switchMutex — initialize() calls switchSession()
     * which acquires it. Holding it here would deadlock since Kotlin's
     * Mutex is NOT reentrant.
     */
    suspend fun retryConnection() {
        connectionManager.close()
        initialize()
    }

    override fun dispose() {
        logger.info { "[ACP] OpenCodeService.dispose() called — project closing" }
        permissionTimeoutJob?.cancel()
        responseDeferred?.complete(Unit)
        responseDeferred = null
        connectionManager.close()
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
