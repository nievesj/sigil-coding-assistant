package com.opencode.acp.mcp

import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [McpConfigWriter.writeSkillPaths] (TDD §8.2 scenarios 1-6, 6a-6d).
 *
 * Uses JUnit5 @TempDir for real filesystem operations and a relaxed mockk for
 * [OpenCodeMcpSettingsState] (following the McpManagerRetryTest pattern).
 * Verifies the skills.paths array is written, merged, deduplicated, and that
 * stale plugin-managed paths are evicted while user-added paths and skills.urls
 * are preserved.
 */
class McpConfigWriterSkillPathsTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun newWriter(): McpConfigWriter {
        val settings = mockk<OpenCodeMcpSettingsState>(relaxed = true)
        every { settings.enableIntellijMcp } returns false
        every { settings.mcpServerUrl } returns ""
        every { settings.additionalMcpServers } returns ""
        return McpConfigWriter(tempDir, settings)
    }

    private fun writeInitialConfig(jsonContent: String) {
        val opencodeDir = tempDir.resolve(".opencode")
        Files.createDirectories(opencodeDir)
        Files.writeString(opencodeDir.resolve("opencode.json"), jsonContent)
    }

    private fun configPath(): Path = tempDir.resolve(".opencode").resolve("opencode.json")

    private fun readConfig(): JsonObject =
        json.parseToJsonElement(Files.readString(configPath())).jsonObject

    private fun skillsPaths(): List<String> {
        val skills = readConfig()["skills"]?.jsonObject ?: return emptyList()
        val paths = skills["paths"]?.jsonArray ?: return emptyList()
        return paths.map { it.jsonPrimitive.content }
    }

    private fun skillsUrls(): JsonArray? =
        readConfig()["skills"]?.jsonObject?.get("urls")?.jsonArray

    @Test
    fun `scenario 1 - writeSkillPaths writes skills paths to empty config`() {
        val writer = newWriter()
        val result = writer.writeSkillPaths(listOf("/some/ide/skills"))
        result shouldBe true

        val paths = skillsPaths()
        paths shouldBe listOf("/some/ide/skills")
    }

    @Test
    fun `scenario 2 - writeSkillPaths merges with existing user skills paths`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["/user/custom/skills"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(listOf("/ide/aia/agents/.agents/skills"))
        result shouldBe true

        val paths = skillsPaths()
        paths shouldNotBe emptyList<String>()
        paths.size shouldBe 2
        paths.contains("/user/custom/skills") shouldBe true
        paths.contains("/ide/aia/agents/.agents/skills") shouldBe true
    }

    @Test
    fun `scenario 3 - writeSkillPaths preserves skills urls`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "urls": ["https://example.com/skills.json"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(listOf("/ide/aia/agents/.agents/skills"))
        result shouldBe true

        val urls = skillsUrls()
        urls shouldNotBe null
        urls!![0].jsonPrimitive.content shouldBe "https://example.com/skills.json"

        // paths should also be written
        val paths = skillsPaths()
        paths shouldBe listOf("/ide/aia/agents/.agents/skills")
    }

    @Test
    fun `scenario 4 - writeSkillPaths preserves mcp and agent sections`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "mcp": {
                "intellij": {
                  "type": "remote",
                  "url": "http://127.0.0.1:64342/sse"
                }
              },
              "agent": {
                "coding-assistant": {
                  "model": "gpt-4"
                }
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(listOf("/ide/aia/agents/.agents/skills"))
        result shouldBe true

        val config = readConfig()
        val mcp = config["mcp"]?.jsonObject
        mcp shouldNotBe null
        mcp!!["intellij"]?.jsonObject?.get("url")?.jsonPrimitive?.content shouldBe "http://127.0.0.1:64342/sse"

        val agent = config["agent"]?.jsonObject
        agent shouldNotBe null
        agent!!["coding-assistant"]?.jsonObject?.get("model")?.jsonPrimitive?.content shouldBe "gpt-4"
    }

    @Test
    fun `scenario 5 - writeSkillPaths with empty list preserves user paths and urls and evicts plugin-managed paths`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": [
                  "/ide/aia/agents/.agents/skills",
                  "/user/custom/skills"
                ],
                "urls": ["https://example.com/skills.json"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(emptyList())
        result shouldBe true

        val paths = skillsPaths()
        // Plugin-managed path evicted, user path preserved
        paths shouldBe listOf("/user/custom/skills")

        // urls preserved
        val urls = skillsUrls()
        urls shouldNotBe null
        urls!![0].jsonPrimitive.content shouldBe "https://example.com/skills.json"
    }

    @Test
    fun `scenario 6 - writeSkillPaths deduplicates identical paths`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["/user/custom/skills"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        // Pass a path that overlaps with the existing user path
        val result = writer.writeSkillPaths(listOf("/user/custom/skills", "/ide/aia/agents/.agents/skills"))
        result shouldBe true

        val paths = skillsPaths()
        // No duplicates
        paths.size shouldBe 2
        paths.contains("/user/custom/skills") shouldBe true
        paths.contains("/ide/aia/agents/.agents/skills") shouldBe true
    }

    @Test
    fun `scenario 6a - writeSkillPaths evicts stale IDE-version paths`() {
        // Pre-populate with an old IDE-version path (matches the plugin-managed pattern)
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["/old/IntelliJIdea2026.1/aia/agents/.agents/skills"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        // Call with the new IDE-version path
        val result = writer.writeSkillPaths(listOf("/new/IntelliJIdea2026.2/aia/agents/.agents/skills"))
        result shouldBe true

        val paths = skillsPaths()
        // Old path evicted, new path written
        paths.size shouldBe 1
        paths[0] shouldBe "/new/IntelliJIdea2026.2/aia/agents/.agents/skills"
        paths.contains("/old/IntelliJIdea2026.1/aia/agents/.agents/skills") shouldBe false
    }

    @Test
    fun `scenario 6b - writeSkillPaths evicts all plugin-managed paths when detectSkillPaths returns empty`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": [
                  "/ide/aia/agents/.agents/skills",
                  "/home/user/extra-ide/aia/agents/.agents/skills",
                  "/user/custom/skills"
                ]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(emptyList())
        result shouldBe true

        val paths = skillsPaths()
        // Both plugin-managed IDE paths evicted; user custom path preserved
        paths shouldBe listOf("/user/custom/skills")
    }

    @Test
    fun `scenario 6c - writeSkillPaths preserves skills urls when paths is empty`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "urls": ["https://example.com/skills.json"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(emptyList())
        result shouldBe true

        val config = readConfig()
        val skills = config["skills"]?.jsonObject
        skills shouldNotBe null
        // urls preserved
        skills!!["urls"]?.jsonArray?.get(0)?.jsonPrimitive?.content shouldBe "https://example.com/skills.json"
        // no paths key (empty list + no existing paths)
        skills["paths"] shouldBe null
    }

    @Test
    fun `scenario 6d - writeSkillPaths handles Windows backslash paths in pattern matching`() {
        // Pre-populate with a Windows backslash path that matches the plugin-managed pattern
        val windowsPath =
            "C:\\Users\\josen\\AppData\\Local\\JetBrains\\IntelliJIdea2026.1\\aia\\agents\\.agents\\skills"
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["${windowsPath.replace("\\", "\\\\")}"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(emptyList())
        result shouldBe true

        val paths = skillsPaths()
        // Windows backslash plugin-managed path evicted
        paths shouldBe emptyList()
    }

    @Test
    fun `scenario 7 - writeSkillPaths preserves unknown skills keys`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["/user/custom/skills"],
                "custom": true,
                "maxSize": 100
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(listOf("/ide/aia/agents/.agents/skills"))
        result shouldBe true

        val skills = readConfig()["skills"]?.jsonObject
        skills shouldNotBe null
        // Unknown keys preserved
        skills!!["custom"]?.jsonPrimitive?.content shouldBe "true"
        skills["maxSize"]?.jsonPrimitive?.content shouldBe "100"
        // paths still written correctly
        val paths = skills["paths"]?.jsonArray ?: emptyList()
        paths.size shouldBe 2
        paths.map { it.jsonPrimitive.content }.contains("/user/custom/skills") shouldBe true
        paths.map { it.jsonPrimitive.content }.contains("/ide/aia/agents/.agents/skills") shouldBe true
    }

    @Test
    fun `scenario 8 - user path containing plugin-managed substring is NOT evicted`() {
        // A user path that CONTAINS the plugin-managed substring but does NOT
        // end with it should be preserved (not evicted as plugin-managed).
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "skills": {
                "paths": ["/home/user/my-aia/agents/.agents/skills-backup"]
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeSkillPaths(emptyList())
        result shouldBe true

        val paths = skillsPaths()
        // This path contains '/aia/agents/.agents/skills' as a substring but
        // does NOT end with it (ends with 'skills-backup'), so it's user-added
        // and must be preserved.
        paths shouldBe listOf("/home/user/my-aia/agents/.agents/skills-backup")
    }

    // ── writeAgentOverrides: stale agent eviction ────────────────────

    @Test
    fun `writeAgentOverrides evicts stale orchestrator agent entry`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "agent": {
                "orchestrator": {
                  "permission": { "read": "allow", "edit": "deny" }
                },
                "coding-assistant": {
                  "model": "gpt-4"
                }
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result =
            writer.writeAgentOverrides(enableExplore = true, enableGeneral = true, disabledAgentNames = emptyList())
        result shouldBe true

        val agent = readConfig()["agent"]?.jsonObject
        agent shouldNotBe null
        // orchestrator must be evicted
        agent!!["orchestrator"] shouldBe null
        // coding-assistant must be preserved
        agent["coding-assistant"]?.jsonObject?.get("model")?.jsonPrimitive?.content shouldBe "gpt-4"
    }

    @Test
    fun `writeAgentOverrides evicts orchestrator and applies explore general overrides`() {
        writeInitialConfig(
            """{
              "${'$'}schema": "https://opencode.ai/config.json",
              "agent": {
                "orchestrator": { "permission": { "read": "allow" } },
                "explore": { "disable": true },
                "general": { "disable": true }
              }
            }""".trimIndent()
        )

        val writer = newWriter()
        val result = writer.writeAgentOverrides(
            enableExplore = true,
            enableGeneral = true,
            disabledAgentNames = listOf("adversarial-glm-5.1")
        )
        result shouldBe true

        val agent = readConfig()["agent"]?.jsonObject
        agent shouldNotBe null
        agent!!["orchestrator"] shouldBe null
        agent["explore"]?.jsonObject?.get("disable")?.jsonPrimitive?.content shouldBe "false"
        agent["general"]?.jsonObject?.get("disable")?.jsonPrimitive?.content shouldBe "false"
        agent["adversarial-glm-5.1"]?.jsonObject?.get("disable")?.jsonPrimitive?.content shouldBe "true"
    }
}