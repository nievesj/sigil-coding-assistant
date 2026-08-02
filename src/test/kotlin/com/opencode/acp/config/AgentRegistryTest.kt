package com.opencode.acp.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import com.opencode.acp.config.AgentFrontmatterContext
import com.opencode.acp.config.settings.OpenCodeAgentSettingsState
import com.opencode.acp.config.settings.CouncilMember
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [AgentRegistry] — the single source of truth iterated by
 * [AgentConfigWriter.writeAll] and
 * [com.opencode.acp.config.settings.OpenCodeAgentConfigurable].
 *
 * Verifies the registry contains all v1 + v2 agents with correct modes,
 * default enable states, hidden flags, and per-agent-model flags per
 * `docs/tdd/custom-agents-v2.md` §4.7.2A and §8.2.
 */
class AgentRegistryTest {

    @Test
    fun `ALL_AGENTS contains all 6 agents in registry order`() {
        AgentRegistry.ALL_AGENTS.map { it.name } shouldContainExactly listOf(
            AgentConstants.CODING_ASSISTANT_AGENT_NAME,
            AgentConstants.COUNCIL_AGENT_NAME,
            AgentConstants.CODER_AGENT_NAME,
            AgentConstants.RESEARCHER_AGENT_NAME,
            AgentConstants.PLANNER_AGENT_NAME,
            AgentConstants.TESTER_AGENT_NAME,
        )
    }

    @Test
    fun `ALL_NAMES matches ALL_AGENTS names`() {
        AgentRegistry.ALL_NAMES shouldContainExactly AgentRegistry.ALL_AGENTS.map { it.name }
    }

    @Test
    fun `byName returns the matching definition`() {
        for (def in AgentRegistry.ALL_AGENTS) {
            AgentRegistry.byName(def.name) shouldBe def
        }
    }

    @Test
    fun `coding-assistant is primary and default-enabled with no per-agent model`() {
        val def = AgentRegistry.byName(AgentConstants.CODING_ASSISTANT_AGENT_NAME)
        def.mode shouldBe "primary"
        def.defaultEnabled shouldBe true
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe false
        def.alwaysOverwrite shouldBe false
    }

    @Test
    fun `council is subagent, default-off, no per-agent model, always overwritten`() {
        val def = AgentRegistry.byName(AgentConstants.COUNCIL_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe false
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe false
        def.alwaysOverwrite shouldBe true
    }

    @Test
    fun `coder is subagent, default-off, has per-agent model, marker-gated overwrite`() {
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe false
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe true
        def.alwaysOverwrite shouldBe false
    }

    @Test
    fun `researcher is subagent, default-off, has per-agent model`() {
        val def = AgentRegistry.byName(AgentConstants.RESEARCHER_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe false
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe true
    }

    @Test
    fun `planner is subagent, default-off, has per-agent model`() {
        val def = AgentRegistry.byName(AgentConstants.PLANNER_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe false
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe true
    }

    @Test
    fun `tester is subagent, default-off, has per-agent model`() {
        val def = AgentRegistry.byName(AgentConstants.TESTER_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe false
        def.hidden shouldBe false
        def.hasPerAgentModel shouldBe true
    }

    @Test
    fun `all v2 subagent names are kebab-case and YAML-safe`() {
        for (name in AgentConstants.V2_SUBAGENT_NAMES) {
            AgentConstants.YAML_SAFE_IDENTIFIER.matches(name) shouldBe true
        }
    }

    @Test
    fun `all agent names in the registry are unique`() {
        val names = AgentRegistry.ALL_AGENTS.map { it.name }
        names.size shouldBe names.toSet().size
    }

    @Test
    fun `all agent descriptions are non-blank`() {
        for (def in AgentRegistry.ALL_AGENTS) {
            def.description shouldNotBe ""
            def.description.isBlank() shouldBe false
        }
    }

    @Test
    fun `all frontmatter and prompt builders are non-null`() {
        // The builders are lambdas — verify they're wired (not null). Invoking
        // them requires a context; a null check confirms the registry entries
        // were constructed with builders.
        for (def in AgentRegistry.ALL_AGENTS) {
            def.promptBuilder shouldNotBe null
            def.frontmatterBuilder shouldNotBe null
        }
    }

    @Test
    fun `all agent names in registry are YAML-safe (not just v2 subagents)`() {
        // Covers the FULL registry (coding-assistant + council + v2 subagents).
        // The v2-only test above would miss an unsafe name added to ALL_AGENTS
        // for a non-v2 agent. YAML_SAFE_IDENTIFIER is the buildTaskPermissionYaml
        // injection guard -- every registry name must pass it.
        for (def in AgentRegistry.ALL_AGENTS) {
            AgentConstants.YAML_SAFE_IDENTIFIER.matches(def.name) shouldBe true
        }
    }

    @Test
    fun `byNameOrNull returns the matching definition`() {
        for (def in AgentRegistry.ALL_AGENTS) {
            AgentRegistry.byNameOrNull(def.name) shouldBe def
        }
    }

    @Test
    fun `byNameOrNull returns null for unknown name (defensive)`() {
        // Unlike byName (which throws IllegalStateException), byNameOrNull
        // returns null for unknown names. Use byNameOrNull when the caller
        // cannot guarantee the name is a known AgentConstants constant.
        AgentRegistry.byNameOrNull("nonexistent-agent") shouldBe null
        AgentRegistry.byNameOrNull("") shouldBe null
    }

    @Test
    fun `frontmatter builders produce non-empty output with mode marker`() {
        // Smoke test: invoke each builder with a real context and verify it
        // produces non-empty output containing "mode:". This catches a broken
        // builder (e.g. references a missing constant) before it reaches the
        // writer. The "non-null" test above is trivial; this is the real check.
        for (def in AgentRegistry.ALL_AGENTS) {
            val settings = OpenCodeAgentSettingsState()
            val ctx = AgentFrontmatterContext(settings, false, def)
            val fm = def.frontmatterBuilder(ctx)
            fm shouldNotBe ""
            fm shouldContain "mode:"
        }
    }

    @Test
    fun `prompt builders produce non-empty output with agent-specific marker`() {
        // Smoke test: invoke each promptBuilder with a representative context
        // and verify non-empty output containing a key phrase. Catches a broken
        // prompt builder (e.g. a missing constant reference) before it reaches
        // the writer, faster than the full writeAgent integration tests.
        for (def in AgentRegistry.ALL_AGENTS) {
            val settings = OpenCodeAgentSettingsState().apply {
                // Council needs at least one valid member for its prompt to be
                // meaningful; others use defaults.
                if (def.name == AgentConstants.COUNCIL_AGENT_NAME) {
                    councilMembers = java.util.ArrayList(listOf(CouncilMember("anthropic", "claude-sonnet-4")))
                }
            }
            val ctx = AgentPromptContext(settings, false, def)
            val prompt = def.promptBuilder(ctx)
            prompt shouldNotBe ""
            // Each prompt starts with a recognizable identifier line.
            when (def.name) {
                AgentConstants.CODING_ASSISTANT_AGENT_NAME -> prompt shouldContain "coding assistant"
                AgentConstants.COUNCIL_AGENT_NAME -> prompt shouldContain "council coordinator"
                AgentConstants.CODER_AGENT_NAME -> prompt shouldContain "implementation subagent"
                AgentConstants.RESEARCHER_AGENT_NAME -> prompt shouldContain "investigator"
                AgentConstants.PLANNER_AGENT_NAME -> prompt shouldContain "decomposer"
                AgentConstants.TESTER_AGENT_NAME -> prompt shouldContain "test implementer"
            }
        }
    }
}
