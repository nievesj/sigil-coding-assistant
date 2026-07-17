package com.opencode.acp.chat.ui.compose

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [detectMentionTrigger] — the pure function that detects whether
 * the input text has an active `@` mention trigger at the cursor position.
 *
 * Also tests [RecentFile.isOpen] field defaults and equality.
 */
class MentionTriggerTest {

    // ── detectMentionTrigger: activation ────────────────────────────────────

    @Test
    fun `bare at-sign at start triggers with empty query`() {
        val result = detectMentionTrigger("@", cursorPos = 1)
        result.active shouldBe true
        result.query shouldBe ""
        result.startIndex shouldBe 0
    }

    @Test
    fun `at-sign at start with query triggers`() {
        val result = detectMentionTrigger("@Main", cursorPos = 5)
        result.active shouldBe true
        result.query shouldBe "Main"
        result.startIndex shouldBe 0
    }

    @Test
    fun `at-sign after space triggers`() {
        val result = detectMentionTrigger("fix @Main", cursorPos = 10)
        result.active shouldBe true
        result.query shouldBe "Main"
        result.startIndex shouldBe 4
    }

    @Test
    fun `at-sign after newline triggers`() {
        val result = detectMentionTrigger("line1\n@Main", cursorPos = 12)
        result.active shouldBe true
        result.query shouldBe "Main"
        result.startIndex shouldBe 6
    }

    @Test
    fun `at-sign after tab triggers`() {
        val result = detectMentionTrigger("\t@Main", cursorPos = 6)
        result.active shouldBe true
        result.query shouldBe "Main"
        result.startIndex shouldBe 1
    }

    // ── detectMentionTrigger: non-activation ────────────────────────────────

    @Test
    fun `no at-sign does not trigger`() {
        val result = detectMentionTrigger("hello world", cursorPos = 11)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `empty text does not trigger`() {
        val result = detectMentionTrigger("", cursorPos = 0)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `email pattern does not trigger`() {
        // "email@example.com" — @ preceded by non-whitespace
        val result = detectMentionTrigger("email@example.com", cursorPos = 17)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `at-sign preceded by word char does not trigger`() {
        val result = detectMentionTrigger("foo@bar", cursorPos = 7)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `whitespace after at-sign closes mention`() {
        // User typed "@Main " — the space closes the mention
        val result = detectMentionTrigger("@Main ", cursorPos = 6)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `newline after at-sign closes mention`() {
        val result = detectMentionTrigger("@Main\n", cursorPos = 6)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `tab after at-sign closes mention`() {
        val result = detectMentionTrigger("@Main\t", cursorPos = 6)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    // ── detectMentionTrigger: cursor position edge cases ────────────────────

    @Test
    fun `at-sign after cursor is not detected`() {
        // Cursor is before the @ — should not trigger
        val result = detectMentionTrigger("hello @Main", cursorPos = 5)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    @Test
    fun `cursor at at-sign position triggers with empty query`() {
        // Cursor is right at the @ — textBeforeCursor is empty up to @
        // Actually cursor at 4 means textBeforeCursor = "fix " which has no @
        val result = detectMentionTrigger("fix @Main", cursorPos = 4)
        result.active shouldBe false
    }

    @Test
    fun `cursor right after at-sign triggers with empty query`() {
        val result = detectMentionTrigger("fix @Main", cursorPos = 5)
        result.active shouldBe true
        result.query shouldBe ""
        result.startIndex shouldBe 4
    }

    @Test
    fun `cursor past end of text is clamped`() {
        // cursorPos beyond text length should be clamped to text.length
        val result = detectMentionTrigger("@Main", cursorPos = 999)
        result.active shouldBe true
        result.query shouldBe "Main"
        result.startIndex shouldBe 0
    }

    @Test
    fun `negative cursor position is clamped`() {
        val result = detectMentionTrigger("@Main", cursorPos = -1)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    // ── detectMentionTrigger: multiple at-signs ─────────────────────────────

    @Test
    fun `last at-sign before cursor is used`() {
        // "fix @Main @Other" with cursor after "Other" — should use the second @
        val result = detectMentionTrigger("fix @Main @Other", cursorPos = 16)
        result.active shouldBe true
        result.query shouldBe "Other"
        result.startIndex shouldBe 10
    }

    @Test
    fun `first at-sign closed by space, second at-sign active`() {
        // "@Main @Other" — first @ is closed by space, second @ is active
        val result = detectMentionTrigger("@Main @Other", cursorPos = 12)
        result.active shouldBe true
        result.query shouldBe "Other"
        result.startIndex shouldBe 6
    }

    @Test
    fun `closed first mention does not trigger even if second at is in email`() {
        // "@Main foo@bar" — first @ closed by space, second @ preceded by non-whitespace
        val result = detectMentionTrigger("@Main foo@bar", cursorPos = 13)
        result.active shouldBe false
        result.startIndex shouldBe -1
    }

    // ── RecentFile.isOpen ───────────────────────────────────────────────────

    @Test
    fun `RecentFile isOpen defaults to false`() {
        val file = RecentFile(name = "Main.kt", path = "/src/Main.kt")
        file.isOpen shouldBe false
    }

    @Test
    fun `RecentFile isOpen true when explicitly set`() {
        val file = RecentFile(name = "Main.kt", path = "/src/Main.kt", isOpen = true)
        file.isOpen shouldBe true
    }

    @Test
    fun `RecentFile equality includes isOpen`() {
        val file1 = RecentFile(name = "Main.kt", path = "/src/Main.kt", isOpen = true)
        val file2 = RecentFile(name = "Main.kt", path = "/src/Main.kt", isOpen = true)
        val file3 = RecentFile(name = "Main.kt", path = "/src/Main.kt", isOpen = false)
        file1 shouldBe file2
        file1.shouldNotBe(file3)
    }

    @Test
    fun `RecentFile copy preserves isOpen`() {
        val file = RecentFile(name = "Main.kt", path = "/src/Main.kt", isOpen = true)
        val copied = file.copy(name = "Other.kt")
        copied.isOpen shouldBe true
        copied.name shouldBe "Other.kt"
    }

    // ── detectMentionTrigger: punctuation-preceded at-sign ─────────────────

    @Test
    fun `at-sign preceded by open paren does not trigger`() {
        val result = detectMentionTrigger("(@Main", cursorPos = 6)
        result.active shouldBe false
    }

    @Test
    fun `at-sign preceded by comma does not trigger`() {
        val result = detectMentionTrigger(",@Main", cursorPos = 6)
        result.active shouldBe false
    }

    // ── detectMentionTrigger: cursor at start ──────────────────────────────

    @Test
    fun `cursor at start of text with at-sign does not trigger`() {
        val result = detectMentionTrigger("@Main", cursorPos = 0)
        result.active shouldBe false
    }
}