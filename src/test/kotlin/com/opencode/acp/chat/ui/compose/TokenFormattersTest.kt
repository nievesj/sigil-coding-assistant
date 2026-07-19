package com.opencode.acp.chat.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for [TokenFormatters] (TDD §8.2 — TokenFormattersTest).
 *
 * Verifies the consolidated token/cost/percent formatting and the locale
 * consistency fix: all formatting uses [Locale.US] to ensure `.` decimal
 * separator regardless of system locale (fixes the latent bug in the
 * original [ContextIndicator.formatTooltipTokens]/[formatTooltipCost] which
 * used `String.format` without an explicit locale).
 */
class TokenFormattersTest {

    // ── formatTokens ─────────────────────────────────────────────────────────

    @Test
    fun `formatTokens zero returns 0`() {
        TokenFormatters.formatTokens(0L) shouldBe "0"
    }

    @Test
    fun `formatTokens one returns 1`() {
        TokenFormatters.formatTokens(1L) shouldBe "1"
    }

    @Test
    fun `formatTokens small number returns plain`() {
        TokenFormatters.formatTokens(42L) shouldBe "42"
    }

    @Test
    fun `formatTokens just under 1000 returns plain`() {
        TokenFormatters.formatTokens(999L) shouldBe "999"
    }

    @Test
    fun `formatTokens exactly 1000 returns 1_0k`() {
        TokenFormatters.formatTokens(1000L) shouldBe "1.0k"
    }

    @Test
    fun `formatTokens 1200 returns 1_2k`() {
        TokenFormatters.formatTokens(1200L) shouldBe "1.2k"
    }

    @Test
    fun `formatTokens 15000 returns 15_0k`() {
        TokenFormatters.formatTokens(15000L) shouldBe "15.0k"
    }

    @Test
    fun `formatTokens just under 1M returns k suffix`() {
        // 999999 / 1000.0 = 999.999 → "%.1f" = "1000.0k"
        TokenFormatters.formatTokens(999999L) shouldBe "1000.0k"
    }

    @Test
    fun `formatTokens exactly 1M returns 1_0M`() {
        TokenFormatters.formatTokens(1_000_000L) shouldBe "1.0M"
    }

    @Test
    fun `formatTokens 1_2M returns 1_2M`() {
        TokenFormatters.formatTokens(1_200_000L) shouldBe "1.2M"
    }

    @Test
    fun `formatTokens 3_4M returns 3_4M`() {
        TokenFormatters.formatTokens(3_400_000L) shouldBe "3.4M"
    }

    @Test
    fun `formatTokens 10M returns 10_0M`() {
        TokenFormatters.formatTokens(10_000_000L) shouldBe "10.0M"
    }

    @Test
    fun `formatTokens Long MAX_VALUE does not throw and produces M suffix`() {
        val result = TokenFormatters.formatTokens(Long.MAX_VALUE)
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("M"), "expected M suffix in: $result")
    }

    // ── formatCost ───────────────────────────────────────────────────────────

    @Test
    fun `formatCost zero returns dollar 0_00`() {
        TokenFormatters.formatCost(0.0) shouldBe "$0.00"
    }

    @Test
    fun `formatCost small fraction returns 4 decimals`() {
        TokenFormatters.formatCost(0.01) shouldBe "$0.0100"
    }

    @Test
    fun `formatCost whole dollar returns 4 decimals`() {
        TokenFormatters.formatCost(1.0) shouldBe "$1.0000"
    }

    @Test
    fun `formatCost 1_2345 returns 4 decimals`() {
        TokenFormatters.formatCost(1.2345) shouldBe "$1.2345"
    }

    @Test
    fun `formatCost 99_99 returns 4 decimals`() {
        TokenFormatters.formatCost(99.99) shouldBe "$99.9900"
    }

    @Test
    fun `formatCost 1000 returns 4 decimals`() {
        TokenFormatters.formatCost(1000.0) shouldBe "$1000.0000"
    }

    @Test
    fun `formatCost negative does not crash`() {
        // Negative cost is unusual but shouldn't throw.
        val result = TokenFormatters.formatCost(-1.0)
        org.junit.jupiter.api.Assertions.assertTrue(result.startsWith("$"), "expected $ prefix in: $result")
    }

    // ── formatPercent ────────────────────────────────────────────────────────

    @Test
    fun `formatPercent zero returns 0 percent`() {
        TokenFormatters.formatPercent(0f) shouldBe "0%"
    }

    @Test
    fun `formatPercent 50 returns 50 percent`() {
        TokenFormatters.formatPercent(50f) shouldBe "50%"
    }

    @Test
    fun `formatPercent 85 returns 85 percent`() {
        TokenFormatters.formatPercent(85f) shouldBe "85%"
    }

    @Test
    fun `formatPercent 100 returns 100 percent`() {
        TokenFormatters.formatPercent(100f) shouldBe "100%"
    }

    @Test
    fun `formatPercent 85_3 returns 85_3 percent`() {
        TokenFormatters.formatPercent(85.3f) shouldBe "85.3%"
    }

    @Test
    fun `formatPercent 33_33 returns 33_3 percent`() {
        TokenFormatters.formatPercent(33.33f) shouldBe "33.3%"
    }

    @Test
    fun `formatPercent 0_5 returns 0_5 percent`() {
        TokenFormatters.formatPercent(0.5f) shouldBe "0.5%"
    }

    // ── Locale consistency (the bug fix) ─────────────────────────────────────

    @Test
    fun `formatTokens uses US locale for decimal separator`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // German uses ',' as decimal separator
            TokenFormatters.formatTokens(1500L) shouldBe "1.5k" // NOT "1,5k"
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `formatCost uses US locale for decimal separator`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TokenFormatters.formatCost(1.5) shouldBe "$1.5000" // NOT "$1,5000"
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `formatPercent uses US locale for decimal separator`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TokenFormatters.formatPercent(85.3f) shouldBe "85.3%" // NOT "85,3%"
        } finally {
            Locale.setDefault(original)
        }
    }
}