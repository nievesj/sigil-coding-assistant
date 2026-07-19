package com.opencode.acp.chat.markdown

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Detects file references with optional line numbers in chat text and rewrites
 * them into markdown links using a custom `opencode-file://` scheme. The click
 * handler in MessageList.kt resolves these links and opens the file in the
 * IntelliJ editor at the referenced line.
 *
 * Supported formats (mirroring JetBrains' AcpPathLinkResolver):
 *   - path/to/file.ext:42
 *   - path/to/file.ext:42:10   (line:column)
 *   - path/to/file.ext#L42
 *   - ./relative/file.ext:42
 *   - C:\path\to\file.ext:42   (Windows absolute)
 *   - /absolute/path/file.ext:42
 *   - file.ext:42              (bare filename — resolved via project index)
 *   - file.ext                 (no line — opens at top)
 *
 * The linker is conservative: it only linkifies paths that contain a file
 * extension (a dot followed by 1-10 alphanumeric chars). This avoids false
 * positives like "12:30" (time), "1.2:3" (version), "host:8080" (no extension).
 * URLs (http://, https://, etc.) are excluded by checking for "://" before the
 * match.
 *
 * The linker does NOT touch text inside existing markdown links, code spans
 * (backtick-wrapped), or fenced code blocks — those are handled separately by
 * the MarkdownSegmenter (code blocks) and should be left as-is. The linker
 * operates on the raw markdown string BEFORE it is passed to Jewel's Markdown
 * composable, so it must not break existing markdown syntax.
 *
 * This object is stateless and safe to call from any thread.
 */
object FileReferenceLinker {

    /**
     * Matches a file path (with extension) followed by an optional line number
     * in `:line`, `:line:col`, or `#Lline` format.
     *
     * Group 1: file path (must contain a dot + extension)
     * Group 2: line number (colon format), or null
     * Group 3: column number (colon format), or null
     * Group 4: line number (hash format), or null
     */
    // NOTE: The regex captures at most line:column (two colon groups). Extra :N segments
    // (e.g., "file.kt:42:10:20") are left as literal text after the match — only the first
    // two colon-separated numbers are captured as line and column.
    private val FILE_REF_REGEX = Regex(
        """([\w./\\-]+\.[A-Za-z0-9]{1,10})(?::(\d+)(?::(\d+))?|#L(\d+))?"""
    )

    /** URL schemes to exclude from linkification. */
    private val URL_SCHEME_REGEX = Regex("""[A-Za-z][A-Za-z0-9+.-]*://""")

    /** Markdown link/inline-code protection: skip text inside [](...), `` ` ``, and ``` fences.
     *  NOTE: Fenced-block alternatives (```) MUST come before the inline-code alternative (`),
     *  otherwise the inline-code pattern matches the first two of three backticks, leaving the
     *  third backtick and the fenced content unprotected. */
    private val PROTECTED_REGION_REGEX = Regex(
        """(\[[^\]]*\]\([^)]*\)|```[\s\S]*?```|```[\s\S]*$|`[^`]*`)"""
    )

    /**
     * Rewrite file references in [text] as `opencode-file://` markdown links.
     * Returns the text unchanged if no file references are found.
     *
     * The link URL format is:
     *   opencode-file://<path>?line=<line>&column=<column>
     *
     * Where <path> is the raw path string (URL-encoded), and line/column are
     * optional query parameters (omitted if not present).
     */
    fun linkify(text: String): String {
        if (text.isBlank()) return text

        // Defense-in-depth against catastrophic backtracking (ReDoS) on very large
        // inputs. The FILE_REF_REGEX is bounded (extension length capped at 10,
        // line/col are digit-only), but capping the total input length avoids any
        // pathological case on abnormally large payloads. 100K chars is far beyond
        // any realistic chat message.
        if (text.length > 100_000) return text

        // Split into protected and unprotected regions so we don't linkify
        // inside existing markdown links, inline code, or fenced code blocks.
        val protectedRegions = mutableListOf<IntRange>()
        for (match in PROTECTED_REGION_REGEX.findAll(text)) {
            protectedRegions.add(match.range)
        }

        // O(n*m) where n=protected regions, m=file-ref matches. Acceptable for
        // typical chat messages (<10KB).
        fun isProtected(start: Int, end: Int): Boolean =
            protectedRegions.any { start in it || end - 1 in it || (start <= it.first && end > it.last) }

        val sb = StringBuilder(text.length + 64)
        var lastEnd = 0
        var anyRewritten = false

        for (match in FILE_REF_REGEX.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1

            // Skip if inside a protected region
            if (isProtected(start, end)) continue

            // Skip URLs: scan back to the previous whitespace (or start of text)
            // and check for a URL scheme ("://"). A fixed 10-char lookback is
            // insufficient for long URLs like "https://example.com/page.html:42"
            // where the "://" is more than 10 chars before the file-path match.
            // We check the full word (from previous whitespace to end of match)
            // because the regex may include the "//" from "://" in the match
            // itself (since "/" is a valid path character), leaving the lookback
            // with only "https:" (no "//"), which would miss the scheme.
            // Limit lookback to 200 chars to prevent O(n^2) on long lines with no whitespace.
            // 200 chars is sufficient to cover URL schemes like "https://very-long-domain.example.com/page.html:42"
            // where the "://" is more than 10 chars before the file-path match but well within 200.
            val lookbackLimit = (start - 200).coerceAtLeast(0)
            val lookbackStart = (lookbackLimit until start).reversed().firstOrNull { text[it].isWhitespace() }
                ?.plus(1) ?: lookbackLimit
            val fullWord = text.substring(lookbackStart, end)
            if (URL_SCHEME_REGEX.containsMatchIn(fullWord)) continue

            // Skip if the match is part of an existing markdown link URL
            // (heuristic: preceded by "](" — already covered by PROTECTED_REGION_REGEX)

            val filePath = match.groupValues[1]
            val lineColon = match.groupValues[2].toIntOrNull()
            val colColon = match.groupValues[3].toIntOrNull()
            val lineHash = match.groupValues[4].toIntOrNull()
            val line = lineColon ?: lineHash
            val column = colColon

            // Skip if filePath looks like a version number or time (no path separator
            // AND no letter in the path before the extension). This filters "1.2:3"
            // and "12:30" but keeps "file.kt:42" and "src/file.kt:42".
            if (!hasPathSeparator(filePath) && !hasLetterBeforeExtension(filePath)) {
                continue
            }

            // Skip path traversal sequences — defense-in-depth. The click handler
            // (openFileReference in MessageList.kt) validates the resolved path is
            // within the project boundary, but we should not render traversal paths
            // as clickable links at all (social-engineering vector for prompt injection).
            if (filePath.contains("..")) {
                continue
            }

            // Append text before this match
            sb.append(text.substring(lastEnd, start))

            // Build the opencode-file:// URL
            val url = buildFileUrl(filePath, line, column)

            // Append as a markdown link: [path:line](opencode-file://...)
            sb.append('[')
            sb.append(match.value)
            sb.append("](")
            sb.append(url)
            sb.append(')')

            lastEnd = end
            anyRewritten = true
        }

        if (!anyRewritten) return text

        sb.append(text.substring(lastEnd))
        return sb.toString()
    }

    private fun hasPathSeparator(path: String): Boolean =
        path.contains('/') || path.contains('\\')

    private fun hasLetterBeforeExtension(path: String): Boolean {
        val dotIndex = path.indexOf('.')
        if (dotIndex <= 0) return false
        val before = path.substring(0, dotIndex)
        return before.any { it.isLetter() }
    }

    /**
     * Build an `opencode-file://` URL from a file path and optional line/column.
     * The path is URL-encoded so special characters don't break the markdown link.
     */
    private fun buildFileUrl(filePath: String, line: Int?, column: Int?): String {
        val encoded = java.net.URLEncoder.encode(filePath, Charsets.UTF_8)
        val params = mutableListOf<String>()
        if (line != null) params.add("line=$line")
        if (column != null) params.add("column=$column")
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return "opencode-file://$encoded$query"
    }

    /**
     * Parse an `opencode-file://` URL back into a file path and optional line/column.
     * Returns null if [url] is not a valid opencode-file:// URL.
     */
    fun parseFileUrl(url: String): FileRef? {
        if (!url.startsWith("opencode-file://")) return null
        val rest = url.removePrefix("opencode-file://")
        val queryStart = rest.indexOf('?')
        val pathEncoded = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
        val path = try {
            java.net.URLDecoder.decode(pathEncoded, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (path.isBlank()) return null
        var line: Int? = null
        var column: Int? = null
        for (param in query.split('&')) {
            val eq = param.indexOf('=')
            if (eq < 0) continue
            val key = param.substring(0, eq)
            val value = param.substring(eq + 1).toIntOrNull() ?: continue
            when (key) {
                "line" -> line = value
                "column" -> column = value
            }
        }
        return FileRef(path, line, column)
    }

    /** A parsed file reference: path + optional line + optional column. */
    data class FileRef(val path: String, val line: Int?, val column: Int?)
}