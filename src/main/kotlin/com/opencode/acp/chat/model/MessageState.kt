package com.opencode.acp.chat.model

/**
 * State machine for a ChatMessage.
 *
 * Transitions:
 *   CREATED ──► STREAMING (first content arrives)
 *   STREAMING ──► COMPLETED (Stop event)
 *   STREAMING ──► FAILED (error/timeout/SSE drop)
 *   STREAMING ──► ABORTED (user cancel)
 */
sealed interface MessageState {
    /** Message just created, no content yet. */
    data object Created : MessageState

    /** Message is actively receiving content via SSE. */
    data object Streaming : MessageState

    /** Message completed normally (Stop event received). */
    data object Completed : MessageState

    /** Message failed (error, timeout, or SSE drop). */
    data class Failed(val reason: String) : MessageState

    /** Message aborted by user or server. */
    data object Aborted : MessageState
}
