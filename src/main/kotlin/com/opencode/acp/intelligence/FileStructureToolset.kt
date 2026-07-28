package com.opencode.acp.intelligence

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP toolset exposing IntelliJ PSI file structure as `psi_file_structure`.
 */
class FileStructureToolset : McpToolset {

    @McpTool(name = "psi_file_structure")
    @McpDescription("Get file members with signatures (no bodies). Returns classes, fields, methods, and nested classes.")
    suspend fun fileStructure(
        @McpDescription("File path (project-relative or absolute)")
        file: String,
    ): String {
        val project = currentCoroutineContext().projectOrNull
            ?: return SymbolFormatter.formatError("No project open")
        val helper = PsiQueryHelper(project)
        return helper.runFileStructure(file)
    }
}