package com.opencode.acp.chat.model

/**
 * The visual rendering phase of a single message, used ONLY for the standalone
 * ThinkingIndicator at the bottom of the message. Does NOT control per-part
 * rendering (CollapsibleThinkingPill, ToolPill, etc.) — those render in the
 * `for` loop based on part type, which is compositional (multiple indicators
 * per message).
 *
 * Replaces the ad-hoc boolean condition:
 *   `isStreaming && !hasThinking && !hasToolCall && no Text/Code/Table parts`
 *
 * Exactly one phase is active per message per frame for the ThinkingIndicator.
 */
enum class MessageRenderPhase {
    /** Message is streaming with no content parts yet — show spinner + "Thinking…" */
    THINKING,

    /** Message has content (thinking, tool calls, text, code, or table) —
     *  no standalone ThinkingIndicator needed. Per-part indicators render
     *  compositionally in the `for` loop. */
    HAS_CONTENT,

    /** Message is not streaming — render all parts as final */
    COMPLETE,
}

/**
 * Computes the render phase for this message.
 *
 * THINKING can only fire when the message has zero content parts.
 * The moment any part arrives (Thinking, ToolCall, Text, etc.),
 * the phase transitions to HAS_CONTENT, and the standalone
 * ThinkingIndicator is suppressed.
 */
fun ChatMessage.renderPhase(): MessageRenderPhase {
    if (!isStreaming) return MessageRenderPhase.COMPLETE

    val hasAnyContent = parts.values.any {
        it is MessagePart.Thinking ||
        it is MessagePart.ToolCall ||
        it is MessagePart.Text ||
        it is MessagePart.Code ||
        it is MessagePart.Table
    }

    return if (hasAnyContent) MessageRenderPhase.HAS_CONTENT
            else MessageRenderPhase.THINKING
}