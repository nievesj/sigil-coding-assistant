package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unit tests for [ContentBlockSerializer] (TDD §4.2.5).
 *
 * Tests OpenCode→ACP content block serialization:
 * - Each [OpenCodePart] variant maps to a [ContentBlock.Text] with the expected text.
 * - [OpenCodePart.ToolResult] concatenates child part text and prefixes "Error: " when isError.
 * - [toContentBlocks] maps a list of parts to a list of ContentBlocks.
 *
 * No mocking — uses real [OpenCodePart] instances (pure data transforms).
 */
class ContentBlockSerializerTest {

    private val serializer = ContentBlockSerializer()

    // ── Text ───────────────────────────────────────────────────────────────

    @Test
    fun `Text part maps to ContentBlock Text with same text`() {
        val part = OpenCodePart.Text("hello world")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "hello world")
    }

    @Test
    fun `Text part with empty string maps to ContentBlock Text`() {
        val part = OpenCodePart.Text("")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "")
    }

    @Test
    fun `Text part with unicode maps to ContentBlock Text`() {
        val part = OpenCodePart.Text("Hello 世界 🌍")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "Hello 世界 🌍")
    }

    // ── Thinking / Reasoning ───────────────────────────────────────────────

    @Test
    fun `Thinking part maps to ContentBlock Text with thinking text`() {
        val part = OpenCodePart.Thinking("hmm")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "hmm")
    }

    @Test
    fun `Reasoning part maps to ContentBlock Text with reasoning text`() {
        val part = OpenCodePart.Reasoning("why")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "why")
    }

    // ── File / Image / Patch / Agent ───────────────────────────────────────

    @Test
    fun `File part maps to ContentBlock Text with File format`() {
        val part = OpenCodePart.File(mime = "text/plain", url = "file:///x", filename = "a.kt")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[File: a.kt (text/plain)]")
    }

    @Test
    fun `File part without filename falls back to url`() {
        val part = OpenCodePart.File(mime = "text/plain", url = "file:///x")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[File: file:///x (text/plain)]")
    }

    @Test
    fun `Image part maps to ContentBlock Text with Image format`() {
        val part = OpenCodePart.Image(mime = "image/png", url = "file:///img", filename = "pic.png")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[Image: pic.png]")
    }

    @Test
    fun `Image part without filename falls back to url`() {
        val part = OpenCodePart.Image(mime = "image/png", url = "file:///img")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[Image: file:///img]")
    }

    @Test
    fun `Patch part maps to ContentBlock Text with Patch format`() {
        val part = OpenCodePart.Patch(hash = "abc123", files = listOf("a.kt", "b.kt"))
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[Patch: abc123 — 2 file(s)]")
    }

    @Test
    fun `Agent part maps to ContentBlock Text with Agent format`() {
        val part = OpenCodePart.Agent(name = "fixer")
        val block = serializer.toContentBlock(part)
        assertIsText(block, "[Agent: fixer]")
    }

    // ── ToolUse ────────────────────────────────────────────────────────────

    @Test
    fun `ToolUse part maps to ContentBlock Text with Tool call format`() {
        val part = OpenCodePart.ToolUse(
            id = "tc_1",
            name = "bash",
            input = buildJsonObject { put("command", "ls") }
        )
        val block = serializer.toContentBlock(part)
        val text = (block as ContentBlock.Text).text
        text shouldStartWith "Tool call: bash (id: tc_1)"
        text shouldContain "Input:"
        text shouldContain "\"command\":\"ls\""
    }

    @Test
    fun `ToolUse part without input omits Input section`() {
        val part = OpenCodePart.ToolUse(id = "tc_2", name = "read")
        val block = serializer.toContentBlock(part)
        val text = (block as ContentBlock.Text).text
        text shouldBe "Tool call: read (id: tc_2)"
    }

    // ── ToolResult ────────────────────────────────────────────────────────

    @Test
    fun `ToolResult part maps to ContentBlock Text with concatenated result text`() {
        val part = OpenCodePart.ToolResult(
            toolUseId = "tc_1",
            content = listOf(
                OpenCodePart.Text("line one"),
                OpenCodePart.Text("line two")
            )
        )
        val block = serializer.toContentBlock(part)
        assertIsText(block, "line one\nline two")
    }

    @Test
    fun `ToolResult with isError prefixes Error`() {
        val part = OpenCodePart.ToolResult(
            toolUseId = "tc_1",
            content = listOf(OpenCodePart.Text("boom")),
            isError = true
        )
        val block = serializer.toContentBlock(part)
        assertIsText(block, "Error: boom")
    }

    @Test
    fun `ToolResult with mixed child parts concatenates representations`() {
        val part = OpenCodePart.ToolResult(
            toolUseId = "tc_1",
            content = listOf(
                OpenCodePart.Text("text content"),
                OpenCodePart.File(mime = "text/plain", url = "file:///x", filename = "a.kt"),
                OpenCodePart.Image(mime = "image/png", url = "file:///img"),
                OpenCodePart.Patch(hash = "h", files = listOf("a.kt")),
                OpenCodePart.Agent(name = "fixer"),
                OpenCodePart.ToolUse(id = "tc_2", name = "read"),
                OpenCodePart.ToolResult(toolUseId = "tc_3", content = emptyList()),
                OpenCodePart.StepStart(),
                OpenCodePart.StepFinish(),
                OpenCodePart.Thinking("hmm"),
                OpenCodePart.Reasoning("why"),
                OpenCodePart.Retry(attempt = 1, maxAttempts = 3),
                OpenCodePart.Compaction(),
                OpenCodePart.Snapshot(id = "snap1"),
                OpenCodePart.Subtask(prompt = "do thing"),
                OpenCodePart.Unknown("weird", buildJsonObject {})
            )
        )
        val block = serializer.toContentBlock(part)
        val text = (block as ContentBlock.Text).text
        text shouldContain "text content"
        text shouldContain "[File: a.kt]"
        text shouldContain "[Image: file:///img]"
        text shouldContain "[Patch: h]"
        text shouldContain "[Agent: fixer]"
        text shouldContain "[ToolUse: read (tc_2)]"
        text shouldContain "[ToolResult: tc_3]"
        text shouldContain "[StepStart]"
        text shouldContain "[StepFinish]"
        text shouldContain "hmm"
        text shouldContain "why"
        text shouldContain "[Retry: 1/3]"
        text shouldContain "[Compaction]"
        text shouldContain "[Snapshot: snap1]"
        text shouldContain "[Subtask: do thing]"
        text shouldContain "[weird]"
    }

    // ── Step / Retry / Compaction / Snapshot / Subtask / Unknown ──────────

    @Test
    fun `StepStart maps to ContentBlock Text with StepStart marker`() {
        val block = serializer.toContentBlock(OpenCodePart.StepStart())
        assertIsText(block, "[StepStart]")
    }

    @Test
    fun `StepFinish maps to ContentBlock Text with StepFinish marker`() {
        val block = serializer.toContentBlock(OpenCodePart.StepFinish())
        assertIsText(block, "[StepFinish]")
    }

    @Test
    fun `Retry part maps to ContentBlock Text with Retry format`() {
        val block = serializer.toContentBlock(OpenCodePart.Retry(attempt = 2, maxAttempts = 5))
        assertIsText(block, "[Retry: 2/5]")
    }

    @Test
    fun `Compaction part maps to ContentBlock Text with Compaction marker`() {
        val block = serializer.toContentBlock(OpenCodePart.Compaction())
        assertIsText(block, "[Compaction]")
    }

    @Test
    fun `Snapshot part maps to ContentBlock Text with Snapshot format`() {
        val block = serializer.toContentBlock(OpenCodePart.Snapshot(id = "snap1"))
        assertIsText(block, "[Snapshot: snap1]")
    }

    @Test
    fun `Subtask part with description maps to ContentBlock Text with Subtask format`() {
        val block = serializer.toContentBlock(OpenCodePart.Subtask(prompt = "p", description = "desc"))
        assertIsText(block, "[Subtask: desc]")
    }

    @Test
    fun `Subtask part without description falls back to prompt`() {
        val block = serializer.toContentBlock(OpenCodePart.Subtask(prompt = "do thing"))
        assertIsText(block, "[Subtask: do thing]")
    }

    @Test
    fun `Unknown part maps to ContentBlock Text with type marker`() {
        val block = serializer.toContentBlock(
            OpenCodePart.Unknown("weird_type", buildJsonObject { put("foo", "bar") })
        )
        assertIsText(block, "[weird_type]")
    }

    // ── toContentBlocks (list) ────────────────────────────────────────────

    @Test
    fun `toContentBlocks with empty list returns empty list`() {
        serializer.toContentBlocks(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `toContentBlocks maps each part to a ContentBlock Text`() {
        val parts = listOf(
            OpenCodePart.Text("a"),
            OpenCodePart.Thinking("b"),
            OpenCodePart.Agent(name = "fixer")
        )
        val blocks = serializer.toContentBlocks(parts)
        blocks.size shouldBe 3
        assertIsText(blocks[0], "a")
        assertIsText(blocks[1], "b")
        assertIsText(blocks[2], "[Agent: fixer]")
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun assertIsText(block: ContentBlock, expected: String) {
        val text = (block as? ContentBlock.Text)?.text
            ?: error("Expected ContentBlock.Text but got ${block::class.simpleName}")
        text shouldBe expected
    }
}