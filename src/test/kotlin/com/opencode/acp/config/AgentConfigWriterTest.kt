package com.opencode.acp.config

import com.opencode.acp.config.settings.CouncilMember
import com.opencode.acp.config.settings.OpenCodeAgentSettingsState
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import com.opencode.acp.mcp.McpConfigWriter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for [AgentConfigWriter] (TDD §4 — Custom Agents).
 *
 * Uses JUnit5 @TempDir for real filesystem operations — no mocking of the
 * filesystem. [OpenCodeAgentSettingsState] is constructed directly (no
 * application context needed for field access). [McpConfigWriter] is a real
 * instance pointed at the temp directory.
 */
class AgentConfigWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newSettings(
        enableCodingAssistant: Boolean = true,
        enableCouncil: Boolean = false,
        taskAllowedAgents: List<String> = listOf("explore", "general"),
        councilMembers: List<CouncilMember> = emptyList()
    ): OpenCodeAgentSettingsState = OpenCodeAgentSettingsState().apply {
        this.enableCodingAssistant = enableCodingAssistant
        this.enableCouncil = enableCouncil
        this.taskAllowedAgents = java.util.ArrayList(taskAllowedAgents)
        this.councilMembers = java.util.ArrayList(councilMembers)
    }

    private fun newWriter(settings: OpenCodeAgentSettingsState): AgentConfigWriter {
        val mcpSettings = OpenCodeMcpSettingsState()
        val mcpWriter = McpConfigWriter(tempDir, mcpSettings)
        return AgentConfigWriter(tempDir, settings, mcpWriter)
    }

    private fun agentsDir(): Path = tempDir.resolve(AgentConstants.AGENTS_DIR)

    private fun codingAssistantFile(): Path = agentsDir().resolve("${AgentConstants.CODING_ASSISTANT_AGENT_NAME}.md")

    private fun councilFile(): Path = agentsDir().resolve("${AgentConstants.COUNCIL_AGENT_NAME}.md")

    private fun readAgentFile(path: Path): String =
        Files.readString(path)

    // ── coding-assistant frontmatter ──────────────────────────────────

    @Test
    fun `writeCodingAssistant with MCP enabled writes valid markdown with prompt and task allowlist`() {
        val writer = newWriter(newSettings())
        val result = writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        result shouldBe true

        val file = codingAssistantFile()
        Files.exists(file) shouldBe true
        val content = readAgentFile(file)

        // Ownership marker first line
        content shouldContain AgentConstants.OWNERSHIP_MARKER
        content shouldContain "mode: primary"
        // permission: block present with `task` delegation allowlist nested under it.
        // Per-tool allow/deny is NOT in the agent file — it is the user's Settings
        // configuration, written to opencode.json by McpConfigWriter.writeToolPermissions.
        content shouldContain "permission:"
        // Task delegation allowlist nested under permission.task
        content shouldContain "task:"
        content shouldContain "\"explore\": \"allow\""
        content shouldContain "\"general\": \"allow\""
        // The agent file must NOT hardcode per-tool permissions (that's opencode.json's job)
        content shouldNotContain "read: allow"
        content shouldNotContain "read: deny"
        content shouldNotContain "bash: allow"
        content shouldNotContain "bash: deny"
        content shouldNotContain "intellij_search_symbol: allow"
        // Prompt body present — references AGENTS.md and mandates intellij_* for intelligence
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA."
        content shouldContain "Mandatory Research Phase"
        content shouldContain "AGENTS.md"
        content shouldContain "Retry On Failure"
        // References the .opencode/context/ project context files
        content shouldContain ".opencode/context/repo-structure.md"
        content shouldContain ".opencode/context/intellij-mcp-tools.md"
    }

    @Test
    fun `writeCodingAssistant frontmatter has no duplicate YAML keys`() {
        val writer = newWriter(newSettings())
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        val content = readAgentFile(codingAssistantFile())

        // Extract frontmatter (between the two --- lines, skipping the ownership marker)
        val lines = content.lines()
        val firstFence = lines.indexOfFirst { it.trim() == "---" }
        val secondFence = (firstFence + 1 until lines.size).first { lines[it].trim() == "---" }
        val frontmatterLines = lines.subList(firstFence + 1, secondFence)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        // Parse key names (strip the value after the colon, strip quotes)
        val keys = frontmatterLines.mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx < 0) null else line.substring(0, idx).trim().trim('"')
        }
        // Group keys and check for duplicates
        val duplicateKeys = keys.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateKeys shouldBe emptyMap()
    }

    @Test
    fun `writeCodingAssistant with MCP disabled writes frontmatter WITHOUT per-tool permissions`() {
        val writer = newWriter(newSettings())
        val result = writer.writeCodingAssistant(isIntellijMcpEnabled = false)
        result shouldBe true

        val content = readAgentFile(codingAssistantFile())
        content shouldContain "mode: primary"
        // permission: block present with `task` delegation allowlist nested under it.
        // Per-tool allow/deny is NOT in the agent file regardless of MCP state —
        // it is the user's Settings configuration (opencode.json is the source of truth).
        content shouldContain "permission:"
        content shouldContain "task:"
        // The agent file must NOT hardcode per-tool permissions
        content shouldNotContain "read: allow"
        content shouldNotContain "read: deny"
        content shouldNotContain "bash: allow"
        content shouldNotContain "bash: deny"
        for (tool in AgentConstants.INTELLIJ_TOOL_NAMES) {
            content shouldNotContain "$tool: allow"
        }
        // Prompt body is the same intellij-exclusive prompt (references AGENTS.md)
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA."
        content shouldContain "AGENTS.md"
        content shouldContain ".opencode/context/repo-structure.md"
        content shouldContain ".opencode/context/intellij-mcp-tools.md"
    }

    // ── task permission YAML ──────────────────────────────────────────

    @Test
    fun `buildTaskPermissionYaml generates deny-all plus one allow per selected agent`() {
        val settings = newSettings(enableCouncil = true, taskAllowedAgents = listOf("explore", "general", "council"))
        val writer = newWriter(settings)
        val yaml = writer.buildTaskPermissionYaml()

        yaml shouldContain "  task:"
        yaml shouldContain "    \"*\": \"deny\""
        yaml shouldContain "    \"explore\": \"allow\""
        yaml shouldContain "    \"general\": \"allow\""
        yaml shouldContain "    \"council\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml omits council when enableCouncil is false even if in taskAllowedAgents`() {
        val settings = newSettings(enableCouncil = false, taskAllowedAgents = listOf("explore", "general", "council"))
        val writer = newWriter(settings)
        val yaml = writer.buildTaskPermissionYaml()

        yaml shouldContain "    \"explore\": \"allow\""
        yaml shouldContain "    \"general\": \"allow\""
        yaml shouldNotContain "\"council\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml includes council when enableCouncil is true and council in taskAllowedAgents`() {
        val settings = newSettings(enableCouncil = true, taskAllowedAgents = listOf("explore", "general", "council"))
        val writer = newWriter(settings)
        val yaml = writer.buildTaskPermissionYaml()

        yaml shouldContain "    \"council\": \"allow\""
    }

    // ── council prompt ────────────────────────────────────────────────

    @Test
    fun `writeCouncil embeds configured member list in prompt body`() {
        val members = listOf(
            CouncilMember("anthropic", "claude-sonnet-4"),
            CouncilMember("openai", "gpt-4o")
        )
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        val result = writer.writeCouncil()
        result shouldBe true

        val content = readAgentFile(councilFile())
        content shouldContain "- anthropic/claude-sonnet-4"
        content shouldContain "- openai/gpt-4o"
        // Council prompt template markers
        content shouldContain "You are a multi-model council coordinator."
        content shouldContain "## Council Members"
    }

    @Test
    fun `writeCouncil skips writing when councilMembers is empty even if enableCouncil is true`() {
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = emptyList()))
        val result = writer.writeCouncil()
        result shouldBe true

        Files.exists(councilFile()) shouldBe false
    }

    // ── removal on toggle off ─────────────────────────────────────────

    @Test
    fun `coding-assistant file removed when enableCodingAssistant is false`() {
        // First write the file
        val writer = newWriter(newSettings(enableCodingAssistant = true))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        Files.exists(codingAssistantFile()) shouldBe true

        // Now disable and re-write
        val writer2 = newWriter(newSettings(enableCodingAssistant = false))
        writer2.writeCodingAssistant(isIntellijMcpEnabled = true)
        Files.exists(codingAssistantFile()) shouldBe false
    }

    @Test
    fun `council file removed when enableCouncil is false`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        // Write with council enabled
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()
        Files.exists(councilFile()) shouldBe true

        // Disable
        val writer2 = newWriter(newSettings(enableCouncil = false, councilMembers = members))
        writer2.writeCouncil()
        Files.exists(councilFile()) shouldBe false
    }

    // ── ownership marker overwrite semantics ──────────────────────────

    @Test
    fun `coding-assistant md preserved when file lacks ownership marker`() {
        val file = codingAssistantFile()
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom agent\nmode: primary\n---\nDo my bidding.\n"
        Files.writeString(file, userContent)

        val writer = newWriter(newSettings(enableCodingAssistant = true))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)

        // Content unchanged — user-managed file preserved
        readAgentFile(file) shouldBe userContent
    }

    @Test
    fun `council md always overwritten regardless of ownership marker`() {
        val file = councilFile()
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom council\nmode: subagent\n---\nCustom.\n"
        Files.writeString(file, userContent)

        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()

        val content = readAgentFile(file)
        content shouldNotBe userContent
        content shouldContain AgentConstants.OWNERSHIP_MARKER
        content shouldContain "You are a multi-model council coordinator."
    }

    // ── gitignore ──────────────────────────────────────────────────────

    @Test
    fun `ensureGitignore creates gitignore with agents entry if missing`() {
        val writer = newWriter(newSettings())
        val result = writer.ensureGitignore()
        result shouldBe true

        val gitignore = tempDir.resolve(".opencode").resolve(".gitignore")
        Files.exists(gitignore) shouldBe true
        Files.readString(gitignore) shouldContain "agents/"
    }

    @Test
    fun `ensureGitignore appends agents entry preserving existing entries`() {
        val opencodeDir = tempDir.resolve(".opencode")
        Files.createDirectories(opencodeDir)
        val gitignore = opencodeDir.resolve(".gitignore")
        Files.writeString(gitignore, "logs/\n*.tmp\n")

        val writer = newWriter(newSettings())
        val result = writer.ensureGitignore()
        result shouldBe true

        val content = Files.readString(gitignore)
        content shouldContain "logs/"
        content shouldContain "*.tmp"
        content shouldContain "agents/"
    }

    @Test
    fun `ensureGitignore is no-op when agents entry already present`() {
        val opencodeDir = tempDir.resolve(".opencode")
        Files.createDirectories(opencodeDir)
        val gitignore = opencodeDir.resolve(".gitignore")
        val original = "logs/\nagents/\n*.tmp\n"
        Files.writeString(gitignore, original)

        val writer = newWriter(newSettings())
        writer.ensureGitignore()

        // Content unchanged — agents/ already present
        Files.readString(gitignore) shouldBe original
    }

    // ── frontmatter mode ──────────────────────────────────────────────

    @Test
    fun `coding-assistant frontmatter contains mode primary`() {
        val writer = newWriter(newSettings())
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        val content = readAgentFile(codingAssistantFile())
        content shouldContain "mode: primary"
    }

    @Test
    fun `council frontmatter contains mode subagent and hidden false`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()
        val content = readAgentFile(councilFile())
        content shouldContain "mode: subagent"
        content shouldContain "hidden: false"
    }

    // ── file format ───────────────────────────────────────────────────

    @Test
    fun `agent file starts with ownership marker then frontmatter`() {
        val writer = newWriter(newSettings())
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        val content = readAgentFile(codingAssistantFile())

        // First line is the ownership marker
        val firstLine = content.lineSequence().first()
        firstLine shouldBe AgentConstants.OWNERSHIP_MARKER
        // Second line is the frontmatter opening delimiter
        content.lines()[1] shouldBe "---"
    }

    // ── clearAll ───────────────────────────────────────────────────────

    @Test
    fun `clearAll removes both agent files`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        writer.writeCouncil()
        Files.exists(codingAssistantFile()) shouldBe true
        Files.exists(councilFile()) shouldBe true

        val result = writer.clearAll()
        result shouldBe true
        Files.exists(codingAssistantFile()) shouldBe false
        Files.exists(councilFile()) shouldBe false
    }

    // ── writeAll ───────────────────────────────────────────────────────

    @Test
    fun `writeAll writes both agents and agent overrides in opencode json`() {
        val members = listOf(
            CouncilMember("anthropic", "claude-sonnet-4"),
            CouncilMember("openai", "gpt-4o")
        )
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        val result = writer.writeAll(isIntellijMcpEnabled = true)
        result shouldBe true

        Files.exists(codingAssistantFile()) shouldBe true
        Files.exists(councilFile()) shouldBe true

        // opencode.json should have agent overrides
        val opencodeJson = tempDir.resolve(".opencode").resolve("opencode.json")
        Files.exists(opencodeJson) shouldBe true
        val jsonContent = Files.readString(opencodeJson)
        jsonContent shouldContain "\"explore\""
        jsonContent shouldContain "\"general\""
        // Known leaked agents disabled — verify the agent is present AND set to disable:true
        val config = Json.parseToJsonElement(jsonContent).jsonObject
        val leakedAgent = config["agent"]?.jsonObject?.get("adversarial-deepseek-v4-pro")?.jsonObject
        leakedAgent shouldNotBe null
        leakedAgent!!["disable"]?.jsonPrimitive?.content shouldBe "true"
    }

    // ── council member filtering ──────────────────────────────────────

    @Test
    fun `writeCouncil filters invalid members from prompt body`() {
        val members = listOf(
            CouncilMember("anthropic", "claude-sonnet-4"), // valid
            CouncilMember("", "gpt-4o"), // invalid — blank provider
            CouncilMember("openai", "gpt-4o") // valid
        )
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()
        val content = readAgentFile(councilFile())

        content shouldContain "- anthropic/claude-sonnet-4"
        content shouldContain "- openai/gpt-4o"
        // Invalid member should NOT appear
        content shouldNotContain "- /gpt-4o"
    }

    // ── council member thinking variant ──────────────────────────────

    @Test
    fun `writeCouncil embeds thinking variant suffix in prompt body`() {
        val members = listOf(
            CouncilMember("anthropic", "claude-sonnet-4", "high"),
            CouncilMember("openai", "gpt-4o") // no variant
        )
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()
        val content = readAgentFile(councilFile())

        // Member with variant should include the colon suffix
        content shouldContain "- anthropic/claude-sonnet-4:high"
        // Member without variant should NOT have a colon suffix
        content shouldContain "- openai/gpt-4o"
        content shouldNotContain "- openai/gpt-4o:"
    }
}