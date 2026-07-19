package com.opencode.acp.chat.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Cooldown-based throttle. Consolidates 3 implementations
 * (CommandFollow, SearchFollow, EditorFollow).
 *
 * Takes a [Clock] for testability — inject [FakeClock] in tests, [SystemClock] in production.
 */
class ThrottleUtil(
    private val cooldownMs: Long,
    private val clock: Clock = SystemClock,
) {
    private val lastMs = AtomicLong(0L)

    /** Returns true if enough time has passed since the last proceed. */
    fun shouldProceed(): Boolean = clock.now() - lastMs.get() >= cooldownMs

    /** Records that a proceed happened now. */
    fun markProceeded() { lastMs.set(clock.now()) }

    /**
     * Atomic check-and-mark: returns true and records the time if within the
     * cooldown window. Uses CAS to handle concurrent calls safely.
     */
    fun canProceedAndMark(): Boolean {
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val current = lastMs.get()
            val now = clock.now()
            if (now - current < cooldownMs) return false
            if (lastMs.compareAndSet(current, now)) return true
        }
        return false  // give up under extreme contention
    }

    companion object { private const val MAX_ATTEMPTS = 5 }
}