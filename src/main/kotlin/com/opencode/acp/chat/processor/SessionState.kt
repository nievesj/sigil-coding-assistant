package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.ToolMapper
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.markdown.MarkdownSegment
import com.opencode.acp.chat.markdown.MarkdownSegmenter
import com.opencode.acp.chat.markdown.StreamHealer
import com.opencode.acp.chat.util.FollowAgentDispatcherInterface
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
 * Thread safety: dual protection model â€”
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
    private val sessionManager: SessionStateContext,
    private val followAgentFactory: (ToolCallState, TurnLifecycleState) -> FollowAgentDispatcherInterface,
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

    /** Internal accessor for SseEventPipeline to emit signals without exposing _signals. */
    internal fun emitSignal(signal: UiSignal) {
        _signals.tryEmit(signal)
    }

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

    /** SSE event dispatch pipeline (extracted from processEventInternal). See TDD Â§4.2.2. */
    internal val sseEventPipeline = SseEventPipeline(logger)

    /** Event channel â€” SSE events buffered for EDT processing.
     *  Declared before collaborators because [messageLifecycle] needs it. */
    private val eventChannel = Channel<SseEvent>(1024)

    /** Whether the first text chunk has been segmented (controls non-debounced
     *  re-segment for responsiveness on the first chunk). Reset in createAssistantMessage().
     *  @Volatile because written by [MessageLifecycleManager.adoptStreamingContext] on the
     *  caller's coroutine and read by the event processing coroutine (via
     *  [TextStreamingManager]'s firstTextSegmentedGet callback). */
    @Volatile internal var firstTextSegmented = false

    // â”€â”€ Collaborators (Phase 1: MessageMapManager + ToolCallStateManager) â”€â”€
    internal val messageMap = MessageMapManager(toolCallState, stateLock, _messages, { closed }, logger)
    internal val toolCallStateManager = ToolCallStateManager(toolCallState, stateLock, messageMap, sessionManager, logger)
    // â”€â”€ Collaborator (Phase 2: FollowAgentDispatcher) â”€â”€
    internal val followAgent: FollowAgentDispatcherInterface = followAgentFactory(toolCallState, turnLifecycleState)
    // â”€â”€ Collaborator (Phase 3: TextStreamingManager) â”€â”€
    internal val textStreaming = TextStreamingManager(textStreamingState, turnLifecycleState, stateLock, scope, messageMap, { firstTextSegmented }, { firstTextSegmented = it }, logger)
    // â”€â”€ Collaborator (Phase 3.5: StreamingLifecycleManager) â”€â”€
    internal val streamingLifecycle = StreamingLifecycleManager(turnLifecycleState, toolCallState, stateLock, scope, messageMap, textStreaming, _signals, { responseDeferred?.complete(Unit); responseDeferred = null }, logger)
    // â”€â”€ Collaborator (Phase 4: PromptStateManager) â”€â”€
    internal val promptState = PromptStateManager(_signals, _pendingPermission, _pendingSelection, sessionId)
    // â”€â”€ Collaborator (Phase 2: MessageLifecycleManager) â”€â”€
    // HIGH-LEVEL streaming message lifecycle (create/adopt/complete/abort/server-ID sync).
    // Sits ABOVE [messageMap] (low-level CRUD). Owns pendingTurnIdentity and the
    // ResetTurn control-event handshake. See TDD Â§4.2.3.
    internal val messageLifecycle = MessageLifecycleManager(
        sessionId = sessionId,
        stateLock = stateLock,
        turnLifecycleState = turnLifecycleState,
        messageMap = messageMap,
        textStreaming = textStreaming,
        streamingLifecycle = streamingLifecycle,
        signals = _signals,
        messages = _messages,
        eventChannel = eventChannel,
        resetTurnState = { resetTurnState() },
        firstTextSegmentedSet = { firstTextSegmented = it },
        lastAccessTimeSet = { lastAccessTime = it },
        logger = logger,
    )

    /** Event processing coroutine (runs on EDT). */
    private var eventProcessingJob: Job? = null

    /** Pending turn identity — delegated to [messageLifecycle].
     *  Read/written by [SseEventPipeline] at the start of processEventInternal. */
    internal var pendingTurnIdentity: MessageLifecycleManager.PendingTurnIdentity?
        get() = messageLifecycle.pendingTurnIdentity
        set(value) { messageLifecycle.pendingTurnIdentity = value }

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

    /** Signal forwarding job â€” forwards signals to global merged flow. */
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
     *  and the read is O(n) â€” brief enough to not cause contention.
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

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun processEvent(event: SseEvent) {
        if (closed) return
        lastAccessTime = System.currentTimeMillis()
        turnLifecycleState.lastActivityTimeMs = System.currentTimeMillis()
        try {
            eventChannel.send(event)
        } catch (_: ClosedSendChannelException) {
            // Session was closed between the @Volatile check and the send â€” safe to drop
        }
    }

    fun createAssistantMessage(
        modelID: String?,
        providerID: String?,
        serverMessageId: String? = null,
        fromEventProcessing: Boolean = false
    ): String = messageLifecycle.createAssistantMessage(modelID, providerID, serverMessageId, fromEventProcessing)

    /**
     * Adopt a streaming assistant message that was loaded from REST (e.g., child session
     * cached via ensureSessionCached while still streaming). Sets turnLifecycleState.activeMessageId so
     * subsequent SSE events (TextChunk, ToolUse, etc.) are routed to this message.
     * Does NOT create a new message — the message already exists in the map from REST fetch.
     */
    fun adoptStreamingContext(messageId: String, modelID: String?, providerID: String?) =
        messageLifecycle.adoptStreamingContext(messageId, modelID, providerID)

    fun completeStreaming(messageId: String) = messageLifecycle.completeStreaming(messageId)

    fun abortStreaming(reason: String) = messageLifecycle.abortStreaming(reason)

    /**
     * Abort streaming with a fallback message ID. Used when [createAssistantMessage] was called
     * with fromEventProcessing=false (the sendMessageInternal path) and the ResetTurn control
     * event hasn't been processed yet — so [turnLifecycleState.activeMessageId] is still null.
     * Without the fallback, [abortStreaming] would no-op and the assistant message would be
     * stuck in isStreaming=true forever (invisible but never finalized — a "ghost message").
     */
    fun abortStreamingWithFallback(reason: String, fallbackMessageId: String) =
        messageLifecycle.abortStreamingWithFallback(reason, fallbackMessageId)

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

    fun updateServerMessageId(messageId: String, serverMessageId: String) =
        messageLifecycle.updateServerMessageId(messageId, serverMessageId)

    fun setLastUserText(text: String?) = messageLifecycle.setLastUserText(text)

    fun close() {
        // Non-blocking close â€” never blocks EDT.
        // Set closed flag first to prevent new events from being processed.
        // Cancel jobs (cooperative â€” they stop at next suspension point).
        // Close channel (non-blocking â€” prevents new events from being enqueued).
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

    // â”€â”€ Internal: Event Processing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun startCoroutines() {
        // Use Dispatchers.Default (NOT EDT) for event processing.
        // SseEventPipeline.process() does CPU-intensive work (markdown parsing, JSON
        // manipulation, message map mutations) that blocks the UI thread when on EDT.
        // StateFlow updates are thread-safe â€” Compose recomposes on EDT automatically.
        // Serialization is guaranteed by the Channel<BUFFERED> (events processed one at a time).
        eventProcessingJob = scope.launch(Dispatchers.Default) {
            for (event in eventChannel) {
                logger.info { "[ACP] processEvent DEQUEUE: ${event::class.simpleName} sid=${event.sessionId}" }
                sseEventPipeline.process(event, SseEventContext(this@SessionState))
            }
        }
    }

    // â”€â”€ Per-event-type handler methods (Phase 5: decomposed from processEventInternal) â”€â”€

    private fun addSimplePart(msgId: String, key: String, part: MessagePart) {
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[key] = part
            msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
        }
    }

    internal fun handlePatch(event: SseEvent.Patch, msgId: String) {
        toolCallState.activePatches.add(event)
        addSimplePart(msgId, "patch_${toolCallState.activePatches.size - 1}", MessagePart.Patch(event.hash, event.files))
    }

    internal fun handleAgent(event: SseEvent.Agent, msgId: String) {
        toolCallState.activeAgentName = event.agentName
        toolCallState.activeAgents.add(event)
        addSimplePart(msgId, "agent_${toolCallState.activeAgents.size}", MessagePart.Agent(event.agentName))
    }

    internal fun handleRetry(event: SseEvent.Retry, msgId: String) {
        toolCallState.activeRetry = event
        addSimplePart(msgId, "retry_${event.attempt}", MessagePart.Retry(event.attempt, event.maxAttempts, event.error))
    }

    internal fun handleCompaction(event: SseEvent.Compaction, msgId: String) {
        toolCallState.activeCompaction = event
        toolCallState.activeCompactions.add(event)
        addSimplePart(msgId, "compaction_${toolCallState.activeCompactions.size}", MessagePart.Compaction(event.summary))
    }

    internal fun handleStepFinish(event: SseEvent.StepFinish, msgId: String) {
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

    internal fun handleAssistantFile(event: SseEvent.AssistantFile, msgId: String) {
        val key = event.partId ?: event.url
        toolCallState.activeAssistantFiles[key] = event
        addSimplePart(msgId, "assistant_file_$key", MessagePart.AssistantFile(event.mime, event.url, event.filename))
    }

    internal fun handleAssistantImage(event: SseEvent.AssistantImage, msgId: String) {
        val key = event.partId ?: event.url
        toolCallState.activeAssistantImages[key] = event
        addSimplePart(msgId, "assistant_image_$key", MessagePart.Image(event.mime, event.url, event.filename))
    }

    internal fun handleSessionError(event: SseEvent.SessionError) {
        if (turnLifecycleState.isStreaming && turnLifecycleState.activeMessageId != null) {
            val msgId = turnLifecycleState.activeMessageId ?: return
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
            emitStreamingCompleted(msgId, naturalCompletion = false)
        }
        _signals.tryEmit(UiSignal.SessionError(sessionId, event.errorMessage))
    }

    internal fun handleError(event: SseEvent.Error, msgId: String) {
        textStreaming.freezeThinking()
        turnLifecycleState.errorMessage = event.message
        turnLifecycleState.isStreaming = false
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(event.message)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Failed(event.message))
        }
        _signals.tryEmit(UiSignal.Error(msgId, event.message))
        emitStreamingCompleted(msgId, naturalCompletion = false)
    }

    internal fun handlePermission(event: SseEvent.Permission, msgId: String) {
        val targetMsgId = toolCallState.toolCallIndex[event.toolCallId]
        if (targetMsgId == null) {
            // The toolCallId is not in the index (likely evicted). We must NOT return early:
            // the server's Deferred promise for this tool call would hang indefinitely waiting
            // for a response that never comes. Instead, skip the pill/part updates (which
            // require the message to exist) but still surface the permission prompt so the
            // user can approve/reject it and unblock the agent.
            logger.warn { "[ACP] Permission: toolCallId=${event.toolCallId} not in index (evicted?) — surfacing prompt without pill update to avoid dropping the server's permission request" }
        } else {
            toolCallState.toolPartStates[event.toolCallId] = PartState.Pending
            val existingPill = toolCallState.toolCallPills[event.toolCallId]
            if (existingPill != null) {
                toolCallState.toolCallPills[event.toolCallId] = existingPill.copy(status = ToolCallStatus.PENDING)
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

    internal fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
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
            logger.debug { "[ACP] permission.replied for already-resolved permission: permissionId=${event.permissionId}, reply=${event.reply} â€” signal suppressed" }
        }
        logger.info { "[ACP] permission.replied received: permissionId=${event.permissionId}, reply=${event.reply}, currentPermId=$currentPermId, cleared=${currentPermId == null || currentPermId == event.permissionId}" }
    }

    internal fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
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

    internal fun handleStop(event: SseEvent.Stop, msgId: String) {
        finalizeStreaming(msgId, event.stopReason)
    }

    internal fun handleMessageFinalized(event: SseEvent.MessageFinalized, msgId: String) {
        val serverMsgId = event.messageId ?: return

        if (serverMsgId != turnLifecycleState.activeServerMessageId && serverMsgId != turnLifecycleState.activeMessageId) {
            if (turnLifecycleState.activeServerMessageId == null && turnLifecycleState.activeMessageId != null && turnLifecycleState.isStreaming) {
                logger.warn { "[ACP] MessageFinalized: adopting serverMsgId=$serverMsgId for activeMsgId=${turnLifecycleState.activeMessageId} (activeServerId was null, firstText=${textStreamingState.firstTextChunkReceived}, pills=${toolCallState.toolCallPills.size}) â€” content events prove this is the active message" }
                turnLifecycleState.activeServerMessageId = serverMsgId
            } else {
                logger.debug { "[ACP] MessageFinalized for non-active message $serverMsgId â€” skipping (activeLocal=${turnLifecycleState.activeMessageId}, activeServer=${turnLifecycleState.activeServerMessageId})" }
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

    internal fun handleTextChunk(event: SseEvent.TextChunk, msgId: String) {
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
            // Case-sensitive prefix match: the server echoes the user's input verbatim,
            // so a case-sensitive match correctly identifies the echo. Case-insensitive
            // matching caused data loss when the assistant's response legitimately
            // started with the same characters as the user's input but in different case
            // (e.g., user "CSS is great" â†’ assistant "css is great for styling" had
            // "CSS is great" stripped, leaving " for styling").
            if (userText != null && text.startsWith(userText, ignoreCase = false)) {
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

    internal fun handleTextReplace(event: SseEvent.TextReplace, msgId: String) {
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
        if (userText != null && textStreamingState.textBuffer.toString().startsWith(userText, ignoreCase = false)) {
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

    internal fun handleThinkingChunk(event: SseEvent.ThinkingChunk, msgId: String) {
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

    internal fun handleThinkingReplace(event: SseEvent.ThinkingReplace, msgId: String) {
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

    internal fun handleToolUse(event: SseEvent.ToolUse, msgId: String) {
        val existingPill = toolCallState.toolCallPills[event.toolCallId]
        if (existingPill != null) {
            val updatedPill = existingPill.copy(
                metadata = event.metadata ?: existingPill.metadata,
                title = event.title?.takeIf { it != existingPill.title } ?: existingPill.title,
                // An empty-but-non-null input from the server is treated as 'no update' —
                // only a non-empty input replaces the existing pill's input.
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
                // Intentionally broad catch — should ideally only catch JsonException,
                // but kept broad for safety against unexpected input shapes.
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

    internal fun handleToolResult(event: SseEvent.ToolResult, msgId: String) {
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
                // Intentionally broad catch — should ideally only catch JsonException,
                // but kept broad for safety against unexpected input shapes.
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
                logger.warn { "[ACP] ToolResult: toolCallId=${event.toolCallId} not in index (evicted?) â€” skipping to avoid misrouting to activeMessageId" }
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
            logger.warn { "[ACP] ToolResult without prior ToolUse for callID=${event.toolCallId} â€” deriving toolName from kind" }
            val newInput = event.input
            val baseKind = ToolMapper.toAcpKind("tool")
            val resolvedKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(newInput) else baseKind
            // Display-only guess: without a prior ToolUse we don't know the real tool name,
            // so we derive a human-readable label from the detected kind for the pill UI.
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
                // Intentionally broad catch — should ideally only catch JsonException,
                // but kept broad for safety against unexpected input shapes.
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

    internal fun finalizeStreaming(msgId: String, stopReason: String) =
        streamingLifecycle.finalizeStreaming(msgId, stopReason)

    // â”€â”€ Internal: Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    // NOTE: Most callers already hold stateLock. ReentrantLock allows this
    // without deadlocking, but consider extracting a stateLock-free internal
    // variant for hot paths (ToolUse/ToolResult on every SSE event).
    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) =
        messageMap.update(messageId, transform)

    private fun emitStreamingStartedIfNeeded(msgId: String) =
        streamingLifecycle.emitStreamingStartedIfNeeded(msgId)

    private fun emitStreamingCompleted(msgId: String, naturalCompletion: Boolean = true) =
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion)
}
