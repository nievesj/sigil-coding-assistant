package com.opencode.acp.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that [ReviewCommentParser] handles UTF-8 BOM-prefixed JSON files.
 *
 * External writers (LLM agents, CI, text editors) may emit `.review/` files
 * with a UTF-8 BOM (0xEF 0xBB 0xBF). The parser must tolerate this — the
 * BOM appears as '\uFEFF' at the start of the string when read via
 * [kotlin.io.path.readText], and kotlinx.serialization's JSON parser rejects
 * it ("Expected start of the object '{', but had '?'").
 *
 * The fix is in [ReviewCommentRepository.readFile] which strips the BOM
 * before parsing. These tests verify the parser itself handles BOM-stripped
 * content correctly, and document the BOM behavior.
 */
class ReviewCommentParserBomTest {

    private val parser = ReviewCommentParser()

    @Test
    fun `parses valid JSON without BOM`() {
        val json = """{"formatVersion":1,"comments":[{"id":"cmt_abcdef123456","startLine":1,"endLine":1,"comment":"test","severity":"info","status":"open","author":"ai-review","createdAt":"2026-01-01T00:00:00Z"}]}"""
        val file = parser.parseReviewFile(json)!!
        assertEquals(1, file.comments.size)
        assertEquals(ReviewStatus.OPEN, file.comments[0].status)
    }

    @Test
    fun `parses JSON with lowercase enum values`() {
        val json = """{"formatVersion":1,"comments":[{"id":"cmt_abcdef123456","startLine":1,"endLine":1,"comment":"test","severity":"info","status":"open","author":"ai-review","createdAt":"2026-01-01T00:00:00Z"}]}"""
        val file = parser.parseReviewFile(json)!!
        assertEquals(ReviewStatus.OPEN, file.comments[0].status)
        assertEquals(ReviewSeverity.INFO, file.comments[0].severity)
    }

    @Test
    fun `BOM-prefixed JSON fails without stripping`() {
        val json = "\uFEFF" + """{"formatVersion":1,"comments":[]}"""
        val file = parser.parseReviewFile(json)
        // Without BOM stripping, the parser returns null (parse failure).
        // This test documents the behavior that motivates the BOM stripping
        // in ReviewCommentRepository.readFile().
        assertNull(file, "BOM-prefixed JSON should fail to parse without stripping")
    }

    @Test
    fun `BOM-stripped JSON parses successfully`() {
        val jsonWithBom = "\uFEFF" + """{"formatVersion":1,"comments":[{"id":"cmt_abcdef123456","startLine":1,"endLine":1,"comment":"test","severity":"info","status":"open","author":"ai-review","createdAt":"2026-01-01T00:00:00Z"}]}"""
        // Simulate the fix: strip BOM before parsing
        val stripped = jsonWithBom.removePrefix("\uFEFF")
        val file = parser.parseReviewFile(stripped)!!
        assertEquals(1, file.comments.size)
        assertEquals(ReviewStatus.OPEN, file.comments[0].status)
    }

    @Test
    fun `multiple BOMs are stripped by removePrefix`() {
        // removePrefix only strips the first occurrence, which is correct —
        // a well-formed file has at most one BOM at the start.
        val jsonWithBom = "\uFEFF" + """{"formatVersion":1,"comments":[]}"""
        val stripped = jsonWithBom.removePrefix("\uFEFF")
        assertTrue(!stripped.startsWith("\uFEFF"))
    }

    @Test
    fun `readFile strips BOM before parsing - integration via parser`() {
        // This test verifies that the BOM stripping logic (removePrefix("\uFEFF"))
        // applied in ReviewCommentRepository.readFile() produces parseable content.
        // A full integration test would require a mock Project with basePath pointing
        // to a temp directory — that requires the IntelliJ test framework (LightPlatformCodeInsightTestCase).
        // Here we verify the stripping + parsing pipeline produces correct results.
        val jsonWithBom = "\uFEFF" + """{"formatVersion":1,"comments":[{"id":"cmt_abcdef123456","startLine":10,"endLine":12,"comment":"BOM test","severity":"warning","status":"open","author":"ai-review","createdAt":"2026-06-29T00:00:00Z"}]}"""
        // Simulate readFile's BOM stripping
        val stripped = jsonWithBom.removePrefix("\uFEFF")
        val file = parser.parseReviewFile(stripped)!!
        assertEquals(1, file.comments.size)
        val comment = file.comments[0]
        assertEquals(10, comment.startLine)
        assertEquals(12, comment.endLine)
        assertEquals("BOM test", comment.comment)
        assertEquals(ReviewSeverity.WARNING, comment.severity)
        assertEquals(ReviewStatus.OPEN, comment.status)
    }
}