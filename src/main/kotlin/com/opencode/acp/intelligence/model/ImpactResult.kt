package com.opencode.acp.intelligence.model

/**
 * Risk level for impact analysis, computed by [com.opencode.acp.intelligence.RiskScorer].
 *
 * - [UNKNOWN]: partial results — risk cannot be determined (timeout/budget exceeded).
* - [LOW]: < 5 affected symbols.
 * - [MEDIUM]: 5–20 affected symbols (inclusive at 20).
 * - [HIGH]: 21–50 affected symbols (inclusive at 50).
* - [CRITICAL]: > 50 affected symbols or touches public API.
 */
enum class RiskLevel {
    UNKNOWN, LOW, MEDIUM, HIGH, CRITICAL,
}

/**
 * A symbol affected by a change to the target symbol, returned by `psi_impact_analysis`.
 *
 * @param name The affected symbol's name.
 * @param kind The symbol kind.
 * @param file Project-relative file path.
 * @param line 1-based line number.
 * @param depth 1 = direct reference, 2 = transitive, etc.
 * @param relationship How the symbol relates to the target: "calls", "overrides",
 *   "implements", "references", "inherits".
 */
data class AffectedSymbol(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val depth: Int,
    val relationship: String,
)

/**
 * Result of `psi_impact_analysis` — the blast radius of changing a symbol.
 *
 * @param symbol The target symbol name.
 * @param affectedFiles Unique list of files containing affected symbols.
 * @param affectedSymbols All affected symbols (direct + transitive).
 * @param riskLevel Computed risk level (UNKNOWN if partial results).
 * @param summary Human-readable summary.
 * @param totalAffected Total count of affected symbols (may exceed affectedSymbols.size if truncated).
 */
data class ImpactResult(
    val symbol: String,
    val affectedFiles: List<String>,
    val affectedSymbols: List<AffectedSymbol>,
    val riskLevel: RiskLevel,
    val summary: String,
    val totalAffected: Int,
)