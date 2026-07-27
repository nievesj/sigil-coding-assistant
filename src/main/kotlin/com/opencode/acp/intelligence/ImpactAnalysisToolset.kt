package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI impact analysis as `psi_impact_analysis`.
 */
class ImpactAnalysisToolset : McpToolset {

    @McpTool(name = "psi_impact_analysis")
    @McpDescription("Blast radius analysis: what breaks if a symbol changes. Returns affected files, symbols, risk level, and summary.")
    suspend fun impactAnalysis(
        @McpDescription("Symbol name (method, class, field)")
        symbol: String,
        @McpDescription("File path to disambiguate")
        file: String? = null,
        @McpDescription("Transitive depth (default 1, max 3)")
        depth: Int = 1,
        @McpDescription("Max affected items (default 100, max 300)")
        limit: Int = 100,
        @McpDescription("Search scope: 'project' (default), 'module:<name>', or 'all'")
        scope: String = "project",
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runImpactAnalysis(symbol, file, depth, limit, scope)
    }
}