package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.ToolMapper
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.ui.compose.MarkdownSegment
import com.opencode.acp.chat.ui.compose.MarkdownSegmenter
import com.opencode.acp.chat.ui.compose.StreamHealer
import com.opencode.acp.chat.util.EDT
import com.opencode.acp.chat.util.generateId
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-session state: message map, processor context, streaming lifecycle.
 * Each session gets its own [SessionState]. Switching sessions is a pointer swap.
 *
 * Thread safety: dual protection model —
 * - Event processing coroutine runs on Dispatchers.Default (serialization for processEvent
 *   is guaranteed by the Channel<BUFFERED> single-reader, NOT by dispatcher confinement)
 * - External callers (createAssistantMessage, addMessage, etc.) acquire stateLock
 * - SSE events arrive on any thread and are buffered in eventChannel
 * - The `closed` flag prevents mutations after close() is called
 * - ClosedSendChannelException is caught in processEvent() for the race between
 *   the @Volatile check and channel close
 */
class SessionState(
    val sessionId: String,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val project: Project,
) {
    private val logger = KotlinLogging.logger {}
    private val stateLock = ReentrantLock()

    /** Whether this SessionState has been closed (evicted or shutdown). */
    @Volatile private var closed = false

    /** Public read-only accessor for the closed flag.
     *  Used by SessionManager.activeMessages to filter out closed sessions
     *  so the UI doesn't display stale data after eviction. */
    val isClosed: Boolean get() = closed

    /** Messages for this session. Keyed by message ID. */
    private val _messages = MutableStateFlow<LinkedHashMap<String, ChatMessage>>(LinkedHashMap())
    val messages: StateFlow<Map<String, ChatMessage>> = _messages.asStateFlow()

    /** Real-time streaming text from the current turn's textBuffer. Updated on every TextChunk/TextReplace. */
    val streamingText: StateFlow<String> get() = textStreamingState.streamingText

    /** UI signals for this session.
     *  Uses replay = 0 (NOT replay = 1) to avoid stale signal replay on session switch. */
    private val _signals = MutableSharedFlow<UiSignal>(replay = 0, extraBufferCapacity = 15)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()

    /** Persistent permission prompt state for this session.
     *  Set by event processing when PermissionRequested is emitted.
     *  Cleared by PermissionManager when the permission is resolved.
     *  The ViewModel reads this on session switch to restore the prompt UI. */
    private val _pendingPermission = MutableStateFlow<PermissionPrompt?>(null)
    val pendingPermission: StateFlow<PermissionPrompt?> = _pendingPermission.asStateFlow()

    /** Persistent selection prompt state for this session.
     *  Set by event processing when SelectionRequested is emitted.
     *  Cleared by ChatViewModel when the user responds. */
    private val _pendingSelection = MutableStateFlow<SelectionPrompt?>(null)
    val pendingSelection: StateFlow<SelectionPrompt?> = _pendingSelection.asStateFlow()

    /** Text streaming state for the current turn (reveal buffers, segments, thinking). */
    internal val textStreamingState = TextStreamingState()
    /** Tool call + per-event state for the current turn (pills, index, file changes). */
    internal val toolCallState = ToolCallState()
    /** Turn lifecycle state for the current turn (active IDs, streaming flag, model). */
    internal val turnLifecycleState = TurnLifecycleState()

    // ── Collaborators (Phase 1: MessageMapManager + ToolCallStateManager) ──
    internal val messageMap = MessageMapManager(toolCallState, stateLock, _messages, { closed }, logger)
    internal val toolCallStateManager = ToolCallStateManager(toolCallState, stateLock, messageMap, sessionManager, logger)
    // ── Collaborator (Phase 2: FollowAgentDispatcher) ──
    internal val followAgent = FollowAgentDispatcher(project, toolCallState, turnLifecycleState, scope, sessionManager, logger)
    // ── Collaborator (Phase 3: TextStreamingManager) ──
    internal val textStreaming = TextStreamingManager(textStreamingState, turnLifecycleState, stateLock, scope, messageMap, { firstTextSegmented }, { firstTextSegmented = it }, logger)
    // ── Collaborator (Phase 3.5: StreamingLifecycleManager) ──
    internal val streamingLifecycle = StreamingLifecycleManager(turnLifecycleState, toolCallState, stateLock, scope, messageMap, textStreaming, _signals, { responseDeferred?.complete(Unit); responseDeferred = null }, logger)
    // ── Collaborator (Phase 4: PromptStateManager) ──
    internal val promptState = PromptStateManager(_signals, _pendingPermission, _pendingSelection, sessionId)

    /** Whether the first text chunk has been segmented (controls non-debounced
     *  re-segment for responsiveness on the first chunk). Reset in createAssistantMessage().
     *  @Volatile because written by [adoptStreamingContext] on the caller's coroutine and
     *  read by the event processing coroutine. */
    @Volatile internal var firstTextSegmented = false

    /** Pending turn identity stored when createAssistantMessage's ResetTurn trySend
     *  fails (event channel full). The event processing coroutine checks this at the
     *  start of processEventInternal and applies it if set, closing the window where
     *  activeMessageId would be null and a duplicate auto-create could fire.
     *  @Volatile because written by createAssistantMessage (caller's coroutine) and
     *  read/cleared by the event processing coroutine. */
    @Volatile internal var pendingTurnIdentity: PendingTurnIdentity? = null

    /** Carries the new turn's identity when the ResetTurn control event is dropped
     *  due to a full event channel. Applied at the start of processEventInternal. */
    internal data class PendingTurnIdentity(
        val messageId: String,
        val serverMessageId: String?,
        val modelID: String?,
        val providerID: String?,
    )

    /** Event channel — SSE events buffered for EDT processing. */
    private val eventChannel = Channel<SseEvent>(1024)

    /** Event processing coroutine (runs on EDT). */
    private var eventProcessingJob: Job? = null

    /** Response deferred for the current send operation.
     *  @Volatile because written by OpenCodeService.sendMessage() on Dispatchers.Default
     *  and read/written by close() under stateLock. */
    @Volatile
    var responseDeferred: CompletableDeferred<Unit>? = null

    /** Whether this session has an in-flight streaming message.
     *  NOTE: This reads [turnLifecycleState.isStreaming] which is mutated on EDT. For eviction
     *  checks this is a best-effort read; exact correctness is not required. */
    val isStreaming: Boolean get() = turnLifecycleState.isStreaming

    /** Whether this session has a pending permission prompt that hasn't been responded to. */
    val hasPendingPermission: Boolean get() = promptState.hasPendingPermission

    /** Toggle the pending permission flag. */
    internal fun setPendingPermission(flag: Boolean) = promptState.setPendingPermission(flag)

    /** Set the pending permission prompt. Also sets hasPendingPermission = true. */
    internal fun setPendingPermissionPrompt(prompt: PermissionPrompt) = promptState.setPermission(prompt)

    /** Set the pending selection prompt. */
    internal fun setPendingSelectionPrompt(prompt: SelectionPrompt) = promptState.setSelection(prompt)

    /** Clear the pending selection prompt. */
    internal fun clearPendingSelection() = promptState.clearSelection()

    /** Last access time (for LRU cache eviction). */
    @Volatile
    var lastAccessTime: Long = System.currentTimeMillis()
        private set

    /** Signal forwarding job — forwards signals to global merged flow. */
    private var signalForwardJob: Job? = null

    init {
        startCoroutines()
        signalForwardJob = scope.launch {
            _signals.collect { signal ->
                sessionManager.emitSessionSignal(sessionId, signal)
            }
        }
    }

    /** Snapshot tool states and pills under stateLock for consistent reads from
     *  external coroutines (e.g., activity monitor in OpenCodeService).
     *
     *  Without the lock, the event processing coroutine could mutate these maps
     *  mid-snapshot, producing an inconsistent view (e.g., a tool appears
     *  InProgress in partStates but Completed in pills). The lock is ReentrantLock
     *  and the read is O(n) — brief enough to not cause contention.
     *
     *  Returns a Triple of:
     *   1. partStates values (List<PartState>)
     *   2. toolCallPills entries (List<Map.Entry<String, ToolCallPill>>)
     *   3. toolPartStates map copy (Map<String, PartState>)
     */
    internal fun snapshotToolState(): Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>> =
        toolCallStateManager.snapshot()

    /** Public read-only accessor for the active streaming message ID.
     *  Used by OpenCodeService.recoverBackgroundSessions and sendMessage timeout handling. */
    val activeMessageId: String? get() = turnLifecycleState.activeMessageId

    /** Public read-only accessor for the last SSE activity timestamp.
     *  Used by OpenCodeService's activity-aware response timeout. */
    val lastActivityTimeMs: Long get() = turnLifecycleState.lastActivityTimeMs

    /** Public read-only accessor for the current turn's error message (if aborted/failed). */
    val errorMessage: String? get() = turnLifecycleState.errorMessage

    /** Reset turn-specific state for a new streaming turn.
     *  Coordinator that calls each state object's [reset] in the same order as the
     *  previous monolithic ProcessorContext.resetTurnState(). Called ONLY from the
     *  event processing coroutine (either directly when fromEventProcessing=true, or
     *  via the SseEvent.ResetTurn control event handler). */
    internal fun resetTurnState() {
        textStreamingState.reset()
        toolCallState.reset()
        turnLifecycleState.reset()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    suspend fun processEvent(event: SseEvent) {
        if (closed) return
        lastAccessTime = System.currentTimeMillis()
        turnLifecycleState.lastActivityTimeMs = System.currentTimeMillis()
        try {
            eventChannel.send(event)
        } catch (_: ClosedSendChannelException) {
            // Session was closed between the @Volatile check and the send — safe to drop
        }
    }

    fun createAssistantMessage(
        modelID: String?,
        providerID: String?,
        serverMessageId: String? = null,
        /** When true, [ctx] is reset directly on the event processing coroutine
         *  (caller is processEventInternal). When false, a [SseEvent.ResetTurn]
         *  control event is sent through [eventChannel] so the reset runs on the
         *  event processing coroutine — eliminating the cross-coroutine race on
         *  ctx fields. */
        fromEventProcessing: Boolean = false
    ): String = stateLock.withLock {
        // NOTE: This is called from two paths:
        // 1. OpenCodeService.sendMessageInternal() (caller's coroutine, holds sendMutex)
        // 2. processEventInternal() auto-create (event processing coroutine, activeMessageId == null)
        //
        // Thread-safety: ctx field mutations must run on the event processing
        // coroutine (single-writer). External callers send a ResetTurn control
        // event through eventChannel; the auto-create path (fromEventProcessing=true)
        // resets directly since it's already on the event processing coroutine.
        val id = serverMessageId ?: generateId()

        if (fromEventProcessing) {
            // Already on the event processing coroutine — reset and set ctx directly.
            resetTurnState()
            turnLifecycleState.activeMessageId = id
            turnLifecycleState.activeServerMessageId = serverMessageId
            turnLifecycleState.modelID = modelID
            turnLifecycleState.providerID = providerID
            turnLifecycleState.isStreaming = true
        } else {
            // Send ResetTurn carrying the new turn's identity. The event-processing
            // coroutine will drain stale events, resetTurnState(), then apply these
            // fields atomically — eliminating the window where activeMessageId is null
            // (which previously caused a duplicate auto-create when the first SSE
            // content event arrived).
            //
            // Stale-event draining is done in the ResetTurn handler (consumer side)
            // rather than here (producer side) to close the window where an SSE event
            // could be enqueued between the drain completing and ResetTurn being sent.
            // The single-reader event coroutine owns both the drain and the reset.
            //
            // We do NOT set ctx fields here; the event coroutine owns them.
            val resetEvent = SseEvent.ResetTurn(
                sessionId = sessionId,
                newTurnMessageId = id,
                newTurnServerMessageId = serverMessageId,
                newTurnModelID = modelID,
                newTurnProviderID = providerID,
            )
            val sendResult = eventChannel.trySend(resetEvent)
            if (sendResult.isFailure) {
                // Channel is full (1024 capacity) or closed. Do NOT drain the channel
                // and do NOT write ctx fields directly — that would race with the event
                // processing coroutine, which mutates ctx fields WITHOUT acquiring
                // stateLock (it relies on single-writer serialization via the channel).
                //
                // Instead, drop the ResetTurn. The event processing coroutine's
                // auto-create logic (the `needsNewMessage` check in processEventInternal)
                // will create the assistant message when the first content-bearing SSE
                // event arrives, using the serverMessageId from that event. This is the
                // same path used for child/subagent sessions and is well-tested.
                //
                // The only downside is that turnLifecycleState.isStreaming won't be true until the first
                // event arrives, but that's a sub-millisecond window in practice.
                //
                // PENDING-TURN FALLBACK: Store the turn identity so the event processing
                // coroutine can apply it at the start of processEventInternal. This closes
                // the window where activeMessageId is null and a duplicate auto-create
                // could fire when the first content-bearing SSE event arrives.
                pendingTurnIdentity = PendingTurnIdentity(id, serverMessageId, modelID, providerID)
                logger.warn { "[ACP] createAssistantMessage: eventChannel FULL — ResetTurn dropped, pendingTurnIdentity stored + auto-create will handle it. id=$id" }
            }
        }

        textStreaming.cancelResegmentJob()

        firstTextSegmented = false
        lastAccessTime = System.currentTimeMillis()
        logger.info { "[ACP] createAssistantMessage: id=$id, serverMessageId=$serverMessageId, fromEventProcessing=$fromEventProcessing" }

        val message = ChatMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(),
            isStreaming = true,
            state = MessageState.Created,
            timestamp = System.currentTimeMillis(),
            modelID = modelID,
            providerID = providerID,
            serverMessageId = serverMessageId,
        )
        addMessage(message)
        id
    }

    /**
     * Adopt a streaming assistant message that was loaded from REST (e.g., child session
     * cached via ensureSessionCached while still streaming). Sets turnLifecycleState.activeMessageId so
     * subsequent SSE events (TextChunk, ToolUse, etc.) are routed to this message.
     * Does NOT create a new message — the message already exists in the map from REST fetch.
     */
    fun adoptStreamingContext(messageId: String, modelID: String?, providerID: String?) = stateLock.withLock {
        // Send through the event channel to maintain single-writer invariant.
        // The event processing coroutine owns ctx field mutations — writing
        // directly here would race with it (the reader doesn't hold stateLock).
        val resetEvent = SseEvent.ResetTurn(
            sessionId = sessionId,
            newTurnMessageId = messageId,
            newTurnServerMessageId = null,
            newTurnModelID = modelID,
            newTurnProviderID = providerID,
        )
        val sendResult = eventChannel.trySend(resetEvent)
        if (sendResult.isFailure) {
            // Channel full — store as pending turn identity (same fallback as createAssistantMessage)
            pendingTurnIdentity = PendingTurnIdentity(messageId, null, modelID, providerID)
            logger.warn { "[ACP] adoptStreamingContext: eventChannel FULL — ResetTurn dropped, pendingTurnIdentity stored" }
        }
        firstTextSegmented = false
        textStreaming.cancelResegmentJob()
        lastAccessTime = System.currentTimeMillis()
        logger.info { "[ACP] adoptStreamingContext: id=$messageId, modelID=$modelID, providerID=$providerID (sent via ResetTurn)" }
    }

    fun completeStreaming(messageId: String) = stateLock.withLock {
        // BY DESIGN: completeStreaming only finalizes the ACTIVE streaming message.
        // Old messages from previous turns are already finalized via finalizeStreaming
        // (triggered by Stop/MessageFinalized/SessionIdle events). If messageId
        // doesn't match turnLifecycleState.activeMessageId, this is a stale call for an already-
        // finalized message — skip it. By design — completeStreaming finalizes the
        // active streaming message; old turns are finalized via Stop/MessageFinalized
        // events.
        if (messageId != turnLifecycleState.activeMessageId) {
            logger.warn { "[ACP] completeStreaming: SKIP messageId=$messageId != activeMessageId=${turnLifecycleState.activeMessageId}" }
            return@withLock
        }
        val textKeysBefore = if (logger.isDebugEnabled()) {
            _messages.value[messageId]?.parts?.keys?.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }?.toSet()
        } else null
        if (logger.isDebugEnabled()) {
            logger.debug { "[ACP] completeStreaming: START msg=$messageId partsBefore=${_messages.value[messageId]?.parts?.size} textKeys=$textKeysBefore" }
        }
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        textStreaming.resegmentFinal(messageId)
        turnLifecycleState.isStreaming = false
        updateMessage(messageId) { it.copy(isStreaming = false, state = MessageState.Completed) }
        if (logger.isDebugEnabled()) {
            val textKeysAfter = _messages.value[messageId]?.parts?.keys?.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }?.toSet()
            logger.debug { "[ACP] completeStreaming: END msg=$messageId partsAfter=${_messages.value[messageId]?.parts?.size} textKeys=$textKeysAfter" }
            if (textKeysBefore != null && textKeysAfter != null && textKeysBefore != textKeysAfter) {
                logger.warn { "[ACP] completeStreaming KEYS CHANGED: msg=$messageId before=$textKeysBefore after=$textKeysAfter removed=${textKeysBefore - textKeysAfter} added=${textKeysAfter - textKeysBefore}" }
            }
        }
        emitStreamingCompleted(messageId)
    }

    fun abortStreaming(reason: String) = stateLock.withLock {
        val msgId = turnLifecycleState.activeMessageId ?: return@withLock
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        turnLifecycleState.errorMessage = reason
        turnLifecycleState.isStreaming = false
        updateMessage(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(reason)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Aborted)
        }
        emitStreamingCompleted(msgId)
        _signals.tryEmit(UiSignal.Error(msgId, reason))
    }

    fun addMessage(message: ChatMessage) = messageMap.add(message)

    /**
     * Remove a message from the cache by its server message ID.
     * Used when the server sends message.removed (e.g., after compaction).
     * The message map is keyed by local ID, so we search by [ChatMessage.serverMessageId].
     */
    fun removeMessageByServerId(serverMessageId: String) = messageMap.removeByServerId(serverMessageId)

    /**
     * Replace all messages in the cache with a fresh set from the server.
     * Used after auto-compaction (session.compacted) when the local cache is stale.
     * Rebuilds the tool call index from the new message set.
     */
    fun replaceAllMessages(newMessages: List<ChatMessage>) = messageMap.replaceAll(newMessages)

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>? = null) =
        toolCallStateManager.updateStatus(toolCallId, status, output)

    fun setToolPartState(toolCallId: String, state: PartState) =
        toolCallStateManager.setState(toolCallId, state)

    fun updateServerMessageId(messageId: String, serverMessageId: String) = stateLock.withLock {
        // Always update the message's serverMessageId field — this is a message-level
        // operation that doesn't depend on ctx (which may be set asynchronously by
        // ResetTurn on the event-processing coroutine).
        updateMessage(messageId) { it.copy(serverMessageId = serverMessageId) }

        // Only update turnLifecycleState.activeServerMessageId if ctx still refers to this message.
        // If ResetTurn hasn't been processed yet, turnLifecycleState.activeMessageId may be null or
        // point to a previous turn — in that case, the ResetTurn event carries
        // newTurnServerMessageId=null, and the first SSE event will set it.
        // If ctx already learned the server ID from an earlier SSE event, keep it.
        if (messageId == turnLifecycleState.activeMessageId) {
            if (turnLifecycleState.activeServerMessageId == null) {
                turnLifecycleState.activeServerMessageId = serverMessageId
                logger.info { "[ACP] updateServerMessageId: msg=$messageId → serverId=$serverMessageId" }
            } else if (turnLifecycleState.activeServerMessageId != serverMessageId) {
                logger.warn { "[ACP] updateServerMessageId: server ID mismatch — HTTP=$serverMessageId, SSE=${turnLifecycleState.activeServerMessageId}. Keeping SSE value." }
            }
        } else {
            logger.info { "[ACP] updateServerMessageId: ctx not yet synced (msg=$messageId, activeMessageId=${turnLifecycleState.activeMessageId}) — message field updated, ctx will be set by ResetTurn or first SSE event" }
        }
    }

    fun setLastUserText(text: String?) = stateLock.withLock {
        turnLifecycleState.lastUserText = text
    }

    fun close() {
        // Non-blocking close — never blocks EDT.
        // Set closed flag first to prevent new events from being processed.
        // Cancel jobs (cooperative — they stop at next suspension point).
        // Close channel (non-blocking — prevents new events from being enqueued).
        // Complete responseDeferred unconditionally (prevents sendMutex leak).
        stateLock.withLock {
            if (closed) return@withLock
            closed = true
        }
        eventProcessingJob?.cancel()
        eventProcessingJob = null
        textStreaming.cancelResegmentJob()
        turnLifecycleState.pendingStopJob?.cancel()
        turnLifecycleState.pendingStopJob = null
        textStreaming.cancelRevealJobs()
        signalForwardJob?.cancel()
        signalForwardJob = null
        eventChannel.close()
        _pendingPermission.value = null
        _pendingSelection.value = null
        responseDeferred?.completeExceptionally(
            CancellationException("Session $sessionId closed")
        )
        responseDeferred = null
    }

    // ── Internal: Event Processing ──────────────────────────────────────────

    private fun startCoroutines() {
        // Use Dispatchers.Default (NOT EDT) for event processing.
        // processEventInternal() does CPU-intensive work (markdown parsing, JSON
        // manipulation, message map mutations) that blocks the UI thread when on EDT.
        // StateFlow updates are thread-safe — Compose recomposes on EDT automatically.
        // Serialization is guaranteed by the Channel<BUFFERED> (events processed one at a time).
        eventProcessingJob = scope.launch(Dispatchers.Default) {
            for (event in eventChannel) {
                logger.info { "[ACP] processEvent DEQUEUE: ${event::class.simpleName} sid=${event.sessionId}" }
                processEventInternal(event)
            }
        }
    }

    private fun processEventInternal(event: SseEvent) {
        // Apply a pending turn identity stored when createAssistantMessage's ResetTurn
        // trySend failed (channel full). This closes the window where activeMessageId
        // was null between the dropped ResetTurn and the first content-bearing SSE event,
        // which previously caused a duplicate auto-create. Resetting here (on the event
        // processing coroutine) is single-writer safe — ctx fields are owned by this
        // coroutine. We do NOT drain the channel; stale events are skipped by the
        // server-ID routing check below (same as the ResetTurn handler).
        val pending = pendingTurnIdentity
        if (pending != null) {
            pendingTurnIdentity = null
            resetTurnState()
            turnLifecycleState.activeMessageId = pending.messageId
            if (pending.serverMessageId != null) turnLifecycleState.activeServerMessageId = pending.serverMessageId
            turnLifecycleState.modelID = pending.modelID
            turnLifecycleState.providerID = pending.providerID
            turnLifecycleState.isStreaming = true
            logger.info { "[ACP] processEventInternal: applied pendingTurnIdentity msg=${pending.messageId} serverMsg=${pending.serverMessageId}" }
        }

        // Events that don't require an active streaming message
        when (event) {
            // Internal control event — reset ctx for a new streaming turn.
            // Runs on the event processing coroutine, eliminating the cross-coroutine
            // race between external callers (under stateLock) and event processing.
            is SseEvent.ResetTurn -> {
                // Do NOT drain the channel — draining can discard new-turn SSE events that
                // arrived between ResetTurn send and this handler executing (the window includes
                // the network round-trip for sendMessageAsync). Stale events from the previous
                // turn are processed normally but skipped by the server-ID routing check below
                // (activeServerId != null && eventServerId != activeServerId → SKIP), because
                // ctx was reset and the stale events carry the old turn's messageId.
                resetTurnState()
                // Apply the new turn's identity atomically after clearing stale state.
                // This closes the window where activeMessageId was null between reset
                // and the first SSE content event (which caused duplicate auto-create).
                event.newTurnMessageId?.let { turnLifecycleState.activeMessageId = it }
                // Only set activeServerMessageId if the event carries one — don't
                // overwrite a value that updateServerMessageId may have already set
                // (e.g. if the HTTP response arrived and was processed before ResetTurn).
                event.newTurnServerMessageId?.let { turnLifecycleState.activeServerMessageId = it }
                turnLifecycleState.modelID = event.newTurnModelID
                turnLifecycleState.providerID = event.newTurnProviderID
                if (event.newTurnMessageId != null) {
                    turnLifecycleState.isStreaming = true
                }
                return
            }
            is SseEvent.TodoUpdated -> {
                val todos = event.todos.map { todo ->
                    TodoItem(content = todo.content, status = todo.status, priority = todo.priority)
                }
                _signals.tryEmit(UiSignal.TodoUpdated(todos))
                return
            }
            is SseEvent.QuestionAsked -> {
                handleQuestionAsked(event)
                return
            }
            is SseEvent.SessionCreated -> {
                _signals.tryEmit(UiSignal.SessionCreated(event.sessionId))
                return
            }
            is SseEvent.SessionIdle -> {
                // Backstop: if the server says it's idle but we're still streaming,
                // finalize. This handles the case where message.updated didn't have
                // a finish field or the messageId was missing.
                //
                // TOCTOU RACE (resolved, benign): There's a TOCTOU window between
                // reading turnLifecycleState.isStreaming here and finalizeStreaming acquiring
                // stateLock. A concurrent finalizeStreaming (from a Stop/
                // MessageFinalized event) could finalize first. The race is benign
                // because both paths check `turnLifecycleState.isStreaming` inside stateLock.withLock
                // (finalizeStreaming line ~1563, completeStreaming, etc.), and
                // emitStreamingCompleted has a `streamingCompletedEmitted` guard that
                // prevents double-emission. So at most one finalization succeeds; the
                // other is a no-op. Race is benign — both paths check turnLifecycleState.isStreaming
                // under stateLock; streamingCompletedEmitted prevents double-emission.
                if (turnLifecycleState.isStreaming) {
                    val msgId = turnLifecycleState.activeMessageId
                    if (msgId != null) {
                        logger.info { "[ACP] SessionIdle backstop: finalizing stuck streaming state" }
                        finalizeStreaming(msgId, "idle")
                    }
                }
                return
            }
            is SseEvent.MessageRemoved -> {
                // Remove the message from the local cache by server message ID
                val serverMsgId = event.messageId ?: return
                removeMessageByServerId(serverMsgId)
                return
            }
            is SseEvent.UserMessage -> { return }
            is SseEvent.Plan -> { return }
            is SseEvent.MessageComplete -> { return }
            is SseEvent.Ignored -> { return }
            is SseEvent.SessionError -> {
                handleSessionError(event)
                return
            }
            is SseEvent.SessionCompacted -> {
                // Handled by SessionManager (refreshes messages via REST + recomputes context).
                // No local state mutation needed in SessionState.
                return
            }
            else -> { /* handled below */ }
        }

        // Cancel pending debounced Stop ONLY for content-bearing events that indicate
        // continued generation. Metadata events (MessageFinalized without stopReason,
        // ToolResult, StepFinish, Snapshot, Compaction, Agent) must NOT cancel the
        // debounce — they can arrive after the final Stop and would otherwise prevent
        // finalization, leaving isStreaming=true forever (the "stuck generation" bug).
        //
        // NOTE: This list is intentionally DIFFERENT from isContentEvent (line 603-609).
        // isContentEvent includes ToolResult/StepFinish/Snapshot/Compaction for auto-create
        // logic. isGenerationEvent excludes them because they don't indicate the LLM is
        // still generating text. If you add a new event type, consider BOTH lists.
        val isGenerationEvent = event is SseEvent.TextChunk
            || event is SseEvent.TextReplace
            || event is SseEvent.ThinkingChunk
            || event is SseEvent.ThinkingReplace
            || event is SseEvent.ToolUse
            || event is SseEvent.Patch
            || event is SseEvent.AssistantFile
            || event is SseEvent.AssistantImage
            || event is SseEvent.Retry
            || event is SseEvent.Subtask
        if (isGenerationEvent) {
            turnLifecycleState.pendingStopJob?.cancel()
            turnLifecycleState.pendingStopJob = null
        }

        // Auto-create or rotate assistant message for child/subagent sessions.
        // Their SSE events arrive while the parent is streaming, but createAssistantMessage
        // was never called for the child's SessionState. Also handles the case where
        // adoptStreamingContext adopted a completed message but a new message is streaming.
        // Only auto-create for content-bearing events — lifecycle events like
        // MessageFinalized/Stop don't carry content and shouldn't create messages.
        val eventServerId = event.messageId
        val isContentEvent = event is SseEvent.TextChunk || event is SseEvent.TextReplace
            || event is SseEvent.ThinkingChunk || event is SseEvent.ThinkingReplace
            || event is SseEvent.ToolUse || event is SseEvent.ToolResult
            || event is SseEvent.Patch || event is SseEvent.Agent
            || event is SseEvent.StepFinish || event is SseEvent.Compaction
            || event is SseEvent.Snapshot || event is SseEvent.AssistantFile
            || event is SseEvent.AssistantImage
        val needsNewMessage = when {
            // ToolResult for a known tool call (in toolCallIndex) belongs to a previous
            // turn's message — don't auto-create a new message. The ToolResult handler
            // routes via toolCallIndex. This prevents a spurious third message when a
            // child task completes after the user steered/sent a follow-up.
            // Also skip if the toolCallId is NOT in the index (evicted) and there's no
            // active message — a stale ToolResult arriving after eviction + finalization
            // should NOT create a spurious new assistant message.
            event is SseEvent.ToolResult && toolCallState.toolCallIndex.containsKey(event.toolCallId) -> false
            event is SseEvent.ToolResult && turnLifecycleState.activeMessageId == null -> false
            turnLifecycleState.activeMessageId == null && isContentEvent -> true // No streaming context at all
            eventServerId != null && eventServerId != turnLifecycleState.activeServerMessageId -> {
                // Note: we compare eventServerId (server namespace) against
                // activeServerMessageId (server namespace) — NOT against
                // activeMessageId (local namespace). The activeMessageId check is
                // intentionally omitted because in the normal sendMessageInternal path,
                // activeMessageId is a random generateId() that never matches a server
                // ID. The auto-create path sets activeMessageId = serverMessageId, but
                // that case is covered by the first branch (activeMessageId == null).
                if (turnLifecycleState.activeServerMessageId == null) {
                    false
                } else {
                    // A new message is streaming — the event's server messageId differs from
                    // the currently active one. This happens for child/subagent sessions where
                    // adoptStreamingContext adopted a REST-loaded message but the server is now
                    // streaming a different message.
                    logger.info { "[ACP] New message detected for session $sessionId: eventServerId=$eventServerId vs activeMsgId=${turnLifecycleState.activeMessageId}/activeServerId=${turnLifecycleState.activeServerMessageId}" }
                    // Finalize the current streaming message before starting a new one
                    val currentId = turnLifecycleState.activeMessageId
                    if (currentId != null && turnLifecycleState.isStreaming) {
                        finalizeStreaming(currentId, "new_message")
                    }
                    true
                }
            }
            else -> false
        }
        if (needsNewMessage) {
            logger.info { "[ACP] Auto-creating assistant message for session $sessionId (triggered by ${event::class.simpleName}, serverMsgId=$eventServerId)" }
            createAssistantMessage(modelID = null, providerID = null, serverMessageId = eventServerId, fromEventProcessing = true)
        }

        val msgId = turnLifecycleState.activeMessageId
        if (msgId == null) {
            logger.info { "[ACP] processEvent DROP: ${event::class.simpleName} — activeMessageId is null even after auto-create" }
            return
        }

        // Server ID routing check
        val activeServerId = turnLifecycleState.activeServerMessageId
        val isCrossMessageEvent = event is SseEvent.ToolResult || event is SseEvent.Permission
        logger.info { "[ACP] processEvent ROUTE: ${event::class.simpleName} activeMsgId=$msgId activeServerId=$activeServerId eventServerId=$eventServerId isCross=$isCrossMessageEvent" }
        if (!isCrossMessageEvent && activeServerId != null && eventServerId != null && eventServerId != activeServerId) {
            logger.info { "[ACP] processEvent SKIP: event messageId=$eventServerId != active=$activeServerId" }
            return
        }

        when (event) {
            // ── Thinking ──────────────────────────────────────────────────
            is SseEvent.ThinkingChunk -> handleThinkingChunk(event, msgId)
            is SseEvent.ThinkingReplace -> handleThinkingReplace(event, msgId)

            // ── Text ──────────────────────────────────────────────────────
            is SseEvent.TextChunk -> handleTextChunk(event, msgId)
            is SseEvent.TextReplace -> handleTextReplace(event, msgId)

            // ── Tool calls ───────────────────────────────────────────────
            is SseEvent.ToolUse -> handleToolUse(event, msgId)
            is SseEvent.ToolResult -> handleToolResult(event, msgId)
            is SseEvent.Permission -> handlePermission(event, msgId)
            is SseEvent.PermissionReplied -> handlePermissionReplied(event)

            // ── Stop ─────────────────────────────────────────────────────
            is SseEvent.Stop -> handleStop(event, msgId)

            // ── MessageFinalized ─────────────────────────────────────────
            is SseEvent.MessageFinalized -> handleMessageFinalized(event, msgId)

            // ── Error ─────────────────────────────────────────────────────
            is SseEvent.Error -> handleError(event, msgId)

            // ── Patch ─────────────────────────────────────────────────────
            is SseEvent.Patch -> handlePatch(event, msgId)

            // ── Agent ─────────────────────────────────────────────────────
            is SseEvent.Agent -> handleAgent(event, msgId)

            // ── Retry ─────────────────────────────────────────────────────
            is SseEvent.Retry -> handleRetry(event, msgId)

            // ── Compaction ────────────────────────────────────────────────
            is SseEvent.Compaction -> handleCompaction(event, msgId)

            // ── Snapshot ──────────────────────────────────────────────────
            is SseEvent.Snapshot -> { /* internal state marker */ }

            // ── StepFinish ───────────────────────────────────────────────
            is SseEvent.StepFinish -> handleStepFinish(event, msgId)

            // ── Subtask ──────────────────────────────────────────────────
            is SseEvent.Subtask -> { /* informational */ }

            // ── Assistant files/images ────────────────────────────────────
            is SseEvent.AssistantFile -> handleAssistantFile(event, msgId)
            is SseEvent.AssistantImage -> handleAssistantImage(event, msgId)

            // Unreachable — handled in first when block
            is SseEvent.ResetTurn,
            is SseEvent.Ignored,
            is SseEvent.MessageComplete,
            is SseEvent.MessageRemoved,
            is SseEvent.Plan,
            is SseEvent.QuestionAsked,
            is SseEvent.SessionCompacted,
            is SseEvent.SessionCreated,
            is SseEvent.SessionIdle,
            is SseEvent.SessionError,
            is SseEvent.TodoUpdated,
            is SseEvent.UserMessage -> { /* already returned above */ }
        }
    }

    // ── Per-event-type handler methods (Phase 5: decomposed from processEventInternal) ──

    private fun addSimplePart(msgId: String, key: String, part: MessagePart) {
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[key] = part
            msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
        }
    }

    private fun handlePatch(event: SseEvent.Patch, msgId: String) {
        toolCallState.activePatches.add(event)
        addSimplePart(msgId, "patch_${toolCallState.activePatches.size - 1}", MessagePart.Patch(event.hash, event.files))
    }

    private fun handleAgent(event: SseEvent.Agent, msgId: String) {
        toolCallState.activeAgentName = event.agentName
        toolCallState.activeAgents.add(event)
        addSimplePart(msgId, "agent_${toolCallState.activeAgents.size}", MessagePart.Agent(event.agentName))
    }

    private fun handleRetry(event: SseEvent.Retry, msgId: String) {
        toolCallState.activeRetry = event
        addSimplePart(msgId, "retry_${event.attempt}", MessagePart.Retry(event.attempt, event.maxAttempts, event.error))
    }

    private fun handleCompaction(event: SseEvent.Compaction, msgId: String) {
        toolCallState.activeCompaction = event
        toolCallState.activeCompactions.add(event)
        addSimplePart(msgId, "compaction_${toolCallState.activeCompactions.size}", MessagePart.Compaction(event.summary))
    }

    private fun handleStepFinish(event: SseEvent.StepFinish, msgId: String) {
        toolCallState.activeStepFinish = event
        toolCallState.activeStepFinishes.add(event)
        addSimplePart(msgId, "step_finish_${toolCallState.activeStepFinishes.size}", MessagePart.StepFinish(
            snapshot = event.snapshot,
            inputTokens = event.inputTokens,
            outputTokens = event.outputTokens,
            reasoningTokens = event.reasoningTokens,
            totalCost = event.totalCost,
        ))
    }

    private fun handleAssistantFile(event: SseEvent.AssistantFile, msgId: String) {
        val key = event.partId ?: event.url
        toolCallState.activeAssistantFiles[key] = event
        addSimplePart(msgId, "assistant_file_$key", MessagePart.AssistantFile(event.mime, event.url, event.filename))
    }

    private fun handleAssistantImage(event: SseEvent.AssistantImage, msgId: String) {
        val key = event.partId ?: event.url
        toolCallState.activeAssistantImages[key] = event
        addSimplePart(msgId, "assistant_image_$key", MessagePart.Image(event.mime, event.url, event.filename))
    }

    private fun handleSessionError(event: SseEvent.SessionError) {
        if (turnLifecycleState.isStreaming && turnLifecycleState.activeMessageId != null) {
            val msgId = turnLifecycleState.activeMessageId!!
            textStreaming.freezeThinking()
            textStreaming.flushReveal()
            val reason = event.errorMessage ?: "Session error"
            turnLifecycleState.errorMessage = reason
            turnLifecycleState.isStreaming = false
            messageMap.update(msgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                parts["error"] = MessagePart.Error(reason)
                msg.copy(parts = parts, isStreaming = false, state = MessageState.Failed(reason))
            }
            _signals.tryEmit(UiSignal.Error(msgId, reason))
            emitStreamingCompleted(msgId)
        }
        _signals.tryEmit(UiSignal.SessionError(sessionId, event.errorMessage))
    }

    private fun handleError(event: SseEvent.Error, msgId: String) {
        textStreaming.freezeThinking()
        turnLifecycleState.errorMessage = event.message
        turnLifecycleState.isStreaming = false
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(event.message)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Failed(event.message))
        }
        _signals.tryEmit(UiSignal.Error(msgId, event.message))
        emitStreamingCompleted(msgId)
    }

    private fun handlePermission(event: SseEvent.Permission, msgId: String) {
        toolCallState.toolPartStates[event.toolCallId] = PartState.Pending
        val existingPill = toolCallState.toolCallPills[event.toolCallId]
        if (existingPill != null) {
            toolCallState.toolCallPills[event.toolCallId] = existingPill.copy(status = ToolCallStatus.PENDING)
        }
        val targetMsgId = toolCallState.toolCallIndex[event.toolCallId]
        if (targetMsgId == null) {
            logger.warn { "[ACP] Permission: toolCallId=${event.toolCallId} not in index (evicted?) — skipping to avoid misrouting to activeMessageId" }
            return
        }
        messageMap.update(targetMsgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            val existing = parts[event.toolCallId]
            if (existing is MessagePart.ToolCall) {
                parts[event.toolCallId] = existing.copy(
                    pill = existing.pill.copy(status = ToolCallStatus.PENDING),
                    state = PartState.Pending
                )
            }
            msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
        }
        val realToolName = toolCallState.toolCallPills[event.toolCallId]?.toolName ?: event.action
        val prompt = PermissionPrompt(
            sessionId = sessionId,
            permissionId = event.permissionId,
            toolCallId = event.toolCallId,
            toolName = realToolName,
            description = event.description,
            patterns = event.patterns
        )
        setPendingPermissionPrompt(prompt)
        _signals.tryEmit(UiSignal.PermissionRequested(prompt))
    }

    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        val currentPermId = _pendingPermission.value?.permissionId
        if (currentPermId == null || currentPermId == event.permissionId) {
            setPendingPermission(false)
        }
        if (currentPermId == event.permissionId) {
            _signals.tryEmit(UiSignal.PermissionReplied(
                permissionId = event.permissionId,
                reply = event.reply,
                sessionId = sessionId,
            ))
        } else if (currentPermId == null) {
            logger.debug { "[ACP] permission.replied for already-resolved permission: permissionId=${event.permissionId}, reply=${event.reply} — signal suppressed" }
        }
        logger.info { "[ACP] permission.replied received: permissionId=${event.permissionId}, reply=${event.reply}, currentPermId=$currentPermId, cleared=${currentPermId == null || currentPermId == event.permissionId}" }
    }

    private fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
        if (event.questions.isNotEmpty()) {
            val q = event.questions.first()
            val prompt = SelectionPrompt(
                sessionId = sessionId,
                promptId = event.requestId,
                question = q.question,
                subtitle = q.header.ifBlank { null },
                options = q.options.map { opt ->
                    SelectionOption(title = opt.label, description = opt.description, label = opt.label)
                },
                allowCustomInput = q.custom,
                multiSelect = q.multiple
            )
            setPendingSelectionPrompt(prompt)
            _signals.tryEmit(UiSignal.SelectionRequested(prompt))
        }
    }

    private fun handleStop(event: SseEvent.Stop, msgId: String) {
        finalizeStreaming(msgId, event.stopReason)
    }

    private fun handleMessageFinalized(event: SseEvent.MessageFinalized, msgId: String) {
        val serverMsgId = event.messageId ?: return

        if (serverMsgId != turnLifecycleState.activeServerMessageId && serverMsgId != turnLifecycleState.activeMessageId) {
            if (turnLifecycleState.activeServerMessageId == null && turnLifecycleState.activeMessageId != null && turnLifecycleState.isStreaming) {
                logger.warn { "[ACP] MessageFinalized: adopting serverMsgId=$serverMsgId for activeMsgId=${turnLifecycleState.activeMessageId} (activeServerId was null, firstText=${textStreamingState.firstTextChunkReceived}, pills=${toolCallState.toolCallPills.size}) — content events prove this is the active message" }
                turnLifecycleState.activeServerMessageId = serverMsgId
            } else {
                logger.debug { "[ACP] MessageFinalized for non-active message $serverMsgId — skipping (activeLocal=${turnLifecycleState.activeMessageId}, activeServer=${turnLifecycleState.activeServerMessageId})" }
                return
            }
        }

        val localMsgId = turnLifecycleState.activeMessageId ?: return

        messageMap.update(localMsgId) { msg ->
            msg.copy(
                inputTokens = event.inputTokens ?: msg.inputTokens,
                outputTokens = event.outputTokens ?: msg.outputTokens,
                reasoningTokens = event.reasoningTokens ?: msg.reasoningTokens,
                cacheReadTokens = event.cacheReadTokens ?: msg.cacheReadTokens,
                cacheWriteTokens = event.cacheWriteTokens ?: msg.cacheWriteTokens,
                cost = event.cost ?: msg.cost,
                modelID = event.modelID ?: msg.modelID,
                providerID = event.providerID ?: msg.providerID,
            )
        }

        if (event.stopReason != null) {
            finalizeStreaming(localMsgId, event.stopReason)
        } else {
            _signals.tryEmit(UiSignal.MessageUpdated(localMsgId))
        }
    }

    private fun handleTextChunk(event: SseEvent.TextChunk, msgId: String) {
        if (!textStreamingState.activeThinkingCompleted && textStreamingState.thinkingBuffer.isNotEmpty()) {
            textStreamingState.activeThinkingCompleted = true
            textStreaming.freezeThinking()
        }
        val text = event.text
        if (!textStreamingState.firstTextChunkReceived && text.isNotBlank()) {
            textStreamingState.firstTextChunkReceived = true
            if (event.partId != null) textStreamingState.activeTextPartId = event.partId
            emitStreamingStartedIfNeeded(msgId)
            if (textStreamingState.textSegments.isEmpty()) {
                textStreamingState.textSegments.add(TextSegment(0, null))
            }
            val userText = turnLifecycleState.lastUserText
            // Case-insensitive prefix match: the server sometimes echoes the user's
            // input with different casing. This strips the echo so the assistant's
            // actual response is shown. Risk: if the assistant's response legitimately
            // starts with the same characters as the user's input (different case), the
            // prefix is incorrectly stripped. This is a known trade-off — the server's
            // echo behavior makes case-sensitive matching unreliable.
            if (userText != null && text.startsWith(userText, ignoreCase = true)) {
                textStreamingState.userEchoStripped = true
                val chunk = text.substring(userText.length).trimStart()
                textStreamingState.textBuffer.append(chunk)
                textStreamingState.revealBuffer.append(chunk)
            } else {
                textStreamingState.textBuffer.append(text)
                textStreamingState.revealBuffer.append(text)
            }
        } else {
            if (event.partId != null && event.partId != textStreamingState.activeTextPartId) {
                textStreamingState.activeTextPartId = event.partId
            }
            textStreamingState.textBuffer.append(text)
            textStreamingState.revealBuffer.append(text)
        }
        textStreamingState.sourceComplete = false
        textStreamingState.streamingText.value = textStreamingState.textBuffer.toString()
        textStreaming.startRevealLoop(msgId)
    }

    private fun handleTextReplace(event: SseEvent.TextReplace, msgId: String) {
        if (!textStreamingState.activeThinkingCompleted && textStreamingState.thinkingBuffer.isNotEmpty()) {
            textStreamingState.activeThinkingCompleted = true
            textStreaming.freezeThinking()
        }

        if (textStreamingState.firstTextChunkReceived && textStreamingState.textBuffer.isNotEmpty()) {
            if (event.partId != null && event.partId != textStreamingState.activeTextPartId) {
                textStreamingState.activeTextPartId = event.partId
            }
            logger.debug { "[ACP] TextReplace: skipping (partId=${event.partId}, active=${textStreamingState.activeTextPartId}, bufferLen=${textStreamingState.textBuffer.length})" }
            return
        }

        if (event.text.isEmpty()) {
            logger.debug { "[ACP] TextReplace: skipping (empty text, no prior streamed content)" }
            return
        }
        textStreamingState.textBuffer.clear()
        textStreamingState.textBuffer.append(event.text)
        textStreamingState.revealBuffer.setLength(0)
        textStreamingState.revealBuffer.append(event.text)
        textStreamingState.revealedLen = event.text.length
        textStreamingState.sourceComplete = true
        textStreamingState.revealJob?.cancel()
        textStreamingState.revealJob = null
        textStreamingState.thinkingRevealJob?.cancel()
        textStreamingState.thinkingRevealJob = null
        textStreamingState.userEchoStripped = false
        val userText = turnLifecycleState.lastUserText
        if (userText != null && textStreamingState.textBuffer.toString().startsWith(userText, ignoreCase = true)) {
            textStreamingState.textBuffer.delete(0, userText.length)
            textStreamingState.revealBuffer.delete(0, userText.length)
            textStreamingState.revealedLen = textStreamingState.revealBuffer.length
            textStreamingState.userEchoStripped = true
        }
        textStreamingState.firstTextChunkReceived = true
        if (event.partId != null) textStreamingState.activeTextPartId = event.partId
        if (textStreamingState.textSegments.isEmpty()) {
            textStreamingState.textSegments.add(TextSegment(0, null))
        }
        textStreamingState.streamingText.value = textStreamingState.textBuffer.toString()
        textStreaming.scheduleResegment(msgId)
    }

    private fun handleThinkingChunk(event: SseEvent.ThinkingChunk, msgId: String) {
        if (textStreamingState.activeThinkingCompleted) {
            textStreaming.freezeThinking()
        }
        if (textStreamingState.activeThinkingKey == null) {
            val key = "thinking_${textStreamingState.thinkingPhaseIndex}"
            textStreamingState.activeThinkingKey = key
            textStreamingState.thinkingPhaseIndex++
            emitStreamingStartedIfNeeded(msgId)
            if (textStreamingState.firstTextChunkReceived) {
                textStreamingState.textSegments.add(TextSegment(textStreamingState.textBuffer.length, key))
            }
        }
        textStreamingState.thinkingBuffer.append(event.text)
        textStreamingState.thinkingRevealBuffer.append(event.text)
        textStreamingState.thinkingSourceComplete = false
        textStreaming.startThinkingRevealLoop(msgId)
    }

    private fun handleThinkingReplace(event: SseEvent.ThinkingReplace, msgId: String) {
        if (textStreamingState.activeThinkingCompleted) {
            textStreaming.freezeThinking()
        }
        if (textStreamingState.activeThinkingKey == null) {
            val key = "thinking_${textStreamingState.thinkingPhaseIndex}"
            textStreamingState.activeThinkingKey = key
            textStreamingState.thinkingPhaseIndex++
            emitStreamingStartedIfNeeded(msgId)
            if (textStreamingState.firstTextChunkReceived) {
                textStreamingState.textSegments.add(TextSegment(textStreamingState.textBuffer.length, key))
            }
        }
        textStreamingState.thinkingBuffer.setLength(0)
        textStreamingState.thinkingBuffer.append(event.text)
        textStreamingState.thinkingRevealBuffer.setLength(0)
        textStreamingState.thinkingRevealBuffer.append(event.text)
        textStreamingState.thinkingRevealedLen = event.text.length
        textStreamingState.thinkingSourceComplete = true
        textStreamingState.thinkingRevealJob?.cancel()
        textStreamingState.thinkingRevealJob = null
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[textStreamingState.activeThinkingKey!!] = MessagePart.Thinking(
                content = textStreamingState.thinkingRevealBuffer.substring(0, textStreamingState.thinkingRevealedLen),
                state = if (textStreamingState.activeThinkingCompleted) PartState.Completed else PartState.Streaming
            )
            msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
        }
    }

    private fun handleToolUse(event: SseEvent.ToolUse, msgId: String) {
        val existingPill = toolCallState.toolCallPills[event.toolCallId]
        if (existingPill != null) {
            val updatedPill = existingPill.copy(
                metadata = event.metadata ?: existingPill.metadata,
                title = event.title?.takeIf { it != existingPill.title } ?: existingPill.title,
                input = if (event.input != null && event.input.isNotEmpty()) event.input else existingPill.input,
            )
            toolCallState.toolCallPills[event.toolCallId] = updatedPill
            val targetMsgId = toolCallState.toolCallIndex[event.toolCallId] ?: return
            messageMap.update(targetMsgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                val existing = parts[event.toolCallId]
                if (existing is MessagePart.ToolCall) {
                    parts[event.toolCallId] = existing.copy(pill = updatedPill)
                    msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
                } else msg
            }
            followAgent.dispatchToolUse(
                toolCallId = event.toolCallId,
                toolName = existingPill.toolName,
                toolKind = existingPill.kind,
                input = event.input,
                metadata = event.metadata,
                startTimeMs = existingPill.startTimeMs,
                isDuplicate = true,
                existingPill = existingPill,
            )
            return
        }

        if (!textStreamingState.activeThinkingCompleted && textStreamingState.thinkingBuffer.isNotEmpty()) {
            textStreamingState.activeThinkingCompleted = true
            textStreaming.freezeThinking()
        }
        emitStreamingStartedIfNeeded(msgId)

        toolCallState.toolPartStates[event.toolCallId] = PartState.InProgress
        val baseKind = ToolMapper.toAcpKind(event.toolName)
        val toolKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(event.input) else baseKind
        val resolvedTitle = event.title
            ?: event.input?.let { input ->
                try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
            }
            ?: event.toolName
        logger.info { "[ACP] ToolUse: callID=${event.toolCallId}, toolName=${event.toolName}, title=${event.title}, kind=$toolKind, hasInput=${event.input != null}, inputKeys=${event.input?.keys}, resolvedTitle=$resolvedTitle" }
        val pill = ToolCallPill(
            toolCallId = event.toolCallId,
            toolName = event.toolName,
            title = resolvedTitle,
            kind = toolKind,
            status = ToolCallStatus.IN_PROGRESS,
            input = event.input,
            metadata = event.metadata,
            startTimeMs = System.currentTimeMillis(),
        )
        toolCallState.toolCallPills[event.toolCallId] = pill
        toolCallState.toolCallIndex[event.toolCallId] = msgId

        followAgent.dispatchToolUse(
            toolCallId = event.toolCallId,
            toolName = event.toolName,
            toolKind = toolKind,
            input = event.input,
            metadata = event.metadata,
            startTimeMs = pill.startTimeMs,
            isDuplicate = false,
        )

        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[event.toolCallId] = MessagePart.ToolCall(pill = pill, state = PartState.InProgress)
            msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
        }

        if (textStreamingState.firstTextChunkReceived) {
            textStreamingState.textSegments.add(TextSegment(textStreamingState.textBuffer.length, event.toolCallId))
        }
    }

    private fun handleToolResult(event: SseEvent.ToolResult, msgId: String) {
        toolCallState.toolPartStates[event.toolCallId] = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed
        val existingPill = toolCallState.toolCallPills[event.toolCallId]
        val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
        logger.info { "[ACP] ToolResult: callID=${event.toolCallId}, hasInput=${event.input != null}, inputKeys=${event.input?.keys}, existingKind=${existingPill?.kind}, existingTitle=${existingPill?.title}" }
        if (existingPill != null) {
            val newInput = event.input ?: existingPill.input
            val resolvedKind = if (existingPill.kind == ToolKind.OTHER) {
                ToolMapper.detectKindFromInput(newInput)
            } else existingPill.kind
            val resolvedTitle = newInput?.let { input ->
                try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
            } ?: existingPill.title
            logger.info { "[ACP] ToolResult: callID=${event.toolCallId}, prevKind=${existingPill.kind}, resolvedKind=$resolvedKind, prevTitle=${existingPill.title}, resolvedTitle=$resolvedTitle, hasEventInput=${event.input != null}, eventInputKeys=${event.input?.keys}, hasNewInput=${newInput != null}, newInputKeys=${newInput?.keys}" }
            toolCallState.toolCallPills[event.toolCallId] = existingPill.copy(
                status = resolvedStatus,
                title = resolvedTitle,
                kind = resolvedKind,
                input = newInput,
                output = event.content,
                metadata = event.metadata ?: existingPill.metadata,
            )
            followAgent.dispatchToolResult(
                toolCallId = event.toolCallId,
                resolvedKind = resolvedKind,
                content = event.content,
                isError = event.isError,
                isOrphan = false,
                signals = _signals,
            )
            val targetMsgId = toolCallState.toolCallIndex[event.toolCallId]
            if (targetMsgId == null) {
                logger.warn { "[ACP] ToolResult: toolCallId=${event.toolCallId} not in index (evicted?) — skipping to avoid misrouting to activeMessageId" }
                return
            }
            messageMap.update(targetMsgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                val existing = parts[event.toolCallId]
                if (existing is MessagePart.ToolCall) {
                    parts[event.toolCallId] = existing.copy(
                        pill = existing.pill.copy(
                            status = resolvedStatus,
                            title = resolvedTitle,
                            kind = resolvedKind,
                            input = newInput,
                            output = event.content ?: existing.pill.output,
                            metadata = event.metadata ?: existing.pill.metadata,
                        ),
                        state = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed
                    )
                }
                msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
            }
        } else {
            logger.warn { "[ACP] ToolResult without prior ToolUse for callID=${event.toolCallId} — deriving toolName from kind" }
            val newInput = event.input
            val baseKind = ToolMapper.toAcpKind("tool")
            val resolvedKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(newInput) else baseKind
            val derivedToolName = when (resolvedKind) {
                ToolKind.READ -> "read"
                ToolKind.EDIT -> "edit"
                ToolKind.DELETE -> "delete"
                ToolKind.MOVE -> "move"
                ToolKind.EXECUTE -> "bash"
                ToolKind.SEARCH -> "search"
                ToolKind.FETCH -> "fetch"
                ToolKind.THINK -> "think"
                ToolKind.SWITCH_MODE -> "switch_mode"
                else -> "tool"
            }
            val resolvedTitle = newInput?.let { input ->
                try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
            } ?: derivedToolName
            val newPill = ToolCallPill(
                toolCallId = event.toolCallId,
                toolName = derivedToolName,
                title = resolvedTitle,
                kind = resolvedKind,
                status = resolvedStatus,
                input = newInput,
                output = event.content,
                metadata = event.metadata,
            )
            toolCallState.toolCallPills[event.toolCallId] = newPill
            val existing = toolCallState.toolCallIndex[event.toolCallId]
            val targetMsgId = existing ?: msgId
            if (existing == null) {
                toolCallState.toolCallIndex[event.toolCallId] = msgId
            }
            messageMap.update(targetMsgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                parts[event.toolCallId] = MessagePart.ToolCall(pill = newPill, state = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed)
                msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
            }
            followAgent.dispatchToolResult(
                toolCallId = event.toolCallId,
                resolvedKind = resolvedKind,
                content = event.content,
                isError = event.isError,
                isOrphan = true,
                input = event.input,
                metadata = event.metadata,
                signals = _signals,
            )
        }
    }

    private fun finalizeStreaming(msgId: String, stopReason: String) =
        streamingLifecycle.finalizeStreaming(msgId, stopReason)

    // ── Internal: Helpers ───────────────────────────────────────────────────

    // NOTE: Most callers already hold stateLock. ReentrantLock allows this
    // without deadlocking, but consider extracting a stateLock-free internal
    // variant for hot paths (ToolUse/ToolResult on every SSE event).
    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) =
        messageMap.update(messageId, transform)

    private fun emitStreamingStartedIfNeeded(msgId: String) =
        streamingLifecycle.emitStreamingStartedIfNeeded(msgId)

    private fun emitStreamingCompleted(msgId: String) =
        streamingLifecycle.emitStreamingCompleted(msgId)
}
