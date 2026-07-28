package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI reference search as `psi_find_references`.
 */
class FindUsagesToolset : McpToolset {

    @McpTool(name = "psi_find_references")
    @McpDescription("Find all references to a symbol (function, class, field). Returns file, line, enclosing symbol, and reference text.")
    suspend fun findReferences(
        @McpDescription("Symbol name (function, class, field)")
        symbol: String,
        @McpDescription("File path to disambiguate when multiple symbols share a name")
        file: String? = null,
        @McpDescription("Search scope (default 'project')")
        scope: String = "project",
        @McpDescription("Max results (default 200, max 500)")
        limit: Int = 200,
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runFindReferences(symbol, file, scope, limit)
    }
}