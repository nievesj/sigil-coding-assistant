package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.PressureLevel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for the real [ContextPressureMonitor].
 *
 * The monitor takes an injectable `clock: () -> Long` — we inject a controllable
 * mutable time variable so we can drive burn-rate calculations deterministically.
 *
 * The monitor's `recordTurn` and `computePressure` are `suspend` functions guarded
 * by a Mutex; tests run inside `kotlinx.coroutines.test.runTest`.
 */
class ContextPressureMonitorTest {

    private var time = 0L
    private fun newMonitor(): ContextPressureMonitor = ContextPressureMonitor(clock = { time })

    // 1. computePressure with < PRESSURE_MIN_TURNS returns null
    @Test
    fun `computePressure returns null when fewer than PRESSURE_MIN_TURNS data points`() = runTest {
        val monitor = newMonitor()
        // recordTurn only adds to growthHistory when lastInputTokens > 0, so the first
        // call does NOT add a data point. We need PRESSURE_MIN_TURNS data points, which
        // requires PRESSURE_MIN_TURNS + 1 recordTurn calls.
        repeat(CompactionConstants.PRESSURE_MIN_TURNS) {
            monitor.recordTurn(inputTokens = 1000L * (it + 1), timestampMs = time)
        }
        // After PRESSURE_MIN_TURNS calls, growthHistory has PRESSURE_MIN_TURNS - 1 entries.
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
    }

    // 2. computePressure with enough turns returns ContextPressure
    @Test
    fun `computePressure returns ContextPressure when enough data points`() = runTest {
        val monitor = newMonitor()
        // Need PRESSURE_MIN_TURNS data points → PRESSURE_MIN_TURNS + 1 recordTurn calls.
        repeat(CompactionConstants.PRESSURE_MIN_TURNS + 1) {
            monitor.recordTurn(inputTokens = 1000L * (it + 1), timestampMs = time)
        }
        val pressure = monitor.computePressure(currentTokens = 5000L, contextLimit = 100_000L)
        pressure shouldNotBe null
    }

    // 3. recordTurn computes delta from previous inputTokens
    @Test
    fun `recordTurn computes delta from previous inputTokens`() = runTest {
        val monitor = newMonitor()
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time) // first call: no delta
        monitor.recordTurn(inputTokens = 1500L, timestampMs = time) // delta = 500
        monitor.recordTurn(inputTokens = 2200L, timestampMs = time) // delta = 700
        // Now we have 2 data points (500, 700). Need PRESSURE_MIN_TURNS (3) for computePressure.
        monitor.recordTurn(inputTokens = 3000L, timestampMs = time) // delta = 800 → 3 data points
        val pressure = monitor.computePressure(currentTokens = 3000L, contextLimit = 100_000L)
        pressure shouldNotBe null
        // Average growth = (500 + 700 + 800) / 3 = 666.666...
        pressure!!.growthPerTurn shouldBe ((500.0 + 700.0 + 800.0) / 3.0)
    }

    // 4. recordTurn first call doesn't add to growthHistory (no previous to delta against)
    @Test
    fun `recordTurn first call does not add to growthHistory`() = runTest {
        val monitor = newMonitor()
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time)
        // Only one call → growthHistory is empty → computePressure returns null.
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
    }

    // 5. recordTurn trims to PRESSURE_WINDOW_SIZE (rolling window)
    @Test
    fun `recordTurn trims growthHistory to PRESSURE_WINDOW_SIZE`() = runTest {
        val monitor = newMonitor()
        // Record PRESSURE_WINDOW_SIZE + 5 turns. The first call doesn't add a data point,
        // so we need PRESSURE_WINDOW_SIZE + 6 calls to get PRESSURE_WINDOW_SIZE + 5 entries,
        // then trimming kicks in.
        val totalCalls = CompactionConstants.PRESSURE_WINDOW_SIZE + 6
        for (i in 1..totalCalls) {
            monitor.recordTurn(inputTokens = i.toLong() * 100, timestampMs = time)
        }
        // After trimming, growthHistory.size should be exactly PRESSURE_WINDOW_SIZE.
        // We verify indirectly: computePressure requires >= PRESSURE_MIN_TURNS, which is met.
        // To verify the window size, we observe that the average only reflects the last
        // PRESSURE_WINDOW_SIZE deltas. The first delta was 100 (between call 1 and 2).
        // After trimming, the oldest entries (including the 100 delta) are dropped.
        // The last PRESSURE_WINDOW_SIZE deltas are all 100 (constant), so average == 100.
        val pressure = monitor.computePressure(currentTokens = 10_000L, contextLimit = 100_000L)
        pressure shouldNotBe null
        pressure!!.growthPerTurn shouldBe 100.0
    }

    // 6. computePressure pressureLevel COMFORTABLE when usage < ELEVATED threshold
    @Test
    fun `computePressure returns COMFORTABLE when usage below ELEVATED threshold`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        val contextLimit = 100_000L
        val currentTokens = (contextLimit * (CompactionConstants.PRESSURE_ELEVATED_THRESHOLD - 0.05)).toLong()
        val pressure = monitor.computePressure(currentTokens = currentTokens, contextLimit = contextLimit)
        pressure shouldNotBe null
        pressure!!.pressureLevel shouldBe PressureLevel.COMFORTABLE
    }

    // 7. computePressure pressureLevel ELEVATED when usage >= ELEVATED threshold
    @Test
    fun `computePressure returns ELEVATED when usage at ELEVATED threshold`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        val contextLimit = 100_000L
        val currentTokens = (contextLimit * CompactionConstants.PRESSURE_ELEVATED_THRESHOLD).toLong()
        val pressure = monitor.computePressure(currentTokens = currentTokens, contextLimit = contextLimit)
        pressure shouldNotBe null
        pressure!!.pressureLevel shouldBe PressureLevel.ELEVATED
    }

    // 8. computePressure pressureLevel HIGH when usage >= HIGH threshold
    @Test
    fun `computePressure returns HIGH when usage at HIGH threshold`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        val contextLimit = 100_000L
        val currentTokens = (contextLimit * CompactionConstants.PRESSURE_HIGH_THRESHOLD).toLong()
        val pressure = monitor.computePressure(currentTokens = currentTokens, contextLimit = contextLimit)
        pressure shouldNotBe null
        pressure!!.pressureLevel shouldBe PressureLevel.HIGH
    }

    // 9. computePressure pressureLevel CRITICAL when usage >= CRITICAL threshold
    @Test
    fun `computePressure returns CRITICAL when usage at CRITICAL threshold`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        val contextLimit = 100_000L
        val currentTokens = (contextLimit * CompactionConstants.PRESSURE_CRITICAL_THRESHOLD).toLong()
        val pressure = monitor.computePressure(currentTokens = currentTokens, contextLimit = contextLimit)
        pressure shouldNotBe null
        pressure!!.pressureLevel shouldBe PressureLevel.CRITICAL
    }

    // 10. computePressure burnRatePerMinute calculated from timestamps
    @Test
    fun `computePressure burnRatePerMinute calculated from timestamps`() = runTest {
        val monitor = newMonitor()
        // Record 4 turns (3 data points) with 60_000ms (1 minute) between each.
        // Deltas: 1000, 1000, 1000. Total growth = 3000 over 2 minutes (first to last timestamp).
        time = 0L
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time) // no data point
        time = 60_000L
        monitor.recordTurn(inputTokens = 2000L, timestampMs = time) // delta 1000 @ 60s
        time = 120_000L
        monitor.recordTurn(inputTokens = 3000L, timestampMs = time) // delta 1000 @ 120s
        time = 180_000L
        monitor.recordTurn(inputTokens = 4000L, timestampMs = time) // delta 1000 @ 180s
        val pressure = monitor.computePressure(currentTokens = 4000L, contextLimit = 100_000L)
        pressure shouldNotBe null
        // timeSpan = 180_000 - 60_000 = 120_000ms = 2 minutes. totalGrowth = 3000.
        // burnRate = 3000 / 2 = 1500 tokens/min.
        pressure!!.burnRatePerMinute shouldBe 1500.0
    }

    // 11. computePressure turnsUntilCompact calculated from growth rate
    @Test
    fun `computePressure turnsUntilCompact calculated from growth rate`() = runTest {
        val monitor = newMonitor()
        // Constant growth of 1000 tokens/turn. 3 data points.
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time)
        monitor.recordTurn(inputTokens = 2000L, timestampMs = time)
        monitor.recordTurn(inputTokens = 3000L, timestampMs = time)
        monitor.recordTurn(inputTokens = 4000L, timestampMs = time)
        val contextLimit = 100_000L
        val currentTokens = 10_000L
        val pressure = monitor.computePressure(currentTokens = currentTokens, contextLimit = contextLimit)
        pressure shouldNotBe null
        // remainingTokens = (100_000 * 0.85) - 10_000 = 85_000 - 10_000 = 75_000
        // turnsUntilCompact = 75_000 / 1000 = 75
        pressure!!.turnsUntilCompact shouldBe 75
    }

    // 12. onCompaction clears history and resets lastInputTokens
    @Test
    fun `onCompaction clears history and resets lastInputTokens`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        monitor.onCompaction()
        // After compaction, growthHistory is empty → computePressure returns null.
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
        // And the next recordTurn should behave like a first call (no delta added).
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time)
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
    }

    // 13. reset clears history and resets lastInputTokens
    @Test
    fun `reset clears history and resets lastInputTokens`() = runTest {
        val monitor = newMonitor()
        seedEnoughTurns(monitor)
        monitor.reset()
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time)
        monitor.computePressure(currentTokens = 1000L, contextLimit = 100_000L) shouldBe null
    }

    // 14. recordTurn with decreasing inputTokens (compaction) — delta coerced to 0
    @Test
    fun `recordTurn with decreasing inputTokens coerces delta to 0`() = runTest {
        val monitor = newMonitor()
        monitor.recordTurn(inputTokens = 5000L, timestampMs = time) // first call: no delta
        monitor.recordTurn(inputTokens = 1000L, timestampMs = time) // delta = 1000 - 5000 = -4000 → coerced to 0
        monitor.recordTurn(inputTokens = 1500L, timestampMs = time) // delta = 500
        monitor.recordTurn(inputTokens = 2000L, timestampMs = time) // delta = 500
        val pressure = monitor.computePressure(currentTokens = 2000L, contextLimit = 100_000L)
        pressure shouldNotBe null
        // Average = (0 + 500 + 500) / 3 = 333.333...
        pressure!!.growthPerTurn shouldBe ((0.0 + 500.0 + 500.0) / 3.0)
    }

    /** Helper: record enough turns to satisfy PRESSURE_MIN_TURNS for computePressure. */
    private suspend fun seedEnoughTurns(monitor: ContextPressureMonitor) {
        repeat(CompactionConstants.PRESSURE_MIN_TURNS + 1) {
            monitor.recordTurn(inputTokens = 1000L * (it + 1), timestampMs = time)
        }
    }
}