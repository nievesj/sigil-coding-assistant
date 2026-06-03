package com.opencode.acp.chat.ui.compose

/**
 * Split markdown content into segments: text (rendered by Jewel Markdown)
 * and fenced code blocks (rendered by our custom ChatFencedCodeBlock).
 *
 * Uses a line-by-line state machine that finds ``` fences anywhere on a line,
 * handling LLM quirks like ``` appearing after trailing text (e.g., "text```css").
 */
data class MarkdownSegment(
    val type: Type,
    val content: String,
    val language: String? = null,
) {
    enum class Type { TEXT, CODE }
}

object MarkdownSegmenter {

    /**
     * Find the first occurrence of 3+ backticks on a line.
     * Returns the index where the backticks start, or -1 if not found.
     * Ignores single/double backticks (inline code).
     */
    private fun findFenceStart(line: String): Int {
        var i = 0
        while (i <= line.length - 3) {
            if (line[i] == '`' && line[i + 1] == '`' && line[i + 2] == '`') {
                // Found 3+ backticks — check it's not preceded by a backtick (would be 4+)
                if (i > 0 && line[i - 1] == '`') {
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
        var count = 0
        var i = start
        while (i < line.length && line[i] == '`') {
            count++
            i++
        }
        return count
    }

    fun segment(markdown: String): List<MarkdownSegment> {
        val lines = markdown.lines()
        val segments = mutableListOf<MarkdownSegment>()
        val textBuilder = StringBuilder()
        var inCodeBlock = false
        var codeLanguage: String? = null
        val codeBuilder = StringBuilder()
        var fenceLength = 3

        for (line in lines) {
            if (!inCodeBlock) {
                // Search for ``` anywhere on this line
                val fenceIdx = findFenceStart(line)
                if (fenceIdx >= 0) {
                    // Found an opening fence
                    val fenceLen = countFenceChars(line, fenceIdx)
                    val afterFence = line.substring(fenceIdx + fenceLen).trimStart()
                    // Language is the first non-whitespace token after ```
                    val lang = afterFence.split(Regex("\\s")).first().ifBlank { null }

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
                    continue
                }

                // No fence found — add to text segment
                textBuilder.appendLine(line)
            } else {
                // Inside code block — look for closing fence
                val trimmedLine = line.trim()
                val fenceIdx = findFenceStart(trimmedLine)

                if (fenceIdx == 0) {
                    // Closing fence at start of (trimmed) line
                    val fenceLen = countFenceChars(trimmedLine, fenceIdx)
                    if (fenceLen >= fenceLength) {
                        // End of code block
                        inCodeBlock = false

                        // Flush text segment
                        val text = textBuilder.toString().trimEnd('\n', '\r')
                        if (text.isNotBlank()) {
                            segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, text))
                        }
                        textBuilder.clear()

                        // Flush code segment
                        val code = codeBuilder.toString().trimEnd('\n', '\r')
                        if (code.isNotBlank()) {
                            segments.add(MarkdownSegment(MarkdownSegment.Type.CODE, code, codeLanguage))
                        }
                        codeBuilder.clear()
                        codeLanguage = null

                        // Capture any text after the closing ``` on the same line
                        val afterClosing = line.substringAfter("```", "").trimStart()
                        if (afterClosing.isNotBlank()) {
                            textBuilder.appendLine(afterClosing)
                        }
                        continue
                    }
                }

                // Regular code content (or fence that didn't match closing criteria)
                codeBuilder.appendLine(line)
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
            val text = textBuilder.toString().trimEnd('\n', '\r')
            if (text.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, text))
            }
            textBuilder.clear()
        }

        if (segments.isEmpty() && markdown.isNotBlank()) {
            segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, markdown))
        }

        return segments
    }
}
