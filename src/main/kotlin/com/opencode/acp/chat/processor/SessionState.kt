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
 * - Event processing coroutine runs on Dispatchers.EDT (serialization for processEvent)
 * - External callers (createAssistantMessage, addMessage, etc.) acquire stateLock
 * - SSE events arrive on any thread and are buffered in eventChannel
 * - The `closed` flag prevents mutations after close() is called
 * - ClosedSendChannelException is caught in processEvent() for the race between
 *   the @Volatile check and channel close
 */
class SessionState(
    val sessionId: String,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager
) {
    private val logger = KotlinLogging.logger {}
    private val stateLock = ReentrantLock()

    /** Whether this SessionState has been closed (evicted or shutdown). */
    @Volatile private var closed = false

    /** Messages for this session. Keyed by message ID. */
    private val _messages = MutableStateFlow<LinkedHashMap<String, ChatMessage>>(LinkedHashMap())
    val messages: StateFlow<Map<String, ChatMessage>> = _messages.asStateFlow()

    /** Real-time streaming text from the current turn's textBuffer. Updated on every TextChunk/TextReplace. */
    val streamingText: StateFlow<String> get() = ctx.streamingText

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

    /** Processor context for the current streaming turn. */
    internal val ctx = ProcessorContext()

    /** Whether the first text chunk has been segmented (controls non-debounced
     *  re-segment for responsiveness on the first chunk). Reset in createAssistantMessage(). */
    internal var firstTextSegmented = false

    /** Event channel — SSE events buffered for EDT processing. */
    private val eventChannel = Channel<SseEvent>(1024)

    /** Event processing coroutine (runs on EDT). */
    private var eventProcessingJob: Job? = null

    /** Debounced text re-segmentation job. */
    private var resegmentJob: Job? = null

    /** Response deferred for the current send operation.
     *  @Volatile because written by OpenCodeService.sendMessage() on Dispatchers.Default
     *  and read/written by close() under stateLock. */
    @Volatile
    var responseDeferred: CompletableDeferred<Unit>? = null

    /** Whether this session has an in-flight streaming message.
     *  NOTE: This reads [ctx.isStreaming] which is mutated on EDT. For eviction
     *  checks this is a best-effort read; exact correctness is not required. */
    val isStreaming: Boolean get() = ctx.isStreaming

    /** Whether this session has a pending permission prompt that hasn't been responded to. */
    @Volatile var hasPendingPermission: Boolean = false
        private set

    /** Toggle the pending permission flag. */
    internal fun setPendingPermission(flag: Boolean) {
        hasPendingPermission = flag
        if (!flag) _pendingPermission.value = null
    }

    /** Set the pending permission prompt. Also sets hasPendingPermission = true. */
    internal fun setPendingPermissionPrompt(prompt: PermissionPrompt) {
        hasPendingPermission = true
        _pendingPermission.value = prompt
    }

    /** Set the pending selection prompt. */
    internal fun setPendingSelectionPrompt(prompt: SelectionPrompt) {
        _pendingSelection.value = prompt
    }

    /** Clear the pending selection prompt. */
    internal fun clearPendingSelection() {
        _pendingSelection.value = null
    }

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

    // ── Public API ──────────────────────────────────────────────────────────

    suspend fun processEvent(event: SseEvent) {
        if (closed) return
        lastAccessTime = System.currentTimeMillis()
        try {
            eventChannel.send(event)
        } catch (_: ClosedSendChannelException) {
            // Session was closed between the @Volatile check and the send — safe to drop
        }
    }

    fun createAssistantMessage(
        modelID: String?,
        providerID: String?,
        serverMessageId: String? = null
    ): String = stateLock.withLock {
        ctx.resetTurnState()

        var droppedCount = 0
        while (eventChannel.tryReceive().isSuccess) { droppedCount++ }
        if (droppedCount > 0) {
            logger.info { "[ACP] createAssistantMessage: drained $droppedCount stale events from previous turn" }
        }
        resegmentJob?.cancel()
        resegmentJob = null

        val id = serverMessageId ?: generateId()
        ctx.activeMessageId = id
        ctx.activeServerMessageId = serverMessageId
        ctx.modelID = modelID
        ctx.providerID = providerID
        ctx.isStreaming = true
        firstTextSegmented = false
        lastAccessTime = System.currentTimeMillis()
        logger.info { "[ACP] createAssistantMessage: id=$id, serverMessageId=$serverMessageId" }

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
     * cached via ensureSessionCached while still streaming). Sets ctx.activeMessageId so
     * subsequent SSE events (TextChunk, ToolUse, etc.) are routed to this message.
     * Does NOT create a new message — the message already exists in the map from REST fetch.
     */
    fun adoptStreamingContext(messageId: String, modelID: String?, providerID: String?) = stateLock.withLock {
        ctx.activeMessageId = messageId
        ctx.modelID = modelID
        ctx.providerID = providerID
        ctx.isStreaming = true
        firstTextSegmented = false
        lastAccessTime = System.currentTimeMillis()
        logger.info { "[ACP] adoptStreamingContext: id=$messageId, modelID=$modelID, providerID=$providerID" }
    }

    fun completeStreaming(messageId: String) = stateLock.withLock {
        if (messageId != ctx.activeMessageId) return@withLock
        freezeCurrentThinking()
        resegmentTextPartsFinal(messageId)
        ctx.isStreaming = false
        updateMessage(messageId) { it.copy(isStreaming = false, state = MessageState.Completed) }
        emitStreamingCompleted(messageId)
    }

    fun abortStreaming(reason: String) = stateLock.withLock {
        val msgId = ctx.activeMessageId ?: return@withLock
        freezeCurrentThinking()
        ctx.errorMessage = reason
        ctx.isStreaming = false
        updateMessage(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(reason)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Aborted)
        }
        emitStreamingCompleted(msgId)
        _signals.tryEmit(UiSignal.Error(msgId, reason))
    }

    fun addMessage(message: ChatMessage) = stateLock.withLock {
        val current = LinkedHashMap(_messages.value)
        current[message.id] = message
        message.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
            ctx.toolCallIndex[part.pill.toolCallId] = message.id
        }
        // FIFO eviction
        if (current.size > ChatConstants.MAX_MESSAGE_HISTORY) {
            val excess = current.size - ChatConstants.MAX_MESSAGE_HISTORY
            val iter = current.entries.iterator()
            var toRemove = excess
            while (iter.hasNext() && toRemove > 0) {
                val entry = iter.next()
                entry.value.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                    ctx.toolCallIndex.remove(part.pill.toolCallId)
                }
                iter.remove()
                toRemove--
            }
        }
        _messages.value = current
        logger.info { "[ACP] addMessage: id=${message.id}, role=${message.role}, mapSize=${current.size}" }
    }

    /**
     * Remove a message from the cache by its server message ID.
     * Used when the server sends message.removed (e.g., after compaction).
     * The message map is keyed by local ID, so we search by [ChatMessage.serverMessageId].
     */
    fun removeMessageByServerId(serverMessageId: String) = stateLock.withLock {
        val current = LinkedHashMap(_messages.value)
        val entry = current.entries.find { it.value.serverMessageId == serverMessageId }
        if (entry != null) {
            // Clean up tool call index for the removed message
            entry.value.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                ctx.toolCallIndex.remove(part.pill.toolCallId)
            }
            current.remove(entry.key)
            _messages.value = current
            logger.info { "[ACP] removeMessageByServerId: serverId=$serverMessageId, localId=${entry.key}, mapSize=${current.size}" }
        } else {
            logger.debug { "[ACP] removeMessageByServerId: serverId=$serverMessageId not found in cache" }
        }
    }

    /**
     * Replace all messages in the cache with a fresh set from the server.
     * Used after auto-compaction (session.compacted) when the local cache is stale.
     * Rebuilds the tool call index from the new message set.
     */
    fun replaceAllMessages(newMessages: List<ChatMessage>) = stateLock.withLock {
        val current = LinkedHashMap<String, ChatMessage>()
        ctx.toolCallIndex.clear()
        newMessages.forEach { message ->
            current[message.id] = message
            message.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                ctx.toolCallIndex[part.pill.toolCallId] = message.id
            }
        }
        _messages.value = current
        logger.info { "[ACP] replaceAllMessages: ${newMessages.size} messages, mapSize=${current.size}" }
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>? = null) = stateLock.withLock {
        val newPartState = when (status) {
            ToolCallStatus.COMPLETED -> PartState.Completed
            ToolCallStatus.FAILED -> PartState.Failed(output?.toString() ?: "Tool error")
            ToolCallStatus.PENDING -> PartState.Pending
            ToolCallStatus.IN_PROGRESS -> PartState.InProgress
            else -> PartState.InProgress
        }
        ctx.toolPartStates[toolCallId] = newPartState

        val targetMsgId = ctx.toolCallIndex[toolCallId] ?: ctx.activeMessageId
        if (targetMsgId != null) {
            updateMessage(targetMsgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                val existing = parts[toolCallId]
                if (existing is MessagePart.ToolCall) {
                    parts[toolCallId] = existing.copy(
                        pill = existing.pill.copy(status = status, output = output ?: existing.pill.output),
                        state = newPartState
                    )
                } else {
                    // Fallback: try to use pill from ctx.toolCallPills (has proper kind/title)
                    val existingPill = ctx.toolCallPills[toolCallId]
                    parts[toolCallId] = MessagePart.ToolCall(
                        pill = existingPill?.copy(status = status, output = output ?: existingPill.output)
                            ?: ToolCallPill(toolCallId = toolCallId, toolName = "tool", title = "tool", kind = ToolKind.OTHER, status = status, output = output),
                        state = newPartState
                    )
                }
                msg.copy(parts = parts)
            }
        }
        val pill = ctx.toolCallPills[toolCallId]
        if (pill != null) {
            ctx.toolCallPills[toolCallId] = pill.copy(status = status, output = output ?: pill.output)
        }
    }

    fun setToolPartState(toolCallId: String, state: PartState) = stateLock.withLock {
        ctx.toolPartStates[toolCallId] = state
        val pill = ctx.toolCallPills[toolCallId]
        val pillStatus = when (state) {
            PartState.Completed -> ToolCallStatus.COMPLETED
            is PartState.Failed -> ToolCallStatus.FAILED
            PartState.Pending -> ToolCallStatus.PENDING
            PartState.InProgress -> ToolCallStatus.IN_PROGRESS
            else -> pill?.status ?: ToolCallStatus.IN_PROGRESS
        }
        if (pill != null) {
            ctx.toolCallPills[toolCallId] = pill.copy(status = pillStatus)
        }
        val targetMsgId = ctx.toolCallIndex[toolCallId] ?: ctx.activeMessageId
        if (targetMsgId != null) {
            updateMessage(targetMsgId) { msg ->
                val parts = LinkedHashMap(msg.parts)
                val existing = parts[toolCallId]
                if (existing is MessagePart.ToolCall) {
                    parts[toolCallId] = existing.copy(state = state)
                }
                msg.copy(parts = parts)
            }
        }
    }

    fun updateServerMessageId(messageId: String, serverMessageId: String) = stateLock.withLock {
        if (messageId != ctx.activeMessageId) {
            logger.warn { "[ACP] updateServerMessageId MISMATCH: msg=$messageId, serverId=$serverMessageId, activeMessageId=${ctx.activeMessageId}" }
            return@withLock
        }
        ctx.activeServerMessageId = serverMessageId
        updateMessage(messageId) { it.copy(serverMessageId = serverMessageId) }
        logger.info { "[ACP] updateServerMessageId: msg=$messageId → serverId=$serverMessageId" }
    }

    fun setLastUserText(text: String?) {
        ctx.lastUserText = text
    }

    fun close() {
        stateLock.withLock {
            if (closed) return
            closed = true
            eventProcessingJob?.cancel()
            resegmentJob?.cancel()
            signalForwardJob?.cancel()
            ctx.pendingStopJob?.cancel()
            eventChannel.close()
            _pendingPermission.value = null
            _pendingSelection.value = null
            responseDeferred?.completeExceptionally(
                CancellationException("Session $sessionId evicted from cache")
            )
            responseDeferred = null
        }
    }

    // ── Internal: Event Processing ──────────────────────────────────────────

    private fun startCoroutines() {
        eventProcessingJob = scope.launch(Dispatchers.EDT) {
            for (event in eventChannel) {
                logger.info { "[ACP] processEvent DEQUEUE: ${event::class.simpleName} sid=${event.sessionId}" }
                processEventInternal(event)
            }
        }
    }

    private fun processEventInternal(event: SseEvent) {
        // Events that don't require an active streaming message
        when (event) {
            is SseEvent.TodoUpdated -> {
                val todos = event.todos.map { todo ->
                    TodoItem(content = todo.content, status = todo.status, priority = todo.priority)
                }
                _signals.tryEmit(UiSignal.TodoUpdated(todos))
                return
            }
            is SseEvent.QuestionAsked -> {
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
                if (ctx.isStreaming) {
                    val msgId = ctx.activeMessageId
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
            else -> { /* handled below */ }
        }

        // Cancel any pending debounced Stop — a new event means streaming continues
        ctx.pendingStopJob?.cancel()
        ctx.pendingStopJob = null

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
            ctx.activeMessageId == null && isContentEvent -> true // No streaming context at all
            eventServerId != null && eventServerId != ctx.activeMessageId && eventServerId != ctx.activeServerMessageId -> {
                // A new message is streaming — the event's server messageId differs from
                // the currently active one. This happens for child/subagent sessions where
                // adoptStreamingContext adopted a REST-loaded message but the server is now
                // streaming a different message.
                logger.info { "[ACP] New message detected for session $sessionId: eventServerId=$eventServerId vs activeMsgId=${ctx.activeMessageId}/activeServerId=${ctx.activeServerMessageId}" }
                // Finalize the current streaming message before starting a new one
                val currentId = ctx.activeMessageId
                if (currentId != null && ctx.isStreaming) {
                    finalizeStreaming(currentId, "new_message")
                }
                true
            }
            else -> false
        }
        if (needsNewMessage) {
            logger.info { "[ACP] Auto-creating assistant message for session $sessionId (triggered by ${event::class.simpleName}, serverMsgId=$eventServerId)" }
            createAssistantMessage(modelID = null, providerID = null, serverMessageId = eventServerId)
        }

        val msgId = ctx.activeMessageId
        if (msgId == null) {
            logger.info { "[ACP] processEvent DROP: ${event::class.simpleName} — activeMessageId is null even after auto-create" }
            return
        }

        // Server ID routing check
        val activeServerId = ctx.activeServerMessageId
        val isCrossMessageEvent = event is SseEvent.ToolResult || event is SseEvent.Permission
        logger.info { "[ACP] processEvent ROUTE: ${event::class.simpleName} activeMsgId=$msgId activeServerId=$activeServerId eventServerId=$eventServerId isCross=$isCrossMessageEvent" }
        if (!isCrossMessageEvent && activeServerId != null && eventServerId != null && eventServerId != activeServerId) {
            logger.info { "[ACP] processEvent SKIP: event messageId=$eventServerId != active=$activeServerId" }
            return
        }

        when (event) {
            // ── Thinking ──────────────────────────────────────────────────
            is SseEvent.ThinkingChunk -> {
                if (ctx.activeThinkingCompleted) {
                    freezeCurrentThinking()
                }
                if (ctx.activeThinkingKey == null) {
                    val key = "thinking_${ctx.thinkingPhaseIndex}"
                    ctx.activeThinkingKey = key
                    ctx.thinkingPhaseIndex++
                    emitStreamingStartedIfNeeded(msgId)
                }
                ctx.thinkingBuffer.append(event.text)
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[ctx.activeThinkingKey!!] = MessagePart.Thinking(
                        content = ctx.thinkingBuffer.toString(),
                        state = PartState.Streaming
                    )
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            is SseEvent.ThinkingReplace -> {
                if (ctx.activeThinkingCompleted) {
                    freezeCurrentThinking()
                }
                if (ctx.activeThinkingKey == null) {
                    val key = "thinking_${ctx.thinkingPhaseIndex}"
                    ctx.activeThinkingKey = key
                    ctx.thinkingPhaseIndex++
                    emitStreamingStartedIfNeeded(msgId)
                }
                ctx.thinkingBuffer.setLength(0)
                ctx.thinkingBuffer.append(event.text)
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[ctx.activeThinkingKey!!] = MessagePart.Thinking(
                        content = ctx.thinkingBuffer.toString(),
                        state = if (ctx.activeThinkingCompleted) PartState.Completed else PartState.Streaming
                    )
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Text ──────────────────────────────────────────────────────
            is SseEvent.TextChunk -> {
                // Thinking ends when text begins — freeze any active thinking
                if (!ctx.activeThinkingCompleted && ctx.thinkingBuffer.isNotEmpty()) {
                    ctx.activeThinkingCompleted = true
                    freezeCurrentThinking()
                }
                val text = event.text
                if (!ctx.firstTextChunkReceived && text.isNotBlank()) {
                    ctx.firstTextChunkReceived = true
                    emitStreamingStartedIfNeeded(msgId)
                    val userText = ctx.lastUserText
                    if (userText != null && text.startsWith(userText, ignoreCase = true)) {
                        ctx.userEchoStripped = true
                        ctx.textBuffer.append(text.substring(userText.length))
                    } else {
                        ctx.textBuffer.append(text)
                    }
                } else {
                    ctx.textBuffer.append(text)
                }
                ctx.streamingText.value = ctx.textBuffer.toString()
                scheduleResegment(msgId)
            }

            is SseEvent.TextReplace -> {
                // Thinking ends when text begins — freeze any active thinking
                if (!ctx.activeThinkingCompleted && ctx.thinkingBuffer.isNotEmpty()) {
                    ctx.activeThinkingCompleted = true
                    freezeCurrentThinking()
                }
                ctx.textBuffer.clear()
                ctx.textBuffer.append(event.text)
                ctx.userEchoStripped = false
                val userText = ctx.lastUserText
                if (userText != null && ctx.textBuffer.toString().startsWith(userText, ignoreCase = true)) {
                    ctx.textBuffer.delete(0, userText.length)
                    ctx.userEchoStripped = true
                }
                ctx.streamingText.value = ctx.textBuffer.toString()
                scheduleResegment(msgId)
            }

            // ── Tool calls ───────────────────────────────────────────────
            is SseEvent.ToolUse -> {
                val existingPill = ctx.toolCallPills[event.toolCallId]
                if (existingPill != null) {
                    // Duplicate ToolUse — server updated the part (e.g. task tool called ctx.metadata()).
                    // Merge metadata, title, and input into the existing pill.
                    val updatedPill = existingPill.copy(
                        metadata = event.metadata ?: existingPill.metadata,
                        title = event.title?.takeIf { it != existingPill.title } ?: existingPill.title,
                        input = if (event.input != null && event.input.isNotEmpty()) event.input else existingPill.input,
                    )
                    ctx.toolCallPills[event.toolCallId] = updatedPill
                    // Update the message part so Compose recomposes with the new pill
                    val targetMsgId = ctx.toolCallIndex[event.toolCallId] ?: return
                    updateMessage(targetMsgId) { msg ->
                        val parts = LinkedHashMap(msg.parts)
                        val existing = parts[event.toolCallId]
                        if (existing is MessagePart.ToolCall) {
                            parts[event.toolCallId] = existing.copy(pill = updatedPill)
                            msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                        } else msg
                    }
                    // For task tools: proactively cache the child session if we just learned the sessionId
                    if (existingPill.toolName == "task" && existingPill.metadata?.get("sessionId") == null && event.metadata?.get("sessionId") != null) {
                        val childId = try { event.metadata["sessionId"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                        if (childId != null) {
                            logger.info { "[ACP] Task tool (metadata update): proactively caching child session $childId" }
                            scope.launch { sessionManager.ensureSessionCached(childId) }
                        }
                    }
                    return
                }

                if (!ctx.activeThinkingCompleted && ctx.thinkingBuffer.isNotEmpty()) {
                    ctx.activeThinkingCompleted = true
                    freezeCurrentThinking()
                }
                emitStreamingStartedIfNeeded(msgId)

                ctx.toolPartStates[event.toolCallId] = PartState.InProgress
                val baseKind = ToolMapper.toAcpKind(event.toolName)
                val toolKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(event.input) else baseKind
                // Resolve a human-readable title: event.title > input.description > toolName
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
                )
                ctx.toolCallPills[event.toolCallId] = pill
                ctx.toolCallIndex[event.toolCallId] = msgId

                if (toolKind == ToolKind.EDIT && event.input != null) {
                    val filePath = extractFilePath(event.input)
                    if (filePath != null) {
                        val fileName = filePath.substringAfterLast('/')
                        val oldString = event.input["oldString"]?.jsonPrimitive?.contentOrNull
                            ?: event.input["old_string"]?.jsonPrimitive?.contentOrNull
                            ?: event.input["old"]?.jsonPrimitive?.contentOrNull
                        val newString = event.input["newString"]?.jsonPrimitive?.contentOrNull
                            ?: event.input["new_string"]?.jsonPrimitive?.contentOrNull
                            ?: event.input["new"]?.jsonPrimitive?.contentOrNull
                        val content = event.input["content"]?.jsonPrimitive?.contentOrNull
                        val additions = when {
                            oldString != null && newString != null -> newString.lines().size
                            content != null -> content.lines().size
                            else -> 0
                        }
                        val deletions = when {
                            oldString != null && newString != null -> oldString.lines().size
                            else -> 0
                        }
                        val change = ChatFileChange(
                            filePath = filePath,
                            fileName = fileName,
                            additions = additions,
                            deletions = deletions
                        )
                        ctx.pendingFileChanges.add(change)
                        _signals.tryEmit(UiSignal.FileChanged(Unit))
                    }
                }

                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[event.toolCallId] = MessagePart.ToolCall(pill = pill, state = PartState.InProgress)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }

                // For task tools: proactively cache the child session so ToolPill can get its messages
                if (event.toolName == "task") {
                    logger.info { "[ACP] Task tool ToolUse: callID=${event.toolCallId}, title=${event.title}, inputKeys=${event.input?.keys}, metadata=${event.metadata}" }
                    val childId = try { event.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                    if (childId != null) {
                        logger.info { "[ACP] Task tool: proactively caching child session $childId" }
                        scope.launch { sessionManager.ensureSessionCached(childId) }
                    }
                }
            }

            is SseEvent.ToolResult -> {
                ctx.toolPartStates[event.toolCallId] = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed
                val existingPill = ctx.toolCallPills[event.toolCallId]
                val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
                logger.info { "[ACP] ToolResult: callID=${event.toolCallId}, hasInput=${event.input != null}, inputKeys=${event.input?.keys}, existingKind=${existingPill?.kind}, existingTitle=${existingPill?.title}" }
                if (existingPill != null) {
                    val newInput = event.input ?: existingPill.input
                    // Re-detect kind from input if it was OTHER — the result may have the real data
                    val resolvedKind = if (existingPill.kind == ToolKind.OTHER) {
                        ToolMapper.detectKindFromInput(newInput)
                    } else existingPill.kind
                    // Recompute title from input
                    val resolvedTitle = newInput?.let { input ->
                        try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                    } ?: existingPill.title
                    logger.info { "[ACP] ToolResult: callID=${event.toolCallId}, prevKind=${existingPill.kind}, resolvedKind=$resolvedKind, prevTitle=${existingPill.title}, resolvedTitle=$resolvedTitle, hasEventInput=${event.input != null}, eventInputKeys=${event.input?.keys}, hasNewInput=${newInput != null}, newInputKeys=${newInput?.keys}" }
                    ctx.toolCallPills[event.toolCallId] = existingPill.copy(
                        status = resolvedStatus,
                        title = resolvedTitle,
                        kind = resolvedKind,
                        input = newInput,
                        output = event.content,
                        metadata = event.metadata ?: existingPill.metadata,
                    )
                } else {
                    // No prior ToolUse — create pill from scratch (fast sub-agents can complete in one event)
                    val newInput = event.input
                    val baseKind = ToolMapper.toAcpKind("tool")
                    val resolvedKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(newInput) else baseKind
                    val resolvedTitle = newInput?.let { input ->
                        try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                    } ?: "tool"
                    val newPill = ToolCallPill(
                        toolCallId = event.toolCallId,
                        toolName = "tool",
                        title = resolvedTitle,
                        kind = resolvedKind,
                        status = resolvedStatus,
                        input = newInput,
                        output = event.content,
                        metadata = event.metadata,
                    )
                    ctx.toolCallPills[event.toolCallId] = newPill
                    ctx.toolCallIndex[event.toolCallId] = msgId
                    updateMessage(msgId) { msg ->
                        val parts = LinkedHashMap(msg.parts)
                        parts[event.toolCallId] = MessagePart.ToolCall(pill = newPill, state = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed)
                        msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                    }
                    // For task tools: proactively cache the child session
                    val childId = try { event.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                    if (childId != null) {
                        logger.info { "[ACP] Task tool (from ToolResult): proactively caching child session $childId" }
                        scope.launch { sessionManager.ensureSessionCached(childId) }
                    }
                }
                val targetMsgId = ctx.toolCallIndex[event.toolCallId] ?: msgId
                updateMessage(targetMsgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    val existing = parts[event.toolCallId]
                    if (existing is MessagePart.ToolCall) {
                        val newInput = event.input ?: existing.pill.input
                        val resolvedKind = if (existing.pill.kind == ToolKind.OTHER) {
                            ToolMapper.detectKindFromInput(newInput)
                        } else existing.pill.kind
                        val resolvedTitle = newInput?.let { input ->
                            try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                        } ?: existing.pill.title
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
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            is SseEvent.Permission -> {
                ctx.toolPartStates[event.toolCallId] = PartState.Pending
                val existingPill = ctx.toolCallPills[event.toolCallId]
                if (existingPill != null) {
                    ctx.toolCallPills[event.toolCallId] = existingPill.copy(status = ToolCallStatus.PENDING)
                }
                val targetMsgId = ctx.toolCallIndex[event.toolCallId] ?: msgId
                updateMessage(targetMsgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    val existing = parts[event.toolCallId]
                    if (existing is MessagePart.ToolCall) {
                        parts[event.toolCallId] = existing.copy(
                            pill = existing.pill.copy(status = ToolCallStatus.PENDING),
                            state = PartState.Pending
                        )
                    }
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
                val prompt = PermissionPrompt(
                    sessionId = sessionId,
                    permissionId = event.permissionId,
                    toolCallId = event.toolCallId,
                    toolName = event.action,
                    description = event.description,
                    patterns = event.patterns
                )
                setPendingPermissionPrompt(prompt)
                _signals.tryEmit(UiSignal.PermissionRequested(prompt))
            }

            // ── Stop ─────────────────────────────────────────────────────
            is SseEvent.Stop -> {
                finalizeStreaming(msgId, event.stopReason)
            }

            // ── MessageFinalized ─────────────────────────────────────────
            // event.messageId is the SERVER message ID (e.g., "msg_xxx").
            // ctx.activeMessageId is the LOCAL ID (generated if server ID wasn't
            // available at creation time). ctx.activeServerMessageId is the server ID
            // set by updateServerMessageId(). We must check against BOTH, and use the
            // LOCAL ID for updateMessage() since the message map is keyed by local ID.
            is SseEvent.MessageFinalized -> {
                val serverMsgId = event.messageId ?: return

                // Guard: only process events for the currently streaming message.
                // Check against both server ID and local ID — the message may have
                // been created with a generated ID before the server assigned one.
                if (serverMsgId != ctx.activeServerMessageId && serverMsgId != ctx.activeMessageId) {
                    logger.debug { "[ACP] MessageFinalized for non-active message $serverMsgId — skipping (activeLocal=${ctx.activeMessageId}, activeServer=${ctx.activeServerMessageId})" }
                    return
                }

                // Use the LOCAL message ID for map lookups — the message cache is
                // keyed by the ID returned from createAssistantMessage(), which may
                // differ from the server ID.
                val localMsgId = ctx.activeMessageId ?: return

                // Apply token/cost/model updates unconditionally (may arrive without stopReason)
                updateMessage(localMsgId) { msg ->
                    msg.copy(
                        inputTokens = event.inputTokens ?: msg.inputTokens,
                        outputTokens = event.outputTokens ?: msg.outputTokens,
                        reasoningTokens = event.reasoningTokens ?: msg.reasoningTokens,
                        cacheReadTokens = event.cacheReadTokens ?: msg.cacheReadTokens,
                        cacheWriteTokens = event.cacheWriteTokens ?: msg.cacheWriteTokens,
                        cost = event.cost ?: msg.cost,
                        modelID = event.modelID ?: msg.modelID,
                        providerID = event.providerID ?: msg.providerID,
                        // isStreaming and state are NOT set here — only in finalizeStreaming()
                    )
                }

                // Delegate finalization to shared logic (same as Stop handler)
                if (event.stopReason != null) {
                    finalizeStreaming(localMsgId, event.stopReason)
                }
            }

            // ── Error ─────────────────────────────────────────────────────
            is SseEvent.Error -> {
                freezeCurrentThinking()
                ctx.errorMessage = event.message
                ctx.isStreaming = false
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts["error"] = MessagePart.Error(event.message)
                    msg.copy(parts = parts, isStreaming = false, state = MessageState.Failed(event.message))
                }
                _signals.tryEmit(UiSignal.Error(msgId, event.message))
            }

            // ── Patch ─────────────────────────────────────────────────────
            is SseEvent.Patch -> {
                ctx.activePatches.add(event)
                val key = "patch_${ctx.activePatches.size - 1}"
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[key] = MessagePart.Patch(hash = event.hash, files = event.files)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Agent ─────────────────────────────────────────────────────
            is SseEvent.Agent -> {
                ctx.activeAgentName = event.agentName
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts["agent"] = MessagePart.Agent(name = event.agentName)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Retry ─────────────────────────────────────────────────────
            is SseEvent.Retry -> {
                ctx.activeRetry = event
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts["retry"] = MessagePart.Retry(attempt = event.attempt, maxAttempts = event.maxAttempts, error = event.error)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Compaction ────────────────────────────────────────────────
            is SseEvent.Compaction -> {
                ctx.activeCompaction = event
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts["compaction"] = MessagePart.Compaction(summary = event.summary)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Snapshot ──────────────────────────────────────────────────
            is SseEvent.Snapshot -> { /* internal state marker */ }

            // ── StepFinish ───────────────────────────────────────────────
            is SseEvent.StepFinish -> {
                ctx.activeStepFinish = event
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts["step_finish"] = MessagePart.StepFinish(
                        snapshot = event.snapshot,
                        inputTokens = event.inputTokens,
                        outputTokens = event.outputTokens,
                        reasoningTokens = event.reasoningTokens,
                        totalCost = event.totalCost,
                    )
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // ── Subtask ──────────────────────────────────────────────────
            is SseEvent.Subtask -> { /* informational */ }

            // ── Assistant files/images ────────────────────────────────────
            is SseEvent.AssistantFile -> {
                val key = "assistant_file_${event.partId ?: event.url}"
                ctx.activeAssistantFiles[event.partId ?: event.url] = event
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[key] = MessagePart.AssistantFile(mime = event.mime, url = event.url, filename = event.filename)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            is SseEvent.AssistantImage -> {
                val key = "assistant_image_${event.partId ?: event.url}"
                ctx.activeAssistantImages[event.partId ?: event.url] = event
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[key] = MessagePart.Image(mime = event.mime, url = event.url, filename = event.filename)
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            // Unreachable — handled in first when block
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

    /**
     * Shared streaming finalization logic — called by both SseEvent.Stop and
     * SseEvent.MessageFinalized handlers. Handles freeze, resegment, tool-calls
     * filter, and debounced completion.
     */
    private fun finalizeStreaming(msgId: String, stopReason: String) {
        if (!ctx.isStreaming) {
            logger.info { "[ACP] finalizeStreaming SKIP: not streaming (reason=$stopReason)" }
            return
        }

        freezeCurrentThinking()
        resegmentTextPartsDirect(msgId)

        if (stopReason == "tool-calls") {
            // Intermediate stop — tool calls starting; keep message streaming, don't mark completed
            logger.info { "[ACP] finalizeStreaming (tool-calls): intermediate — continuing stream, token data applied" }
        } else {
            val capturedMsgId = msgId
            ctx.pendingStopJob?.cancel()
            ctx.pendingStopJob = scope.launch {
                delay(300)
                stateLock.withLock {
                    if (!ctx.isStreaming) return@withLock
                    ctx.isStreaming = false
                    updateMessage(capturedMsgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                    emitStreamingCompleted(capturedMsgId)
                }
            }
            logger.info { "[ACP] finalizeStreaming (reason=$stopReason): debounced finalization (300ms)" }
        }
    }

    // ── Internal: Thinking Phase Management ─────────────────────────────────

    private fun freezeCurrentThinking() {
        val key = ctx.activeThinkingKey ?: return
        if (ctx.thinkingBuffer.isEmpty()) return
        val content = ctx.thinkingBuffer.toString()
        val msgId = ctx.activeMessageId ?: return
        updateMessage(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[key] = MessagePart.Thinking(content = content, state = PartState.Completed)
            msg.copy(parts = parts)
        }
        ctx.thinkingBuffer.setLength(0)
        ctx.activeThinkingKey = null
        ctx.activeThinkingCompleted = false
    }

    // ── Internal: Text Re-Segmentation ──────────────────────────────────────

    private fun scheduleResegment(msgId: String) {
        if (!firstTextSegmented) {
            firstTextSegmented = true
            resegmentTextPartsDirect(msgId)
            return
        }
        resegmentJob?.cancel()
        resegmentJob = scope.launch(Dispatchers.EDT) {
            try {
                delay(DEBOUNCE_MS)
                resegmentTextPartsDirect(msgId)
            } catch (_: CancellationException) { /* newer event arrived */ }
        }
    }

    private fun resegmentTextPartsDirect(msgId: String) {
        stateLock.withLock {
            if (ctx.textBuffer.isEmpty()) {
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts.keys.removeIf { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") }
                    msg.copy(parts = parts)
                }
                return@withLock
            }
            val raw = ctx.textBuffer.toString()
            val segments = if (ctx.isStreaming) {
                MarkdownSegmenter.segmentHealed(raw)
            } else {
                MarkdownSegmenter.segment(raw)
            }
            val newParts = mutableListOf<Pair<String, MessagePart>>()
            segments.forEachIndexed { i, segment ->
                when (segment.type) {
                    MarkdownSegment.Type.TEXT -> {
                        if (segment.content.isNotBlank()) newParts.add("text_$i" to MessagePart.Text(segment.content))
                    }
                    MarkdownSegment.Type.CODE -> {
                        if (segment.content.isNotBlank()) newParts.add("code_$i" to MessagePart.Code(segment.language ?: "", segment.content))
                    }
                    MarkdownSegment.Type.TABLE -> {
                        val parsed = MarkdownSegmenter.parseTable(segment.content.lines())
                        if (parsed != null) {
                            newParts.add("table_$i" to MessagePart.Table(
                                rawMarkdown = segment.content,
                                headers = parsed.header,
                                rows = parsed.rows,
                                alignments = parsed.alignments
                            ))
                        } else {
                            newParts.add("text_$i" to MessagePart.Text(segment.content))
                        }
                    }
                    MarkdownSegment.Type.TASK -> {
                        val state = segment.taskAttrs?.get("state") ?: "completed"
                        val status = when (state) {
                            "completed" -> ToolCallStatus.COMPLETED
                            "failed" -> ToolCallStatus.FAILED
                            else -> ToolCallStatus.IN_PROGRESS
                        }
                        val agentId = segment.taskAttrs?.get("id") ?: ""
                        val output = listOf(kotlinx.serialization.json.JsonObject(
                            mapOf("text" to kotlinx.serialization.json.JsonPrimitive(segment.content))
                        ))
                        val pill = ToolCallPill(
                            toolCallId = "task_${agentId.hashCode().toString(16).takeLast(8)}",
                            toolName = "task",
                            title = "task",
                            kind = com.agentclientprotocol.model.ToolKind.OTHER,
                            status = status,
                            output = output,
                        )
                        newParts.add("task_$i" to MessagePart.ToolCall(
                            pill = pill,
                            state = if (status == ToolCallStatus.COMPLETED) PartState.Completed else PartState.InProgress
                        ))
                    }
                }
            }
            updateMessage(msgId) { msg ->
                val oldParts = LinkedHashMap(msg.parts)
                val preserved = mutableListOf<Pair<String, MessagePart>>()
                var lastNonTextIndex = -1
                oldParts.entries.forEachIndexed { i, entry ->
                    val isTextDerived = entry.key.startsWith("text_") || entry.key.startsWith("code_") || entry.key.startsWith("table_") || entry.key.startsWith("task_")
                    if (!isTextDerived) {
                        preserved.add(entry.key to entry.value)
                        lastNonTextIndex = preserved.size - 1
                    }
                }
                val newMap = linkedMapOf<String, MessagePart>()
                preserved.forEachIndexed { i, (key, part) ->
                    newMap[key] = part
                    if (i == lastNonTextIndex || (lastNonTextIndex == -1 && i == 0)) {
                        newParts.forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                    }
                }
                if (lastNonTextIndex == -1) {
                    newParts.forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                }
                msg.copy(parts = newMap)
            }
        }
    }

    private fun resegmentTextPartsFinal(msgId: String) = stateLock.withLock {
        val wasStreaming = ctx.isStreaming
        ctx.isStreaming = false
        resegmentTextPartsDirect(msgId)
        ctx.isStreaming = wasStreaming
    }

    companion object {
        private const val DEBOUNCE_MS = 50L
    }

    // ── Internal: Helpers ───────────────────────────────────────────────────

    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) = stateLock.withLock {
        val map = _messages.value
        val existing = map[messageId] ?: return@withLock
        val updated = LinkedHashMap(map)
        updated[messageId] = transform(existing)
        _messages.value = updated
    }

    private fun emitStreamingStartedIfNeeded(msgId: String) {
        if (!ctx.streamingStartedEmitted) {
            ctx.streamingStartedEmitted = true
            updateMessage(msgId) { it.copy(state = MessageState.Streaming) }
            _signals.tryEmit(UiSignal.StreamingStarted(msgId))
        }
    }

    private fun emitStreamingCompleted(msgId: String) {
        if (ctx.streamingCompletedEmitted) return
        ctx.streamingCompletedEmitted = true
        _signals.tryEmit(UiSignal.StreamingCompleted(msgId, ctx.pendingFileChanges.toList()))
    }

    private fun extractFilePath(input: JsonObject): String? {
        for (key in listOf("file_path", "filePath", "path")) {
            val element = input[key] ?: continue
            val path = try {
                (element as? JsonPrimitive)?.content
            } catch (_: Exception) { null }
            if (!path.isNullOrEmpty()) return path
        }
        return null
    }
}
