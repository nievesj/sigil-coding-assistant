package com.opencode.acp.chat.util

/**
 * Timeout and cooldown constants. Consolidates scattered values across 10+ files.
 * Values here are the canonical source — existing constants in ChatConstants
 * should eventually delegate here (not in this phase).
 */
object Timeouts {
    const val FOLLOW_COOLDOWN_MS = 2_000L
    const val PENDING_FOLLOW_DELAY_MS = 5_000L
    const val HEALTH_CHECK_MS = 10_000L
    const val SSE_HEALTH_CHECK_INTERVAL_MS = 60_000L
    const val SSE_HEALTH_CHECK_TIMEOUT_MS = 10_000L
    const val RECONNECT_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
}

/**
 * HTTP timeout profiles for [HttpHelper] (Phase 2).
 * INFINITE uses 24h (86,400,000ms) instead of Long.MAX_VALUE to avoid
 * overflow in Ktor engine implementations that use Int for timeout values.
 */
enum class TimeoutProfile(val timeoutMs: Long) {
    DEFAULT(30_000L),
    INFINITE(86_400_000L),
    MCP(10_000L),
}