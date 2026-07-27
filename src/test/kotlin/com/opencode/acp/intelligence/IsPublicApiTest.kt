package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the top-level [isPublicApi] function in [PublicApiDetector].
 *
 * Verifies the visibility rules:
 *  - `public` and `protected` → true (public API surface)
 *  - `private`, package-private (empty), `internal` → false
 *  - case-insensitive matching
 */
class IsPublicApiTest {

    @Test
    fun `empty list returns false`() {
        isPublicApi(emptyList()) shouldBe false
    }

    @Test
    fun `public returns true`() {
        isPublicApi(listOf("public")) shouldBe true
    }

    @Test
    fun `protected returns true`() {
        isPublicApi(listOf("protected")) shouldBe true
    }

    @Test
    fun `private returns false`() {
        isPublicApi(listOf("private")) shouldBe false
    }

    @Test
    fun `public and static returns true`() {
        isPublicApi(listOf("public", "static")) shouldBe true
    }

    @Test
    fun `PRIVATE returns false (case-insensitive)`() {
        isPublicApi(listOf("PRIVATE")) shouldBe false
    }

    @Test
    fun `internal returns false (Kotlin internal is not public API)`() {
        isPublicApi(listOf("internal")) shouldBe false
    }
}