package com.opencode.acp.adapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unit tests for [OpenCodePartSerializer] (TDD §4.2.5).
 *
 * Tests the custom [KSerializer] for [OpenCodePart]:
 * - Each known `type` discriminator deserializes to the correct [OpenCodePart] subtype.
 * - Unknown types fall back to [OpenCodePart.Unknown] gracefully (no crash).
 * - `tool`/`tool_use` types restructure `state.input` → top-level `input`.
 * - Outbound serialization of [OpenCodePart.Text] and [OpenCodePart.File] produces the expected JSON.
 *
 * No mocking — uses real kotlinx.serialization Json round-trips.
 */
class OpenCodePartSerializerTest {

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    // ── Deserialize: known types ──────────────────────────────────────────

    @Test
    fun `deserialize text part`() {
        val part = json.decodeFromString(OpenCodePart.serializer(), """{"type":"text","text":"hello"}""")
        (part as OpenCodePart.Text).text shouldBe "hello"
    }

    @Test
    fun `deserialize thinking part`() {
        val part = json.decodeFromString(OpenCodePart.serializer(), """{"type":"thinking","text":"hmm"}""")
        (part as OpenCodePart.Thinking).text shouldBe "hmm"
    }

    @Test
    fun `deserialize reasoning part`() {
        val part = json.decodeFromString(OpenCodePart.serializer(), """{"type":"reasoning","text":"why"}""")
        (part as OpenCodePart.Reasoning).text shouldBe "why"
    }

    @Test
    fun `deserialize file part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"file","mime":"text/plain","url":"file:///x"}"""
        )
        val file = part as OpenCodePart.File
        file.mime shouldBe "text/plain"
        file.url shouldBe "file:///x"
        file.filename shouldBe null
    }

    @Test
    fun `deserialize file part with filename`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"file","mime":"text/plain","url":"file:///x","filename":"a.kt"}"""
        )
        val file = part as OpenCodePart.File
        file.filename shouldBe "a.kt"
    }

    @Test
    fun `deserialize image part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"image","mime":"image/png","url":"file:///img"}"""
        )
        val image = part as OpenCodePart.Image
        image.mime shouldBe "image/png"
        image.url shouldBe "file:///img"
    }

    @Test
    fun `deserialize patch part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"patch","hash":"abc","files":["a.kt"]}"""
        )
        val patch = part as OpenCodePart.Patch
        patch.hash shouldBe "abc"
        patch.files shouldBe listOf("a.kt")
    }

    @Test
    fun `deserialize agent part`() {
        val part = json.decodeFromString(OpenCodePart.serializer(), """{"type":"agent","name":"fixer"}""")
        (part as OpenCodePart.Agent).name shouldBe "fixer"
    }

    @Test
    fun `deserialize retry part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"retry","attempt":2,"maxAttempts":5}"""
        )
        val retry = part as OpenCodePart.Retry
        retry.attempt shouldBe 2
        retry.maxAttempts shouldBe 5
    }

    @Test
    fun `deserialize compaction part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"compaction","summary":"summarized"}"""
        )
        (part as OpenCodePart.Compaction).summary shouldBe "summarized"
    }

    @Test
    fun `deserialize snapshot part`() {
        val part = json.decodeFromString(OpenCodePart.serializer(), """{"type":"snapshot","id":"snap1"}""")
        (part as OpenCodePart.Snapshot).id shouldBe "snap1"
    }

    @Test
    fun `deserialize subtask part`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"subtask","prompt":"do thing"}"""
        )
        (part as OpenCodePart.Subtask).prompt shouldBe "do thing"
    }

    // ── Deserialize: tool_use / tool ──────────────────────────────────────

    @Test
    fun `deserialize tool_use part with top-level input`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"tool_use","id":"tc_1","tool":"bash","input":{"command":"ls"}}"""
        )
        val toolUse = part as OpenCodePart.ToolUse
        toolUse.id shouldBe "tc_1"
        toolUse.name shouldBe "bash"
        toolUse.input["command"]?.jsonPrimitive?.contentOrNull shouldBe "ls"
    }

    @Test
    fun `deserialize tool part extracts input from state_input`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"tool","id":"tc_2","tool":"read","state":{"input":{"path":"/x"},"output":"data"}}"""
        )
        val toolUse = part as OpenCodePart.ToolUse
        toolUse.id shouldBe "tc_2"
        toolUse.name shouldBe "read"
        toolUse.input["path"]?.jsonPrimitive?.contentOrNull shouldBe "/x"
    }

    @Test
    fun `deserialize tool part injects state_title at top level`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"tool","id":"tc_3","tool":"write","state":{"input":{"path":"/y"},"title":"Write file"}}"""
        )
        val toolUse = part as OpenCodePart.ToolUse
        toolUse.title shouldBe "Write file"
    }

    // ── Deserialize: unknown type ─────────────────────────────────────────

    @Test
    fun `deserialize unknown type falls back to Unknown without crashing`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"type":"unknown_type","foo":"bar"}"""
        )
        val unknown = part as OpenCodePart.Unknown
        unknown.type shouldBe "unknown_type"
        unknown.rawJson["foo"]?.jsonPrimitive?.contentOrNull shouldBe "bar"
    }

    @Test
    fun `deserialize missing type field falls back to Unknown with unknown type label`() {
        val part = json.decodeFromString(
            OpenCodePart.serializer(),
            """{"foo":"bar"}"""
        )
        val unknown = part as OpenCodePart.Unknown
        unknown.type shouldBe "unknown"
    }

    // ── Serialize: outbound ───────────────────────────────────────────────

    @Test
    fun `serialize Text part produces type and text fields`() {
        val encoded = json.encodeToString(OpenCodePart.serializer(), OpenCodePart.Text("hi"))
        val obj = json.parseToJsonElement(encoded).jsonObject
        obj["type"]?.jsonPrimitive?.contentOrNull shouldBe "text"
        obj["text"]?.jsonPrimitive?.contentOrNull shouldBe "hi"
    }

    @Test
    fun `serialize File part produces type mime and url fields`() {
        val encoded = json.encodeToString(
            OpenCodePart.serializer(),
            OpenCodePart.File(mime = "text/plain", url = "file:///x", filename = "a.kt")
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        obj["type"]?.jsonPrimitive?.contentOrNull shouldBe "file"
        obj["mime"]?.jsonPrimitive?.contentOrNull shouldBe "text/plain"
        obj["url"]?.jsonPrimitive?.contentOrNull shouldBe "file:///x"
        obj["filename"]?.jsonPrimitive?.contentOrNull shouldBe "a.kt"
    }

    @Test
    fun `serialize File part without filename omits filename field`() {
        val encoded = json.encodeToString(
            OpenCodePart.serializer(),
            OpenCodePart.File(mime = "text/plain", url = "file:///x")
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        obj["type"]?.jsonPrimitive?.contentOrNull shouldBe "file"
        obj.containsKey("filename") shouldBe false
    }

    @Test
    fun `serialize Image part produces type mime and url fields`() {
        val encoded = json.encodeToString(
            OpenCodePart.serializer(),
            OpenCodePart.Image(mime = "image/png", url = "file:///img")
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        obj["type"]?.jsonPrimitive?.contentOrNull shouldBe "image"
        obj["mime"]?.jsonPrimitive?.contentOrNull shouldBe "image/png"
        obj["url"]?.jsonPrimitive?.contentOrNull shouldBe "file:///img"
    }

    @Test
    fun `round trip Text part preserves content`() {
        val original = OpenCodePart.Text("round trip")
        val encoded = json.encodeToString(OpenCodePart.serializer(), original)
        val decoded = json.decodeFromString(OpenCodePart.serializer(), encoded) as OpenCodePart.Text
        decoded.text shouldBe "round trip"
    }
}