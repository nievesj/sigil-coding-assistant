@file:OptIn(
    com.agentclientprotocol.annotations.UnstableApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)

package com.opencode.acp

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.opencode.acp.adapter.*
import com.opencode.acp.event.SseEventListener
import com.opencode.acp.session.SessionIdMap
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val logger = KotlinLogging.logger {}

/**
 * Implements the ACP SDK's AgentSession interface.
 * Bridges ACP prompt/cancel operations to OpenCode engine via HTTP + SSE.
 */
class OpenCodeAgentSession(
    override val sessionId: SessionId,
    private val openCodeSessionId: String,
    private val openCodeClient: OpenCodeClient,
    private val contentMapper: ContentMapper,
    private val permissionBridge: PermissionBridge,
    private val terminalExecutor: TerminalExecutor,
    private val sessionIdMap: SessionIdMap,
    private val scope: CoroutineScope,
    private val replayMessages: List<StoredMessage> = emptyList()
) : AgentSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val promptJob = CompletableDeferred<Job>()

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?
    ): Flow<Event> = flow {
        val job = currentCoroutineContext()[Job] ?: error("No job in flow context")
        promptJob.complete(job)

        _meta?.let { handleMeta(it) }

        // If loading a session, replay stored messages first
        if (replayMessages.isNotEmpty()) {
            replayMessages.forEach { msg ->
                val contentBlock: ContentBlock = try {
                    json.decodeFromString<ContentBlock>(msg.contentJson)
                } catch (_: Exception) {
                    ContentBlock.Text(text = msg.contentJson)
                }
                when (msg.role) {
                    "user" -> emit(Event.SessionUpdateEvent(
                        update = SessionUpdate.UserMessageChunk(content = contentBlock)
                    ))
                    "assistant" -> emit(Event.SessionUpdateEvent(
                        update = SessionUpdate.AgentMessageChunk(content = contentBlock)
                    ))
                }
            }
            emit(Event.PromptResponseEvent(
                response = PromptResponse(stopReason = StopReason.END_TURN)
            ))
            return@flow
        }

        try {
            // 1. Convert ContentBlocks → OpenCodeParts
            val parts = content.mapNotNull { block ->
                try { contentMapper.toOpenCodePart(block) }
                catch (e: UnsupportedContentException) {
                    logger.warn(e) { "Skipping unsupported content type" }
                    null
                }
            }

            // 2. Send async to OpenCode, get correlation ID for SSE matching
            val correlationId = openCodeClient.sendMessageAsync(openCodeSessionId, parts)

            // 3. Subscribe to SSE events
            val sseListener = SseEventListener(
                openCodeClient = openCodeClient,
                sessionId = openCodeSessionId,
                correlationId = correlationId,
                scope = scope
            )

            sseListener.events.collect { sseEvent ->
                when (sseEvent) {
                    is SseEvent.TextChunk -> emit(Event.SessionUpdateEvent(
                        update = SessionUpdate.AgentMessageChunk(
                            content = ContentBlock.Text(text = sseEvent.text)
                        )
                    ))

                    is SseEvent.TextReplace -> emit(Event.SessionUpdateEvent(
                        update = SessionUpdate.AgentMessageChunk(
                            content = ContentBlock.Text(text = sseEvent.text)
                        )
                    ))

                    is SseEvent.MessageFinalized -> { /* informational — token/cost data */ }

                    is SseEvent.ToolUse -> emit(Event.SessionUpdateEvent(
                        // Initial tool call: use SessionUpdate.ToolCall for the first appearance
                        update = SessionUpdate.ToolCall(
                            toolCallId = ToolCallId(sseEvent.toolCallId),
                            title = sseEvent.title ?: sseEvent.toolName,
                            kind = ToolMapper.toAcpKind(sseEvent.toolName),
                            status = ToolCallStatus.PENDING,
                            content = emptyList(),
                            locations = emptyList(),
                            rawInput = sseEvent.input,
                            rawOutput = null
                        )
                    ))

                    is SseEvent.ToolResult -> emit(Event.SessionUpdateEvent(
                        // Tool result: use ToolCallUpdate to update status/content
                        update = SessionUpdate.ToolCallUpdate(
                            toolCallId = ToolCallId(sseEvent.toolCallId),
                            title = null,
                            kind = null,
                            status = if (sseEvent.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                            content = sseEvent.content?.mapNotNull { jsonObj ->
                                try {
                                    ToolCallContent.Content(content = ContentBlock.Text(text = jsonObj.toString()))
                                } catch (e: Exception) { null }
                            },
                            locations = null,
                            rawInput = null,
                            rawOutput = null
                        )
                    ))

                    is SseEvent.Plan -> emit(Event.SessionUpdateEvent(
                        update = SessionUpdate.PlanUpdate(
                            entries = PlanAdapter.toPlanEntries(sseEvent.entries)
                        )
                    ))

                    is SseEvent.Stop -> {
                        val reason = when (sseEvent.stopReason) {
                            "end_turn" -> StopReason.END_TURN
                            "max_tokens" -> StopReason.MAX_TOKENS
                            "max_turn_requests" -> StopReason.MAX_TURN_REQUESTS
                            "refusal" -> StopReason.REFUSAL
                            "cancelled" -> StopReason.CANCELLED
                            else -> StopReason.END_TURN
                        }
                        emit(Event.PromptResponseEvent(
                            response = PromptResponse(stopReason = reason)
                        ))
                        return@collect
                    }

                    is SseEvent.Permission -> {
                        // Permission handling is routed by the SDK.
                        // This event is informational — the SDK handles session/request_permission.
                        logger.debug { "Permission event for tool ${sseEvent.toolCallId}: ${sseEvent.action}" }
                    }

                    is SseEvent.Error -> {
                        logger.error { "OpenCode error: ${sseEvent.message}" }
                        emit(Event.PromptResponseEvent(
                            response = PromptResponse(stopReason = StopReason.CANCELLED)
                        ))
                        return@collect
                    }

                    is SseEvent.SessionCreated -> {
                        logger.debug { "Session created: ${sseEvent.sessionId}" }
                    }

                    is SseEvent.SessionIdle -> {
                        logger.debug { "Session idle: ${sseEvent.sessionId}" }
                    }

                    is SseEvent.SessionError -> {
                        logger.warn { "Session error: ${sseEvent.sessionId}, error=${sseEvent.errorMessage}" }
                    }

                    is SseEvent.SessionCompacted -> {
                        logger.info { "Session compacted: ${sseEvent.sessionId}" }
                    }

                    is SseEvent.MessageRemoved -> {
                        logger.debug { "Message removed: ${sseEvent.messageId}" }
                    }

                    is SseEvent.MessageComplete -> {
                        logger.debug { "Message completed: ${sseEvent.messageId}" }
                    }

                    is SseEvent.ThinkingChunk -> {
                        // Thinking content is handled by ChatViewModel for the chat UI.
                        // In the ACP SDK path, this is informational only.
                        logger.debug { "Thinking chunk: ${sseEvent.text.take(100)}" }
                    }

                    is SseEvent.ThinkingReplace -> {
                        logger.debug { "Thinking replace: ${sseEvent.text.take(100)}" }
                    }

                    is SseEvent.TodoUpdated -> {
                        // Todo updates are handled by ChatViewModel for the chat UI.
                        logger.debug { "Todo updated: ${sseEvent.todos.size} items" }
                    }

                    is SseEvent.UserMessage -> {
                        // User message from server - handled by ChatViewModel for the chat UI.
                        logger.debug { "User message received: ${sseEvent.text.take(100)}" }
                    }

                    is SseEvent.QuestionAsked -> {
                        // Question prompts are handled by ChatViewModel for the chat UI.
                        logger.debug { "Question asked: ${sseEvent.requestId}" }
                    }

                    is SseEvent.Patch -> {
                        // TODO: Implement when ACP SDK needs patch handling — emit appropriate event
                        logger.debug { "Patch: ${sseEvent.hash} — ${sseEvent.files.size} file(s)" }
                    }

                    is SseEvent.Agent -> {
                        // TODO: Implement when ACP SDK needs agent identification — emit appropriate event
                        logger.debug { "Agent: ${sseEvent.agentName}" }
                    }

                    is SseEvent.Retry -> {
                        // TODO: Implement when ACP SDK needs retry status — emit appropriate event
                        logger.debug { "Retry: ${sseEvent.attempt}/${sseEvent.maxAttempts}" }
                    }

                    is SseEvent.Compaction -> {
                        // TODO: Implement when ACP SDK needs compaction notification — emit appropriate event
                        logger.debug { "Compaction: ${sseEvent.summary}" }
                    }

                    is SseEvent.Snapshot -> {
                        // TODO: Implement when ACP SDK needs snapshot markers — emit appropriate event
                        logger.debug { "Snapshot: ${sseEvent.id}" }
                    }

                    is SseEvent.StepFinish -> {
                        // TODO: Implement when ACP SDK needs step finish with token data — emit appropriate event
                        logger.debug { "Step finish: ${sseEvent.snapshot}" }
                    }

                    is SseEvent.Subtask -> {
                        // TODO: Implement when ACP SDK needs subtask creation — emit appropriate event
                        logger.debug { "Subtask: ${sseEvent.description ?: sseEvent.prompt}" }
                    }

                    is SseEvent.AssistantFile -> {
                        // TODO: Implement when ACP SDK needs assistant file handling — emit appropriate event
                        logger.debug { "Assistant file: ${sseEvent.filename ?: sseEvent.url}" }
                    }

                    is SseEvent.AssistantImage -> {
                        // TODO: Implement when ACP SDK needs assistant image handling — emit appropriate event
                        logger.debug { "Assistant image: ${sseEvent.filename ?: sseEvent.url}" }
                    }

                    is SseEvent.Ignored -> {
                        logger.debug { "Ignored: ${sseEvent.eventType} — ${sseEvent.reason}" }
                    }
                }
            }
        } catch (e: CancellationException) {
            emit(Event.PromptResponseEvent(
                response = PromptResponse(stopReason = StopReason.CANCELLED)
            ))
        } catch (e: Exception) {
            logger.error(e) { "Prompt processing failed" }
            emit(Event.PromptResponseEvent(
                response = PromptResponse(stopReason = StopReason.CANCELLED)
            ))
        }
    }

    override suspend fun cancel() {
        try {
            openCodeClient.abortSession(openCodeSessionId)
        } catch (_: Exception) { }
        try {
            promptJob.getCompleted().cancel()
        } catch (_: Exception) { }
        terminalExecutor.releaseAllForSession(openCodeSessionId)
        sessionIdMap.remove(sessionId.value)
    }

    private fun handleMeta(meta: JsonElement) {
        // _meta from editor may contain mode override, model selection, etc.
        // Route to session config update if applicable
        logger.debug { "Received _meta: $meta" }
    }
}