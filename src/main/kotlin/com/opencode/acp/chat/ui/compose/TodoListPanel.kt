@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.TodoItem
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Collapsible todo list panel showing the current session's tasks.
 * Styled to match the chat UI's card surfaces and spacing rhythm.
 */
@Composable
fun TodoListPanel(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier
) {
    if (todos.isEmpty()) return

    // Show all todos except cancelled. Completed items are kept and rendered
    // with a green check + strikethrough so the user sees progress.
    val visibleTodos = todos.filter { it.status != TodoItem.STATUS_CANCELLED }
    if (visibleTodos.isEmpty()) return

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
            .border(width = 1.dp, color = colors.border.subtle, shape = shapes.todoCornerRadius)
            .background(colors.component.todoBg)
            .padding(horizontal = dims.todoPaddingH, vertical = dims.todoPaddingV),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Header with toggle — always collapsible
        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isHovered) colors.component.paletteHoverBg else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null) { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(dims.todoChevronSize),
                tint = colors.component.todoHeader,
            )
            Text(
                text = "Todo",
                fontSize = fonts.todoHeader,
                fontWeight = fontWeights.todoHeader,
                color = colors.text.secondary,
            )
            Text(
                text = "${visibleTodos.size}",
                fontSize = fonts.todoCount,
                fontWeight = FontWeight.Medium,
                color = colors.component.todoPending,
            )
        }

        // Todo items with animated expand/collapse
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(150))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleTodos.forEach { todo ->
                    TodoRow(todo = todo)
                }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val shapes = ChatTheme.shapes

    val contentColor = if (todo.status == TodoItem.STATUS_COMPLETED || todo.status == TodoItem.STATUS_CANCELLED) {
        colors.component.todoPending
    } else {
        colors.component.todoActiveText
    }
    val textDecoration = if (todo.status == TodoItem.STATUS_COMPLETED) TextDecoration.LineThrough else TextDecoration.None

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = dims.todoPaddingH, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        when (todo.status) {
            TodoItem.STATUS_COMPLETED -> Icon(
                key = AllIconsKeys.Actions.Checked,
                contentDescription = todo.status,
                modifier = Modifier.size(dims.todoStatusIconSize),
                tint = colors.component.todoCompleted,
            )
            TodoItem.STATUS_IN_PROGRESS -> Icon(
                key = AllIconsKeys.Actions.Execute,
                contentDescription = todo.status,
                modifier = Modifier.size(dims.todoStatusIconSize),
                tint = colors.component.todoInProgress,
            )
            TodoItem.STATUS_CANCELLED -> Icon(
                key = AllIconsKeys.Actions.Cancel,
                contentDescription = todo.status,
                modifier = Modifier.size(dims.todoStatusIconSize),
                tint = colors.component.todoCancelled,
            )
            else -> TodoPendingDot(
                color = colors.component.todoPending,
                size = dims.todoStatusIconSize
            )
        }
        TooltipArea(
            tooltip = {
                Box(
                    modifier = Modifier
                        .clip(shapes.contextTooltipCornerRadius)
                        .background(colors.surface.dark)
                        .border(1.dp, colors.component.tooltipBorder, shapes.contextTooltipCornerRadius)
                        .padding(8.dp)
                ) {
                    Text(
                        text = todo.content,
                        fontSize = 11.sp,
                        color = colors.text.primary
                    )
                }
            }
        ) {
            Text(
                text = todo.content,
                fontSize = fonts.todoContent,
                color = contentColor,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = textDecoration,
                modifier = Modifier.weight(1f).padding(end = 4.dp)
            )
        }
    }
}

/**
 * Hollow ring indicator for pending todo items.
 */
@Composable
private fun TodoPendingDot(
    color: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val strokeWidth = 1.5.dp.toPx()
        val radius = size.toPx() / 2f - strokeWidth
        drawCircle(
            color = color,
            radius = radius,
            style = Stroke(width = strokeWidth)
        )
    }
}
