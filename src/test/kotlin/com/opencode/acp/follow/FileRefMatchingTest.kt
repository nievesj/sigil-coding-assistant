package com.opencode.acp.follow

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FileRefMatching] — the pure-logic helpers extracted from
 * [EditorFollowManager.resolveViaFilenameIndex].
 *
 * These tests cover the filename-extraction and match-selection logic that
 * does NOT require the IntelliJ Platform. The IntelliJ-dependent parts of
 * the fallback resolver (the `FilenameIndex` lookup, `VirtualFile` traversal
 * guard, read-action wrapping) are NOT covered here — they require a real
 * application context (see AGENTS.md "Compose UI Tests" section for the same
 * limitation pattern).
 */
class FileRefMatchingTest {

    // ── extractFileName ───────────────────────────────────────────────────

    @Test
    fun `extractFileName strips line suffix`() {
        FileRefMatching.extractFileName("src/Foo.kt:42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName strips line and column suffix`() {
        FileRefMatching.extractFileName("src/Foo.kt:42:10") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName strips hash-style line suffix`() {
        // The LINE_SUFFIX_REGEX strips trailing '#L42' suffixes.
        FileRefMatching.extractFileName("src/Foo.kt#L42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName returns filename for bare filename`() {
        FileRefMatching.extractFileName("Foo.kt") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName normalizes backslashes and strips line suffix`() {
        FileRefMatching.extractFileName("C:\\src\\Foo.kt:42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName normalizes backslashes and strips line and column suffix`() {
        FileRefMatching.extractFileName("C:\\src\\Foo.kt:42:10") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName returns last segment for absolute path`() {
        FileRefMatching.extractFileName("/abs/path/Foo.kt") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName returns null for line-only suffix`() {
        FileRefMatching.extractFileName(":42").shouldBeNull()
    }

    @Test
    fun `extractFileName returns null for empty string`() {
        FileRefMatching.extractFileName("").shouldBeNull()
    }

    @Test
    fun `extractFileName returns null for trailing slash`() {
        FileRefMatching.extractFileName("src/").shouldBeNull()
    }

    @Test
    fun `extractFileName trims whitespace around filename`() {
        FileRefMatching.extractFileName("src/  Foo.kt  :42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName strips hash-style line and column suffix`() {
        // The LINE_SUFFIX_REGEX strips trailing '#L42:10' suffixes (symmetric
        // with the colon form). Without the column capture, `:10` would be
        // left attached to the filename.
        FileRefMatching.extractFileName("src/Foo.kt#L42:10") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName strips leading-zero line suffix`() {
        // `\d+` matches leading zeros, so `:007` is treated as a line suffix.
        FileRefMatching.extractFileName("Foo.kt:007") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName handles whitespace before line suffix`() {
        // `:42` at the end matches LINE_SUFFIX_REGEX and is stripped, leaving
        // `src/Foo.kt ` → substringAfterLast('/') = `Foo.kt ` → trim() = `Foo.kt`.
        FileRefMatching.extractFileName("src/Foo.kt :42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractFileName returns null for non-digit line suffix`() {
        // A colon in the filename after line-suffix stripping indicates the input was
        // not a clean file reference (e.g. "Foo.kt:abc" where :abc is not numeric).
        // Return null so the caller doesn't pass garbage to FilenameIndex.
        FileRefMatching.extractFileName("Foo.kt:abc").shouldBeNull()
    }

    // ── extractRelativePath ──────────────────────────────────────────────

    @Test
    fun `extractRelativePath strips line suffix`() {
        FileRefMatching.extractRelativePath("src/Foo.kt:42") shouldBe "src/Foo.kt"
    }

    @Test
    fun `extractRelativePath returns bare filename for bare filename`() {
        FileRefMatching.extractRelativePath("Foo.kt:42") shouldBe "Foo.kt"
    }

    @Test
    fun `extractRelativePath normalizes backslashes`() {
        FileRefMatching.extractRelativePath("C:\\src\\Foo.kt") shouldBe "C:/src/Foo.kt"
    }

    @Test
    fun `extractRelativePath returns null for empty string`() {
        FileRefMatching.extractRelativePath("").shouldBeNull()
    }

    @Test
    fun `extractRelativePath trims trailing slash`() {
        FileRefMatching.extractRelativePath("src/") shouldBe "src"
    }

    @Test
    fun `extractRelativePath returns null for colon-only`() {
        FileRefMatching.extractRelativePath(":42").shouldBeNull()
    }

    // ── selectBestMatch: suffix match ─────────────────────────────────────

    @Test
    fun `selectBestMatch prefers suffix match over shortest path`() {
        val candidates = listOf("/proj/src/Foo.kt", "/proj/test/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch returns first suffix match when multiple candidates suffix-match`() {
        // Both candidates end with "/Foo.kt" — the FIRST one in the list wins.
        val candidates = listOf("/proj/src/Foo.kt", "/proj/test/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch returns the only suffix-matching candidate`() {
        val candidates = listOf("/proj/a/Baz.kt", "/proj/src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch suffix match is case-insensitive on candidate path`() {
        val candidates = listOf("/proj/Src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "/proj/Src/Foo.kt"
    }

    @Test
    fun `selectBestMatch suffix match is case-insensitive on relPath`() {
        val candidates = listOf("/proj/src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "SRC/FOO.KT") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch suffix match treats relPath equal to full path`() {
        // pathLower == relLower branch: candidate path equals relPath (no slash).
        val candidates = listOf("Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Foo.kt") shouldBe "Foo.kt"
    }

    // ── selectBestMatch: shortest-path fallback ───────────────────────────

    @Test
    fun `selectBestMatch falls back to shortest path when no suffix match`() {
        val candidates = listOf("/proj/a/Foo.kt", "/proj/b/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Bar.kt") shouldBe "/proj/a/Foo.kt"
    }

    @Test
    fun `selectBestMatch shortest-path fallback picks the shorter of two`() {
        val candidates = listOf("/proj/deeper/b/Foo.kt", "/proj/a/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Bar.kt") shouldBe "/proj/a/Foo.kt"
    }

    @Test
    fun `selectBestMatch returns null for empty candidate list`() {
        FileRefMatching.selectBestMatch(emptyList(), "Foo.kt").shouldBeNull()
    }

    // ── selectBestMatch: backslash handling ───────────────────────────────

    @Test
    fun `selectBestMatch normalizes backslashes in candidate paths for comparison only`() {
        // The candidate has backslashes; the comparison normalizes them to
        // forward slashes. The ORIGINAL candidate string is returned unchanged.
        val candidates = listOf("C:\\proj\\src\\Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "C:\\proj\\src\\Foo.kt"
    }

    @Test
    fun `selectBestMatch backslash candidate suffix-matches forward-slash relPath`() {
        val candidates = listOf("C:\\proj\\src\\Foo.kt", "C:\\proj\\test\\Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "C:\\proj\\src\\Foo.kt"
    }

    @Test
    fun `selectBestMatch backslash-only candidate shortest-path fallback`() {
        // No suffix match — shortest path wins. Length is measured on the raw
        // string (with backslashes), so the shorter raw string wins.
        val candidates = listOf("C:\\proj\\a\\Foo.kt", "C:\\proj\\deeper\\b\\Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Bar.kt") shouldBe "C:\\proj\\a\\Foo.kt"
    }

    // ── selectBestMatch: single candidate ─────────────────────────────────

    @Test
    fun `selectBestMatch returns the single candidate when it suffix-matches`() {
        val candidates = listOf("/proj/src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src/Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch returns the single candidate even without suffix match`() {
        // No suffix match, but only one candidate — shortest-path fallback returns it.
        val candidates = listOf("/proj/src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Bar.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch returns first candidate when duplicates exist`() {
        // firstOrNull returns the first of the duplicate suffix matches.
        val candidates = listOf("/a/Foo.kt", "/a/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "Foo.kt") shouldBe "/a/Foo.kt"
    }

    @Test
    fun `selectBestMatch normalizes backslashes in relPath`() {
        // Defensive normalization: a relPath with backslashes is normalized to
        // forward slashes before the suffix comparison.
        val candidates = listOf("/proj/src/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src\\Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch forward-slash candidates with backslash relPath resolves correctly`() {
        // VirtualFile.path always uses forward slashes. Verify that a backslash relPath
        // normalizes correctly and the forward-slash candidate is returned unchanged.
        val candidates = listOf("/proj/src/Foo.kt", "/proj/test/Foo.kt")
        FileRefMatching.selectBestMatch(candidates, "src\\Foo.kt") shouldBe "/proj/src/Foo.kt"
    }

    @Test
    fun `selectBestMatch candidate path returned unchanged for forward-slash input`() {
        // Confirms the caller's `matches.firstOrNull { it.path == selectedPath }` lookup
        // pattern works: selectedPath is a raw candidate string (forward slashes), and
        // VirtualFile.path also uses forward slashes, so == succeeds.
        val candidates = listOf("/proj/src/Foo.kt")
        val selected = FileRefMatching.selectBestMatch(candidates, "src/Foo.kt")
        selected shouldBe "/proj/src/Foo.kt"
        // The caller does: matches.firstOrNull { it.path == selected }
        // Since candidates come from matches.map { it.path }, selected IS one of those
        // paths, so the lookup succeeds.
    }
}