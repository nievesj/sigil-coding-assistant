package com.opencode.acp.chat.ui.compose

/**
 * Result of detecting a `$` skill trigger in the input text.
 *
 * @param active true if the skill palette should be shown
 * @param query the text after `$` (empty string if `$` was just typed —
 *   in that case the palette shows all skills, unfiltered)
 */
data class SkillTriggerResult(
    val active: Boolean,
    val query: String,
)

/**
 * Detects whether the input text has an active `$` skill trigger.
 *
 * Mirrors the slash palette gate (`/` prefix, `//` escape, no newline):
 * - Starts with `$` (single, not `$$` escape)
 * - No newline in the text
 *
 * The palette opens **immediately on bare `$`** (showing all skills, unfiltered),
 * exactly as the slash palette opens on bare `/` (showing all commands). `$$` is
 * the escape to send literal text starting with `$`.
 *
 * The first non-escape character determines which palette: `/` → slash, `$` →
 * skill. Mutual exclusion with the slash palette is enforced by the caller
 * (InputArea), not by this function — text cannot start with both `$` and `/`.
 *
 * **History:** Previously this gate also required `text.length > 1` and a
 * valid skill-name-start character at `text[1]`, which prevented the palette
 * from opening on bare `$` — the user saw nothing happen when typing `$`.
 * That M7 gate was removed to match the slash palette's behavior.
 *
 * @param text the full input text
 * @return a [SkillTriggerResult] describing whether the palette should show
 *         and what query to filter on
 */
fun detectSkillTrigger(text: String): SkillTriggerResult {
    if (!text.startsWith("$") || text.startsWith("$$") || text.contains("\n")) {
        return SkillTriggerResult(active = false, query = "")
    }
    return SkillTriggerResult(active = true, query = text.substring(1))
}