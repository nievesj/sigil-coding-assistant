package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure-logic parts of [PsiQueryHelper]:
 *  - [PsiQueryHelper.truncateResults]
 *  - [PsiQueryHelper.enforceTokenBudget] (via companion)
 *  - [isPublicApi] (top-level function in [PublicApiDetector])
 *
 * These tests do NOT require a [com.intellij.openapi.project.Project] and
 * therefore run in the main test task (no `@Tag("psi")`).
 */
class PsiQueryHelperTest {

    // ------------------------------------------------------------------
    // truncateResults
    // ------------------------------------------------------------------

    @Test
    fun `truncateResults with list smaller than limit returns all items, truncated false, correct total`() {
        val items = listOf("a", "b", "c")
        val (kept, truncated, total) = PsiQueryHelper.truncateResults(items, 10)
        kept shouldBe items
        truncated shouldBe false
        total shouldBe 3
    }

    @Test
    fun `truncateResults with list larger than limit returns limited items, truncated true, correct total`() {
        val items = (1..10).toList()
        val (kept, truncated, total) = PsiQueryHelper.truncateResults(items, 3)
        kept shouldBe listOf(1, 2, 3)
        truncated shouldBe true
        total shouldBe 10
    }

    @Test
    fun `truncateResults with list equal to limit returns all items, truncated false`() {
        val items = listOf("a", "b", "c")
        val (kept, truncated, total) = PsiQueryHelper.truncateResults(items, 3)
        kept shouldBe items
        truncated shouldBe false
        total shouldBe 3
    }

    // ------------------------------------------------------------------
    // enforceTokenBudget (companion)
    // ------------------------------------------------------------------

    @Test
    fun `enforceTokenBudget with short string returns unchanged`() {
        val json = """{"x":1}"""
        PsiQueryHelper.enforceTokenBudget(json) shouldBe json
    }

    @Test
    fun `enforceTokenBudget with string exceeding MAX_TOOL_OUTPUT_CHARS truncates and appends _truncated`() {
        val big = "x".repeat(MAX_TOOL_OUTPUT_CHARS + 100)
        val json = """{"data":"$big"}"""
        val result = PsiQueryHelper.enforceTokenBudget(json)
        result shouldContain """"_truncated":true"""
        (result.length <= MAX_TOOL_OUTPUT_CHARS + 50) shouldBe true
    }

    // ------------------------------------------------------------------
    // isPublicApi (top-level function)
    // ------------------------------------------------------------------

    @Test
    fun `isPublicApi returns true for public`() {
        isPublicApi(listOf("public")) shouldBe true
    }

    @Test
    fun `isPublicApi returns true for protected`() {
        isPublicApi(listOf("protected")) shouldBe true
    }

    @Test
    fun `isPublicApi returns true for Public (case-insensitive)`() {
        isPublicApi(listOf("Public")) shouldBe true
    }

    @Test
    fun `isPublicApi returns false for private`() {
        isPublicApi(listOf("private")) shouldBe false
    }

    @Test
    fun `isPublicApi returns false for empty list`() {
        isPublicApi(emptyList()) shouldBe false
    }

    @Test
    fun `isPublicApi returns false for static`() {
        isPublicApi(listOf("static")) shouldBe false
    }
}
