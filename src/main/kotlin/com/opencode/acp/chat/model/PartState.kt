package com.opencode.acp.chat.model

/**
 * State machine for message parts that have a lifecycle.
 *
 * ToolCall states:
 *   Created ──► InProgress (tool called)
 *   Created ──► Pending (permission needed)
 *   Pending ──► InProgress (user allowed)
 *   Pending ──► Rejected (user rejected)
 *   InProgress ──► Completed (tool success)
 *   InProgress ──► Failed (tool error)
 *
 * Thinking/Text states:
 *   Streaming ──► Completed (all content received)
 */
sealed interface PartState {
    /** Part just created, no content yet. */
    data object Created : PartState

    /** Part is actively receiving content (streaming delta). */
    data object Streaming : PartState

    /** Tool call is in progress. */
    data object InProgress : PartState

    /** Tool call is waiting for user permission. */
    data object Pending : PartState

    /** Part completed normally. */
    data object Completed : PartState

    /** Part failed (tool error or parse error). */
    data class Failed(val reason: String) : PartState

    /** Tool call rejected by user. */
    data object Rejected : PartState
}
