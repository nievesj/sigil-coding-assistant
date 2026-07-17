package com.opencode.acp.adapter

/**
 * Path/ID validation helpers extracted from [OpenCodeClient].
 *
 * Centralizes the strict allow-list check used for session/message/permission/
 * question IDs before they are interpolated into URL path segments, plus the
 * directory-path normalization used for the `?directory=` query parameter.
 *
 * Extracting these as a stateless [object] keeps [OpenCodeClient] focused on
 * HTTP wiring and makes the validation rules independently testable.
 *
 * See TDD §4.2.3 — PathValidator.
 */
object PathValidator {
    /** Strict allow-list for URL path segment IDs: `^[A-Za-z0-9_-]{1,128}$`.
     *  Limits length to 128 chars and forbids any character that could break
     *  out of a single path segment (slashes, dots, query separators, etc.). */
    private val PATH_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

    /**
     * Validates that an ID is safe to interpolate into a URL path segment.
     *
     * @param id the ID to validate
     * @param fieldName human-readable field name used in the error message
     *   (e.g. "sessionId", "permissionId") to aid debugging
     * @throws IllegalArgumentException if the ID contains characters outside
     *   the strict allow-list `^[A-Za-z0-9_-]{1,128}$`
     */
    fun validatePathId(id: String, fieldName: String) {
        require(id.matches(PATH_ID_REGEX)) {
            "Invalid $fieldName: '$id' — must match ^[A-Za-z0-9_-]{1,128}$"
        }
    }

    /**
     * Normalize a directory path for the `?directory=` query parameter.
     *
     * **This is separator normalization ONLY, NOT path validation.** It does
     * not check for path traversal, absolute paths, or any other security
     * concerns. The caller ([OpenCodeClient.createSession]/[listSessions])
     * passes the result to Ktor's `parameter()` builder, which handles
     * URL-encoding correctly — so injection via the query parameter is not
     * possible regardless of the path content.
     *
     * On Windows, the OpenCode server expects backslash-separated paths
     * (e.g. `D:\Projects\foo`). Forward slashes cause the server to return
     * an empty session list, so we normalize before passing to Ktor's
     * `parameter()` builder (which handles URL-encoding correctly).
     *
     * On non-Windows platforms the path is returned unchanged.
     *
     * **Known limitation:** Drive letter case is NOT handled. If the project
     * path is `d:\projects\foo` (lowercase `d`) but the server expects
     * `D:\projects\foo` (uppercase `D`), the directory filter may not match.
     * Windows paths are case-insensitive at the filesystem level, so this is
     * unlikely to cause issues in practice, but callers should be aware that
     * drive letter case is preserved as-is from the input.
     */
    fun normalizeDirectoryPath(path: String): String {
        return if (System.getProperty("os.name").lowercase().contains("windows")) {
            path.replace('/', '\\')
        } else path
    }
}