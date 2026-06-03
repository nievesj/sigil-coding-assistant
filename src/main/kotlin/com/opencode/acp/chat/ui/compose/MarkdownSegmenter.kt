package com.opencode.acp.chat.ui.compose

/**
 * Split markdown content into segments: text (rendered by Jewel Markdown)
 * and fenced code blocks (rendered by our custom ChatFencedCodeBlock).
 *
 * This bypasses Jewel's DefaultMarkdownBlockRenderer which we cannot
 * reliably override for code block rendering in IU-261.
 */
data class MarkdownSegment(
    val type: Type,
    val content: String,
    val language: String? = null,
) {
    enum class Type { TEXT, CODE }
}

object MarkdownSegmenter {

    private val FENCED_CODE_REGEX = Regex(
        """^```(\S*)\n(.*?)^```""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
    )

    fun segment(markdown: String): List<MarkdownSegment> {
        val segments = mutableListOf<MarkdownSegment>()
        var lastIndex = 0

        for (match in FENCED_CODE_REGEX.findAll(markdown)) {
            // Text before this code block
            if (match.range.first > lastIndex) {
                val textBefore = markdown.substring(lastIndex, match.range.first)
                if (textBefore.isNotBlank()) {
                    segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, textBefore))
                }
            }

            // The code block
            val lang = match.groupValues[1].ifBlank { null }
            val code = match.groupValues[2]
            segments.add(MarkdownSegment(MarkdownSegment.Type.CODE, code, lang))

            lastIndex = match.range.last + 1
        }

        // Remaining text after last code block
        if (lastIndex < markdown.length) {
            val remaining = markdown.substring(lastIndex)
            if (remaining.isNotBlank()) {
                segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, remaining))
            }
        }

        // If no code blocks found, return the whole content as text
        if (segments.isEmpty() && markdown.isNotBlank()) {
            segments.add(MarkdownSegment(MarkdownSegment.Type.TEXT, markdown))
        }

        return segments
    }
}