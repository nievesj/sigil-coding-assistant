package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.TodoItem
import org.jetbrains.jewel.ui.component.Text

private val TodoBg = Color(0xFF252525)
private val TodoHeaderColor = Color(0xFF808080)
private val TodoPendingColor = Color(0xFF808080)
private val TodoInProgressColor = Color(0xFFE5A617)
private val TodoCompletedColor = Color(0xFF6BBE50)
private val TodoCancelledColor = Color(0xFF666666)
private val TodoAccent = Color(0xFF3574F0)

/**
 * Collapsible todo list panel showing the current session's tasks.
 * Modeled after OpenCode's sidebar todo panel — shows ✓ for completed,
 * • for in_progress, and ○ for pending tasks.
 *
 * Automatically collapses when there are more than 4 incomplete items.
 */
@Composable
fun TodoListPanel(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier
) {
    if (todos.isEmpty()) return

    val incomplete = todos.filter { it.status != "completed" && it.status != "cancelled" }
    if (incomplete.isEmpty()) return

    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TodoBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Header with toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = incomplete.size > 4) { expanded = !expanded }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (incomplete.size > 4 && !expanded) "▶" else "▼",
                fontSize = 10.sp,
                color = TodoHeaderColor,
            )
            Text(
                text = "Todo",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TodoAccent,
            )
            Text(
                text = "${incomplete.size}",
                fontSize = 10.sp,
                color = TodoPendingColor,
            )
        }

        // Todo items
        val visibleItems = if (expanded || incomplete.size <= 4) incomplete else incomplete.take(2)
        visibleItems.forEach { todo ->
            TodoRow(todo = todo)
        }
        if (!expanded && incomplete.size > 4) {
            Text(
                text = "  +${incomplete.size - 2} more…",
                fontSize = 10.sp,
                color = TodoPendingColor,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val (icon, color) = when (todo.status) {
        "completed" -> "✓" to TodoCompletedColor
        "in_progress" -> "•" to TodoInProgressColor
        "cancelled" -> "✗" to TodoCancelledColor
        else -> "○" to TodoPendingColor
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = icon,
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.size(width = 14.dp, height = 14.dp)
        )
        Text(
            text = todo.content,
            fontSize = 11.sp,
            color = if (todo.status == "completed" || todo.status == "cancelled") TodoPendingColor
                    else Color(0xFFDDDDDD),
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
    }
}