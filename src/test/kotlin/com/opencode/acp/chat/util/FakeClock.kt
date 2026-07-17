package com.opencode.acp.chat.util

/**
 * Controllable time source for tests. Advance time to test timeout/throttle logic.
 *
 * @Synchronized ensures thread safety when tests use concurrent coroutines
 * (e.g., testing SseEventPipeline with multiple concurrent events).
 */
class FakeClock(private var time: Long = 1000L) : Clock {
    override fun now(): Long = time
    @Synchronized fun advance(by: Long) { time += by }
    @Synchronized fun set(time: Long) { this.time = time }
}