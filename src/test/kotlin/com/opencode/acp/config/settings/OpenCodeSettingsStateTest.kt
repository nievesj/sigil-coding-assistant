package com.opencode.acp.config.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OpenCodeSettingsState] — `loadState` persistence for the
 * last-selected agent and thinking effort fields. These fields were previously
 * missing from `loadState`, causing them to reset to empty (default) on every
 * IDE restart.
 *
 * Tests construct instances directly (not via `getInstance()`, which requires
 * the IntelliJ application context).
 */
class OpenCodeSettingsStateTest {

    @Test
    fun `loadState persists lastSelectedAgent across restart`() {
        val target = OpenCodeSettingsState()
        val source = OpenCodeSettingsState().apply {
            lastSelectedAgent = "coding-assistant"
        }

        target.loadState(source)

        target.lastSelectedAgent shouldBe "coding-assistant"
    }

    @Test
    fun `loadState persists lastSelectedThinkingEffort across restart`() {
        val target = OpenCodeSettingsState()
        val source = OpenCodeSettingsState().apply {
            lastSelectedThinkingEffort = "HIGH"
        }

        target.loadState(source)

        target.lastSelectedThinkingEffort shouldBe "HIGH"
    }

    @Test
    fun `loadState persists both lastSelectedAgent and lastSelectedThinkingEffort together`() {
        val target = OpenCodeSettingsState()
        val source = OpenCodeSettingsState().apply {
            lastSelectedModelKey = "anthropic/claude-sonnet-4"
            lastSelectedAgent = "coding-assistant"
            lastSelectedThinkingEffort = "MEDIUM"
        }

        target.loadState(source)

        target.lastSelectedModelKey shouldBe "anthropic/claude-sonnet-4"
        target.lastSelectedAgent shouldBe "coding-assistant"
        target.lastSelectedThinkingEffort shouldBe "MEDIUM"
    }

    @Test
    fun `loadState resets to default when source fields are empty`() {
        // Simulate a fresh install or a settings file with empty values.
        val target = OpenCodeSettingsState().apply {
            lastSelectedAgent = "old-agent"
            lastSelectedThinkingEffort = "HIGH"
        }
        val source = OpenCodeSettingsState() // defaults: empty strings

        target.loadState(source)

        target.lastSelectedAgent shouldBe ""
        target.lastSelectedThinkingEffort shouldBe ""
    }
}