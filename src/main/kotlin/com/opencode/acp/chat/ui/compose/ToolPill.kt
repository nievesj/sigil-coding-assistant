package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentclientprotocol.model.ToolCallStatus
import com.intellij.icons.AllIcons
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.util.ToolStatusDisplay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

@Composable
fun ToolPill(pill: ToolCallPill, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    // Completed pills render in compact mode (single line, no border/background)
    val isCompact = pill.status == ToolCallStatus.COMPLETED || pill.status == ToolCallStatus.FAILED

    val iconKey = when (pill.status) {
        ToolCallStatus.PENDING -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Execute)
        ToolCallStatus.IN_PROGRESS -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Execute)
        ToolCallStatus.COMPLETED -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Checked)
        ToolCallStatus.FAILED -> IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Cancel)
    }

    val statusLabel = ToolStatusDisplay.label(pill.kind)
    val hasDetails = pill.input != null || pill.output != null

    if (isCompact) {
        // Compact row: no border, no background, single line
        Row(
            modifier = modifier
                .padding(horizontal = 12.dp, vertical = 1.dp)
                .then(
                    if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                key = iconKey,
                contentDescription = pill.status.name,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$statusLabel: ${pill.title}",
                color = Color(0xFF666666),
                fontSize = 11.sp
            )
            if (hasDetails) {
                Icon(
                    key = IntelliJIconKey.fromPlatformIcon(
                        if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                    ),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(10.dp),
                    tint = Color(0xFF666666),
                )
            }
        }
    } else {
        // Full row: bordered, with background (for running/pending pills)
        Column(
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
        ) {
            // Header row — always visible, clickable to toggle details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
                if (hasDetails) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(
                            if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                        ),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF808080),
                    )
                }
            }
        }
    }

    // Detail section — input and output JSON (shared between compact and full)
    if (expanded && hasDetails) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompact) 28.dp else 8.dp, vertical = 2.dp)
        ) {
            pill.input?.let { input ->
                DetailSection(label = "Input", json = inputToString(input))
            }
            pill.output?.let { output ->
                val text = output.joinToString("\n") { obj -> objToString(obj) }
                if (text.isNotBlank()) {
                    DetailSection(label = "Output", json = text)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, json: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF888888),
            fontSize = 10.sp,
        )
        Text(
            text = json,
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(
                    color = Color(0x08FFFFFF),
                    shape = RoundedCornerShape(3.dp)
                )
                .padding(4.dp)
        )
    }
}

/** Pretty-print a JsonObject to a readable string. */
private fun inputToString(obj: JsonObject): String {
    return obj.entries.joinToString(", ") { (key, value) ->
        val content = try {
            val primitive = value.jsonPrimitive
            primitive.content
        } catch (_: Exception) {
            value.toString()
        }
        "$key=$content"
    }
}

/** Pretty-print a JsonObject from tool output. */
private fun objToString(obj: JsonObject): String {
    return obj.entries.joinToString(", ") { (key, value) ->
        val content = try {
            val primitive = value.jsonPrimitive
            primitive.content
        } catch (_: Exception) {
            value.toString()
        }
        "$key=$content"
    }
}
