package com.opencode.acp.chat.ui.compose

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for [InputKeyboardHandler] — the pure keyboard event reducer.
 *
 * These tests verify the DECISION LOGIC only (which [InputKeyboardAction] is
 * emitted for a given [KeyboardEventInput] + [InputKeyboardState] combination).
 * The action EXECUTION (applying the action to TextFieldState) lives in
 * InputArea and needs Compose integration tests.
 *
 * Branch-order invariants verified:
 * - `//` escape is checked BEFORE slash interception (test `double-slash escape takes priority over slash interception`)
 * - Escape cascade order: slash palette → attach menu → history → cancel streaming
 * - Slash palette navigation takes priority over mention palette navigation
 *
 * NOTE: Tests use [KeyboardEventInput] (a pure data class) rather than the
 * Compose [androidx.compose.ui.input.key.KeyEvent] value class, which cannot
 * be constructed or mocked in a pure JVM test. The InputArea executor adapts
 * real KeyEvents to [KeyboardEventInput] before calling the reducer.
 */
class InputKeyboardHandlerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Build a [KeyboardEventInput] with the given [Key], shift state, and key-down state. */
    private fun keyEvent(
        key: Key,
        shift: Boolean = false,
        isKeyDown: Boolean = true,
    ): KeyboardEventInput = KeyboardEventInput(key = key, isShiftPressed = shift, isKeyDown = isKeyDown)

    /** Default state — nothing visible, empty input. */
    private fun defaultState(
        block: InputKeyboardState.() -> InputKeyboardState = { this }
    ): InputKeyboardState = InputKeyboardState(
        text = "",
        cursorPos = 0,
        showSlashPalette = false,
        showMentionPalette = false,
        showAttachMenu = false,
        filteredSlashSize = 0,
        filteredMentionSize = 0,
        slashSelectedIndex = 0,
        mentionSelectedIndex = 0,
        historyIndex = -1,
        inHistoryMode = false,
        commandHistorySize = 0,
        hasMatchingSlashCommand = false,
    ).block()

    // ── Slash palette navigation ─────────────────────────────────────────────

    @Test
    fun `Up arrow with slash palette visible selects previous slash index`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 5, slashSelectedIndex = 2)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSlashIndex>()
        action.index shouldBe 1
    }

    @Test
    fun `Up arrow with slash palette at index 0 stays at 0`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 5, slashSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSlashIndex>()
        action.index shouldBe 0
    }

    @Test
    fun `Down arrow with slash palette visible selects next slash index`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 5, slashSelectedIndex = 2)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSlashIndex>()
        action.index shouldBe 3
    }

    @Test
    fun `Down arrow with slash palette at last index stays at last`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 5, slashSelectedIndex = 4)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSlashIndex>()
        action.index shouldBe 4
    }

    @Test
    fun `Up arrow with slash palette NOT visible falls through to history`() {
        val state = defaultState {
            copy(showSlashPalette = false, commandHistorySize = 3, historyIndex = -1)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.NavigateHistory>()
        action.delta shouldBe 1
    }

    @Test
    fun `Slash palette navigation ignored when palette empty`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 0, slashSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        // Falls through to history (commandHistorySize = 0 → None)
        action shouldBe InputKeyboardAction.None
    }

    // ── Mention palette navigation ───────────────────────────────────────────

    @Test
    fun `Up arrow with mention palette visible selects previous mention index`() {
        val state = defaultState {
            copy(showMentionPalette = true, filteredMentionSize = 3, mentionSelectedIndex = 1)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectMentionIndex>()
        action.index shouldBe 0
    }

    @Test
    fun `Down arrow with mention palette visible selects next mention index`() {
        val state = defaultState {
            copy(showMentionPalette = true, filteredMentionSize = 3, mentionSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectMentionIndex>()
        action.index shouldBe 1
    }

    @Test
    fun `Enter with mention palette visible selects mention file`() {
        val state = defaultState {
            copy(showMentionPalette = true, filteredMentionSize = 2, mentionSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.SelectMentionFile
    }

    @Test
    fun `Escape with mention palette visible dismisses mention`() {
        val state = defaultState {
            copy(showMentionPalette = true, filteredMentionSize = 2)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissMention
    }

    // ── Command history navigation ───────────────────────────────────────────

    @Test
    fun `Up arrow with no palettes and non-empty history navigates older`() {
        val state = defaultState {
            copy(commandHistorySize = 3, historyIndex = -1)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.NavigateHistory>()
        action.delta shouldBe 1
    }

    @Test
    fun `Down arrow with no palettes and inHistoryMode navigates newer`() {
        val state = defaultState {
            copy(commandHistorySize = 3, historyIndex = 2, inHistoryMode = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.NavigateHistory>()
        action.delta shouldBe -1
    }

    @Test
    fun `Down arrow at history index 0 in history mode restores draft`() {
        val state = defaultState {
            copy(commandHistorySize = 3, historyIndex = 0, inHistoryMode = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action shouldBe InputKeyboardAction.RestoreDraft
    }

    @Test
    fun `Down arrow with no palettes but NOT in history mode is None`() {
        val state = defaultState {
            copy(commandHistorySize = 3, historyIndex = -1, inHistoryMode = false)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action shouldBe InputKeyboardAction.None
    }

    // ── Enter + slash palette ───────────────────────────────────────────────

    @Test
    fun `Enter with slash palette visible executes slash command`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 2, slashSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.ExecuteSlashCommand
    }

    // ── Enter — double-slash escape vs slash interception vs send ────────────

    @Test
    fun `Enter with text starting double-slash sends (escape mechanism)`() {
        val state = defaultState {
            copy(text = "//foo", hasMatchingSlashCommand = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `Enter with text starting slash and matching command executes slash command`() {
        val state = defaultState {
            copy(text = "/compact", hasMatchingSlashCommand = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.ExecuteSlashCommand
    }

    @Test
    fun `Enter with text starting slash and NO matching command sends`() {
        val state = defaultState {
            copy(text = "/unknowncmd", hasMatchingSlashCommand = false)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `Enter with plain text sends`() {
        val state = defaultState {
            copy(text = "hello world")
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `double-slash escape takes priority over slash interception`() {
        // CRITICAL: text = "//foo" with hasMatchingSlashCommand = true must return
        // Send (NOT ExecuteSlashCommand) because // escape is checked first.
        val state = defaultState {
            copy(text = "//compact", hasMatchingSlashCommand = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    // ── Shift+Enter ─────────────────────────────────────────────────────────

    @Test
    fun `Shift+Enter inserts newline`() {
        val state = defaultState { copy(text = "hello") }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter, shift = true), state)
        action shouldBe InputKeyboardAction.InsertNewline
    }

    // ── Escape cascade ──────────────────────────────────────────────────────

    @Test
    fun `Escape with slash palette visible dismisses slash palette`() {
        val state = defaultState {
            copy(showSlashPalette = true, showAttachMenu = true, inHistoryMode = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissSlashPalette
    }

    @Test
    fun `Escape with attach menu visible (no slash palette) dismisses attach menu`() {
        val state = defaultState {
            copy(showSlashPalette = false, showAttachMenu = true, inHistoryMode = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissAttachMenu
    }

    @Test
    fun `Escape in history mode (no slash, no attach) restores draft`() {
        val state = defaultState {
            copy(showSlashPalette = false, showAttachMenu = false, inHistoryMode = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.RestoreDraft
    }

    @Test
    fun `Escape with nothing visible cancels streaming`() {
        val state = defaultState()
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.Cancel
    }

    // ── Unknown keys / non-KeyDown events ────────────────────────────────────

    @Test
    fun `Unknown key returns None`() {
        val state = defaultState()
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.F1), state)
        action shouldBe InputKeyboardAction.None
    }

    @Test
    fun `KeyUp event type returns None`() {
        val state = defaultState {
            copy(showSlashPalette = true, filteredSlashSize = 2, slashSelectedIndex = 0)
        }
        val action = InputKeyboardHandler.handleKeyEvent(
            keyEvent(Key.DirectionUp, isKeyDown = false),
            state,
        )
        action shouldBe InputKeyboardAction.None
    }
}