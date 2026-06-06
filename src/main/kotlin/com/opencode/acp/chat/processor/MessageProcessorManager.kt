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

    companion object {
        /**
         * Feature flag controlling the rendering order of parts in AssistantMessage.
         * When true (default): Thinking → ToolCall → Text/Code/Table → FileChange → Subagent → Error
         * When false: ToolCall → Thinking → FileChange → Subagent → Text/Code/Table (legacy order)
         *
         * This allows toggling back to the old order if user testing reveals regressions.
         * See TDD §4.6.3 for details.
         */
        var useNewRenderingOrder: Boolean = true
    }


    private val logger = KotlinLogging.logger {}

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()

    private val ctx = ProcessorContext()
    private val messageIndex = mutableMapOf<String, Int>()  // messageId → index in _messages

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

    /** Enqueue an SSE event for processing. Thread-safe — called from SSE coroutine (any thread). */
    fun process(event: SseEvent) {
        eventChannel.trySend(event)
    }

    /** Create a placeholder assistant message for a new turn.
     *  Adds the message to _messages, sets ctx.activeMessageId, stores modelID/providerID.
     *  MUST be called before the first SSE event arrives (i.e., before process() is called).
     *  Returns the message ID. */
    fun createAssistantMessage(modelID: String?, providerID: String?): String {
        // Reset turn-specific state to prevent stale data from the previous turn
        // leaking into the new message. Preserves toolCallIndex (cross-message routing)
        // and lastUserText (set separately via setLastUserText).
        ctx.resetTurnState()

        val id = generateId()
        ctx.activeMessageId = id
        ctx.modelID = modelID
        ctx.providerID = providerID
        ctx.isStreaming = true
        firstChunkFlushed = false

        val message = ChatMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            isStreaming = true,
            timestamp = System.currentTimeMillis(),
            modelID = modelID,
            providerID = providerID,
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
        flushToMessages()
        ctx.isStreaming = false
        // Update the message's isStreaming flag
        updateMessage(messageId) { it.copy(isStreaming = false) }
        emitStreamingCompleted(messageId)
    }

    /** Abort in-flight streaming due to SSE stream drop or session switch.
     *  Appends MessagePart.Error, marks streaming complete. */
    fun abortStreaming(reason: String) {
        val msgId = ctx.activeMessageId ?: return
        ctx.errorMessage = reason
        ctx.isStreaming = false
        flushToMessages()
        updateMessage(msgId) { it.copy(isStreaming = false) }
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
        _messages.value = emptyList()
        messageIndex.clear()
        firstChunkFlushed = false
        // Restart coroutines for the new session
        eventProcessingJob?.cancel()
        flushJob?.cancel()
        startCoroutines()
    }

    /** Add a message directly (for loading history from REST API). */
    fun addMessage(message: ChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(message)
        messageIndex[message.id] = current.size - 1
        // Update toolCallIndex for history messages
        message.parts.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
            ctx.toolCallIndex[part.pill.toolCallId] = message.id
        }
        // FIFO eviction
        if (current.size > ChatConstants.MAX_MESSAGE_HISTORY) {
            val evictCount = current.size - ChatConstants.MAX_MESSAGE_HISTORY
            val evicted = current.take(evictCount)
            val remaining = current.drop(evictCount)
            evicted.forEach { msg ->
                messageIndex.remove(msg.id)
                msg.parts.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                    ctx.toolCallIndex.remove(part.pill.toolCallId)
                }
            }
            remaining.forEachIndexed { i, msg -> messageIndex[msg.id] = i }
            _messages.value = remaining
        } else {
            _messages.value = current
        }
    }

    /** Inject subagent references into an existing message.
     *  Removes existing Subagent parts, adds new ones, emits StateFlow. */
    fun injectSubagentRefs(messageId: String, refs: List<SubagentRef>) {
        updateMessage(messageId) { msg ->
            val nonSubagentParts = msg.parts.filter { it !is MessagePart.Subagent }
            val subagentParts = refs.map { MessagePart.Subagent(it) }
            msg.copy(parts = nonSubagentParts + subagentParts)
        }
    }

    /** Update tool call status (for permission responses that change pill state). */
    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>? = null) {
        // Update in context if it's the active message
        val pill = ctx.toolCallPills[toolCallId]
        if (pill != null) {
            ctx.toolCallPills[toolCallId] = pill.copy(status = status, output = output ?: pill.output)
            flushToMessages()
            return
        }
        // Otherwise, find the message containing this tool call and update it
        val messageId = ctx.toolCallIndex[toolCallId] ?: return
        updateMessage(messageId) { msg ->
            val updatedParts = msg.parts.map { part ->
                if (part is MessagePart.ToolCall && part.pill.toolCallId == toolCallId) {
                    MessagePart.ToolCall(part.pill.copy(status = status, output = output ?: part.pill.output))
                } else part
            }
            msg.copy(parts = updatedParts)
        }
    }

    /** Set the last user message text for echo stripping.
     *  MUST be called before process() begins (i.e., in sendMessage() before SSE events arrive). */
    fun setLastUserText(text: String?) {
        ctx.lastUserText = text
    }

    /** Cancel all coroutines. Call on plugin disposal to prevent leaks. */
    fun close() {
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
            else -> { /* handled below */ }
        }

        val msgId = ctx.activeMessageId
        if (msgId == null) {
            logger.debug { "Processor DROP: ${event::class.simpleName} — activeMessageId is null" }
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
                // Flush remaining buffer (isStreaming still true → StreamHealer runs)
                flushToMessages()
                ctx.isStreaming = false
                updateMessage(msgId) { it.copy(isStreaming = false) }
                emitStreamingCompleted(msgId)
            }

            is SseEvent.Error -> {
                ctx.errorMessage = event.message
                ctx.isStreaming = false
                flushToMessages()
                updateMessage(msgId) { it.copy(isStreaming = false) }
                _signals.tryEmit(UiSignal.Error(msgId, event.message))
            }
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

        // 2. Build thinking part
        val thinkingPart: MessagePart.Thinking? = if (ctx.thinkingBuffer.isNotEmpty()) {
            MessagePart.Thinking(ctx.thinkingBuffer.toString())
        } else {
            null
        }

        // 3. Assemble complete parts list in fixed rendering order
        val parts = mutableListOf<MessagePart>()

        // Thinking first
        if (thinkingPart != null) parts.add(thinkingPart)

        // Tool calls (insertion order via LinkedHashMap)
        ctx.toolCallPills.values.forEach { pill ->
            parts.add(MessagePart.ToolCall(pill))
        }

        // Text-derived parts (Text, Code, Table in segment order)
        parts.addAll(textDerivedParts)

        // File changes
        ctx.pendingFileChanges.forEach { change ->
            parts.add(MessagePart.FileChange(change))
        }

        // Error
        if (ctx.errorMessage != null) {
            parts.add(MessagePart.Error(ctx.errorMessage!!))
        }

        // 4. Build updated ChatMessage preserving metadata from existing message
        val existingIdx = messageIndex[msgId]
        val existing = if (existingIdx != null) _messages.value.getOrNull(existingIdx) else null
        val updatedMessage = ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = parts,
            timestamp = existing?.timestamp ?: System.currentTimeMillis(),
            isStreaming = ctx.isStreaming,
            attachedFiles = existing?.attachedFiles ?: emptyList(),
            modelID = existing?.modelID ?: ctx.modelID,
            providerID = existing?.providerID ?: ctx.providerID,
            inputTokens = existing?.inputTokens ?: 0L,
            outputTokens = existing?.outputTokens ?: 0L,
            reasoningTokens = existing?.reasoningTokens ?: 0L,
            cacheReadTokens = existing?.cacheReadTokens ?: 0L,
            cacheWriteTokens = existing?.cacheWriteTokens ?: 0L,
            cost = existing?.cost ?: 0.0,
        )

        // 5. Update _messages
        val idx = messageIndex[msgId]
        if (idx != null) {
            val list = _messages.value.toMutableList()
            list[idx] = updatedMessage
            _messages.value = list
        }
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
        val idx = messageIndex[messageId] ?: return
        val list = _messages.value.toMutableList()
        list[idx] = transform(list[idx])
        _messages.value = list
    }

    /** Emit StreamingStarted signal exactly once per turn, on first content chunk arrival. */
    private fun emitStreamingStartedIfNeeded(msgId: String) {
        if (!ctx.streamingStartedEmitted) {
            ctx.streamingStartedEmitted = true
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
