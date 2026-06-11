package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.TodoItem
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Collapsible todo list panel showing the current session's tasks.
 * Uses IntelliJ platform icons for status indicators — consistent with the IDE.
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

    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val fontWeights = ChatTheme.fontWeights
    val shapes = ChatTheme.shapes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.todoCornerRadius)
            .background(colors.component.todoBg)
            .padding(horizontal = dims.todoPaddingH, vertical = dims.todoPaddingV),
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
            Icon(
                key = if (incomplete.size > 4 && !expanded) AllIconsKeys.General.ChevronRight else AllIconsKeys.General.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(dims.todoChevronSize),
                tint = colors.component.todoHeader,
            )
            Text(
                text = "Todo",
                fontSize = fonts.todoHeader,
                fontWeight = fontWeights.todoHeader,
                color = colors.component.todoAccent,
            )
            Text(
                text = "${incomplete.size}",
                fontSize = fonts.todoCount,
                color = colors.component.todoPending,
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
                fontSize = fonts.todoMoreHint,
                color = colors.component.todoPending,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts

    val (iconKey, color) = when (todo.status) {
        "completed" -> AllIconsKeys.Actions.Checked to colors.component.todoCompleted
        "in_progress" -> AllIconsKeys.Actions.Execute to colors.component.todoInProgress
        "cancelled" -> AllIconsKeys.Actions.Cancel to colors.component.todoCancelled
        else -> AllIconsKeys.Actions.Lightning to colors.component.todoPending
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = dims.todoPaddingH, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            key = iconKey,
            contentDescription = todo.status,
            modifier = Modifier.size(dims.todoStatusIconSize),
            tint = color,
        )
        Text(
            text = todo.content,
            fontSize = fonts.todoContent,
            color = if (todo.status == "completed" || todo.status == "cancelled") colors.component.todoPending
                    else colors.component.todoActiveText,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
    }
}
