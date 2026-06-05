package com.opencode.acp.chat.model

object ChatConstants {
    const val TOOL_WINDOW_ID = "OpenCodeChat"
    const val MAX_MESSAGE_HISTORY = 500
    const val THINKING_INDICATOR_DELAY_MS = 300L
    const val PERMISSION_TIMEOUT_MS = 60_000L
    const val RECONNECT_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    const val SIDEBAR_WIDTH_DP = 260
    const val SIDEBAR_CONTEXT_WIDTH_DP = 320
    const val SIDEBAR_REVIEW_WIDTH_DP = 260
    const val CONTEXT_INDICATOR_SIZE_DP = 28
    const val CONTEXT_FETCH_TIMEOUT_MS = 30_000L
    const val CONTEXT_REFRESH_DELAY_MS = 500L

    // ── Context overflow / compaction thresholds ─────────────────────────────────
    // Matches OpenCode's overflow.ts: usable = input_limit - reserved
    // reserved defaults to min(COMPACTION_BUFFER, maxOutputTokens)
    const val COMPACTION_BUFFER_TOKENS = 20_000L

    // Overflow = totalTokens >= usable, where:
    //   usable = inputLimit - COMPACTION_BUFFER (if we have inputLimit)
    //          or contextLimit - outputLimit - COMPACTION_BUFFER (fallback)
    // We simplify: overflow when usagePercent >= 80% (conservative threshold
    // that leaves room for the next response).
    const val OVERFLOW_THRESHOLD_PERCENT = 80f
}
