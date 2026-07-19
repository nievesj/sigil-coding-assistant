package com.opencode.acp.chat.util

import com.opencode.acp.chat.util.JsonUtils.getFilePath
import com.opencode.acp.chat.util.JsonUtils.getInt
import com.opencode.acp.chat.util.JsonUtils.getString
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JsonUtils] (TDD §4.2.6).
 *
 * Tests JSON field extraction helpers: getString, getInt, getFilePath.
 * No mocking — uses real JsonObject instances.
 */
class JsonUtilsTest {

    @Test
    fun `getString returns value for present string field`() {
        val obj = buildJsonObject { put("name", "hello") }
        obj.getString("name") shouldBe "hello"
    }

    @Test
    fun `getString returns null for absent field`() {
        val obj = buildJsonObject { put("other", "x") }
        obj.getString("name") shouldBe null
    }

    @Test
    fun `getString returns null for non-primitive field`() {
        val obj = buildJsonObject { put("nested", buildJsonObject { put("a", "b") }) }
        obj.getString("nested") shouldBe null
    }

    @Test
    fun `getString returns null for empty object`() {
        val obj = JsonObject(emptyMap())
        obj.getString("anything") shouldBe null
    }

    @Test
    fun `getInt returns value for int primitive`() {
        val obj = buildJsonObject { put("count", 42) }
        obj.getInt("count") shouldBe 42
    }

    @Test
    fun `getInt returns value for string-encoded int`() {
        val obj = buildJsonObject { put("count", "99") }
        obj.getInt("count") shouldBe 99
    }

    @Test
    fun `getInt returns null for non-numeric string`() {
        val obj = buildJsonObject { put("count", "abc") }
        obj.getInt("count") shouldBe null
    }

    @Test
    fun `getInt returns null for absent field`() {
        val obj = buildJsonObject { put("other", 1) }
        obj.getInt("count") shouldBe null
    }

    @Test
    fun `getInt throws IllegalArgumentException for object field`() {
        val obj = buildJsonObject { put("count", buildJsonObject {}) }
        // jsonPrimitive throws for non-primitive elements (by design — getInt uses jsonPrimitive)
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            obj.getInt("count")
        }
    }

    // ── getFilePath (fallback chain) ──────────────────────────────────────

    @Test
    fun `getFilePath returns filePath when present`() {
        val obj = buildJsonObject { put("filePath", "/a/b") }
        obj.getFilePath() shouldBe "/a/b"
    }

    @Test
    fun `getFilePath falls back to file_path`() {
        val obj = buildJsonObject { put("file_path", "/a/b") }
        obj.getFilePath() shouldBe "/a/b"
    }

    @Test
    fun `getFilePath falls back to path`() {
        val obj = buildJsonObject { put("path", "/a/b") }
        obj.getFilePath() shouldBe "/a/b"
    }

    @Test
    fun `getFilePath prefers filePath over file_path`() {
        val obj = buildJsonObject {
            put("filePath", "/first")
            put("file_path", "/second")
        }
        obj.getFilePath() shouldBe "/first"
    }

    @Test
    fun `getFilePath prefers file_path over path`() {
        val obj = buildJsonObject {
            put("file_path", "/second")
            put("path", "/third")
        }
        obj.getFilePath() shouldBe "/second"
    }

    @Test
    fun `getFilePath returns null when no path field present`() {
        val obj = buildJsonObject { put("other", "x") }
        obj.getFilePath() shouldBe null
    }

    @Test
    fun `getFilePath returns null for empty object`() {
        val obj = JsonObject(emptyMap())
        obj.getFilePath() shouldBe null
    }

    @Test
    fun `getFilePath returns null when field is not a primitive`() {
        val obj = buildJsonObject { put("filePath", buildJsonObject {}) }
        obj.getFilePath() shouldBe null
    }
}