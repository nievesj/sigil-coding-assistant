package com.opencode.acp.chat.ui.compose

/**
 * Result of detecting an `@` mention trigger in the input text.
 *
 * @param active true if the mention palette should be shown
 * @param query the text between `@` and the cursor (empty string if `@` was just typed)
 * @param startIndex the character index of the `@` in the original text, or -1 if not active
 */
data class MentionTriggerResult(
    val active: Boolean,
    val query: String,
    val startIndex: Int,
)

/**
 * Detects whether the input text has an active `@` mention trigger at the cursor position.
 *
 * The mention is active when:
 * - There is an `@` character in the text before the cursor
 * - The `@` is at the start of the text or preceded by whitespace (so `email@example.com`
 *   does NOT trigger)
 * - There is no whitespace between `@` and the cursor (typing a space "closes" the mention)
 *
 * @param text the full input text
 * @param cursorPos the cursor position (0-based character offset into [text])
 * @return a [MentionTriggerResult] describing whether the palette should show and what query to filter on
 */
fun detectMentionTrigger(text: String, cursorPos: Int): MentionTriggerResult {
    val textBeforeCursor = text.substring(0, cursorPos.coerceIn(0, text.length))
    val atIndex = textBeforeCursor.lastIndexOf('@')
    if (atIndex < 0) {
        return MentionTriggerResult(active = false, query = "", startIndex = -1)
    }
    // Extract query between "@" and cursor
    val afterAt = textBeforeCursor.substring(atIndex + 1)
    // If there's whitespace after "@", the mention is "closed" — dismiss
    if (afterAt.contains(' ') || afterAt.contains('\n') || afterAt.contains('\t')) {
        return MentionTriggerResult(active = false, query = "", startIndex = -1)
    }
    // Don't trigger if "@" is preceded by a non-whitespace, non-start character
    // (e.g. "email@example.com" should not trigger). Allow "@" at start of text
    // or after whitespace.
    val charBefore = if (atIndex > 0) textBeforeCursor[atIndex - 1] else ' '
    if (!charBefore.isWhitespace() && atIndex != 0) {
        return MentionTriggerResult(active = false, query = "", startIndex = -1)
    }
    return MentionTriggerResult(active = true, query = afterAt, startIndex = atIndex)
}