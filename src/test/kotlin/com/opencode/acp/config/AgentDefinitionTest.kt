package com.opencode.acp.config

import com.opencode.acp.config.settings.AgentModelBinding
import com.opencode.acp.config.settings.CouncilMember
import com.opencode.acp.config.settings.OpenCodeAgentSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AgentDefinition], [AgentFrontmatterContext], and
 * [AgentPromptContext] — the data classes that describe one plugin-defined
 * agent and the context passed to its prompt/frontmatter builders.
 *
 * See `docs/tdd/custom-agents-v2.md` §4.7.1A and §4.7.1A2.
 *
 * Note: [AgentDefinition] is a data class, but its `promptBuilder` and
 * `frontmatterBuilder` are lambdas. Lambda equality is reference-based (two
 * lambda instances are never `==` even if they compute the same value), so
 * data-class `equals`/`hashCode` only match when the SAME lambda instance is
 * used for both. Tests use a shared builder instance to verify equality, and
 * separate instances to verify inequality.
 */
class AgentDefinitionTest {

    // Shared builder instances so data-class equality can be tested.
    private val sharedPromptBuilder: (AgentPromptContext) -> String = { ctx -> "prompt-${ctx.agentDef.name}" }
    private val sharedFrontmatterBuilder: (AgentFrontmatterContext) -> String = { ctx -> "fm-${ctx.agentDef.name}" }
    private val sharedEnableGetter: (OpenCodeAgentSettingsState) -> Boolean = { false }
    private val sharedEnableSetter: (OpenCodeAgentSettingsState, Boolean) -> Unit = { _, _ -> }

    private fun newDef(
        name: String = "coder",
        mode: String = "subagent",
        defaultEnabled: Boolean = false,
        hidden: Boolean = false,
        hasPerAgentModel: Boolean = true,
        alwaysOverwrite: Boolean = false,
        promptBuilder: (AgentPromptContext) -> String = sharedPromptBuilder,
        frontmatterBuilder: (AgentFrontmatterContext) -> String = sharedFrontmatterBuilder,
        enableFlagGetter: (OpenCodeAgentSettingsState) -> Boolean = sharedEnableGetter,
        enableFlagSetter: (OpenCodeAgentSettingsState, Boolean) -> Unit = sharedEnableSetter,
    ): AgentDefinition = AgentDefinition(
        name = name,
        mode = mode,
        defaultEnabled = defaultEnabled,
        hidden = hidden,
        hasPerAgentModel = hasPerAgentModel,
        alwaysOverwrite = alwaysOverwrite,
        description = "test agent",
        promptBuilder = promptBuilder,
        frontmatterBuilder = frontmatterBuilder,
        enableFlagGetter = enableFlagGetter,
        enableFlagSetter = enableFlagSetter,
    )

    @Test
    fun `AgentDefinition data class equality holds when same builder instances are used`() {
        // Both defs share the SAME promptBuilder/frontmatterBuilder instances
        // → lambdas are reference-equal → data class equals returns true.
        val a = newDef()
        val b = newDef()
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `AgentDefinition data class inequality on different name`() {
        val a = newDef(name = "coder")
        val b = newDef(name = "researcher")
        a shouldNotBe b
    }

    @Test
    fun `AgentDefinition data class inequality with different lambda instances`() {
        // Two distinct lambda instances are never equal even if they compute
        // the same value — documents the lambda-equality caveat.
        val a = newDef(promptBuilder = { ctx -> "prompt-${ctx.agentDef.name}" })
        val b = newDef(promptBuilder = { ctx -> "prompt-${ctx.agentDef.name}" })
        a shouldNotBe b
    }

    @Test
    fun `AgentDefinition defaults alwaysOverwrite to false`() {
        val def = newDef()
        def.alwaysOverwrite shouldBe false
    }

    @Test
    fun `AgentDefinition promptBuilder is invoked with context`() {
        val def = newDef(name = "planner")
        val ctx = AgentPromptContext(OpenCodeAgentSettingsState(), false, def)
        def.promptBuilder(ctx) shouldBe "prompt-planner"
    }

    @Test
    fun `AgentDefinition frontmatterBuilder is invoked with context`() {
        val def = newDef(name = "tester")
        val ctx = AgentFrontmatterContext(OpenCodeAgentSettingsState(), false, def)
        def.frontmatterBuilder(ctx) shouldBe "fm-tester"
    }

    @Test
    fun `AgentFrontmatterContext modelBinding is null when agent has no per-agent model`() {
        val def = AgentDefinition(
            name = "coding-assistant",
            mode = "primary",
            defaultEnabled = true,
            hidden = false,
            hasPerAgentModel = false,
            description = "x",
            promptBuilder = { "p" },
            frontmatterBuilder = { "f" },
            enableFlagGetter = { false },
            enableFlagSetter = { _, _ -> },
        )
        val ctx = AgentFrontmatterContext(OpenCodeAgentSettingsState(), false, def)
        ctx.modelBinding shouldBe null
    }

    @Test
    fun `AgentFrontmatterContext modelBinding is null when no binding configured`() {
        val def = newDef(name = "coder", hasPerAgentModel = true)
        val settings = OpenCodeAgentSettingsState()
        // No agentModels configured → modelFor returns null
        val ctx = AgentFrontmatterContext(settings, false, def)
        ctx.modelBinding shouldBe null
    }

    @Test
    fun `AgentFrontmatterContext modelBinding returns configured model`() {
        val def = newDef(name = "coder", hasPerAgentModel = true)
        val settings = OpenCodeAgentSettingsState().apply {
            agentModels = java.util.ArrayList(
                listOf(
                    AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "high"))
                )
            )
        }
        val ctx = AgentFrontmatterContext(settings, false, def)
        ctx.modelBinding shouldNotBe null
        ctx.modelBinding?.providerID shouldBe "anthropic"
        ctx.modelBinding?.modelID shouldBe "claude-sonnet-4"
        ctx.modelBinding?.thinkingVariant shouldBe "high"
    }

    @Test
    fun `AgentFrontmatterContext carries settings, MCP flag, and def`() {
        val settings = OpenCodeAgentSettingsState()
        val def = newDef(name = "coder")
        val ctx = AgentFrontmatterContext(settings, true, def)
        ctx.settings shouldBe settings
        ctx.isIntellijMcpEnabled shouldBe true
        ctx.agentDef shouldBe def
    }

    @Test
    fun `AgentPromptContext carries settings, MCP flag, and def`() {
        val settings = OpenCodeAgentSettingsState()
        val def = newDef(name = "planner")
        val ctx = AgentPromptContext(settings, false, def)
        ctx.settings shouldBe settings
        ctx.isIntellijMcpEnabled shouldBe false
        ctx.agentDef shouldBe def
    }

    @Test
    fun `AgentDefinition in a Set deduplicates by reference not value (footgun)`() {
        // Two structurally-identical defs with DISTINCT lambda instances are
        // NOT equal (data-class equals uses reference equality for lambdas).
        // So a Set keeps both, and contains() returns false for a
        // structurally-identical-but-distinct instance. This documents the
        // trap: do NOT use AgentDefinition in Set/Map-key contexts.
        val a = newDef(promptBuilder = { ctx -> "p-${ctx.agentDef.name}" })
        val b = newDef(promptBuilder = { ctx -> "p-${ctx.agentDef.name}" })
        setOf(a, b).size shouldBe 2
        setOf(a).contains(b) shouldBe false
    }

    @Test
    fun `AgentDefinition in a Set deduplicates by reference when same instance (inverse)`() {
        // Inverse of the test above: two refs to the SAME instance ARE equal
        // (data-class equals uses reference equality for lambdas, and the same
        // instance is reference-equal to itself). A Set keeps one, and
        // contains() returns true. This completes the picture: identity
        // equality is the data-class behavior the caveat is about.
        val a = newDef(promptBuilder = { ctx -> "p-${ctx.agentDef.name}" })
        setOf(a, a).size shouldBe 1
        setOf(a).contains(a) shouldBe true
    }
}
