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

                    is SseEvent.MessageComplete -> {
                        logger.debug { "Message completed: ${sseEvent.messageId}" }
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