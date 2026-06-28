package com.opencode.acp.chat.model

/**
 * Constants for the smart compaction & context management feature.
 *
 * Single source of truth for pressure thresholds, background compaction defaults,
 * tool output truncation limits, and pressure monitor window sizes.
 */
object CompactionConstants {
    // ── Tool Output Truncation (opt-in, off by default) ──
    /** Default maximum characters for a single tool output when truncation is enabled. ~12.5K tokens. */
    const val DEFAULT_TOOL_OUTPUT_CHAR_LIMIT = 50_000

    // ── Context Pressure ──
    /** Rolling window size for growth rate computation (last N turns). */
    const val PRESSURE_WINDOW_SIZE = 20

    /** Minimum data points before pressure forecast is shown. */
    const val PRESSURE_MIN_TURNS = 3

    /**
     * Thresholds for pressure level color coding.
     * Single source of truth — do NOT duplicate elsewhere.
     */
    const val PRESSURE_CRITICAL_THRESHOLD = 0.85
    const val PRESSURE_HIGH_THRESHOLD = 0.70
    const val PRESSURE_ELEVATED_THRESHOLD = 0.50

    // ── Background Compaction (ON by default) ──
    /** Default checkpoint threshold — start background work at 60% context. */
    const val DEFAULT_CHECKPOINT_THRESHOLD_PERCENT = 60f

    /** Default swap threshold — use pre-computed summary at 80% context. */
    const val DEFAULT_SWAP_THRESHOLD_PERCENT = 80f

    /** Maximum age for a checkpoint before it's discarded (5 minutes). */
    const val MAX_CHECKPOINT_AGE_MS = 300_000L

    // ── Compact Command ──
    /** Timeout for manual compaction HTTP call. */
    const val COMPACT_TIMEOUT_MS = 120_000L  // 2 minutes

    // ── FileReadCache ──
    /** Maximum cache entries before LRU eviction kicks in. */
    const val FILE_READ_CACHE_MAX_ENTRIES = 500

    // ── BreakdownComputer ──
    /** Default char-to-token ratio for breakdown estimation. */
    const val DEFAULT_CHARS_PER_TOKEN = 4.0
}