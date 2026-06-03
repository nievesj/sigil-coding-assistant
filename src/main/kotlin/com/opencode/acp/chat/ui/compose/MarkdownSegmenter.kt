package com.opencode.acp.chat.ui.compose

/**
 * Split markdown content into segments: text (rendered by Jewel Markdown)
 * and fenced code blocks (rendered by our custom ChatFencedCodeBlock).
 *
 * This bypasses Jewel's DefaultMarkdownBlockRenderer which we cannot
 * reliably override for code block rendering in IU-261.
 *
 * Uses a line-by-line state machine instead of regex to handle edge cases
 * like ``` appearing mid-line (e.g., "text```css") which the LLM models
 * sometimes produce.
 */
data class MarkdownSegment(
    val type: Type,
    val content: String,
    val language: String? = null,
) {
    enum class Type { TEXT, CODE }
}

object MarkdownSegmenter {

    private val OPEN_FENCE = Regex("""^(\s*)(`{3,})(\S*)(\s.*)?$""")

    /**
     * State machine that scans line-by-line to find fenced code blocks.
     * Handles ``` at start of line AND mid-line (after text).
     */
    fun segment(markdown: String): List<MarkdownSegment> {
        val lines = markdown.lines()
        val segments = mutableListOf<MarkdownSegment>()
        val textBuilder = StringBuilder()
        var inCodeBlock = false
        var codeLanguage: String? = null
        val codeBuilder = StringBuilder()
        var fenceChar = '`'
        var fenceLength = 3

        for (line in lines) {
            if (!inCodeBlock) {
                // Look for opening fence — ``` can appear at start of line OR after trailing text
                val trimmedStart = line.trimStart()
                if (trimmedStart.startsWith("`")) {
                    val match = OPEN_FENCE.matchEntire(trimmedStart)
                    if (match != null) {
                        val lang = match.groupValues[3].trim()
                        val fence = match.groupValues[2]
                        // Only treat as code fence if there's a language or it's on its own
                        // A bare ``` with no language on a line by itself = code fence
                        // ```css = code fence with language
                        // text```css = also a code fence (LLMs sometimes do this)
                        inCodeBlock = true
                        codeLanguage = lang.ifBlank { null }
                        fenceLength = fence.length
                        fenceChar = '`'

                        // Flush any text before the fence on this line
                        // Check if there's text before the ``` (e.g., "text```css")
                        val fenceIdx = line.indexOf(fence)
                        if (fenceIdx > 0) {
                            // There's text before the ``` — include it in the text segment
                            val textBefore = line.substring(0, fenceIdx)
                            if (textBefore.isNotBlank()) {
                                textBuilder.appendLine(textBefore.trimEnd())
                            }
                        }
                        continue
                    }
                }

                // Not a fence — add to current text segment
                textBuilder.appendLine(line)
            } else {
                // Inside code block — look for closing fence
                val trimmedLine = line.trim()
                val isClosingFence = trimmedLine.matchesClosingFence(fenceChar, fenceLength)

                if (isClosingFence) {
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
                } else {
                    // Regular code content
                    codeBuilder.appendLine(line)
                }
            }
        }

        // Handle unclosed code block (streaming — closing ``` hasn't arrived yet)
        if (inCodeBlock) {
            // Put what we have as code
            val code = codeBuilder.toString().trimEnd('\n', '\r')
            if (code.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.CODE, code, codeLanguage))
            }
            codeBuilder.clear()
        } else {
            // Flush remaining text
            val text = textBuilder.toString().trimEnd('\n', '\r')
            if (text.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, text))
            }
            textBuilder.clear()
        }

        // If nothing found, return whole content as text
        if (segments.isEmpty() && markdown.isNotBlank()) {
            segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, markdown))
        }

        return segments
    }

    private fun String.matchesClosingFence(expectedChar: Char, minLength: Int): Boolean {
        val trimmed = trim()
        if (trimmed.isEmpty()) return false
        // Closing fence must be 3+ of the same char and nothing else (or optional trailing spaces)
        if (trimmed[0] != expectedChar) return false
        if (trimmed.length < minLength) return false
        // All chars must be the fence char (allow trailing spaces)
        return trimmed.all { it == expectedChar || it == ' ' || it == '\t' }
    }
}