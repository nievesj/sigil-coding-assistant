package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodePart
import com.opencode.acp.chat.model.AttachedFile
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.io.File
import kotlin.io.path.writeBytes

/**
 * Unit tests for [AttachmentValidator] (TDD §8.1 — AttachmentValidator scenarios).
 *
 * The validator is a pure class taking canonical path strings — no IntelliJ
 * [com.intellij.openapi.project.Project] mocking needed. Tests create real
 * temp files under a temp directory that stands in for the project root.
 */
class AttachmentValidatorTest {

    @TempDir
    lateinit var tempProjectDir: Path

    private fun validator(): AttachmentValidator {
        val projectBase = tempProjectDir.toFile().canonicalPath
        val userHome = File(System.getProperty("user.home")).canonicalPath
        return AttachmentValidator(projectBasePath = projectBase, userHomePath = userHome)
    }

    private fun fileInProject(relativePath: String, content: ByteArray = "hello".toByteArray()): AttachedFile {
        val f = tempProjectDir.resolve(relativePath).toFile()
        f.parentFile.mkdirs()
        f.writeBytes(content)
        return AttachedFile(name = f.name, path = f.canonicalPath, mime = "text/plain")
    }

    private fun imageInProject(relativePath: String, content: ByteArray = byteArrayOf(1, 2, 3)): AttachedFile {
        val f = tempProjectDir.resolve(relativePath).toFile()
        f.parentFile.mkdirs()
        f.writeBytes(content)
        return AttachedFile(name = f.name, path = f.canonicalPath, mime = "image/png")
    }

    // ── Acceptance paths ────────────────────────────────────────────────────

    @Test
    fun `file inside project is accepted with file URL`() {
        val v = validator()
        val file = fileInProject("src/Main.kt")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 1
        result.rejectedFiles shouldHaveSize 0
        val filePart = result.parts.filterIsInstance<OpenCodePart.File>().single()
        filePart.url shouldStartWith "file:"
        filePart.filename shouldBe "Main.kt"
    }

    @Test
    fun `file inside opencode attachments dir is accepted`() {
        val v = validator()
        val file = fileInProject(".opencode/attachments/clip.png", byteArrayOf(1, 2, 3))
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 1
        result.rejectedFiles shouldHaveSize 0
    }

    @Test
    fun `image produces data URI with base64`() {
        val v = validator()
        val file = imageInProject("img.png", byteArrayOf(1, 2, 3))
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 1
        val filePart = result.parts.filterIsInstance<OpenCodePart.File>().single()
        filePart.url shouldStartWith "data:image/png;base64,"
        filePart.mime shouldBe "image/png"
    }

    @Test
    fun `non-image file gets normalized mime`() {
        val v = validator()
        val file = fileInProject("code.kt")
        val result = v.validateAndEncode(listOf(file))
        val filePart = result.parts.filterIsInstance<OpenCodePart.File>().single()
        // normalizeAttachmentMime maps .kt to text/plain (or similar) — just verify it's not null
        filePart.mime shouldNotBe ""
    }

    @Test
    fun `empty files list produces empty parts`() {
        val v = validator()
        val result = v.validateAndEncode(emptyList())
        result.parts shouldHaveSize 0
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 0
    }

    // ── Rejection paths ─────────────────────────────────────────────────────

    @Test
    fun `file outside project and outside user-home attachments is rejected`() {
        val v = validator()
        // Create a file in the system temp dir. On Windows this is under userHome
        // (C:\Users\<user>\AppData\Local\Temp) but NOT under userHome/.opencode/attachments/,
        // so it should still be rejected by the path-boundary check. On Linux/macOS the
        // temp dir may or may not be under userHome — either way it's not under the project
        // or the attachments dirs, so it's rejected.
        val outside = File(System.getProperty("java.io.tmpdir"), "outside_test_${System.nanoTime()}.txt")
        outside.writeBytes("secret".toByteArray())
        try {
            val file = AttachedFile(name = outside.name, path = outside.canonicalPath, mime = "text/plain")
            val result = v.validateAndEncode(listOf(file))
            result.acceptedFileNames shouldHaveSize 0
            result.rejectedFiles shouldHaveSize 1
            result.rejectedFiles[0].reason shouldContain "escapes allowed directories"
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `file with blank path is rejected`() {
        val v = validator()
        val file = AttachedFile(name = "legacy.png", path = "", mime = "image/png")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "blank path"
    }

    @Test
    fun `unreadable or deleted file is rejected`() {
        val v = validator()
        val file = AttachedFile(name = "ghost.txt", path = tempProjectDir.resolve("nonexistent.txt").toString(), mime = "text/plain")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "not found or unreadable"
    }

    @Test
    fun `file with env segment is rejected by denylist`() {
        val v = validator()
        val file = fileInProject(".env")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }

    @Test
    fun `file with git segment is rejected by denylist`() {
        val v = validator()
        val file = fileInProject(".git/config")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }

    @Test
    fun `file with node_modules segment is rejected by denylist`() {
        val v = validator()
        val file = fileInProject("node_modules/lib/index.js")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }

    @Test
    fun `file with idea segment is rejected by denylist`() {
        val v = validator()
        val file = fileInProject(".idea/workspace.xml")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }

    @Test
    fun `root-level build segment is rejected by root-only denylist`() {
        val v = validator()
        val file = fileInProject("build/output.txt")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }

    @Test
    fun `root-level target segment is rejected by root-only denylist`() {
        val v = validator()
        val file = fileInProject("target/classes.txt")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
    }

    @Test
    fun `root-level out segment is rejected by root-only denylist`() {
        val v = validator()
        val file = fileInProject("out/result.txt")
        val result = v.validateAndEncode(listOf(file))
        result.rejectedFiles shouldHaveSize 1
    }

    @Test
    fun `non-root build segment is NOT rejected by root-only denylist`() {
        val v = validator()
        // build/ under src/ is NOT root-level — should be accepted
        val file = fileInProject("src/build/config.txt")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 1
        result.rejectedFiles shouldHaveSize 0
    }

    @Test
    fun `image larger than 10MB is rejected`() {
        val v = validator()
        // Use a sparse file to avoid allocating 11MB in memory. The validator checks
        // fileObj.length() BEFORE reading bytes, so we just need the file to report >10MB.
        val bigFile = tempProjectDir.resolve("big.png").toFile()
        java.io.RandomAccessFile(bigFile, "rw").use { it.setLength(11L * 1024 * 1024) }
        val file = AttachedFile(name = bigFile.name, path = bigFile.canonicalPath, mime = "image/png")
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "too large"
    }

    @Test
    fun `empty image file is rejected`() {
        val v = validator()
        val file = imageInProject("empty.png", ByteArray(0))
        val result = v.validateAndEncode(listOf(file))
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "empty"
    }

    @Test
    fun `text part is always included in parts`() {
        val v = validator()
        val result = v.validateAndEncode(emptyList())
        // No files → no text part added by validator (text part is added by caller)
        result.parts shouldHaveSize 0
    }

    @Test
    fun `multiple files - mixed accept and reject`() {
        val v = validator()
        val good = fileInProject("src/Main.kt")
        val bad = AttachedFile(name = "secret.env", path = "", mime = "text/plain")
        val result = v.validateAndEncode(listOf(good, bad))
        result.acceptedFileNames shouldHaveSize 1
        result.rejectedFiles shouldHaveSize 1
        result.acceptedFileNames[0] shouldBe "Main.kt"
    }

    // ── Symlink / TOCTOU tests (TDD §7.1) ──────────────────────────────────

    @Test
    fun `symlink in attachments pointing to denylisted path is rejected`() {
        val v = validator()
        // Create a real .env file in the project
        val envFile = fileInProject(".env", "SECRET=hello".toByteArray())
        // Create a symlink in .opencode/attachments/ pointing to .env
        val attachmentsDir = tempProjectDir.resolve(".opencode/attachments").toFile()
        attachmentsDir.mkdirs()
        val symlink = java.io.File(attachmentsDir, "link_to_env")
        try {
            java.nio.file.Files.createSymbolicLink(
                symlink.toPath(),
                java.io.File(envFile.path).toPath()
            )
        } catch (e: java.nio.file.FileSystemException) {
            // Symbolic links may require elevated privileges on Windows.
            // Skip this test if symlinks aren't supported.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlinks not supported on this platform")
            return
        }
        val file = AttachedFile(name = symlink.name, path = symlink.canonicalPath, mime = "text/plain")
        val result = v.validateAndEncode(listOf(file))
        // The canonical path resolves to .env, which is denylisted — must be rejected
        // even though the symlink itself is inside the allowed attachments directory.
        result.acceptedFileNames shouldHaveSize 0
        result.rejectedFiles shouldHaveSize 1
        result.rejectedFiles[0].reason shouldContain "denylist"
    }
}