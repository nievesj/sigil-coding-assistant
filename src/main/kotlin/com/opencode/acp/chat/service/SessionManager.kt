package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.processor.MessageProcessorManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages session lifecycle: list, switch, create, archive.
 *
 * Owns session-level state ([sessionId], [sessionListState], [childSessionMap],
 * [todoItems], [sessionContextState]) and the [switchMutex] that serializes
 * session switches.
 *
 * Callers provide [onBeforeReset] and [onAfterSseSetup] callbacks so the
 * coordinator can wire in SSE subscription and processor cleanup without
 * this class depending on them directly.
 *
 * @param onBeforeReset  Called inside switchMutex before clearing state.
 *                       Receives the new target session ID.
 * @param onAfterSseSetup Called inside switchMutex after messages are loaded
 *                        and the new SSE subscription is established.
 *                        Receives the new session ID.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val processor: MessageProcessorManager,
    private val onBeforeReset: suspend (String) -> Unit,
    private val onAfterSseSetup: suspend (String) -> Unit,
) {

    private val logger = KotlinLogging.logger {}

    // ── State ──────────────────────────────────────────────────────────────

    var sessionId: String? = null
        private set

    val switchMutex = Mutex()

    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    private val _childSessionMap = MutableStateFlow<Map<String, List<SessionItem>>>(emptyMap())
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = _childSessionMap.asStateFlow()

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    // ── Session list ───────────────────────────────────────────────────────

    suspend fun loadSessions() {
        val client = clientProvider() ?: run {
            logger.warn { "[ACP] SessionManager.loadSessions: client is null" }
            return
        }
        try {
            logger.info { "[ACP] SessionManager.loadSessions: fetching session list..." }
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

    // ── Switching ──────────────────────────────────────────────────────────

    /**
     * Switch to an existing session. Re-entering the same session re-establishes
     * the SSE subscription (fixes stale sseJob). Uses [switchMutex] internally.
     */
    suspend fun switchSession(targetSessionId: String) {
        switchMutex.withLock {
            val client = clientProvider() ?: return
            if (sessionId == targetSessionId) {
                onBeforeReset(targetSessionId)
                onAfterSseSetup(targetSessionId)
                return
            }

            val previousSessionId = sessionId
            logger.info { "[ACP] SessionManager.switchSession: START target=$targetSessionId, previous=$previousSessionId" }

            try {
                onBeforeReset(targetSessionId)
                processor.resetSessionState()
                client.resetReasoningTracking()

                logger.info { "[ACP] SessionManager.switchSession: fetching messages for $targetSessionId ..." }
                val messages = client.listMessages(targetSessionId, limit = null)
                logger.info { "[ACP] SessionManager.switchSession: got ${messages.size} raw messages" }
                sessionId = targetSessionId

                val children = _childSessionMap.value[targetSessionId] ?: emptyList()
                logger.info { "[ACP] SessionManager.switchSession: converting ${messages.size} messages to chat, children=${children.size}" }
                val chatMessages = if (children.isNotEmpty()) {
                    injectSubagentRefs(messages.map { it.toChatMessage() }, children)
                } else {
                    messages.map { it.toChatMessage() }
                }
                logger.info { "[ACP] SessionManager.switchSession: ${chatMessages.size} chat messages ready, adding to processor" }
                chatMessages.forEach { processor.addMessage(it) }

                onAfterSseSetup(targetSessionId)
                updateSessionSelection(targetSessionId)
                computeSessionContext()
                fetchTodos()

                logger.info { "[ACP] SessionManager.switchSession: DONE — ${chatMessages.size} messages loaded" }
            } catch (e: CancellationException) {
                logger.info { "[ACP] SessionManager.switchSession: CANCELLED" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] SessionManager.switchSession: FAILED — ${e::class.simpleName}: ${e.message}" }
                sessionId = previousSessionId
                updateSessionSelection(previousSessionId)
                if (previousSessionId != null) {
                    try {
                        val msgs = client.listMessages(previousSessionId, limit = null)
                        msgs.map { it.toChatMessage() }.forEach { processor.addMessage(it) }
                        onAfterSseSetup(previousSessionId)
                    } catch (e2: Exception) {
                        logger.error(e2) { "[ACP] SessionManager.switchSession: revert also FAILED" }
                    }
                }
            }
        }
    }

    /**
     * Create a new session and switch to it. Does NOT hold [switchMutex] while
     * creating the session server-side; only acquires it for the switch itself.
     */
    suspend fun createAndSwitchSession(title: String? = null) {
        val client = clientProvider() ?: return

        val session = try {
            client.createSession(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to create session" }
            return
        }

        switchMutex.withLock {
            onBeforeReset(session.id)
            processor.resetSessionState()
            sessionId = session.id
            onAfterSseSetup(session.id)
        }

        loadSessions()
        updateSessionSelection(session.id)
        computeSessionContext()
        fetchTodos()
        logger.info { "Created and switched to new session: ${session.id}" }
    }

    /**
     * Archive (delete) a session. If it's the active session, creates a new
     * one as replacement.
     */
    suspend fun archiveSession(targetSessionId: String) {
        val client = clientProvider() ?: return
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

    // ── Context / Todos ────────────────────────────────────────────────────

    suspend fun computeSessionContext(controlState: ControlBarState? = null): SessionContextState {
        val currentSessionId = sessionId ?: return SessionContextState.Loading
        val client = clientProvider() ?: return SessionContextState.Loading
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
        val client = clientProvider() ?: return
        try {
            val todos = client.getTodos(currentSessionId)
            _todoItems.value = todos.map { TodoItem(content = it.content, status = it.status, priority = it.priority) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silently fail
        }
    }

    // ── Selection helpers ──────────────────────────────────────────────────

    private fun updateSessionSelection(selectedId: String?) {
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(selectedId = selectedId)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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
}
