package com.opencode.acp.chat.model

import com.opencode.acp.chat.ui.compose.ParsedTable

/**
 * A typed, display-ready segment of message content.
 * The session event processing logic determines what each part is — the UI just renders it.
 *
 * Every part carries a `partId` when known — this is the server-side part ID
 * (from V1 `message.part.delta`/`message.part.updated` events, or the server's
 * response on REST). It allows deterministic mapping of SSE deltas to specific
 * parts instead of dumping everything into shared buffers.
 */
sealed interface MessagePart {
    companion object {
        /** Generate a unique part ID for locally-created parts without a server partId. */
        fun generatePartId(): String = "prt_${com.opencode.acp.chat.util.generateId()}"
    }

    /** Part ID from the server. Null when not yet known (e.g. during initial segmentation). */
    val partId: String?

    /** Markdown text content. Rendered via Jewel's Markdown composable.
     *  The processor has already segmented and healed this text.
     *  A future phase may replace the markdown string with structured TextBlocks. */
    data class Text(
        val content: String,
        override val partId: String? = null
    ) : MessagePart

    /** Fenced code block. Language identifier and source code. */
    data class Code(
        val language: String,
        val content: String,
        override val partId: String? = null
    ) : MessagePart

    /** Markdown table. Raw markdown is preserved for rendering; parsed fields available for direct rendering. */
    data class Table(
        val rawMarkdown: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<ParsedTable.ColumnAlignment>,
        override val partId: String? = null
    ) : MessagePart

    /** Tool call pill. Uses ToolCallPill (a UI-presentable data class) directly
     *  for simplicity in this phase. A future refactor may introduce a domain-level
     *  ToolCallData class and decouple the processor from UI types. */
    data class ToolCall(
        val pill: ToolCallPill,
        /** Lifecycle state of this tool call. */
        val state: PartState = PartState.Created,
        override val partId: String? = null
    ) : MessagePart

    /** Thinking/reasoning content. Always rendered before text content. */
    data class Thinking(
        val content: String,
        /** Lifecycle state — Streaming while receiving deltas, Completed when done. */
        val state: PartState = PartState.Streaming,
        override val partId: String? = null
    ) : MessagePart

    /** File change from a tool call. */
    data class FileChange(
        val change: ChatFileChange,
        override val partId: String? = null
    ) : MessagePart

    /** Error message appended to a response. */
    data class Error(
        val message: String,
        override val partId: String? = null
    ) : MessagePart

    /** Reference to a subagent/child session. */
    data class Subagent(
        val ref: SubagentRef,
        override val partId: String? = null
    ) : MessagePart

    /** Authoritative file change summary from the server's patch part.
     *  Contains git hash for diff/revert and list of changed file paths. */
    data class Patch(
        val hash: String,
        val files: List<String>,
        override val partId: String? = null
    ) : MessagePart

    /** Assistant-generated file (distinct from tool-caused file changes). */
    data class AssistantFile(
        val mime: String,
        val url: String,
        val filename: String? = null,
        override val partId: String? = null
    ) : MessagePart

    /** Assistant-generated image. */
    data class Image(
        val mime: String,
        val url: String,
        val filename: String? = null,
        override val partId: String? = null
    ) : MessagePart

    /** Agent identification badge for a step. */
    data class Agent(
        val name: String,
        override val partId: String? = null
    ) : MessagePart

    /** Step finish with token usage summary. */
    data class StepFinish(
        val snapshot: String? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val reasoningTokens: Long? = null,
        val totalCost: Double? = null,
        override val partId: String? = null
    ) : MessagePart

    /** Retry attempt indicator. */
    data class Retry(
        val attempt: Int,
        val maxAttempts: Int,
        val error: String? = null,
        override val partId: String? = null
    ) : MessagePart

    /** Context compaction notification. */
    data class Compaction(
        val summary: String? = null,
        override val partId: String? = null
    ) : MessagePart
}

