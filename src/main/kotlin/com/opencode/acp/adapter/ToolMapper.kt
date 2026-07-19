package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonObject

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
    * - read -> READ
    * - remove, delete -> DELETE
    * - move, rename -> MOVE
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
        "read" -> ToolKind.READ
        "remove", "delete" -> ToolKind.DELETE
       "move", "rename" -> ToolKind.MOVE
       "grep", "glob", "find" -> ToolKind.SEARCH
       "websearch", "webfetch" -> ToolKind.FETCH
       "question" -> ToolKind.THINK
       "lsp" -> ToolKind.READ
       "skill", "todowrite", "task", "external_directory" -> ToolKind.OTHER
       else -> ToolKind.OTHER
   }

    /**
     * Detects tool kind from input JSON when the tool name is generic ("tool" or unknown).
     * Checks for characteristic input keys:
     * - `command` → EXECUTE
     * - `old_string`/`new_string` + `file_path` → EDIT
     * - `file_path` only → READ
     * - `pattern`/`query` → SEARCH
     */
    fun detectKindFromInput(input: JsonObject?): ToolKind {
        if (input == null || input.isEmpty()) return ToolKind.OTHER
        val hasFilePath = input.containsKey("file_path") || input.containsKey("filePath") || input.containsKey("path")
        // Priority order: EXECUTE > SEARCH > EDIT > READ > OTHER.
        // EXECUTE (command) is checked first because a tool with a 'command' field is
        // unambiguously a shell/execute tool, even if it also has file_path or pattern.
        // SEARCH is checked before EDIT/READ because search tools often carry a 'path'
        // parameter (directory scope) that would otherwise match READ, and some use
        // 'content' for search text which would match EDIT.
        return when {
            input.containsKey("command") -> ToolKind.EXECUTE
            input.containsKey("pattern") || input.containsKey("query") -> ToolKind.SEARCH
            input.containsKey("glob") || input.containsKey("include") -> ToolKind.SEARCH
            // Edit: has file_path AND (content or old_string/new_string or old/new)
            hasFilePath && (
                input.containsKey("content") ||
                input.containsKey("old_string") || input.containsKey("new_string") ||
                input.containsKey("old") || input.containsKey("new")
            ) -> ToolKind.EDIT
            hasFilePath -> ToolKind.READ
            else -> ToolKind.OTHER
        }
    }

    /**
     * Returns all known OpenCode tool names grouped by their [ToolKind].
     */
   fun allMappings(): Map<ToolKind, List<String>> = mapOf(
       ToolKind.EXECUTE to listOf("bash", "shell"),
       ToolKind.EDIT to listOf("edit", "apply_patch", "write"),
       ToolKind.READ to listOf("read", "lsp"),
       ToolKind.DELETE to listOf("remove", "delete"),
       ToolKind.MOVE to listOf("move", "rename"),
       ToolKind.SEARCH to listOf("grep", "glob", "find"),
       ToolKind.FETCH to listOf("websearch", "webfetch"),
       ToolKind.THINK to listOf("question"),
       ToolKind.OTHER to listOf("skill", "todowrite", "task", "external_directory")
   )
}
