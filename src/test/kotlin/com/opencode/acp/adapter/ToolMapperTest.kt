package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolKind
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        "remove, DELETE",
        "delete, DELETE",
        "move, MOVE",
        "rename, MOVE",
        "read, READ",
        "list, OTHER",
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

    @ParameterizedTest(name = "unknown or edge-case ''{0}'' maps to OTHER")
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
        assertTrue(mappings.containsKey(ToolKind.DELETE))
        assertTrue(mappings.containsKey(ToolKind.MOVE))
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
    fun `allMappings READ contains read and lsp`() {
        val read = ToolMapper.allMappings()[ToolKind.READ]!!
        read shouldBe listOf("read", "lsp")
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

    @Test
    fun `allMappings DELETE contains remove and delete`() {
        val delete = ToolMapper.allMappings()[ToolKind.DELETE]!!
        delete shouldBe listOf("remove", "delete")
    }

    @Test
    fun `allMappings MOVE contains move and rename`() {
        val move = ToolMapper.allMappings()[ToolKind.MOVE]!!
        move shouldBe listOf("move", "rename")
    }

    // ── detectKindFromInput ───────────────────────────────────────────────

    @ParameterizedTest(name = "detectKindFromInput {0} -> {1}")
    @CsvSource(
        "command, EXECUTE",
        "file_path, READ",
        "filePath, READ",
        "path, READ",
        "pattern, SEARCH",
        "query, SEARCH",
    )
    fun `detectKindFromInput maps single-key inputs to expected kinds`(key: String, expectedKind: String) {
        val input = JsonObject(mapOf(key to JsonPrimitive("value")))
        val expected = ToolKind.valueOf(expectedKind)
        ToolMapper.detectKindFromInput(input) shouldBe expected
    }

    @Test
    fun `detectKindFromInput returns OTHER for null and empty`() {
        ToolMapper.detectKindFromInput(null) shouldBe ToolKind.OTHER
        ToolMapper.detectKindFromInput(JsonObject(emptyMap())) shouldBe ToolKind.OTHER
    }

    @Test
    fun `detectKindFromInput returns EDIT for file_path with old_string and new_string`() {
        val input = JsonObject(mapOf(
            "file_path" to JsonPrimitive("foo.kt"),
            "old_string" to JsonPrimitive("a"),
            "new_string" to JsonPrimitive("b"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.EDIT
    }

    @Test
    fun `detectKindFromInput returns SEARCH for pattern with path (not READ)`() {
        // Regression test: previously misclassified as READ because `path` matched the READ branch first.
        val input = JsonObject(mapOf(
            "pattern" to JsonPrimitive("foo"),
            "path" to JsonPrimitive("/some/dir"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns SEARCH for query with path`() {
        val input = JsonObject(mapOf(
            "query" to JsonPrimitive("foo"),
            "path" to JsonPrimitive("/some/dir"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns SEARCH for glob with path`() {
        // Glob tool: directory in 'path', file pattern in 'glob'.
        val input = JsonObject(mapOf(
            "path" to JsonPrimitive("/some/dir"),
            "glob" to JsonPrimitive("*.kt"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns SEARCH for include with path`() {
        val input = JsonObject(mapOf(
            "path" to JsonPrimitive("/some/dir"),
            "include" to JsonPrimitive("*.kt"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns SEARCH for glob only`() {
        val input = JsonObject(mapOf("glob" to JsonPrimitive("*.kt")))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns READ for path only without glob`() {
        // path-only without glob/include is still READ (could be a file read).
        val input = JsonObject(mapOf("path" to JsonPrimitive("/some/file.kt")))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.READ
    }

    @Test
    fun `detectKindFromInput returns EXECUTE when command and file_path both present`() {
        // Ambiguous: EXECUTE wins per the when-branch order.
        val input = JsonObject(mapOf(
            "command" to JsonPrimitive("ls"),
            "file_path" to JsonPrimitive("/some/dir"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.EXECUTE
    }

    @Test
    fun `detectKindFromInput returns SEARCH for path with content when pattern also present`() {
        // A search tool that uses 'content' for the search text (not file content)
        // should be classified as SEARCH if pattern/query is present, not EDIT.
        val input = JsonObject(mapOf(
            "path" to JsonPrimitive("/some/dir"),
            "content" to JsonPrimitive("search text"),
            "pattern" to JsonPrimitive("foo"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }

    @Test
    fun `detectKindFromInput returns EDIT for path with content and no search keys`() {
        // path + content without pattern/query/glob/include is EDIT (file write).
        val input = JsonObject(mapOf(
            "path" to JsonPrimitive("/some/file.kt"),
            "content" to JsonPrimitive("new file content"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.EDIT
    }

    @Test
    fun `detectKindFromInput returns EXECUTE when command and pattern both present`() {
        // EXECUTE wins per the when-branch order — command is checked first.
        // This test locks in the priority so a future reordering doesn't silently change it.
        val input = JsonObject(mapOf(
            "command" to JsonPrimitive("grep -r foo ."),
            "pattern" to JsonPrimitive("foo"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.EXECUTE
    }

    @Test
    fun `detectKindFromInput returns EXECUTE when command and glob both present`() {
        // EXECUTE wins per the when-branch order — command is checked first.
        val input = JsonObject(mapOf(
            "command" to JsonPrimitive("ls"),
            "glob" to JsonPrimitive("*.kt"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.EXECUTE
    }

    @Test
    fun `detectKindFromInput returns SEARCH when file_path and pattern both present`() {
        // SEARCH wins per the when-branch order — pattern is checked before READ.
        val input = JsonObject(mapOf(
            "file_path" to JsonPrimitive("foo.kt"),
            "pattern" to JsonPrimitive("bar"),
        ))
        ToolMapper.detectKindFromInput(input) shouldBe ToolKind.SEARCH
    }
}