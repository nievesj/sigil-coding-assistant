package com.opencode.acp.review

/**
 * Empty-state and non-git guidance message constants for the Review feature.
 *
 * The `_UI` variants are for composable rendering in the Review tab (shorter,
 * multi-line via `\n`). The plain variants are for `injectLocalMessage` calls
 * in `ReviewCommandHandler` (longer, single-line, user-actionable).
 *
 * See TDD `docs/tdd/review-staged-changes-only.md` §4.7.5.
 */
object ReviewMessages {
    /** Injected local message when `/review-perform*` finds nothing staged. */
    const val NOTHING_STAGED =
        "⚠️ No staged changes. Use `git add <file>` to stage files for review, " +
                "then run /review-perform again."

    /** Injected local message when `/review-perform*` runs in a non-git project. */
    const val NO_GIT_REPO =
        "⚠️ Staged-changes review requires a Git repository. " +
                "Initialize git (`git init`) or open a git project."

    /** Injected local message prefix when `git diff --cached` fails for all repos.
     *  The actual error message is appended at runtime. */
    const val GIT_ERROR_PREFIX =
        "⚠️ Git command failed: "

    /** Review tab UI text when nothing is staged. */
    const val NOTHING_STAGED_UI =
        "No staged changes\nUse `git add` to stage files for review"

    /** Review tab UI text when the project is not a git repository. */
    const val NO_GIT_REPO_UI =
        "Staged-changes review requires a Git repository\n" +
                "Initialize git to use the Review tab"

    /** Review tab UI text when git command fails. The actual error is appended. */
    const val GIT_ERROR_UI_PREFIX =
        "Git command failed\n"
}