package com.opencode.acp.intelligence.context

import com.opencode.acp.mcp.ToolInfo
import com.opencode.acp.mcp.ToolPermission
import com.opencode.acp.mcp.ToolSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Unit tests for [McpToolsDocGenerator] — the pure markdown generator that
 * produces `intellij-mcp-tools.md` from the live tool registry snapshot.
 *
 * The JetBrains MCP Server exposes tools to `tools/list` with RAW names (no
 * `intellij_` prefix); the OpenCode server adds the prefix when forwarding to
 * the LLM. These tests use the raw names (matching what [ToolRegistry] stores)
 * and verify the generator emits `intellij_<name>` in the output.
 */
class McpToolsDocGeneratorTest {

    private val generator = McpToolsDocGenerator()

    private fun tool(
        name: String,
        description: String = "A tool",
        enabled: Boolean = true,
        permission: ToolPermission = ToolPermission.ALLOW,
        serverName: String = "intellij",
        source: ToolSource = ToolSource.MCP,
    ): ToolInfo = ToolInfo.create(
        name = name,
        description = description,
        source = source,
        serverName = serverName,
        enabled = enabled,
        permission = permission,
    )

    @Test
    fun `generates header and critical parameter rules for non-empty tool set`() {
        val md = generator.generate(listOf(tool("read_file")))
        md shouldContain "# IntelliJ MCP Tools"
        md shouldContain "## Critical parameter rules"
        md shouldContain "project-relative"
        md shouldContain "1-based"
        md shouldContain "Never guess"
    }

    @Test
    fun `emits intellij_ prefix on tool names in output`() {
        // The raw MCP name is "read_file"; the output should show "intellij_read_file".
        val md = generator.generate(listOf(tool("read_file")))
        md shouldContain "`intellij_read_file`"
        md shouldNotContain "| `read_file` |"
    }

    @Test
    fun `emits no-tools stub when MCP tools are absent`() {
        val md = generator.generate(emptyList())
        md shouldContain "## No MCP tools available"
        md shouldNotContain "## Critical parameter rules"
    }

    @Test
    fun `emits no-tools stub when only builtin tools exist`() {
        // Built-in tools (read, edit, bash) have source=BUILTIN and are excluded.
        val md = generator.generate(
            listOf(
                tool("read_file", source = ToolSource.MCP),
                ToolInfo.create("bash", "shell", ToolSource.BUILTIN, "builtin"),
            )
        )
        md shouldContain "`intellij_read_file`"
        md shouldNotContain "`bash`"
        md shouldNotContain "| `intellij_bash` |"
    }

    @Test
    fun `third-party MCP server tools emitted with raw name, no intellij_ prefix`() {
        // A third-party MCP server tool has source=MCP but serverName != intellij.
        // The OpenCode server only adds the `intellij_` prefix to tools from
        // the IntelliJ MCP Server. Third-party tools are called by their raw
        // name. The generator must emit the raw name (not intellij_-prefixed)
        // so the LLM calls the correct tool name.
        val md = generator.generate(listOf(tool("github_create_issue", serverName = "github")))
        md shouldContain "`github_create_issue`"
        md shouldNotContain "`intellij_github_create_issue`"
    }

    @Test
    fun `groups tools into categories`() {
        val md = generator.generate(
            listOf(
                tool("read_file"),
                tool("search_symbol"),
                tool("analyze_calls"),
                tool("rename_refactoring"),
                tool("xdebug_get_stack"),
                tool("git_status"),
            )
        )
        md shouldContain "## File / project operations"
        md shouldContain "## Symbol / code intelligence"
        md shouldContain "## Call analysis"
        md shouldContain "## Refactoring"
        md shouldContain "## Debugger (intellij_xdebug_*)"
        md shouldContain "## VCS / git"
    }

    @Test
    fun `includes permission column from tool enabled state when no permission map given`() {
        val md = generator.generate(
            listOf(
                tool("read_file", enabled = true),
                tool("build_project", enabled = false),
            )
        )
        md shouldContain "| `intellij_read_file` | allow |"
        md shouldContain "| `intellij_build_project` | deny |"
    }

    @Test
    fun `includes permission status section when permission map provided`() {
        val perms = mapOf(
            "read_file" to ToolPermission.ALLOW,
            "build_project" to ToolPermission.DENY,
            "search_symbol" to ToolPermission.ASK,
        )
        val md = generator.generate(
            listOf(
                tool("read_file"),
                tool("build_project"),
                tool("search_symbol"),
            ),
            permissions = perms,
        )
        md shouldContain "## Permission Status"
        md shouldContain "| `intellij_read_file` | allow |"
        md shouldContain "| `intellij_build_project` | deny |"
        md shouldContain "| `intellij_search_symbol` | ask |"
    }

    @Test
    fun `permission map overrides tool enabled state`() {
        val md = generator.generate(
            listOf(tool("read_file", enabled = true)),
            permissions = mapOf("read_file" to ToolPermission.DENY),
        )
        md shouldContain "| `intellij_read_file` | deny |"
    }

    @Test
    fun `always includes apply_patch format section for non-empty tool set`() {
        val md = generator.generate(listOf(tool("read_file")))
        md shouldContain "## apply_patch"
        md shouldContain "*** Begin Patch"
        md shouldContain "*** Update File:"
    }

    @Test
    fun `no-tools stub omits apply_patch section`() {
        val md = generator.generate(emptyList())
        md shouldNotContain "## apply_patch"
    }

    @Test
    fun `tools sorted alphabetically by name`() {
        val md = generator.generate(
            listOf(
                tool("zsearch"),
                tool("aread"),
                tool("mbuild"),
            )
        )
        val aIdx = md.indexOf("intellij_aread")
        val mIdx = md.indexOf("intellij_mbuild")
        val zIdx = md.indexOf("intellij_zsearch")
        (aIdx < mIdx) shouldBe true
        (mIdx < zIdx) shouldBe true
    }

    @Test
    fun `notebook tools grouped into notebooks category`() {
        val md = generator.generate(
            listOf(
                tool("notebookEdit"),
                tool("readNotebook"),
                tool("runNotebookCell"),
            )
        )
        md shouldContain "## Notebooks"
        md shouldContain "`intellij_notebookEdit`"
        md shouldContain "`intellij_readNotebook`"
        md shouldContain "`intellij_runNotebookCell`"
    }
}