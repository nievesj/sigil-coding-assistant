package com.opencode.acp.chat.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import org.jetbrains.jewel.ui.component.Text

/**
 * Lightweight inline markdown renderer for table cells and other constrained layouts.
 * Handles bold (**text**), italic (*text*), inline code (`text`),
 * and strikethrough (~~text~~) — delimiters are stripped, only content is styled.
 *
 * This is NOT a full markdown renderer — it only handles inline formatting,
 * not block-level elements like headings, lists, code blocks, or links.
 */
@Composable
fun InlineMarkdownText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: TextUnit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    inlineCodeColor: androidx.compose.ui.graphics.Color = color,
    inlineCodeFontFamily: FontFamily = FontFamily.Monospace,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    val annotated = buildInlineMarkdown(text, inlineCodeColor, inlineCodeFontFamily)
    if (textAlign != null) {
        Text(
            text = annotated,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            modifier = modifier,
        )
    } else {
        Text(
            text = annotated,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = modifier,
        )
    }
}

/**
 * Represents a matched inline markdown pattern with its delimiter ranges and content range.
 * fullStart/fullEnd include the delimiters; contentStart/contentEnd exclude them.
 */
private data class InlineMatch(
    val fullStart: Int,
    val fullEnd: Int,      // exclusive
    val contentStart: Int,
    val contentEnd: Int,    // exclusive
    val style: SpanStyle,
)

private val inlineCodePattern = Regex("""`([^`\n]+)`""")
private val strikethroughPattern = Regex("""~~([^~]+)~~""")
private val boldPattern = Regex("""\*\*([^*]+(?:\*[^*]+)*)\*\*""")
private val italicPattern = Regex("""\*([^*]+)\*""")

private fun buildInlineMarkdown(
    text: String,
    inlineCodeColor: androidx.compose.ui.graphics.Color,
    inlineCodeFontFamily: FontFamily,
): AnnotatedString {
    val matches = mutableListOf<InlineMatch>()

    // 1. Inline code — highest priority
    for (m in inlineCodePattern.findAll(text)) {
        val contentGroup = m.groups[1]!!
        matches.add(InlineMatch(
            fullStart = m.range.first,
            fullEnd = m.range.last + 1,
            contentStart = contentGroup.range.first,
            contentEnd = contentGroup.range.last + 1,
            style = SpanStyle(color = inlineCodeColor, fontFamily = inlineCodeFontFamily),
        ))
    }

    // 2. Strikethrough
    for (m in strikethroughPattern.findAll(text)) {
        if (!overlapsAny(m.range.first, m.range.last + 1, matches)) {
            val contentGroup = m.groups[1]!!
            matches.add(InlineMatch(
                fullStart = m.range.first,
                fullEnd = m.range.last + 1,
                contentStart = contentGroup.range.first,
                contentEnd = contentGroup.range.last + 1,
                style = SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
            ))
        }
    }

    // 3. Bold (**content**)
    for (m in boldPattern.findAll(text)) {
        if (!overlapsAny(m.range.first, m.range.last + 1, matches)) {
            val contentGroup = m.groups[1]!!
            matches.add(InlineMatch(
                fullStart = m.range.first,
                fullEnd = m.range.last + 1,
                contentStart = contentGroup.range.first,
                contentEnd = contentGroup.range.last + 1,
                style = SpanStyle(fontWeight = FontWeight.Bold),
            ))
        }
    }

    // 4. Italic (*content*)
    for (m in italicPattern.findAll(text)) {
        if (!overlapsAny(m.range.first, m.range.last + 1, matches)) {
            val contentGroup = m.groups[1]!!
            matches.add(InlineMatch(
                fullStart = m.range.first,
                fullEnd = m.range.last + 1,
                contentStart = contentGroup.range.first,
                contentEnd = contentGroup.range.last + 1,
                style = SpanStyle(fontStyle = FontStyle.Italic),
            ))
        }
    }

    if (matches.isEmpty()) {
        return AnnotatedString(text)
    }

    // Sort by start position
    matches.sortBy { it.fullStart }

    // Build the output string by stripping delimiters and track new positions
    val output = StringBuilder()
    val styleRanges = mutableListOf<Triple<Int, Int, SpanStyle>>() // (start, end, style) in output coords
    var inputPos = 0

    for (match in matches) {
        // Skip past any matches that overlap (shouldn't happen due to overlap check, but be safe)
        if (match.fullStart < inputPos) continue

        // Append plain text before this match (including text between end of previous and start of this)
        if (match.fullStart > inputPos) {
            output.append(text.substring(inputPos, match.fullStart))
        }

        // Append the content (without delimiters)
        val contentOffset = output.length
        output.append(text.substring(match.contentStart, match.contentEnd))
        styleRanges.add(Triple(contentOffset, output.length, match.style))

        inputPos = match.fullEnd
    }

    // Append remaining plain text
    if (inputPos < text.length) {
        output.append(text.substring(inputPos))
    }

    return buildAnnotatedString {
        append(output.toString())
        for ((start, end, style) in styleRanges) {
            addStyle(style, start, end)
        }
    }
}

private fun overlapsAny(start: Int, end: Int, existing: List<InlineMatch>): Boolean {
    return existing.any { it.fullStart < end && start < it.fullEnd }
}