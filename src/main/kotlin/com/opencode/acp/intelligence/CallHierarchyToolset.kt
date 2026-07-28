package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI call hierarchy as `psi_call_hierarchy`.
 */
class CallHierarchyToolset : McpToolset {

    @McpTool(name = "psi_call_hierarchy")
    @McpDescription("Get caller or callee tree for a method. Returns a tree of name, file, line, and children.")
    suspend fun callHierarchy(
        @McpDescription("Method name")
        symbol: String,
        @McpDescription("File path to disambiguate")
        file: String? = null,
        @McpDescription("Direction: 'callers' (default) or 'callees'")
        direction: String = "callers",
        @McpDescription("Traversal depth (default 2, max 4)")
        depth: Int = 2,
        @McpDescription("Max nodes per level (default 20, max 50)")
        limit: Int = 20,
        @McpDescription("Search scope: 'project' (default), 'module:<name>', or 'all'")
        scope: String = "project",
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runCallHierarchy(symbol, file, direction, depth, limit, scope)
    }
}