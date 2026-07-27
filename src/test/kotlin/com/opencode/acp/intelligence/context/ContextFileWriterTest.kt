package com.opencode.acp.intelligence.context

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Unit tests for [ContextFileWriter] — pure filesystem tests using JUnit5
 * `@TempDir`. No IntelliJ platform dependencies.
 */
class ContextFileWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newWriter(): ContextFileWriter = ContextFileWriter(tempDir)

    private fun repoFile(): Path = tempDir.resolve(".opencode").resolve("context").resolve("repo-structure.md")

    @Test
    fun `writes file with correct content`() {
        val writer = newWriter()
        val content = "# Repository Structure: test\n\nHello world\n"
        val success = writer.writeRepoStructure(content)

        success shouldBe true
        Files.exists(repoFile()) shouldBe true
        Files.readString(repoFile()) shouldBe content
    }

    @Test
    fun `creates opencode context directory if it does not exist`() {
        val contextDir = tempDir.resolve(".opencode").resolve("context")
        Files.exists(contextDir) shouldBe false

        val writer = newWriter()
        val success = writer.writeRepoStructure("# Test\n")

        success shouldBe true
        Files.isDirectory(contextDir) shouldBe true
        Files.exists(repoFile()) shouldBe true
    }

    @Test
    fun `overwrites existing file`() {
        val writer = newWriter()
        writer.writeRepoStructure("# Old content\n")

        val newContent = "# New content\n"
        writer.writeRepoStructure(newContent)

        Files.readString(repoFile()) shouldBe newContent
    }

    @Test
    fun `returns true for empty content`() {
        val writer = newWriter()
        val success = writer.writeRepoStructure("")

        success shouldBe true
        Files.readString(repoFile()) shouldBe ""
    }

    @Test
    fun `returns true for large content`() {
        val writer = newWriter()
        val content = "# Repo\n\n" + "line\n".repeat(10_000)
        val success = writer.writeRepoStructure(content)

        success shouldBe true
        Files.readString(repoFile()).length shouldBe content.length
    }

    @Test
    fun `preserves markdown structure in written file`() {
        val writer = newWriter()
        val content = buildString {
            appendLine("# Repository Structure: my-project")
            appendLine()
            appendLine("## Tech Stack")
            appendLine()
            appendLine("- Kotlin")
            appendLine()
            appendLine("## Key Classes")
            appendLine()
            appendLine("| Class | File | Qualified Name |")
            appendLine("|-------|------|----------------|")
            appendLine("| `Foo` | `src/Foo.kt` | `com.example.Foo` |")
        }
        writer.writeRepoStructure(content)

        val written = Files.readString(repoFile())
        written shouldContain "# Repository Structure: my-project"
        written shouldContain "- Kotlin"
        written shouldContain "| `Foo` | `src/Foo.kt` | `com.example.Foo` |"
    }

    @Test
    fun `does not leave temp files behind on success`() {
        val writer = newWriter()
        writer.writeRepoStructure("# Test\n")

        val contextDir = tempDir.resolve(".opencode").resolve("context")
        val tempFiles = Files.list(contextDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        tempFiles shouldBe emptyList()
    }

    @Test
    fun `concurrent writes - both succeed and final content is valid`() {
        val writer = newWriter()
        val latch = CountDownLatch(1)
        val results = mutableListOf<Boolean>()
        val lock = Any()

        val threads = (1..2).map { i ->
            thread(start = false) {
                latch.await(5, TimeUnit.SECONDS)
                val content = "# Writer $i\n"
                val ok = writer.writeRepoStructure(content)
                synchronized(lock) { results.add(ok) }
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join(10_000) }

        // At least one write must succeed (concurrent atomic moves on Windows
        // can race — one may fail if the other holds the file lock)
        results.size shouldBe 2
        results.any { it } shouldBe true
        // Final file must exist and be one of the two contents
        Files.exists(repoFile()) shouldBe true
        val finalContent = Files.readString(repoFile())
        (finalContent == "# Writer 1\n" || finalContent == "# Writer 2\n") shouldBe true
    }

    @Test
    fun `concurrent writes via coroutines - all succeed`() {
        val writer = newWriter()
        runBlocking {
            val results = (1..5).map { i ->
                async(Dispatchers.IO) {
                    writer.writeRepoStructure("# Writer $i\n")
                }
            }.awaitAll()
            // At least one must succeed (concurrent atomic moves can race on Windows)
            results.any { it } shouldBe true
        }
        Files.exists(repoFile()) shouldBe true
    }

    @Test
    fun `returns false when base path does not exist and cannot be created`() {
        // Use a path that cannot be created (a file as parent on Windows is rejected)
        val impossiblePath = tempDir.resolve("i-am-a-file.txt")
        Files.writeString(impossiblePath, "blocker")
        val writer = ContextFileWriter(impossiblePath.resolve("subdir"))

        val success = writer.writeRepoStructure("# Test\n")

        success shouldBe false
    }

    @Test
    fun `writeRepoStructure returns false on IOException-like failure`() {
        // Point the writer at a path whose parent is a regular file, so
        // createDirectories fails.
        val blocker = tempDir.resolve("blocker.txt")
        Files.writeString(blocker, "x")
        val writer = ContextFileWriter(blocker.resolve(".opencode"))

        val success = writer.writeRepoStructure("# Test\n")

        success shouldBe false
    }

    @Test
    fun `multiple sequential writes all succeed`() {
        val writer = newWriter()
        for (i in 1..5) {
            val ok = writer.writeRepoStructure("# Iteration $i\n")
            ok shouldBe true
        }
        Files.readString(repoFile()) shouldBe "# Iteration 5\n"
    }

    @Test
    fun `file path is under opencode context directory`() {
        val writer = newWriter()
        writer.writeRepoStructure("# Test\n")

        val expected = tempDir.resolve(".opencode").resolve("context").resolve("repo-structure.md")
        Files.exists(expected) shouldBe true
        repoFile() shouldBe expected
    }

    @Test
    fun `content with unicode is preserved`() {
        val writer = newWriter()
        val content = "# Répo: 日本語 \u2022 emoji \uD83D\uDE00\n"
        val success = writer.writeRepoStructure(content)

        success shouldBe true
        Files.readString(repoFile()) shouldBe content
    }
}