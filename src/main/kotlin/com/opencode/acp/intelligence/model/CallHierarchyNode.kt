package com.opencode.acp.intelligence.model

/**
 * A node in a call hierarchy tree, returned by `psi_call_hierarchy`.
 *
 * @param name The method name.
 * @param kind The symbol kind (typically METHOD or FUNCTION).
 * @param file Project-relative file path.
 * @param line 1-based line number of the method declaration.
 * @param children Child nodes (callers or callees depending on direction).
 */
data class CallHierarchyNode(
    val name: String,
    val kind: SymbolKind,
    val file: String,
    val line: Int,
    val children: List<CallHierarchyNode> = emptyList(),
)