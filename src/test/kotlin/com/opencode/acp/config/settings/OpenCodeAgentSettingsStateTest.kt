package com.opencode.acp.config.settings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OpenCodeAgentSettingsState] — loadState dedup/filter logic
 * and default values. Tests construct instances directly (not via getInstance(),
 * which requires the IntelliJ application context).
 *
 * v2 additions (TDD `docs/tdd/custom-agents-v2.md`): tests for the new
 * `enableCoder`/`enableResearcher`/`enablePlanner`/`enableTester` flags,
 * `agentModels` loadState dedup/filter, `modelFor()` helper, and XStream
 * round-trip (Q2).
 */
class OpenCodeAgentSettingsStateTest {

    @Test
    fun `default values are correct`() {
        val state = OpenCodeAgentSettingsState()
        state.enableCodingAssistant shouldBe true
        state.enableCouncil shouldBe false
        state.taskAllowedAgents shouldContainExactly listOf("explore", "general", "reviewer")
        state.councilMembers shouldHaveSize 0
        // v2 defaults
        state.enableCoder shouldBe false
        state.enableResearcher shouldBe false
        state.enablePlanner shouldBe false
        state.enableTester shouldBe false
        // reviewer is the documented exception: the first v2 subagent to default ON
        state.enableReviewer shouldBe true
        state.agentModels shouldHaveSize 0
    }

    @Test
    fun `loadState deduplicates council members by providerID and modelID`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("anthropic", "claude-sonnet-4"),
                CouncilMember("anthropic", "claude-sonnet-4"), // exact duplicate
                CouncilMember("openai", "gpt-4o"),
                CouncilMember("anthropic", "claude-opus-4"),   // same provider, different model
                CouncilMember("openai", "gpt-4o"),             // duplicate of above
            )
        )

        target.loadState(source)

        target.councilMembers shouldHaveSize 3
        target.councilMembers.map { it.modelString() } shouldContainExactly listOf(
            "anthropic/claude-sonnet-4",
            "openai/gpt-4o",
            "anthropic/claude-opus-4",
        )
    }

    @Test
    fun `loadState filters invalid council members`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("anthropic", "claude-sonnet-4"),  // valid
                CouncilMember("", "claude-sonnet-4"),            // blank providerID
                CouncilMember("anthropic", ""),                  // blank modelID
                CouncilMember("anthropic:evil", "claude"),       // invalid char (colon)
                CouncilMember("anthropic", "claude sonnet"),     // invalid char (space)
                CouncilMember("a".repeat(129), "claude"),        // too long
                CouncilMember("openai", "gpt-4o"),               // valid
            )
        )

        target.loadState(source)

        target.councilMembers shouldHaveSize 2
        target.councilMembers.map { it.modelString() } shouldContainExactly listOf(
            "anthropic/claude-sonnet-4",
            "openai/gpt-4o",
        )
    }

    @Test
    fun `loadState filters blank entries from taskAllowedAgents`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.taskAllowedAgents = java.util.ArrayList(
            listOf(
                "explore",
                "",
                "general",
                "   ",
                "plan",
                "",
            )
        )

        target.loadState(source)

        target.taskAllowedAgents shouldContainExactly listOf("explore", "general", "plan")
    }

    @Test
    fun `loadState deduplicates taskAllowedAgents entries`() {
        // Defense-in-depth: duplicates in the persisted XML (from a hand-edit or
        // older plugin version) must not survive reload — otherwise
        // buildTaskPermissionYaml emits duplicate YAML keys.
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.taskAllowedAgents = java.util.ArrayList(
            listOf(
                "explore",
                "general",
                "explore",  // duplicate
                "coder",
                "general",  // duplicate
                "explore",  // duplicate
            )
        )

        target.loadState(source)

        target.taskAllowedAgents shouldContainExactly listOf("explore", "general", "coder")
    }

    @Test
    fun `loadState copies booleans`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.enableCodingAssistant = false
        source.enableCouncil = true

        target.loadState(source)

        target.enableCodingAssistant shouldBe false
        target.enableCouncil shouldBe true
    }

    @Test
    fun `loadState copies taskAllowedAgents ArrayList not shared reference`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.taskAllowedAgents = java.util.ArrayList(listOf("explore", "general"))

        target.loadState(source)

        // Mutate the source after loadState — target must be unaffected.
        source.taskAllowedAgents.add("plan")
        source.taskAllowedAgents.clear()

        target.taskAllowedAgents shouldContainExactly listOf("explore", "general")
    }

    @Test
    fun `loadState copies councilMembers ArrayList not shared reference`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("anthropic", "claude-sonnet-4"),
            )
        )

        target.loadState(source)

        // Mutate the source after loadState — target must be unaffected.
        source.councilMembers.add(CouncilMember("openai", "gpt-4o"))
        source.councilMembers.clear()

        target.councilMembers shouldHaveSize 1
        target.councilMembers[0].modelString() shouldBe "anthropic/claude-sonnet-4"
    }

    @Test
    fun `getState returns self`() {
        val state = OpenCodeAgentSettingsState()
        state.getState() shouldBe state
    }

    @Test
    fun `loadState with empty source preserves defaults structure`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList()
        source.taskAllowedAgents = java.util.ArrayList()
        source.agentModels = java.util.ArrayList()

        target.loadState(source)

        target.councilMembers shouldHaveSize 0
        target.taskAllowedAgents shouldHaveSize 0
        target.agentModels shouldHaveSize 0
    }

    @Test
    fun `loadState dedup is case-sensitive`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("Anthropic", "claude-sonnet-4"),
                CouncilMember("anthropic", "claude-sonnet-4"), // different case in providerID
            )
        )

        target.loadState(source)

        // Case-sensitive dedup: both are kept (different providerID strings)
        target.councilMembers shouldHaveSize 2
    }

    @Test
    fun `loadState keeps same model with different thinking variants as separate members`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("anthropic", "claude-sonnet-4", "low"),
                CouncilMember("anthropic", "claude-sonnet-4", "high"),   // same model, different variant
                CouncilMember("anthropic", "claude-sonnet-4", "low"),   // exact duplicate of first
            )
        )

        target.loadState(source)

        // Two distinct members: (anthropic, claude-sonnet-4, low) and (anthropic, claude-sonnet-4, high)
        target.councilMembers shouldHaveSize 2
        target.councilMembers.map { it.promptString() } shouldContainExactly listOf(
            "anthropic/claude-sonnet-4:low",
            "anthropic/claude-sonnet-4:high",
        )
    }

    @Test
    fun `loadState deduplicates same model with same thinking variant`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(
            listOf(
                CouncilMember("anthropic", "claude-sonnet-4", "high"),
                CouncilMember("anthropic", "claude-sonnet-4", "high"), // exact duplicate
            )
        )

        target.loadState(source)

        target.councilMembers shouldHaveSize 1
    }

    // ── v2 new: enable flags ──────────────────────────────────────────────

    @Test
    fun `loadState copies v2 enable flags`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            enableCoder = true
            enableResearcher = true
            enablePlanner = false
            enableTester = true
            enableReviewer = true
        }

        target.loadState(source)

        target.enableCoder shouldBe true
        target.enableResearcher shouldBe true
        target.enablePlanner shouldBe false
        target.enableTester shouldBe true
        target.enableReviewer shouldBe true
    }

    // ── v2 new: agentModels loadState ─────────────────────────────────────

    @Test
    fun `loadState filters invalid AgentModelBindings (blank agentName)`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4")),
                    AgentModelBinding("", CouncilMember("anthropic", "claude-sonnet-4")), // invalid
                )
            )
        }

        target.loadState(source)

        target.agentModels shouldHaveSize 1
        target.agentModels[0].agentName shouldBe "coder"
    }

    @Test
    fun `loadState filters invalid AgentModelBindings (unsafe agentName chars)`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("co:der", CouncilMember("anthropic", "claude-sonnet-4")), // colon
                    AgentModelBinding("co der", null), // space
                    AgentModelBinding("a".repeat(129), null), // too long
                    AgentModelBinding("coder", null), // valid
                )
            )
        }

        target.loadState(source)

        target.agentModels shouldHaveSize 1
        target.agentModels[0].agentName shouldBe "coder"
    }

    @Test
    fun `loadState normalizes non-null-but-invalid inner model to null (inherit)`() {
        // A binding with a valid agentName but an invalid (blank-provider)
        // CouncilMember is now NORMALIZED to a null-model (inherit) binding
        // at loadState, NOT dropped. This preserves the inherit intent if
        // XStream deserializes <model/> as a blank-fields CouncilMember.
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("", "claude-sonnet-4")),  // invalid model (blank provider)
                    AgentModelBinding("researcher", CouncilMember("anthropic", "claude-sonnet-4")),  // valid
                    AgentModelBinding("planner", CouncilMember("anthropic", "")),  // invalid model (blank modelID)
                )
            )
        }

        target.loadState(source)

        // All 3 bindings are preserved, but the invalid-model ones are
        // normalized to null-model (inherit). modelFor returns null for them.
        target.agentModels shouldHaveSize 3
        target.modelFor("coder") shouldBe null
        target.modelFor("planner") shouldBe null
        target.modelFor("researcher")?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `loadState deduplicates AgentModelBindings by agentName (first wins among equal hasModel)`() {
        val target = OpenCodeAgentSettingsState()
        val first = AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4"))
        val second = AgentModelBinding("coder", CouncilMember("openai", "gpt-4o"))
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(listOf(first, second))
        }

        target.loadState(source)

        target.agentModels shouldHaveSize 1
        // groupBy + first() keeps the FIRST entry for a given key (consistent
        // with the councilMembers dedup strategy — first wins).
        target.agentModels[0].agentName shouldBe "coder"
        target.agentModels[0].model?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `loadState preserves AgentModelBindings with null model (inherit)`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", null), // inherit
                    AgentModelBinding("researcher", CouncilMember("anthropic", "claude-sonnet-4")),
                )
            )
        }

        target.loadState(source)

        target.agentModels shouldHaveSize 2
        target.agentModels.find { it.agentName == "coder" }?.model shouldBe null
        target.agentModels.find { it.agentName == "researcher" }?.model?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `loadState copies agentModels ArrayList not shared reference`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4"))
                )
            )
        }

        target.loadState(source)

        source.agentModels.clear()
        target.agentModels shouldHaveSize 1
        target.agentModels[0].agentName shouldBe "coder"
    }

    // ── v2 new: modelFor helper ───────────────────────────────────────────

    @Test
    fun `modelFor returns null when no binding exists`() {
        val state = OpenCodeAgentSettingsState()
        state.modelFor("coder") shouldBe null
        state.modelFor("researcher") shouldBe null
    }

    @Test
    fun `modelFor returns the configured model`() {
        val state = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "high"))
                )
            )
        }
        val model = state.modelFor("coder")
        model.shouldNotBeNull()
        model.providerID shouldBe "anthropic"
        model.modelID shouldBe "claude-sonnet-4"
        model.thinkingVariant shouldBe "high"
    }

    @Test
    fun `modelFor returns null when binding has null model`() {
        val state = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", null)
                )
            )
        }
        state.modelFor("coder") shouldBe null
    }

    @Test
    fun `modelFor returns null when binding has invalid model`() {
        val state = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("", "claude-sonnet-4"))
                )
            )
        }
        state.modelFor("coder") shouldBe null
    }

    @Test
    fun `modelFor returns null for unknown agent name`() {
        val state = OpenCodeAgentSettingsState()
        state.modelFor("nonexistent-agent") shouldBe null
    }

    // ── v2 new: loadState preserves mixed null and non-null AgentModelBindings ─
    // NOTE: This test does NOT verify XStream XML serialization fidelity. A true
    // XStream round-trip test would require the IntelliJ application context
    // (PersistentStateComponent + XStream), which plain unit tests cannot
    // bootstrap (same limitation as the Compose UI tests in AGENTS.md). This
    // test exercises the loadState filter/dedup logic only - loadState is what
    // XStream calls after deserializing the saved state.
    //
    // The earlier disclaimer here warned that "if XStream deserializes a null
    // model as a default-constructed CouncilMember (with blank fields) instead
    // of null, the inner-model filter in loadState would drop the binding
    // entirely (changing the count)." That risk is now MITIGATED: loadState
    // normalizes a blank-fields inner CouncilMember to null (inherit) via a
    // `.map` BEFORE filtering (see `loadState normalizes blank-fields inner
    // CouncilMember to null` test below), so the count does NOT change - the
    // binding is preserved as a null-model (inherit) binding instead of being
    // dropped. The remaining unverified assumption is narrower: whether XStream
    // deserializes `<model/>` as a blank-fields CouncilMember AT ALL (vs. null
    // or throwing). An integration test with a live XStream instance is still
    // needed to confirm the deserialization shape - see the "Compose UI Tests"
    // section of AGENTS.md for the same context-limitation pattern.

    @Test
    fun `loadState preserves mixed null and non-null AgentModelBindings`() {
        // Simulate the post-deserialization state: a mix of null (inherit) and
        // non-null model bindings. loadState is what XStream calls after
        // deserializing the saved state.
        // We approximate by constructing a "deserialized" source state and
        // running loadState (the exact code path XStream triggers).
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", null), // inherit
                    AgentModelBinding("researcher", CouncilMember("anthropic", "claude-sonnet-4")),
                    AgentModelBinding("planner", CouncilMember("openai", "gpt-4o", "high")),
                    AgentModelBinding("tester", null), // inherit
                )
            )
        }
        val target = OpenCodeAgentSettingsState()
        target.loadState(source)

        target.agentModels shouldHaveSize 4
        target.modelFor("coder") shouldBe null
        target.modelFor("researcher")?.modelID shouldBe "claude-sonnet-4"
        target.modelFor("planner")?.thinkingVariant shouldBe "high"
        target.modelFor("tester") shouldBe null
    }

    // ── v2 new: dedup prefers model-bearing bindings ───────────────────

    @Test
    fun `loadState dedup prefers model-bearing binding over null-model for same agent`() {
        // A null-model (inherit) binding appearing before a valid-model
        // binding for the same agent must NOT silently discard the valid
        // model. The dedup now sorts so hasModel()==true comes first.
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", null), // inherit, appears first
                    AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4")) // valid model
                )
            )
        }

        target.loadState(source)

        target.agentModels shouldHaveSize 1
        // The model-bearing binding is preserved (not the null-model one).
        target.modelFor("coder")?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `loadState normalizes blank-fields inner CouncilMember to null (XStream sentinel)`() {
        // If XStream deserializes <model/> as a blank-fields CouncilMember
        // (providerID="", modelID="") instead of null, loadState normalizes
        // it to a null-model (inherit) binding so the inherit intent survives
        // the round-trip instead of being dropped.
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    // Simulate XStream blank-sentinel: non-null but invalid model
                    AgentModelBinding("coder", CouncilMember("", "")),
                    AgentModelBinding("researcher", CouncilMember("anthropic", "claude-sonnet-4"))
                )
            )
        }

        target.loadState(source)

        // coder binding preserved as null-model (inherit), not dropped.
        target.agentModels shouldHaveSize 2
        target.modelFor("coder") shouldBe null
        target.modelFor("researcher")?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `loadState deep-copies councilMembers (no element aliasing)`() {
        // Mutating a source member field after loadState must NOT affect the
        // target (the elements are deep-copied, not shared references).
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            councilMembers = java.util.ArrayList(
                listOf(CouncilMember("anthropic", "claude-sonnet-4"))
            )
        }

        target.loadState(source)

        // Mutate the source member field.
        source.councilMembers[0].providerID = "mutated"

        // Target is unaffected (deep copy).
        target.councilMembers[0].providerID shouldBe "anthropic"
    }

    @Test
    fun `loadState preserves coding-assistant binding (not dropped by dedup)`() {
        // A coding-assistant binding (no UI row for hasPerAgentModel==false)
        // must survive loadState so apply() can preserve it. This is the
        // settings-state half of the OpenCodeAgentConfigurable apply()
        // preservation fix (the apply() half cannot be unit-tested without
        // the IntelliJ application context).
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(AgentModelBinding("coding-assistant", CouncilMember("anthropic", "claude-sonnet-4")))
            )
        }

        target.loadState(source)

        // The coding-assistant binding is preserved (not dropped).
        target.agentModels shouldHaveSize 1
        target.agentModels[0].agentName shouldBe "coding-assistant"
        target.agentModels[0].model?.modelID shouldBe "claude-sonnet-4"
    }

    // ── v2 new: mergeAgentModelBindings pure merge (review cmt_d8e9f0a1b2c3) ─

    @Test
    fun `mergeAgentModelBindings preserves non-UI bindings and overwrites UI bindings`() {
        // The merge logic extracted from OpenCodeAgentConfigurable.apply():
        // preserve bindings for agents WITHOUT a UI row (coding-assistant,
        // council), then add/overwrite with the UI bindings. This test
        // verifies the pure function without needing the IntelliJ app context.
        val existing = listOf(
            AgentModelBinding("coding-assistant", CouncilMember("anthropic", "claude-sonnet-4")),
            AgentModelBinding("coder", CouncilMember("openai", "gpt-4o")), // stale UI binding
            AgentModelBinding("council", CouncilMember("anthropic", "claude-opus-4")),
        )
        val uiBindings = listOf(
            AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "high")),
            AgentModelBinding("researcher", CouncilMember("openai", "gpt-4o")),
        )
        val uiAgentNames = setOf("coder", "researcher", "planner", "tester", "reviewer")

        val merged = OpenCodeAgentConfigurable.mergeAgentModelBindings(existing, uiBindings, uiAgentNames)

        // coding-assistant and council (non-UI) are preserved.
        merged.any { it.agentName == "coding-assistant" } shouldBe true
        merged.any { it.agentName == "council" } shouldBe true
        // The stale coder binding is replaced by the UI coder binding (not duplicated).
        val coderBindings = merged.filter { it.agentName == "coder" }
        coderBindings.size shouldBe 1
        coderBindings[0].model?.modelID shouldBe "claude-sonnet-4"
        coderBindings[0].model?.thinkingVariant shouldBe "high"
        // The new researcher binding is added.
        merged.any { it.agentName == "researcher" } shouldBe true
        // Total: 2 preserved (coding-assistant, council) + 2 UI (coder, researcher) = 4.
        merged.size shouldBe 4
    }

    @Test
    fun `mergeAgentModelBindings with empty existing returns only UI bindings`() {
        val uiBindings = listOf(AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4")))
        val merged = OpenCodeAgentConfigurable.mergeAgentModelBindings(emptyList(), uiBindings, setOf("coder"))
        merged.size shouldBe 1
        merged[0].agentName shouldBe "coder"
    }

    @Test
    fun `mergeAgentModelBindings with empty UI bindings preserves all existing`() {
        val existing = listOf(
            AgentModelBinding("coding-assistant", CouncilMember("anthropic", "claude-sonnet-4")),
            AgentModelBinding("council", CouncilMember("anthropic", "claude-opus-4")),
        )
        val merged = OpenCodeAgentConfigurable.mergeAgentModelBindings(existing, emptyList(), setOf("coder"))
        // All existing bindings preserved (none are UI-agent-named).
        merged.size shouldBe 2
    }
}
