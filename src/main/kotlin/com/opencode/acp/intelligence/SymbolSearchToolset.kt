package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI symbol search as `psi_find_symbol`.
 *
 * Application-scoped singleton (the `McpToolset` EP is application-scoped).
 * The [com.intellij.openapi.project.Project] is obtained per-call from the
 * coroutine context via [currentCoroutineContext].projectOrNull.
 */
class SymbolSearchToolset : McpToolset {

    @McpTool(name = "psi_find_symbol")
    @McpDescription("Find symbols by name pattern across the project. Returns name, kind, file, line, and signature.")
    suspend fun findSymbol(
        @McpDescription("Symbol name or pattern (camelCase, substring, or exact name)")
        pattern: String,
        @McpDescription("Search scope: 'project' (default), 'module:<name>', or 'all'")
        scope: String = "project",
        @McpDescription("Max results (default 50, max 200)")
        limit: Int = 50,
        @McpDescription("Filter by symbol kind: 'class', 'method', 'field', 'function', 'property', 'interface', 'enum', 'object', 'annotation', 'constructor', 'package', 'type_alias', 'companion_object'. Omit for all kinds. Unknown values return empty results.")
        kind: String? = null,
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runSymbolSearch(pattern, scope, limit, kind)
    }
}