package com.opencode.acp.chat.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [detectSkillTrigger] — the pure function that detects whether
 * the input text has an active `$` skill trigger.
 *
 * Mirrors [MentionTriggerTest] in structure. The skill palette must open
 * **immediately on bare `$`** (showing all skills, unfiltered), exactly as the
 * slash palette opens on bare `/`. `$$` is the escape to send literal text
 * starting with `$`.
 *
 * These tests verify the pure gate logic in isolation, without Compose
 * infrastructure. The visible-palette state in InputArea delegates to this
 * function, so its behavior is locked here.
 */
class SkillTriggerTest {

    // ── Activation ───────────────────────────────────────────────────────────

    @Test
    fun `bare dollar triggers with empty query`() {
        // The palette must open on bare "$" — this is the bug fix. Previously
        // the gate required text.length > 1, so bare "$" showed nothing.
        val result = detectSkillTrigger("$")
        result.active shouldBe true
        result.query shouldBe ""
    }

    @Test
    fun `dollar with letter query triggers`() {
        val result = detectSkillTrigger("${'$'}git")
        result.active shouldBe true
        result.query shouldBe "git"
    }

    @Test
    fun `dollar with digit query triggers`() {
        // TDD §10 Q7 / M7: "$50" DOES trigger the palette now — the M7 gate
        // (digit-after-$ suppresses palette) was removed because it prevented
        // discoverability (user types "$", sees nothing). The palette simply
        // shows no matches for "$50" if no skill is named "50".
        val result = detectSkillTrigger("$50")
        result.active shouldBe true
        result.query shouldBe "50"
    }

    @Test
    fun `dollar with hyphen query triggers`() {
        val result = detectSkillTrigger("${'$'}my-skill")
        result.active shouldBe true
        result.query shouldBe "my-skill"
    }

    @Test
    fun `dollar with underscore query triggers`() {
        val result = detectSkillTrigger("${'$'}my_skill")
        result.active shouldBe true
        result.query shouldBe "my_skill"
    }

    @Test
    fun `dollar with slash query triggers with slash in query`() {
        // TDD §10 Q7: "$/" → skill palette filtered by "/". The slash palette
        // is NOT triggered (text starts with "$", not "/").
        val result = detectSkillTrigger("$/")
        result.active shouldBe true
        result.query shouldBe "/"
    }

    // ── Escape: $$ ───────────────────────────────────────────────────────────

    @Test
    fun `dollar-dollar escape does not trigger`() {
        // "$$" is the escape to send literal text starting with "$".
        val result = detectSkillTrigger("$$")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `dollar-dollar with text does not trigger`() {
        val result = detectSkillTrigger("${'$'}${'$'}foo")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `dollar-dollar-slash does not trigger`() {
        // TDD §10 Q7 edge case: "$$/" shows neither palette while typing.
        val result = detectSkillTrigger("$$/")
        result.active shouldBe false
        result.query shouldBe ""
    }

    // ── Newline ──────────────────────────────────────────────────────────────

    @Test
    fun `dollar with newline does not trigger`() {
        val result = detectSkillTrigger("${'$'}git\ncommit")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `bare dollar with newline does not trigger`() {
        val result = detectSkillTrigger("$\n")
        result.active shouldBe false
        result.query shouldBe ""
    }

    // ── Non-triggering text ──────────────────────────────────────────────────

    @Test
    fun `empty text does not trigger`() {
        val result = detectSkillTrigger("")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `plain text does not trigger`() {
        val result = detectSkillTrigger("hello world")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `slash-prefixed text does not trigger skill palette`() {
        // Slash palette owns this text. Mutual exclusion is enforced by the
        // caller (InputArea), but detectSkillTrigger returns false regardless
        // because the text does not start with "$".
        val result = detectSkillTrigger("/clear")
        result.active shouldBe false
        result.query shouldBe ""
    }

    @Test
    fun `dollar not at start does not trigger`() {
        val result = detectSkillTrigger("price is $50")
        result.active shouldBe false
        result.query shouldBe ""
    }

    // ── Query extraction ─────────────────────────────────────────────────────

    @Test
    fun `query is everything after the dollar`() {
        val result = detectSkillTrigger("${'$'}git-release args here")
        result.active shouldBe true
        result.query shouldBe "git-release args here"
    }

    @Test
    fun `bare dollar query is empty not null`() {
        val result = detectSkillTrigger("$")
        result.active shouldBe true
        // Empty query means "show all skills, unfiltered" — handled by
        // InputArea's filteredSkills derivation (skillQuery.isBlank() → all).
        result.query shouldBe ""
    }
}