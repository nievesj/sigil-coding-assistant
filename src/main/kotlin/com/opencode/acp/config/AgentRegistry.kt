package com.opencode.acp.config

import com.opencode.acp.config.settings.OpenCodeAgentSettingsState

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
     * v2: `coder`, `researcher`, `planner`, `tester` (all opt-in / default OFF)
     * and `reviewer` (adversarial review subagent, default ON — advisory /
     * read-only: writes only .review/ findings).
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
            enableFlagGetter = { s -> s.enableCodingAssistant },
            enableFlagSetter = { s, v -> s.enableCodingAssistant = v },
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
            enableFlagGetter = { s -> s.enableCouncil },
            enableFlagSetter = { s, v -> s.enableCouncil = v },
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
            enableFlagGetter = { s -> s.enableCoder },
            enableFlagSetter = { s, v -> s.enableCoder = v },
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
            enableFlagGetter = { s -> s.enableResearcher },
            enableFlagSetter = { s, v -> s.enableResearcher = v },
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
            enableFlagGetter = { s -> s.enablePlanner },
            enableFlagSetter = { s, v -> s.enablePlanner = v },
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
            enableFlagGetter = { s -> s.enableTester },
            enableFlagSetter = { s, v -> s.enableTester = v },
        ),
        AgentDefinition(
            name = AgentConstants.REVIEWER_AGENT_NAME,
            mode = "subagent",
            defaultEnabled = true, // FIRST v2 subagent to default ON — reviewer is advisory/read-only (writes only .review/ findings)
            hidden = false,
            hasPerAgentModel = true,
            description = "Adversarial code reviewer. Reviews changed files for flaws (coding standards, patterns, SOLID/DRY, bugs, security, test gaps) and writes .review/<path>.json findings. Identifies issues — never fixes them.",
            promptBuilder = { ctx -> AgentConfigWriter.buildReviewerPrompt(ctx) },
            frontmatterBuilder = { ctx -> AgentConfigWriter.buildReviewerFrontmatter(ctx) },
            enableFlagGetter = { s -> s.enableReviewer },
            enableFlagSetter = { s, v -> s.enableReviewer = v },
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

    /**
     * Read the enable flag for [name] from [settings] via the centralized
     * [AgentDefinition.enableFlagGetter]. Returns `false` for unknown names
     * (defense-in-depth — only registry agents can be enabled). This replaces
     * the four parallel `when` expressions that previously mapped name→flag
     * across [AgentConfigWriter] and
     * [com.opencode.acp.config.settings.OpenCodeAgentConfigurable]; the
     * mapping now lives once on each [AgentDefinition] entry.
     */
    fun enableFlagFor(name: String, settings: OpenCodeAgentSettingsState): Boolean =
        byNameOrNull(name)?.enableFlagGetter?.invoke(settings) ?: false
}
