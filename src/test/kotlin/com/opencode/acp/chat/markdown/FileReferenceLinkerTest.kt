package com.opencode.acp.chat.markdown

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class FileReferenceLinkerTest {

    @Test
    fun `relative path with line number is linkified`() {
        val input = "I edited src/Foo.kt:42 to fix the bug."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "[src/Foo.kt:42](opencode-file://src%2FFoo.kt?line=42)"
    }

    @Test
    fun `relative path with line and column is linkified`() {
        val input = "See src/Foo.kt:42:10 for details."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "line=42"
        result shouldContain "column=10"
    }

    @Test
    fun `hash line format is linkified`() {
        val input = "Check src/Foo.kt#L42"
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "[src/Foo.kt#L42](opencode-file://src%2FFoo.kt?line=42)"
    }

    @Test
    fun `path without line is linkified`() {
        val input = "Look at src/Foo.kt for context."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "[src/Foo.kt](opencode-file://src%2FFoo.kt)"
    }

    @Test
    fun `bare filename with line is linkified`() {
        val input = "The issue is in Foo.kt:42."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "line=42"
    }

    @Test
    fun `Windows absolute path is linkified`() {
        val input = "See C:\\src\\Foo.kt:42 for the fix."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "opencode-file://"
        result shouldContain "line=42"
    }

    @Test
    fun `Unix absolute path is linkified`() {
        val input = "Check /home/user/src/Foo.kt:42"
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "opencode-file://"
        result shouldContain "line=42"
    }

    @Test
    fun `URLs are not linkified`() {
        val input = "Visit https://example.com:8080/path for docs."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // no changes — the :8080 should not become a file link
    }

    @Test
    fun `http URL with path and extension is not linkified`() {
        val input = "See https://example.com/page.html:42 for info."
        val result = FileReferenceLinker.linkify(input)
        // The page.html:42 part should NOT be linkified because it's part of a URL
        result shouldNotContain "opencode-file://"
    }

    @Test
    fun `time-like patterns are not linkified`() {
        val input = "The time is 12:30 and the meeting is at 1.2:3."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // no changes
    }

    @Test
    fun `version numbers are not linkified`() {
        val input = "Upgraded from version 1.2:3 to 2.0."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input
    }

    @Test
    fun `inline code spans are not linkified`() {
        val input = "Use the `src/Foo.kt:42` syntax."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // protected by backticks
    }

    @Test
    fun `existing markdown links are not double-linkified`() {
        val input = "See [the docs](https://example.com/Foo.kt:42) for info."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // protected by markdown link syntax
    }

    @Test
    fun `multiple file references in one line are all linkified`() {
        val input = "Changed src/Foo.kt:10 and src/Bar.kt:20."
        val result = FileReferenceLinker.linkify(input)
        result shouldContain "opencode-file://src%2FFoo.kt?line=10"
        result shouldContain "opencode-file://src%2FBar.kt?line=20"
    }

    @Test
    fun `blank text returns unchanged`() {
        FileReferenceLinker.linkify("") shouldBe ""
        FileReferenceLinker.linkify("   ") shouldBe "   "
    }

    @Test
    fun `text with no file refs returns unchanged`() {
        val input = "This is just a normal sentence with no file references."
        FileReferenceLinker.linkify(input) shouldBe input
    }

    @Test
    fun `parseFileUrl parses path and line`() {
        val ref = FileReferenceLinker.parseFileUrl("opencode-file://src%2FFoo.kt?line=42")
        ref shouldBe FileReferenceLinker.FileRef("src/Foo.kt", 42, null)
    }

    @Test
    fun `parseFileUrl parses path line and column`() {
        val ref = FileReferenceLinker.parseFileUrl("opencode-file://src%2FFoo.kt?line=42&column=10")
        ref shouldBe FileReferenceLinker.FileRef("src/Foo.kt", 42, 10)
    }

    @Test
    fun `parseFileUrl parses path only`() {
        val ref = FileReferenceLinker.parseFileUrl("opencode-file://src%2FFoo.kt")
        ref shouldBe FileReferenceLinker.FileRef("src/Foo.kt", null, null)
    }

    @Test
    fun `parseFileUrl returns null for non-opencode-file URLs`() {
        FileReferenceLinker.parseFileUrl("https://example.com") shouldBe null
        FileReferenceLinker.parseFileUrl("file:///path") shouldBe null
        FileReferenceLinker.parseFileUrl("") shouldBe null
    }

    @Test
    fun `parseFileUrl handles Windows backslash paths`() {
        val ref = FileReferenceLinker.parseFileUrl("opencode-file://C%3A%5Csrc%5CFoo.kt?line=42")
        ref shouldBe FileReferenceLinker.FileRef("C:\\src\\Foo.kt", 42, null)
    }

    @Test
    fun `file ref in markdown link text is not double-linkified`() {
        val input = "See [Foo.kt:42](https://example.com) for info."
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // protected by markdown link syntax
    }

    @Test
    fun `path traversal sequences are NOT linkified - defense in depth`() {
        val input = "See ../../../etc/secrets.txt:42"
        val result = FileReferenceLinker.linkify(input)
        // Path traversal sequences must NOT be linkified — rendering them as clickable
        // links is a social-engineering vector for prompt injection. The click handler
        // also validates, but the link should not be rendered clickable in the first place.
        result shouldNotContain "opencode-file://"
        result shouldBe input  // returned unchanged
    }

    @Test
    fun `long path does not cause catastrophic backtracking`() {
        val longPath = "a".repeat(10000) + ".kt:42"
        val input = "See $longPath for details."
        val startTime = System.currentTimeMillis()
        val result = FileReferenceLinker.linkify(input)
        val elapsed = System.currentTimeMillis() - startTime
        // Should complete in under 1 second (not catastrophic backtracking)
        (elapsed < 1000) shouldBe true
        result shouldContain "opencode-file://"
    }

    @Test
    fun `path with null bytes is handled safely`() {
        val input = "See src\u0000/Foo.kt:42"
        val result = FileReferenceLinker.linkify(input)
        // Should not crash — null bytes are handled by the regex as non-word chars
        // The result may or may not linkify, but it must not crash
    }

    @Test
    fun `file refs inside fenced code blocks are not linkified`() {
        val input = "```\nsrc/Foo.kt:42\n```"
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // protected by fenced code block
    }

    @Test
    fun `file refs after unterminated fence are not linkified`() {
        val input = "```\nsrc/Foo.kt:42\n"
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // unterminated fence protects to end of string
    }

    @Test
    fun `file ref inside inline code inside fenced block is not linkified`() {
        val input = "```\nUse `src/Foo.kt:42` here\n```"
        val result = FileReferenceLinker.linkify(input)
        result shouldBe input  // protected by fenced code block
    }

    @Test
    fun `file ref inside markdown link URL with backtick is not linkified`() {
        // Edge case: a markdown link whose URL contains a backtick
        // The PROTECTED_REGION_REGEX should handle this, but it's an overlapping region case
        val input = "See [docs](https://example.com/`code`) for info."
        val result = FileReferenceLinker.linkify(input)
        // The markdown link should be protected — no opencode-file:// links should appear
        result shouldNotContain "opencode-file://"
    }
}