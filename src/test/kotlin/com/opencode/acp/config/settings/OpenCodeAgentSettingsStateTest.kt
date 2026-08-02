package com.opencode.acp.config.settings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OpenCodeAgentSettingsState] — loadState dedup/filter logic
 * and default values. Tests construct instances directly (not via getInstance(),
 * which requires the IntelliJ application context).
 */
class OpenCodeAgentSettingsStateTest {

    @Test
    fun `default values are correct`() {
        val state = OpenCodeAgentSettingsState()
        state.enableCodingAssistant shouldBe true
        state.enableCouncil shouldBe false
        state.taskAllowedAgents shouldContainExactly listOf("explore", "general")
        state.councilMembers shouldHaveSize 0
    }

    @Test
    fun `loadState deduplicates council members by providerID and modelID`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("anthropic", "claude-sonnet-4"),
            CouncilMember("anthropic", "claude-sonnet-4"), // exact duplicate
            CouncilMember("openai", "gpt-4o"),
            CouncilMember("anthropic", "claude-opus-4"),   // same provider, different model
            CouncilMember("openai", "gpt-4o"),             // duplicate of above
        ))

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
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("anthropic", "claude-sonnet-4"),  // valid
            CouncilMember("", "claude-sonnet-4"),            // blank providerID
            CouncilMember("anthropic", ""),                  // blank modelID
            CouncilMember("anthropic:evil", "claude"),       // invalid char (colon)
            CouncilMember("anthropic", "claude sonnet"),     // invalid char (space)
            CouncilMember("a".repeat(129), "claude"),        // too long
            CouncilMember("openai", "gpt-4o"),               // valid
        ))

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
        source.taskAllowedAgents = java.util.ArrayList(listOf(
            "explore",
            "",
            "general",
            "   ",
            "plan",
            "",
        ))

        target.loadState(source)

        target.taskAllowedAgents shouldContainExactly listOf("explore", "general", "plan")
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
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("anthropic", "claude-sonnet-4"),
        ))

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

        target.loadState(source)

        target.councilMembers shouldHaveSize 0
        target.taskAllowedAgents shouldHaveSize 0
    }

    @Test
    fun `loadState dedup is case-sensitive`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("Anthropic", "claude-sonnet-4"),
            CouncilMember("anthropic", "claude-sonnet-4"), // different case in providerID
        ))

        target.loadState(source)

        // Case-sensitive dedup: both are kept (different providerID strings)
        target.councilMembers shouldHaveSize 2
    }

    @Test
    fun `loadState keeps same model with different thinking variants as separate members`() {
        val target = OpenCodeAgentSettingsState()
        val source = OpenCodeAgentSettingsState()
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("anthropic", "claude-sonnet-4", "low"),
            CouncilMember("anthropic", "claude-sonnet-4", "high"),   // same model, different variant
            CouncilMember("anthropic", "claude-sonnet-4", "low"),   // exact duplicate of first
        ))

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
        source.councilMembers = java.util.ArrayList(listOf(
            CouncilMember("anthropic", "claude-sonnet-4", "high"),
            CouncilMember("anthropic", "claude-sonnet-4", "high"), // exact duplicate
        ))

        target.loadState(source)

        target.councilMembers shouldHaveSize 1
    }
}