package com.opencode.acp.intelligence.model

/**
 * An entry in the repo map, returned by `psi_repo_map`.
 *
 * @param name The symbol's simple name.
 * @param kind The symbol kind.
 * @param file Project-relative file path.
 * @param line 1-based line number of the symbol declaration.
 * @param referenceCount Number of references to this symbol (capped at 100 for sampling).
 * @param importance Log-scaled reference count (0.0–1.0). Formula:
 *   `if (maxCount > 0) ln(count + 1) / ln(maxCount + 1) else 0.0`.
 *   Symbols with 0 references get `importance = 0.0` directly (skip log-scaling
 *   to avoid log(0) = -∞).
 */
data class RepoMapEntry(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val referenceCount: Int,
    val importance: Double,
)