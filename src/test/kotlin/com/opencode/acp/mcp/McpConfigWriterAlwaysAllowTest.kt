package com.opencode.acp.mcp

import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [McpConfigWriter.writeAlwaysAllowRule] — specifically the
 * deny-protection guards that prevent a single "Always Allow" click from
 * silently flipping an existing Deny rule to Allow (CWE-284).
 *
 * Regression coverage for the review comment (cmt_e5f6a7b8c9d0): the original
 * guard only protected the object form {"*": "deny"} and silently overwrote
 * a simple-string JsonPrimitive("deny"). Both forms must now be protected.
 */
class McpConfigWriterAlwaysAllowTest {

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

    private fun toolPermission(agent: String, tool: String): String? =
        readConfig()["agent"]?.jsonObject?.get(agent)?.jsonObject
            ?.get("permission")?.jsonObject?.get(tool)?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: it.toString()
            }

    @Test
    fun `writeAlwaysAllowRule overwrites a simple allow rule`() {
        writeInitialConfig(
            """{
              "agent": {
                "coding-assistant": {
                  "permission": { "bash": "allow" }
                }
              }
            }""".trimIndent()
        )
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bash", emptyList())
        result shouldBe true
        toolPermission("coding-assistant", "bash") shouldBe "allow"
    }

    @Test
    fun `writeAlwaysAllowRule refuses to overwrite a simple-string deny rule`() {
        // Regression: the original code only guarded the object form {"*": "deny"}.
        // A simple-string "deny" (written by writeToolPermissions) was silently
        // overwritten with "allow". This test verifies the fix guards both forms.
        writeInitialConfig(
            """{
              "agent": {
                "coding-assistant": {
                  "permission": { "bash": "deny" }
                }
              }
            }""".trimIndent()
        )
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bash", emptyList())
        // Returns false because the deny-guard preserves the existing deny (the
        // config file IS written with the deny preserved, but the method signals
        // "not applied" via false — see McpConfigWriter.writeAlwaysAllowRule KDoc).
        result shouldBe false
        // The deny must be preserved, NOT overwritten with "allow"
        toolPermission("coding-assistant", "bash") shouldBe "deny"
    }

    @Test
    fun `writeAlwaysAllowRule refuses to overwrite an object wildcard deny rule`() {
        // Existing object-form guard: {"*": "deny"} must not be flipped to allow.
        writeInitialConfig(
            """{
              "agent": {
                "coding-assistant": {
                  "permission": { "bash": { "*": "deny" } }
                }
              }
            }""".trimIndent()
        )
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bash", emptyList())
        // The wildcard deny must be preserved
        // Returns false because the deny-guard preserves the existing wildcard deny.
        result shouldBe false
        val perm =
            readConfig()["agent"]!!.jsonObject["coding-assistant"]!!.jsonObject["permission"]!!.jsonObject["bash"]!!.jsonObject
        perm["*"]?.jsonPrimitive?.content shouldBe "deny"
    }

    @Test
    fun `writeAlwaysAllowRule merges wildcard allow into object with pattern-specific allow rules`() {
        writeInitialConfig(
            """{
              "agent": {
                "coding-assistant": {
                  "permission": { "bash": { "ls *": "allow" } }
                }
              }
            }""".trimIndent()
        )
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bash", emptyList())
        result shouldBe true
        val perm =
            readConfig()["agent"]!!.jsonObject["coding-assistant"]!!.jsonObject["permission"]!!.jsonObject["bash"]!!.jsonObject
        // Pattern-specific allow preserved
        perm["ls *"]?.jsonPrimitive?.content shouldBe "allow"
        // Wildcard allow added
        perm["*"]?.jsonPrimitive?.content shouldBe "allow"
    }

    @Test
    fun `writeAlwaysAllowRule rejects invalid agentName`() {
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("in-valid/agent", "bash", emptyList())
        result shouldBe false
    }

    @Test
    fun `writeAlwaysAllowRule rejects invalid toolName`() {
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bad/tool", emptyList())
        result shouldBe false
    }

    @Test
    fun `writeAlwaysAllowRule writes allow when no existing permission`() {
        writeInitialConfig(
            """{
              "agent": {
                "coding-assistant": {}
              }
            }""".trimIndent()
        )
        val writer = newWriter()
        val result = writer.writeAlwaysAllowRule("coding-assistant", "bash", emptyList())
        result shouldBe true
        toolPermission("coding-assistant", "bash") shouldBe "allow"
    }
}