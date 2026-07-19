package com.opencode.acp.chat.model

import com.opencode.acp.chat.markdown.ParsedTable

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

    /**
     * Estimated character count of this part's text content, used for context
     * breakdown token estimation (TDD §4.2.5 rich domain model).
     *
     * Returns the length of the primary text-bearing field for each part type:
     * - [Text] → [Text.content] length
     * - [Code] → [Code.content] length
     * - [Table] → [Table.rawMarkdown] length
     * - [Thinking] → [Thinking.content] length
     * - All other parts → 0 (non-text parts don't contribute to char-based token estimation)
     *
     * This encapsulates the estimation logic previously duplicated in
     * `ContextBreakdown.estimateMessageChars()` and `estimateAssistantTextChars()`.
     */
    val estimatedCharCount: Long
        get() = when (this) {
            is Text -> content.length.toLong()
            is Code -> content.length.toLong()
            is Table -> rawMarkdown.length.toLong()
            is Thinking -> content.length.toLong()
            is ToolCall -> 0L
            is FileChange -> 0L
            is Error -> 0L
            is Patch -> 0L
            is AssistantFile -> 0L
            is Image -> 0L
            is Agent -> 0L
            is StepFinish -> 0L
            is Retry -> 0L
            is Compaction -> 0L
        }

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

