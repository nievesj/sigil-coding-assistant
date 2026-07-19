@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.chat.markdown.MarkdownSegmenter
import com.opencode.acp.chat.markdown.ParsedTable
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text

/**
 * Renders a markdown table with header, separator, and data rows.
 * Parses the raw table markdown into [ParsedTable] and renders it
 * as a scrollable grid with proper column alignment and styling.
 */
@Composable
fun ChatTable(
    rawMarkdown: String,
    modifier: Modifier = Modifier,
) {
    val table = remember(rawMarkdown) {
        MarkdownSegmenter.parseTable(rawMarkdown.lines())
    }

    if (table == null) {
        // Fallback: render as plain text if parsing fails
        Text(
            text = rawMarkdown,
            color = ChatTheme.colors.component.tableCellText,
            fontSize = ChatTheme.fonts.tableCell,
            modifier = modifier.padding(8.dp),
        )
        return
    }

    val headerTextColor = ChatTheme.colors.component.tableHeaderText
    val cellTextColor = ChatTheme.colors.component.tableCellText
    val separatorColor = ChatTheme.colors.component.tableSeparator
    val headerBgColor = ChatTheme.colors.component.tableHeaderBg
    val hoverBgColor = ChatTheme.colors.component.tableHoverBg
    val borderColor = ChatTheme.colors.component.tableBorder
    val cellFontSize = ChatTheme.fonts.tableCell
    val cellPaddingHorizontal = 12.dp
    val cellPaddingVertical = 6.dp

    // Calculate column widths based on content
    val allRows = listOf(table.header) + table.rows
    val columnCount = allRows.maxOfOrNull { it.size } ?: table.header.size
    val columnWidths = remember(allRows) {
        List(columnCount) { col ->
            val maxWidth = allRows.maxOfOrNull { row ->
                row.getOrElse(col) { "" }.length
            } ?: 0
            // Approximate width: ~7.5sp per character for monospace at 13sp
            ((maxWidth * 8) + 24).dp.coerceAtMost(300.dp)
        }
    }

    Column(
        modifier = modifier
            .clip(ChatTheme.shapes.tableCornerRadius)
            .background(ChatTheme.colors.component.tableContainerBg)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBgColor)
                .padding(vertical = cellPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            table.header.forEachIndexed { colIdx, cell ->
                val alignment = table.alignments.getOrElse(colIdx) { ParsedTable.ColumnAlignment.LEFT }
                val textAlign = when (alignment) {
                    ParsedTable.ColumnAlignment.LEFT -> TextAlign.Start
                    ParsedTable.ColumnAlignment.CENTER -> TextAlign.Center
                    ParsedTable.ColumnAlignment.RIGHT -> TextAlign.End
                }
                InlineMarkdownText(
                    text = cell,
                    color = headerTextColor,
                    fontSize = cellFontSize,
                    fontWeight = ChatTheme.fontWeights.tableHeader,
                    textAlign = textAlign,
                    inlineCodeColor = headerTextColor,
                    modifier = Modifier
                        .width(columnWidths.getOrElse(colIdx) { 100.dp })
                        .padding(horizontal = cellPaddingHorizontal),
                )
            }
        }

        // Separator line
        Box(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(separatorColor),
        )

        // Data rows
        table.rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (rowIdx % 2 == 1) Modifier.background(hoverBgColor)
                        else Modifier
                    )
                    .padding(vertical = cellPaddingVertical),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                row.forEachIndexed { colIdx, cell ->
                    val alignment = table.alignments.getOrElse(colIdx) { ParsedTable.ColumnAlignment.LEFT }
                    val textAlign = when (alignment) {
                        ParsedTable.ColumnAlignment.LEFT -> TextAlign.Start
                        ParsedTable.ColumnAlignment.CENTER -> TextAlign.Center
                        ParsedTable.ColumnAlignment.RIGHT -> TextAlign.End
                    }
                    InlineMarkdownText(
                        text = cell,
                        color = cellTextColor,
                        fontSize = cellFontSize,
                        textAlign = textAlign,
                        inlineCodeColor = headerTextColor,
                        modifier = Modifier
                            .width(columnWidths.getOrElse(colIdx) { 100.dp })
                            .padding(horizontal = cellPaddingHorizontal),
                    )
                }
            }
        }
    }
}
