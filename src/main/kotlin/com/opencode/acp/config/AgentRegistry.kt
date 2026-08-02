package com.opencode.acp.config

/**
 * The ordered list of all plugin-defined agents.
 *
 * Single source of truth iterated by [AgentConfigWriter.writeAll] and
 * [com.opencode.acp.config.settings.OpenCodeAgentConfigurable]. Adding a new
 * agent = add one entry here + a prompt/frontmatter builder in
 * [AgentConfigWriter]. No new writer methods, no new checkboxes, no new
 * settings-field wiring.
 *
 * Order matters for the settings UI display order.
 *
 * See `docs/tdd/custom-agents-v2.md` §4.7.2A.
 */
object AgentRegistry {

    /**
     * The ordered list of all plugin-defined agents (v1 + v2).
     *
     * v1: `coding-assistant` (primary), `council` (subagent).
     * v2: `coder`, `researcher`, `planner`, `tester` (all subagents, all
     * opt-in / default OFF).
     */
    val ALL_AGENTS: List<AgentDefinition> = listOf(
        AgentDefinition(
            name = AgentConstants.CODING_ASSISTANT_AGENT_NAME,
            mode = "primary",
            defaultEnabled = true,
            hidden = false,
            hasPerAgentModel = false, // primary uses the chat's active model
            description = "Coding assistant optimized for IntelliJ-based development with hands-on codebase access and IDE intelligence tools.",
            promptBuilder = { ctx -> AgentConfigWriter.buildCodingAssistantPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildCodingAssistantFrontmatter(ctx) },
        ),
        AgentDefinition(
            name = AgentConstants.COUNCIL_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = false, // council has its own per-MEMBER model list (not per-agent)
            alwaysOverwrite = true, // council content is dynamic (member list changes) — always overwrite
            description = "Multi-model council coordinator that fans out review subtasks to configured models and synthesizes a consensus report.",
            promptBuilder = { ctx -> AgentConfigWriter.buildCouncilPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildCouncilFrontmatter(ctx) },
        ),
        AgentDefinition(
            name = AgentConstants.CODER_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = true,
            description = "Scoped implementation subagent. Takes one file-scoped chunk, researches target files, edits, self-verifies, and returns a structured result. The parallelism unit for fan-out implementation.",
            promptBuilder = { ctx -> AgentConfigWriter.buildCoderPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildCoderFrontmatter(ctx) },
        ),
        AgentDefinition(
            name = AgentConstants.RESEARCHER_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = true,
            description = "Semantic codebase investigator. Uses IntelliJ PSI tools to produce structured context briefs (symbols, call graphs, affected files, gotchas). Read-only — does not edit.",
            promptBuilder = { ctx -> AgentConfigWriter.buildResearcherPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildResearcherFrontmatter(ctx) },
        ),
        AgentDefinition(
            name = AgentConstants.PLANNER_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = true,
            description = "Task decomposer. Takes a feature/task and produces a chunk plan with file assignments and cross-chunk contracts. The safety layer for parallel coder fan-out.",
            promptBuilder = { ctx -> AgentConfigWriter.buildPlannerPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildPlannerFrontmatter(ctx) },
        ),
        AgentDefinition(
            name = AgentConstants.TESTER_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = true,
            description = "Scoped test implementer. Takes one test-file-scoped chunk, reads existing tests for patterns, writes tests, self-verifies, and returns a structured result.",
            promptBuilder = { ctx -> AgentConfigWriter.buildTesterPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildTesterFrontmatter(ctx) },
        ),
    )

    /** All agent names (for the taskAllowedAgents UI + validation). */
    val ALL_NAMES: List<String> = ALL_AGENTS.map { it.name }

    /**
     * Lookup by name. Throws [IllegalStateException] with a descriptive message if
     * absent - the registry is static, so a missing name indicates a bug (typo in
     * a [AgentConstants] constant or a future caller passing user-controlled input).
     *
     * **Call only with [AgentConstants] literals.** Do NOT pass settings-derived
     * or user-controlled names to this method - use [byNameOrNull] for those. The
     * thrown [IllegalStateException] is appropriate for plugin-internal callers
     * using compile-time constants but propagates as an uncaught plugin error if
     * a settings-derived name reaches this path.
     */
    fun byName(name: String): AgentDefinition =
        ALL_AGENTS.firstOrNull { it.name == name }
            ?: error("AgentRegistry.byName: no agent named '$name' in registry (known: ${ALL_NAMES})")

    /**
     * Lookup by name, returning `null` when absent (defensive variant
     * of [byName]). Use this when the caller cannot guarantee the
     * name is a known [AgentConstants] constant (e.g. iterating a
     * user-edited list) -- [byName] throws [IllegalStateException] on
     * an unknown name, which is appropriate for plugin-internal
     * callers using constants but unsafe for settings-derived names.
     */
    fun byNameOrNull(name: String): AgentDefinition? =
        ALL_AGENTS.firstOrNull { it.name == name }
}
