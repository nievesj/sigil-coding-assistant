package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentMapperTest {
    private val mapper = ContentMapper()

    @Test
    fun `text content block maps to OpenCodePart Text`() {
        val block = ContentBlock.Text(text = "Hello world")
        val result = mapper.toOpenCodePart(block)
        assertTrue(result is OpenCodePart.Text)
        assertEquals("Hello world", (result as OpenCodePart.Text).text)
    }

    @Test
    fun `image content block returns null`() {
        val block = ContentBlock.Image(data = "base64data", mimeType = "image/png")
        assertNull(mapper.toOpenCodePart(block))
    }

    @Test
    fun `audio content block returns null`() {
        val block = ContentBlock.Audio(data = "base64data", mimeType = "audio/wav")
        assertNull(mapper.toOpenCodePart(block))
    }

    @Test
    fun `OpenCodePart Text maps back to ContentBlock Text`() {
        val part = OpenCodePart.Text(text = "Hello")
        val result = mapper.toContentBlock(part)
        assertTrue(result is ContentBlock.Text)
        assertEquals("Hello", (result as ContentBlock.Text).text)
    }

    @Test
    fun `toOpenCodeParts filters unsupported types`() {
        val blocks = listOf(
            ContentBlock.Text(text = "supported"),
            ContentBlock.Image(data = "data", mimeType = "image/png")
        )
        val result = mapper.toOpenCodeParts(blocks)
        assertEquals(1, result.size)
        assertTrue(result[0] is OpenCodePart.Text)
    }

    @Test
    fun `toContentBlocks converts all parts`() {
        val parts = listOf(
            OpenCodePart.Text(text = "hello"),
            OpenCodePart.Text(text = "world")
        )
        val result = mapper.toContentBlocks(parts)
        assertEquals(2, result.size)
    }
}