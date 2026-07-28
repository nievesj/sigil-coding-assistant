package com.opencode.acp.mcp

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class McpConfigInstructionsMergeTest {
    private val ourGlob = ".opencode/context/**/*.md"

    @Test
    fun `empty array - appends our glob`() {
        val existing = buildJsonArray {}
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result.size shouldBe 1
        result[0].jsonPrimitive.content shouldBe ourGlob
    }

    @Test
    fun `exact match - returns unchanged`() {
        val existing = buildJsonArray {
            add(JsonPrimitive(ourGlob))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result shouldBe existing
        result.size shouldBe 1
    }

    @Test
    fun `covering glob - returns unchanged`() {
        // `.opencode/context/*.md` covers `.opencode/context/**/*.md` after normalization
        val existing = buildJsonArray {
            add(JsonPrimitive(".opencode/context/*.md"))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result shouldBe existing
        result.size shouldBe 1
        result[0].jsonPrimitive.content shouldBe ".opencode/context/*.md"
    }

    @Test
    fun `unrelated entries - appends our glob and preserves existing`() {
        val existing = buildJsonArray {
            add(JsonPrimitive("README.md"))
            add(JsonPrimitive("docs/*.md"))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result.size shouldBe 3
        result[0].jsonPrimitive.content shouldBe "README.md"
        result[1].jsonPrimitive.content shouldBe "docs/*.md"
        result[2].jsonPrimitive.content shouldBe ourGlob
    }

    @Test
    fun `non-string entries - preserved and our glob appended`() {
        val existing = Json.parseToJsonElement("""[{"type":"file","path":"foo.md"}]""").jsonArray
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result.size shouldBe 2
        // Non-string entry preserved
        (result[0] is kotlinx.serialization.json.JsonObject) shouldBe true
        // Our glob appended
        result[1].jsonPrimitive.content shouldBe ourGlob
    }

    @Test
    fun `mixed string and non-string entries - all preserved and our glob appended`() {
        val existing = Json.parseToJsonElement(
            """["README.md",{"type":"file","path":"foo.md"},"docs/*.md"]"""
        ).jsonArray
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result.size shouldBe 4
        result[0].jsonPrimitive.content shouldBe "README.md"
        result[1].toString() shouldBe """{"type":"file","path":"foo.md"}"""
        result[2].jsonPrimitive.content shouldBe "docs/*.md"
        result[3].jsonPrimitive.content shouldBe ourGlob
    }

    @Test
    fun `our glob covered by broader glob - returns unchanged`() {
        // Existing has `.opencode/**/*.md` which covers `.opencode/context/**/*.md`
        val existing = buildJsonArray {
            add(JsonPrimitive(".opencode/**/*.md"))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result shouldBe existing
        result.size shouldBe 1
        result[0].jsonPrimitive.content shouldBe ".opencode/**/*.md"
    }

    @Test
    fun `symmetric coverage - our glob covers existing narrower glob still appends`() {
        // If existing has the narrower glob and ours is broader, ours is NOT a duplicate
        // (existing does not cover ours), so ours should be appended.
        val existing = buildJsonArray {
            add(JsonPrimitive(".opencode/context/*.md"))
        }
        val broaderGlob = ".opencode/**/*.md"
        val result = McpConfigWriter.mergeInstructions(existing, broaderGlob)
        // existing `.opencode/context/*.md` does NOT cover `.opencode/**/*.md`
        // (normalized: `.opencode/context/*.md` is not a prefix of `.opencode/*.md`)
        result.size shouldBe 2
        result[0].jsonPrimitive.content shouldBe ".opencode/context/*.md"
        result[1].jsonPrimitive.content shouldBe broaderGlob
    }

    @Test
    fun `multiple existing globs with one covering - returns unchanged`() {
        val existing = buildJsonArray {
            add(JsonPrimitive("README.md"))
            add(JsonPrimitive(".opencode/context/*.md"))
            add(JsonPrimitive("docs/**/*.md"))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result shouldBe existing
        result.size shouldBe 3
    }

    @Test
    fun `our glob is exact match among multiple entries - returns unchanged`() {
        val existing = buildJsonArray {
            add(JsonPrimitive("README.md"))
            add(JsonPrimitive(ourGlob))
            add(JsonPrimitive("docs/*.md"))
        }
        val result = McpConfigWriter.mergeInstructions(existing, ourGlob)
        result shouldBe existing
        result.size shouldBe 3
    }
}