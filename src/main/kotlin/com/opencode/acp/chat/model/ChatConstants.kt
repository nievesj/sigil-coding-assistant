package com.opencode.acp.chat.model

object ChatConstants {
    const val TOOL_WINDOW_ID = "OpenCodeChat"
    const val MAX_MESSAGE_HISTORY = 500
    const val THINKING_INDICATOR_DELAY_MS = 300L
    const val PERMISSION_TIMEOUT_MS = 60_000L
    const val RECONNECT_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    /** Max idle time (ms) on the SSE connection before triggering reconnection.
     *  The Java HTTP engine has no socket-level idle timeout (see TDD §4.2.1),
     *  so we detect idle connections client-side. If no SSE events arrive within
     *  this window, we assume the connection is dead and reconnect. */
    const val SSE_IDLE_TIMEOUT_MS = 120_000L
    /** Interval (ms) for the SSE idle watch coroutine to check the last event time. */
    const val SSE_IDLE_CHECK_INTERVAL_MS = 15_000L
    const val SIDEBAR_WIDTH_DP = 260
    const val SIDEBAR_CONTEXT_WIDTH_DP = 320
    const val SIDEBAR_REVIEW_WIDTH_DP = 260
    const val CONTEXT_INDICATOR_SIZE_DP = 28
}
