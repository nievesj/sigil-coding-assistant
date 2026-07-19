package com.opencode.acp.follow

import com.intellij.find.FindModel
import com.intellij.find.findInProject.FindInProjectManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

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
    private val lastSearchMs = java.util.concurrent.atomic.AtomicLong(0)

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
        if (!OpenCodeFollowSettingsState.getInstance().followAgentEnabled) return
        if (project.isDisposed) return
        if (pattern.isNullOrBlank()) return

        // ── Throttle ──────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        val last = lastSearchMs.get()
        if (now - last < FOLLOW_SEARCH_COOLDOWN_MS) return
        if (!lastSearchMs.compareAndSet(last, now)) return

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
            // Validate searchPath is within the project to prevent searching outside the project
            val projectBase = project.basePath
            if (projectBase != null) {
                val canonicalSearch = try {
                    java.io.File(searchPath).let { f ->
                        if (f.isAbsolute) f.canonicalPath
                        else java.io.File(projectBase, searchPath).canonicalPath
                    }
                } catch (_: Exception) { null }
                val canonicalBase = try { java.io.File(projectBase).canonicalPath } catch (_: Exception) { null }
                if (canonicalSearch != null && canonicalBase != null) {
                    val searchNorm = canonicalSearch.replace('\\', '/')
                    val baseNorm = canonicalBase.replace('\\', '/')
                    val isWindows = System.getProperty("os.name").lowercase().contains("win")
                    val prefixMatches = if (isWindows) {
                        searchNorm.equals(baseNorm, ignoreCase = true) ||
                            searchNorm.startsWith("$baseNorm/", ignoreCase = true)
                    } else {
                        searchNorm == baseNorm || searchNorm.startsWith("$baseNorm/")
                    }
                    if (!prefixMatches) {
                        logger.warn { "[ACP] Follow Agent: searchPath outside project blocked: $searchPath" }
                        isProjectScope = true
                    } else {
                        // The canonical path is the authoritative traversal check (above).
                        // However, FindInProjectManager's directoryName is matched against
                        // VFS paths, which on Windows may be registered under the symlink
                        // form (not the canonical form). If the non-canonical absolute path
                        // also passes the prefix check, prefer it for directoryName since
                        // VFS likely registered the symlink form. Fall back to the canonical
                        // path only if the non-canonical form fails the prefix check.
                        val absNormPath = try {
                            java.io.File(searchPath).let { f ->
                                if (f.isAbsolute) f.path
                                else java.io.File(projectBase, searchPath).path
                            }
                        } catch (_: Exception) { null }
                        val directoryForVfs = if (absNormPath != null) {
                            val absNormNormalized = absNormPath.replace('\\', '/').replace('/', File.separatorChar).replace('\\', '/')
                            val absNormSlash = absNormPath.replace('\\', '/')
                            val absNormSafe = if (isWindows) {
                                absNormSlash.equals(baseNorm, ignoreCase = true) ||
                                    absNormSlash.startsWith("$baseNorm/", ignoreCase = true)
                            } else {
                                absNormSlash == baseNorm || absNormSlash.startsWith("$baseNorm/")
                            }
                            if (absNormSafe) absNormPath else canonicalSearch
                        } else {
                            canonicalSearch
                        }
                        isProjectScope = false
                        directoryName = directoryForVfs
                        isWithSubdirectories = true
                    }
                } else {
                    // Canonicalization failed — default to project scope for safety.
                    // Using the raw searchPath would bypass the path traversal check.
                    logger.warn { "[ACP] Follow Agent: canonicalization failed for searchPath=$searchPath, defaulting to project scope" }
                    isProjectScope = true
                }
            } else {
                // No project base path (rare — default/Light project). Default to project
                // scope for safety — using the raw searchPath would bypass the path
                // traversal check since there's no base to compare against.
                logger.warn { "[ACP] Follow Agent: project.basePath is null, defaulting to project scope for searchPath=$searchPath" }
                isProjectScope = true
            }
        } else {
            isProjectScope = true
        }

        // File filter: e.g. "*.kt", "*.{kt,java}" — passed through as-is.
        if (!includeGlob.isNullOrBlank()) {
            val cappedGlob = if (includeGlob.length > 256) includeGlob.take(256) else includeGlob
            // Reject globs that could escape the search directory via '..' path segments.
            // fileFilter is scoped to the search directory, but '..' could cause
            // unexpected scope expansion on some FindInProjectManager implementations.
            // Match '..' only as a path segment (not any two consecutive dots, which
            // would reject legitimate patterns like `file..bak` or `*.[a..z]`).
            val traversalSegmentRegex = Regex("""(^|[/\\])\.\.([/\\]|$)""")
            if (traversalSegmentRegex.containsMatchIn(cappedGlob)) {
                logger.warn { "[ACP] Follow Agent: rejecting includeGlob with '..' traversal segment: $cappedGlob" }
            } else {
                fileFilter = cappedGlob
            }
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
        if (!OpenCodeFollowSettingsState.getInstance().followAgentEnabled) return
        if (project.isDisposed) return
        if (pattern.isBlank()) return

        // ── Regex safety check ─────────────────────────────────────────
        // Reject patterns likely to cause catastrophic backtracking (ReDoS).
        // The pattern still originates from untrusted SSE tool input.
        if (isRegex && !isRegexSafe(pattern)) {
            logger.warn { "[ACP] Follow Agent: rejecting potentially unsafe regex pattern on reopen (ReDoS risk): '$pattern'" }
            return
        }

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
     *
     * WARNING: This is a BEST-EFFORT heuristic, NOT a complete ReDoS defense. It may miss
     * some backtracking vectors. FindInProjectManager does not impose a search timeout by
     * default. For complete protection, consider adding a max-result-count or time-limit
     * guard on the Find in Files call.
     *
     * Rejects:
     * - Patterns longer than 100 characters (legitimate search patterns are rarely longer;
     *   the lower cap reduces the surface area for adversarial patterns)
     * - Patterns with nested parentheses depth > 2 (walks the pattern char-by-char
     *   tracking paren depth — classic ReDoS vectors like `(a(b+))+` or `((a+)(b*))+`
     *   are rejected)
     * - Patterns with nested quantifiers (e.g., `(a+)+`, `(a*)*`, `(a{1,3})+`,
     *   `(a{2,})+`). A quantifier is `+`, `*`, `?`, or `{n}`, `{n,}`, `{n,m}`.
     *
     * This is a best-effort heuristic — it may reject some safe patterns and allow
     * some unsafe ones. The goal is to block the most common ReDoS vectors without
     * requiring a full regex complexity analyzer.
     */
    private fun isRegexSafe(pattern: String): Boolean {
        if (pattern.length > 100) return false
        // Reject patterns with nested parentheses deeper than 2.
        // Walks the pattern char-by-char tracking paren depth; if depth exceeds 2
        // at any point, the pattern is rejected. This catches classic nested-group
        // ReDoS vectors like `(a(b+))+` that the regex-based nested-quantifier
        // check below cannot detect (its `[^)]*` cannot match nested parens).
        if (!isParenNestingSafe(pattern, maxDepth = 2)) return false
        // Detect nested quantifiers: a quantifier (+, *, ?, {n}, {n,}, {n,m})
        // immediately following a group that contains a quantifier. This is the
        // classic ReDoS pattern: (a+)+, (a*)*, (a?)+, (a{1,3})+, (a{2,})+, etc.
        val quantifier = """[+*?]|\{\d+(,\d*)?\}"""
        val nestedQuantifierRegex = Regex("""\([^)]*(?:$quantifier)[^)]*\)(?:$quantifier)""")
        if (nestedQuantifierRegex.containsMatchIn(pattern)) return false
        return true
    }

    /**
     * Walks [pattern] char-by-char tracking parenthesis nesting depth.
     * Returns false if depth ever exceeds [maxDepth] (i.e., goes to maxDepth + 1).
     * Also returns false if parentheses are unbalanced.
     */
    private fun isParenNestingSafe(pattern: String, maxDepth: Int): Boolean {
        var depth = 0
        var maxSeen = 0
        var escaped = false
        for (c in pattern) {
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '(' -> {
                    depth++
                    if (depth > maxSeen) maxSeen = depth
                    if (depth > maxDepth) return false
                }
                ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    // ── Disposable ────────────────────────────────────────────────────

    override fun dispose() {
        // Intentionally empty — the Find tool window manages its own lifecycle.
        // No coroutine scopes, timers, or background threads to cancel.
    }
}
