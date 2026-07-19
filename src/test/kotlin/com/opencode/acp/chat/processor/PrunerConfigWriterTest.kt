package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeContextSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [PrunerConfigWriter] (TDD §4.2.4).
 *
 * Uses JUnit5 @TempDir for real filesystem operations — no mocking.
 * Verifies the config file is written to `.opencode/sigil-pruner.json`,
 * contains the expected JSON sections, and that clearConfig is idempotent.
 */
class PrunerConfigWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun newWriter(): PrunerConfigWriter = PrunerConfigWriter(tempDir)

    private fun newSettings(): OpenCodeContextSettingsState = OpenCodeContextSettingsState().apply {
        // Use defaults — they are valid and exercised by buildConfigObject
        enableContextPruner = true
        prunerMaxToolOutputMessages = 20
        prunerErroredToolTurns = 4
        prunerCompressEnabled = true
        prunerCompressMode = "range"
        prunerNudgeEnabled = true
        prunerNudgeThresholdPercent = 60
        prunerNudgeUrgentPercent = 80
        prunerNudgeCooldownTurns = 3
        prunerDefaultContextLimit = 128000
    }

    private fun configPath(): Path =
        tempDir.resolve(".opencode").resolve(ChatConstants.PRUNER_CONFIG_FILENAME)

    @Test
    fun `writeConfig writes the JSON file to dot-opencode sigil-pruner json`() {
        val writer = newWriter()
        val result = writer.writeConfig(newSettings())
        result shouldBe true
        Files.exists(configPath()) shouldBe true
    }

    @Test
    fun `writeConfig returns true on success`() {
        val writer = newWriter()
        val result = writer.writeConfig(newSettings())
        result shouldBe true
    }

    @Test
    fun `writeConfig creates dot-opencode directory if it does not exist`() {
        // Pre-condition: .opencode does not exist yet
        Files.exists(tempDir.resolve(".opencode")) shouldBe false

        val writer = newWriter()
        val result = writer.writeConfig(newSettings())
        result shouldBe true
        Files.exists(tempDir.resolve(".opencode")) shouldBe true
        Files.exists(configPath()) shouldBe true
    }

    @Test
    fun `written JSON contains enabled pluginApiVersion deterministic compress and nudge sections`() {
        val writer = newWriter()
        writer.writeConfig(newSettings())

        val content = Files.readString(configPath())
        val root = json.parseToJsonElement(content).jsonObject

        // Top-level keys
        root["enabled"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        root["pluginApiVersion"]?.jsonPrimitive?.contentOrNull shouldBe
            ChatConstants.PRUNER_API_VERSION.toString()

        // deterministic section
        val deterministic = root["deterministic"]?.jsonObject
        deterministic shouldNotBe null
        deterministic!!["dedupEnabled"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        deterministic["pruneOldToolOutputs"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        deterministic["maxToolOutputMessages"]?.jsonPrimitive?.contentOrNull shouldBe "20"
        deterministic["pruneErroredToolInputs"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        deterministic["erroredToolTurns"]?.jsonPrimitive?.contentOrNull shouldBe "4"

        // compress section
        val compress = root["compress"]?.jsonObject
        compress shouldNotBe null
        compress!!["enabled"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        compress["mode"]?.jsonPrimitive?.contentOrNull shouldBe "range"

        // nudge section
        val nudge = root["nudge"]?.jsonObject
        nudge shouldNotBe null
        nudge!!["enabled"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        nudge["thresholdPercent"]?.jsonPrimitive?.contentOrNull shouldBe "60"
        nudge["urgentPercent"]?.jsonPrimitive?.contentOrNull shouldBe "80"
        nudge["cooldownTurns"]?.jsonPrimitive?.contentOrNull shouldBe "3"
        nudge["defaultContextLimit"]?.jsonPrimitive?.contentOrNull shouldBe "128000"
    }

    @Test
    fun `clearConfig removes the file and returns true`() {
        val writer = newWriter()
        writer.writeConfig(newSettings())
        Files.exists(configPath()) shouldBe true

        val result = writer.clearConfig()
        result shouldBe true
        Files.exists(configPath()) shouldBe false
    }

    @Test
    fun `clearConfig returns true even if file does not exist`() {
        val writer = newWriter()
        // No file written yet
        Files.exists(configPath()) shouldBe false

        val result = writer.clearConfig()
        result shouldBe true
    }

    @Test
    fun `writeConfig reflects disabled flag in JSON`() {
        val writer = newWriter()
        val settings = newSettings().apply { enableContextPruner = false }
        writer.writeConfig(settings)

        val content = Files.readString(configPath())
        val root = json.parseToJsonElement(content).jsonObject
        root["enabled"]?.jsonPrimitive?.contentOrNull shouldBe "false"
    }
}