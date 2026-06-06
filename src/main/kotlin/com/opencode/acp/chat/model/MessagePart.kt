package com.opencode.acp.chat.model

import com.opencode.acp.chat.ui.compose.ParsedTable

/**
 * A typed, display-ready segment of message content.
 * The MessageProcessorManager decides what each part is — the UI just renders it.
 */
sealed interface MessagePart {
    /** Markdown text content. Rendered via Jewel's Markdown composable.
     *  The processor has already segmented and healed this text.
     *  A future phase may replace the markdown string with structured TextBlocks. */
    data class Text(val content: String) : MessagePart

    /** Fenced code block. Language identifier and source code. */
    data class Code(val language: String, val content: String) : MessagePart

    /** Markdown table. Raw markdown is preserved for rendering; parsed fields available for direct rendering. */
    data class Table(
        val rawMarkdown: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<ParsedTable.ColumnAlignment>
    ) : MessagePart

    /** Tool call pill. Uses ToolCallPill (a UI-presentable data class) directly
     *  for simplicity in this phase. A future refactor may introduce a domain-level
     *  ToolCallData class and decouple the processor from UI types. */
    data class ToolCall(val pill: ToolCallPill) : MessagePart

    /** Thinking/reasoning content. Always rendered before text content. */
    data class Thinking(val content: String) : MessagePart

    /** File change from a tool call. */
    data class FileChange(val change: ChatFileChange) : MessagePart

    /** Error message appended to a response. */
    data class Error(val message: String) : MessagePart

    /** Reference to a subagent/child session. */
    data class Subagent(val ref: SubagentRef) : MessagePart
}

