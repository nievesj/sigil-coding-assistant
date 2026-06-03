package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentclientprotocol.model.ToolCallStatus
import com.intellij.icons.AllIcons
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.util.ToolStatusDisplay
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

@Composable
fun ToolPill(pill: ToolCallPill, modifier: Modifier = Modifier) {
    val iconKey = when (pill.status) {
        ToolCallStatus.PENDING -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Execute)
        ToolCallStatus.IN_PROGRESS -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Execute)
        ToolCallStatus.COMPLETED -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Checked)
        ToolCallStatus.FAILED -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Cancel)
    }

    val statusLabel = ToolStatusDisplay.label(pill.kind)

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(
                width = 1.dp,
                color = Color(0x40808080),
                shape = RoundedCornerShape(4.dp)
            )
            .background(
                color = Color(0x10808080),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                key = iconKey,
                contentDescription = pill.status.name,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "$statusLabel: ${pill.title}",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}