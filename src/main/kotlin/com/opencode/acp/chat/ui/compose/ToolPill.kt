package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ToolCallPill
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun ToolPill(pill: ToolCallPill, modifier: Modifier = Modifier) {
    // Edit and Shell pills expanded by default
    var expanded by remember {
        mutableStateOf(pill.kind == ToolKind.EXECUTE || pill.kind == ToolKind.EDIT)
    }

    val iconKey = when (pill.status) {
        ToolCallStatus.PENDING -> AllIconsKeys.Actions.Lightning
        ToolCallStatus.IN_PROGRESS -> AllIconsKeys.Actions.Execute
        ToolCallStatus.COMPLETED -> AllIconsKeys.Actions.Checked
        ToolCallStatus.FAILED -> AllIconsKeys.Actions.Cancel
    }

    val accentColor = toolKindColor(pill.kind)
    val kindLabel = toolKindLabel(pill.kind)
    val hasDetails = pill.input != null || pill.output != null

    // Resolve display data
    val fileName = remember(pill.kind, pill.input) { resolveFileName(pill) }
    val description = remember(pill.kind, pill.input) { resolveDescription(pill) }
    val lineDelta = remember(pill.kind, pill.input) { computeLineDelta(pill) }

    // ── Unified container — header + expanded body as one visual element ──
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x0CFFFFFF))
    ) {
        // ── Header row (always visible, clickable to toggle) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (hasDetails) expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored accent strip
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(8.dp))

            // Status icon
            Icon(
                key = iconKey,
                contentDescription = pill.status.name,
                modifier = Modifier.size(16.dp),
                tint = accentColor,
            )
            Spacer(Modifier.width(8.dp))

            // Kind label (bold, colored)
            Text(
                text = kindLabel,
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))

            // Filename or description
            val headerText = fileName ?: description
            Text(
                text = headerText,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            // Line delta (+N / -N)
            if (lineDelta != null) {
                Spacer(Modifier.width(6.dp))
                if (lineDelta.first > 0) {
                    Text(
                        text = "+${lineDelta.first}",
                        color = Color(0xFF7EE787),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (lineDelta.second > 0) {
                    if (lineDelta.first > 0) Spacer(Modifier.width(4.dp))
                    Text(
                        text = "-${lineDelta.second}",
                        color = Color(0xFFFF7B72),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Chevron
            if (hasDetails) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    key = if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF808080),
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        // ── Expanded body (inside the same container) ──
        if (expanded && hasDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                when (pill.kind) {
                    ToolKind.EXECUTE -> expandShell(pill)
                    ToolKind.EDIT -> expandEdit(pill, fileName)
                    ToolKind.READ -> expandRead(pill, fileName)
                    else -> expandGeneric(pill)
                }
            }
        }
    }
}

// ── Expanded content by tool kind ──────────────────────────────

@Composable
private fun expandShell(pill: ToolCallPill) {
    // Command block
    pill.input?.getString("command")?.let { command ->
        ChatFencedCodeBlock(content = command, language = "shell")
    }
    // Output
    pill.output?.let { output ->
        val text = formatOutput(output)
        if (text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            ChatFencedCodeBlock(content = text, language = "text")
        }
    }
}

@Composable
private fun expandEdit(pill: ToolCallPill, fileName: String?) {
    val lang = fileName?.substringAfterLast('.') ?: "text"
    // Prefer full content (write tool), then newString (edit tool), then new
    val content = pill.input?.getString("content")
    val newString = pill.input?.getString("newString") ?: pill.input?.getString("new_string")
        ?: pill.input?.getString("new")
    if (content != null) {
        ChatFencedCodeBlock(content = content, language = lang)
    } else if (newString != null) {
        ChatFencedCodeBlock(content = newString, language = lang)
    } else {
        // Nothing to show — don't expand
    }
}

@Composable
private fun expandRead(pill: ToolCallPill, fileName: String?) {
    val lang = fileName?.substringAfterLast('.') ?: "text"
    pill.output?.let { output ->
        val text = formatOutput(output)
        if (text.isNotBlank()) {
            ChatFencedCodeBlock(content = text, language = lang)
        }
    }
}

@Composable
private fun expandGeneric(pill: ToolCallPill) {
    // Show output only — input fields are internal, not user-facing
    pill.output?.let { output ->
        val text = formatOutput(output)
        if (text.isNotBlank()) {
            ChatFencedCodeBlock(content = text, language = "text")
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────

/** Extract the file name from input JSON. */
private fun resolveFileName(pill: ToolCallPill): String? {
    val input = pill.input ?: return null
    val path = input.getString("file_path")
        ?: input.getString("filePath")
        ?: input.getString("path")
        ?: input.getString("old_file_path")
    return path?.let { it.substringAfterLast('/').substringAfterLast('\\') }
}

/** Resolve a human-readable description for the header. */
private fun resolveDescription(pill: ToolCallPill): String {
    val input = pill.input
    return when (pill.kind) {
        ToolKind.EXECUTE -> {
            input?.getString("description")
                ?: input?.getString("command")?.take(80)
                ?: pill.title
        }
        ToolKind.EDIT -> {
            val path = input?.getString("filePath") ?: input?.getString("file_path")
                ?: input?.getString("path")
                ?: input?.getString("old_file_path")
            path?.substringAfterLast('/') ?: pill.title
        }
        ToolKind.READ -> {
            val path = input?.getString("filePath") ?: input?.getString("file_path")
                ?: input?.getString("path")
            path?.substringAfterLast('/') ?: pill.title
        }
        ToolKind.SEARCH -> {
            val query = input?.getString("pattern") ?: input?.getString("query")
            if (query != null) "\"${query.take(50)}\"" else pill.title
        }
        else -> pill.title
    }
}

/** Compute line additions/deletions from edit input. Returns (additions, deletions) or null. */
private fun computeLineDelta(pill: ToolCallPill): Pair<Int, Int>? {
    val input = pill.input ?: return null
    return when (pill.kind) {
        ToolKind.EDIT -> {
            val oldString = input.getString("oldString") ?: input.getString("old_string") ?: input.getString("old")
            val newString = input.getString("newString") ?: input.getString("new_string") ?: input.getString("new")
            if (oldString != null && newString != null) {
                val additions = newString.lines().size
                val deletions = oldString.lines().size
                Pair(additions, deletions)
            } else {
                // content field = full new file; no old content to diff against
                val content = input.getString("content")
                if (content != null) Pair(content.lines().size, 0) else null
            }
        }
        else -> null
    }
}

private fun JsonObject.getString(key: String): String? {
    return try {
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

private fun formatOutput(output: List<JsonObject>): String {
    return output.joinToString("\n") { obj ->
        try {
            obj["text"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        } ?: obj.entries.joinToString(", ") { (key, value) ->
            val content = try {
                value.jsonPrimitive.content
            } catch (_: Exception) {
                value.toString()
            }
            "$key=$content"
        }
    }
}

private fun formatInputAsText(input: JsonObject): String {
    val skip = setOf("content", "command", "description", "file_path", "path", "old_file_path",
        "old_string", "new_string", "old", "new", "workdir", "timeout")
    return input.entries
        .filter { it.key !in skip }
        .joinToString("\n") { (key, value) ->
            val content = try { value.jsonPrimitive.content } catch (_: Exception) { value.toString() }
            "$key=$content"
        }
}

private fun toolKindLabel(kind: ToolKind): String = when (kind) {
    ToolKind.EXECUTE -> "Shell"
    ToolKind.EDIT -> "Edit"
    ToolKind.READ -> "Read"
    ToolKind.SEARCH -> "Search"
    ToolKind.DELETE -> "Delete"
    ToolKind.MOVE -> "Move"
    ToolKind.FETCH -> "Fetch"
    ToolKind.THINK -> "Thinking"
    ToolKind.SWITCH_MODE -> "Switch"
    ToolKind.OTHER -> "Tool"
}

private fun toolKindColor(kind: ToolKind): Color = when (kind) {
    ToolKind.EXECUTE -> Color(0xFF3574F0)
    ToolKind.EDIT -> Color(0xFF7EE787)
    ToolKind.READ -> Color(0xFFBBBBBB)
    ToolKind.SEARCH -> Color(0xFFF0C674)
    ToolKind.DELETE -> Color(0xFFFF7B72)
    ToolKind.MOVE -> Color(0xFFBBBBBB)
    ToolKind.FETCH -> Color(0xFFF0C674)
    ToolKind.THINK -> Color(0xFF9E9E9E)
    ToolKind.SWITCH_MODE -> Color(0xFF9E9E9E)
    ToolKind.OTHER -> Color(0xFF9E9E9E)
}
