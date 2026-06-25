package com.opencode.acp

import kotlinx.serialization.json.JsonObject

/**
 * Sealed interface representing OpenCode SSE event types.
 * These are parsed from the /global/event SSE stream.
 *
 * Every event carries:
 * - `sessionId` — the OpenCode session this event belongs to
 * - `messageId` — the message this event belongs to (deterministic routing)
 *   Null for session-level events (TodoUpdated, Permission, QuestionAsked, etc.)
 * - `partId` — the message part this event applies to (for content-level events)
 *   Null for session/message-level events
 */
sealed interface SseEvent {
    val sessionId: String
    val messageId: String?
    val partId: String?

    /** Text content from the model (streaming delta). */
    data class TextChunk(
        override val sessionId: String,
        val text: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Per-part finalization echo (message.part.updated — carries ONE text part's content, NOT the whole message's accumulated text). Streamed TextChunk deltas are authoritative; this confirms them. */
    data class TextReplace(
        override val sessionId: String,
        val text: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Tool requested by the LLM. */
    data class ToolUse(
        override val sessionId: String,
        val toolCallId: String,
        val toolName: String,
        val title: String? = null,
        val input: JsonObject? = null,
        val metadata: JsonObject? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Tool execution result. */
    data class ToolResult(
        override val sessionId: String,
        val toolCallId: String,
        val isError: Boolean = false,
        val content: List<JsonObject>? = null,
        val input: JsonObject? = null,
        val metadata: JsonObject? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Agent execution plan. */
    data class Plan(
        override val sessionId: String,
        val entries: List<PlanEntry>,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Turn completed with stop reason. */
    data class Stop(
        override val sessionId: String,
        val stopReason: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Permission check requested by OpenCode. */
    data class Permission(
        override val sessionId: String,
        val permissionId: String,
        val toolCallId: String,
        val action: String,
        val description: String? = null,
        val patterns: List<String> = emptyList(),
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Error event from OpenCode. */
    data class Error(
        override val sessionId: String,
        val message: String,
        val code: Int? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Session created event. */
    data class SessionCreated(
        override val sessionId: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Message completed event. */
    data class MessageComplete(
        override val sessionId: String,
        override val messageId: String,
        override val partId: String? = null
    ) : SseEvent

    /** Thinking/reasoning content from the model (streaming delta). */
    data class ThinkingChunk(
        override val sessionId: String,
        val text: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Per-thinking-part content replacement (message.part.updated — one thinking part's accumulated text, not the full turn's text). */
    data class ThinkingReplace(
        override val sessionId: String,
        val text: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Todo list updated event. */
    data class TodoUpdated(
        override val sessionId: String,
        val todos: List<SseTodoItem>,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** User message received from server (session.next.prompted). */
    data class UserMessage(
        override val sessionId: String,
        val text: String,
        val files: List<String> = emptyList(),
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Question asked by the agent (question.asked) — needs user selection/input. */
    data class QuestionAsked(
        override val sessionId: String,
        val requestId: String,
        val questions: List<SseQuestionInfo>,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Patch part: server-provided file change summary with git hash and file paths. */
    data class Patch(
        override val sessionId: String,
        val hash: String,
        val files: List<String>,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Agent part: identifies which agent handled a step. */
    data class Agent(
        override val sessionId: String,
        val agentName: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Retry part: retry attempt status. */
    data class Retry(
        override val sessionId: String,
        val attempt: Int,
        val maxAttempts: Int,
        val error: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Compaction part: context compaction notification. */
    data class Compaction(
        override val sessionId: String,
        val summary: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Snapshot part: internal state marker. */
    data class Snapshot(
        override val sessionId: String,
        val id: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Step finish: step completion with token usage data. */
    data class StepFinish(
        override val sessionId: String,
        val snapshot: String? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val reasoningTokens: Long? = null,
        val totalCost: Double? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Subtask: child session creation event. */
    data class Subtask(
        override val sessionId: String,
        val prompt: String? = null,
        val description: String? = null,
        val agent: String? = null,
        val model: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Assistant-generated file (not a tool file change — represents output files). */
    data class AssistantFile(
        override val sessionId: String,
        val mime: String,
        val url: String,
        val filename: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Assistant-generated image. */
    data class AssistantImage(
        override val sessionId: String,
        val mime: String,
        val url: String,
        val filename: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /** Session idle — server finished processing the current prompt cycle. */
    data class SessionIdle(
        override val sessionId: String,
        override val messageId: String? = null,
        override val partId: String? = null,
    ) : SseEvent

    /** Session error — server encountered an error for this session. */
    data class SessionError(
        override val sessionId: String,
        val errorMessage: String? = null,
        override val messageId: String? = null,
        override val partId: String? = null,
    ) : SseEvent

    /** Session compacted — server performed auto-compaction (context window overflow). */
    data class SessionCompacted(
        override val sessionId: String,
        override val messageId: String? = null,
        override val partId: String? = null,
    ) : SseEvent

    /** Message finalized with token/cost/model data from message.updated SSE event. */
    data class MessageFinalized(
        override val sessionId: String,
        override val messageId: String?,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val reasoningTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val cacheWriteTokens: Long? = null,
        val cost: Double? = null,
        val modelID: String? = null,
        val providerID: String? = null,
        val stopReason: String? = null,
        override val partId: String? = null,
    ) : SseEvent

    /** Message removed — server removed a message (e.g., after compaction). */
    data class MessageRemoved(
        override val sessionId: String,
        override val messageId: String?,
        override val partId: String? = null,
    ) : SseEvent

    /**
     * Event that was received but intentionally not processed.
     * Replaces silent null returns — every SSE event produces a non-null SseEvent.
     *
     * @param eventType The raw SSE event type (e.g. "session.next.step.started")
     * @param reason Why this event was ignored: "intentional no-op", "unknown type", "parse error: ..."
     */
    data class Ignored(
        override val sessionId: String,
        val eventType: String,
        val reason: String,
        override val messageId: String? = null,
        override val partId: String? = null
    ) : SseEvent

    /**
     * Internal control event (never from SSE) — signals that [SessionState.ctx]
     * should be reset for a new streaming turn. Sent through [SessionState.eventChannel]
     * so the reset runs on the event processing coroutine, eliminating the race
     * between external callers (under stateLock) and event processing (no lock).
     *
     * This event is NOT produced by the SSE parser — it is only enqueued by
     * [SessionState.createAssistantMessage] when called from a non-event-processing
     * coroutine.
     *
     * When [newTurnMessageId] is non-null, the event carries the identity of the
     * freshly-created turn so the event-processing coroutine can apply it
     * atomically after clearing stale state. This eliminates the window where
     * [ProcessorContext.activeMessageId] is null between the reset and the first
     * real SSE content event — which previously caused a duplicate auto-create.
     */
    data class ResetTurn(
        override val sessionId: String,
        override val messageId: String? = null,
        override val partId: String? = null,
        /** Identity of the new turn established by [SessionState.createAssistantMessage].
         *  When non-null, applied to [ProcessorContext] after [ProcessorContext.resetTurnState]. */
        val newTurnMessageId: String? = null,
        val newTurnServerMessageId: String? = null,
        val newTurnModelID: String? = null,
        val newTurnProviderID: String? = null,
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

/**
 * Represents a todo item from OpenCode's todowrite tool.
 */
data class SseTodoItem(
    val content: String,
    val status: String,
    val priority: String
)

/** A single question from a question.asked SSE event. */
data class SseQuestionInfo(
    val question: String,
    val header: String,
    val options: List<SseQuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true
)

/** A single option in a question.asked event. */
data class SseQuestionOption(
    val label: String,
    val description: String
)
