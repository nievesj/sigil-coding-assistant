package com.opencode.acp.chat.ui.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Immutable state snapshot for the keyboard reducer.
 *
 * The reducer resolves actual objects (SlashCommand, RecentFile, CommandHistoryEntry)
 * via INDICES — the state carries sizes and indices, and the InputArea executor
 * resolves them against the real lists it owns.
 *
 * @param text the current input text (trimmed view is computed by the reducer as needed)
 * @param cursorPos the current cursor position in the text
 * @param showSlashPalette whether the slash command palette is visible
 * @param showMentionPalette whether the @-mention file palette is visible
 * @param showAttachMenu whether the attach menu popup is visible
 * @param filteredSlashSize the number of filtered slash commands (0 means palette empty)
 * @param filteredMentionSize the number of filtered mention files (0 means palette empty)
 * @param slashSelectedIndex the currently highlighted slash command index
 * @param mentionSelectedIndex the currently highlighted mention file index
 * @param historyIndex the current history navigation index (-1 = not navigating)
 * @param inHistoryMode whether history navigation mode is active
 * @param commandHistorySize the total number of command history entries (0 means empty)
 * @param hasMatchingSlashCommand whether the first word after `/` matches a known
 *   slash command. Computed by the executor before calling the reducer so the
 *   reducer stays pure (no access to the commands list).
 */
data class InputKeyboardState(
    val text: String,
    val cursorPos: Int,
    val showSlashPalette: Boolean,
    val showMentionPalette: Boolean,
    val showAttachMenu: Boolean,
    val filteredSlashSize: Int,
    val filteredMentionSize: Int,
    val slashSelectedIndex: Int,
    val mentionSelectedIndex: Int,
    val historyIndex: Int,
    val inHistoryMode: Boolean,
    val commandHistorySize: Int,
    val hasMatchingSlashCommand: Boolean = false,
)

/**
 * Pure, Compose-independent view of a keyboard event. The InputArea executor
 * adapts a Compose [KeyEvent] to this type before calling [InputKeyboardHandler].
 *
 * Decoupling the reducer from [KeyEvent] makes the reducer unit-testable without
 * Compose infrastructure — [KeyEvent] is a `@JvmInline value class` wrapping a
 * `NativeKeyEvent` and cannot be mocked with MockK, and its extension properties
 * (`key`, `type`, `isShiftPressed`) are not constructable in a pure JVM test.
 */
data class KeyboardEventInput(
    val key: Key,
    val isShiftPressed: Boolean,
    val isKeyDown: Boolean,
)

/**
 * Action emitted by [InputKeyboardHandler.handleKeyEvent]. The InputArea executor
 * applies these to the real TextFieldState and Compose state.
 */
sealed interface InputKeyboardAction {
    /** Move the slash palette selection to [index]. */
    data class SelectSlashIndex(val index: Int) : InputKeyboardAction
    /** Move the mention palette selection to [index]. */
    data class SelectMentionIndex(val index: Int) : InputKeyboardAction
    /** Confirm the mention file selection (replace @query with @filename, attach file). */
    object SelectMentionFile : InputKeyboardAction
    /** Dismiss the mention palette. */
    object DismissMention : InputKeyboardAction
    /** Navigate command history by [delta] (1 = older, -1 = newer). */
    data class NavigateHistory(val delta: Int) : InputKeyboardAction
    /** Restore the saved draft (exit history mode). */
    object RestoreDraft : InputKeyboardAction
    /** Execute the currently selected slash command (executor resolves the command). */
    object ExecuteSlashCommand : InputKeyboardAction
    /** Send the current message. For `//` escape, the executor strips one `/`. */
    object Send : InputKeyboardAction
    /** Insert a newline at the cursor. */
    object InsertNewline : InputKeyboardAction
    /** Cancel streaming (or no-op if not streaming). */
    object Cancel : InputKeyboardAction
    /** Dismiss the slash command palette. */
    object DismissSlashPalette : InputKeyboardAction
    /** Dismiss the attach menu popup. */
    object DismissAttachMenu : InputKeyboardAction
    /** No action — pass the event through. */
    object None : InputKeyboardAction
}

/**
 * Pure reducer for keyboard event DECISION LOGIC.
 *
 * Branch order (MUST NOT change — `//` escape must be checked before slash
 * interception, and Escape cascade must follow the documented order):
 *
 * 1. Up/Down arrow → slash palette navigation (if palette visible & non-empty)
 * 2. Up/Down arrow → mention palette navigation (if palette visible & non-empty)
 * 3. Enter + mention palette → select file
 * 4. Escape + mention palette → dismiss
 * 5. Up/Down arrow → command history (if no palettes visible)
 * 6. Enter + slash palette → execute command
 * 7. Enter (no modifiers) → `//` escape OR slash interception OR send
 * 8. Shift+Enter → insert newline
 * 9. Escape → dismiss slash / dismiss attach / cancel history / cancel streaming
 * 10. else → None
 *
 * The reducer does NOT touch TextFieldState, Compose state, or any side-effectful
 * API. It only inspects the [KeyboardEventInput] and [InputKeyboardState] and
 * returns an [InputKeyboardAction]. The InputArea executor applies the action.
 *
 * The public [handleKeyEvent] overload accepts a Compose [KeyEvent] and adapts
 * it to [KeyboardEventInput] for backward compatibility with the InputArea
 * executor. Tests call the [handleKeyEvent] overload that takes
 * [KeyboardEventInput] directly.
 */
object InputKeyboardHandler {

    /**
     * Compose-dependent entry point. Adapts a [KeyEvent] to [KeyboardEventInput]
     * and delegates to the pure reducer. Called by the InputArea executor.
     */
    fun handleKeyEvent(event: KeyEvent, state: InputKeyboardState): InputKeyboardAction {
        val input = KeyboardEventInput(
            key = event.key,
            isShiftPressed = event.isShiftPressed,
            isKeyDown = event.type == KeyEventType.KeyDown,
        )
        return handleKeyEvent(input, state)
    }

    /**
     * Pure reducer entry point. Takes a [KeyboardEventInput] (no Compose
     * dependency) and an [InputKeyboardState], returns an [InputKeyboardAction].
     * Unit-testable without Compose infrastructure.
     */
    fun handleKeyEvent(event: KeyboardEventInput, state: InputKeyboardState): InputKeyboardAction {
        // Only handle KeyDown events; anything else is None.
        if (!event.isKeyDown) return InputKeyboardAction.None

        val slashPaletteVisible = state.showSlashPalette && state.filteredSlashSize > 0
        val mentionPaletteVisible = state.showMentionPalette && state.filteredMentionSize > 0
        val historyAvailable = state.commandHistorySize > 0

        return when {
            // 1. Up arrow — navigate slash palette selection (older)
            event.key == Key.DirectionUp && !event.isShiftPressed && slashPaletteVisible -> {
                InputKeyboardAction.SelectSlashIndex((state.slashSelectedIndex - 1).coerceAtLeast(0))
            }
            // Down arrow — navigate slash palette selection (newer)
            event.key == Key.DirectionDown && !event.isShiftPressed && slashPaletteVisible -> {
                InputKeyboardAction.SelectSlashIndex(
                    (state.slashSelectedIndex + 1).coerceAtMost(state.filteredSlashSize - 1)
                )
            }
            // 2. Up arrow — navigate mention palette selection
            event.key == Key.DirectionUp && !event.isShiftPressed && mentionPaletteVisible -> {
                InputKeyboardAction.SelectMentionIndex((state.mentionSelectedIndex - 1).coerceAtLeast(0))
            }
            // Down arrow — navigate mention palette selection
            event.key == Key.DirectionDown && !event.isShiftPressed && mentionPaletteVisible -> {
                InputKeyboardAction.SelectMentionIndex(
                    (state.mentionSelectedIndex + 1).coerceAtMost(state.filteredMentionSize - 1)
                )
            }
            // 3. Enter with mention palette: select the file
            event.key == Key.Enter && !event.isShiftPressed && mentionPaletteVisible -> {
                InputKeyboardAction.SelectMentionFile
            }
            // 4. Escape with mention palette: dismiss it
            event.key == Key.Escape && state.showMentionPalette -> {
                InputKeyboardAction.DismissMention
            }
            // 5. Up arrow — navigate command history (older).
            event.key == Key.DirectionUp && !event.isShiftPressed &&
                !state.showSlashPalette && !state.showMentionPalette && historyAvailable -> {
                InputKeyboardAction.NavigateHistory(1)
            }
            // Down arrow — navigate command history (newer) or restore draft.
            // Only fires when in history mode (no palettes visible).
            event.key == Key.DirectionDown && !event.isShiftPressed &&
                !state.showSlashPalette && !state.showMentionPalette && state.inHistoryMode -> {
                val newIndex = state.historyIndex - 1
                if (newIndex < 0) {
                    InputKeyboardAction.RestoreDraft
                } else {
                    InputKeyboardAction.NavigateHistory(-1)
                }
            }
            // 6. Enter with slash palette: execute selected command
            event.key == Key.Enter && !event.isShiftPressed && state.showSlashPalette -> {
                InputKeyboardAction.ExecuteSlashCommand
            }
            // 7. Enter (no modifiers) — // escape OR slash interception OR send.
            // CRITICAL: // escape must be checked BEFORE slash interception.
            event.key == Key.Enter && !event.isShiftPressed -> {
                val text = state.text.trim()
                when {
                    // Escape mechanism: "//" prefix strips one "/" and sends the
                    // rest as literal text (lets the user send text starting with
                    // "/" without triggering a slash command).
                    text.startsWith("//") -> InputKeyboardAction.Send
                    // Slash command interception: text starts with "/" and the
                    // first word matches a known command. The executor resolves
                    // the actual command object.
                    text.startsWith("/") && state.hasMatchingSlashCommand -> {
                        InputKeyboardAction.ExecuteSlashCommand
                    }
                    else -> InputKeyboardAction.Send
                }
            }
            // 8. Shift+Enter — insert newline
            event.key == Key.Enter && event.isShiftPressed -> {
                InputKeyboardAction.InsertNewline
            }
            // 9. Escape — cascade: dismiss slash → dismiss attach → cancel history → cancel streaming
            event.key == Key.Escape -> {
                when {
                    state.showSlashPalette -> InputKeyboardAction.DismissSlashPalette
                    state.showAttachMenu -> InputKeyboardAction.DismissAttachMenu
                    state.inHistoryMode -> InputKeyboardAction.RestoreDraft
                    else -> InputKeyboardAction.Cancel
                }
            }
            // 10. else
            else -> InputKeyboardAction.None
        }
    }
}