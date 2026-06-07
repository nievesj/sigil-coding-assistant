package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun PermissionPrompt(
    prompt: PermissionPrompt,
    onRespond: (PermissionResponse) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .border(
                width = 1.dp,
                color = Color(0x40808080),
                shape = RoundedCornerShape(4.dp)
            )
            .background(
                color = Color(0x10808080),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                key = AllIconsKeys.Actions.Lightning,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = prompt.toolName,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = prompt.description ?: "This tool requires permission.",
                    color = Color.Gray
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.End)
        ) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { onRespond(PermissionResponse.ALLOW_ONCE) }) {
                Text("Allow Once")
            }
            OutlinedButton(onClick = { onRespond(PermissionResponse.REJECT_ONCE) }) {
                Text("Reject")
            }
            DefaultButton(onClick = { onRespond(PermissionResponse.ALLOW_ALWAYS) }) {
                Text("Always Allow")
            }
        }
    }
}