package com.opencode.acp.intelligence

import com.opencode.acp.intelligence.model.AffectedSymbol
import com.opencode.acp.intelligence.model.ImpactResult
import com.opencode.acp.intelligence.model.RiskLevel
import com.opencode.acp.intelligence.model.SymbolKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RiskScorer] — pure-logic risk scoring for impact analysis.
 *
 * Covers the threshold ladder (LOW/MEDIUM/HIGH/CRITICAL), the public-API
 * override, and the [RiskScorer.summarize] human-readable output.
 */
class RiskScorerTest {

    private fun affected(count: Int): List<AffectedSymbol> =
        (1..count).map { i ->
            AffectedSymbol(
                name = "sym$i",
                kind = SymbolKind.METHOD,
                file = "File.kt",
                line = i,
                depth = 1,
                relationship = "calls",
            )
        }

    @Test
    fun `scoreImpact returns LOW for 0 affected symbols`() {
        RiskScorer.scoreImpact(emptyList(), touchesPublicApi = false) shouldBe RiskLevel.LOW
    }

    @Test
    fun `scoreImpact returns LOW for 1 affected symbol`() {
        RiskScorer.scoreImpact(affected(1), touchesPublicApi = false) shouldBe RiskLevel.LOW
    }

    @Test
    fun `scoreImpact returns LOW for 4 affected symbols (below LOW threshold)`() {
        RiskScorer.scoreImpact(affected(4), touchesPublicApi = false) shouldBe RiskLevel.LOW
    }

    @Test
    fun `scoreImpact returns MEDIUM for 5 affected symbols (at LOW threshold)`() {
        RiskScorer.scoreImpact(affected(5), touchesPublicApi = false) shouldBe RiskLevel.MEDIUM
    }

    @Test
    fun `scoreImpact returns MEDIUM for 10 affected symbols`() {
        RiskScorer.scoreImpact(affected(10), touchesPublicApi = false) shouldBe RiskLevel.MEDIUM
    }

    @Test
    fun `scoreImpact returns MEDIUM for 20 affected symbols (below MEDIUM threshold)`() {
        RiskScorer.scoreImpact(affected(20), touchesPublicApi = false) shouldBe RiskLevel.MEDIUM
    }

    @Test
    fun `scoreImpact returns HIGH for 21 affected symbols (at MEDIUM threshold)`() {
        RiskScorer.scoreImpact(affected(21), touchesPublicApi = false) shouldBe RiskLevel.HIGH
    }

    @Test
    fun `scoreImpact returns HIGH for 30 affected symbols`() {
        RiskScorer.scoreImpact(affected(30), touchesPublicApi = false) shouldBe RiskLevel.HIGH
    }

    @Test
    fun `scoreImpact returns HIGH for 50 affected symbols (below HIGH threshold)`() {
        RiskScorer.scoreImpact(affected(50), touchesPublicApi = false) shouldBe RiskLevel.HIGH
    }

    @Test
    fun `scoreImpact returns CRITICAL for 51 affected symbols (at HIGH threshold)`() {
        RiskScorer.scoreImpact(affected(51), touchesPublicApi = false) shouldBe RiskLevel.CRITICAL
    }

    @Test
    fun `scoreImpact returns CRITICAL for 100 affected symbols`() {
        RiskScorer.scoreImpact(affected(100), touchesPublicApi = false) shouldBe RiskLevel.CRITICAL
    }

    @Test
    fun `scoreImpact returns CRITICAL when touchesPublicApi is true regardless of count`() {
        RiskScorer.scoreImpact(emptyList(), touchesPublicApi = true) shouldBe RiskLevel.CRITICAL
        RiskScorer.scoreImpact(affected(1), touchesPublicApi = true) shouldBe RiskLevel.CRITICAL
        RiskScorer.scoreImpact(affected(100), touchesPublicApi = true) shouldBe RiskLevel.CRITICAL
    }

    @Test
    fun `scoreImpact returns LOW when touchesPublicApi false and 0 symbols`() {
        RiskScorer.scoreImpact(emptyList(), touchesPublicApi = false) shouldBe RiskLevel.LOW
    }

    @Test
    fun `summarize produces string containing symbol name, count, and risk level`() {
        val result = ImpactResult(
            symbol = "processOrder",
            affectedFiles = listOf("A.kt", "B.kt", "C.kt"),
            affectedSymbols = affected(15),
            riskLevel = RiskLevel.HIGH,
            summary = "",
            totalAffected = 15,
        )
        val summary = RiskScorer.summarize(result)
        summary shouldContain "processOrder"
        summary shouldContain "15"
        summary shouldContain "HIGH"
        summary shouldContain "3"
    }

    @Test
    fun `summarize uses totalAffected not affectedSymbols size when truncated`() {
        val result = ImpactResult(
            symbol = "foo",
            affectedFiles = listOf("A.kt"),
            affectedSymbols = affected(10), // truncated list
            riskLevel = RiskLevel.MEDIUM,
            summary = "",
            totalAffected = 250, // true count exceeds list size
        )
        val summary = RiskScorer.summarize(result)
        summary shouldContain "250"
    }
}