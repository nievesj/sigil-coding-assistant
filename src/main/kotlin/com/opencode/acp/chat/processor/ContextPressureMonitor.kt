package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.ContextPressure
import com.opencode.acp.chat.model.PressureLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks context pressure via rolling growth rate.
 * Uses Welford's algorithm for online variance computation.
 *
 * Thread safety: All mutable state is guarded by a [Mutex]. Both recordTurn()
 * and computePressure() acquire the lock to prevent concurrent modification of
 * growthHistory. Since recordTurn() is called from computeSessionContext()
 * (coroutine dispatcher) and computePressure() may be called from the UI thread,
 * the mutex prevents ConcurrentModificationException and data corruption.
 *
 * @param clock injectable clock for testing (defaults to wall-clock millis)
 */
class ContextPressureMonitor(
    val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    /** Rolling window of (timestampMs, growth) pairs for per-turn growth. */
    private val growthHistory = mutableListOf<Pair<Long, Double>>()
    private var lastInputTokens: Long = 0

    /**
     * Record a new cumulative input token count after an assistant response completes.
     *
     * @param inputTokens CUMULATIVE prompt size from the last assistant message's
     *   inputTokens field (total tokens in the full context window at this point).
     *   NOT incremental — the delta is computed internally by subtracting the
     *   previous recording.
     * @param timestampMs Wall-clock time in epoch millis for burn-rate computation.
     */
    suspend fun recordTurn(inputTokens: Long, timestampMs: Long): Unit = mutex.withLock {
        if (lastInputTokens > 0) {
            val delta = (inputTokens - lastInputTokens).coerceAtLeast(0)
            growthHistory.add(timestampMs to delta.toDouble())
            // Trim to rolling window
            while (growthHistory.size > CompactionConstants.PRESSURE_WINDOW_SIZE) {
                growthHistory.removeAt(0)
            }
        }
        lastInputTokens = inputTokens
    }

    /**
     * Reset growth history after a compaction event. When the server compacts,
     * inputTokens drops significantly (e.g., 150K → 40K), which would produce a
     * large negative delta that corrupts the rolling average. Calling this after
     * compaction resets the monitor to start fresh from the new context state.
     *
     * Called from SessionManager when handling session.compacted SSE events.
     */
    suspend fun onCompaction(): Unit = mutex.withLock {
        growthHistory.clear()
        lastInputTokens = 0
    }

    /**
     * Compute current context pressure from accumulated growth data.
     * Returns null if not enough data points (< [CompactionConstants.PRESSURE_MIN_TURNS]).
     */
    suspend fun computePressure(currentTokens: Long, contextLimit: Long): ContextPressure? = mutex.withLock {
        if (growthHistory.size < CompactionConstants.PRESSURE_MIN_TURNS) return@withLock null

        val usagePercent = if (contextLimit > 0L) {
            (currentTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        // Rolling average growth per turn
        val growthPerTurn = growthHistory.map { it.second }.average()

        // Burn rate: tokens per minute (wall-clock)
        // Use the time span from the first entry in the window to the last entry,
        // which gives us the actual time window covered by the rolling data.
        val firstTimestampMs = growthHistory.firstOrNull()?.first ?: 0L
        val lastTimestampMs = growthHistory.lastOrNull()?.first ?: 0L
        val timeSpanMs = lastTimestampMs - firstTimestampMs
        val burnRatePerMinute = if (timeSpanMs > 0) {
            val totalGrowth = growthHistory.sumOf { it.second }
            val minutes = (timeSpanMs.toDouble() / 60_000.0).coerceAtLeast(0.001)
            totalGrowth / minutes
        } else 0.0

        // Turns until compact: how many turns until usage hits the auto-compact threshold (~85%)
        val turnsUntilCompact = if (contextLimit > 0L && growthPerTurn > 0) {
            val remainingTokens = (contextLimit * CompactionConstants.PRESSURE_CRITICAL_THRESHOLD).toLong() - currentTokens
            if (remainingTokens > 0) {
                (remainingTokens / growthPerTurn).toInt().coerceAtLeast(0)
            } else 0
        } else null

        val usageFraction = if (contextLimit > 0L) currentTokens.toDouble() / contextLimit else 0.0
        val pressureLevel = when {
            usageFraction >= CompactionConstants.PRESSURE_CRITICAL_THRESHOLD -> PressureLevel.CRITICAL
            usageFraction >= CompactionConstants.PRESSURE_HIGH_THRESHOLD -> PressureLevel.HIGH
            usageFraction >= CompactionConstants.PRESSURE_ELEVATED_THRESHOLD -> PressureLevel.ELEVATED
            else -> PressureLevel.COMFORTABLE
        }

        logger.debug {
            "[ACP] Pressure: $pressureLevel growth=${"%.0f".format(growthPerTurn)} tokens/turn " +
                "turnsUntilCompact=$turnsUntilCompact burn=${"%.0f".format(burnRatePerMinute)} tok/min"
        }

        ContextPressure(
            currentTokens = currentTokens,
            contextLimit = contextLimit,
            usagePercent = usagePercent,
            growthPerTurn = growthPerTurn,
            turnsUntilCompact = turnsUntilCompact,
            burnRatePerMinute = burnRatePerMinute,
            pressureLevel = pressureLevel,
        )
    }

    /** Reset on session switch. */
    suspend fun reset(): Unit = mutex.withLock {
        growthHistory.clear()
        lastInputTokens = 0
    }
}