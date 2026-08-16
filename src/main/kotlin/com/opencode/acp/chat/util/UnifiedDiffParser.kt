package com.opencode.acp.chat.util

import com.opencode.acp.chat.model.FileChangeStatus

/**
 * A single file entry parsed from a `git diff --cached --no-renames` unified diff.
 *
 * @property filePath  Relative path (without the `a/` or `b/` prefix).
 * @property status    ADDED, MODIFIED, or DELETED.
 * @property additions Count of `+` lines across all hunks.
 * @property deletions Count of `-` lines across all hunks.
 */
data class DiffEntry(
    val filePath: String,
    val status: FileChangeStatus,
    val additions: Int,
    val deletions: Int,
)

/**
 * Pure-logic parser for `git diff --cached --no-renames` unified diff output.
 * No IntelliJ platform or git4idea dependencies.
 *
 * Parsing is a line-by-line state machine that tracks the current file path,
 * status, and running addition/deletion counts, resetting per file.
 *
 * @see DiffEntry
 */
object UnifiedDiffParser {

    /** Prefix for a new file boundary line. */
    private const val DIFF_HEADER_PREFIX = "diff --git "
    private const val NEW_FILE_MODE = "new file mode"
    private const val DELETED_FILE_MODE = "deleted file mode"
    private const val BINARY_FILES_PREFIX = "Binary files "
    private const val RENAME_FROM_PREFIX = "rename from "
    private const val RENAME_TO_PREFIX = "rename to "
    private const val HUNK_HEADER_PREFIX = "@@"
    private const val FROM_FILE_PREFIX = "--- "
    private const val TO_FILE_PREFIX = "+++ "
    private const val DEV_NULL = "/dev/null"

    /**
     * Parse the full diff output into a list of [DiffEntry] instances.
     *
     * Returns an empty list for blank/empty input. Handles binary files
     * (status MODIFIED, zero additions/deletions) and gracefully handles
     * rename entries that may appear if `--no-renames` is not used —
     * rename entries are kept under the destination (b/) path with their
     * content changes, so no file with real diffs is silently dropped.
     */
    fun parse(diffOutput: String): List<DiffEntry> {
        val lines = diffOutput.lines()
        if (diffOutput.isBlank() || lines.isEmpty()) return emptyList()

        val entries = mutableListOf<DiffEntry>()
        var currentPath: String? = null
        var currentStatus: FileChangeStatus = FileChangeStatus.MODIFIED
        var additions = 0
        var deletions = 0
        var inHunk = false
        var pendingPathFromHeader: String? = null
        var oldPathFromDash: String? = null
        var isRename = false
        var hasContentChanges = false

        fun flushEntry() {
            val path = currentPath ?: pendingPathFromHeader
            if (path != null) {
                // Keep the entry if it has content changes (additions/deletions > 0)
                // OR if it's a non-rename entry. For rename entries with no content
                // changes, we skip them (they're pure renames with no diff to review).
                // For rename entries WITH content changes, we keep the entry under
                // the destination path — the rename metadata is ignored for review
                // purposes; what matters is the content changes.
                if (hasContentChanges || !isRename) {
                    // For deleted files where the path was set from --- /dev/null,
                    // currentPath may still be the a/ path. For added files,
                    // currentPath is the b/ path. We resolve the final path below.
                    val finalPath = resolvePath(path, oldPathFromDash, currentStatus)
                    entries.add(
                        DiffEntry(
                            filePath = finalPath,
                            status = currentStatus,
                            additions = additions,
                            deletions = deletions,
                        )
                    )
                }
            }
            currentPath = null
            currentStatus = FileChangeStatus.MODIFIED
            additions = 0
            deletions = 0
            inHunk = false
            pendingPathFromHeader = null
            oldPathFromDash = null
            isRename = false
            hasContentChanges = false
        }

        for (line in lines) {
            when {
                line.startsWith(DIFF_HEADER_PREFIX) -> {
                    // Flush the previous file entry (if any)
                    flushEntry()
                    // Extract the "after" path from the b/ side
                    pendingPathFromHeader = extractPathFromDiffHeader(line)
                    // Reset status — will be set by mode lines or remain MODIFIED
                }

                line.startsWith(NEW_FILE_MODE) -> {
                    currentStatus = FileChangeStatus.ADDED
                }

                line.startsWith(DELETED_FILE_MODE) -> {
                    currentStatus = FileChangeStatus.DELETED
                }

                line.startsWith(RENAME_FROM_PREFIX) || line.startsWith(RENAME_TO_PREFIX) -> {
                    // Rename entry — mark so we can skip it IF it has no content changes.
                    // If it has hunks with +/- lines, hasContentChanges will be set and
                    // the entry will be kept under the destination path.
                    isRename = true
                }

                line.startsWith(BINARY_FILES_PREFIX) -> {
                    // Binary file diff — no hunks, zero counts, MODIFIED.
                    // Binary files have no +++ /dev/null or @@ lines, so the path
                    // must come from the diff --git header (pendingPathFromHeader).
                    // Unconditional assignment (not `if (currentPath == null)`)
                    // guards against malformed input where a stale `currentPath`
                    // was set by an earlier line — the binary header is the
                    // authoritative path source for binary diffs.
                    currentPath = pendingPathFromHeader ?: currentPath
                }

                !inHunk && line.startsWith(FROM_FILE_PREFIX) -> {
                    val afterPrefix = line.substring(FROM_FILE_PREFIX.length).trim()
                    if (afterPrefix == DEV_NULL) {
                        // --- /dev/null means a new file (ADDED)
                        if (currentStatus != FileChangeStatus.ADDED) {
                            currentStatus = FileChangeStatus.ADDED
                        }
                    } else {
                        // --- a/path → extract the old path (used for deleted files)
                        oldPathFromDash = stripPathPrefix(afterPrefix)
                    }
                }

                !inHunk && line.startsWith(TO_FILE_PREFIX) -> {
                    val afterPrefix = line.substring(TO_FILE_PREFIX.length).trim()
                    if (afterPrefix == DEV_NULL) {
                        // +++ /dev/null means a deleted file
                        currentStatus = FileChangeStatus.DELETED
                        // The path should come from the --- a/ line (oldPathFromDash)
                        // or from the diff --git header
                    } else {
                        // +++ b/path → prefer this path (the "after" path)
                        val toPath = stripPathPrefix(afterPrefix)
                        currentPath = toPath
                    }
                }

                line.startsWith(HUNK_HEADER_PREFIX) -> {
                    inHunk = true
                    // If currentPath wasn't set from +++ line (e.g., edge cases),
                    // use the path from the diff --git header
                    if (currentPath == null) {
                        currentPath = pendingPathFromHeader
                    }
                }

                inHunk -> {
                    // Count additions and deletions within hunk body.
                    //
                    // Header lines `+++ b/file` / `--- a/file` are NOT a concern here:
                    // they are matched earlier in this `when` block by TO_FILE_PREFIX
                    // ("+++ ") / FROM_FILE_PREFIX ("--- "), which both require a trailing
                    // space. A hunk body line like `+++i` (an added line whose content is
                    // `++i`, e.g. a C++ increment) does NOT match TO_FILE_PREFIX (no
                    // trailing space), so it reaches this branch and must be counted.
                    //
                    // We therefore must NOT exclude lines starting with "++"/"--" — that
                    // would silently skip legitimate added/deleted lines whose content
                    // begins with "++" or "--" (C++ --iter, Lua --comment, etc.).
                    when {
                        line.startsWith("+") -> {
                            additions++
                            hasContentChanges = true
                        }

                        line.startsWith("-") -> {
                            deletions++
                            hasContentChanges = true
                        }

                        line.startsWith("\\") -> {
                            // "\\ No newline at end of file" — ignore
                        }
                    }
                }

                else -> {
                    // Lines outside hunks (index lines, blank lines, etc.) — skip
                }
            }
        }

        // Flush the last entry
        flushEntry()

        return entries
    }

    /**
     * Extract the "after" path from a `diff --git a/<path> b/<path>` line.
     * Uses the b/ side. Handles paths with spaces by splitting on ` b/`.
     *
     * Handles quoted paths (git quotes paths containing special characters):
     * `diff --git "a/foo b/bar.kt" "b/foo b/bar.kt"` → unquotes and extracts
     * the b/ side.
     */
    private fun extractPathFromDiffHeader(line: String): String? {
        // Line format: "diff --git a/some path b/some path"
        // Or quoted:  "diff --git \"a/some path\" \"b/some path\""
        val rest = line.substring(DIFF_HEADER_PREFIX.length)

        // Handle quoted paths: if both paths are quoted, extract the second quoted segment
        if (rest.startsWith("\"")) {
            // Find the closing quote of the a/ path, then the opening quote of the b/ path
            val firstClose = rest.indexOf("\"", 1)
            if (firstClose < 0) return null
            val afterFirst = rest.substring(firstClose + 1).trim()
            if (afterFirst.startsWith("\"")) {
                val bPart = afterFirst.substring(1) // skip opening quote
                val closeIdx = bPart.indexOf("\"")
                val path = if (closeIdx >= 0) bPart.substring(0, closeIdx) else bPart
                return stripPathPrefix(path.trim()).ifEmpty { null }
            }
            return null
        }

        // Unquoted: find the LAST " b/" — this is the separator between the a/ path and b/ path.
        // We use lastIndexOf because the a/ path could contain " b/" as part of a directory
        // name (e.g., "a/src/b/file.kt b/src/b/file.kt"), but the real separator is the
        // last one since git places " b/" between the two complete paths.
        // Note: for paths containing " b/" as part of the file name itself (e.g., "foo b/bar.kt"),
        // the +++ b/ and --- a/ lines should be the primary path source — this header
        // extraction is a fallback for binary files.
        val bIndex = rest.lastIndexOf(" b/")
        if (bIndex < 0) return null
        val bPart = rest.substring(bIndex + 3) // skip " b/"
        return bPart.trim().ifEmpty { null }
    }

    /**
     * Strip the `a/`, `b/`, or `c/` prefix from a path. If the path doesn't have the
     * prefix, return it as-is. The `c/` prefix appears in combined diffs (merge
     * commits), though `git diff --cached --no-renames` normally uses only `a/` and `b/`.
     *
     * Also strips surrounding double-quotes (git quotes paths containing special
     * characters when `core.quotePath=true`, the default) and unescapes git's
     * C-string octal escapes (e.g. `\303\274` → `ü`) and common escape sequences
     * (`\"`, `\\`, `\t`, `\n`). Without this, quoted paths produce a `filePath`
     * with embedded quote/escape characters that fails to match downstream
     * (LocalFileSystem lookup, openCommentsByFile map, openDiffForPath comparison).
     */
    private fun stripPathPrefix(path: String): String {
        // Strip surrounding double-quotes first (git quotes the whole path when
        // it contains special characters: `"b/src/my file.kt"`).
        val unquoted = if (path.length >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path.substring(1, path.length - 1)
        } else {
            path
        }
        val withoutPrefix = when {
            unquoted.startsWith("a/") -> unquoted.substring(2)
            unquoted.startsWith("b/") -> unquoted.substring(2)
            unquoted.startsWith("c/") -> unquoted.substring(2)
            else -> unquoted
        }
        return unescapeGitPath(withoutPrefix)
    }

    /**
     * Unescape git's C-string path escaping. Git escapes bytes >0x7F and special
     * characters as octal sequences (e.g. `\303\274` → `ü`) and control characters
     * as `\"`, `\\`, `\t`, `\n`, `\r`. Without unescaping, paths with non-ASCII or
     * special chars produce a `filePath` that doesn't match the real file on disk.
     *
     * Octal escapes represent raw BYTES (git escapes at the byte level, not the
     * character level). A UTF-8 character like `ü` (U+00FC) is two bytes (0xC3 0xBC)
     * and git emits `\303\274` — two separate octal escapes. This function
     * accumulates consecutive octal-escaped bytes into a buffer and decodes them
     * as UTF-8 so the result matches the filesystem's string representation.
     *
     * See https://git-scm.com/docs/git-config#Documentation/git-config.txt-corequotePath
     */
    private fun unescapeGitPath(path: String): String {
        if ('\\' !in path) return path
        val sb = StringBuilder(path.length)
        val byteAccumulator = java.io.ByteArrayOutputStream()
        var i = 0

        fun flushBytes() {
            if (byteAccumulator.size() > 0) {
                sb.append(String(byteAccumulator.toByteArray(), Charsets.UTF_8))
                byteAccumulator.reset()
            }
        }

        while (i < path.length) {
            val c = path[i]
            if (c == '\\' && i + 1 < path.length) {
                when (path[i + 1]) {
                    '"' -> { flushBytes(); sb.append('"'); i += 2 }
                    '\\' -> { flushBytes(); sb.append('\\'); i += 2 }
                    't' -> { flushBytes(); sb.append('\t'); i += 2 }
                    'n' -> { flushBytes(); sb.append('\n'); i += 2 }
                    'r' -> { flushBytes(); sb.append('\r'); i += 2 }
                    else -> {
                        // Octal escape: \NNN (1-3 octal digits) → single byte.
                        // Accumulate consecutive octal escapes into a byte buffer
                        // and decode as UTF-8 so multi-byte chars reconstruct correctly.
                        if (path[i + 1] in '0'..'7') {
                            val octStart = i + 1
                            var octEnd = octStart
                            while (octEnd < path.length && octEnd < octStart + 3 && path[octEnd] in '0'..'7') {
                                octEnd++
                            }
                            val octVal = path.substring(octStart, octEnd).toInt(8)
                            byteAccumulator.write(octVal)
                            i = octEnd
                        } else {
                            // Unknown escape — flush accumulated bytes, keep backslash + char literally
                            flushBytes()
                            sb.append(c)
                            i++
                        }
                    }
                }
            } else {
                flushBytes()
                sb.append(c)
                i++
            }
        }
        flushBytes()
        return sb.toString()
    }

    /**
     * Resolve the final file path. For deleted files, prefer the `--- a/` path
     * (oldPathFromDash). For all other cases, use the currentPath (from +++ b/
     * or the diff --git header).
     */
    private fun resolvePath(
        currentPath: String,
        oldPathFromDash: String?,
        status: FileChangeStatus,
    ): String {
        return if (status == FileChangeStatus.DELETED && oldPathFromDash != null) {
            oldPathFromDash
        } else {
            currentPath
        }
    }
}