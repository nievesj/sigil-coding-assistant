package com.opencode.acp.follow

/**
 * Pure-logic helpers for the FilenameIndex fallback in [EditorFollowManager].
 * Extracted so the filename-extraction and match-selection logic is unit-testable
 * without the IntelliJ Platform (which requires a real application context —
 * see AGENTS.md "Compose UI Tests" section for the same limitation pattern).
 *
 * These functions have NO IntelliJ dependencies (no `VirtualFile`, no `Project`,
 * no `FilenameIndex`). The IntelliJ-dependent parts of the fallback resolver
 * (the `FilenameIndex` lookup, `VirtualFile` traversal guard, read-action
 * wrapping) stay in [EditorFollowManager.resolveViaFilenameIndex].
 */
internal object FileRefMatching {

    /**
     * Regex matching a trailing line/column suffix: `:line`, `:line:col`, or
     * `#Lline`. Anchored to end of string so it only strips a suffix, never an
     * interior colon (e.g., the `C:` in a Windows drive letter). The line/col
     * values are digits-only.
     */
    private val LINE_SUFFIX_REGEX = Regex("""(?::(\d+)(?::(\d+))?|#L(\d+)(?::(\d+))?)$""")

    /**
     * Extract the filename (last path segment) from a file reference string,
     * stripping any `:line` or `:line:col` suffix. Handles both `/` and `\`
     * separators. Returns null if no filename can be extracted.
     *
     * Strips trailing `:line`, `:line:col`, and `#Lline` suffixes via a regex
     * anchored to end of string. This avoids the Windows-drive-letter pitfall
     * that a naive `substringBefore(':')` would hit (`C:\src\Foo.kt:42` would
     * cut at the `C:` colon and return just `"C"`).
     */
    fun extractFileName(filePath: String): String? {
        val cleaned = LINE_SUFFIX_REGEX.replace(filePath, "").replace('\\', '/')
        val fileName = cleaned.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return null
        // Reject filenames containing a colon — no real file has a colon in its name
        // (after line-suffix stripping). A colon here means the input was not a clean
        // file reference (e.g. "Foo.kt:abc" where :abc is not a numeric line suffix).
        if (fileName.contains(':')) return null
        return fileName
    }

    /**
     * Extract the relative path (without line suffix) for suffix matching.
     * Backslashes are normalized to forward slashes and a trailing slash is
     * trimmed. Returns null if empty after cleaning.
     */
    fun extractRelativePath(filePath: String): String? {
        val cleaned = LINE_SUFFIX_REGEX.replace(filePath, "").replace('\\', '/').trimEnd('/')
        return cleaned.ifEmpty { null }
    }

    /**
     * Select the best match from a list of candidate paths. Prefers a
     * case-insensitive suffix match (path ends with "/<relPath>" or equals
     * relPath); falls back to the shortest path (closest to project root).
     *
     * Backslashes in candidate paths are normalized to forward slashes for the
     * comparison only — the original candidate string is returned unchanged.
     *
     * @param candidatePaths list of absolute file paths (already filtered to
     *   valid, non-directory files inside the project basePath by the caller)
     * @param relPath the cleaned relative path from the user's file reference
     *   (e.g., "src/Foo.kt" or "Foo.kt")
     * @return the selected path (one of the input strings, unchanged), or null
     *   if [candidatePaths] is empty
     */
    fun selectBestMatch(candidatePaths: List<String>, relPath: String): String? {
        if (candidatePaths.isEmpty()) return null
        val relLower = relPath.replace('\\', '/').lowercase()
        val suffixMatch = candidatePaths.firstOrNull { path ->
            val pathLower = path.replace('\\', '/').lowercase()
            pathLower.endsWith("/$relLower") || pathLower == relLower
        }
        if (suffixMatch != null) return suffixMatch
        // Normalize backslashes to forward slashes before comparing lengths so
        // mixed-separator candidates are compared fairly. Raw length is still a
        // rough heuristic (doesn't account for redundant segments like ../), but
        // the caller pre-filters candidates to those inside the project basePath
        // via the CWE-22 traversal guard, so cross-project candidates should not
        // reach this function.
        return candidatePaths.minByOrNull { it.replace('\\', '/').length }
    }
}