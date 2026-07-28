package com.opencode.acp.chat.ui.compose

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for [InputKeyboardHandler] skill palette branches — the pure keyboard
 * event reducer.
 *
 * Covers TDD §8.2 scenarios 18-20: skill palette keyboard navigation, `$$`
 * escape, and Escape cascade priority.
 *
 * These tests verify the DECISION LOGIC only (which [InputKeyboardAction] is
 * emitted for a given [KeyboardEventInput] + [InputKeyboardState] combination).
 * The action EXECUTION (applying the action to TextFieldState) lives in
 * InputArea and needs Compose integration tests.
 *
 * Branch-order invariants verified:
 * - `$$` escape is checked BEFORE slash interception and before plain send
 * - Skill palette navigation takes priority over slash palette navigation
 *   (mutual exclusion: only one palette is visible at a time)
 * - Escape cascade order: skill palette → slash palette → attach menu → history → cancel streaming
 *
 * NOTE: Tests use [KeyboardEventInput] (a pure data class) rather than the
 * Compose [androidx.compose.ui.input.key.KeyEvent] value class, which cannot
 * be constructed or mocked in a pure JVM test.
 */
class InputKeyboardHandlerSkillTest {

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
        showSkillPalette = false,
        filteredSkillSize = 0,
        skillSelectedIndex = 0,
    ).block()

    // ── $$ escape does not trigger skill palette ─────────────────────────────

    @Test
    fun `dollar-dollar escape does not trigger skill palette - showSkillPalette false`() {
        // $$foo: the reducer never sees showSkillPalette=true because InputArea
        // only sets showSkillPalette when text starts with a single "$". Here we
        // verify that with showSkillPalette=false, Enter returns Send (which the
        // executor strips to "$foo").
        val state = defaultState {
            copy(text = "${'$'}${'$'}foo")
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `dollar-dollar-slash does not trigger skill palette - sends as literal slash-dollar`() {
        // $$/ : with showSkillPalette=false, Enter returns Send. The executor
        // strips one "$" → sends "$/" as literal text.
        val state = defaultState {
            copy(text = "${'$'}${'$'}/")
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `dollar with newline does not trigger skill palette`() {
        // InputArea only sets showSkillPalette when text has no newline. The
        // reducer trusts the showSkillPalette flag, so with it false, Enter sends.
        val state = defaultState {
            copy(text = "${'$'}git\ncommit")
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    // ── Skill palette keyboard navigation ────────────────────────────────────

    @Test
    fun `skill palette Up arrow navigates selection`() {
        val state = defaultState {
            copy(
                text = "${'$'}git",
                cursorPos = 4,
                showSkillPalette = true,
                filteredSkillSize = 3,
                skillSelectedIndex = 1,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSkillIndex>()
        action.index shouldBe 0
    }

    @Test
    fun `skill palette Down arrow navigates selection`() {
        val state = defaultState {
            copy(
                text = "${'$'}git",
                showSkillPalette = true,
                filteredSkillSize = 3,
                skillSelectedIndex = 1,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSkillIndex>()
        action.index shouldBe 2
    }

    @Test
    fun `skill palette Up arrow at index 0 stays at 0`() {
        val state = defaultState {
            copy(
                text = "${'$'}git",
                showSkillPalette = true,
                filteredSkillSize = 3,
                skillSelectedIndex = 0,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSkillIndex>()
        action.index shouldBe 0
    }

    @Test
    fun `skill palette Down arrow at last index stays at last`() {
        val state = defaultState {
            copy(
                text = "${'$'}git",
                showSkillPalette = true,
                filteredSkillSize = 3,
                skillSelectedIndex = 2,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionDown), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSkillIndex>()
        action.index shouldBe 2
    }

    @Test
    fun `skill palette navigation ignored when palette empty`() {
        val state = defaultState {
            copy(
                showSkillPalette = true,
                filteredSkillSize = 0,
                skillSelectedIndex = 0,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        // Falls through — no history available → None
        action shouldBe InputKeyboardAction.None
    }

    // ── Enter + skill palette ───────────────────────────────────────────────

    @Test
    fun `Enter with skill palette visible executes skill command`() {
        val state = defaultState {
            copy(
                text = "${'$'}git",
                showSkillPalette = true,
                filteredSkillSize = 2,
                skillSelectedIndex = 0,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.ExecuteSkillCommand
    }

    @Test
    fun `Enter with skill palette visible but empty falls through to Send`() {
        // When showSkillPalette=true but filteredSkillSize=0 (no skills match),
        // Enter should fall through to Send, not ExecuteSkillCommand.
        val state = defaultState {
            copy(
                text = "${'$'}xyznonexistent",
                showSkillPalette = true,
                filteredSkillSize = 0,
                skillSelectedIndex = 0,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    // ── $$ send-time strip ───────────────────────────────────────────────────

    @Test
    fun `dollar-dollar send-time strip returns Send for dollar-dollar-foo`() {
        // $$foo → Send (executor strips one "$" → sends "$foo" as literal text)
        val state = defaultState {
            copy(text = "${'$'}${'$'}foo")
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    @Test
    fun `dollar-dollar escape takes priority over slash interception`() {
        // Even if hasMatchingSlashCommand were true, $$ is checked first.
        // (hasMatchingSlashCommand is only relevant for "/" prefix, but this
        // confirms $$ → Send regardless.)
        val state = defaultState {
            copy(text = "${'$'}${'$'}compact", hasMatchingSlashCommand = true)
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Enter), state)
        action shouldBe InputKeyboardAction.Send
    }

    // ── Escape cascade — skill palette priority ─────────────────────────────

    @Test
    fun `Escape with skill palette visible dismisses skill palette`() {
        val state = defaultState {
            copy(
                showSkillPalette = true,
                showSlashPalette = false,
                showAttachMenu = true,
                inHistoryMode = true,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissSkillPalette
    }

    @Test
    fun `Escape skill palette has priority over slash palette`() {
        // When both showSkillPalette and showSlashPalette are true (should not
        // happen in practice due to mutual exclusion, but the reducer must be
        // deterministic), skill palette dismissal wins.
        val state = defaultState {
            copy(
                showSkillPalette = true,
                showSlashPalette = true,
                showAttachMenu = true,
                inHistoryMode = true,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissSkillPalette
    }

    @Test
    fun `Escape with slash palette visible (no skill palette) dismisses slash palette`() {
        val state = defaultState {
            copy(
                showSkillPalette = false,
                showSlashPalette = true,
                showAttachMenu = true,
                inHistoryMode = true,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.Escape), state)
        action shouldBe InputKeyboardAction.DismissSlashPalette
    }

    // ── Skill palette takes priority over slash palette for Up/Down ──────────

    @Test
    fun `Up arrow with skill palette visible selects skill index not slash index`() {
        // Mutual exclusion ensures only one palette is visible at a time, but
        // verify the reducer checks skill palette BEFORE slash palette.
        val state = defaultState {
            copy(
                showSkillPalette = true,
                filteredSkillSize = 3,
                skillSelectedIndex = 1,
                showSlashPalette = true,
                filteredSlashSize = 5,
                slashSelectedIndex = 2,
            )
        }
        val action = InputKeyboardHandler.handleKeyEvent(keyEvent(Key.DirectionUp), state)
        action.shouldBeInstanceOf<InputKeyboardAction.SelectSkillIndex>()
        action.index shouldBe 0
    }
}