package com.opencode.acp.config.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CouncilMember] — validation regex, model-string formatting,
 * and constructor behavior.
 */
class CouncilMemberTest {

    @Test
    fun `no-arg constructor creates instance with empty strings`() {
        val member = CouncilMember()
        member.providerID shouldBe ""
        member.modelID shouldBe ""
        member.isValid() shouldBe false
    }

    @Test
    fun `parameterized constructor sets fields correctly`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4")
        member.providerID shouldBe "anthropic"
        member.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `isValid returns true for valid alphanumeric provider and model`() {
        CouncilMember("anthropic", "claude-sonnet-4").isValid() shouldBe true
        CouncilMember("openai", "gpt-4o").isValid() shouldBe true
        CouncilMember("google", "gemini-2.5-pro").isValid() shouldBe true
    }

    @Test
    fun `isValid returns true for strings with hyphen underscore dot`() {
        CouncilMember("my-provider", "model_v2.1").isValid() shouldBe true
        CouncilMember("provider.io", "model.v2").isValid() shouldBe true
        CouncilMember("a_b-c.d", "x_y-z.w").isValid() shouldBe true
    }

    @Test
    fun `isValid returns false for strings with slash to prevent promptString delimiter ambiguity`() {
        CouncilMember("provider", "path/to/model").isValid() shouldBe false
        CouncilMember("a/b", "model").isValid() shouldBe false
        CouncilMember("provider", "x_y-z.w/p").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for blank providerID`() {
        CouncilMember("", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("   ", "claude-sonnet-4").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for blank modelID`() {
        CouncilMember("anthropic", "").isValid() shouldBe false
        CouncilMember("anthropic", "   ").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings with colons`() {
        CouncilMember("anthropic:evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic", "claude:sonnet").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings with quotes`() {
        CouncilMember("\"anthropic\"", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic", "'claude'").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings with newlines`() {
        CouncilMember("anthropic\n", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic", "claude\nsonnet").isValid() shouldBe false
        CouncilMember("anthropic\r\n", "claude-sonnet-4").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings with spaces`() {
        CouncilMember("anthropic labs", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic", "claude sonnet 4").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings with other YAML-special chars`() {
        // '#' starts a YAML comment; '&' is an anchor; '*' is an alias; '!' is a tag
        CouncilMember("anthropic#evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic&evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic*evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic!evil", "claude-sonnet-4").isValid() shouldBe false
        // '|' is a YAML block scalar indicator; '>' is a folded scalar; '%' is a directive
        CouncilMember("anthropic|evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic>evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic%evil", "claude-sonnet-4").isValid() shouldBe false
        // '@' and '`' are reserved in YAML
        CouncilMember("anthropic@evil", "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic`evil", "claude-sonnet-4").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false for strings longer than 128 chars`() {
        val tooLong = "a".repeat(129)
        CouncilMember(tooLong, "claude-sonnet-4").isValid() shouldBe false
        CouncilMember("anthropic", tooLong).isValid() shouldBe false
    }

    @Test
    fun `isValid returns true for strings exactly 128 chars`() {
        val maxLen = "a".repeat(128)
        CouncilMember(maxLen, "claude-sonnet-4").isValid() shouldBe true
        CouncilMember("anthropic", maxLen).isValid() shouldBe true
    }

    @Test
    fun `modelString returns provider slash model format`() {
        CouncilMember("anthropic", "claude-sonnet-4").modelString() shouldBe "anthropic/claude-sonnet-4"
        CouncilMember("openai", "gpt-4o").modelString() shouldBe "openai/gpt-4o"
        CouncilMember("google", "gemini-2.5-pro").modelString() shouldBe "google/gemini-2.5-pro"
    }

    @Test
    fun `toString returns same value as modelString`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4")
        member.toString() shouldBe member.modelString()
    }

    @Test
    fun `two members with same fields are equal by property`() {
        // CouncilMember is NOT a data class, so equals() is identity-based.
        // Verify field equality manually to document the contract.
        val a = CouncilMember("anthropic", "claude-sonnet-4")
        val b = CouncilMember("anthropic", "claude-sonnet-4")
        a shouldNotBe b // different instances
        a.providerID shouldBe b.providerID
        a.modelID shouldBe b.modelID
        a.modelString() shouldBe b.modelString()
    }

    // ── thinkingVariant field ───────────────────────────────────────────────

    @Test
    fun `no-arg constructor defaults thinkingVariant to empty`() {
        val member = CouncilMember()
        member.thinkingVariant shouldBe ""
    }

    @Test
    fun `two-arg constructor defaults thinkingVariant to empty`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4")
        member.thinkingVariant shouldBe ""
    }

    @Test
    fun `three-arg constructor sets thinkingVariant`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4", "high")
        member.providerID shouldBe "anthropic"
        member.modelID shouldBe "claude-sonnet-4"
        member.thinkingVariant shouldBe "high"
    }

    @Test
    fun `isValid returns true when thinkingVariant is blank`() {
        CouncilMember("anthropic", "claude-sonnet-4", "").isValid() shouldBe true
    }

    @Test
    fun `isValid returns true when thinkingVariant is valid variant name`() {
        CouncilMember("anthropic", "claude-sonnet-4", "high").isValid() shouldBe true
        CouncilMember("anthropic", "claude-sonnet-4", "max").isValid() shouldBe true
    }

    @Test
    fun `isValid returns false when thinkingVariant has invalid chars`() {
        CouncilMember("anthropic", "claude-sonnet-4", "hi:gh").isValid() shouldBe false
        CouncilMember("anthropic", "claude-sonnet-4", "high ").isValid() shouldBe false
        CouncilMember("anthropic", "claude-sonnet-4", "hi\ngh").isValid() shouldBe false
    }

    @Test
    fun `isValid returns false when thinkingVariant exceeds 128 chars`() {
        val tooLong = "a".repeat(129)
        CouncilMember("anthropic", "claude-sonnet-4", tooLong).isValid() shouldBe false
    }

    @Test
    fun `promptString returns modelString when thinkingVariant is blank`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4", "")
        member.promptString() shouldBe "anthropic/claude-sonnet-4"
    }

    @Test
    fun `promptString appends variant with colon when thinkingVariant is set`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4", "high")
        member.promptString() shouldBe "anthropic/claude-sonnet-4:high"
    }

    @Test
    fun `toString returns promptString`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4", "high")
        member.toString() shouldBe "anthropic/claude-sonnet-4:high"
    }

    @Test
    fun `toString returns modelString when no variant`() {
        val member = CouncilMember("anthropic", "claude-sonnet-4")
        member.toString() shouldBe "anthropic/claude-sonnet-4"
    }
}