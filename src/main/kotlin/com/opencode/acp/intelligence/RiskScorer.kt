package com.opencode.acp.intelligence

import com.opencode.acp.intelligence.model.AffectedSymbol
import com.opencode.acp.intelligence.model.ImpactResult
import com.opencode.acp.intelligence.model.RiskLevel

/**
 * Pure-logic risk scorer for impact analysis results.
 *
 * Risk is derived from two inputs:
 *  - [touchesPublicApi]: a change to a public/protected symbol is always CRITICAL
 *    because it can break external consumers.
 *  - affected symbol count: blast radius by volume.
 *
 * Thresholds (from [IntelligenceConstants]):
 *  - < [RISK_LOW_THRESHOLD] (5)        → LOW
 *  - <= [RISK_MEDIUM_THRESHOLD] (20)   → MEDIUM
 *  - <= [RISK_HIGH_THRESHOLD] (50)     → HIGH
 *  - > 50                              → CRITICAL
 *
 * Pure-logic object — unit-testable without PSI.
 */
object RiskScorer {

    /**
     * Score the risk level of an impact analysis result.
     *
     * @param affectedSymbols the affected symbols (may be truncated; only the
     *   count is used for threshold comparison).
     * @param touchesPublicApi true if the target symbol is public/protected.
     * @return the computed [RiskLevel]. Empty affected lists with no public-API
     *   touch return LOW (not UNKNOWN — UNKNOWN is reserved for partial results
     *   from timeouts/budget exhaustion, set by the caller).
     */
    fun scoreImpact(affectedSymbols: List<AffectedSymbol>, touchesPublicApi: Boolean): RiskLevel {
        // Design note: returns CRITICAL even when there are 0 affected symbols.
        // A public API change with no local references may still break external
        // consumers (tests, other modules, downstream projects) that are outside
        // the search scope. Reserving CRITICAL for public API with actual blast
        // radius would require searching beyond the project scope, which is not
        // supported. This is intentionally conservative.
        //
        // Known limitation: if 80% of methods in a codebase are public (common in
        // library projects), then 80% of impact analyses return CRITICAL, which
        // degrades the signal value of the CRITICAL level for prioritization.
        // A future enhancement could introduce a PUBLIC_API level between HIGH
        // and CRITICAL to distinguish "public API with 0 local references" from
        // "public API with 100 local references". Deferred — the conservative
        // default is safer for now.
        if (touchesPublicApi) return RiskLevel.CRITICAL
        val count = affectedSymbols.size
        if (count == 0) return RiskLevel.LOW
       return when {
           count < RISK_LOW_THRESHOLD -> RiskLevel.LOW
            count <= RISK_MEDIUM_THRESHOLD -> RiskLevel.MEDIUM
            count <= RISK_HIGH_THRESHOLD -> RiskLevel.HIGH
           else -> RiskLevel.CRITICAL
       }
    }

    /**
     * Produce a human-readable summary of an [ImpactResult].
     *
     * Uses [ImpactResult.totalAffected] (not `affectedSymbols.size`) because the
     * affected-symbols list may be truncated to [DEFAULT_IMPACT_LIMIT] while
     * `totalAffected` reflects the true blast radius.
     */
    fun summarize(result: ImpactResult): String {
        return "Impact analysis for '${result.symbol}': ${result.totalAffected} affected symbols " +
            "across ${result.affectedFiles.size} files. Risk level: ${result.riskLevel.name}."
    }
}