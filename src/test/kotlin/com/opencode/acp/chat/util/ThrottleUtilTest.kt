package com.opencode.acp.chat.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ThrottleUtil] (TDD §4.2.6).
 *
 * Uses [FakeClock] for controllable time — no real-time dependencies.
 * Tests shouldProceed, markProceeded, and canProceedAndMark (CAS-based).
 */
class ThrottleUtilTest {

    private lateinit var clock: FakeClock
    private lateinit var throttle: ThrottleUtil

    @BeforeEach
    fun setUp() {
        clock = FakeClock(1000L)
        throttle = ThrottleUtil(cooldownMs = 100L, clock = clock)
    }

    // ── shouldProceed / markProceeded ─────────────────────────────────────

    @Test
    fun `shouldProceed returns true initially`() {
        throttle.shouldProceed() shouldBe true
    }

    @Test
    fun `shouldProceed returns false immediately after markProceeded`() {
        throttle.markProceeded()
        throttle.shouldProceed() shouldBe false
    }

    @Test
    fun `shouldProceed returns true after cooldown elapses`() {
        throttle.markProceeded()
        clock.advance(100L)
        throttle.shouldProceed() shouldBe true
    }

    @Test
    fun `shouldProceed returns false before cooldown elapses`() {
        throttle.markProceeded()
        clock.advance(99L)
        throttle.shouldProceed() shouldBe false
    }

    @Test
    fun `shouldProceed returns true exactly at cooldown boundary`() {
        throttle.markProceeded()
        clock.advance(100L)
        throttle.shouldProceed() shouldBe true
    }

    @Test
    fun `shouldProceed returns true well after cooldown`() {
        throttle.markProceeded()
        clock.advance(500L)
        throttle.shouldProceed() shouldBe true
    }

    @Test
    fun `markProceeded updates lastMs to current clock time`() {
        clock.advance(50L)
        throttle.markProceeded()
        // Now should be blocked until another 100ms passes
        clock.advance(99L)
        throttle.shouldProceed() shouldBe false
        clock.advance(1L)
        throttle.shouldProceed() shouldBe true
    }

    // ── canProceedAndMark (atomic check-and-mark) ─────────────────────────

    @Test
    fun `canProceedAndMark returns true on first call`() {
        throttle.canProceedAndMark() shouldBe true
    }

    @Test
    fun `canProceedAndMark returns false immediately after a successful call`() {
        throttle.canProceedAndMark() shouldBe true
        throttle.canProceedAndMark() shouldBe false
    }

    @Test
    fun `canProceedAndMark returns true after cooldown elapses`() {
        throttle.canProceedAndMark() shouldBe true
        clock.advance(100L)
        throttle.canProceedAndMark() shouldBe true
    }

    @Test
    fun `canProceedAndMark returns false before cooldown elapses`() {
        throttle.canProceedAndMark() shouldBe true
        clock.advance(50L)
        throttle.canProceedAndMark() shouldBe false
    }

    @Test
    fun `canProceedAndMark updates lastMs so subsequent shouldProceed is false`() {
        throttle.canProceedAndMark()
        throttle.shouldProceed() shouldBe false
    }

    @Test
    fun `canProceedAndMark multiple calls after cooldown all succeed serially`() {
        throttle.canProceedAndMark() shouldBe true
        clock.advance(100L)
        throttle.canProceedAndMark() shouldBe true
        clock.advance(100L)
        throttle.canProceedAndMark() shouldBe true
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun `zero cooldown always proceeds`() {
        val zeroThrottle = ThrottleUtil(cooldownMs = 0L, clock = clock)
        zeroThrottle.shouldProceed() shouldBe true
        zeroThrottle.markProceeded()
        zeroThrottle.shouldProceed() shouldBe true
    }

    @Test
    fun `canProceedAndMark with zero cooldown always succeeds`() {
        val zeroThrottle = ThrottleUtil(cooldownMs = 0L, clock = clock)
        zeroThrottle.canProceedAndMark() shouldBe true
        zeroThrottle.canProceedAndMark() shouldBe true
    }
}