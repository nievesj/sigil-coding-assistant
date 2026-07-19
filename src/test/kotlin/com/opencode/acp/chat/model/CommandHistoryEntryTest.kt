package com.opencode.acp.chat.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the real [CommandHistoryEntry].
 *
 * Tests the parallel `ArrayList<String>` field structure (used for XStream
 * serialization compatibility) and the [CommandHistoryEntry.toAttachedFiles]
 * reconstruction method. No fakes — the real class is constructed directly.
 */
class CommandHistoryEntryTest {

    // 1. toAttachedFiles() with empty lists returns empty list
    @Test
    fun `toAttachedFiles with empty lists returns empty list`() {
        val entry = CommandHistoryEntry()
        entry.toAttachedFiles() shouldBe emptyList()
        entry.toAttachedFiles() shouldHaveSize 0
    }

    // 2. toAttachedFiles() with matching-size lists returns List<AttachedFile>
    @Test
    fun `toAttachedFiles with matching lists returns AttachedFile list`() {
        val entry = CommandHistoryEntry()
        entry.attachedFileNames = arrayListOf("a.txt", "b.txt")
        entry.attachedFilePaths = arrayListOf("/a.txt", "/b.txt")
        entry.attachedFileMimes = arrayListOf("text/plain", "text/plain")
        val files = entry.toAttachedFiles()
        files shouldHaveSize 2
        files[0] shouldBe AttachedFile(name = "a.txt", path = "/a.txt", mime = "text/plain")
        files[1] shouldBe AttachedFile(name = "b.txt", path = "/b.txt", mime = "text/plain")
    }

    // 3. toAttachedFiles() with mismatched sizes — truncates to min size
    @Test
    fun `toAttachedFiles with mismatched sizes truncates to min size`() {
        val entry = CommandHistoryEntry()
        entry.attachedFileNames = arrayListOf("a.txt", "b.txt", "c.txt")
        entry.attachedFilePaths = arrayListOf("/a.txt")           // only 1
        entry.attachedFileMimes = arrayListOf("text/plain", "text/plain")  // 2
        // min(3, 1, 2) = 1 → only the first entry is reconstructed.
        val files = entry.toAttachedFiles()
        files shouldHaveSize 1
        files[0] shouldBe AttachedFile(name = "a.txt", path = "/a.txt", mime = "text/plain")
    }

    @Test
    fun `toAttachedFiles with empty names but non-empty paths returns empty`() {
        val entry = CommandHistoryEntry()
        entry.attachedFileNames = arrayListOf()
        entry.attachedFilePaths = arrayListOf("/a.txt")
        entry.attachedFileMimes = arrayListOf("text/plain")
        // min(0, 1, 1) = 0 → empty.
        entry.toAttachedFiles() shouldHaveSize 0
    }

    // 4. Construct with text only, verify text field
    @Test
    fun `construct with text only sets text field`() {
        val entry = CommandHistoryEntry(text = "hello world", files = emptyList())
        entry.text shouldBe "hello world"
        entry.attachedFileNames shouldHaveSize 0
        entry.attachedFilePaths shouldHaveSize 0
        entry.attachedFileMimes shouldHaveSize 0
        entry.toAttachedFiles() shouldHaveSize 0
    }

    // 5. Construct with attached files, verify the parallel lists are populated
    @Test
    fun `construct with attached files populates parallel lists`() {
        val files = listOf(
            AttachedFile(name = "a.txt", path = "/a.txt", mime = "text/plain"),
            AttachedFile(name = "b.png", path = "/b.png", mime = "image/png"),
        )
        val entry = CommandHistoryEntry(text = "msg", files = files)
        entry.text shouldBe "msg"
        entry.attachedFileNames shouldBe arrayListOf("a.txt", "b.png")
        entry.attachedFilePaths shouldBe arrayListOf("/a.txt", "/b.png")
        entry.attachedFileMimes shouldBe arrayListOf("text/plain", "image/png")
    }

    // 6. Round-trip: create entry → toAttachedFiles() → verify fields match
    @Test
    fun `round-trip create entry then toAttachedFiles preserves fields`() {
        val original = listOf(
            AttachedFile(name = "Main.kt", path = "src/Main.kt", mime = "text/x-kotlin"),
            AttachedFile(name = "config.json", path = "config/config.json", mime = "application/json"),
            AttachedFile(name = "logo.png", path = "assets/logo.png", mime = "image/png"),
        )
        val entry = CommandHistoryEntry(text = "build the project", files = original)
        val reconstructed = entry.toAttachedFiles()
        reconstructed shouldHaveSize original.size
        for (i in original.indices) {
            reconstructed[i].name shouldBe original[i].name
            reconstructed[i].path shouldBe original[i].path
            reconstructed[i].mime shouldBe original[i].mime
        }
    }

    @Test
    fun `no-arg constructor creates empty entry`() {
        val entry = CommandHistoryEntry()
        entry.text shouldBe ""
        entry.attachedFileNames shouldHaveSize 0
        entry.attachedFilePaths shouldHaveSize 0
        entry.attachedFileMimes shouldHaveSize 0
    }

    @Test
    fun `text field is mutable`() {
        val entry = CommandHistoryEntry()
        entry.text = "updated"
        entry.text shouldBe "updated"
    }
}