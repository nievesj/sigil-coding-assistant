package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI repo map as `psi_repo_map`.
 *
 * The repo_map cache is keyed by [com.intellij.openapi.project.Project] (stored
 * in a project-level service) because toolset instances are application-scoped
 * singletons — a cache on the instance would leak across projects.
 */
class RepoMapToolset : McpToolset {

    @McpTool(name = "psi_repo_map")
    @McpDescription("Importance-ranked symbol index for the project. Returns symbols sorted by reference count. Samples top 500 class names alphabetically; not exhaustive.")
    suspend fun repoMap(
        @McpDescription("Max symbols to return (default 100, max 500)")
        limit: Int = 100,
        @McpDescription("Search scope (default 'project')")
        scope: String = "project",
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runRepoMap(limit, scope)
    }
}