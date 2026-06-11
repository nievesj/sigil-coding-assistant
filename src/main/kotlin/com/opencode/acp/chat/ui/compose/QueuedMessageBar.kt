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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Displays queued messages above the input area during queue mode.
 *
 * Shows each queued message with a clock icon, the message text (truncated),
 * and a cancel (X) button. The bar is compact and uses the same visual style
 * as the TodoListPanel.
 */
@Composable
fun QueuedMessageBar(
    queuedMessages: List<QueuedMessage>,
    onCancelMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (queuedMessages.isEmpty()) return

    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts
    val fontWeights = ChatTheme.fontWeights

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ChatTheme.shapes.todoCornerRadius)
            .background(colors.component.todoBg)
            .padding(horizontal = dims.todoPaddingH, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                key = AllIconsKeys.Actions.Execute,
                contentDescription = "Queued",
                modifier = Modifier.size(14.dp),
                tint = colors.component.todoInProgress,
            )
            Text(
                text = "Queued",
                fontSize = fonts.todoHeader,
                fontWeight = fontWeights.todoHeader,
                color = colors.component.todoInProgress,
            )
            Text(
                text = "${queuedMessages.size}",
                fontSize = fonts.todoCount,
                color = colors.component.todoPending,
            )
        }

        // Queued message items
        queuedMessages.forEach { msg ->
            QueuedMessageRow(
                message = msg,
                onCancel = { onCancelMessage(msg.id) }
            )
        }
    }
}

@Composable
private fun QueuedMessageRow(
    message: QueuedMessage,
    onCancel: () -> Unit
) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims
    val fonts = ChatTheme.fonts

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = dims.todoPaddingH, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = AllIconsKeys.Actions.Lightning,
            contentDescription = "Queued",
            modifier = Modifier.size(dims.todoStatusIconSize),
            tint = colors.component.todoInProgress,
        )
        Text(
            text = message.text.take(80) + if (message.text.length > 80) "…" else "",
            fontSize = fonts.todoContent,
            color = colors.component.todoActiveText,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        // Cancel button (X)
        Icon(
            key = AllIconsKeys.Actions.Close,
            contentDescription = "Remove from queue",
            modifier = Modifier
                .size(14.dp)
                .clip(ChatTheme.shapes.todoCornerRadius)
                .clickable { onCancel() }
                .padding(1.dp),
            tint = colors.component.todoPending,
        )
    }
}
