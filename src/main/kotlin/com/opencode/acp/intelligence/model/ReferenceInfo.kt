package com.opencode.acp.intelligence.model

/**
 * A reference to a symbol, found by `psi_find_references`.
 *
 * @param file Project-relative file path of the reference site.
 * @param line 1-based line number of the reference.
 * @param column 1-based column number of the reference.
 * @param enclosingSymbol The method/class containing the reference (e.g., "MyClass.processOrder()"),
 *   or null if the reference is at top level.
 * @param text The trimmed source line containing the reference.
 */
data class ReferenceInfo(
    val file: String,
    val line: Int,
    val column: Int,
    val enclosingSymbol: String?,
    val text: String,
)