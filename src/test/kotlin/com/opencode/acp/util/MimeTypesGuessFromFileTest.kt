package com.opencode.acp.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [MimeTypes.guessFromFile] — the content-based detection path.
 *
 * Unlike [MimeTypesTest] (which tests [MimeTypes.guessFromFileName] only), this
 * class exercises the full [MimeTypes.guessFromFile] lookup chain:
 * 1. [MimeTypes.fullNameMap] — keyed by the full lowercased file name
 * 2. [MimeTypes.extensionMap] — keyed by the substring after the last dot
 * 3. [URLConnection.guessContentTypeFromName] — JDK built-in map
 * 4. [java.nio.file.Files.probeContentType] — content-based detection (reads
 *    file headers/magic bytes)
 * 5. `application/octet-stream` — final fallback
 *
 * Uses `@TempDir` to create real files on disk so that `Files.probeContentType`
 * has actual content to inspect.
 *
 * NOTE: `Files.probeContentType` behavior is platform-dependent (uses the OS's
 * file-type registry on Windows, `mime.types` files on Linux, LaunchServices on
 * macOS). Tests that assert specific `probeContentType` results are written to
 * be resilient: they only assert that the result is NON-NULL when the file
 * exists and is readable, and that it falls back to `application/octet-stream`
 * when the file doesn't exist or isn't readable. They do NOT assert specific
 * MIME types from `probeContentType` because those vary across platforms.
 */
class MimeTypesGuessFromFileTest {

    @TempDir
    lateinit var tempDir: Path

    // ── Full-name lookup (extensionless / multi-dot dotfiles) ──────────────

    @Test
    fun `guessFromFile Makefile returns text-x-makefile via fullNameMap`() {
        val file = java.io.File(tempDir.toFile(), "Makefile").apply { writeText("all: build\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/x-makefile"
    }

    @Test
    fun `guessFromFile Dockerfile returns text-x-dockerfile via fullNameMap`() {
        val file = java.io.File(tempDir.toFile(), "Dockerfile").apply { writeText("FROM ubuntu\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/x-dockerfile"
    }

    @Test
    fun `guessFromFile env local returns text-plain via fullNameMap`() {
        val file = java.io.File(tempDir.toFile(), ".env.local").apply { writeText("DEBUG=true\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/plain"
    }

    @Test
    fun `guessFromFile full-name lookup is case-insensitive`() {
        val file = java.io.File(tempDir.toFile(), "MAKEFILE").apply { writeText("all: build\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/x-makefile"
    }

    // ── Extension-based lookup (fast path) ─────────────────────────────────

    @Test
    fun `guessFromFile with known extension returns mapped type`() {
        val file = java.io.File(tempDir.toFile(), "Main.kt").apply { writeText("fun main() {}\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/x-kotlin"
    }

    @Test
    fun `guessFromFile with known json extension returns application-json`() {
        val file = java.io.File(tempDir.toFile(), "config.json").apply { writeText("{}\n") }
        MimeTypes.guessFromFile(file) shouldBe "application/json"
    }

    @Test
    fun `guessFromFile with known image extension returns image type`() {
        val file = java.io.File(tempDir.toFile(), "logo.png").apply { writeText("not-a-real-png") }
        MimeTypes.guessFromFile(file) shouldBe "image/png"
    }

    @Test
    fun `guessFromFile extension lookup is case-insensitive`() {
        val file = java.io.File(tempDir.toFile(), "Main.KT").apply { writeText("fun main() {}\n") }
        MimeTypes.guessFromFile(file) shouldBe "text/x-kotlin"
    }

    // ── Non-existent / non-readable / non-file fallback ────────────────────

    @Test
    fun `guessFromFile non-existent file falls back to application-octet-stream`() {
        val file = java.io.File(tempDir.toFile(), "does-not-exist.kt")
        // File doesn't exist → probeContentType is skipped → octet-stream.
        // The extensionMap lookup still runs first, so a known extension on a
        // non-existent file returns the mapped type (extension-only path).
        // For an UNKNOWN extension on a non-existent file, the result is
        // application/octet-stream.
        MimeTypes.guessFromFile(file) shouldBe "text/x-kotlin"
    }

    @Test
    fun `guessFromFile non-existent file with unknown extension falls back to octet-stream`() {
        val file = java.io.File(tempDir.toFile(), "does-not-exist.xyzunknown")
        MimeTypes.guessFromFile(file) shouldBe "application/octet-stream"
    }

    @Test
    fun `guessFromFile directory falls back to application-octet-stream`() {
        // A directory is not a file (file.isFile == false), so probeContentType
        // is skipped. With an unknown "extension" (the dir name has no dot),
        // the result is application/octet-stream.
        val dir = java.io.File(tempDir.toFile(), "somedir").apply { mkdirs() }
        MimeTypes.guessFromFile(dir) shouldBe "application/octet-stream"
    }

    @Test
    fun `guessFromFile directory with known extension returns mapped type`() {
        // A directory named "config.json" — file.isFile is false, but the
        // extensionMap lookup runs before the content-based check, so the
        // mapped type is returned. This is the same behavior as guessFromFileName.
        val dir = java.io.File(tempDir.toFile(), "config.json").apply { mkdirs() }
        try {
            MimeTypes.guessFromFile(dir) shouldBe "application/json"
        } finally {
            dir.delete()
        }
    }

    // ── Content-based fallback (probeContentType) ──────────────────────────
    //
    // These tests verify that guessFromFile does NOT throw when probeContentType
    // is invoked, and that it returns a non-null result for a real file with an
    // unknown extension. The exact MIME type returned by probeContentType is
    // platform-dependent, so we only assert non-octet-stream OR octet-stream
    // (both are acceptable — the point is that the function doesn't throw and
    // returns a valid string).

    @Test
    fun `guessFromFile with unknown extension does not throw and returns a string`() {
        val file = java.io.File(tempDir.toFile(), "data.xyzunknown").apply { writeText("hello world\n") }
        // The result should be a valid MIME type string (either from
        // probeContentType or the octet-stream fallback). We don't assert the
        // exact value because probeContentType is platform-dependent.
        val result = MimeTypes.guessFromFile(file)
        assert(result.isNotEmpty()) { "guessFromFile should return a non-empty string" }
    }

    @Test
    fun `guessFromFile with text content and unknown extension returns a string`() {
        // A text file with an unknown extension — probeContentType may return
        // text/plain on some platforms, or null (→ octet-stream) on others.
        val file = java.io.File(tempDir.toFile(), "notes.unknownext").apply { writeText("This is plain text.\n") }
        val result = MimeTypes.guessFromFile(file)
        assert(result.isNotEmpty()) { "guessFromFile should return a non-empty string" }
    }
}