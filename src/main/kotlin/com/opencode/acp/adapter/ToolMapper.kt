package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolKind

/**
 * Maps OpenCode tool names to ACP [ToolKind] values.
 */
object ToolMapper {

    /**
     * Maps an OpenCode tool name string to the corresponding [ToolKind].
     *
     * Known mappings:
     * - bash, shell -> EXECUTE
     * - edit, apply_patch, write -> EDIT
     * - read, list -> READ
     * - grep, glob, find -> SEARCH
     * - websearch, webfetch -> FETCH
     * - question -> THINK
     * - lsp -> READ
     * - skill, todowrite, task, external_directory -> OTHER
     * - anything else -> OTHER
     */
    fun toAcpKind(toolName: String): ToolKind = when (toolName.lowercase()) {
        "bash", "shell" -> ToolKind.EXECUTE
        "edit", "apply_patch", "write" -> ToolKind.EDIT
        "read", "list" -> ToolKind.READ
        "grep", "glob", "find" -> ToolKind.SEARCH
        "websearch", "webfetch" -> ToolKind.FETCH
        "question" -> ToolKind.THINK
        "lsp" -> ToolKind.READ
        "skill", "todowrite", "task", "external_directory" -> ToolKind.OTHER
        else -> ToolKind.OTHER
    }

    /**
     * Returns all known OpenCode tool names grouped by their [ToolKind].
     */
    fun allMappings(): Map<ToolKind, List<String>> = mapOf(
        ToolKind.EXECUTE to listOf("bash", "shell"),
        ToolKind.EDIT to listOf("edit", "apply_patch", "write"),
        ToolKind.READ to listOf("read", "list", "lsp"),
        ToolKind.SEARCH to listOf("grep", "glob", "find"),
        ToolKind.FETCH to listOf("websearch", "webfetch"),
        ToolKind.THINK to listOf("question"),
        ToolKind.OTHER to listOf("skill", "todowrite", "task", "external_directory")
    )
}
