package com.opencode.acp

import kotlinx.serialization.json.JsonObject

/**
 * Sealed interface representing OpenCode SSE event types.
 * These are parsed from the /global/event SSE stream.
 */
sealed interface SseEvent {
    val sessionId: String

    /** Text content from the model (streaming). */
    data class TextChunk(
        override val sessionId: String,
        val text: String
    ) : SseEvent

    /** Tool requested by the LLM. */
    data class ToolUse(
        override val sessionId: String,
        val toolCallId: String,
        val toolName: String,
        val title: String? = null,
        val input: JsonObject? = null
    ) : SseEvent

    /** Tool execution result. */
    data class ToolResult(
        override val sessionId: String,
        val toolCallId: String,
        val isError: Boolean = false,
        val content: List<JsonObject>? = null
    ) : SseEvent

    /** Agent execution plan. */
    data class Plan(
        override val sessionId: String,
        val entries: List<PlanEntry>
    ) : SseEvent

    /** Turn completed with stop reason. */
    data class Stop(
        override val sessionId: String,
        val stopReason: String
    ) : SseEvent

    /** Permission check requested by OpenCode. */
    data class Permission(
        override val sessionId: String,
        val toolCallId: String,
        val action: String,
        val description: String? = null
    ) : SseEvent

    /** Error event from OpenCode. */
    data class Error(
        override val sessionId: String,
        val message: String,
        val code: Int? = null
    ) : SseEvent

    /** Session created event. */
    data class SessionCreated(
        override val sessionId: String
    ) : SseEvent

    /** Message completed event. */
    data class MessageComplete(
        override val sessionId: String,
        val messageId: String
    ) : SseEvent
}

/**
 * Represents a plan entry in an OpenCode agent plan.
 */
data class PlanEntry(
    val description: String,
    val priority: String,
    val status: String
)
