package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.follow.EditorFollowManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun ToolPill(
    pill: ToolCallPill,
    modifier: Modifier = Modifier,
    getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null,
) {
    // Default expanded from settings — task pills use dedicated setting, others use ToolKind setting
    val settings = OpenCodeSettingsState.getInstance()
    val defaultExpanded = if (pill.toolName == "task") settings.expandTaskPillsByDefault
        else settings.isToolKindDefaultExpanded(pill.kind)
    var expanded by remember { mutableStateOf(defaultExpanded) }

    val isTask = pill.toolName == "task"
    val childSessionId = if (isTask) {
        try { pill.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
    } else null
    val taskAgentName = if (isTask) {
        pill.input?.getString("subagent_type") ?: pill.input?.getString("description") ?: "subagent"
    } else null

    // Observe child session streaming text for task pills
    val childStreamingText = if (isTask && childSessionId != null && getStreamingText != null) {
        getStreamingText(childSessionId)?.collectAsState()?.value
    } else null

    val iconKey = when {
        isTask && pill.status == ToolCallStatus.IN_PROGRESS -> AllIconsKeys.Actions.Execute
        isTask && pill.status == ToolCallStatus.COMPLETED -> AllIconsKeys.Actions.Checked
        isTask && pill.status == ToolCallStatus.FAILED -> AllIconsKeys.Actions.Cancel
        pill.status == ToolCallStatus.PENDING -> AllIconsKeys.Actions.Lightning
        pill.status == ToolCallStatus.IN_PROGRESS -> AllIconsKeys.Actions.Execute
        pill.status == ToolCallStatus.COMPLETED -> AllIconsKeys.Actions.Checked
        pill.status == ToolCallStatus.FAILED -> AllIconsKeys.Actions.Cancel
        else -> AllIconsKeys.Actions.Execute
    }

    val accentColor = if (isTask) ChatTheme.colors.component.taskAccent else toolKindColor(pill.kind)
    val kindLabel = if (isTask) "Task" else toolKindLabel(pill.kind)
    // Task pills always have an expandable body; other pills only if they have input/output
    val hasDetails = isTask || pill.input != null || pill.output != null

    // Resolve display data
    val fileName = remember(pill.kind, pill.input) { resolveFileName(pill) }
    val description = remember(pill.kind, pill.input) { resolveDescription(pill) }
    val lineDelta = remember(pill.kind, pill.input) { computeLineDelta(pill) }

    // ── Unified container — header + expanded body as one visual element ──
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(ChatTheme.shapes.toolPillCornerRadius)
            .background(ChatTheme.colors.accent.pillContainerBg)
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
                    .width(ChatTheme.dims.toolAccentStripWidth)
                    .height(ChatTheme.dims.toolAccentStripHeight)
                    .clip(ChatTheme.shapes.toolAccentCornerRadius)
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
                fontSize = ChatTheme.fonts.toolKindLabel,
                fontWeight = ChatTheme.fontWeights.toolKindLabel,
            )
            Spacer(Modifier.width(6.dp))

            // Filename or description (for task pills, show agent name)
            val headerText = if (isTask) "@${taskAgentName?.replaceFirstChar { it.uppercase() }}" else (fileName ?: description)
            Text(
                text = headerText,
                color = ChatTheme.colors.tool.read,
                fontSize = ChatTheme.fonts.toolFileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            // Open-in-editor icon button (Follow Agent).
            // Shown whenever the pill has a resolvable file path. Disabled (no-op)
            // when Follow Agent is off — keeps the affordance discoverable while
            // preventing accidental file opens from a feature the user hasn't opted into.
            val followEnabled = remember {
                OpenCodeSettingsState.getInstance().followAgentEnabled
            }
            val openInEditorPath = remember(pill.kind, pill.input) {
                pill.input?.let { input ->
                    val path = input.getString("file_path")
                        ?: input.getString("filePath")
                        ?: input.getString("path")
                    path?.takeIf { it.isNotBlank() }
                }
            }
            if (openInEditorPath != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    key = AllIconsKeys.Actions.EditSource,
                    contentDescription = if (followEnabled) "Open in editor" else "Follow Agent disabled",
                    modifier = Modifier
                        .size(20.dp)
                        .padding(2.dp)
                        .clickable(enabled = followEnabled) {
                            val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                            if (project != null) {
                                val line = try {
                                    pill.input?.get("offset")?.jsonPrimitive?.intOrNull ?: 0
                                } catch (_: Exception) { 0 }
                                EditorFollowManager.getInstance(project).openFileAtLine(
                                    project = project,
                                    filePath = openInEditorPath,
                                    line = line,
                                    focus = true,
                                )
                            }
                        },
                    tint = if (followEnabled) ChatTheme.colors.component.taskRunning
                           else ChatTheme.colors.component.taskPending,
                )
            }

            // Line delta (+N / -N)
            if (lineDelta != null) {
                Spacer(Modifier.width(6.dp))
                if (lineDelta.first > 0) {
                    Text(
                        text = "+${lineDelta.first}",
                        color = ChatTheme.colors.tool.edit,
                        fontSize = ChatTheme.fonts.toolLineDelta,
                        fontWeight = ChatTheme.fontWeights.toolLineDelta,
                    )
                }
                if (lineDelta.second > 0) {
                    if (lineDelta.first > 0) Spacer(Modifier.width(4.dp))
                    Text(
                        text = "-${lineDelta.second}",
                        color = ChatTheme.colors.tool.delete,
                        fontSize = ChatTheme.fonts.toolLineDelta,
                        fontWeight = ChatTheme.fontWeights.toolLineDelta,
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
                    tint = ChatTheme.colors.component.taskRunning,
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
                if (isTask) {
                    // Task pill: status + child session streaming text
                    when (pill.status) {
                        ToolCallStatus.IN_PROGRESS -> {
                            // Show real-time child session text if available, else prompt/description
                            if (!childStreamingText.isNullOrBlank()) {
                                ChatFencedCodeBlock(content = childStreamingText, language = "text")
                            } else {
                                val desc = pill.input?.getString("description") ?: pill.title.takeIf { it != "task" } ?: ""
                                val prompt = pill.input?.getString("prompt") ?: ""
                                if (prompt.isNotBlank()) {
                                    Text(
                                        text = prompt.take(500),
                                        color = ChatTheme.colors.component.taskCompleted,
                                        fontSize = ChatTheme.fonts.toolTaskDescription,
                                        maxLines = 10,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                } else if (desc.isNotBlank()) {
                                    Text(
                                        text = desc,
                                        color = ChatTheme.colors.component.taskCompleted,
                                        fontSize = ChatTheme.fonts.toolTaskDescription,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                } else {
                                    Text("Running task…", color = ChatTheme.colors.component.taskRunning, fontSize = ChatTheme.fonts.toolTaskStatus)
                                }
                            }
                        }
                        ToolCallStatus.COMPLETED -> {
                            // Show child session text if available, else output, else description
                            val taskOutput = if (!childStreamingText.isNullOrBlank()) childStreamingText else formatTaskOutput(pill.output)
                            if (!taskOutput.isNullOrBlank()) {
                                ChatFencedCodeBlock(content = taskOutput, language = "text")
                            } else {
                                val desc = pill.input?.getString("description") ?: pill.title.takeIf { it != "task" } ?: ""
                                if (desc.isNotBlank()) {
                                    Text(
                                        text = desc,
                                        color = ChatTheme.colors.tool.read,
                                        fontSize = ChatTheme.fonts.toolTaskDescription,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                } else {
                                    Text("Task completed", color = ChatTheme.colors.component.taskRunning, fontSize = ChatTheme.fonts.toolTaskStatus)
                                }
                            }
                        }
                        ToolCallStatus.FAILED -> {
                            val taskOutput = if (!childStreamingText.isNullOrBlank()) childStreamingText else formatTaskOutput(pill.output)
                            if (!taskOutput.isNullOrBlank()) {
                                ChatFencedCodeBlock(content = taskOutput, language = "text")
                            } else {
                                Text("Task failed", color = ChatTheme.colors.component.taskFailed, fontSize = ChatTheme.fonts.toolTaskStatus)
                            }
                        }
                        else -> {
                            Text("Task pending…", color = ChatTheme.colors.component.taskPending, fontSize = ChatTheme.fonts.toolTaskStatus)
                        }
                    }
                } else {
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

/** Format task tool output by extracting text from the result content.
 *  The server returns output as a list of JsonObject parts, each with a "text" field.
 *  We extract and concatenate the text content, stripping XML task wrapper tags.
 */
private fun formatTaskOutput(output: List<kotlinx.serialization.json.JsonObject>?): String? {
    if (output.isNullOrEmpty()) return null
    val sb = StringBuilder()
    for (part in output) {
        val text = part["text"]?.jsonPrimitive?.contentOrNull ?: continue
        sb.append(text)
    }
    var text = sb.toString().trim()
    if (text.isBlank()) return null
    // Strip <task ...> and <task_result>...</task_result></task> wrappers
    val taskMatch = Regex("<task[^>]*>\\s*<task_result>\\s*([\\s\\S]*?)\\s*</task_result>\\s*</task>", RegexOption.MULTILINE)
        .find(text)
    if (taskMatch != null) {
        text = taskMatch.groupValues[1].trim()
    } else {
        // Try removing just <task_result>...</task_result>
        val resultMatch = Regex("<task_result>\\s*([\\s\\S]*?)\\s*</task_result>", RegexOption.MULTILINE)
            .find(text)
        if (resultMatch != null) {
            text = resultMatch.groupValues[1].trim()
        }
    }
    return text.ifBlank { null }
}

@Composable
private fun toolKindColor(kind: ToolKind): Color = when (kind) {
    ToolKind.EXECUTE -> ChatTheme.colors.tool.execute
    ToolKind.EDIT -> ChatTheme.colors.tool.edit
    ToolKind.READ -> ChatTheme.colors.tool.read
    ToolKind.SEARCH -> ChatTheme.colors.tool.search
    ToolKind.DELETE -> ChatTheme.colors.tool.delete
    ToolKind.MOVE -> ChatTheme.colors.tool.move
    ToolKind.FETCH -> ChatTheme.colors.tool.fetch
    ToolKind.THINK -> ChatTheme.colors.tool.think
    ToolKind.SWITCH_MODE -> ChatTheme.colors.tool.switchMode
    ToolKind.OTHER -> ChatTheme.colors.tool.other
}
