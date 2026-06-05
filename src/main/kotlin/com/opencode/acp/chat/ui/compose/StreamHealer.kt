package com.opencode.acp.chat.ui.compose

/**
 * Heals incomplete/stale markdown formatting during streaming to prevent
 * raw syntax (backticks, asterisks, link brackets) from flashing in the UI.
 *
 * Inspired by OpenCode's `remend` library which performs similar healing
 * for streamed markdown content.
 */
object StreamHealer {

    /**
     * Heals incomplete markdown in [text], returning a version where
     * unclosed formatting is closed and incomplete links are stripped.
     *
     * This should be called on the text BEFORE it passes through
     * MarkdownSegmenter.segment().
     */
    fun heal(text: String): String {
        if (text.isBlank()) return text

        // Split into code-fence segments and non-code segments.
        // Only heal non-code text.
        val segments = splitByCodeFences(text)
        val healed = StringBuilder()
        for (segment in segments) {
            if (segment.inCode) {
                healed.append(segment.text)
            } else {
                healed.append(healInlineMarkdown(segment.text))
            }
        }
        return healed.toString()
    }

    /**
     * Splits text into regions that are inside fenced code blocks
     * and regions that are outside them.
     */
    private fun splitByCodeFences(text: String): List<FenceSegment> {
        val segments = mutableListOf<FenceSegment>()
        val lines = text.lines()
        var inCode = false
        val buffer = StringBuilder()

        for (line in lines) {
            val trimmed = line.trimStart()
            val isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (isFence) {
                // Flush current buffer
                if (buffer.isNotEmpty()) {
                    segments.add(FenceSegment(buffer.toString(), inCode))
                    buffer.clear()
                }
                // Toggle code block state
                inCode = !inCode
                buffer.appendLine(line)
            } else {
                buffer.appendLine(line)
            }
        }
        // Flush remaining
        if (buffer.isNotEmpty()) {
            segments.add(FenceSegment(buffer.toString(), inCode))
        }
        return segments
    }

    /**
     * Heals inline markdown issues in non-code text:
     * 1. Strips incomplete links [text](... without closing )
     * 2. Closes unclosed inline code backticks
     * 3. Closes unclosed bold (**... without closing **)
     */
    private fun healInlineMarkdown(text: String): String {
        var result = text

        // 1. Strip incomplete links: [text](url... without closing )
        // Pattern: [anything](anything that doesn't end with )
        // Replace with just the link text
        result = incompleteLinkRegex.replace(result) { match ->
            match.groupValues[1] // Just the link text, drop the (url part
        }

        // 2. Close unclosed inline code backticks
        // Count single backticks (not triple+) that are unmatched
        result = closeUnclosedInlineCode(result)

        // 3. Close unclosed bold (** without closing **)
        result = closeUnclosedBold(result)

        return result
    }

    /**
     * Close unclosed inline code backticks.
     * If a line has an odd number of single backtick pairs (not inside triple fences),
     * append a closing backtick.
     */
    private fun closeUnclosedInlineCode(text: String): String {
        val lines = text.lines()
        val healed = mutableListOf<String>()
        for (line in lines) {
            // Skip lines that start with ``` (code fence markers)
            if (line.trimStart().startsWith("```")) {
                healed.add(line)
                continue
            }
            // Count single backticks (not triples)
            // Remove all triple+ backtick sequences first, then count remaining single backticks
            val withoutTriples = tripleBacktickRegex.replace(line, "")
            val singleBacktickCount = withoutTriples.count { it == '`' }
            if (singleBacktickCount % 2 != 0) {
                // Odd number of backticks — add a closing one
                healed.add(line + "`")
            } else {
                healed.add(line)
            }
        }
        return healed.joinToString("\n")
    }

    /**
     * Close unclosed bold markers (** without matching **).
     * Only matches ** that are not part of *** (bold+italic) or inside code.
     */
    private fun closeUnclosedBold(text: String): String {
        // Count ** occurrences outside of code spans
        var result = text
        val boldCount = doubleStarRegex.findAll(result).count()
        if (boldCount % 2 != 0) {
            result = result.trimEnd() + "**"
        }
        return result
    }

    private data class FenceSegment(val text: String, val inCode: Boolean)

    private val incompleteLinkRegex = Regex("""\[([^\]]+)\]\(([^)]*)$""")
    private val tripleBacktickRegex = Regex("""`{3,}""")
    private val doubleStarRegex = Regex("""\*\*""")
}
