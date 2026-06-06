package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.ToolMapper
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.ui.compose.MarkdownSegment
import com.opencode.acp.chat.ui.compose.MarkdownSegmenter
import com.opencode.acp.chat.ui.compose.ParsedTable
import com.opencode.acp.chat.ui.compose.StreamHealer
import com.opencode.acp.chat.util.EDT
import com.opencode.acp.chat.util.generateId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.LinkedHashMap

/**
 * Owns all mutable streaming state (text accumulation, tool call tracking,
 * markdown segmentation) and emits display-ready [List<ChatMessage>] where
 * each message contains typed [MessagePart] objects instead of raw markdown.
 *
 * Thread safety: SSE coroutine sends events via [process] (any thread).
 * A separate EDT-based coroutine consumes events and mutates [ProcessorContext].
 * A third EDT-based coroutine handles flush batching via [Channel.CONFLATED].
 */
class MessageProcessorManager(private val scope: CoroutineScope) {

    private val logger = KotlinLogging.logger {}

    private val _messages = MutableStateFlow<LinkedHashMap<String, ChatMessage>>(LinkedHashMap())
    val messages: StateFlow<Map<String, ChatMessage>> = _messages.asStateFlow()

    private val _signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()

    private val ctx = ProcessorContext()

    // Thread-safe event queue: SSE coroutine sends events here (any thread),
    // the event processing coroutine consumes them on EDT.
    private val eventChannel = Channel<SseEvent>(Channel.UNLIMITED)

    // Batching: uses a conflated channel to coalesce multiple process() calls
    // into a single flush per frame.
    private val flushChannel = Channel<Unit>(Channel.CONFLATED)
    private var eventProcessingJob: Job? = null
    private var flushJob: Job? = null

    /** Whether the first chunk has been flushed (for immediate first-flush responsiveness). */
    private var firstChunkFlushed = false

    init {
        startCoroutines()
    }

    private fun startCoroutines() {
        eventProcessingJob = scope.launch(Dispatchers.EDT) {
            for (event in eventChannel) {
                logger.info { "[ACP] processEvent DEQUEUE: ${event::class.simpleName} sid=${event.sessionId}" }
                processEvent(event)
            }
        }
        flushJob = scope.launch(Dispatchers.EDT) {
            for (unit in flushChannel) {
                flushToMessages()
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Enqueue an SSE event for processing. Thread-safe — called from SSE coroutine (any thread).
     *  Filters out SseEvent.Ignored to avoid consuming channel buffer space with events
     *  that the processor will immediately log and discard. */
    fun process(event: SseEvent) {
        if (event is SseEvent.Ignored) {
            logger.debug { "Ignored: ${event.eventType} — ${event.reason}" }
            return
        }
        val result = eventChannel.trySend(event)
        if (result.isFailure) {
            val isClosed = eventChannel.isClosedForSend
            logger.error { "[ACP] processEvent ENQUEUE FAILED: ${event::class.simpleName} — isClosed=$isClosed, reason=${result.exceptionOrNull()?.message}" }
        }
    }

    /** Create a placeholder assistant message for a new turn.
     *  Adds the message to _messages, sets ctx.activeMessageId, stores modelID/providerID.
     *  MUST be called before the first SSE event arrives (i.e., before process() is called).
     *  Returns the message ID. */
    fun createAssistantMessage(
        modelID: String?,
        providerID: String?,
        serverMessageId: String? = null
    ): String {
        // Reset turn-specific state to prevent stale data from the previous turn
        // leaking into the new message. Preserves toolCallIndex (cross-message routing)
        // and lastUserText (set separately via setLastUserText).
        ctx.resetTurnState()

        // Use server's messageID if provided, otherwise generate a local UUID
        // Using serverMessageId allows deterministic SSE event routing via messageID match
        val id = serverMessageId ?: generateId()
        ctx.activeMessageId = id
        ctx.activeServerMessageId = serverMessageId
        ctx.modelID = modelID
        ctx.providerID = providerID
        ctx.isStreaming = true
        logger.info { "[ACP] createAssistantMessage: id=$id, serverMessageId=$serverMessageId" }
        firstChunkFlushed = false

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
        // StreamingStarted is NOT emitted here — it fires when the first content
        // chunk (TextChunk or ThinkingChunk) arrives in processEvent(). This ensures
        // the signal accurately reflects actual content delivery, not placeholder creation.
        return id
    }

    /** Mark the current streaming turn as complete. Flushes remaining buffer.
     *  Verifies messageId == ctx.activeMessageId; no-op if mismatch. */
    fun completeStreaming(messageId: String) {
        if (messageId != ctx.activeMessageId) return
        ctx.activeThinkingCompleted = true
        flushToMessages()
        ctx.isStreaming = false
        // Transition message state: Streaming → Completed
        updateMessage(messageId) { it.copy(isStreaming = false, state = MessageState.Completed) }
        emitStreamingCompleted(messageId)
    }

    /** Abort in-flight streaming due to SSE stream drop or session switch.
     *  Appends MessagePart.Error, marks streaming complete. */
    fun abortStreaming(reason: String) {
        val msgId = ctx.activeMessageId ?: return
        // Mark thinking as completed so its state transitions Streaming → Completed (or Failed if error in thinking)
        ctx.activeThinkingCompleted = true
        ctx.errorMessage = reason
        ctx.isStreaming = false
        flushToMessages()
        // Transition message state: Streaming → Aborted
        updateMessage(msgId) { it.copy(isStreaming = false, state = MessageState.Aborted) }
        emitStreamingCompleted(msgId)
        _signals.tryEmit(UiSignal.Error(msgId, reason))
    }

    /** Reset all state for a session switch. Cancels any pending flush. */
    fun resetSessionState() {
        // Drain all pending events from both channels to prevent stale events
        // from the old session being processed by the new coroutine.
        while (eventChannel.tryReceive().isSuccess) { /* drain */ }
        while (flushChannel.tryReceive().isSuccess) { /* drain */ }
        ctx.reset()
        _messages.value = LinkedHashMap()
        firstChunkFlushed = false
        // Restart coroutines for the new session
        eventProcessingJob?.cancel()
        flushJob?.cancel()
        startCoroutines()
    }

    /** Add a message directly (for loading history from REST API). */
    fun addMessage(message: ChatMessage) {
        val current = LinkedHashMap(_messages.value)
        current[message.id] = message
        message.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
            ctx.toolCallIndex[part.pill.toolCallId] = message.id
        }
        // FIFO eviction — use iterator for O(1) per removal (avoids rehash per remove())
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
    }

    /** Inject subagent references into an existing message.
     *  Removes existing Subagent parts, adds new ones, emits StateFlow. */
    fun injectSubagentRefs(messageId: String, refs: List<SubagentRef>) {
        updateMessage(messageId) { msg ->
            val partsWithoutSubagents = LinkedHashMap(msg.parts.filterValues { it !is MessagePart.Subagent })
            refs.forEach { ref ->
                partsWithoutSubagents[ref.sessionId] = MessagePart.Subagent(ref)
            }
            msg.copy(parts = partsWithoutSubagents)
        }
    }

    /** Update tool call status (for permission responses that change pill state). */
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>? = null) {
        // Track part state transition
        val newPartState = when (status) {
            ToolCallStatus.COMPLETED -> PartState.Completed
            ToolCallStatus.FAILED -> PartState.Failed(output?.toString() ?: "Tool error")
            ToolCallStatus.PENDING -> PartState.Pending
            ToolCallStatus.IN_PROGRESS -> PartState.InProgress
            else -> PartState.InProgress
        }
        ctx.toolPartStates[toolCallId] = newPartState

        updateToolCallStatusInternal(toolCallId, status, output, newPartState)
    }

    /** Set tool part state directly (e.g. Rejected on permission reject). */
    fun setToolPartState(toolCallId: String, state: PartState) {
        ctx.toolPartStates[toolCallId] = state
        // Also update pill status for visual consistency
        val pill = ctx.toolCallPills[toolCallId]
        if (pill != null) {
            val pillStatus = when (state) {
                PartState.Completed -> ToolCallStatus.COMPLETED
                is PartState.Failed -> ToolCallStatus.FAILED
                PartState.Pending -> ToolCallStatus.PENDING
                PartState.InProgress -> ToolCallStatus.IN_PROGRESS
                else -> pill.status
            }
            ctx.toolCallPills[toolCallId] = pill.copy(status = pillStatus)
            flushToMessages()
        } else {
            val messageId = ctx.toolCallIndex[toolCallId] ?: return
            updateMessage(messageId) { msg ->
                val updatedParts = LinkedHashMap(msg.parts)
                updatedParts.replaceAll { _, part ->
                    if (part is MessagePart.ToolCall && part.pill.toolCallId == toolCallId) {
                        part.copy(state = state)
                    } else part
                }
                msg.copy(parts = updatedParts)
            }
        }
    }

    private fun updateToolCallStatusInternal(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>?, newPartState: PartState) {
        val pill = ctx.toolCallPills[toolCallId]
        if (pill != null) {
            ctx.toolCallPills[toolCallId] = pill.copy(status = status, output = output ?: pill.output)
            flushToMessages()
            return
        }
        // Otherwise, find the message containing this tool call and update it
        val messageId = ctx.toolCallIndex[toolCallId] ?: return
        updateMessage(messageId) { msg ->
            val updatedParts = LinkedHashMap(msg.parts)
            updatedParts.replaceAll { _, part ->
                if (part is MessagePart.ToolCall && part.pill.toolCallId == toolCallId) {
                    MessagePart.ToolCall(part.pill.copy(status = status, output = output ?: part.pill.output), state = newPartState)
                } else part
            }
            msg.copy(parts = updatedParts)
        }
    }

    /** Update the server's message ID on an already-created assistant message.
     *  Called after sendMessageAsync() returns the server-assigned ID.
     *  Also updates ctx.activeServerMessageId for SSE event routing. */
    fun updateServerMessageId(messageId: String, serverMessageId: String) {
        if (messageId != ctx.activeMessageId) return
        ctx.activeServerMessageId = serverMessageId
        updateMessage(messageId) { it.copy(serverMessageId = serverMessageId) }
        logger.info { "[ACP] updateServerMessageId: msg=$messageId → serverId=$serverMessageId" }
    }

    /** Set the last user message text for echo stripping.
     *  MUST be called before process() begins (i.e., in sendMessage() before SSE events arrive). */
    fun setLastUserText(text: String?) {
        ctx.lastUserText = text
    }

    /** Cancel all coroutines. Call on plugin disposal to prevent leaks. */
    fun close() {
        logger.info { "[ACP] processor.close() called" }
        eventProcessingJob?.cancel()
        flushJob?.cancel()
        eventChannel.close()
        flushChannel.close()
    }

    // ── Internal: Event Processing ──────────────────────────────────────────

    /** Process a single SSE event on EDT. All ProcessorContext mutation happens here. */
    private fun processEvent(event: SseEvent) {
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
                        promptId = event.requestId,
                        question = q.question,
                        subtitle = q.header.ifBlank { null },
                        options = q.options.map { opt ->
                            SelectionOption(title = opt.label, description = opt.description, label = opt.label)
                        },
                        allowCustomInput = q.custom,
                        multiSelect = q.multiple
                    )
                    _signals.tryEmit(UiSignal.SelectionRequested(prompt))
                }
                return
            }
            is SseEvent.SessionCreated -> {
                _signals.tryEmit(UiSignal.SessionCreated(event.sessionId))
                return
            }
            is SseEvent.UserMessage -> {
                // Informational only — not added to messages
                return
            }
            is SseEvent.Plan -> {
                // Informational — no UI action needed
                return
            }
            is SseEvent.MessageComplete -> {
                // The Stop event handles streaming completion
                return
            }
            is SseEvent.Ignored -> {
                // Never reached — filtered in process() before enqueue. Handled here for
                // compiler exhaustiveness (Ignored is a member of the SseEvent sealed interface).
                return
            }
            else -> { /* handled below */ }
        }

        val msgId = ctx.activeMessageId
        if (msgId == null) {
            logger.info { "[ACP] processEvent DROP: ${event::class.simpleName} — activeMessageId is null (sid=${event.sessionId})" }
            return
        }

        // Verify the event's messageId matches our active turn's serverMessageId.
        // If we have a serverMessageId and the event carries one, they must match.
        // Cross-message events (ToolResult, Permission) are allowed through — they reference
        // toolCallId, not messageId, and are routed via toolCallIndex separately.
        val activeServerId = ctx.activeServerMessageId
        val eventServerId = event.messageId
        val isCrossMessageEvent = event is SseEvent.ToolResult || event is SseEvent.Permission
        logger.info { "[ACP] processEvent ROUTE: ${event::class.simpleName} activeMsgId=$msgId activeServerId=$activeServerId eventServerId=$eventServerId isCross=$isCrossMessageEvent" }
        if (!isCrossMessageEvent && activeServerId != null && eventServerId != null && eventServerId != activeServerId) {
            logger.info { "[ACP] processEvent SKIP: event messageId=$eventServerId != active=$activeServerId (${event::class.simpleName})" }
            return
        }

        when (event) {
            is SseEvent.TextChunk -> {
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
                scheduleFlush()
            }

            is SseEvent.TextReplace -> {
                ctx.textBuffer.clear()
                ctx.textBuffer.append(event.text)
                ctx.userEchoStripped = false
                val userText = ctx.lastUserText
                if (userText != null && ctx.textBuffer.toString().startsWith(userText, ignoreCase = true)) {
                    ctx.textBuffer.delete(0, userText.length)
                    ctx.userEchoStripped = true
                }
                scheduleFlush()
            }

            is SseEvent.ThinkingChunk -> {
                if (event.text.isNotBlank()) {
                    emitStreamingStartedIfNeeded(msgId)
                }
                ctx.thinkingBuffer.append(event.text)
                scheduleFlush()
            }

            is SseEvent.ThinkingReplace -> {
                ctx.thinkingBuffer.clear()
                ctx.thinkingBuffer.append(event.text)
                scheduleFlush()
            }

            is SseEvent.ToolUse -> {
                // Dedup: skip if callId already seen (input.started + called pair)
                if (event.toolCallId in ctx.toolCallPills) return

                // Track part state: this tool call is now InProgress
                ctx.toolPartStates[event.toolCallId] = PartState.InProgress

                val toolKind = ToolMapper.toAcpKind(event.toolName)
                val pill = ToolCallPill(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    title = event.title ?: event.toolName,
                    kind = toolKind,
                    status = ToolCallStatus.IN_PROGRESS,
                    input = event.input,
                )
                ctx.toolCallPills[event.toolCallId] = pill
                ctx.toolCallIndex[event.toolCallId] = msgId

                // Extract file path from tool input for edit tools
                if (toolKind == ToolKind.EDIT && event.input != null) {
                    val filePath = extractFilePath(event.input)
                    if (filePath != null) {
                        val fileName = filePath.substringAfterLast('/')
                        val change = ChatFileChange(filePath = filePath, fileName = fileName)
                        ctx.pendingFileChanges.add(change)
                        _signals.tryEmit(UiSignal.FileChanged(Unit))
                    }
                }

                flushToMessages()
            }

            is SseEvent.ToolResult -> {
                // Track part state: tool call completed or failed
                ctx.toolPartStates[event.toolCallId] = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed

                val existingPill = ctx.toolCallPills[event.toolCallId]
                if (existingPill != null) {
                    val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
                    ctx.toolCallPills[event.toolCallId] = existingPill.copy(
                        status = resolvedStatus,
                        output = event.content
                    )
                    flushToMessages()
                } else {
                    // Cross-message tool result — update via toolCallIndex
                    val targetMessageId = ctx.toolCallIndex[event.toolCallId]
                    if (targetMessageId != null) {
                        val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
                        updateToolCallStatus(event.toolCallId, resolvedStatus, event.content)
                    }
                }
            }

            is SseEvent.Permission -> {
                // Track part state: tool call is now Pending (waiting for user permission)
                ctx.toolPartStates[event.toolCallId] = PartState.Pending

                // Update pill status to PENDING
                val existingPill = ctx.toolCallPills[event.toolCallId]
                if (existingPill != null) {
                    ctx.toolCallPills[event.toolCallId] = existingPill.copy(status = ToolCallStatus.PENDING)
                } else {
                    // Cross-message permission — update via toolCallIndex
                    val targetMessageId = ctx.toolCallIndex[event.toolCallId]
                    if (targetMessageId != null) {
                        updateToolCallStatus(event.toolCallId, ToolCallStatus.PENDING)
                    }
                }
                val prompt = PermissionPrompt(
                    permissionId = event.permissionId,
                    toolCallId = event.toolCallId,
                    toolName = event.action,
                    description = event.description,
                    patterns = event.patterns
                )
                _signals.tryEmit(UiSignal.PermissionRequested(prompt))
                flushToMessages()
            }

            is SseEvent.Stop -> {
                // Mark thinking as completed so its state transitions Streaming → Completed
                ctx.activeThinkingCompleted = true
                // Flush remaining buffer (isStreaming still true → StreamHealer runs)
                flushToMessages()
                ctx.isStreaming = false
                // Transition message state: Streaming → Completed
                updateMessage(msgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                emitStreamingCompleted(msgId)
            }

            is SseEvent.Error -> {
                ctx.errorMessage = event.message
                ctx.isStreaming = false
                flushToMessages()
                // Transition message state: Streaming → Failed
                updateMessage(msgId) { it.copy(isStreaming = false, state = MessageState.Failed(event.message)) }
                _signals.tryEmit(UiSignal.Error(msgId, event.message))
            }

            is SseEvent.Patch -> {
                ctx.activePatches.add(event)
                scheduleFlush()
            }
            is SseEvent.Agent -> {
                ctx.activeAgentName = event.agentName
                scheduleFlush()
            }
            is SseEvent.Retry -> {
                ctx.activeRetry = event
                scheduleFlush()
            }
            is SseEvent.Compaction -> {
                ctx.activeCompaction = event
                scheduleFlush()
            }
            is SseEvent.Snapshot -> {
                // Internal state marker — no visual needed
            }
            is SseEvent.StepFinish -> {
                ctx.activeStepFinish = event
                scheduleFlush()
            }
            is SseEvent.Subtask -> {
                // Subtask creation — currently informational until split view is implemented
            }

            is SseEvent.AssistantFile -> {
                // Track as proper MessagePart type (rendered as inline card), NOT dumped into text buffer.
                // Key by partId for correct update semantics; fall back to url if partId is null.
                ctx.activeAssistantFiles[event.partId ?: event.url] = event
                scheduleFlush()
            }
            is SseEvent.AssistantImage -> {
                // Track as proper MessagePart type, NOT dumped into text buffer.
                // Key by partId for correct update semantics; fall back to url if partId is null.
                ctx.activeAssistantImages[event.partId ?: event.url] = event
                scheduleFlush()
            }
            // Events handled in the first when block (early return) — unreachable here.
            is SseEvent.Ignored,
            is SseEvent.MessageComplete,
            is SseEvent.Plan,
            is SseEvent.QuestionAsked,
            is SseEvent.SessionCreated,
            is SseEvent.TodoUpdated,
            is SseEvent.UserMessage -> { /* already returned above */ }
        }
    }

    // ── Internal: Flush & Segmentation ──────────────────────────────────────

    /** Schedule a flush. First chunk flushes immediately; subsequent chunks batch via conflated channel. */
    private fun scheduleFlush() {
        if (!firstChunkFlushed) {
            firstChunkFlushed = true
            flushToMessages()
        } else {
            flushChannel.trySend(Unit)
        }
    }

    /** Re-segment the full textBuffer, rebuild all parts, and emit StateFlow. */
    private fun flushToMessages() {
        val msgId = ctx.activeMessageId ?: return

        // 1. Segment text buffer into text-derived parts
        val textDerivedParts: List<MessagePart> = if (ctx.textBuffer.isNotEmpty()) {
            val raw = ctx.textBuffer.toString()
            val segments = if (ctx.isStreaming) {
                MarkdownSegmenter.segmentHealed(raw)
            } else {
                MarkdownSegmenter.segment(raw)
            }
            segments.mapNotNull { segmentToPart(it) }
        } else {
            emptyList()
        }

        // 2. Build thinking part with state transition
        val thinkingPart: MessagePart.Thinking? = if (ctx.thinkingBuffer.isNotEmpty()) {
            val thinkingState = if (ctx.activeThinkingCompleted) PartState.Completed else PartState.Streaming
            MessagePart.Thinking(ctx.thinkingBuffer.toString(), state = thinkingState)
        } else {
            null
        }

        // 3. Assemble complete parts map.
        //    Insertion order here does NOT determine rendering order — the UI
        //    (AssistantMessage composable) re-sorts by type. The map just needs
        //    all parts present with unique keys.
        //    All non-text-derived parts use generatePartId() as key to avoid collisions
        //    (never use business data like patch.hash or agentName as map keys).
        val parts = linkedMapOf<String, MessagePart>()

        // Thinking first
        if (thinkingPart != null) parts[MessagePart.generatePartId()] = thinkingPart

        // Tool calls (insertion order via LinkedHashMap)
        ctx.toolCallPills.values.forEach { pill ->
            val partState = ctx.toolPartStates[pill.toolCallId] ?: PartState.Created
            parts[pill.toolCallId] = MessagePart.ToolCall(pill = pill, state = partState)
        }

        // Text-derived parts (Text, Code, Table in segment order)
        textDerivedParts.forEach { part ->
            parts[MessagePart.generatePartId()] = part
        }

        // File changes
        ctx.pendingFileChanges.forEach { change ->
            parts[MessagePart.generatePartId()] = MessagePart.FileChange(change)
        }

        // Patch parts (authoritative file change summary from server)
        ctx.activePatches.forEach { patch ->
            parts[MessagePart.generatePartId()] = MessagePart.Patch(hash = patch.hash, files = patch.files)
        }

        // Assistant-generated files
        ctx.activeAssistantFiles.values.forEach { file ->
            parts[MessagePart.generatePartId()] = MessagePart.AssistantFile(
                mime = file.mime, url = file.url, filename = file.filename
            )
        }

        // Assistant-generated images
        ctx.activeAssistantImages.values.forEach { image ->
            parts[MessagePart.generatePartId()] = MessagePart.Image(
                mime = image.mime, url = image.url, filename = image.filename
            )
        }

        // Agent badge
        val agentName = ctx.activeAgentName
        if (agentName != null) {
            parts[MessagePart.generatePartId()] = MessagePart.Agent(name = agentName)
        }

        // Step finish
        val stepFinish = ctx.activeStepFinish
        if (stepFinish != null) {
            parts[MessagePart.generatePartId()] = MessagePart.StepFinish(
                snapshot = stepFinish.snapshot,
                inputTokens = stepFinish.inputTokens,
                outputTokens = stepFinish.outputTokens,
                reasoningTokens = stepFinish.reasoningTokens,
                totalCost = stepFinish.totalCost,
            )
        }

        // Retry
        val retry = ctx.activeRetry
        if (retry != null) {
            parts[MessagePart.generatePartId()] = MessagePart.Retry(attempt = retry.attempt, maxAttempts = retry.maxAttempts, error = retry.error)
        }

        // Compaction
        val compaction = ctx.activeCompaction
        if (compaction != null) {
            parts[MessagePart.generatePartId()] = MessagePart.Compaction(summary = compaction.summary)
        }

        // Error
        if (ctx.errorMessage != null) {
            parts[MessagePart.generatePartId()] = MessagePart.Error(ctx.errorMessage!!)
        }

        // 4. Build updated ChatMessage preserving metadata from existing message
        val existing = _messages.value[msgId]
        val updatedMessage = ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = parts,
            timestamp = existing?.timestamp ?: System.currentTimeMillis(),
            isStreaming = ctx.isStreaming,
            // Preserve existing state — transitions are set explicitly by event handlers
            state = existing?.state ?: MessageState.Streaming,
            attachedFiles = existing?.attachedFiles ?: emptyList(),
            modelID = existing?.modelID ?: ctx.modelID,
            providerID = existing?.providerID ?: ctx.providerID,
            inputTokens = existing?.inputTokens ?: 0L,
            outputTokens = existing?.outputTokens ?: 0L,
            reasoningTokens = existing?.reasoningTokens ?: 0L,
            cacheReadTokens = existing?.cacheReadTokens ?: 0L,
            cacheWriteTokens = existing?.cacheWriteTokens ?: 0L,
            cost = existing?.cost ?: 0.0,
            serverMessageId = existing?.serverMessageId,
        )

        // 5. Update _messages
        val updated = LinkedHashMap(_messages.value)
        updated[msgId] = updatedMessage
        _messages.value = updated
    }

    /** Convert a MarkdownSegment to a MessagePart. Returns null for blank segments. */
    private fun segmentToPart(segment: MarkdownSegment): MessagePart? {
        return when (segment.type) {
            MarkdownSegment.Type.TEXT -> {
                if (segment.content.isNotBlank()) MessagePart.Text(segment.content) else null
            }
            MarkdownSegment.Type.CODE -> {
                if (segment.content.isNotBlank()) MessagePart.Code(segment.language ?: "", segment.content) else null
            }
            MarkdownSegment.Type.TABLE -> {
                val parsed = MarkdownSegmenter.parseTable(segment.content.lines())
                if (parsed != null) {
                    MessagePart.Table(
                        rawMarkdown = segment.content,
                        headers = parsed.header,
                        rows = parsed.rows,
                        alignments = parsed.alignments
                    )
                } else {
                    // Fallback: treat as text
                    MessagePart.Text(segment.content)
                }
            }
        }
    }

    // ── Internal: Helpers ───────────────────────────────────────────────────

    /** Update a message in _messages by ID. No-op if not found. */
    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        val map = _messages.value
        val existing = map[messageId] ?: return
        val updated = LinkedHashMap(map)
        updated[messageId] = transform(existing)
        _messages.value = updated
    }

    /** Emit StreamingStarted signal exactly once per turn, on first content chunk arrival. */
    private fun emitStreamingStartedIfNeeded(msgId: String) {
        if (!ctx.streamingStartedEmitted) {
            ctx.streamingStartedEmitted = true
            // Transition message state: Created → Streaming
            updateMessage(msgId) { it.copy(state = MessageState.Streaming) }
            _signals.tryEmit(UiSignal.StreamingStarted(msgId))
        }
    }

    /** Emit StreamingCompleted signal exactly once per turn.
     *  Guards against double-emission when completeStreaming() is called after Stop. */
    private fun emitStreamingCompleted(msgId: String) {
        if (ctx.streamingCompletedEmitted) return
        ctx.streamingCompletedEmitted = true
        _signals.tryEmit(UiSignal.StreamingCompleted(msgId, ctx.pendingFileChanges.toList()))
    }

    /**
     * Extracts a file path from tool input JSON, trying multiple common key names.
     * Returns null if no path found.
     */
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
