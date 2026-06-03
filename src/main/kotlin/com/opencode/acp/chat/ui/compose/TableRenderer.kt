@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            color = Color(0xFFD4D4D4),
            fontSize = 13.sp,
            modifier = modifier.padding(8.dp),
        )
        return
    }

    val headerTextColor = Color(0xFF6BBE50)
    val cellTextColor = Color(0xFFD4D4D4)
    val separatorColor = Color(0xFF3E3E3E)
    val headerBgColor = Color(0xFF2A2A2A)
    val hoverBgColor = Color(0xFF252525)
    val borderColor = Color(0xFF3E3E3E)
    val cellFontSize = 13.sp
    val cellPaddingHorizontal = 12.dp
    val cellPaddingVertical = 6.dp

    // Calculate column widths based on content
    val allRows = listOf(table.header) + table.rows
    val columnCount = table.header.size
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
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .background(headerBgColor)
                .padding(vertical = cellPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            table.header.forEachIndexed { colIdx, cell ->
                Text(
                    text = cell,
                    color = headerTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = cellFontSize,
                    modifier = Modifier
                        .width(columnWidths[colIdx])
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
                    .then(
                        if (rowIdx % 2 == 1) Modifier.background(hoverBgColor)
                        else Modifier
                    )
                    .padding(vertical = cellPaddingVertical),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                row.forEachIndexed { colIdx, cell ->
                    Text(
                        text = cell,
                        color = cellTextColor,
                        fontSize = cellFontSize,
                        modifier = Modifier
                            .width(columnWidths[colIdx])
                            .padding(horizontal = cellPaddingHorizontal),
                    )
                }
            }
        }
    }
}
