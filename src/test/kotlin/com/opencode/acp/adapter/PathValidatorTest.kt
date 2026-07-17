package com.opencode.acp.adapter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [PathValidator] (TDD §8.1 — PathValidator scenarios).
 *
 * [PathValidator] is a stateless object — no mocks or IntelliJ services needed.
 * Covers [validatePathId] (allow-list enforcement) and [normalizeDirectoryPath]
 * (Windows backslash normalization).
 */
class PathValidatorTest {

    // -------------------------------------------------------------------------
    // validatePathId
    // -------------------------------------------------------------------------

    @Test
    fun `valid IDs are accepted`() {
        // Should not throw for any of these
        PathValidator.validatePathId("ses_abc123", "testId")
        PathValidator.validatePathId("msg_xyz", "testId")
        PathValidator.validatePathId("que_1234567890", "testId")
        PathValidator.validatePathId("ABCdef123", "testId")
        PathValidator.validatePathId("a", "testId")
        PathValidator.validatePathId("a-b_c", "testId")
        // 128 chars (max)
        PathValidator.validatePathId("a".repeat(128), "testId")
    }

    @Test
    fun `invalid IDs are rejected with IllegalArgumentException`() {
        val invalid = listOf(
            "",                          // empty
            "ses/abc",                   // slash — path traversal
            "ses\\abc",                  // backslash
            "ses abc",                   // space
            "ses.abc",                   // dot
            "ses:abc",                   // colon
            "ses?abc",                   // query separator
            "ses#abc",                   // fragment
            "ses@abc",                   // at-sign
            "ses%20abc",                 // percent-encoding
            "ses!abc",                   // bang
            "ses*abc",                   // star
            "a".repeat(129),            // 129 chars (over max)
        )
        for (id in invalid) {
            val ex = assertThrows<IllegalArgumentException> {
                PathValidator.validatePathId(id, "testId")
            }
            // Error message should include the field name and the regex for debugging
            ex.message shouldContain "testId"
            ex.message shouldContain "^[A-Za-z0-9_-]{1,128}$"
        }
    }

    @Test
    fun `error message includes the field name for debugging`() {
        val ex = assertThrows<IllegalArgumentException> {
            PathValidator.validatePathId("bad/id", "permissionId")
        }
        ex.message shouldContain "permissionId"
        ex.message shouldContain "bad/id"
    }

    @Test
    fun `boundary - exactly 128 chars accepted, 129 rejected`() {
        val max = "a".repeat(128)
        val over = "a".repeat(129)
        // Should not throw
        PathValidator.validatePathId(max, "id")
        assertThrows<IllegalArgumentException> {
            PathValidator.validatePathId(over, "id")
        }
    }

    // -------------------------------------------------------------------------
    // normalizeDirectoryPath
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeDirectoryPath replaces forward slashes with backslashes on Windows`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val input = "D:/Projects/foo/bar"
        val result = PathValidator.normalizeDirectoryPath(input)
        if (isWindows) {
            result shouldBe "D:\\Projects\\foo\\bar"
        } else {
            // Non-Windows: unchanged
            result shouldBe input
        }
    }

    @Test
    fun `normalizeDirectoryPath leaves backslash paths unchanged on Windows`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val input = "D:\\Projects\\foo\\bar"
        val result = PathValidator.normalizeDirectoryPath(input)
        // On Windows, no forward slashes to replace — unchanged.
        // On non-Windows, also unchanged (no forward slashes to replace).
        result shouldBe input
    }

    @Test
    fun `normalizeDirectoryPath handles mixed separators on Windows`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val input = "D:/Projects\\foo/bar"
        val result = PathValidator.normalizeDirectoryPath(input)
        if (isWindows) {
            result shouldBe "D:\\Projects\\foo\\bar"
        } else {
            result shouldBe input
        }
    }

    @Test
    fun `normalizeDirectoryPath handles relative paths`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val input = "relative/path/to/dir"
        val result = PathValidator.normalizeDirectoryPath(input)
        if (isWindows) {
            result shouldBe "relative\\path\\to\\dir"
        } else {
            result shouldBe input
        }
    }

    @Test
    fun `normalizeDirectoryPath handles single-segment path`() {
        val input = "nopathseparators"
        val result = PathValidator.normalizeDirectoryPath(input)
        // No slashes to replace on any platform
        result shouldBe input
    }
}