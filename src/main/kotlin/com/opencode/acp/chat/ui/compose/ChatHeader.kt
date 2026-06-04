package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

@Composable
fun ChatHeader(
    isSidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            key = IntelliJIconKey.fromPlatformIcon(
                if (isSidebarVisible) AllIcons.General.ChevronLeft else AllIcons.General.ChevronRight
            ),
            contentDescription = if (isSidebarVisible) "Hide sessions" else "Show sessions",
            modifier = Modifier
                .size(16.dp)
                .clickable { onToggleSidebar() }
        )
        Spacer(Modifier.width(8.dp))
        Text("OpenCode")
    }
}
