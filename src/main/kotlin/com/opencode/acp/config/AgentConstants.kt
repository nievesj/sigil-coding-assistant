package com.opencode.acp.config

/**
 * Constants for custom agent configuration.
 */
object AgentConstants {
    const val CODING_ASSISTANT_AGENT_NAME = "coding-assistant"
    const val COUNCIL_AGENT_NAME = "council"

    // ── v2 new agent names (TDD custom-agents-v2 §4.7.5) ──
    const val CODER_AGENT_NAME = "coder"
    const val RESEARCHER_AGENT_NAME = "researcher"
    const val PLANNER_AGENT_NAME = "planner"
    const val TESTER_AGENT_NAME = "tester"
    const val REVIEWER_AGENT_NAME = "reviewer"

    /**
     * All plugin-defined v2 subagent names (excludes `coding-assistant`
     * primary and the v1 `council` subagent — those have special handling).
     * Iterated by [com.opencode.acp.config.AgentConfigWriter.isEnabled] and
     * [com.opencode.acp.config.settings.OpenCodeAgentConfigurable].
     */
    val V2_SUBAGENT_NAMES: List<String> = listOf(
        CODER_AGENT_NAME,
        RESEARCHER_AGENT_NAME,
        PLANNER_AGENT_NAME,
        TESTER_AGENT_NAME,
        REVIEWER_AGENT_NAME,
    )

    /**
     * Per-agent frontmatter defaults (HARDCODED constants written into the
     * agent file frontmatter — NOT user-configurable in the settings UI).
     *
     * See `docs/tdd/custom-agents-v2.md` §4.7.1D and §4.7.5.
     *
     * Temperature/steps UI configurability is a v2.1 follow-up. To change
     * these values, edit the constants and regenerate agent files (toggle a
     * setting and Apply, or restart the IDE).
     *
     * - `coder` / `tester`: low temperature (0.2) for deterministic codegen;
     *   `steps: 25` cost guardrail prevents runaway loops on hard chunks.
     * - `researcher`: slightly higher temperature (0.3) for exploratory
     *   analysis; no steps cap (research is open-ended).
     * - `planner`: moderate temperature (0.4) for balanced decomposition; no
     *   steps cap (planning is iterative).
     * - `reviewer`: low temperature (0.2) for rigorous deterministic analysis;
     *   steps: 50 guardrail (reviews are file-read-heavy — higher than
     *   coder/tester's 25).
     *
     * When `coder`/`tester` hits the steps limit, OpenCode stops the agentic
     * loop and returns whatever the agent has produced so far (partial result).
     * The parent agent's prompt instructs it to handle partial results (fix
     * yourself / retry / flag the gap).
     */
    const val CODER_DEFAULT_TEMPERATURE = 0.2
    const val CODER_DEFAULT_STEPS: Int = 25
    const val RESEARCHER_DEFAULT_TEMPERATURE = 0.3
    const val PLANNER_DEFAULT_TEMPERATURE = 0.4
    const val TESTER_DEFAULT_TEMPERATURE = 0.2
    const val TESTER_DEFAULT_STEPS: Int = 25
    const val REVIEWER_DEFAULT_TEMPERATURE = 0.2
    const val REVIEWER_DEFAULT_STEPS: Int = 50

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
    // WARNING: This list is environment-specific and must NOT be shipped to all
    // users. Passing it to McpConfigWriter.writeAgentOverrides() writes
    // `disable: true` for these names into EVERY user .opencode/opencode.json,
    // silently disabling any user-defined agent that shares a name (e.g. a user
    // who legitimately defines `adversarial-deepseek-v4`). The plugin has no
    // authority to disable agents it did not create -- these come from the
    // user global ~/.config/opencode, not the plugin server instance.
    //
    // Production callers MUST pass `emptyList()` to writeAgentOverrides. The
    // list is retained here only as reference for the future runtime detection
    // fix (gate each disable on a GET /agent check). See AgentConfigWriter.writeAll.
    // TODO: Runtime-detect leaked agents via GET /agent before disabling.
    val KNOWN_LEAKED_AGENTS = listOf(
        // Names redacted to placeholders - the specific model identifiers
        // revealed the developer's personal agent configuration (informational
        // exposure with no value as dead reference). The structure
        // (adversarial-<family>-<variant>) is preserved as a reference for the
        // future runtime-detection fix. Placeholders use only YAML_SAFE_IDENTIFIER
        // chars (alphanumeric + hyphen) so they remain consistent with the
        // validation pattern used elsewhere. See the TODO above.
        "adversarial-model-a-v1-pro",
        "adversarial-model-a-v1",
        "adversarial-model-b-v2",
        "adversarial-model-c-v3-code",
        "adversarial-model-d-v4-pro",
        "adversarial-model-e-v5"
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
}
