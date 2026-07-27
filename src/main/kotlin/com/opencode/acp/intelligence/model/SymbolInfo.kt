package com.opencode.acp.intelligence.model

/**
 * Kind of a PSI symbol, used across all tool results.
 *
 * Covers Java and Kotlin symbol kinds. Non-JVM languages map to the closest
 * equivalent (e.g., Python class → CLASS, Python function → FUNCTION).
 */
enum class SymbolKind {
    CLASS, INTERFACE, ENUM, OBJECT, METHOD, FUNCTION, FIELD, PROPERTY, ANNOTATION,
    CONSTRUCTOR, PARAMETER, PACKAGE, TYPE_ALIAS, COMPANION_OBJECT,
}

/**
 * A symbol found by `psi_find_symbol`.
 *
 * @param name The symbol's simple name.
 * @param kind The symbol kind (class, method, field, etc.).
 * @param file Project-relative file path.
 * @param line 1-based line number of the symbol declaration.
 * @param signature Method/class signature without body (null if not applicable).
 * @param qualifiedName Fully qualified name (only for classes when `kind=class`).
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val signature: String? = null,
    val qualifiedName: String? = null,
)