package com.opencode.acp.chat.util

import com.opencode.acp.chat.model.FileChangeStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UnifiedDiffParser] — a pure-logic parser for
 * `git diff --cached --no-renames` unified diff output.
 *
 * No mocking needed — the parser is pure string-in, list-out.
 */
class UnifiedDiffParserTest {

    // ── Empty / blank input ───────────────────────────────────────────────

    @Test
    fun `empty input returns empty list`() {
        UnifiedDiffParser.parse("") shouldBe emptyList()
    }

    @Test
    fun `blank input returns empty list`() {
        UnifiedDiffParser.parse("   \n  \n") shouldBe emptyList()
    }

    @Test
    fun `input with only whitespace and newlines returns empty list`() {
        UnifiedDiffParser.parse("\n\n\n") shouldBe emptyList()
    }

    // ── Single modified file ──────────────────────────────────────────────

    @Test
    fun `single modified file with one hunk has correct path, status, and counts`() {
        val diff = """
            diff --git a/src/main.kt b/src/main.kt
            index abc1234..def5678 100644
            --- a/src/main.kt
            +++ b/src/main.kt
            @@ -1,3 +1,4 @@
             context line
            -removed line
            +added line
            +another added line
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/main.kt"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 2
        entries[0].deletions shouldBe 1
    }

    // ── New file (ADDED) ───────────────────────────────────────────────────

    @Test
    fun `new file mode produces ADDED status with correct additions`() {
        val diff = """
            diff --git a/src/new.kt b/src/new.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/src/new.kt
            @@ -0,0 +1,5 @@
            +line1
            +line2
            +line3
            +line4
            +line5
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/new.kt"
        entries[0].status shouldBe FileChangeStatus.ADDED
        entries[0].additions shouldBe 5
        entries[0].deletions shouldBe 0
    }

    // ── Deleted file (DELETED) ─────────────────────────────────────────────

    @Test
    fun `deleted file mode produces DELETED status with correct deletions`() {
        val diff = """
            diff --git a/src/old.kt b/src/old.kt
            deleted file mode 100644
            index abc1234..0000000
            --- a/src/old.kt
            +++ /dev/null
            @@ -1,3 +0,0 @@
            -line1
            -line2
            -line3
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/old.kt"
        entries[0].status shouldBe FileChangeStatus.DELETED
        entries[0].additions shouldBe 0
        entries[0].deletions shouldBe 3
    }

    // ── Multiple files ─────────────────────────────────────────────────────

    @Test
    fun `multiple files in one diff output produces correct per-file entries`() {
        val diff = """
            diff --git a/src/file1.kt b/src/file1.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/src/file1.kt
            @@ -0,0 +1,2 @@
            +line1
            +line2

            diff --git a/src/file2.kt b/src/file2.kt
            index abc1234..def5678 100644
            --- a/src/file2.kt
            +++ b/src/file2.kt
            @@ -1,2 +1,3 @@
             context
            -old line
            +new line
            +extra line

            diff --git a/src/file3.kt b/src/file3.kt
            deleted file mode 100644
            index abc1234..0000000
            --- a/src/file3.kt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -deleted line
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 3

        entries[0].filePath shouldBe "src/file1.kt"
        entries[0].status shouldBe FileChangeStatus.ADDED
        entries[0].additions shouldBe 2
        entries[0].deletions shouldBe 0

        entries[1].filePath shouldBe "src/file2.kt"
        entries[1].status shouldBe FileChangeStatus.MODIFIED
        entries[1].additions shouldBe 2
        entries[1].deletions shouldBe 1

        entries[2].filePath shouldBe "src/file3.kt"
        entries[2].status shouldBe FileChangeStatus.DELETED
        entries[2].additions shouldBe 0
        entries[2].deletions shouldBe 1
    }

    // ── Binary file diff ───────────────────────────────────────────────────

    @Test
    fun `binary file diff produces MODIFIED with zero additions and deletions`() {
        val diff = """
            diff --git a/image.png b/image.png
            index abc1234..def5678 100644
            Binary files a/image.png and b/image.png differ
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "image.png"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 0
        entries[0].deletions shouldBe 0
    }

    // ── Multiple hunks in one file ─────────────────────────────────────────

    @Test
    fun `file with multiple hunks sums additions and deletions across hunks`() {
        val diff = """
            diff --git a/src/multi.kt b/src/multi.kt
            index abc1234..def5678 100644
            --- a/src/multi.kt
            +++ b/src/multi.kt
            @@ -1,3 +1,4 @@
             ctx1
            -old1
            +new1
            +extra1
            @@ -10,2 +11,3 @@
             ctx2
            -old2
            +new2
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/multi.kt"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 3 // new1 + extra1 + new2
        entries[0].deletions shouldBe 2 // old1 + old2
    }

    // ── Paths with spaces ──────────────────────────────────────────────────

    @Test
    fun `file path containing spaces is parsed correctly`() {
        val diff = """
            diff --git a/src/my file.kt b/src/my file.kt
            index abc1234..def5678 100644
            --- a/src/my file.kt
            +++ b/src/my file.kt
            @@ -1,2 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/my file.kt"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── No hunks (edge case) ───────────────────────────────────────────────

    @Test
    fun `diff header with no hunk header produces entry with zero counts`() {
        val diff = """
            diff --git a/src/noop.kt b/src/noop.kt
            index abc1234..abc1234 100644
            --- a/src/noop.kt
            +++ b/src/noop.kt
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/noop.kt"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 0
        entries[0].deletions shouldBe 0
    }

    // ── Context lines not counted ──────────────────────────────────────────

    @Test
    fun `context lines starting with space are not counted as additions or deletions`() {
        val diff = """
            diff --git a/src/ctx.kt b/src/ctx.kt
            index abc1234..def5678 100644
            --- a/src/ctx.kt
            +++ b/src/ctx.kt
            @@ -1,5 +1,5 @@
             keep this
             keep that
            -remove
            +add
             keep more
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── No newline at end of file marker ───────────────────────────────────

    @Test
    fun `no newline at end of file marker is not counted`() {
        val diff = """
            diff --git a/src/eof.kt b/src/eof.kt
            index abc1234..def5678 100644
            --- a/src/eof.kt
            +++ b/src/eof.kt
            @@ -1,2 +1,2 @@
             line1
            -line2
            +line2
            \ No newline at end of file
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── Rename entry with content changes is kept ────────────────────────
    // A rename that also includes content changes should NOT be silently
    // dropped — the file's changes need to be reviewed. The entry is kept
    // under the destination (b/) path.

    @Test
    fun `rename entry with content changes is kept under destination path`() {
        val diff = """
            diff --git a/src/old_name.kt b/src/new_name.kt
            similarity index 90%
            rename from src/old_name.kt
            rename to src/new_name.kt
            index abc1234..def5678 100644
            --- a/src/old_name.kt
            +++ b/src/new_name.kt
            @@ -1,3 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        // Rename WITH content changes → entry is kept under destination path
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/new_name.kt"
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    @Test
    fun `pure rename with no content changes is skipped`() {
        val diff = """
            diff --git a/src/old_name.kt b/src/new_name.kt
            similarity index 100%
            rename from src/old_name.kt
            rename to src/new_name.kt
            index abc1234..abc1234 100644
            --- a/src/old_name.kt
            +++ b/src/new_name.kt
            @@ -1,1 +1,1 @@
             ctx
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        // Pure rename with no +/- lines → nothing to review → skipped
        entries shouldHaveSize 0
    }

    // ── Mixed: binary + text files ─────────────────────────────────────────

    @Test
    fun `mixed binary and text files in same diff output`() {
        val diff = """
            diff --git a/src/code.kt b/src/code.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/src/code.kt
            @@ -0,0 +1,3 @@
            +fun main() {
            +    println("hi")
            +}

            diff --git a/assets/logo.png b/assets/logo.png
            index abc1234..def5678 100644
            Binary files a/assets/logo.png and b/assets/logo.png differ
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 2

        entries[0].filePath shouldBe "src/code.kt"
        entries[0].status shouldBe FileChangeStatus.ADDED
        entries[0].additions shouldBe 3
        entries[0].deletions shouldBe 0

        entries[1].filePath shouldBe "assets/logo.png"
        entries[1].status shouldBe FileChangeStatus.MODIFIED
        entries[1].additions shouldBe 0
        entries[1].deletions shouldBe 0
    }

    // ── Deleted file path from --- a/ line ─────────────────────────────────

    @Test
    fun `deleted file path is extracted from dash line when plus plus is dev null`() {
        val diff = """
            diff --git a/src/deleted.kt b/src/deleted.kt
            deleted file mode 100644
            index abc1234..0000000
            --- a/src/deleted.kt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -line1
            -line2
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/deleted.kt"
        entries[0].status shouldBe FileChangeStatus.DELETED
        entries[0].deletions shouldBe 2
    }

    // ── New file path from +++ b/ line ─────────────────────────────────────

    @Test
    fun `new file path is extracted from plus plus line when dash dash is dev null`() {
        val diff = """
            diff --git a/src/created.kt b/src/created.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/src/created.kt
            @@ -0,0 +1,2 @@
            +line1
            +line2
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/created.kt"
        entries[0].status shouldBe FileChangeStatus.ADDED
        entries[0].additions shouldBe 2
    }

    // ── Hunk with only context lines ──────────────────────────────────────

    @Test
    fun `hunk with only context lines has zero additions and deletions`() {
        val diff = """
            diff --git a/src/unchanged.kt b/src/unchanged.kt
            index abc1234..def5678 100644
            --- a/src/unchanged.kt
            +++ b/src/unchanged.kt
            @@ -1,3 +1,3 @@
             line1
             line2
             line3
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].additions shouldBe 0
        entries[0].deletions shouldBe 0
    }

    // ── Nested directory paths ───────────────────────────────────────────

    @Test
    fun `deeply nested directory paths are preserved`() {
        val diff = """
            diff --git a/src/main/kotlin/com/example/deep/nested/File.kt b/src/main/kotlin/com/example/deep/nested/File.kt
            index abc1234..def5678 100644
            --- a/src/main/kotlin/com/example/deep/nested/File.kt
            +++ b/src/main/kotlin/com/example/deep/nested/File.kt
            @@ -1,1 +1,2 @@
            -old
            +new1
            +new2
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/main/kotlin/com/example/deep/nested/File.kt"
        entries[0].additions shouldBe 2
        entries[0].deletions shouldBe 1
    }

    // ── Hunk body lines starting with ++ or -- (no trailing space) ────────
    // Regression: lines whose content begins with "++" or "--" (e.g. C++ ++i,
    // --iter, Lua --comment) must be counted, not skipped as header lines.

    @Test
    fun `hunk body line whose content starts with double-plus is counted as addition`() {
        val diff = """
            diff --git a/src/code.cpp b/src/code.cpp
            index abc1234..def5678 100644
            --- a/src/code.cpp
            +++ b/src/code.cpp
            @@ -1,2 +1,3 @@
             int i = 0;
            -i = i + 1;
            +++i;
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/code.cpp"
        // "+++i;" is an added line (content "++i;") — must be counted, not
        // skipped as a "+++" header (it has no trailing space after "+++").
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    @Test
    fun `hunk body line whose content starts with double-dash is counted as deletion`() {
        val diff = """
            diff --git a/src/code.cpp b/src/code.cpp
            index abc1234..def5678 100644
            --- a/src/code.cpp
            +++ b/src/code.cpp
            @@ -1,2 +1,1 @@
            ---iter;
            +iter;
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/code.cpp"
        // "---iter;" is a deleted line (content "--iter;") — must be counted.
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── Hunk body lines starting with "--- " or "+++ " (with trailing space) ──
    // Regression: a context/added/deleted line whose content begins with "--- "
    // or "+++ " must NOT be mis-parsed as a file header. Header matching is now
    // gated on !inHunk so these are treated as hunk body lines.

    @Test
    fun `hunk body line starting with dash dash dash space is counted as deletion not header`() {
        // A deleted line whose content is "-- some lua comment"
        val diff = """
            diff --git a/src.lua b/src.lua
            index abc1234..def5678 100644
            --- a/src.lua
            +++ b/src.lua
            @@ -1,2 +1,1 @@
            --- old lua comment
            +print("hi")
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src.lua"
        // "--- old lua comment" is a deleted line (content "-- old lua comment"),
        // NOT a --- a/path header. It must be counted as a deletion.
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── Quoted paths (git quotes paths with special characters) ──────────

    @Test
    fun `quoted path with spaces is parsed correctly via plus plus and dash dash lines`() {
        // When git quotes paths, the +++ and --- lines carry the actual path.
        // The diff --git header is quoted, but we primarily use +++ b/ for the path.
        val diff = """
            diff --git "a/src/my file.kt" "b/src/my file.kt"
            index abc1234..def5678 100644
            --- "a/src/my file.kt"
            +++ "b/src/my file.kt"
            @@ -1,2 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        // The filePath must NOT contain surrounding quotes — stripPathPrefix
        // strips them so the path matches downstream (LocalFileSystem lookup,
        // openCommentsByFile map, openDiffForPath comparison).
        entries[0].filePath shouldBe "src/my file.kt"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    @Test
    fun `quoted path for deleted file extracts path from dash dash line without quotes`() {
        val diff = """
            diff --git "a/src/old file.kt" "b/src/old file.kt"
            deleted file mode 100644
            index abc1234..0000000
            --- "a/src/old file.kt"
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -line1
            -line2
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/old file.kt"
        entries[0].status shouldBe FileChangeStatus.DELETED
        entries[0].deletions shouldBe 2
    }

    @Test
    fun `quoted path for binary file extracts path from diff header without quotes`() {
        val diff = """
            diff --git "a/src/my image.png" "b/src/my image.png"
            index abc1234..def5678 100644
            Binary files "a/src/my image.png" and "b/src/my image.png" differ
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        entries[0].filePath shouldBe "src/my image.png"
        entries[0].status shouldBe FileChangeStatus.MODIFIED
        entries[0].additions shouldBe 0
        entries[0].deletions shouldBe 0
    }

    // ── Octal-escaped paths (git core.quotePath=true for non-ASCII bytes) ──

    @Test
    fun `octal-escaped non-ASCII path is unescaped to the real file path`() {
        // git escapes bytes >0x7F as \NNN octal sequences. "ü" is U+00FC,
        // which in UTF-8 is 0xC3 0xBC → git renders as \303\274.
        val diff = """
            diff --git "a/src/\303\274.txt" "b/src/\303\274.txt"
            index abc1234..def5678 100644
            --- "a/src/\303\274.txt"
            +++ "b/src/\303\274.txt"
            @@ -1,2 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        // The octal escapes \303\274 are unescaped to the UTF-8 bytes for ü.
        entries[0].filePath shouldBe "src/ü.txt"
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }

    // ── Path with control-char escapes (git escapes \t, \n, \r) ────────────

    @Test
    fun `path with tab escape is unescaped to a literal tab`() {
        // git escapes a literal TAB in a filename as \t (lowercase).
        // We build the diff with a raw string so the Kotlin compiler doesn't
        // interpret the backslash escapes — they must reach the parser literally.
        val diff = buildString {
            appendLine("diff --git \"a/src/foo\\tbar.kt\" \"b/src/foo\\tbar.kt\"")
            appendLine("index abc1234..def5678 100644")
            appendLine("--- \"a/src/foo\\tbar.kt\"")
            appendLine("+++ \"b/src/foo\\tbar.kt\"")
            appendLine("@@ -1,1 +1,2 @@")
            appendLine("-old")
            appendLine("+new")
            appendLine("+new2")
        }

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        // \t is unescaped to a literal TAB character.
        entries[0].filePath shouldBe "src/foo\tbar.kt"
        entries[0].additions shouldBe 2
    }

    // ── Path with ' b/' in the file name (edge case for header parsing) ──
    // The extractPathFromDiffHeader uses lastIndexOf(" b/") which can misparse
    // paths containing " b/" in the file name. However, the +++ b/ line is the
    // primary path source for text files, so this edge case only affects
    // binary files (which have no +++ line).

    @Test
    fun `text file with b-slash in directory name uses plus plus line for path`() {
        // A file in a directory named "b" — the diff header has " b/src/b/file.kt"
        // which confuses lastIndexOf, but the +++ line gives the correct path.
        val diff = """
            diff --git a/src/b/file.kt b/src/b/file.kt
            index abc1234..def5678 100644
            --- a/src/b/file.kt
            +++ b/src/b/file.kt
            @@ -1,2 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()

        val entries = UnifiedDiffParser.parse(diff)
        entries shouldHaveSize 1
        // The +++ b/ line correctly provides the path (stripPathPrefix strips "b/")
        entries[0].filePath shouldBe "src/b/file.kt"
        entries[0].additions shouldBe 1
        entries[0].deletions shouldBe 1
    }
}