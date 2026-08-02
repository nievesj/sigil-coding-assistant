package com.opencode.acp.config.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AgentModelBinding] — validation, model presence, and
 * constructor behavior. Mirrors the [CouncilMemberTest] style.
 *
 * See `docs/tdd/custom-agents-v2.md` §4.7.1B.
 */
class AgentModelBindingTest {

    @Test
    fun `no-arg constructor creates instance with blank name and null model`() {
        val binding = AgentModelBinding()
        binding.agentName shouldBe ""
        binding.model shouldBe null
        binding.isValid() shouldBe false
    }

    @Test
    fun `parameterized constructor sets fields`() {
        val model = CouncilMember("anthropic", "claude-sonnet-4")
        val binding = AgentModelBinding("coder", model)
        binding.agentName shouldBe "coder"
        binding.model shouldBe model
    }

    @Test
    fun `parameterized constructor accepts null model (inherit)`() {
        val binding = AgentModelBinding("coder", null)
        binding.agentName shouldBe "coder"
        binding.model shouldBe null
        binding.isValid() shouldBe true
        binding.hasModel() shouldBe false
    }

    @Test
    fun `isValid returns true for valid agent name`() {
        AgentModelBinding("coder", null).isValid() shouldBe true
        AgentModelBinding("researcher", null).isValid() shouldBe true
        AgentModelBinding("planner", null).isValid() shouldBe true
        AgentModelBinding("tester", null).isValid() shouldBe true
    }

    @Test
    fun `isValid returns false for blank agent name`() {
        AgentModelBinding("", null).isValid() shouldBe false
        AgentModelBinding("   ", null).isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for agent name with unsafe chars`() {
        AgentModelBinding("co der", null).isValid() shouldBe false
        AgentModelBinding("co:der", null).isValid() shouldBe false
        AgentModelBinding("co/der", null).isValid() shouldBe false
        AgentModelBinding("co\"der", null).isValid() shouldBe false
        AgentModelBinding("co\nder", null).isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for agent name exceeding 128 chars`() {
        val tooLong = "a".repeat(129)
        AgentModelBinding(tooLong, null).isValid() shouldBe false
    }

    @Test
    fun `isValid returns true for agent name exactly 128 chars`() {
        val maxLen = "a".repeat(128)
        AgentModelBinding(maxLen, null).isValid() shouldBe true
    }

    @Test
    fun `isValid is independent of the model field`() {
        // isValid() only checks the agent name — model can be null (inherit) or
        // valid or invalid; isValid() reflects the agent name only.
        AgentModelBinding("coder", null).isValid() shouldBe true
        AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4")).isValid() shouldBe true
        AgentModelBinding("coder", CouncilMember("", "")).isValid() shouldBe true
    }

    @Test
    fun `hasModel returns true when model is valid`() {
        val binding = AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4"))
        binding.hasModel() shouldBe true
    }

    @Test
    fun `hasModel returns false when model is null`() {
        val binding = AgentModelBinding("coder", null)
        binding.hasModel() shouldBe false
    }

    @Test
    fun `hasModel returns false when model is invalid`() {
        val binding = AgentModelBinding("coder", CouncilMember("", ""))
        binding.hasModel() shouldBe false
        val binding2 = AgentModelBinding("coder", CouncilMember("anthropic", ""))
        binding2.hasModel() shouldBe false
        val binding3 = AgentModelBinding("coder", CouncilMember("an:thropic", "claude"))
        binding3.hasModel() shouldBe false
    }

    @Test
    fun `toString includes agent name and model state`() {
        val withModel = AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "high"))
        withModel.toString() shouldBe "AgentModelBinding(coder -> anthropic/claude-sonnet-4:high)"

        val inherit = AgentModelBinding("coder", null)
        inherit.toString() shouldBe "AgentModelBinding(coder -> inherit)"
    }

    @Test
    fun `two bindings with same fields are equal by property`() {
        // AgentModelBinding is NOT a data class, so equals() is identity-based.
        // Verify field equality manually to document the contract.
        val model = CouncilMember("anthropic", "claude-sonnet-4")
        val a = AgentModelBinding("coder", model)
        val b = AgentModelBinding("coder", model)
        a shouldNotBe b // different instances
        a.agentName shouldBe b.agentName
        a.model shouldBe b.model
    }

    @Test
    fun `isFullyValid returns true for valid name and null model (inherit)`() {
        AgentModelBinding("coder", null).isFullyValid() shouldBe true
    }

    @Test
    fun `isFullyValid returns true for valid name and valid model`() {
        AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4")).isFullyValid() shouldBe true
    }

    @Test
    fun `isFullyValid returns false for valid name but invalid inner model`() {
        // isValid() returns true (name is fine), but isFullyValid() returns
        // false because the inner model is invalid. This is the key
        // distinction: isValid() checks ONLY the name; isFullyValid() checks
        // both the name and the model.
        AgentModelBinding("coder", CouncilMember("", "x")).isFullyValid() shouldBe false
        AgentModelBinding("coder", CouncilMember("anthropic", "")).isFullyValid() shouldBe false
    }

    @Test
    fun `isFullyValid returns false for invalid name`() {
        AgentModelBinding("", null).isFullyValid() shouldBe false
        AgentModelBinding("co:der", null).isFullyValid() shouldBe false
    }

    @Test
    fun `contains uses identity equality (footgun documentation)`() {
        // AgentModelBinding.equals is identity-based. list.contains(b)
        // returns false for a structurally-identical b. This documents the
        // footgun: do NOT use List.contains / Set membership for bindings.
        val model = CouncilMember("anthropic", "claude-sonnet-4")
        val a = AgentModelBinding("coder", model)
        val b = AgentModelBinding("coder", model)
        listOf(a).contains(b) shouldBe false
    }

    @Test
    fun `isFullyValid and hasModel return false when inner model has unsafe thinkingVariant`() {
        // CouncilMember.isValid() checks thinkingVariant against
        // YAML_SAFE_IDENTIFIER. A variant with a colon (e.g. "hi:gh") is
        // invalid, so a binding with such a model is NOT isFullyValid and does
        // NOT hasModel. This pins the delegation: isFullyValid/hasModel call
        // model.isValid() which includes the variant check.
        val binding = AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "hi:gh"))
        binding.isFullyValid() shouldBe false
        binding.hasModel() shouldBe false
    }
}
