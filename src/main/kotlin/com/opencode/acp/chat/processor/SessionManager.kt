package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.chat.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns all per-session state. Routes SSE events to the correct [SessionState].
 * Manages session lifecycle (create, switch, archive, list).
 * Replaces both the old MessageProcessorManager and the old SessionManager.
 *
 * Thread safety: Uses ReentrantLock (not Mutex) for sessions map access because
 * getActiveSession() is called from non-suspend functions. The lock is held briefly
 * (O(1) map operations) and won't cause coroutine starvation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionManager(private val scope: CoroutineScope) {

    private val logger = KotlinLogging.logger {}

    companion object {
        /** Maximum number of sessions to keep in memory. LRU eviction. */
        const val MAX_CACHED_SESSIONS = 10
    }

    // ── Per-Session State ──

    /** All cached session states. Keyed by session ID. */
    private val sessions = LinkedHashMap<String, SessionState>()
    private val sessionsLock = ReentrantLock()

    /** Mutex to serialize session switches. */
    private val switchMutex = Mutex()

    /** Timestamp of the last successful computeSessionContext() call (dedup guard). */
    @Volatile
    private var lastContextComputeTime: Long = 0L

    /** The currently active session ID. Null if no session is active. */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Messages for the active session. UI observes this.
     *  Uses flatMapLatest on _activeSessionId so that when the active session
     *  changes, the upstream automatically switches to the new session's messages.
     *  CRITICAL: Uses SharingStarted.Eagerly — the ViewModel reads .value synchronously. */
    val activeMessages: StateFlow<Map<String, ChatMessage>> = _activeSessionId
        .flatMapLatest { id ->
            sessionsLock.withLock { sessions[id] }?.messages ?: flowOf(emptyMap())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Signals for the active session. ViewModel collects this.
     *  Same flatMapLatest pattern as activeMessages.
     *  CRITICAL: Uses SharingStarted.Eagerly for the same reason. */
    val activeSignals: SharedFlow<UiSignal> = _activeSessionId
        .flatMapLatest { id ->
            sessionsLock.withLock { sessions[id] }?.signals ?: emptyFlow()
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Global signals for cross-session events (SessionCreated, etc.). */
    private val _globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 16)
    val globalSignals: SharedFlow<UiSignal> = _globalSignals.asSharedFlow()

    // ── Session List State (from old SessionManager) ──

    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    private val _childSessionMap = MutableStateFlow<Map<String, List<SessionItem>>>(emptyMap())
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = _childSessionMap.asStateFlow()

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    // ── Dependencies (injected by OpenCodeService) ──

    internal var client: OpenCodeClient? = null

    // ── Global signal merging ──

    private val _allSessionSignals = MutableSharedFlow<Pair<String, UiSignal>>(extraBufferCapacity = 64)
    val allSessionSignals: Flow<Pair<String, UiSignal>> = _allSessionSignals.asSharedFlow()

    /** Session IDs that are currently streaming (generation in progress).
     *  Updated reactively from allSessionSignals — StreamingStarted adds, StreamingCompleted removes. */
    private val _streamingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val streamingSessionIds: StateFlow<Set<String>> = _streamingSessionIds.asStateFlow()

    /** Session IDs that are being created on the server (optimistic placeholder).
     *  Shows a spinner in the sidebar until loadSessions() returns the real data. */
    private val _pendingCreationSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingCreationSessionIds: StateFlow<Set<String>> = _pendingCreationSessionIds.asStateFlow()

    internal fun emitSessionSignal(sessionId: String, signal: UiSignal) {
        _allSessionSignals.tryEmit(sessionId to signal)
    }

    init {
        // Track streaming session IDs from all session signals
        scope.launch {
            allSessionSignals.collect { (sessionId, signal) ->
                when (signal) {
                    is UiSignal.StreamingStarted -> {
                        _streamingSessionIds.value = _streamingSessionIds.value + sessionId
                    }
                    is UiSignal.StreamingCompleted -> {
                        _streamingSessionIds.value = _streamingSessionIds.value - sessionId
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Session Lifecycle ──

    /**
     * Switch the active session. Does NOT destroy any session's state.
     * Serialized by switchMutex to prevent concurrent switches.
     */
    suspend fun switchSession(targetSessionId: String) {
        switchMutex.withLock {
            if (_activeSessionId.value == targetSessionId) return

            val previousSessionId = _activeSessionId.value

            try {
                ensureSessionCached(targetSessionId)
                _activeSessionId.value = targetSessionId
                updateSessionSelection(targetSessionId)
                computeSessionContext()
                fetchTodos()
                logger.info { "[ACP] Switched to session $targetSessionId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to switch session $targetSessionId" }
                _activeSessionId.value = previousSessionId
                updateSessionSelection(previousSessionId)
            }
        }
    }

    /** Create a new session on the server and switch to it. */
    suspend fun createAndSwitchSession(title: String? = null) {
        val c = client ?: return
        val session = try {
            c.createSession(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to create session" }
            return
        }

        // Optimistically add the new session to the list so it appears immediately
        // in the sidebar before loadSessions() completes the server round-trip.
        val newItem = session.toSessionItem()
        _pendingCreationSessionIds.value = _pendingCreationSessionIds.value + session.id
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(
                sessions = listOf(newItem) + current.sessions,
                selectedId = session.id
            )
        }

        switchMutex.withLock {
            ensureSessionCached(session.id)
            _activeSessionId.value = session.id
            updateSessionSelection(session.id)
            computeSessionContext()
            fetchTodos()
        }

        try {
            loadSessions()
        } finally {
            _pendingCreationSessionIds.value = _pendingCreationSessionIds.value - session.id
        }
        logger.info { "Created and switched to new session: ${session.id}" }
    }

    /** Load/reload the session list from the server. */
    suspend fun loadSessions() {
        val c = client ?: run {
            logger.warn { "[ACP] SessionManager.loadSessions: client is null" }
            return
        }
        try {
            logger.info { "[ACP] SessionManager.loadSessions: fetching session list..." }
            val sessionList = c.listSessions()
            val currentId = _activeSessionId.value
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

    /** Archive (delete) a session. Removes from cache if present. */
    suspend fun archiveSession(targetSessionId: String) {
        val c = client ?: return
        try {
            val success = c.deleteSession(targetSessionId)
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

        // Remove from cache — close() outside lock (not O(1): cancels coroutines, closes channels)
        val evictedState = sessionsLock.withLock {
            sessions.remove(targetSessionId)
        }
        evictedState?.close()

        if (targetSessionId == _activeSessionId.value) {
            val next = sessionsLock.withLock { sessions.keys.firstOrNull() }
            if (next != null) switchSession(next) else _activeSessionId.value = null
        }

        loadSessions()
        logger.info { "Archived session $targetSessionId" }
    }

    // ── SSE Event Routing ──

    /** Process an SSE event. Routes to the correct SessionState by event.sessionId. */
    suspend fun processEvent(event: SseEvent) {
        val sessionId = event.sessionId

        when (event) {
            is SseEvent.SessionCreated -> {
                _globalSignals.tryEmit(UiSignal.SessionCreated(event.sessionId))
                return
            }
            is SseEvent.SessionIdle -> {
                if (event.sessionId == _activeSessionId.value) {
                    _globalSignals.tryEmit(UiSignal.SessionIdle(event.sessionId))
                }
                // Also route to SessionState as a backstop — if streaming is still
                // active when the server says it's idle, finalize it.
                // Don't return early: fall through to the else -> branch below.
            }
            is SseEvent.SessionError -> {
                logger.warn { "[ACP] Session error: session=${event.sessionId}, error=${event.errorMessage}" }
                _globalSignals.tryEmit(UiSignal.SessionError(event.sessionId, event.errorMessage))
                return
            }
            is SseEvent.SessionCompacted -> {
                // Server performed auto-compaction — local message cache is stale.
                // Emit signal so ViewModel can refresh messages and recompute context.
                if (event.sessionId == _activeSessionId.value) {
                    _globalSignals.tryEmit(UiSignal.SessionCompacted(event.sessionId))
                }
                return
            }
            is SseEvent.MessageRemoved -> {
                // Route to SessionState for cache removal, then refresh context
                sessionsLock.withLock { sessions[sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                if (sessionId == _activeSessionId.value) {
                    scope.launch { computeSessionContext() }
                }
                return
            }
            is SseEvent.Subtask -> {
                // Subagent spawned — refresh the session list so it appears in the sidebar
                scope.launch { loadSessions() }
                return
            }
            is SseEvent.TodoUpdated -> {
                sessionsLock.withLock { sessions[sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                if (sessionId == _activeSessionId.value) {
                    _todoItems.value = event.todos.map { todo -> TodoItem(content = todo.content, status = todo.status, priority = todo.priority) }
                }
                return
            }
            else -> {
                val state = sessionsLock.withLock { sessions[sessionId] }
                if (state == null) {
                    logger.debug { "[ACP] Ignoring event for uncached session $sessionId" }
                    return
                }
                state.processEvent(event)
            }
        }
    }

    // ── Message Operations (delegate to active SessionState) ──

    fun createAssistantMessage(modelID: String?, providerID: String?, serverMessageId: String? = null): String {
        return getActiveSession()?.createAssistantMessage(modelID, providerID, serverMessageId)
            ?: throw IllegalStateException("No active session")
    }

    fun completeStreaming(messageId: String) {
        getActiveSession()?.completeStreaming(messageId)
    }

    fun abortStreaming(reason: String) {
        getActiveSession()?.abortStreaming(reason)
    }

    fun addMessage(message: ChatMessage) {
        getActiveSession()?.addMessage(message)
    }

    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>?) {
        getActiveSession()?.updateToolCallStatus(toolCallId, status, output)
    }

    fun setToolPartState(toolCallId: String, state: PartState) {
        getActiveSession()?.setToolPartState(toolCallId, state)
    }

    fun updateServerMessageId(messageId: String, serverMessageId: String) {
        getActiveSession()?.updateServerMessageId(messageId, serverMessageId)
    }

    fun setLastUserText(text: String?) {
        getActiveSession()?.setLastUserText(text)
    }

    fun injectSubagentRefs(messageId: String, refs: List<SubagentRef>) {
        getActiveSession()?.injectSubagentRefs(messageId, refs)
    }

    // ── Session-specific routing (for PermissionManager) ──

    suspend fun setToolPartStateForSession(sessionId: String, toolCallId: String, state: PartState) {
        sessionsLock.withLock { sessions[sessionId] }?.setToolPartState(toolCallId, state)
    }

    suspend fun updateToolCallStatusForSession(sessionId: String, toolCallId: String, status: ToolCallStatus, output: List<JsonObject>?) {
        sessionsLock.withLock { sessions[sessionId] }?.updateToolCallStatus(toolCallId, status, output)
    }

    suspend fun getSession(sessionId: String): SessionState? {
        return sessionsLock.withLock { sessions[sessionId] }
    }

    /** Get all cached sessions that have isStreaming == true.
     *  Used by OpenCodeService.recoverBackgroundSessions() after SSE reconnection. */
    internal fun getStreamingSessions(): List<SessionState> {
        return sessionsLock.withLock {
            sessions.values.filter { it.isStreaming }
        }
    }

    // ── Helpers ──

    /** Get the currently active SessionState. Returns null if no session is active.
     *  Uses ReentrantLock (not Mutex) because callers are non-suspend functions. */
    internal fun getActiveSession(): SessionState? {
        val id = _activeSessionId.value ?: return null
        return sessionsLock.withLock { sessions[id] }
    }

    fun close() {
        // Remove sessions from map under lock, then close outside the lock.
        // close() is NOT O(1) — it cancels coroutines, closes channels, and
        // completes deferreds. Holding sessionsLock during close() would block
        // processEvent() and getActiveSession() (same pattern as evictIfNeeded).
        val toClose = runBlocking {
            sessionsLock.withLock {
                val states = sessions.values.toList()
                sessions.clear()
                states
            }
        }
        toClose.forEach { it.close() }
    }

    // ── Private: Cache Management ──

    /** Ensure a session's SessionState exists in cache. Fetch from server if not. */
    internal suspend fun ensureSessionCached(sessionId: String) {
        sessionsLock.withLock {
            if (sessions.containsKey(sessionId)) return
        }

        evictIfNeeded(excludeSessionId = sessionId)

        val c = client
        val messages = try {
            c?.listMessages(sessionId, limit = null) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch messages for $sessionId" }
            emptyList()
        }

        val state = SessionState(sessionId, scope, this)
        messages.forEach { state.addMessage(it.toChatMessage()) }

        // Inject subagent refs if this session has children
        val children = _childSessionMap.value[sessionId]
        if (children != null) {
            state.messages.value.values.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { msg ->
                state.injectSubagentRefs(msg.id, children.map { child ->
                    SubagentRef(
                        sessionId = child.id,
                        agentName = child.title.replaceFirstChar { it.uppercase() },
                        taskDescription = child.title,
                        status = if (child.outputTokens > 0) SubagentStatus.COMPLETED else SubagentStatus.RUNNING
                    )
                })
            }
        }

        sessionsLock.withLock {
            val existing = sessions.putIfAbsent(sessionId, state)
            if (existing != null) {
                state.close()
            }
        }
    }

    /**
     * Refresh the message cache for the active session by re-fetching from the server.
     * Used after auto-compaction (session.compacted) when the local cache is stale —
     * compacted messages are removed/summarized server-side, so local token
     * accumulation would produce inflated numbers without a refresh.
     */
    internal suspend fun refreshActiveSessionMessages() {
        val currentSessionId = _activeSessionId.value ?: return
        val c = client ?: return
        val state = sessionsLock.withLock { sessions[currentSessionId] } ?: return

        val messages = try {
            c.listMessages(currentSessionId, limit = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Failed to refresh messages for $currentSessionId after compaction" }
            return
        }

        state.replaceAllMessages(messages.map { it.toChatMessage() })
        logger.info { "[ACP] Refreshed messages for session $currentSessionId after compaction (${messages.size} messages)" }
    }

    /** Evict the least-recently-used non-active session if cache is full.
     *  Never evict streaming or permission-pending sessions.
     *  Removes sessions from the map under lock, then closes them outside the lock. */
    private suspend fun evictIfNeeded(excludeSessionId: String? = null) {
        val toEvict = mutableListOf<Pair<String, SessionState>>()
        val evictedIds = mutableSetOf<String>()
        sessionsLock.withLock {
            while (sessions.size - evictedIds.size > MAX_CACHED_SESSIONS) {
                val lru = sessions.entries
                    .filter { it.key != _activeSessionId.value
                            && it.key != excludeSessionId
                            && !it.value.isStreaming
                            && !it.value.hasPendingPermission
                            && it.key !in evictedIds }
                    .minByOrNull { it.value.lastAccessTime }
                    ?: break
                toEvict.add(lru.key to lru.value)
                evictedIds.add(lru.key)
                sessions.remove(lru.key)
            }
        }
        toEvict.forEach { (id, state) ->
            state.close()
            logger.debug { "[ACP] Evicted session $id from cache" }
        }
    }

    // ── Private: Context / Todos ──

    private fun updateSessionSelection(selectedId: String?) {
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(selectedId = selectedId)
        }
    }

    internal suspend fun computeSessionContext(controlState: ControlBarState? = null): SessionContextState {
        val currentSessionId = _activeSessionId.value ?: return SessionContextState.Loading
        val c = client ?: return SessionContextState.Loading

        // Dedup: skip re-computation if last call was < 300ms ago and state is Loaded.
        // This prevents redundant REST calls when StreamingCompleted and SessionIdle
        // fire close together for the same response.
        val now = System.currentTimeMillis()
        if (now - lastContextComputeTime < 300 && _sessionContextState.value is SessionContextState.Loaded) {
            return _sessionContextState.value
        }
        lastContextComputeTime = now

        val messages = getActiveSession()?.messages?.value ?: emptyMap()

        // Best-effort session fetch — used for summary, time, and model metadata.
        // Token/cost data comes from the local message cache (kept accurate by
        // MessageFinalized SSE events), NOT from session.tokens/session.cost
        // (the V1 API returns these as always-zero — see AGENTS.md § API Testing).
        //
        // Design deviation from TDD §7.1: On fetch failure, we return Loaded with
        // defaults (0 for summary/time, controlState fallback for model) instead of
        // Error. This is intentional because token/cost data (the primary context
        // data) is still available from the local message cache — only the secondary
        // metadata (summary, time, model) degrades gracefully. Showing an Error
        // state for a transient session-fetch failure would be too aggressive since
        // the core data is still correct.
        val session = try {
            c.getSession(currentSessionId)
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Failed to fetch session for context" }
            null
        }

        // ── Token data: use LAST assistant message (matches OpenCode desktop) ──
        // The desktop app shows the last assistant message's token breakdown,
        // not cumulative across all messages. Only cost is cumulative.
        // MessageFinalized SSE events keep per-message fields accurate.
        val assistantMessages = messages.values.filter { it.role == MessageRole.ASSISTANT }
        val lastAssistant = messages.values.findLast {
            it.role == MessageRole.ASSISTANT &&
                (it.inputTokens + it.outputTokens + it.reasoningTokens + it.cacheReadTokens + it.cacheWriteTokens) > 0
        }
        val inputTokens = lastAssistant?.inputTokens ?: 0L
        val outputTokens = lastAssistant?.outputTokens ?: 0L
        val reasoningTokens = lastAssistant?.reasoningTokens ?: 0L
        val cacheReadTokens = lastAssistant?.cacheReadTokens ?: 0L
        val cacheWriteTokens = lastAssistant?.cacheWriteTokens ?: 0L
        val totalTokens = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens
        val totalCost = assistantMessages.sumOf { it.cost }  // cumulative across all messages

        // ── Model info: from session metadata, fallback to controlState ──
        // Note: SessionModel.id is String = "" (non-nullable). Must use takeIf
        // to avoid treating empty string as a valid model ID, which would
        // suppress the controlState fallback.
        val modelId = session?.model?.id?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.modelID
        val providerId = session?.model?.providerID?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.providerID

        val (providerName, modelName) = resolveModelNames(
            controlState?.models ?: emptyList(), modelId, providerId
        )
        val contextLimit = resolveContextLimit(
            controlState?.allModels?.ifEmpty { controlState.models } ?: emptyList(),
            providerId, modelId
        )

        val usagePercent = if (contextLimit > 0L) {
            (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        // ── Message counts: from local cache (not in OpenCodeSession) ──
        val messageCount = messages.size
        val userMessageCount = messages.values.count { it.role == MessageRole.USER }
        val assistantMessageCount = assistantMessages.size

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
                messageCount = messageCount,
                userMessageCount = userMessageCount,
                assistantMessageCount = assistantMessageCount,
                additions = session?.summary?.additions ?: 0,
                deletions = session?.summary?.deletions ?: 0,
                filesModified = session?.summary?.files ?: 0,
                sessionCreated = session?.time?.created ?: 0L,
                lastUpdated = session?.time?.updated ?: 0L,
            )
        )
        logger.info { "[ACP] computeSessionContext: session=$currentSessionId totalTokens=$totalTokens cost=$totalCost usagePercent=${"%.1f".format(usagePercent)}% model=$modelId provider=$providerId" }
        _sessionContextState.value = result
        return result
    }

    internal suspend fun fetchTodos() {
        val currentSessionId = _activeSessionId.value ?: return
        val c = client ?: return
        try {
            val todos = c.getTodos(currentSessionId)
            _todoItems.value = todos.map { TodoItem(content = it.content, status = it.status, priority = it.priority) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silently fail
        }
    }

    // ── Private: Helpers ──

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
