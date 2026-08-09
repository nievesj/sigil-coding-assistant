package com.opencode.acp.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
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
    fun `ALL_AGENTS contains all 7 agents in registry order`() {
        AgentRegistry.ALL_AGENTS.map { it.name } shouldContainExactly listOf(
            AgentConstants.CODING_ASSISTANT_AGENT_NAME,
            AgentConstants.COUNCIL_AGENT_NAME,
            AgentConstants.CODER_AGENT_NAME,
            AgentConstants.RESEARCHER_AGENT_NAME,
            AgentConstants.PLANNER_AGENT_NAME,
            AgentConstants.TESTER_AGENT_NAME,
            AgentConstants.REVIEWER_AGENT_NAME,
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
    fun `reviewer is subagent, default-enabled, has per-agent model`() {
        // INVERTED vs coder/researcher/planner/tester: reviewer is the FIRST
        // v2 subagent to default ON (advisory/read-only — writes only .review/
        // findings, never edits source files). See AgentRegistry.ALL_AGENTS.
        val def = AgentRegistry.byName(AgentConstants.REVIEWER_AGENT_NAME)
        def.mode shouldBe "subagent"
        def.defaultEnabled shouldBe true
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
    fun `enableFlagFor reflects the correct settings flag for every registry agent (exhaustive coverage)`() {
        // Exhaustive-coverage test (review cmt_b2c3d4e5f6a8 / cmt_e5f6a7b8c9d1 /
        // cmt_f6a7b8c9d0e2): the name→enable-flag mapping is now centralized on
        // each AgentDefinition (enableFlagGetter), exposed via
        // AgentRegistry.enableFlagFor. This test asserts that EVERY registry
        // agent's getter reads the correct settings field — enabling all flags
        // and verifying enableFlagFor returns true for every name. A missing
        // getter wiring (e.g., a new agent added to ALL_AGENTS without an
        // enableFlagGetter) would fail here. Previously the mapping was
        // duplicated across four parallel `when` expressions with no exhaustive
        // test — a missing arm was a silent failure.
        val settings = OpenCodeAgentSettingsState().apply {
            enableCodingAssistant = true
            enableCouncil = true
            enableCoder = true
            enableResearcher = true
            enablePlanner = true
            enableTester = true
            enableReviewer = true
        }
        for (def in AgentRegistry.ALL_AGENTS) {
            AgentRegistry.enableFlagFor(def.name, settings) shouldBe true
        }
        // Disable all flags → enableFlagFor returns false for every name.
        val allOff = OpenCodeAgentSettingsState().apply {
            enableCodingAssistant = false
            enableCouncil = false
            enableCoder = false
            enableResearcher = false
            enablePlanner = false
            enableTester = false
            enableReviewer = false
        }
        for (def in AgentRegistry.ALL_AGENTS) {
            AgentRegistry.enableFlagFor(def.name, allOff) shouldBe false
        }
    }

    @Test
    fun `enableFlagFor returns false for unknown agent name (defense-in-depth)`() {
        // Unknown names must fail-closed (false) — only registry agents can be
        // enabled. This is the safety net that replaced the `else -> false` arm
        // of the old `when` expressions.
        val settings = OpenCodeAgentSettingsState()
        AgentRegistry.enableFlagFor("nonexistent-agent", settings) shouldBe false
        AgentRegistry.enableFlagFor("", settings) shouldBe false
    }

    @Test
    fun `enableFlagSetter writes the correct settings flag for every registry agent (exhaustive coverage)`() {
        // Exhaustive-coverage test for the setter side (review
        // cmt_f6a7b8c9d0e2): the name→enable-flag setter is centralized on each
        // AgentDefinition. This test asserts that setting each registry agent's
        // flag via the centralized setter actually mutates the correct settings
        // field. A missing setter wiring would leave the field unchanged — the
        // user's Apply choice would silently revert on reload.
        for (def in AgentRegistry.ALL_AGENTS) {
            val settings = OpenCodeAgentSettingsState()
            // Start from false (the safe default), set true via the centralized
            // setter, then verify the raw settings field reflects the change.
            def.enableFlagSetter.invoke(settings, true)
            // enableFlagFor reads it back via the getter — if the setter wrote
            // the WRONG field, the getter would still read false.
            AgentRegistry.enableFlagFor(def.name, settings) shouldBe true
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
                AgentConstants.REVIEWER_AGENT_NAME -> prompt shouldContain "adversarial"
            }
        }
    }
}
