package com.opencode.acp.config

import com.opencode.acp.config.settings.AgentModelBinding
import com.opencode.acp.config.settings.CouncilMember
import com.opencode.acp.config.settings.OpenCodeAgentSettingsState
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import com.opencode.acp.mcp.McpConfigWriter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for the v2 data-driven refactor of [AgentConfigWriter]
 * (TDD `docs/tdd/custom-agents-v2.md`).
 *
 * Verifies:
 * - [AgentRegistry] iteration writes enabled agents and removes disabled ones.
 * - New subagent files (coder/researcher/planner/tester) have valid frontmatter
 *   + prompt body.
 * - Per-agent model frontmatter (Path B): `model: "providerID/modelID"` +
 *   `variant: <value>` (sibling, not inline) when configured; omitted when
 *   not (inherit).
 * - Per-agent `temperature`/`steps` frontmatter (hardcoded constants).
 * - Generalized [AgentConfigWriter.buildTaskPermissionYaml] gating for v2
 *   subagents.
 * - `clearAll()` removes all 6 managed agent files (deliberate v2 behavior
 *   change, §7.7).
 * - v1 back-compat wrappers (`writeCodingAssistant`/`writeCouncil`) still work.
 *
 * Uses JUnit5 @TempDir — same setup as [AgentConfigWriterTest].
 */
class AgentConfigWriterV2Test {

    @TempDir
    lateinit var tempDir: Path

    private fun newSettings(
        enableCodingAssistant: Boolean = true,
        enableCouncil: Boolean = false,
        enableCoder: Boolean = false,
        enableResearcher: Boolean = false,
        enablePlanner: Boolean = false,
        enableTester: Boolean = false,
        taskAllowedAgents: List<String> = listOf("explore", "general"),
        councilMembers: List<CouncilMember> = emptyList(),
        agentModels: List<AgentModelBinding> = emptyList(),
    ): OpenCodeAgentSettingsState = OpenCodeAgentSettingsState().apply {
        this.enableCodingAssistant = enableCodingAssistant
        this.enableCouncil = enableCouncil
        this.enableCoder = enableCoder
        this.enableResearcher = enableResearcher
        this.enablePlanner = enablePlanner
        this.enableTester = enableTester
        this.taskAllowedAgents = java.util.ArrayList(taskAllowedAgents)
        this.councilMembers = java.util.ArrayList(councilMembers)
        this.agentModels = java.util.ArrayList(agentModels)
    }

    private fun newWriter(settings: OpenCodeAgentSettingsState): AgentConfigWriter {
        val mcpSettings = OpenCodeMcpSettingsState()
        val mcpWriter = McpConfigWriter(tempDir, mcpSettings)
        return AgentConfigWriter(tempDir, settings, mcpWriter)
    }

    private fun agentsDir(): Path = tempDir.resolve(AgentConstants.AGENTS_DIR)

    private fun agentFile(name: String): Path = agentsDir().resolve("$name.md")

    private fun readAgent(name: String): String = Files.readString(agentFile(name))

    private fun coderBinding(model: CouncilMember) = AgentModelBinding(AgentConstants.CODER_AGENT_NAME, model)

    // ── Registry-driven writeAll ─────────────────────────────────────────

    @Test
    fun `writeAll with only v1 agents enabled writes only coding-assistant and council`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        writer.writeAll(isIntellijMcpEnabled = true)

        Files.exists(agentFile(AgentConstants.CODING_ASSISTANT_AGENT_NAME)) shouldBe true
        Files.exists(agentFile(AgentConstants.COUNCIL_AGENT_NAME)) shouldBe true
        // v2 agents are disabled by default → not written
        Files.exists(agentFile(AgentConstants.CODER_AGENT_NAME)) shouldBe false
        Files.exists(agentFile(AgentConstants.RESEARCHER_AGENT_NAME)) shouldBe false
        Files.exists(agentFile(AgentConstants.PLANNER_AGENT_NAME)) shouldBe false
        Files.exists(agentFile(AgentConstants.TESTER_AGENT_NAME)) shouldBe false
    }

    @Test
    fun `writeAll with coder enabled writes coder md`() {
        val writer = newWriter(newSettings(enableCoder = true))
        writer.writeAll(isIntellijMcpEnabled = true)

        Files.exists(agentFile(AgentConstants.CODER_AGENT_NAME)) shouldBe true
    }

    @Test
    fun `writeAgent for coder writes valid markdown with frontmatter and prompt`() {
        val writer = newWriter(newSettings(enableCoder = true))
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        content shouldContain AgentConstants.OWNERSHIP_MARKER
        content shouldContain "mode: subagent"
        content shouldContain "hidden: false"
        content shouldContain "temperature: 0.2"
        content shouldContain "steps: 25"
        // Subagents cannot delegate (subagent_depth: 1): deny-only
        // task permission, NOT the full allowlist (see cmt_b2e3c4d5e6f7).
        content shouldContain "    \"*\": \"deny\""
        content shouldNotContain "\"explore\": \"allow\""
        content shouldNotContain "\"coder\": \"allow\""
        // No model configured → no model: frontmatter (inherit)
        content shouldNotContain "model:"
        content shouldNotContain "variant:"
        // Prompt body present
        content shouldContain "You are a scoped implementation subagent."
        content shouldContain "## Return Format (REQUIRED)"
        content shouldContain "Do NOT call `intellij_build_project`"
    }

    @Test
    fun `writeAgent for researcher writes valid markdown`() {
        val writer = newWriter(newSettings(enableResearcher = true))
        val def = AgentRegistry.byName(AgentConstants.RESEARCHER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.RESEARCHER_AGENT_NAME)
        content shouldContain "mode: subagent"
        content shouldContain "temperature: 0.3"
        // Researcher has no steps cap
        content shouldNotContain "steps:"
        // Prompt body present — read-only constraint
        content shouldContain "You are a semantic codebase investigator."
        content shouldContain "READ-ONLY"
        content shouldContain "## Return Format (REQUIRED)"
    }

    @Test
    fun `writeAgent for planner writes valid markdown with chunk-plan format`() {
        val writer = newWriter(newSettings(enablePlanner = true))
        val def = AgentRegistry.byName(AgentConstants.PLANNER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.PLANNER_AGENT_NAME)
        content shouldContain "mode: subagent"
        content shouldContain "temperature: 0.4"
        content shouldNotContain "steps:"
        content shouldContain "You are a task decomposer."
        content shouldContain "one chunk per file"
        content shouldContain "## Chunk Plan"
        content shouldContain "## Fan-out Recommendation"
        // Planner does NOT delegate (subagent_depth: 1)
        content shouldContain "Do NOT delegate"
    }

    @Test
    fun `writeAgent for tester writes valid markdown mirroring coder with test focus`() {
        val writer = newWriter(newSettings(enableTester = true))
        val def = AgentRegistry.byName(AgentConstants.TESTER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.TESTER_AGENT_NAME)
        content shouldContain "mode: subagent"
        content shouldContain "temperature: 0.2"
        content shouldContain "steps: 25"
        content shouldContain "You are a scoped test implementer."
        content shouldContain "Read existing tests in the same package/module first"
        content shouldContain "## Return Format (REQUIRED)"
        content shouldContain "Do NOT call `intellij_build_project`"
    }

    // ── Per-agent model frontmatter (Path B) ────────────────────────────

    @Test
    fun `writeAgent emits model and variant frontmatter when per-agent model configured`() {
        val model = CouncilMember("anthropic", "claude-sonnet-4", "high")
        val writer = newWriter(
            newSettings(enableCoder = true, agentModels = listOf(coderBinding(model)))
        )
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        // Path B: model is a STRING "providerID/modelID" (NOT a nested object);
        // variant is a SIBLING field.
        content shouldContain """model: "anthropic/claude-sonnet-4""""
        content shouldContain "variant: \"high\""
        // Must NOT be inline (providerID/modelID:variant)
        content shouldNotContain """model: "anthropic/claude-sonnet-4:high""""
    }

    @Test
    fun `writeAgent omits variant frontmatter when thinking variant is blank`() {
        val model = CouncilMember("anthropic", "claude-sonnet-4") // no variant
        val writer = newWriter(
            newSettings(enableCoder = true, agentModels = listOf(coderBinding(model)))
        )
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        content shouldContain """model: "anthropic/claude-sonnet-4""""
        content shouldNotContain "variant:"
    }

    @Test
    fun `writeAgent omits model frontmatter when no per-agent model configured`() {
        val writer = newWriter(newSettings(enableCoder = true, agentModels = emptyList()))
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        content shouldNotContain "model:"
        content shouldNotContain "variant:"
    }

    @Test
    fun `writeAgent omits model frontmatter when binding has null model (inherit)`() {
        val writer = newWriter(
            newSettings(
                enableCoder = true,
                agentModels = listOf(AgentModelBinding(AgentConstants.CODER_AGENT_NAME, null))
            )
        )
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        content shouldNotContain "model:"
        content shouldNotContain "variant:"
    }

    @Test
    fun `writeAgent omits model frontmatter when binding has invalid model`() {
        val writer = newWriter(
            newSettings(
                enableCoder = true,
                agentModels = listOf(coderBinding(CouncilMember("", "claude-sonnet-4"))),
            )
        )
        val def = AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME)
        writer.writeAgent(def, isIntellijMcpEnabled = true)

        val content = readAgent(AgentConstants.CODER_AGENT_NAME)
        content shouldNotContain "model:"
    }

    @Test
    fun `coding-assistant and council never emit per-agent model frontmatter`() {
        // coding-assistant has hasPerAgentModel=false (primary uses chat's
        // active model); council has its own per-MEMBER list embedded in the
        // prompt body. Neither should emit model:/variant: frontmatter even
        // if an agentModels binding happens to exist for them (defense).
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(
            newSettings(
                enableCouncil = true,
                councilMembers = members,
                agentModels = listOf(
                    AgentModelBinding(AgentConstants.CODING_ASSISTANT_AGENT_NAME, CouncilMember("x", "y")),
                    AgentModelBinding(AgentConstants.COUNCIL_AGENT_NAME, CouncilMember("x", "y")),
                ),
            )
        )
        writer.writeAll(isIntellijMcpEnabled = true)

        val ca = readAgent(AgentConstants.CODING_ASSISTANT_AGENT_NAME)
        // Strengthened: assert no `model:`/`variant:` key in the FRONTMATTER
        // (catches any quoting style, not just the exact double-quoted form).
        // The frontmatter is between the first two `---` lines; the prompt body
        // may legitimately mention "model:" (e.g. the council prompt instructs
        // the LLM to pass `model: { providerID: ... }` to the task tool), so
        // checking the full content would false-positive.
        val caFm = extractFrontmatter(ca)
        caFm shouldNotContain "model:"
        caFm shouldNotContain "variant:"
        val co = readAgent(AgentConstants.COUNCIL_AGENT_NAME)
        val coFm = extractFrontmatter(co)
        coFm shouldNotContain "model:"
        coFm shouldNotContain "variant:"
    }

    // ── Removal on toggle off ───────────────────────────────────────────

    @Test
    fun `coder file removed when enableCoder is false`() {
        // First write the file
        val writer = newWriter(newSettings(enableCoder = true))
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)
        Files.exists(agentFile(AgentConstants.CODER_AGENT_NAME)) shouldBe true

        // Now disable and re-write
        val writer2 = newWriter(newSettings(enableCoder = false))
        writer2.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)
        Files.exists(agentFile(AgentConstants.CODER_AGENT_NAME)) shouldBe false
    }

    @Test
    fun `all v2 subagent files removed via writeAll when disabled`() {
        // Write all enabled first
        val writer = newWriter(
            newSettings(
                enableCoder = true, enableResearcher = true, enablePlanner = true, enableTester = true,
            )
        )
        writer.writeAll(isIntellijMcpEnabled = true)
        for (name in AgentConstants.V2_SUBAGENT_NAMES) {
            Files.exists(agentFile(name)) shouldBe true
        }

        // Disable all and re-run writeAll
        val writer2 = newWriter(newSettings())
        writer2.writeAll(isIntellijMcpEnabled = true)
        for (name in AgentConstants.V2_SUBAGENT_NAMES) {
            Files.exists(agentFile(name)) shouldBe false
        }
    }

    // ── Ownership marker overwrite semantics ─────────────────────────────

    @Test
    fun `v2 subagent md preserved when file lacks ownership marker`() {
        val file = agentFile(AgentConstants.CODER_AGENT_NAME)
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom coder\nmode: subagent\n---\nDo my bidding.\n"
        Files.writeString(file, userContent)

        val writer = newWriter(newSettings(enableCoder = true))
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)

        // Content unchanged — user-managed file preserved
        Files.readString(file) shouldBe userContent
    }

    @Test
    fun `v2 subagent md overwritten when file has ownership marker`() {
        val file = agentFile(AgentConstants.CODER_AGENT_NAME)
        Files.createDirectories(file.parent)
        // Pre-existing plugin-managed file (has marker) — should be overwritten
        Files.writeString(file, AgentConstants.OWNERSHIP_MARKER + "\n---\nold\n---\nold prompt\n")

        val writer = newWriter(newSettings(enableCoder = true))
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)

        val content = Files.readString(file)
        content shouldNotBe "old prompt"
        content shouldContain "You are a scoped implementation subagent."
    }

    // ── remove-path marker-less preservation (review cmt_b0c1d2e3f4a5) ────

    @Test
    fun `v2 subagent md preserved on REMOVE path when file lacks ownership marker`() {
        // Pre-create a marker-less coder.md with user content, then disable
        // coder (routes to removeAgentFile). The user file must be preserved,
        // NOT deleted (mirrors the write-path protection in the test above).
        val file = agentFile(AgentConstants.CODER_AGENT_NAME)
        Files.createDirectories(file.parent)
        val userContent = "---\ndescription: my custom coder\nmode: subagent\n---\nDo my bidding.\n"
        Files.writeString(file, userContent)

        val writer = newWriter(newSettings(enableCoder = false))
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)

        // User file is still present and unchanged.
        Files.exists(file) shouldBe true
        Files.readString(file) shouldBe userContent
    }

    // ── Generalized buildTaskPermissionYaml (v2 gating) ─────────────────

    @Test
    fun `buildTaskPermissionYaml omits coder when enableCoder is false even if in taskAllowedAgents`() {
        val writer = newWriter(
            newSettings(
                enableCoder = false,
                taskAllowedAgents = listOf("explore", "general", "coder"),
            )
        )
        val yaml = writer.buildTaskPermissionYaml()

        yaml shouldContain "\"explore\": \"allow\""
        yaml shouldContain "\"general\": \"allow\""
        yaml shouldNotContain "\"coder\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml includes coder when enableCoder is true and coder in taskAllowedAgents`() {
        val writer = newWriter(
            newSettings(
                enableCoder = true,
                taskAllowedAgents = listOf("explore", "general", "coder"),
            )
        )
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"coder\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml gates all v2 subagents on their enable flags`() {
        // All v2 subagents in allowlist, but only coder + planner enabled
        val writer = newWriter(
            newSettings(
                enableCoder = true,
                enableResearcher = false,
                enablePlanner = true,
                enableTester = false,
                taskAllowedAgents = listOf("explore", "general", "coder", "researcher", "planner", "tester"),
            )
        )
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"coder\": \"allow\""
        yaml shouldContain "\"planner\": \"allow\""
        yaml shouldNotContain "\"researcher\": \"allow\""
        yaml shouldNotContain "\"tester\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml still includes explore and general unconditionally`() {
        val writer = newWriter(newSettings(taskAllowedAgents = listOf("explore", "general")))
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"explore\": \"allow\""
        yaml shouldContain "\"general\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml drops unknown user-added agent names (fail-closed)`() {
        // A user could add an unknown agent name to the allowlist (not a plugin
        // agent, not a built-in). The gating now FAILS CLOSED: unknown names
        // are dropped (defense-in-depth) rather than emitted as dead allow
        // entries. A typo no longer silently writes a dead YAML key into every
        // agent file permission block.
        val writer = newWriter(newSettings(taskAllowedAgents = listOf("explore", "general", "my-custom-agent")))
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"explore\": \"allow\""
        yaml shouldContain "\"general\": \"allow\""
        yaml shouldNotContain "\"my-custom-agent\": \"allow\""
    }

    @Test
    fun `buildTaskPermissionYaml skips agent names that fail YAML_SAFE_IDENTIFIER`() {
        // The YAML_SAFE_IDENTIFIER regex is the YAML-injection guard (CWE-94).
        // Unsafe agent names (with colons, slashes, spaces, quotes, newlines)
        // must be skipped — emitting them would break the frontmatter structure
        // or allow injection of arbitrary YAML keys.
        val writer = newWriter(
            newSettings(
                taskAllowedAgents = listOf(
                    "explore",
                    "my:agent",     // colon — YAML special char
                    "my/agent",     // slash — not in YAML_SAFE_IDENTIFIER
                    "my agent",     // space
                    "x\"y",         // quote
                    "general",
                    "a".repeat(129), // too long
                )
            )
        )
        val yaml = writer.buildTaskPermissionYaml()
        yaml shouldContain "\"explore\": \"allow\""
        yaml shouldContain "\"general\": \"allow\""
        yaml shouldNotContain "my:agent"
        yaml shouldNotContain "my/agent"
        yaml shouldNotContain "my agent"
        yaml shouldNotContain "x\"y"
    }

    // ── clearAll removes all 6 managed files (deliberate v2 behavior change) ─

    @Test
    fun `clearAll removes all 6 managed agent files`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(
            newSettings(
                enableCouncil = true, councilMembers = members,
                enableCoder = true, enableResearcher = true, enablePlanner = true, enableTester = true,
            )
        )
        // Write all 6
        writer.writeAll(isIntellijMcpEnabled = true)
        for (name in AgentRegistry.ALL_NAMES) {
            Files.exists(agentFile(name)) shouldBe true
        }

        // Clear all
        val result = writer.clearAll()
        result shouldBe true
        for (name in AgentRegistry.ALL_NAMES) {
            Files.exists(agentFile(name)) shouldBe false
        }
    }

    // ── v1 back-compat wrappers ─────────────────────────────────────────

    @Test
    fun `writeCodingAssistant back-compat wrapper delegates to writeAgent via registry`() {
        val writer = newWriter(newSettings(enableCodingAssistant = true))
        val ok = writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        ok shouldBe true
        Files.exists(agentFile(AgentConstants.CODING_ASSISTANT_AGENT_NAME)) shouldBe true
    }

    @Test
    fun `writeCouncil back-compat wrapper delegates to writeAgent via registry`() {
        val members = listOf(CouncilMember("anthropic", "claude-sonnet-4"))
        val writer = newWriter(newSettings(enableCouncil = true, councilMembers = members))
        val ok = writer.writeCouncil()
        ok shouldBe true
        Files.exists(agentFile(AgentConstants.COUNCIL_AGENT_NAME)) shouldBe true
    }

    // ── coding-assistant delegation section (v2 §4.7.3D) ────────────────

    @Test
    fun `coding-assistant prompt includes v2 delegation section with all subagents`() {
        val writer = newWriter(newSettings(enableCodingAssistant = true))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        val content = readAgent(AgentConstants.CODING_ASSISTANT_AGENT_NAME)

        // v2 delegation table includes all subagents
        content shouldContain "`planner`"
        content shouldContain "`coder`"
        content shouldContain "`researcher`"
        content shouldContain "`tester`"
        content shouldContain "`council`"
        content shouldContain "`explore`"
        content shouldContain "`general`"
        // Parallel-implementation workflow (§4.4.1)
        content shouldContain "Parallel-implementation workflow"
        content shouldContain "Delegate to `planner`"
        content shouldContain "Emit ALL `parallel: true` coder `task` calls in ONE assistant response"
        content shouldContain "Run `intellij_build_project`"
        content shouldContain "Fix cross-file integration errors yourself"
        // Per-agent models applied server-side (§10.Q1)
        content shouldContain "Per-agent models (applied server-side)"
        content shouldContain "do NOT pass a `model` parameter"
        // Fan-out heuristic
        content shouldContain "Fan-out heuristic"
        content shouldContain "3+ independent files"
    }

    @Test
    fun `coding-assistant prompt still includes v1 research phase and tool reference`() {
        val writer = newWriter(newSettings(enableCodingAssistant = true))
        writer.writeCodingAssistant(isIntellijMcpEnabled = true)
        val content = readAgent(AgentConstants.CODING_ASSISTANT_AGENT_NAME)

        // v1 core prompt intact
        content shouldContain "You are a coding assistant embedded in IntelliJ IDEA"
        content shouldContain "Mandatory Research Phase"
        content shouldContain "AGENTS.md"
        content shouldContain ".opencode/context/repo-structure.md"
        content shouldContain ".opencode/context/intellij-mcp-tools.md"
        content shouldContain "Retry On Failure"
    }

    // ── frontmatter has no duplicate YAML keys ──────────────────────────

    @Test
    fun `coder frontmatter has no duplicate YAML keys`() {
        val model = CouncilMember("anthropic", "claude-sonnet-4", "high")
        val writer = newWriter(
            newSettings(enableCoder = true, agentModels = listOf(coderBinding(model)))
        )
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)
        val content = readAgent(AgentConstants.CODER_AGENT_NAME)

        val lines = content.lines()
        val firstFence = lines.indexOfFirst { it.trim() == "---" }
        val secondFence = (firstFence + 1 until lines.size).first { lines[it].trim() == "---" }
        val frontmatterLines = lines.subList(firstFence + 1, secondFence)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("task:") && !it.startsWith("\"") }
        // Parse top-level keys (strip nested permission/task block)
        val keys = frontmatterLines.mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx < 0) null else line.substring(0, idx).trim()
        }
        val duplicateKeys = keys.groupingBy { it }.eachCount().filter { it.value > 1 }
        duplicateKeys shouldBe emptyMap()
    }

    @Test
    fun `coder frontmatter task block has no duplicate allowlist keys`() {
        // The test above filters out lines starting with `task:` or `"`, which
        // blinds it to duplicate keys INSIDE the task block (e.g. a regression
        // producing two `"coder": "allow"` lines). This test parses the task
        // block specifically and asserts no duplicate quoted keys within it.
        val writer = newWriter(
            newSettings(
                enableCoder = true,
                taskAllowedAgents = listOf("explore", "general", "coder"),
            )
        )
        writer.writeAgent(AgentRegistry.byName(AgentConstants.CODER_AGENT_NAME), isIntellijMcpEnabled = true)
        val content = readAgent(AgentConstants.CODER_AGENT_NAME)

        // Extract the task: block lines (indented under `permission:` then `task:`).
        val lines = content.lines()
        val taskLineIdx = lines.indexOfFirst { it.trim() == "task:" }
        // Subagents emit a deny-only task block, so taskLineIdx is found.
        (taskLineIdx >= 0) shouldBe true
        // Collect quoted keys within the task block (lines indented more than
        // the `task:` line, starting with a quote).
        val taskBlockKeys = mutableListOf<String>()
        var i = taskLineIdx + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("---")) break
            val trimmed = line.trim()
            if (trimmed.startsWith("\"")) {
                // Extract the key between the first pair of quotes.
                val key = trimmed.substring(1, trimmed.indexOf('"', 1))
                taskBlockKeys.add(key)
            }
            i++
        }
        // No duplicate keys in the task block (defense-in-depth for the
        // `emitted` set + isAgentEnabledForTaskAllowlist gate in
        // buildTaskPermissionYaml).
        val dupes = taskBlockKeys.groupingBy { it }.eachCount().filter { it.value > 1 }
        dupes shouldBe emptyMap()
    }

    /** Extract the frontmatter block (between the first two `---` lines, excluding them). */
    private fun extractFrontmatter(content: String): String {
        val lines = content.lines()
        val firstFence = lines.indexOfFirst { it.trim() == "---" }
        val secondFence = (firstFence + 1 until lines.size).first { lines[it].trim() == "---" }
        return lines.subList(firstFence + 1, secondFence).joinToString("\n")
    }
}
