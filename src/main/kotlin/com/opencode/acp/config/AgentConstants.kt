package com.opencode.acp.config

/**
 * Constants for custom agent configuration.
 */
object AgentConstants {
    const val CODING_ASSISTANT_AGENT_NAME = "coding-assistant"
    const val COUNCIL_AGENT_NAME = "council"
    const val AGENTS_DIR = ".opencode/agents"
    const val OWNERSHIP_MARKER = "<!-- sigil-managed -->"

    /**
     * Shared regex for YAML-safe identifiers (agent names, tool names, council member fields).
     * Alphanumeric, hyphen, underscore, dot. Max 128 chars. NO slash (slash is the
     * delimiter in council promptString) and NO other YAML-special chars.
     * Used by [com.opencode.acp.config.AgentConfigWriter.buildTaskPermissionYaml] and
     * [com.opencode.acp.config.settings.CouncilMember] to prevent YAML injection (CWE-94).
     * Keep these two references in sync via this shared constant (DRY).
     */
    val YAML_SAFE_IDENTIFIER: Regex = Regex("^[A-Za-z0-9._\\-]{1,128}$")

    // Council subtask agents (built-in OpenCode agents)
    const val COUNCIL_MEMBER_SUBTASK_AGENT = "explore"
    const val COUNCIL_SYNTHESIS_SUBTASK_AGENT = "general"

    // Known leaked global agents (from developer machine global config).
    // Disabled via opencode.json overrides. This is a best-effort, environment-
    // specific denylist — it only matches agents from this developer's global
    // config and will not catch leaked agents on other machines. A future
    // improvement should detect leaked agents at runtime by comparing the
    // server's agent list against the plugin's known agents + built-ins.
    // TODO: Only disable agents that actually exist in the server's agent list.
    val KNOWN_LEAKED_AGENTS = listOf(
        "adversarial-deepseek-v4-pro",
        "adversarial-deepseek-v4",
        "adversarial-glm-5.1",
        "adversarial-kimi-k2.6",
        "adversarial-kimi-k2.7-code",
        "adversarial-mimo-v2.5-pro",
        "adversarial-minimax-m2.7",
        "adversarial-minimax-m3"
    )

    // INFORMATIONAL ONLY — not used by production code. Per-tool permissions now
    // come from opencode.json (written by McpConfigWriter.writeToolPermissions),
    // not from the agent frontmatter. Retained for test assertions and as a
    // reference list of the IntelliJ MCP tool surface. Do NOT add new tool
    // permissions to agent frontmatter based on this list.
    val INTELLIJ_TOOL_NAMES = listOf(
        "intellij_read_file",
        "intellij_search_symbol",
        "intellij_search_text",
        "intellij_search_regex",
        "intellij_list_directory_tree",
        "intellij_get_symbol_info",
        "intellij_analyze_calls",
        "intellij_psi_find_symbol",
        "intellij_psi_find_references",
        "intellij_psi_call_hierarchy",
        "intellij_psi_file_structure",
        "intellij_psi_impact_analysis",
        "intellij_psi_repo_map",
        "intellij_get_file_problems",
        "intellij_lint_files",
        "intellij_reformat_file",
        "intellij_rename_refactoring",
        "intellij_create_new_file",
        "intellij_open_file_in_editor",
        "intellij_get_run_configurations",
        "intellij_build_project",
        "intellij_get_project_modules",
        "intellij_get_project_dependencies",
        "intellij_get_all_open_file_paths",
        "intellij_get_repositories",
        "intellij_git_status",
        "intellij_execute_terminal_command",
        "intellij_xdebug_start_debugger_session",
        "intellij_xdebug_control_session",
        "intellij_xdebug_get_stack",
        "intellij_xdebug_get_frame_values",
        "intellij_xdebug_evaluate_expression",
        "intellij_xdebug_set_breakpoint",
        "intellij_xdebug_remove_breakpoint",
        "intellij_xdebug_run_to_line",
        "intellij_xdebug_get_threads",
        "intellij_xdebug_get_debugger_status",
        "intellij_xdebug_set_variable",
        "intellij_xdebug_get_value_by_path",
        "intellij_xdebug_list_breakpoints",
        "intellij_execute_sql_query",
        "intellij_list_database_connections",
        "intellij_list_database_schemas",
        "intellij_list_schema_objects",
        "intellij_preview_table_data",
        "intellij_get_database_object_description",
        "intellij_test_database_connection",
        "intellij_find_lock_requirements_usages",
        "intellij_find_threading_requirements_usages",
        "intellij_execute_tool",
        "intellij_skill_search"
    )

    // INFORMATIONAL ONLY — not used by any production or test code (dead code).
    // Retained as a reference list of generic tool names. Per-tool permissions
    // now come from opencode.json, not from the agent frontmatter. Safe to
    // remove if no longer needed for documentation.
    val GENERIC_TOOL_NAMES = listOf(
        "read",
        "edit",
        "write",
        "bash",
        "apply_patch",
        "glob",
        "grep",
        "list",
        "webfetch",
        "websearch",
        "skill",
        "question",
        "todowrite",
        "build_project",
        "execute_run_configuration",
        "execute_terminal_command",
        "wait_for_user",
        "cancel_task"
    )
}