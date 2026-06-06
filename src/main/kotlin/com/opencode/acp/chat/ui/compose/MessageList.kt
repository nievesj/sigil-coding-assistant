@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.SubagentRef
import com.opencode.acp.chat.model.SubagentStatus
import com.opencode.acp.chat.processor.MessageProcessorManager
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.foundation.theme.JewelTheme

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    project: Project? = null,
    onSubagentClick: ((String) -> Unit)? = null,
    onImagePreview: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        reverseLayout = true,
    ) {
        items(
            count = messages.size,
            key = { index -> messages[messages.size - 1 - index].id }
        ) { index ->
            MessageItem(messages[messages.size - 1 - index], project, onSubagentClick, onImagePreview)
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, project: Project? = null, onSubagentClick: ((String) -> Unit)? = null, onImagePreview: ((String) -> Unit)? = null) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, onImagePreview)
        MessageRole.ASSISTANT -> AssistantMessage(message, project, onSubagentClick)
    }
}

@Composable
fun UserMessage(message: ChatMessage, onImagePreview: ((String) -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        // Display attached images if any
        if (message.attachedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.attachedFiles.forEach { file ->
                    if (file.mime.startsWith("image/")) {
                        val bitmap = remember(file.dataUri) { decodeDataUriToBitmap(file.dataUri) }
                        if (bitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF3E3E3E))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onImagePreview?.invoke(file.dataUri) }
                            ) {
                                ComposeImage(
                                    bitmap = bitmap,
                                    contentDescription = file.name,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Display text content if any
        val textContent = message.parts.values.filterIsInstance<MessagePart.Text>().joinToString("") { it.content }
        if (textContent.isNotBlank()) {
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFF3574F0).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                org.jetbrains.jewel.ui.component.Text(textContent)
            }
        }
    }
}

@Composable
fun AssistantMessage(message: ChatMessage, project: Project? = null, onSubagentClick: ((String) -> Unit)? = null) {
    // Streaming fade-in: text starts at reduced alpha and smoothly transitions to full when complete
    val streamingAlpha by animateFloatAsState(
        targetValue = if (message.isStreaming) 0.6f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "streamingAlpha",
    )

    // Extract parts by type for rendering in fixed order
    val thinkingParts = message.parts.values.filterIsInstance<MessagePart.Thinking>()
    val toolCallParts = message.parts.values.filterIsInstance<MessagePart.ToolCall>()
    val textParts = message.parts.values.filter { it is MessagePart.Text || it is MessagePart.Code || it is MessagePart.Table }
    val fileChangeParts = message.parts.values.filterIsInstance<MessagePart.FileChange>()
    val subagentParts = message.parts.values.filterIsInstance<MessagePart.Subagent>()
    val errorParts = message.parts.values.filterIsInstance<MessagePart.Error>()
    val patchParts = message.parts.values.filterIsInstance<MessagePart.Patch>()
    val agentParts = message.parts.values.filterIsInstance<MessagePart.Agent>()
    val stepFinishParts = message.parts.values.filterIsInstance<MessagePart.StepFinish>()
    val retryParts = message.parts.values.filterIsInstance<MessagePart.Retry>()
    val compactionParts = message.parts.values.filterIsInstance<MessagePart.Compaction>()
    val assistantFileParts = message.parts.values.filterIsInstance<MessagePart.AssistantFile>()
    val assistantImageParts = message.parts.values.filterIsInstance<MessagePart.Image>()

    Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = streamingAlpha }) {
        // Rendering order: Thinking → ToolCall → Agent → Text → Patch → File/Image → FileChange → Subagent → StepFinish → Retry → Compaction → Error
        thinkingParts.forEach { ThinkingPill(it.content) }
        toolCallParts.forEach { key(it.pill.toolCallId) { ToolPill(it.pill) } }
        agentParts.forEach { AgentBadge(it.name) }
        RenderTextContent(textParts, project)
        // Show thinking indicator when streaming with no content yet
        if (message.isStreaming && textParts.isEmpty() && thinkingParts.isEmpty() && toolCallParts.isEmpty()) {
            ThinkingIndicator()
        }
        RenderPatchCards(patchParts)
        RenderAssistantFileCards(assistantFileParts)
        RenderAssistantImageCards(assistantImageParts)
        RenderFileChanges(fileChangeParts, project)
        RenderSubagentSessions(subagentParts, onSubagentClick)
        stepFinishParts.forEach { StepFinishPill(it) }
        retryParts.forEach { RetryPill(it.attempt, it.maxAttempts, it.error) }
        compactionParts.forEach { CompactionPill(it.summary) }
        RenderErrorParts(errorParts)
    }
}

// ── Helper Composables for AssistantMessage ──────────────────────────────────

/**
 * Renders Text, Code, and Table message parts with Jewel's Markdown composable.
 */
@Composable
private fun RenderTextContent(textParts: List<MessagePart>, project: Project?) {
    if (textParts.isEmpty()) return

    val currentProject = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()

    val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().state
    val defaultGreen = Color(0xFF6BBE50)
    val inlineCodeColor = parseColorOrDefault(settings.inlineCodeColor, defaultGreen)
    val listNumberColor = parseColorOrDefault(settings.listNumberColor, defaultGreen)

    val customInlinesStyling = remember(inlineCodeColor) {
        val linkColor = Color(0xFF3574F0)
        InlinesStyling.create(
            inlineCode = SpanStyle(
                color = inlineCodeColor,
                background = Color.Transparent,
                fontFamily = FontFamily.Monospace,
            ),
            link = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
            linkHovered = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
        )
    }

    val markdownStyling = remember(customInlinesStyling, listNumberColor) {
        val baseTextStyle = org.jetbrains.jewel.bridge.theme.retrieveDefaultTextStyle()
        MarkdownStyling.create(
            inlinesStyling = customInlinesStyling,
            heading = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.create(
                h1 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H1.create(
                    inlinesStyling = customInlinesStyling,
                ),
                h2 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H2.create(
                    inlinesStyling = customInlinesStyling,
                ),
                h3 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H3.create(
                    inlinesStyling = customInlinesStyling,
                ),
                h4 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H4.create(
                    inlinesStyling = customInlinesStyling,
                ),
                h5 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H5.create(
                    inlinesStyling = customInlinesStyling,
                ),
                h6 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H6.create(
                    inlinesStyling = customInlinesStyling,
                ),
            ),
            list = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.create(
                ordered = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.create(
                    numberStyle = TextStyle(color = listNumberColor, fontSize = 14.sp),
                    numberFormatStyles = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.NumberFormatStyles(
                        firstLevel = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.NumberFormatStyles.NumberFormatStyle.Decimal,
                        secondLevel = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.NumberFormatStyles.NumberFormatStyle.Decimal,
                        thirdLevel = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.NumberFormatStyles.NumberFormatStyle.Decimal,
                    ),
                ),
            ),
            blockQuote = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.BlockQuote.create(
                padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                lineWidth = 3.dp,
                lineColor = Color(0xFF3E3E3E),
                textColor = Color(0xFF9E9E9E),
            ),
        )
    }
    val markdownProcessor = remember { MarkdownProcessor() }
    val codeHighlighter = remember(currentProject) {
        currentProject?.let {
            try {
                org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory.getInstance(it).createHighlighter()
            } catch (_: Exception) { NoOpCodeHighlighter }
        } ?: NoOpCodeHighlighter
    }

    ProvideMarkdownStyling(
        markdownStyling = markdownStyling,
        markdownProcessor = markdownProcessor,
        codeHighlighter = codeHighlighter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            textParts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> {
                        val parsedBlocks = remember(part.content) {
                            val raw = markdownProcessor.processMarkdownDocument(part.content)
                            clampOrderedLists(raw)
                        }
                        Markdown(
                            markdownBlocks = parsedBlocks,
                            markdown = part.content,
                            modifier = Modifier.fillMaxWidth(),
                            selectable = true,
                            onUrlClick = { url -> BrowserUtil.open(url) },
                        )
                    }
                    is MessagePart.Code -> {
                        ChatFencedCodeBlock(
                            content = part.content,
                            language = part.language,
                        )
                    }
                    is MessagePart.Table -> {
                        ChatTable(
                            rawMarkdown = part.rawMarkdown,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {} // Should not happen given the filter
                }
            }
        }
    }
}

@Composable
private fun RenderFileChanges(fileChangeParts: List<MessagePart.FileChange>, project: Project?) {
    if (fileChangeParts.isEmpty()) return
    FileChangesList(
        changes = fileChangeParts.map { it.change },
        project = project,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RenderSubagentSessions(subagentParts: List<MessagePart.Subagent>, onSubagentClick: ((String) -> Unit)?) {
    if (subagentParts.isEmpty()) return
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        subagentParts.forEach { part ->
            SubagentSessionBar(
                ref = part.ref,
                onClick = { onSubagentClick?.invoke(it) },
            )
        }
    }
}

@Composable
private fun RenderErrorParts(errorParts: List<MessagePart.Error>) {
    errorParts.forEach { part ->
        val errorText = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Error: ") }
            append(part.message)
        }
        Text(
            text = errorText,
            color = Color(0xFFFF7B72),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AgentBadge(name: String) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = name,
            style = TextStyle(
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .background(
                    color = Color(0xFF3E3E3E),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RenderPatchCards(patchParts: List<MessagePart.Patch>) {
    if (patchParts.isEmpty()) return
    patchParts.forEach { patch ->
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth()
                .background(
                    color = Color(0xFF2D2D2D),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "📄 ${patch.files.size} changed file${if (patch.files.size != 1) "s" else ""}",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                    fontWeight = FontWeight.Medium,
                ),
            )
            patch.files.forEach { filePath ->
                val fileName = filePath.substringAfterLast('/')
                Text(
                    text = "  $fileName",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color(0xFFBBBBBB),
                    ),
                )
            }
        }
    }
}

@Composable
private fun StepFinishPill(stepFinish: MessagePart.StepFinish) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tokenParts = buildList {
            stepFinish.inputTokens?.let { add("${formatTokenCount(it)} in") }
            stepFinish.outputTokens?.let { add("${formatTokenCount(it)} out") }
            stepFinish.reasoningTokens?.let { add("${formatTokenCount(it)} reasoning") }
            stepFinish.totalCost?.let { add("$${"%.4f".format(it)}") }
        }
        val label = if (tokenParts.isNotEmpty()) {
            "Step — ${tokenParts.joinToString(" / ")}"
        } else if (stepFinish.snapshot != null) {
            "Step completed"
        } else {
            return
        }
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                color = Color(0xFF7E7E7E),
            ),
        )
    }
}

private fun formatTokenCount(count: Long): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M"
    count >= 1_000 -> "${"%.1f".format(count / 1_000.0)}k"
    else -> count.toString()
}

@Composable
private fun RetryPill(attempt: Int, maxAttempts: Int, error: String?) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                color = Color(0xFF5C4A00),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "↻ Retry $attempt/$maxAttempts",
            style = TextStyle(
                fontSize = 11.sp,
                color = Color(0xFFFFD666),
                fontWeight = FontWeight.Medium,
            ),
        )
        if (error != null) {
            Text(
                text = "— $error",
                style = TextStyle(
                    fontSize = 10.sp,
                    color = Color(0xFFCCAA44),
                ),
            )
        }
    }
}

@Composable
private fun RenderAssistantFileCards(parts: List<MessagePart.AssistantFile>) {
    if (parts.isEmpty()) return
    parts.forEach { file ->
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .fillMaxWidth()
                .background(
                    color = Color(0xFF2D2D2D),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "📎",
                style = TextStyle(fontSize = 12.sp),
            )
            Column {
                Text(
                    text = file.filename ?: "file",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFFBBBBBB),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = file.mime,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = Color(0xFF7E7E7E),
                    ),
                )
            }
        }
    }
}

@Composable
private fun RenderAssistantImageCards(parts: List<MessagePart.Image>) {
    if (parts.isEmpty()) return
    parts.forEach { image ->
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .fillMaxWidth()
                .background(
                    color = Color(0xFF2D2D2D),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "🖼️",
                style = TextStyle(fontSize = 12.sp),
            )
            Column {
                Text(
                    text = image.filename ?: "image",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFFBBBBBB),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = image.mime,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = Color(0xFF7E7E7E),
                    ),
                )
            }
        }
    }
}

@Composable
private fun CompactionPill(summary: String?) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                color = Color(0xFF1A2A1A),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "📎 Context compacted${if (summary != null) " — $summary" else ""}",
            style = TextStyle(
                fontSize = 11.sp,
                color = Color(0xFF7EBF7E),
            ),
        )
    }
}

// ── File Changes Inline Display ──────────────────────────────────────────────

/**
 * Renders a list of files modified by tool calls in the assistant message.
 * Each card shows: file icon, name, path, +X/-Y counts.
 * Clicking a file opens the diff; the target icon opens the file directly.
 */
@Composable
private fun FileChangesList(
    changes: List<ChatFileChange>,
    project: Project?,
    modifier: Modifier = Modifier,
) {
    val addedColor = Color(0xFF7EE787)
    val deletedColor = Color(0xFFFF7B72)
    val pathColor = retrieveColorOrUnspecified("Link.activeForeground").copy(alpha = 0.5f)
    val headerColor = retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.55f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header label
        Text(
            text = "Modified files",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = headerColor,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        changes.forEach { change ->
            FileChangeCard(
                change = change,
                project = project,
                addedColor = addedColor,
                deletedColor = deletedColor,
                pathColor = pathColor,
            )
        }
    }
}

@Composable
private fun FileChangeCard(
    change: ChatFileChange,
    project: Project?,
    addedColor: Color,
    deletedColor: Color,
    pathColor: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBg = retrieveColorOrUnspecified("MenuItem.selectionBackground")
    val normalColor = JewelTheme.globalColors.text.normal

    // Resolve file type icon
    val fileIconKey = remember(change.fileName) {
        val ext = change.fileName.substringAfterLast('.', "").lowercase()
        val icon = when (ext) {
            "kt", "kts" -> AllIcons.Language.Kotlin
            "java" -> AllIcons.FileTypes.Java
            "xml" -> AllIcons.FileTypes.Xml
            "json" -> AllIcons.FileTypes.Json
            "yaml", "yml" -> AllIcons.FileTypes.Yaml
            "md", "txt", "properties", "gitignore" -> AllIcons.FileTypes.Text
            "js", "jsx", "ts", "tsx" -> AllIcons.FileTypes.JavaScript
            "css" -> AllIcons.FileTypes.Css
            "html", "htm" -> AllIcons.FileTypes.Html
            "py" -> AllIcons.Language.Python
            "rb" -> AllIcons.Language.Ruby
            "rs" -> AllIcons.Language.Rust
            "go" -> AllIcons.Language.GO
            "scala" -> AllIcons.Language.Scala
            "php" -> AllIcons.Language.Php
            "gradle", "gradle.kts" -> AllIcons.Nodes.Folder
            "svg", "png", "jpg", "jpeg", "gif", "bmp", "webp" -> AllIcons.FileTypes.Image
            else -> AllIcons.FileTypes.Text
        }
        IntelliJIconKey.fromPlatformIcon(icon)
    }

    // Resolve virtual file for diff/open actions
    val projectResolved = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
    val virtualFile = remember(projectResolved, change.filePath) {
        projectResolved?.basePath?.let { basePath ->
            val absPath = "$basePath/${change.filePath}".replace('/', java.io.File.separatorChar)
            LocalFileSystem.getInstance().findFileByPath(absPath)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) hoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable {
                if (projectResolved != null && virtualFile != null) {
                    openDiffForPath(projectResolved, change.filePath, virtualFile)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File type icon
        Icon(
            key = fileIconKey,
            contentDescription = change.fileName,
            modifier = Modifier.size(16.dp),
            tint = Color.Unspecified,
        )
        Spacer(Modifier.width(8.dp))

        // File info column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.fileName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = normalColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = change.filePath,
                fontSize = 10.sp,
                color = pathColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Line delta (+X / -Y) — only show if we have actual counts
        if (change.additions > 0 || change.deletions > 0) {
            if (change.additions > 0) {
                Text(
                    text = "+${change.additions}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = addedColor,
                )
            }
            if (change.deletions > 0) {
                if (change.additions > 0) Spacer(Modifier.width(4.dp))
                Text(
                    text = "-${change.deletions}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = deletedColor,
                )
            }
        }

        // Open file target icon
        if (virtualFile != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Locate),
                contentDescription = "Open file",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { openFileInEditor(projectResolved!!, virtualFile) }
                    .padding(1.dp),
                tint = pathColor,
            )
        }
    }
}

/**
 * Parse a hex color string ("#RRGGBB" or "RRGGBB") to Compose Color.
 * Falls back to defaultColor if invalid or blank.
 */

// ── Subagent Session Bar ────────────────────────────────────────────────────

@Composable
private fun SubagentSessionBar(
    ref: SubagentRef,
    onClick: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tintBase = Color(0xFF3574F0)

    val bgColor = when (ref.status) {
        SubagentStatus.RUNNING -> Color(0xFF1A4D1A)
        SubagentStatus.COMPLETED -> Color(0xFF2B2B2B)
        SubagentStatus.FAILED -> Color(0xFF4D1A1A)
    }
    val borderColor = if (isHovered) tintBase.copy(alpha = 0.5f) else Color(0xFF3E3E3E)
    val textColor = when (ref.status) {
        SubagentStatus.RUNNING -> Color(0xFF7EE787)
        SubagentStatus.COMPLETED -> Color(0xFFCCCCCC)
        SubagentStatus.FAILED -> Color(0xFFFF7B72)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick(ref.sessionId) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Status dot
        val dotColor = when (ref.status) {
            SubagentStatus.RUNNING -> Color(0xFF7EE787)
            SubagentStatus.COMPLETED -> Color(0xFF808080)
            SubagentStatus.FAILED -> Color(0xFFFF7B72)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(dotColor)
        )
        // Agent name
        Text(
            text = ref.agentName,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
        // Description
        Text(
            text = ref.taskDescription,
            fontSize = 11.sp,
            color = textColor.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Clamp non-positive [MarkdownBlock.ListBlock.OrderedList.startFrom] values in a parsed
 * markdown block tree.
 *
 * All of Jewel's [NumberFormatStyle] implementations (Decimal, Roman, Alphabetical)
 * throw [IllegalArgumentException] for `number <= 0`. CommonMark allows `startFrom = 0`
 * (e.g. `0. item`), and nested lists can produce surprising `startFrom` values.
 * This function walks the block tree and replaces any [OrderedList] with
 * `startFrom <= 0` with a fresh instance using `startFrom = 1`.
 */
private fun clampOrderedLists(blocks: List<MarkdownBlock>): List<MarkdownBlock> =
    blocks.map { block -> clampBlock(block) }

private fun clampBlock(block: MarkdownBlock): MarkdownBlock = when (block) {
    is MarkdownBlock.ListBlock.OrderedList -> {
        val fixedChildren = block.children.map { clampListItem(it) }
        if (block.startFrom <= 0) {
            MarkdownBlock.ListBlock.OrderedList(fixedChildren, block.isTight, 1, block.delimiter)
        } else {
            MarkdownBlock.ListBlock.OrderedList(fixedChildren, block.isTight, block.startFrom, block.delimiter)
        }
    }
    is MarkdownBlock.ListBlock.UnorderedList -> {
        val fixedChildren = block.children.map { clampListItem(it) }
        MarkdownBlock.ListBlock.UnorderedList(fixedChildren, block.isTight, block.marker)
    }
    is MarkdownBlock.ListItem -> clampListItem(block)
    is MarkdownBlock.BlockQuote -> MarkdownBlock.BlockQuote(block.children.map { clampBlock(it) })
    else -> block
}

private fun clampListItem(item: MarkdownBlock.ListItem): MarkdownBlock.ListItem {
    val fixedChildren = item.children.map { clampBlock(it) }
    return MarkdownBlock.ListItem(fixedChildren, item.level)
}

private fun parseColorOrDefault(hex: String, defaultColor: Color): Color {
    if (hex.isBlank()) return defaultColor
    val clean = hex.removePrefix("#")
    return try {
        val argb = clean.toLong(16) or 0xFF000000
        Color(argb.toInt())
    } catch (_: Exception) {
        defaultColor
    }
}
