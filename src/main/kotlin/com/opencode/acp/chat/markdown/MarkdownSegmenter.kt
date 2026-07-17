package com.opencode.acp.chat.markdown

/**
 * Split markdown content into segments: text (rendered by Jewel Markdown),
 * fenced code blocks (rendered by ChatFencedCodeBlock), and tables
 * (rendered by ChatTable).
 *
 * Uses a line-by-line state machine that finds ``` fences anywhere on a line,
 * handling LLM quirks like ``` appearing after trailing text (e.g., "text```css").
 */
data class MarkdownSegment(
    val type: Type,
    val content: String,
    val language: String? = null,
    val taskAttrs: Map<String, String>? = null,
) {
    enum class Type { TEXT, CODE, TABLE, TASK }
}

/**
 * Parsed table structure for rendering.
 */
data class ParsedTable(
    val header: List<String>,
    val alignments: List<ColumnAlignment>,
    val rows: List<List<String>>,
) {
    enum class ColumnAlignment { LEFT, CENTER, RIGHT }
}

object MarkdownSegmenter {

    private val WHITESPACE_REGEX = Regex("\\s")

    /**
     * Find the first occurrence of 3+ fence chars (backticks ``` or tildes ~~~)
     * on a line. Returns the index where the fence starts, or -1 if not found.
     * Ignores single/double backticks (inline code).
     */
    private fun findFenceStart(line: String): Int {
        var i = 0
        while (i <= line.length - 3) {
            val c = line[i]
            if ((c == '`' || c == '~') && line[i + 1] == c && line[i + 2] == c) {
                // Found 3+ matching fence chars — check it's not preceded by the same char (would be 4+)
                if (i > 0 && line[i - 1] == c) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    /**
     * Count consecutive fence chars starting at position.
     */
    private fun countFenceChars(line: String, start: Int): Int {
        val fenceChar = line[start]
        var count = 0
        var i = start
        while (i < line.length && line[i] == fenceChar) {
            count++
            i++
        }
        return count
    }

    /** Check if a line looks like a markdown table row (starts with | after trimming). */
    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.length > 1
    }

    /** Check if a line is a table separator row (e.g., |---|---|). */
    private fun isSeparatorRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|")) return false
        // A separator row has only |, -, :, and spaces
        return trimmed.all { it in "|-: \t" } && trimmed.contains("---")
    }

    /** Parse a table row into cells, trimming whitespace. */
    private fun parseRow(line: String): List<String> {
        val trimmed = line.trim()
        // Remove leading and trailing |
        val inner = trimmed.removePrefix("|").removeSuffix("|")
        return inner.split("|").map { it.trim() }
    }

    /** Infer column alignment from the separator row. */
    private fun parseAlignments(separatorLine: String): List<ParsedTable.ColumnAlignment> {
        val cells = parseRow(separatorLine)
        return cells.map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> ParsedTable.ColumnAlignment.CENTER
                right -> ParsedTable.ColumnAlignment.RIGHT
                else -> ParsedTable.ColumnAlignment.LEFT
            }
        }
    }

    /**
     * Parse a table block from consecutive table lines.
     * Returns a ParsedTable or null if the lines don't form a valid table.
     */
    fun parseTable(lines: List<String>): ParsedTable? {
        if (lines.size < 2) return null

        val header = parseRow(lines[0])
        if (header.isEmpty()) return null

        // Find separator row (usually line 1, but could be later)
        val separatorIdx = lines.indexOfFirst { isSeparatorRow(it) }
        if (separatorIdx < 0 || separatorIdx >= lines.size - 1) return null

        val alignments = parseAlignments(lines[separatorIdx])
        val dataRows = lines.drop(separatorIdx + 1).filter { it.trim().isNotBlank() }

        // Pad alignments if needed
        val paddedAlignments = alignments + List(maxOf(0, header.size - alignments.size)) {
            ParsedTable.ColumnAlignment.LEFT
        }

        val rows = dataRows.map { parseRow(it) }
        return ParsedTable(
            header = header,
            alignments = paddedAlignments,
            rows = rows,
        )
    }

    fun segment(markdown: String): List<MarkdownSegment> {
        val lines = markdown.lines()
        val segments = mutableListOf<MarkdownSegment>()
        val textBuilder = StringBuilder()
        var inCodeBlock = false
        var codeLanguage: String? = null
        val codeBuilder = StringBuilder()
        var fenceLength = 3
        var fenceChar = '`'

        fun flushText() {
            val text = textBuilder.toString().trim('\n', '\r')
            if (text.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, text))
            }
            textBuilder.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (!inCodeBlock) {
                // Check for table block
                if (isTableRow(line)) {
                    // Collect consecutive table lines
                    val tableLines = mutableListOf<String>()
                    while (i < lines.size && isTableRow(lines[i])) {
                        tableLines.add(lines[i])
                        i++
                    }

                    // Try to parse as a table
                    val table = parseTable(tableLines)
                    if (table != null) {
                        // Flush any text before the table
                        flushText()
                        // Emit the raw table markdown as a TABLE segment
                        segments.add(
                            MarkdownSegment(
                                MarkdownSegment.Type.TABLE,
                                tableLines.joinToString("\n"),
                            )
                        )
                        continue // i already advanced past table lines
                    } else {
                        // Not a valid table — add lines back as text
                        tableLines.forEach { textBuilder.appendLine(it) }
                        continue
                    }
                }

                // Search for ``` or ~~~ anywhere on this line
                val fenceIdx = findFenceStart(line)
                if (fenceIdx >= 0) {
                    // Found an opening fence
                    val fenceLen = countFenceChars(line, fenceIdx)
                    val afterFence = line.substring(fenceIdx + fenceLen).trimStart()
                    // Language is the first non-whitespace token after the fence
                    val lang = afterFence.split(WHITESPACE_REGEX).first().ifBlank { null }

                    // Flush text before the fence
                    if (fenceIdx > 0) {
                        val textBefore = line.substring(0, fenceIdx).trimEnd()
                        if (textBefore.isNotBlank()) {
                            textBuilder.appendLine(textBefore)
                        }
                    }

                    inCodeBlock = true
                    codeLanguage = lang
                    fenceLength = fenceLen
                    fenceChar = line[fenceIdx]
                    i++
                    continue
                }

                // No fence or table found — add to text segment
                textBuilder.appendLine(line)
                i++
            } else {
                // Inside code block — look for closing fence
                val trimmedLine = line.trim()
                val fenceIdx = findFenceStart(trimmedLine)

                if (fenceIdx == 0) {
                    // Closing fence at start of (trimmed) line — must match opening fence char
                    val closingFenceLen = countFenceChars(trimmedLine, fenceIdx)
                    if (closingFenceLen >= fenceLength && trimmedLine[fenceIdx] == fenceChar) {
                        // End of code block
                        inCodeBlock = false

                        // Flush text segment
                        flushText()

                        // Flush code segment
                        val code = codeBuilder.toString().trimEnd('\n', '\r')
                        if (code.isNotBlank()) {
                            segments.add(MarkdownSegment(MarkdownSegment.Type.CODE, code, codeLanguage))
                        }
                        codeBuilder.clear()
                        codeLanguage = null

                        // Capture any text after the closing fence on the same line
                        val afterClosing = line.substringAfter(fenceChar.toString().repeat(3), "").trimStart()
                        if (afterClosing.isNotBlank()) {
                            textBuilder.appendLine(afterClosing)
                        }
                        i++
                        continue
                    }
                }

                // Regular code content (or fence that didn't match closing criteria)
                codeBuilder.appendLine(line)
                i++
            }
        }

        // Handle unclosed code block (streaming — closing ``` hasn't arrived yet)
        if (inCodeBlock) {
            val code = codeBuilder.toString().trimEnd('\n', '\r')
            if (code.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.CODE, code, codeLanguage))
            }
            codeBuilder.clear()
        } else {
            flushText()
        }

        if (segments.isEmpty() && markdown.isNotBlank()) {
            segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, markdown))
        }

        // Post-process: split <task>...</task> XML from text segments into TASK segments
        return splitTaskSegments(segments)
    }

    /**
     * Split <task>...</task> XML out of text segments into dedicated TASK segments.
     * The server wraps subagent task results in <task id="..." state="completed">
     * <task_result>...</task_result></task> XML within the text stream.
     */
    private fun splitTaskSegments(segments: List<MarkdownSegment>): List<MarkdownSegment> {
        val taskRegex = Regex(
            "<task\\s+([^>]*)>\\s*<task_result>\\s*([\\s\\S]*?)\\s*</task_result>\\s*</task>",
            RegexOption.MULTILINE
        )
        val result = mutableListOf<MarkdownSegment>()
        for (seg in segments) {
            if (seg.type != MarkdownSegment.Type.TEXT) {
                result.add(seg)
                continue
            }
            val matches = taskRegex.findAll(seg.content).toList()
            if (matches.isEmpty()) {
                result.add(seg)
                continue
            }
            // Split text around task blocks
            var lastEnd = 0
            for (m in matches) {
                // Text before the task
                val before = seg.content.substring(lastEnd, m.range.first).trim()
                if (before.isNotBlank()) {
                    result.add(MarkdownSegment(MarkdownSegment.Type.TEXT, before))
                }
                // Parse attributes from the <task ...> tag
                val attrs = parseAttributes(m.groupValues[1])
                val taskContent = m.groupValues[2].trim()
                if (taskContent.isNotBlank()) {
                    result.add(MarkdownSegment(MarkdownSegment.Type.TASK, taskContent, taskAttrs = attrs))
                }
                lastEnd = m.range.last + 1
            }
            // Text after the last task
            val after = seg.content.substring(lastEnd).trim()
            if (after.isNotBlank()) {
                result.add(MarkdownSegment(MarkdownSegment.Type.TEXT, after))
            }
        }
        return result
    }

    /** Parse key="value" pairs from an XML tag's attribute string. */
    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val attrRegex = Regex("""(\w+)\s*=\s*"([^"]*)"""")
        for (m in attrRegex.findAll(attrString)) {
            result[m.groupValues[1]] = m.groupValues[2]
        }
        return result
    }

    /**
     * Segment markdown after healing incomplete formatting (unclosed backticks,
     * bold, incomplete links). Use this for streaming content to prevent raw
     * syntax from flashing in the UI.
     */
    fun segmentHealed(markdown: String): List<MarkdownSegment> =
        segment(StreamHealer.heal(markdown))
}