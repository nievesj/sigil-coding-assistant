package com.opencode.acp.review

import com.intellij.openapi.project.Project

/**
 * Documents and centralizes the registration of the `/review` slash command
 * and its backing [ReviewSkill] prompt.
 *
 * The TDD §9.4 specifies a `ReviewSkillRegistrar` initialized during
 * [ReviewCommentManager] construction. In the shipped implementation the
 * slash command is registered declaratively in
 * [com.opencode.acp.chat.ui.compose.ChatScreen] alongside `/clear` and
 * `/cancel` (local commands handled by the plugin, not sent to the server),
 * and the prompt is built by [ReviewSkill.buildPrompt] and injected via
 * [com.opencode.acp.chat.viewmodel.ChatViewModel.executeReviewCommand].
 *
 * This class exists as the documented entry point so the wiring is
 * discoverable — it is intentionally a thin marker rather than an active
 * registrar, because the actual command dispatch already lives in the chat
 * layer (where all local slash commands are handled) and duplicating the
 * registration here would create two sources of truth.
 *
 * If a future phase adds per-project `SKILL.md` file generation (the TDD's
 * original design), that logic belongs here.
 */
object ReviewSkillRegistrar {

    /** The slash-command name. Used by [com.opencode.acp.chat.ui.compose.ChatScreen]
     *  when registering local commands. */
    const val COMMAND_NAME = "review"

    /** Human-readable description shown in the slash command palette. */
    const val COMMAND_DESCRIPTION = "Read and resolve review comments"

    /** No-op initialization hook. Kept for API stability with the TDD's
     *  `ReviewCommentManager.init { ReviewSkillRegistrar.initialize(project) }`
     *  pattern — the actual registration happens in the chat layer. */
    fun initialize(@Suppress("UNUSED_PARAMETER") project: Project) {
        // Intentional no-op: the slash command is registered in ChatScreen
        // alongside /clear and /cancel. This method exists so the TDD's
        // documented initialization call site compiles if reintroduced.
    }
}