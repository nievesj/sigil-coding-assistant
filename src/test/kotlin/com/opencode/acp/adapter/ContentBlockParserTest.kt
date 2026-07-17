package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ContentBlockParser] (TDD §4.2.6).
 *
 * Tests ACP→OpenCode content block parsing:
 * - Text → OpenCodePart.Text
 * - Image/Audio/Resource/ResourceLink → null (unsupported)
 * - toOpenCodeParts filters unsupported types
 *
 * No mocking — uses real ContentBlock instances.
 */
class ContentBlockParserTest {

    private val parser = ContentBlockParser()

    // ── toOpenCodePart (single block) ──────────────────────────────────────

    @Test
    fun `Text block maps to OpenCodePart Text`() {
        val block = ContentBlock.Text(text = "Hello world")
        val result = parser.toOpenCodePart(block)
        result shouldNotBe null
        assertInstanceOf(OpenCodePart.Text::class.java, result)
        (result as OpenCodePart.Text).text shouldBe "Hello world"
    }

    @Test
    fun `Text block with empty string maps to OpenCodePart Text`() {
        val block = ContentBlock.Text(text = "")
        val result = parser.toOpenCodePart(block)
        assertInstanceOf(OpenCodePart.Text::class.java, result)
        (result as OpenCodePart.Text).text shouldBe ""
    }

    @Test
    fun `Text block with unicode maps to OpenCodePart Text`() {
        val block = ContentBlock.Text(text = "Hello 世界 🌍")
        val result = parser.toOpenCodePart(block)
        assertInstanceOf(OpenCodePart.Text::class.java, result)
        (result as OpenCodePart.Text).text shouldBe "Hello 世界 🌍"
    }

    @Test
    fun `Image block returns null`() {
        val block = ContentBlock.Image(data = "base64data", mimeType = "image/png")
        parser.toOpenCodePart(block) shouldBe null
    }

    @Test
    fun `Audio block returns null`() {
        val block = ContentBlock.Audio(data = "base64data", mimeType = "audio/wav")
        parser.toOpenCodePart(block) shouldBe null
    }

    @Test
    fun `Resource block returns null`() {
        val embedded = EmbeddedResourceResource.TextResourceContents(
            text = "content", uri = "file:///tmp/x"
        )
        val block = ContentBlock.Resource(resource = embedded)
        parser.toOpenCodePart(block) shouldBe null
    }

    @Test
    fun `ResourceLink block returns null`() {
        val block = ContentBlock.ResourceLink(name = "test", uri = "file:///tmp/y")
        parser.toOpenCodePart(block) shouldBe null
    }

    // ── toOpenCodeParts (list filtering) ──────────────────────────────────

    @Test
    fun `toOpenCodeParts returns empty list for empty input`() {
        parser.toOpenCodeParts(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `toOpenCodeParts keeps only Text blocks`() {
        val blocks = listOf(
            ContentBlock.Text(text = "first"),
            ContentBlock.Image(data = "d", mimeType = "image/png"),
            ContentBlock.Text(text = "second"),
        )
        val result = parser.toOpenCodeParts(blocks)
        result.size shouldBe 2
        (result[0] as OpenCodePart.Text).text shouldBe "first"
        (result[1] as OpenCodePart.Text).text shouldBe "second"
    }

    @Test
    fun `toOpenCodeParts filters all unsupported types`() {
        val embedded = EmbeddedResourceResource.TextResourceContents(
            text = "c", uri = "file:///x"
        )
        val blocks = listOf(
            ContentBlock.Image(data = "d1", mimeType = "image/png"),
            ContentBlock.Audio(data = "d2", mimeType = "audio/wav"),
            ContentBlock.Resource(resource = embedded),
            ContentBlock.ResourceLink(name = "n", uri = "file:///y"),
        )
        parser.toOpenCodeParts(blocks) shouldBe emptyList()
    }

    @Test
    fun `toOpenCodeParts with all Text blocks returns all`() {
        val blocks = listOf(
            ContentBlock.Text(text = "a"),
            ContentBlock.Text(text = "b"),
            ContentBlock.Text(text = "c"),
        )
        val result = parser.toOpenCodeParts(blocks)
        result.size shouldBe 3
    }

    @Test
    fun `toOpenCodeParts preserves order of supported blocks`() {
        val blocks = listOf(
            ContentBlock.Text(text = "1"),
            ContentBlock.Image(data = "d", mimeType = "image/png"),
            ContentBlock.Text(text = "2"),
            ContentBlock.Audio(data = "d", mimeType = "audio/wav"),
            ContentBlock.Text(text = "3"),
        )
        val result = parser.toOpenCodeParts(blocks)
        result.size shouldBe 3
        (result[0] as OpenCodePart.Text).text shouldBe "1"
        (result[1] as OpenCodePart.Text).text shouldBe "2"
        (result[2] as OpenCodePart.Text).text shouldBe "3"
    }
}