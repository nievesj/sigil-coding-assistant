package com.opencode.acp.config

import com.opencode.acp.config.settings.AgentModelBinding
import com.opencode.acp.config.settings.CouncilMember
import com.opencode.acp.config.settings.OpenCodeAgentConfigurable
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
        enableReviewer: Boolean = false,
        taskAllowedAgents: List<String> = listOf("explore", "general"),
        councilMembers: List<CouncilMember> = emptyList()
    ): OpenCodeAgentSettingsState = OpenCodeAgentSettingsState().apply {
        this.enableCodingAssistant = enableCodingAssistant
        this.enableCouncil = enableCouncil
        this.enableReviewer = enableReviewer
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
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA"
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
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA"
        content shouldContain "AGENTS.md"
        content shouldContain ".opencode/context/repo-structure.md"
        content shouldContain ".opencode/context/intellij-mcp-tools.md"
    }

    // ── task permission YAML ──────────────────────────────────────────

    @Test
    fun `buildTaskPermissionYaml generates deny-all plus one allow per selected agent`() {
        // Council needs valid members for isAgentEnabledForTaskAllowlist to
        // emit "council": "allow" (mirrors hasValidConfig - council.md is not
        // written without valid members, so the allowlist must not reference it).
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val settings = newSettings(
            enableCouncil = true,
            councilMembers = members,
            taskAllowedAgents = listOf("explore", "general", "council")
        )
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
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val settings = newSettings(
            enableCouncil = true,
            councilMembers = members,
            taskAllowedAgents = listOf("explore", "general", "council")
        )
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
    fun `council md backs up user content before overwriting when council enabled`() {
        val file = councilFile()
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom council\nmode: subagent\n---\nCustom.\n"
        Files.writeString(file, userContent)

        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()

        // The user file is backed up to council.md.user.<timestamp>.<counter>.bak
        // (alwaysOverwrite + no marker -> backup before overwrite, not silent
        // destruction). The timestamp + monotonic counter prevents overwriting a
        // previous backup on repeated cycles, even when two cycles share the same
        // millisecond (review cmt_b2c3d4e5f6a7 + cmt_c3d4e5f6a7b9).
        val agentsDir = file.parent
        val backups = agentsDir.toFile().listFiles { f ->
            f.name.startsWith("council.md.user.") && f.name.endsWith(".bak")
        } ?: emptyArray()
        backups.size shouldBe 1
        Files.readString(backups[0].toPath()) shouldBe userContent
        // The agent file is overwritten with plugin-managed content.
        val content = readAgentFile(file)
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
    fun `clearAll removes written agent files (no-op for never-written v2 agents)`() {
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
        // KNOWN_LEAKED_AGENTS is NOT shipped to all users (cross-user config
        // mutation). The plugin must NOT write disable:true for the developer
        // machine denylist into every user opencode.json.
        val config = Json.parseToJsonElement(jsonContent).jsonObject
        val agentSection = config["agent"]?.jsonObject
        // Strengthened: assert agentSection is non-null (so the guard is
        // exercised, not skipped vacuously), then iterate ALL
        // KNOWN_LEAKED_AGENTS and assert each is absent. This catches any
        // regression that ships the denylist to all users.
        agentSection shouldNotBe null
        for (leaked in AgentConstants.KNOWN_LEAKED_AGENTS) {
            agentSection!![leaked] shouldBe null
        }
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

    // ── remove-path marker-less preservation (review cmt_f8a9b0c1d2e3) ────

    @Test
    fun `coding-assistant md preserved on REMOVE path when file lacks ownership marker`() {
        // Pre-create a marker-less coding-assistant.md with user content, then
        // disable coding-assistant (routes to removeAgentFile). The user file
        // must be preserved, NOT deleted (mirrors the write-path protection).
        val file = codingAssistantFile()
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom agent\nmode: primary\n---\nDo my bidding.\n"
        Files.writeString(file, userContent)

        val writer = newWriter(newSettings(enableCodingAssistant = false))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)

        // User file is still present and unchanged.
        Files.exists(file) shouldBe true
        Files.readString(file) shouldBe userContent
    }

    // ── backup uses timestamp to avoid overwrite (review cmt_b2c3d4e5f6a7) ─

    @Test
    fun `council backup filename includes timestamp so repeated cycles do not overwrite previous backup`() {
        val file = councilFile()
        Files.createDirectories(file.parent)
        val firstUserContent = "---\ndescription: first custom council\nmode: subagent\n---\nFirst.\n"
        Files.writeString(file, firstUserContent)

        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()

        // After the first cycle, the agent file has the marker (plugin content).
        // A user re-edits the file removing the marker, then re-enables - the
        // second backup must go to a DIFFERENT filename so the first backup
        // survives. The backup filename now includes BOTH a timestamp AND a
        // monotonic counter (review cmt_c3d4e5f6a7b9): the counter guarantees
        // uniqueness even when two cycles share the same millisecond, so no
        // Thread.sleep is needed to dodge a timestamp collision.
        val secondUserContent = "---\ndescription: second custom council\nmode: subagent\n---\nSecond.\n"
        Files.writeString(file, secondUserContent) // overwrite with new user content (no marker)
        writer.writeCouncil()

        val agentsDir = file.parent
        val backups =
            agentsDir.toFile().listFiles { f -> f.name.startsWith("council.md.user.") && f.name.endsWith(".bak") }
                ?: emptyArray()
        // Strengthened assertion (review cmt_b8c9d0e1f2a3): the test name
        // promises "repeated cycles do not overwrite previous backup", so
        // assert BOTH backups survive (size shouldBe 2). The Thread.sleep(15)
        // above guarantees distinct epoch-ms timestamps (well above the
        // ~1-2ms granularity of System.currentTimeMillis on modern JVMs),
        // so the two backups MUST have different filenames. A regression
        // reverting to the fixed ".user.bak" name (no timestamp) would
        // produce size==1 (overwrite) and fail this assertion.
        backups.size shouldBe 2
        // The first backup has the first user content; the second has the second.
        val backupContents = backups.map { Files.readString(it.toPath()) }.toSet()
        backupContents shouldBe setOf(firstUserContent, secondUserContent)
        // All backup filenames match the timestamped pattern (not the old fixed name).
        for (b in backups) {
            b.name shouldNotBe "council.md.user.bak"
        }
    }

    @Test
    fun `council backup survives same-millisecond collision via monotonic counter`() {
        // Direct test of the collision case the counter fixes (review
        // cmt_c3d4e5f6a7b9): two backup cycles that share the same epoch-ms
        // (e.g., a tight re-application loop) must produce DISTINCT filenames
        // so the earlier backup is not overwritten by REPLACE_EXISTING. The
        // monotonic counter guarantees uniqueness regardless of timestamp
        // granularity. This test does NOT sleep — it relies solely on the
        // counter to distinguish the two backups.
        val file = councilFile()
        Files.createDirectories(file.parent)
        val firstUserContent = "---\ndescription: first\nmode: subagent\n---\nFirst.\n"
        Files.writeString(file, firstUserContent)

        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeCouncil()

        // Immediately re-edit (no marker) and re-write — no sleep, so the
        // timestamp MAY tie. The counter must still produce a distinct filename.
        val secondUserContent = "---\ndescription: second\nmode: subagent\n---\nSecond.\n"
        Files.writeString(file, secondUserContent)
        writer.writeCouncil()

        val agentsDir = file.parent
        val backups =
            agentsDir.toFile().listFiles { f -> f.name.startsWith("council.md.user.") && f.name.endsWith(".bak") }
                ?: emptyArray()
        // Both backups survive (the counter prevented the collision).
        backups.size shouldBe 2
        val backupContents = backups.map { Files.readString(it.toPath()) }.toSet()
        backupContents shouldBe setOf(firstUserContent, secondUserContent)
    }

    // ── council omitted from allowlist when enabled but memberless (review cmt_a1b2c3d4e5f6) ─

    @Test
    fun `buildTaskPermissionYaml omits council when enabled but no valid members`() {
        // Council enabled but councilMembers is empty: writeAgent removes
        // council.md (hasValidConfig fails), so the allowlist must NOT emit
        // "council": "allow" (dead entry referencing a non-existent agent file).
        val writer = newWriter(
            newSettings(
                enableCouncil = true,
                councilMembers = emptyList(),
                taskAllowedAgents = listOf("explore", "general", "council")
            )
        )
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"explore\": \"allow\""
        yaml shouldContain "\"general\": \"allow\""
        yaml shouldNotContain "\"council\": \"allow\""
    }

    // ── hasOwnershipMarker fail-closed on read error (review cmt_a1b2c3d4e5f6) ──

    @Test
    fun `writeAgent preserves marker-less file whose ownership marker cannot be read (fail-closed)`() {
        // Simulate a read failure by creating a DIRECTORY at the agent file
        // path. Files.lines() on a directory throws IOException, which is the
        // exact condition hasOwnershipMarker's catch handles. With the
        // fail-closed fix, hasOwnershipMarker returns false (treat as
        // user-managed), so writeAgent preserves it (skip write) instead of
        // overwriting it. Before the fix, hasOwnershipMarker returned true
        // (fail-open) and the file would be overwritten WITHOUT backup
        // (non-alwaysOverwrite agent) — silent data loss on any I/O hiccup.
        val file = codingAssistantFile()
        Files.createDirectories(file.parent)
        // Create a DIRECTORY where the .md file should be — forces Files.lines
        // to throw when hasOwnershipMarker tries to read the first line.
        Files.createDirectory(file)
        Files.isDirectory(file) shouldBe true

        val writer = newWriter(newSettings(enableCodingAssistant = true))
        val result = writer.writeCodingAssistant(isIntellijMcpEnabled = true)

        // The "file" (directory) is preserved — not destroyed. The write was
        // skipped (user-managed, marker unreadable). Result is true (skip is a
        // non-fatal success). The key assertion is that the directory still
        // exists — fail-open would have called AtomicFileWriter which would
        // either fail or replace the directory, losing the user's content.
        result shouldBe true
        Files.exists(file) shouldBe true
        Files.isDirectory(file) shouldBe true
    }

    @Test
    fun `removeAgentFile preserves marker-less file whose ownership marker cannot be read (fail-closed)`() {
        // The remove path has the same fail-closed requirement: if the marker
        // can't be read, treat the file as user-managed (preserve, don't
        // delete). A directory at the .md path forces the read failure.
        val file = agentsDir().resolve("${AgentConstants.CODER_AGENT_NAME}.md")
        Files.createDirectories(file.parent)
        Files.createDirectory(file)
        Files.isDirectory(file) shouldBe true

        // newSettings() (v1 helper) has no enableCoder param — set it directly
        // on the state. enableCoder=false routes writeAgent to removeAgentFile.
        val settings = newSettings().apply { enableCoder = false }
        val writer = newWriter(settings)
        // writeAgent with enabled=false routes to removeAgentFile.
        val result = writer.writeAgent(
            AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME),
            isIntellijMcpEnabled = true
        )

        // The directory is preserved (not deleted). Before the fix,
        // hasOwnershipMarker returned true (fail-open), so removeAgentFile
        // would call Files.delete on the directory (which would throw, but the
        // catch would return false — still wrong behavior). With fail-closed,
        // the marker read returns false, the preserve branch fires, and the
        // user's content survives.
        result shouldBe true
        Files.exists(file) shouldBe true
        Files.isDirectory(file) shouldBe true
    }

    // ── hasOwnershipMarker strips UTF-8 BOM (review cmt_d4e5f6a7b8c0) ──

    @Test
    fun `writeAgent overwrites plugin-managed file that has a UTF-8 BOM prefix`() {
        // A plugin-managed file (with ownership marker) that an external tool
        // prefixed with a UTF-8 BOM (U+FEFF) must still be recognized as
        // plugin-managed and overwritten. Before the BOM-strip fix, trim() left
        // the BOM in place, the marker check failed, and the file was treated as
        // user-managed (skipped on write, preserved on remove — stale content
        // persisted indefinitely). See review cmt_d4e5f6a7b8c0.
        val file = codingAssistantFile()
        Files.createDirectories(file.parent)
        // Write a plugin-managed file (with marker) but BOM-prefixed.
        val bomPrefixed = "\uFEFF" + AgentConstants.OWNERSHIP_MARKER + "\n---\nold\n---\nold prompt\n"
        Files.writeString(file, bomPrefixed)

        val writer = newWriter(newSettings(enableCodingAssistant = true))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)

        // The file is overwritten with fresh plugin content (BOM stripped on
        // read, marker recognized, overwrite proceeds). The new content starts
        // with the marker (no BOM — AtomicFileWriter writes without one).
        val content = readAgentFile(file)
        content shouldContain AgentConstants.OWNERSHIP_MARKER
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA"
        // The stale "old prompt" is gone.
        content shouldNotContain "old prompt"
    }

    // ── builder exception does not abort writeAll (review cmt_c3d4e5f6a7b8) ──

    @Test
    fun `writeAgent returns false and skips file when frontmatterBuilder throws`() {
        // Real catch-path test (review cmt_c3d4e5f6a7b8): construct a test-only
        // AgentDefinition whose frontmatterBuilder throws, with enableFlagGetter
        // returning true so the write path is taken (the catch fires). This is
        // now possible because enableFlagGetter is a lambda on AgentDefinition
        // (centralized mapping) — previously isEnabled(name) returned false for
        // any non-registry name, so a test-only def never reached the write path
        // and the catch could not be exercised without reflection.
        //
        // Verifies: (a) writeAgent returns false on a builder throw, (b) the
        // agent file is NOT written, (c) the exception does not propagate.
        val throwingDef = AgentDefinition(
            name = "test-throwing-agent",
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = false,
            description = "test-only agent that throws during frontmatter build",
            promptBuilder = { _ -> "prompt" },
            frontmatterBuilder = { _ -> throw IllegalStateException("test: simulated builder failure") },
            enableFlagGetter = { _ -> true }, // force the write path so the catch fires
            enableFlagSetter = { _, _ -> },
        )
        val writer = newWriter(newSettings())
        val result = writer.writeAgent(throwingDef, isIntellijMcpEnabled = true)

        // (a) writeAgent returns false (the catch caught the throw and reported
        // failure — it did NOT propagate, which would abort the caller).
        result shouldBe false
        // (b) The agent file was NOT written (the throw happened before
        // writeAgentFile, so AtomicFileWriter was never called).
        Files.exists(agentsDir().resolve("test-throwing-agent.md")) shouldBe false
    }

    @Test
    fun `writeAll continues with remaining agents when one agent's builder throws`() {
        // The writeAll loop must NOT abort when one agent's builder throws — the
        // remaining agents are still processed (best-effort continuation is
        // safer than aborting mid-iteration and leaving .opencode/agents/ in a
        // partial state). This is the cross-agent guarantee that the single-
        // agent test above does not cover.
        //
        // Setup: a test-only throwing def + a real enabled agent (coder). We
        // cannot inject the throwing def into AgentRegistry.ALL_AGENTS (it's a
        // val), so we call writeAgent directly for the throwing def, then call
        // writeAll and verify the real agents still write. This proves the
        // catch isolates failures: a throw in one writeAgent call does not
        // corrupt the writer's state for subsequent calls.
        val throwingDef = AgentDefinition(
            name = "test-throwing-agent",
            mode = "subagent",
            defaultEnabled = false,
            hidden = false,
            hasPerAgentModel = false,
            description = "test-only agent that throws during frontmatter build",
            promptBuilder = { _ -> "prompt" },
            frontmatterBuilder = { _ -> throw IllegalStateException("test: simulated builder failure") },
            enableFlagGetter = { _ -> true },
            enableFlagSetter = { _, _ -> },
        )
        val writer = newWriter(newSettings().apply { enableCoder = true })

        // First: the throwing agent fails (returns false, no file).
        val throwResult = writer.writeAgent(throwingDef, isIntellijMcpEnabled = true)
        throwResult shouldBe false

        // Then: writeAll still succeeds for the real enabled agent (coder).
        // The earlier throw did not corrupt the writer's state.
        val allResult = writer.writeAll(isIntellijMcpEnabled = true)
        allResult shouldBe true
        Files.exists(agentsDir().resolve("${AgentConstants.CODER_AGENT_NAME}.md")) shouldBe true
    }

    // ── mergeAgentModelBindings dedup of preserved non-UI bindings (review cmt_e5f6a7b8c9d0) ──

    @Test
    fun `mergeAgentModelBindings dedups preserved non-UI bindings by agentName (first wins)`() {
        // If settings.agentModels (hand-edited XML or a prior plugin version)
        // contains TWO bindings for the same non-UI agent (e.g., two
        // coding-assistant entries), the merge must dedup them (first wins)
        // instead of preserving both. Without dedup, duplicates accumulate
        // across Applies (preserved again each time) and bloat persisted state.
        val existing = listOf(
            AgentModelBinding("coding-assistant", CouncilMember("anthropic", "claude-sonnet-4")),
            AgentModelBinding("coding-assistant", CouncilMember("openai", "gpt-4o")), // dup agentName
            AgentModelBinding("council", CouncilMember("anthropic", "claude-opus-4")),
        )
        val uiBindings = listOf(
            AgentModelBinding("coder", CouncilMember("anthropic", "claude-sonnet-4", "high")),
        )
        val uiAgentNames = setOf("coder", "researcher", "planner", "tester", "reviewer")

        val merged = OpenCodeAgentConfigurable.mergeAgentModelBindings(existing, uiBindings, uiAgentNames)

        // coding-assistant appears ONCE (first wins: anthropic/claude-sonnet-4),
        // council once, coder once. No duplicate agentNames.
        val codingAssistantBindings = merged.filter { it.agentName == "coding-assistant" }
        codingAssistantBindings.size shouldBe 1
        codingAssistantBindings[0].model?.modelID shouldBe "claude-sonnet-4"
        merged.size shouldBe 3 // 2 deduped non-UI (coding-assistant, council) + 1 UI (coder)
        // No duplicate agentNames in the merged list.
        val names = merged.map { it.agentName }
        names.size shouldBe names.toSet().size
    }
}
