package com.opencode.acp.chat.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for [AtomicFileWriter] (TDD §4.2.6).
 *
 * Uses JUnit5 @TempDir for real filesystem operations — no mocking.
 * Verifies atomic write (temp + rename), parent directory creation,
 * overwrite behavior, and content integrity.
 */
class AtomicFileWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writeAtomically creates file with correct content`() {
        val target = tempDir.resolve("test.txt")
        val result = AtomicFileWriter.writeAtomically(target, "Hello world")
        result shouldBe true
        Files.exists(target) shouldBe true
        Files.readString(target) shouldBe "Hello world"
    }

    @Test
    fun `writeAtomically overwrites existing file`() {
        val target = tempDir.resolve("existing.txt")
        Files.writeString(target, "old content")
        val result = AtomicFileWriter.writeAtomically(target, "new content")
        result shouldBe true
        Files.readString(target) shouldBe "new content"
    }

    @Test
    fun `writeAtomically creates parent directories`() {
        val target = tempDir.resolve("a/b/c/test.txt")
        val result = AtomicFileWriter.writeAtomically(target, "nested")
        result shouldBe true
        Files.exists(target) shouldBe true
        Files.readString(target) shouldBe "nested"
    }

    @Test
    fun `writeAtomically handles empty content`() {
        val target = tempDir.resolve("empty.txt")
        val result = AtomicFileWriter.writeAtomically(target, "")
        result shouldBe true
        Files.exists(target) shouldBe true
        Files.readString(target) shouldBe ""
    }

    @Test
    fun `writeAtomically handles unicode content`() {
        val target = tempDir.resolve("unicode.txt")
        val content = "Hello 世界 🌍 café"
        val result = AtomicFileWriter.writeAtomically(target, content)
        result shouldBe true
        Files.readString(target) shouldBe content
    }

    @Test
    fun `writeAtomically handles large content`() {
        val target = tempDir.resolve("large.txt")
        val content = "x".repeat(100_000)
        val result = AtomicFileWriter.writeAtomically(target, content)
        result shouldBe true
        Files.readString(target) shouldBe content
    }

    @Test
    fun `writeAtomically does not leave temp file on success`() {
        val target = tempDir.resolve("clean.txt")
        AtomicFileWriter.writeAtomically(target, "data")
        // Only the target file should exist in tempDir (no .atomic-*.tmp files)
        val files = Files.list(tempDir).use { it.toList() }
        files.size shouldBe 1
        files[0].fileName.toString() shouldBe "clean.txt"
    }

    @Test
    fun `writeAtomically returns true for multiple sequential writes`() {
        val target = tempDir.resolve("multi.txt")
        AtomicFileWriter.writeAtomically(target, "first") shouldBe true
        AtomicFileWriter.writeAtomically(target, "second") shouldBe true
        AtomicFileWriter.writeAtomically(target, "third") shouldBe true
        Files.readString(target) shouldBe "third"
    }

    @Test
    fun `writeAtomically to deeply nested path creates all parents`() {
        val target = tempDir.resolve("x/y/z/w/deep.txt")
        val result = AtomicFileWriter.writeAtomically(target, "deep")
        result shouldBe true
        Files.exists(target) shouldBe true
    }

    @Test
    fun `writeAtomically preserves content with newlines`() {
        val target = tempDir.resolve("newlines.txt")
        val content = "line1\nline2\nline3\n"
        val result = AtomicFileWriter.writeAtomically(target, content)
        result shouldBe true
        Files.readString(target) shouldBe content
    }
}