package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [ToolMapper] (TDD §4.2.6 — migrated to parameterized tests).
 *
 * The 21 near-identical test methods from the original file are now consolidated
 * into parameterized tests using @CsvSource. The allMappings() test remains
 * non-parameterized because it verifies the full map structure.
 */
class ToolMapperTest {

    // ── toAcpKind parameterized tests ─────────────────────────────────────
    // Format: toolName, expectedToolKind

    @ParameterizedTest(name = "{0} maps to {1}")
    @CsvSource(
        "bash, EXECUTE",
        "shell, EXECUTE",
        "edit, EDIT",
        "apply_patch, EDIT",
        "write, EDIT",
        "read, READ",
        "list, READ",
        "lsp, READ",
        "grep, SEARCH",
        "glob, SEARCH",
        "find, SEARCH",
        "websearch, FETCH",
        "webfetch, FETCH",
        "question, THINK",
        "skill, OTHER",
        "todowrite, OTHER",
        "task, OTHER",
        "external_directory, OTHER",
        "unknown_tool, OTHER",
    )
    fun `toAcpKind maps tool names to expected kinds`(toolName: String, expectedKind: String) {
        val expected = ToolKind.valueOf(expectedKind)
        ToolMapper.toAcpKind(toolName) shouldBe expected
    }

    @ParameterizedTest(name = "uppercase {0} maps to {1} (case insensitive)")
    @CsvSource(
        "BASH, EXECUTE",
        "Edit, EDIT",
        "READ, READ",
        "GREP, SEARCH",
    )
    fun `toAcpKind is case insensitive`(toolName: String, expectedKind: String) {
        val expected = ToolKind.valueOf(expectedKind)
        ToolMapper.toAcpKind(toolName) shouldBe expected
    }

    @ParameterizedTest(name = "empty or blank ''{0}'' maps to OTHER")
    @ValueSource(strings = ["", "   ", "totally_unknown_tool_xyz", "12345"])
    fun `toAcpKind maps unknown and edge-case names to OTHER`(toolName: String) {
        ToolMapper.toAcpKind(toolName) shouldBe ToolKind.OTHER
    }

    // ── allMappings (non-parameterized — verifies full map structure) ─────

    @Test
    fun `allMappings returns expected tool kinds`() {
        val mappings = ToolMapper.allMappings()
        assertTrue(mappings.containsKey(ToolKind.EXECUTE))
        assertTrue(mappings.containsKey(ToolKind.EDIT))
        assertTrue(mappings.containsKey(ToolKind.READ))
        assertTrue(mappings.containsKey(ToolKind.SEARCH))
        assertTrue(mappings.containsKey(ToolKind.FETCH))
        assertTrue(mappings.containsKey(ToolKind.THINK))
        assertTrue(mappings.containsKey(ToolKind.OTHER))
    }

    @Test
    fun `allMappings EXECUTE contains bash and shell`() {
        val execute = ToolMapper.allMappings()[ToolKind.EXECUTE]!!
        execute shouldBe listOf("bash", "shell")
    }

    @Test
    fun `allMappings EDIT contains edit apply_patch and write`() {
        val edit = ToolMapper.allMappings()[ToolKind.EDIT]!!
        edit shouldBe listOf("edit", "apply_patch", "write")
    }

    @Test
    fun `allMappings READ contains read list and lsp`() {
        val read = ToolMapper.allMappings()[ToolKind.READ]!!
        read shouldBe listOf("read", "list", "lsp")
    }

    @Test
    fun `allMappings SEARCH contains grep glob and find`() {
        val search = ToolMapper.allMappings()[ToolKind.SEARCH]!!
        search shouldBe listOf("grep", "glob", "find")
    }

    @Test
    fun `allMappings FETCH contains websearch and webfetch`() {
        val fetch = ToolMapper.allMappings()[ToolKind.FETCH]!!
        fetch shouldBe listOf("websearch", "webfetch")
    }

    @Test
    fun `allMappings THINK contains question`() {
        val think = ToolMapper.allMappings()[ToolKind.THINK]!!
        think shouldBe listOf("question")
    }

    @Test
    fun `allMappings OTHER contains skill todowrite task and external_directory`() {
        val other = ToolMapper.allMappings()[ToolKind.OTHER]!!
        other shouldBe listOf("skill", "todowrite", "task", "external_directory")
    }
}