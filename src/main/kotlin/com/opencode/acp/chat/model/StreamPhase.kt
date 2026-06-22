package com.opencode.acp.chat.model

/**
 * The streaming lifecycle phase of the chat input area.
 *
 * Replaces the boolean _isStreaming with an explicit state machine that
 * distinguishes "message dispatched, awaiting first token" (SENDING) from
 * "LLM actively generating tokens" (STREAMING). Both phases show identical
 * UI indicators (glow, stop button, pulse, shimmer).
 *
 * Transitions:
 *   IDLE → SENDING      (sendMessage() called, before suspend)
 *   SENDING → STREAMING  (UiSignal.StreamingStarted — first token)
 *   SENDING → IDLE       (error, cancel, switchSession, session.error)
 *   STREAMING → IDLE     (UiSignal.StreamingCompleted, cancel, switchSession, session.error)
 *   STREAMING → SENDING  (steerMessage: cancel + resend via sendMessage)
 *
 * Backstops (recovery paths):
 *   SENDING/STREAMING → IDLE  (activity monitor timeout → abortStreaming → StreamingCompleted)
 *   SENDING/STREAMING → IDLE  (SessionIdle backstop → finalizeStreaming → StreamingCompleted)
 *   STREAMING → IDLE          (SseEvent.Error → emitStreamingCompleted → StreamingCompleted)
 */
enum class StreamPhase {
    IDLE,
    SENDING,
    STREAMING,
}