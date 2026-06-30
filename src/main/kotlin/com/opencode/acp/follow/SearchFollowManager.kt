package com.opencode.acp.follow

import com.intellij.find.FindModel
import com.intellij.find.findInProject.FindInProjectManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Project-level service that opens IntelliJ's native "Find in Files" tool window
 * when the agent performs a search tool call, allowing the user to browse all
 * results in the standard IDE search interface.
 *
 * **Thread safety:** Called from non-EDT threads (SSE event processing). All
 * IDE API calls are dispatched to EDT via [ApplicationManager.invokeLater].
 * Throttle state uses [Volatile] for cross-thread visibility.
 *
 * **Lifecycle:** Managed by the IntelliJ project service container. [dispose]
 * is intentionally empty — the Find tool window manages its own lifecycle.
 */
@Service(Service.Level.PROJECT)
class SearchFollowManager(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}

    companion object {
        /** Minimum interval between successive "Find in Files" opens to avoid flooding. */
        const val FOLLOW_SEARCH_COOLDOWN_MS = 2_000L

        fun getInstance(project: Project): SearchFollowManager =
            project.service<SearchFollowManager>()
    }

    /** Timestamp of the last search follow (epoch millis). Cross-thread visible. */
    @Volatile
    private var lastSearchMs: Long = 0

    /**
     * Main entry point: called by SessionState when the agent executes a search tool.
     * Thread-safe — can be called from any thread.
     *
     * @param project      target project
     * @param pattern      search pattern (text or regex)
     * @param searchPath   optional directory path to scope the search
     * @param includeGlob  optional file filter glob (e.g. "*.kt")
     * @param isRegex      whether [pattern] is a regular expression
     * @param agentName    name of the agent that triggered the search (for logging)
     * @param modelName    model that triggered the search (for logging)
     */
    fun followSearch(
        project: Project,
        pattern: String?,
        searchPath: String? = null,
        includeGlob: String? = null,
        isRegex: Boolean = false,
        agentName: String? = null,
        modelName: String? = null,
    ) {
        // ── Pre-condition checks ──────────────────────────────────────
        if (!OpenCodeSettingsState.getInstance().followAgentEnabled) return
        if (!OpenCodeSettingsState.getInstance().followSearchesInFindWindow) return
        if (project.isDisposed) return
        if (pattern.isNullOrBlank()) return

        // ── Throttle ──────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        if (now - lastSearchMs < FOLLOW_SEARCH_COOLDOWN_MS) return
        lastSearchMs = now

        // ── Regex safety check ─────────────────────────────────────────
        // Reject patterns likely to cause catastrophic backtracking (ReDoS).
        // The pattern comes from untrusted SSE tool input (the LLM agent's tool call).
        if (isRegex && !isRegexSafe(pattern)) {
            logger.warn { "[ACP] Follow Agent: rejecting potentially unsafe regex pattern (ReDoS risk): '$pattern'" }
            return
        }

        logger.info {
            "[ACP] Follow Agent: scheduling Find in Files for pattern='$pattern' " +
                "(agent=${agentName ?: "unknown"}, model=${modelName ?: "unknown"})"
        }

        // ── Dispatch to EDT ───────────────────────────────────────────
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater

            try {
                val findModel = buildFindModel(pattern, searchPath, includeGlob, isRegex)
                FindInProjectManager.getInstance(project).startFindInProject(findModel)

                logger.info {
                    "[ACP] Follow Agent: Find in Files opened for pattern='$pattern'" +
                        (if (!searchPath.isNullOrBlank()) " in $searchPath" else "")
                }
            } catch (e: Exception) {
                logger.error(e) {
                    "[ACP] Follow Agent: failed to open Find in Files for pattern='$pattern'"
                }
            }
        }, ModalityState.nonModal())
    }

    // ── FindModel construction ────────────────────────────────────────

    private fun buildFindModel(
        pattern: String,
        searchPath: String?,
        includeGlob: String?,
        isRegex: Boolean,
    ): FindModel = FindModel().apply {
        stringToFind = pattern
        isRegularExpressions = isRegex
        isCaseSensitive = false
        isWholeWordsOnly = false

        // Scope: prefer directory if provided, otherwise search the whole project.
        if (!searchPath.isNullOrBlank()) {
            isProjectScope = false
            // FindModel uses directoryName for directory-scoped search.
            // The path must be relative to the project base or absolute.
            directoryName = searchPath
            isWithSubdirectories = true
        } else {
            isProjectScope = true
        }

        // File filter: e.g. "*.kt", "*.{kt,java}" — passed through as-is.
        if (!includeGlob.isNullOrBlank()) {
            fileFilter = includeGlob
        }
    }

    // ── Reopen (for ToolPill button) ───────────────────────────────────

    /**
     * Re-open Find in Files for a given search. Used by the ToolPill "open in Find"
     * button to re-trigger the search on user click. Bypasses the throttle since
     * this is an explicit user action.
     */
    fun reopenSearch(
        project: Project,
        pattern: String,
        searchPath: String? = null,
        includeGlob: String? = null,
        isRegex: Boolean = false,
    ) {
        if (project.isDisposed) return
        if (pattern.isBlank()) return

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val findModel = buildFindModel(pattern, searchPath, includeGlob, isRegex)
                FindInProjectManager.getInstance(project).startFindInProject(findModel)
                logger.info { "[ACP] Follow Agent: Find in Files reopened for pattern='$pattern'" }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] Follow Agent: error reopening Find in Files" }
            }
        }, ModalityState.nonModal())
    }

    // ── Regex safety ─────────────────────────────────────────────────

    /**
     * Heuristic check for regex patterns that could cause catastrophic backtracking (ReDoS).
     * Rejects:
     * - Patterns longer than 200 characters (unnecessary complexity for search)
     * - Patterns with nested quantifiers (e.g., `(a+)+`, `(a*)*`, `(a{1,3})+`)
     *
     * This is a best-effort heuristic — it may reject some safe patterns and allow
     * some unsafe ones. The goal is to block the most common ReDoS vectors without
     * requiring a full regex complexity analyzer.
     */
    private fun isRegexSafe(pattern: String): Boolean {
        if (pattern.length > 200) return false
        // Detect nested quantifiers: a quantifier (+, *, ?, {n,m}) immediately
        // following a group that ends with a quantifier. This is the classic
        // ReDoS pattern: (a+)+, (a*)*, (a?)+, etc.
        val nestedQuantifierRegex = Regex("""\([^)]*[+*?][^)]*\)[+*?]""")
        if (nestedQuantifierRegex.containsMatchIn(pattern)) return false
        // Also check for quantifiers on groups containing alternation with quantifiers
        val alternationQuantifierRegex = Regex("""\([^)]*[+*?][^)]*[|][^)]*[+*?][^)]*\)[+*?]""")
        if (alternationQuantifierRegex.containsMatchIn(pattern)) return false
        return true
    }

    // ── Disposable ────────────────────────────────────────────────────

    override fun dispose() {
        // Intentionally empty — the Find tool window manages its own lifecycle.
        // No coroutine scopes, timers, or background threads to cancel.
    }
}
