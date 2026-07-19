package com.opencode.acp.chat.ui.compose

import java.util.Locale

/**
 * Consolidated token/cost/percent formatting.
 *
 * Fixes locale inconsistency: all formatting uses [Locale.US] to ensure
 * consistent `.` decimal separator regardless of system locale.
 *
 * Token formatting rules (union of all 4 existing sites):
 * - 0 → "0"
 * - < 1000 → plain number (e.g., "42")
 * - 1,000 to 999,999 → "X.Xk" (e.g., "1.2k", "42.5k")
 * - ≥ 1,000,000 → "X.XM" (e.g., "3.4M")
 *
 * Cost formatting rules:
 * - 0.0 → "$0.00"
 * - otherwise → "$X.XXXX" (4 decimal places, e.g., "$1.2345")
 *
 * Percent formatting:
 * - Whole numbers → "85%"
 * - Non-whole → "85.3%"
 */
object TokenFormatters {

    /**
     * Formats a token count into a compact human-readable string.
     *
     * Note: The pre-refactor SessionSidebar formatter used 0 decimals for values ≥ 10k
     * (e.g., '10k'). This consolidated formatter always uses 1 decimal (e.g., '10.0k')
     * for consistency across all call sites. This is an intentional precision improvement.
     */
    fun formatTokens(tokens: Long): String {
        return when {
            tokens == 0L -> "0"
            tokens < 1000L -> tokens.toString()
            tokens < 1_000_000L -> "${String.format(Locale.US, "%.1f", tokens / 1000.0)}k"
            else -> "${String.format(Locale.US, "%.1f", tokens / 1_000_000.0)}M"
        }
    }

    /**
     * Formats a cost value as a USD-prefixed string with 4 decimal places.
     *
     * Note: The pre-refactor SessionSidebar and ContextIndicator used 2 decimal places
     * (e.g., '$1.23'). This consolidated formatter uses 4 decimal places (e.g., '$1.2345')
     * for consistency with the MessageList step-finish display and to surface sub-cent
     * costs. This is an intentional precision improvement.
     */
    fun formatCost(cost: Double): String {
        return if (cost == 0.0) "$0.00" else "$${String.format(Locale.US, "%.4f", cost)}"
    }

    fun formatPercent(percent: Float): String {
        return if (percent == percent.toInt().toFloat()) {
            "${percent.toInt()}%"
        } else {
            "${String.format(Locale.US, "%.1f", percent)}%"
        }
    }
}