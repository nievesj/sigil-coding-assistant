package com.opencode.acp.chat.util

/**
 * Time abstraction for testability. Inject into classes that need controllable
 * time in tests. Do NOT inject into UI composables or data class defaults.
 *
 * See TDD §4.2.1 — injected ONLY into: ResponseTimeoutMonitor, SseConnectionManager,
 * TextStreamingManager, EditorFollowManager, ThrottleUtil.
 */
interface Clock {
    fun now(): Long
}

/** Production implementation using wall-clock time. */
object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}