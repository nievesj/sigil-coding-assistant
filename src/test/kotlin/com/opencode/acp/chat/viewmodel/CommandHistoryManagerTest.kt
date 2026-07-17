package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.CommandHistoryEntry
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CommandHistoryManager] (TDD §4.2.6).
 *
 * Tests command history persistence, recall, deduplication, trimming, and
 * clearing. [CommandHistoryManager] reads [OpenCodeSettingsState.getInstance]
 * directly (not injected), so the companion object is mocked to return a real
 * [OpenCodeSettingsState] instance with a small [commandHistorySize].
 */
class CommandHistoryManagerTest {

    private lateinit var settingsState: OpenCodeSettingsState
    private lateinit var manager: CommandHistoryManager

    @BeforeEach
    fun setUp() {
        // Use a real OpenCodeSettingsState instance so recordCommand can write
        // back to settings.commandHistory (exercising the persistence path).
        settingsState = OpenCodeSettingsState()
        settingsState.commandHistorySize = 5
        settingsState.commandHistory = java.util.ArrayList()

        mockkObject(OpenCodeSettingsState.Companion)
        every { OpenCodeSettingsState.getInstance() } returns settingsState

        manager = CommandHistoryManager()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OpenCodeSettingsState.Companion)
    }

    // ── recordCommand ────────────────────────────────────────────────────

    @Test
    fun `recordCommand adds entry to commandHistory`() {
        manager.recordCommand("hello", emptyList())
        manager.commandHistory.value shouldHaveSize 1
        manager.commandHistory.value[0].text shouldBe "hello"
    }

    @Test
    fun `recordCommand with blank text and empty files is a no-op`() {
        manager.recordCommand("   ", emptyList())
        manager.recordCommand("", emptyList())
        manager.commandHistory.value shouldHaveSize 0
    }

    @Test
    fun `recordCommand with files but blank text still records`() {
        val files = listOf(AttachedFile(name = "a.txt", path = "/a", mime = "text/plain"))
        manager.recordCommand("", files)
        manager.commandHistory.value shouldHaveSize 1
        manager.commandHistory.value[0].text shouldBe ""
        manager.commandHistory.value[0].attachedFileNames shouldBe arrayListOf("a.txt")
    }

    @Test
    fun `recordCommand deduplicates same text and files moving to front`() {
        manager.recordCommand("first", emptyList())
        manager.recordCommand("second", emptyList())
        // Re-record "first" — should move to front, not duplicate
        manager.recordCommand("first", emptyList())
        manager.commandHistory.value shouldHaveSize 2
        manager.commandHistory.value[0].text shouldBe "first"
        manager.commandHistory.value[1].text shouldBe "second"
    }

    @Test
    fun `recordCommand does not deduplicate when files differ`() {
        val fileA = listOf(AttachedFile(name = "a.txt", path = "/a", mime = "text/plain"))
        val fileB = listOf(AttachedFile(name = "b.txt", path = "/b", mime = "text/plain"))
        manager.recordCommand("same", fileA)
        manager.recordCommand("same", fileB)
        manager.commandHistory.value shouldHaveSize 2
        manager.commandHistory.value[0].attachedFileNames shouldBe arrayListOf("b.txt")
    }

    @Test
    fun `recordCommand trims to commandHistorySize`() {
        settingsState.commandHistorySize = 3
        manager.recordCommand("one", emptyList())
        manager.recordCommand("two", emptyList())
        manager.recordCommand("three", emptyList())
        manager.recordCommand("four", emptyList())
        manager.recordCommand("five", emptyList())
        manager.commandHistory.value shouldHaveSize 3
        manager.commandHistory.value[0].text shouldBe "five"
        manager.commandHistory.value[2].text shouldBe "three"
    }

    @Test
    fun `recordCommand with files stores file names paths and mimes`() {
        val files = listOf(
            AttachedFile(name = "a.kt", path = "/src/a.kt", mime = "text/x-kotlin"),
            AttachedFile(name = "b.json", path = "/res/b.json", mime = "application/json"),
        )
        manager.recordCommand("review", files)
        val entry = manager.commandHistory.value[0]
        entry.text shouldBe "review"
        entry.attachedFileNames shouldBe arrayListOf("a.kt", "b.json")
        entry.attachedFilePaths shouldBe arrayListOf("/src/a.kt", "/res/b.json")
        entry.attachedFileMimes shouldBe arrayListOf("text/x-kotlin", "application/json")
    }

    @Test
    fun `recordCommand persists to settings commandHistory`() {
        manager.recordCommand("persisted", emptyList())
        settingsState.commandHistory shouldHaveSize 1
        settingsState.commandHistory[0].text shouldBe "persisted"
    }

    // ── clearCommandHistory ───────────────────────────────────────────────

    @Test
    fun `clearCommandHistory empties the StateFlow`() {
        manager.recordCommand("a", emptyList())
        manager.recordCommand("b", emptyList())
        manager.clearCommandHistory()
        manager.commandHistory.value shouldHaveSize 0
    }

    @Test
    fun `clearCommandHistory empties persisted settings`() {
        manager.recordCommand("a", emptyList())
        manager.clearCommandHistory()
        settingsState.commandHistory shouldHaveSize 0
    }

    @Test
    fun `clearCommandHistory on empty history is a no-op`() {
        manager.clearCommandHistory()
        manager.commandHistory.value shouldHaveSize 0
    }

    // ── loadFromSettings ─────────────────────────────────────────────────

    @Test
    fun `loadFromSettings loads entries from settings commandHistory`() {
        val existing = CommandHistoryEntry(text = "old", files = emptyList())
        settingsState.commandHistory = java.util.ArrayList(listOf(existing))

        manager.loadFromSettings()
        manager.commandHistory.value shouldHaveSize 1
        manager.commandHistory.value[0].text shouldBe "old"
    }

    @Test
    fun `loadFromSettings loads multiple entries preserving order`() {
        val entries = listOf(
            CommandHistoryEntry(text = "first", files = emptyList()),
            CommandHistoryEntry(text = "second", files = emptyList()),
            CommandHistoryEntry(text = "third", files = emptyList()),
        )
        settingsState.commandHistory = java.util.ArrayList(entries)

        manager.loadFromSettings()
        manager.commandHistory.value shouldHaveSize 3
        manager.commandHistory.value.map { it.text } shouldBe listOf("first", "second", "third")
    }

    @Test
    fun `loadFromSettings with empty settings yields empty history`() {
        settingsState.commandHistory = java.util.ArrayList()
        manager.loadFromSettings()
        manager.commandHistory.value shouldHaveSize 0
    }

    @Test
    fun `loadFromSettings replaces existing StateFlow contents`() {
        manager.recordCommand("initial", emptyList())
        manager.commandHistory.value shouldHaveSize 1

        val loaded = CommandHistoryEntry(text = "loaded", files = emptyList())
        settingsState.commandHistory = java.util.ArrayList(listOf(loaded))
        manager.loadFromSettings()

        manager.commandHistory.value shouldHaveSize 1
        manager.commandHistory.value[0].text shouldBe "loaded"
    }
}