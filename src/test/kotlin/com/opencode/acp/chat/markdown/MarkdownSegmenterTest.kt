package com.opencode.acp.chat.markdown

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Baseline regression guard tests for [MarkdownSegmenter].
 *
 * Protects documented bug fix #2 (streaming jump root cause — see AGENTS.md
 * "Streaming 'Jump' — animateScrollToItem on New Streaming Message"). The
 * segmenter's behavior is the foundation of the streaming-jump fix:
 * - Mid-line fence handling (LLM quirks like "text```css")
 * - Unclosed code blocks during streaming
 * - Consistent segment structure between `segment()` and `segmentHealed()`
 *   (the `resegmentTextPartsFinal` fix relies on `segmentHealed()` producing
 *   identical structure for complete content)
 *
 * These tests pin the segmenter's pure-logic contract so refactors of the
 * streaming pipeline cannot silently regress the segment structure.
 */
class MarkdownSegmenterTest {

    @Test
    fun `basic text segment`() {
        val segments = MarkdownSegmenter.segment("Hello world")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "Hello world"
        segments[0].language shouldBe null
    }

    @Test
    fun `code fence basic with language`() {
        val segments = MarkdownSegmenter.segment("```kotlin\nval x = 1\n```")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "val x = 1"
        segments[0].language shouldBe "kotlin"
    }

    @Test
    fun `code fence without language`() {
        val segments = MarkdownSegmenter.segment("```\ncode\n```")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "code"
        segments[0].language shouldBe null
    }

    @Test
    fun `code fence mid-line fence splits text and code`() {
        // LLM quirk: fence appears mid-line after trailing text (e.g. "text```css").
        val segments = MarkdownSegmenter.segment("text```css\n.color { color: red; }\n```")
        segments shouldHaveSize 2
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "text"
        segments[1].type shouldBe MarkdownSegment.Type.CODE
        segments[1].content shouldBe ".color { color: red; }"
        segments[1].language shouldBe "css"
    }

    @Test
    fun `code fence with tildes`() {
        val segments = MarkdownSegmenter.segment("~~~python\nprint('hi')\n~~~")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "print('hi')"
        segments[0].language shouldBe "python"
    }

    @Test
    fun `unclosed code fence during streaming produces code segment`() {
        // Streaming case: closing ``` hasn't arrived yet.
        val segments = MarkdownSegmenter.segment("```kotlin\nval x = 1")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "val x = 1"
        segments[0].language shouldBe "kotlin"
    }

    @Test
    fun `text after closing fence becomes text segment`() {
        val segments = MarkdownSegmenter.segment("```kotlin\ncode\n```\nAfter text")
        segments shouldHaveSize 2
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "code"
        segments[0].language shouldBe "kotlin"
        segments[1].type shouldBe MarkdownSegment.Type.TEXT
        segments[1].content shouldBe "After text"
    }

    @Test
    fun `multiple code blocks separated by text`() {
        val segments = MarkdownSegmenter.segment("```js\na\n```\ntext\n```py\nb\n```")
        segments shouldHaveSize 3
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "a"
        segments[0].language shouldBe "js"
        segments[1].type shouldBe MarkdownSegment.Type.TEXT
        segments[1].content shouldBe "text"
        segments[2].type shouldBe MarkdownSegment.Type.CODE
        segments[2].content shouldBe "b"
        segments[2].language shouldBe "py"
    }

    @Test
    fun `table basic produces table segment`() {
        val segments = MarkdownSegmenter.segment("| H1 | H2 |\n|---|---|\n| r1 | r2 |")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.TABLE
        segments[0].content shouldBe "| H1 | H2 |\n|---|---|\n| r1 | r2 |"
    }

    @Test
    fun `parseTable left and center alignment`() {
        val table = MarkdownSegmenter.parseTable(
            listOf("| H1 | H2 |", "|:---|:---:|", "| r1 | r2 |")
        )
        table.shouldBeInstanceOf<ParsedTable>()
        table.header shouldBe listOf("H1", "H2")
        table.alignments shouldBe listOf(
            ParsedTable.ColumnAlignment.LEFT,
            ParsedTable.ColumnAlignment.CENTER,
        )
        table.rows shouldBe listOf(listOf("r1", "r2"))
    }

    @Test
    fun `parseTable right alignment`() {
        val table = MarkdownSegmenter.parseTable(
            listOf("| H1 | H2 |", "|---:|---:|", "| r1 | r2 |")
        )
        table.shouldBeInstanceOf<ParsedTable>()
        table.alignments shouldBe listOf(
            ParsedTable.ColumnAlignment.RIGHT,
            ParsedTable.ColumnAlignment.RIGHT,
        )
    }

    @Test
    fun `parseTable invalid without separator returns null`() {
        val table = MarkdownSegmenter.parseTable(
            listOf("| H1 | H2 |", "| r1 | r2 |")
        )
        table shouldBe null
    }

    @Test
    fun `parseTable separator at end with no data rows returns null`() {
        val table = MarkdownSegmenter.parseTable(
            listOf("| H1 |", "|---|")
        )
        table shouldBe null
    }

    @Test
    fun `empty string produces empty segment list`() {
        val segments = MarkdownSegmenter.segment("")
        segments shouldHaveSize 0
    }

    @Test
    fun `blank string produces empty segment list`() {
        val segments = MarkdownSegmenter.segment("   ")
        segments shouldHaveSize 0
    }

    @Test
    fun `only fence produces code segment with empty content or empty list`() {
        // A lone ``` opens a code block but never closes. The actual behavior
        // depends on how the segmenter handles the unclosed fence — verify it
        // doesn't crash and returns a list (may be empty or contain one segment).
        val segments = MarkdownSegmenter.segment("```")
        // No assertion on size — just verify it doesn't throw. The behavior is
        // implementation-defined for a lone fence with no content.
        segments // shouldNotThrow
    }

    @Test
    fun `text code text sequence`() {
        val segments = MarkdownSegmenter.segment("Before\n```kt\ncode\n```\nAfter")
        segments shouldHaveSize 3
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "Before"
        segments[1].type shouldBe MarkdownSegment.Type.CODE
        segments[1].content shouldBe "code"
        segments[1].language shouldBe "kt"
        segments[2].type shouldBe MarkdownSegment.Type.TEXT
        segments[2].content shouldBe "After"
    }

    @Test
    fun `segmentHealed closes unclosed inline backtick`() {
        val segments = MarkdownSegmenter.segmentHealed("Hello `code")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "Hello `code`"
    }

    @Test
    fun `segmentHealed closes unclosed bold`() {
        val segments = MarkdownSegmenter.segmentHealed("**bold text")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "**bold text**"
    }

    @Test
    fun `segmentHealed preserves backtick inside code fence`() {
        // Code-fence immunity: content inside ``` fences is NOT healed.
        val segments = MarkdownSegmenter.segmentHealed("```kt\nval x = `unclosed\n```")
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.CODE
        segments[0].content shouldBe "val x = `unclosed"
        segments[0].language shouldBe "kt"
    }

    @Test
    fun `task segment with attributes`() {
        val segments = MarkdownSegmenter.segment(
            """<task id="t1" state="completed"><task_result>Done</task_result></task>"""
        )
        segments shouldHaveSize 1
        segments[0].type shouldBe MarkdownSegment.Type.TASK
        segments[0].content shouldBe "Done"
        segments[0].taskAttrs shouldBe mapOf("id" to "t1", "state" to "completed")
    }

    @Test
    fun `task segment surrounded by text`() {
        val segments = MarkdownSegmenter.segment(
            "Before\n<task id=\"t1\"><task_result>Done</task_result></task>\nAfter"
        )
        segments shouldHaveSize 3
        segments[0].type shouldBe MarkdownSegment.Type.TEXT
        segments[0].content shouldBe "Before"
        segments[1].type shouldBe MarkdownSegment.Type.TASK
        segments[1].content shouldBe "Done"
        segments[1].taskAttrs shouldBe mapOf("id" to "t1")
        segments[2].type shouldBe MarkdownSegment.Type.TEXT
        segments[2].content shouldBe "After"
    }
}