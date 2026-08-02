package com.opencode.acp.config

import com.opencode.acp.config.settings.CouncilMember
import com.opencode.acp.config.settings.OpenCodeAgentSettingsState

/**
 * Static description of one plugin-defined agent.
 *
 * Iterated by [AgentConfigWriter.writeAll] and
 * [com.opencode.acp.config.settings.OpenCodeAgentConfigurable] to avoid
 * hardcoded per-agent methods/checkboxes (the v2 refactor of v1's hardcoded
 * approach — see `docs/tdd/custom-agents-v2.md` §4.7.1A).
 *
 * Adding a new agent = add one [AgentRegistry.ALL_AGENTS] entry + a prompt
 * constant. No new writer methods, no new settings checkboxes, no new UI
 * wiring.
 *
 * @property name agent file name (kebab-case, matches [AgentConstants.YAML_SAFE_IDENTIFIER])
 * @property mode "primary" or "subagent"
 * @property defaultEnabled default enable state on fresh install
 * @property hidden frontmatter `hidden` flag (true = invoke only via `task`;
 *   false = `@agent` direct too). All v2 subagents are `hidden=false` so users
 *   can `@coder`/`@planner`/`@researcher` directly when desired.
 * @property hasPerAgentModel whether this agent has a per-agent model setting
 *   in the UI (only subagents with a single model — `coding-assistant` and
 *   `council` do NOT, since they use the chat's active model / per-member list
 *   respectively).
 * @property alwaysOverwrite if true, skip the ownership-marker check (always
 *   overwrite the file). Used for agents whose content is dynamic (e.g.,
 *   `council` — member list changes).
 * @property description agent description shown in the OpenCode agent list
 *   and as the settings-UI tooltip.
 * @property promptBuilder builds the prompt body (parameterized by settings
 *   via [AgentPromptContext]).
 * @property frontmatterBuilder builds the YAML frontmatter (parameterized by
 *   settings + per-agent model via [AgentFrontmatterContext]).
 *
 * LAMBDA EQUALITY CAVEAT: this is a `data class` with two lambda
 * properties ([promptBuilder], [frontmatterBuilder]). Data-class
 * `equals`/`hashCode`/`copy` use REFERENCE equality for lambdas, so two
 * `AgentDefinition` instances built with structurally-identical-but-distinct
 * lambdas are NOT equal, and `copy()` shares lambda references with the
 * original. Do NOT use `AgentDefinition` in `Set` contexts, as a `Map` key,
 * or rely on `==` for registry membership -- use [AgentRegistry.byName]
 * (which matches by `name`, the natural key) instead.
 *
 * HIDDEN FIELD: the [hidden] property is currently `false` for every
 * registry entry (no agent uses `hidden: true`). It is retained for
 * forward-compatibility (a future agent may need `hidden: true` to
 * restrict invocation to `@task` only). [AgentConfigWriter.buildFrontmatter]
 * emits `hidden:` only when the value is non-null, so a future `null`
 * entry would omit the key cleanly.
 */
data class AgentDefinition(
    val name: String,
    val mode: String,
    val defaultEnabled: Boolean,
    val hidden: Boolean,
    val hasPerAgentModel: Boolean,
    val alwaysOverwrite: Boolean = false,
    val description: String,
    val promptBuilder: (AgentPromptContext) -> String,
    val frontmatterBuilder: (AgentFrontmatterContext) -> String,
)

/**
 * Context passed to [AgentDefinition.frontmatterBuilder] lambdas.
 * Carries everything the builder needs to emit the YAML frontmatter.
 *
 * @property settings the live agent settings (enable flags + per-agent models)
 * @property isIntellijMcpEnabled whether IntelliJ MCP tools are available
 *   (passed through from [AgentConfigWriter.writeAll]; only affects the
 *   `coding-assistant` prompt's MCP-off degradation, but threaded through
 *   all builders for uniformity).
 * @property agentDef the [AgentDefinition] being rendered (self-reference for
 *   convenience; lets a builder reuse the same lambda for multiple agents).
 */
data class AgentFrontmatterContext(
    val settings: OpenCodeAgentSettingsState,
    val isIntellijMcpEnabled: Boolean,
    val agentDef: AgentDefinition,
) {
    /**
     * Convenience: the configured model for this agent, or `null` = inherit
     * parent's model. Returns null when the agent has no per-agent model
     * (`hasPerAgentModel == false`) or when no binding is configured.
     */
    val modelBinding: CouncilMember?
        get() = if (agentDef.hasPerAgentModel) settings.modelFor(agentDef.name) else null
}

/**
 * Context passed to [AgentDefinition.promptBuilder] lambdas.
 * Carries everything the builder needs to emit the prompt body.
 *
 * @property settings the live agent settings
 * @property isIntellijMcpEnabled whether IntelliJ MCP tools are available
 * @property agentDef the [AgentDefinition] being rendered
 */
data class AgentPromptContext(
    val settings: OpenCodeAgentSettingsState,
    val isIntellijMcpEnabled: Boolean,
    val agentDef: AgentDefinition,
)
