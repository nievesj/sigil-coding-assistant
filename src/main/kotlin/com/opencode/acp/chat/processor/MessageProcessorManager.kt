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
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns all mutable streaming state and emits display-ready [List<ChatMessage>].
 *
 * Architecture: every SSE event handler updates the message parts map DIRECTLY
 * via [updateMessage], touching only its own key. No full rebuild. The parts map
 * is a [LinkedHashMap] so insertion order is preserved — the UI renders in the
 * order events arrive (thinking → tool call → text), not grouped by type.
 *
 * The one exception is text: [TextChunk]/[TextReplace] events accumulate in
 * [ProcessorContext.textBuffer] and are periodically re-segmented into markdown
 * parts ([MessagePart.Text], [MessagePart.Code], [MessagePart.Table]) via
 * [resegmentTextParts]. This replaces only the `text_*`/`code_*`/`table_*`
 * keys in the map — everything else stays untouched.
 *
 * Thread safety: all mutations to [_messages] and [ctx] are protected by [stateLock].
 */
class MessageProcessorManager(private val scope: CoroutineScope) {

    private val logger = KotlinLogging.logger {}

    private val _messages = MutableStateFlow<LinkedHashMap<String, ChatMessage>>(LinkedHashMap())
    val messages: StateFlow<Map<String, ChatMessage>> = _messages.asStateFlow()

    private val _signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()

    private val ctx = ProcessorContext()

    private val stateLock = ReentrantLock()

    private val eventChannel = Channel<SseEvent>(capacity = 1024)
    private var eventProcessingJob: Job? = null

    /** Debounced resegment job for text parts. */
    private var resegmentJob: Job? = null

    /** Whether the first text chunk has been segmented (for immediate first-segment responsiveness). */
    private var firstTextSegmented = false

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
    }

    // ── Public API ──────────────────────────────────────────────────────────

    suspend fun process(event: SseEvent) {
        if (event is SseEvent.Ignored) {
            logger.debug { "Ignored: ${event.eventType} — ${event.reason}" }
            return
        }
        try {
            eventChannel.send(event)
        } catch (e: Exception) {
            logger.debug { "[ACP] processEvent send interrupted: ${event::class.simpleName} — ${e.message}" }
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
        logger.info { "[ACP] createAssistantMessage: id=$id, serverMessageId=$serverMessageId" }
        firstTextSegmented = false

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

    fun completeStreaming(messageId: String) = stateLock.withLock {
        if (messageId != ctx.activeMessageId) return@withLock
        // Freeze current thinking phase if still streaming
        freezeCurrentThinking()
        // Re-segment text one final time (without StreamHealer — completed text)
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
        // Add error part directly
        updateMessage(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(reason)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Aborted)
        }
        emitStreamingCompleted(msgId)
        _signals.tryEmit(UiSignal.Error(msgId, reason))
    }

    fun resetSessionState() = stateLock.withLock {
        val msgCount = _messages.value.size
        logger.info { "[ACP] resetSessionState: clearing $msgCount messages from _messages" }
        while (eventChannel.tryReceive().isSuccess) { /* drain */ }
        resegmentJob?.cancel()
        resegmentJob = null
        ctx.reset()
        _messages.value = LinkedHashMap()
        firstTextSegmented = false
        eventProcessingJob?.cancel()
        eventProcessingJob = null
        startCoroutines()
        logger.info { "[ACP] resetSessionState: DONE — _messages cleared" }
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

    fun injectSubagentRefs(messageId: String, refs: List<SubagentRef>) = stateLock.withLock {
        updateMessage(messageId) { msg ->
            val partsWithoutSubagents = LinkedHashMap(msg.parts.filterValues { it !is MessagePart.Subagent })
            refs.forEach { ref ->
                partsWithoutSubagents[ref.sessionId] = MessagePart.Subagent(ref)
            }
            msg.copy(parts = partsWithoutSubagents)
        }
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

        // Find the message containing this tool call
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
                    parts[toolCallId] = MessagePart.ToolCall(
                        pill = ToolCallPill(toolCallId = toolCallId, toolName = "tool", title = "tool", kind = ToolKind.OTHER, status = status, output = output),
                        state = newPartState
                    )
                }
                msg.copy(parts = parts)
            }
        }
        // Also update secondary index
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

    fun close() = stateLock.withLock {
        logger.info { "[ACP] processor.close() called" }
        eventProcessingJob?.cancel()
        eventProcessingJob = null
        resegmentJob?.cancel()
        resegmentJob = null
        eventChannel.close()
    }

    // ── Internal: Event Processing ──────────────────────────────────────────

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
            is SseEvent.UserMessage -> { return }
            is SseEvent.Plan -> { return }
            is SseEvent.MessageComplete -> { return }
            is SseEvent.Ignored -> { return }
            else -> { /* handled below */ }
        }

        val msgId = ctx.activeMessageId
        if (msgId == null) {
            logger.info { "[ACP] processEvent DROP: ${event::class.simpleName} — activeMessageId is null" }
            return
        }

        // Server ID routing check
        val activeServerId = ctx.activeServerMessageId
        val eventServerId = event.messageId
        val isCrossMessageEvent = event is SseEvent.ToolResult || event is SseEvent.Permission
        logger.info { "[ACP] processEvent ROUTE: ${event::class.simpleName} activeMsgId=$msgId activeServerId=$activeServerId eventServerId=$eventServerId isCross=$isCrossMessageEvent" }
        if (!isCrossMessageEvent && activeServerId != null && eventServerId != null && eventServerId != activeServerId) {
            logger.info { "[ACP] processEvent SKIP: event messageId=$eventServerId != active=$activeServerId" }
            return
        }

        when (event) {
            // ── Thinking ──────────────────────────────────────────────────
            is SseEvent.ThinkingChunk -> {
                // If current thinking phase is completed, start a new one
                if (ctx.activeThinkingCompleted) {
                    freezeCurrentThinking()
                }
                // If no active thinking phase, start one
                if (ctx.activeThinkingKey == null) {
                    val key = "thinking_${ctx.thinkingPhaseIndex}"
                    ctx.activeThinkingKey = key
                    ctx.thinkingPhaseIndex++
                    emitStreamingStartedIfNeeded(msgId)
                }
                ctx.thinkingBuffer.append(event.text)
                // Direct update: only touch this thinking phase's key
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
                // If current thinking phase is completed, start a new one
                if (ctx.activeThinkingCompleted) {
                    freezeCurrentThinking()
                }
                // If no active thinking phase, start one
                if (ctx.activeThinkingKey == null) {
                    val key = "thinking_${ctx.thinkingPhaseIndex}"
                    ctx.activeThinkingKey = key
                    ctx.thinkingPhaseIndex++
                    emitStreamingStartedIfNeeded(msgId)
                }
                // Replace buffer content (full replacement from server)
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
                scheduleResegment(msgId)
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
                scheduleResegment(msgId)
            }

            // ── Tool calls ───────────────────────────────────────────────
            is SseEvent.ToolUse -> {
                // Dedup: skip if callId already seen
                if (event.toolCallId in ctx.toolCallPills) return

                // If there's an active streaming thinking phase, freeze it now
                // (tool call means thinking is done for this phase)
                if (!ctx.activeThinkingCompleted && ctx.thinkingBuffer.isNotEmpty()) {
                    ctx.activeThinkingCompleted = true
                    freezeCurrentThinking()
                }
                emitStreamingStartedIfNeeded(msgId)

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

                // Extract file path for edit tools
                if (toolKind == ToolKind.EDIT && event.input != null) {
                    val filePath = extractFilePath(event.input)
                    if (filePath != null) {
                        val fileName = filePath.substringAfterLast('/')
                        val change = ChatFileChange(filePath = filePath, fileName = fileName)
                        ctx.pendingFileChanges.add(change)
                        _signals.tryEmit(UiSignal.FileChanged(Unit))
                    }
                }

                // Direct update: add tool call part under its own key
                updateMessage(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts[event.toolCallId] = MessagePart.ToolCall(pill = pill, state = PartState.InProgress)
                    // Also add file change part if any
                    if (toolKind == ToolKind.EDIT && event.input != null) {
                        extractFilePath(event.input)?.let { path ->
                            val fileName = path.substringAfterLast('/')
                            val change = ChatFileChange(filePath = path, fileName = fileName)
                            val fcKey = "filechange_${ctx.pendingFileChanges.size - 1}"
                            parts[fcKey] = MessagePart.FileChange(change)
                        }
                    }
                    msg.copy(parts = parts, isStreaming = ctx.isStreaming)
                }
            }

            is SseEvent.ToolResult -> {
                ctx.toolPartStates[event.toolCallId] = if (event.isError) PartState.Failed(event.content?.toString() ?: "Tool error") else PartState.Completed
                val existingPill = ctx.toolCallPills[event.toolCallId]
                val resolvedStatus = if (event.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
                if (existingPill != null) {
                    ctx.toolCallPills[event.toolCallId] = existingPill.copy(
                        status = resolvedStatus,
                        input = event.input ?: existingPill.input,
                        output = event.content
                    )
                }
                // Direct update: update only this tool call's key
                val targetMsgId = ctx.toolCallIndex[event.toolCallId] ?: msgId
                updateMessage(targetMsgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    val existing = parts[event.toolCallId]
                    if (existing is MessagePart.ToolCall) {
                        val newInput = event.input ?: existing.pill.input
                        parts[event.toolCallId] = existing.copy(
                            pill = existing.pill.copy(status = resolvedStatus, input = newInput, output = event.content ?: existing.pill.output),
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
                // Direct update: update only this tool call's key
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
                    permissionId = event.permissionId,
                    toolCallId = event.toolCallId,
                    toolName = event.action,
                    description = event.description,
                    patterns = event.patterns
                )
                _signals.tryEmit(UiSignal.PermissionRequested(prompt))
            }

            // ── Stop ─────────────────────────────────────────────────────
            is SseEvent.Stop -> {
                if (!ctx.isStreaming) {
                    logger.info { "[ACP] processEvent SKIP Stop: not streaming (reason=${event.stopReason})" }
                    return
                }

                // Freeze current thinking phase
                freezeCurrentThinking()
                // Final text resegment
                resegmentTextPartsDirect(msgId)

                if (event.stopReason == "tool-calls") {
                    logger.info { "[ACP] processEvent Stop (tool-calls): intermediate — continuing stream" }
                    // Don't stop streaming — more events will follow
                } else {
                    ctx.isStreaming = false
                    updateMessage(msgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                    emitStreamingCompleted(msgId)
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
            is SseEvent.Plan,
            is SseEvent.QuestionAsked,
            is SseEvent.SessionCreated,
            is SseEvent.TodoUpdated,
            is SseEvent.UserMessage -> { /* already returned above */ }
        }
    }

    // ── Internal: Thinking Phase Management ─────────────────────────────────

    /** Freeze the current thinking phase: mark it Completed in the parts map
     *  and reset per-phase state so a new phase can start. */
    private fun freezeCurrentThinking() {
        val key = ctx.activeThinkingKey ?: return
        if (ctx.thinkingBuffer.isEmpty()) return
        val content = ctx.thinkingBuffer.toString()
        // Update the part to Completed
        val msgId = ctx.activeMessageId ?: return
        updateMessage(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[key] = MessagePart.Thinking(content = content, state = PartState.Completed)
            msg.copy(parts = parts)
        }
        // Reset per-phase state for next phase
        ctx.thinkingBuffer.setLength(0)
        ctx.activeThinkingKey = null
        ctx.activeThinkingCompleted = false
    }

    // ── Internal: Text Re-Segmentation ──────────────────────────────────────

    /** Schedule a debounced resegmentation of text parts. */
    private fun scheduleResegment(msgId: String) {
        if (!firstTextSegmented) {
            firstTextSegmented = true
            resegmentTextPartsDirect(msgId)
            return
        }
        resegmentJob?.cancel()
        resegmentJob = scope.launch(Dispatchers.EDT) {
            try {
                kotlinx.coroutines.delay(DEBOUNCE_MS)
                resegmentTextPartsDirect(msgId)
            } catch (_: CancellationException) { /* newer event arrived */ }
        }
    }

    /** Re-segment the text buffer and replace only text-derived keys in the parts map.
     *  All other keys (thinking, tool calls, patches, etc.) are left untouched. */
    private fun resegmentTextPartsDirect(msgId: String) {
        stateLock.withLock {
            if (ctx.textBuffer.isEmpty()) {
                // Remove all text-derived keys
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
            // Build new text-derived parts
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
                }
            }
            // Replace text-derived keys: remove old ones, insert new ones
            // The position is right after the last non-text part (or at the end).
            // Since LinkedHashMap preserves insertion order, we re-insert at the
            // same position by rebuilding the map.
            updateMessage(msgId) { msg ->
                val oldParts = LinkedHashMap(msg.parts)
                // Collect non-text entries in order
                val preserved = mutableListOf<Pair<String, MessagePart>>()
                var lastNonTextIndex = -1
                oldParts.entries.forEachIndexed { i, entry ->
                    val isTextDerived = entry.key.startsWith("text_") || entry.key.startsWith("code_") || entry.key.startsWith("table_")
                    if (!isTextDerived) {
                        preserved.add(entry.key to entry.value)
                        lastNonTextIndex = preserved.size - 1
                    }
                }
                // Build new map: preserved parts with text parts inserted after the last non-text part
                val newMap = linkedMapOf<String, MessagePart>()
                preserved.forEachIndexed { i, (key, part) ->
                    newMap[key] = part
                    // Insert text parts right after this non-text entry if it's the last one
                    // (or if we haven't placed them yet)
                    if (i == lastNonTextIndex || (lastNonTextIndex == -1 && i == 0)) {
                        newParts.forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                    }
                }
                // Edge case: no non-text parts at all
                if (lastNonTextIndex == -1) {
                    newParts.forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                }
                msg.copy(parts = newMap)
            }
        }
    }

    /** Final resegment after streaming completes — uses strict (non-healed) segmentation. */
    private fun resegmentTextPartsFinal(msgId: String) = stateLock.withLock {
        // Temporarily mark as not streaming so segmentation is strict
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