@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRenderPhase
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.model.renderPhase
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
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.foundation.theme.JewelTheme
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.util.decodeFileToBitmap
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    project: Project? = null,
    onImagePreview: ((String) -> Unit)? = null,
    getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null,
    queuedMessages: List<com.opencode.acp.chat.model.QueuedMessage> = emptyList(),
    onCancelQueuedMessage: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Auto-scroll state: starts ON, stays ON until user manually scrolls up.
    // Re-enabled by clicking jump-to-bottom button OR by sending a new message.
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // True while we are performing a programmatic scroll. Prevents the disable
    // detector from misinterpreting our own scrolls as user input. A Mutex is
    // used so only one programmatic scroll runs at a time and so the flag is
    // cleared only after the scroll has fully settled.
    val scrollMutex = remember { Mutex() }

    // Incremented to request a programmatic scroll. Multiple triggers (new
    // message, streaming content growth, jump-to-bottom click) all bump this.
    var scrollRequest by remember { mutableIntStateOf(0) }

    // Track previous message count to distinguish bulk loads (session switch)
    // from single new messages from streaming in-place growth.
    var prevMessageCount by remember { mutableIntStateOf(0) }

    // Pixel-based "at bottom" detection — more reliable than canScrollForward,
    // which toggles rapidly during streaming as content grows. This checks
    // that the last visible item IS the last item AND is fully visible (its
    // bottom edge is within the viewport). See:
    // https://github.com/gkd-kit/gkd/blob/main/app/src/main/kotlin/li/songe/gkd/ui/component/Hooks.kt
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            if (lastVisible == null) {
                info.totalItemsCount == 0 // empty list = "at bottom"
            } else {
                lastVisible.index == info.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <= info.viewportEndOffset
            }
        }
    }

    // Detect user scroll toward older messages (up). previousScrollIndex/Offset
    // are plain local vars (not Compose state) to avoid recompositions.
    LaunchedEffect(Unit) {
        var previousScrollIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (scrollMutex.isLocked) {
                // Programmatic scroll in progress — ignore.
                previousScrollIndex = index
                previousScrollOffset = offset
                return@collect
            }
            // Use pixel-based isAtBottom instead of canScrollForward — the latter
            // toggles rapidly during streaming, causing false "moved up" detection.
            if (isAtBottom) {
                autoScrollEnabled = true
            } else {
                val movedUp = index < previousScrollIndex ||
                    (index == previousScrollIndex && offset < previousScrollOffset)
                if (movedUp) {
                    autoScrollEnabled = false
                }
            }
            previousScrollIndex = index
            previousScrollOffset = offset
        }
    }

    // Re-enable auto-scroll when user scrolls back to the very bottom.
    // Uses pixel-based isAtBottom to avoid the canScrollForward toggle noise.
    LaunchedEffect(Unit) {
        snapshotFlow { isAtBottom }
            .collect { atBottom ->
                if (atBottom) autoScrollEnabled = true
            }
    }

    // Trigger scroll on content growth during streaming. Instead of watching
    // canScrollForward (which toggles on every content change, creating a
    // feedback loop), watch the last visible item's size. When it grows and
    // we're at the bottom, request a scroll. distinctUntilChanged prevents
    // re-triggering on the same size. See:
    // https://github.com/ggml-org/llama.cpp/commit/e58add7
    LaunchedEffect(Unit) {
        var lastHeight = 0
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            (lastItem?.index ?: -1) to (lastItem?.size ?: 0)
        }.collect { (lastIndex, lastSize) ->
            if (lastSize > lastHeight && lastIndex == listState.layoutInfo.totalItemsCount - 1 && autoScrollEnabled) {
                scrollRequest++
            }
            lastHeight = lastSize
        }
    }

    // Trigger scroll on new messages / queued messages.
    LaunchedEffect(messages.size, queuedMessages.size) {
        if (messages.isNotEmpty() || queuedMessages.isNotEmpty()) {
            val lastIsUser = messages.lastOrNull()?.role == MessageRole.USER
            val hasQueued = queuedMessages.isNotEmpty()
            if (lastIsUser || hasQueued) {
                autoScrollEnabled = true
            }
            if (autoScrollEnabled) {
                scrollRequest++
            }
        }
    }

    // Single scroll coordinator. Serializes all programmatic scrolls and keeps
    // the scroll guard active until the list has fully settled.
    //
    // Three scroll modes based on what triggered the request:
    // 1. Bulk load (session switch): messages.size jumps by >1 or from 0 →
    //    instant scrollToItem (no animation — history should appear at bottom)
    // 2. New message (messages.size grows by exactly 1): animateScrollToItem
    //    (gentle glide when a new message bubble appears)
    // 3. Streaming growth (messages.size unchanged, content grew in-place):
    //    instant scrollToItem (no animation — the content growth IS the motion;
    //    animating creates a feedback loop where the animation fights content growth)
    LaunchedEffect(scrollRequest) {
        if (!autoScrollEnabled) return@LaunchedEffect
        val totalItems = messages.size + queuedMessages.size + if (queuedMessages.isNotEmpty() && messages.isNotEmpty()) 1 else 0
        if (totalItems <= 0) return@LaunchedEffect

        // Determine scroll mode by comparing message count delta.
        val messageDelta = messages.size - prevMessageCount
        prevMessageCount = messages.size
        val isBulkLoad = messageDelta > 1 || (prevMessageCount == messages.size && messages.size > 1 && scrollRequest == 1)
        // isBulkLoad: session switch loaded N messages at once (delta > 1),
        // or first render with existing messages (prevCount=0, now >1).
        // Treat delta 0 (streaming growth) and delta 1 (single new message) as
        // non-bulk. delta 1 gets animation; delta 0 gets instant.
        val isStreamingGrowth = messageDelta == 0

        scrollMutex.withLock {
            try {
                if (isBulkLoad || isStreamingGrowth) {
                    // Instant snap — no animation. For bulk loads, history appears
                    // at the bottom immediately. For streaming, the content growth
                    // is the motion; animating creates a feedback loop.
                    // Int.MAX_VALUE offset forces scroll to the very bottom of the
                    // last item (required with Arrangement.Bottom).
                    listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
                } else {
                    // New message appeared (delta == 1) — gentle animated glide.
                    // Short coalesce delay so rapid size changes merge into one
                    // animation. 60ms is short enough to feel responsive.
                    delay(60)
                    listState.animateScrollToItem(totalItems - 1, Int.MAX_VALUE)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.debug(e) { "[ACP] scroll failed, retrying without offset" }
                try {
                    if (isBulkLoad || isStreamingGrowth) {
                        listState.scrollToItem(totalItems - 1)
                    } else {
                        listState.animateScrollToItem(totalItems - 1)
                    }
                } catch (e2: Exception) {
                    if (e2 is kotlinx.coroutines.CancellationException) throw e2
                    logger.debug(e2) { "[ACP] scroll fallback to instant scrollToItem" }
                    listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
                }
            }
            // Wait for the scroll to fully settle before releasing the mutex.
            // This prevents the disable detector from seeing isScrollInProgress
            // with the guard already cleared.
            snapshotFlow { listState.isScrollInProgress }
                .first { !it }
        }
        // Wait one more frame after the scroll settles so the disable detector
        // sees the settled state with the guard still active. This is what
        // prevents programmatic scrolls from being misinterpreted as user input.
        withFrameNanos { }
    }

    Box(modifier = modifier) {
        SelectionContainer {
            // Arrangement.Bottom anchors items to the BOTTOM of the viewport.
            // fillMaxSize() is CRITICAL — without it, the LazyColumn wraps content
            // height and Arrangement.Bottom has no effect.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp, start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                items(
                    count = messages.size,
                    key = { index ->
                        val m = messages[index]
                        // Include parts count and streaming state in key so LazyColumn
                        // detects data changes (new tool calls, thinking completed, etc.)
                        // and recreates the composition instead of reusing stale data.
                        "${m.id}_${m.parts.size}_${m.isStreaming}"
                    }
                ) { index ->
                    MessageItem(messages[index], project, onImagePreview, getStreamingText = getStreamingText)
                    if (index < messages.size - 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // Queued messages — rendered at the bottom of the chat as user-style bubbles
                if (queuedMessages.isNotEmpty()) {
                    // Spacer between last real message and queued messages
                    if (messages.isNotEmpty()) {
                        item(key = "queue_spacer") {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    items(
                        count = queuedMessages.size,
                        key = { index -> "queued_${queuedMessages[index].id}" }
                    ) { index ->
                        QueuedMessageBubble(
                            message = queuedMessages[index],
                            onCancel = { onCancelQueuedMessage?.invoke(queuedMessages[index].id) }
                        )
                        if (index < queuedMessages.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // Scrollbar OUTSIDE SelectionContainer — SelectionContainer consumes pointer events,
        // so the scrollbar must be a sibling, not a child.
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
            scrollState = listState,
        )

        // Jump to bottom button — always visible when there are messages.
        // Bright when scrolled up (actionable), dimmed when already at bottom.
        if (messages.isNotEmpty()) {
            val buttonBg = if (isAtBottom) ChatTheme.colors.text.muted.copy(alpha = 0.3f) else ChatTheme.colors.accent.blue
            val iconTint = if (isAtBottom) ChatTheme.colors.text.muted.copy(alpha = 0.6f) else Color.White
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(36.dp)
                    .background(
                        color = buttonBg,
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = if (isAtBottom) ChatTheme.colors.text.muted.copy(alpha = 0.2f) else ChatTheme.colors.accent.userAvatarFill,
                        shape = CircleShape
                    )
                    .clickable {
                        autoScrollEnabled = true
                        scrollRequest++
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = "Jump to bottom",
                    modifier = Modifier.size(16.dp),
                    tint = iconTint
                )
            }
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, project: Project? = null, onImagePreview: ((String) -> Unit)? = null, getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, onImagePreview)
        MessageRole.ASSISTANT -> AssistantMessage(message, project, getStreamingText = getStreamingText)
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
                        var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                        LaunchedEffect(file.path) {
                            bitmap = withContext(Dispatchers.IO) { decodeFileToBitmap(file.path) }
                        }
                        bitmap?.let { bm ->
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ChatTheme.colors.border.default)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onImagePreview?.invoke(file.path) }
                            ) {
                                ComposeImage(
                                    bitmap = bm,
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
                    .heightIn(min = ChatTheme.dims.toolAccentStripHeight)
                    .background(
                        color = ChatTheme.colors.border.selectionBg,
                        shape = ChatTheme.shapes.messageBubbleCornerRadius
                    )
                    .padding(horizontal = ChatTheme.dims.messagePaddingH, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                org.jetbrains.jewel.ui.component.Text(
                    text = textContent,
                    fontSize = ChatTheme.fonts.toolKindLabel,
                    fontWeight = ChatTheme.fontWeights.toolKindLabel,
                )
            }
        }
    }
}

@Composable
fun AssistantMessage(message: ChatMessage, project: Project? = null, getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null) {
     val streamingAlpha = if (message.isStreaming) 0.85f else 1f

     // Set up markdown styling once for the entire message (needed by Text and Code parts)
     val currentProject = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
     val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().state
     val defaultGreen = ChatTheme.colors.accent.green
     val inlineCodeColor = parseColorOrDefault(settings.inlineCodeColor, defaultGreen)
     val listNumberColor = parseColorOrDefault(settings.listNumberColor, defaultGreen)
     val linkColor = ChatTheme.colors.accent.blue
     val customInlinesStyling = remember(inlineCodeColor, linkColor) {
         InlinesStyling.create(
             inlineCode = SpanStyle(color = inlineCodeColor, background = Color.Transparent, fontFamily = FontFamily.Monospace),
             link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
             linkHovered = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
         )
     }
     val blockLineColor = ChatTheme.colors.accent.blockQuoteLine
     val blockTextColor = ChatTheme.colors.accent.blockQuoteText
     val orderedListFontSize = ChatTheme.fonts.messageOrderedListItem
     val markdownStyling = remember(customInlinesStyling, listNumberColor, blockLineColor, blockTextColor, orderedListFontSize) {
         val baseTextStyle = org.jetbrains.jewel.bridge.theme.retrieveDefaultTextStyle()
         MarkdownStyling.create(
             inlinesStyling = customInlinesStyling,
             heading = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.create(
                 h1 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H1.create(inlinesStyling = customInlinesStyling),
                 h2 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H2.create(inlinesStyling = customInlinesStyling),
                 h3 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H3.create(inlinesStyling = customInlinesStyling),
                 h4 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H4.create(inlinesStyling = customInlinesStyling),
                 h5 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H5.create(inlinesStyling = customInlinesStyling),
                 h6 = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.Heading.H6.create(inlinesStyling = customInlinesStyling),
             ),
             list = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.create(
                 ordered = org.jetbrains.jewel.markdown.rendering.MarkdownStyling.List.Ordered.create(
                      numberStyle = TextStyle(color = listNumberColor, fontSize = orderedListFontSize),
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
                 lineColor = blockLineColor,
                 textColor = blockTextColor,
             ),
         )
     }
     val markdownProcessor = remember { MarkdownProcessor() }
     val codeHighlighter = remember(currentProject) {
         currentProject?.let {
             try { org.jetbrains.jewel.bridge.code.highlighting.CodeHighlighterFactory.getInstance(it).createHighlighter() }
             catch (_: Exception) { NoOpCodeHighlighter }
         } ?: NoOpCodeHighlighter
     }

     ProvideMarkdownStyling(
         markdownStyling = markdownStyling,
         markdownProcessor = markdownProcessor,
         codeHighlighter = codeHighlighter,
     ) {
          Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = streamingAlpha }) {
               // Render parts in LinkedHashMap insertion order — the order events arrived.
               // Pre-compute part presence to avoid LazyColumn stale-closure bugs
               val hasThinking = message.parts.values.any { it is MessagePart.Thinking }
               // Pre-compute: if message has any ToolCall parts, suppress Patch/FileChange cards
               // (ToolPill already shows file info, line counts, and expandable content)
               val hasToolCallInMessage = message.parts.values.any { it is MessagePart.ToolCall }
              var hasToolCall = hasToolCallInMessage
              // Render parts in LinkedHashMap insertion order — chronological event order.
              // resegmentTextPartsDirect preserves insertion positions via textSegments.
             for ((key, part) in message.parts.entries) {
                 when (part) {
                        is MessagePart.Thinking -> {
                            key(key) {
                               CollapsibleThinkingPill(
                                   content = part.content,
                                   state = part.state,
                               )
                           }
                       }
                       is MessagePart.ToolCall -> {
                           hasToolCall = true
                           key(key) { ToolPill(part.pill, getStreamingText = getStreamingText) }
                       }
                     is MessagePart.Text -> {
                         key(key) {
                             val parsedBlocks = remember(part.content) {
                                 val raw = markdownProcessor.processMarkdownDocument(part.content)
                                 clampOrderedLists(raw)
                             }
                             Markdown(
                                 markdownBlocks = parsedBlocks,
                                 markdown = part.content,
                                 modifier = Modifier.fillMaxWidth().padding(horizontal = ChatTheme.dims.messagePaddingH, vertical = ChatTheme.dims.messagePaddingV),
                                 selectable = true,
                                 onUrlClick = { url -> BrowserUtil.open(url) },
                             )
                         }
                     }
                      is MessagePart.Code -> key(key) {
                          Box(modifier = Modifier.padding(vertical = 8.dp)) {
                              ChatFencedCodeBlock(content = part.content, language = part.language)
                          }
                      }
                     is MessagePart.Table -> key(key) { ChatTable(rawMarkdown = part.rawMarkdown, modifier = Modifier.fillMaxWidth()) }
                     is MessagePart.Patch -> if (!hasToolCall) key(key) {
                         Column(
                             modifier = Modifier.padding(horizontal = 8.dp, vertical = ChatTheme.dims.messagePaddingV).fillMaxWidth()
                                 .background(color = ChatTheme.colors.surface.card, shape = ChatTheme.shapes.messageBubbleCornerRadius).padding(8.dp),
                             verticalArrangement = Arrangement.spacedBy(4.dp),
                              ) {
                             Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                 Icon(key = AllIconsKeys.Nodes.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = ChatTheme.colors.component.thinkingChevron)
                                 Text(text = "${part.files.size} changed file${if (part.files.size != 1) "s" else ""}", style = TextStyle(fontSize = ChatTheme.fonts.messagePatchFileCount, color = ChatTheme.colors.component.thinkingChevron, fontWeight = FontWeight.Medium))
                             }
                            part.files.forEach { filePath ->
                                Text(text = "  ${filePath.substringAfterLast('/')}", style = TextStyle(fontSize = ChatTheme.fonts.messagePatchFileName, color = ChatTheme.colors.component.attachmentRemoveIcon))
                             }
                         }
                     }
                     is MessagePart.Agent -> key(key) { AgentBadge(part.name) }
                     is MessagePart.StepFinish -> key(key) { StepFinishPill(part) }
                     is MessagePart.Retry -> key(key) { RetryPill(part.attempt, part.maxAttempts, part.error) }
                     is MessagePart.Compaction -> key(key) { CompactionPill(part.summary) }
                      is MessagePart.FileChange -> if (!hasToolCall) key(key) { FileChangeCard(change = part.change, project = project, addedColor = ChatTheme.colors.accent.codeAdded, deletedColor = ChatTheme.colors.accent.codeDeleted, pathColor = retrieveColorOrUnspecified("Link.activeForeground").copy(alpha = 0.5f)) }
                      is MessagePart.AssistantFile -> key(key) {
                         Row(
                             modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth()
                                 .background(color = ChatTheme.colors.surface.card, shape = ChatTheme.shapes.attachmentCornerRadius).padding(horizontal = 8.dp, vertical = 6.dp),
                             verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                          ) {
                              Icon(key = AllIconsKeys.FileTypes.Text, contentDescription = null, modifier = Modifier.size(14.dp), tint = ChatTheme.colors.component.attachmentRemoveIcon)
                              Column {
                                 Text(text = part.filename ?: "file", style = TextStyle(fontSize = ChatTheme.fonts.messageFileName, color = ChatTheme.colors.component.attachmentRemoveIcon, fontWeight = FontWeight.Medium))
                                 Text(text = part.mime, style = TextStyle(fontSize = ChatTheme.fonts.messageFileMime, color = ChatTheme.colors.component.mimeText))
                             }
                         }
                     }
                      is MessagePart.Image -> key(key) {
                         Row(
                             modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth()
                                 .background(color = ChatTheme.colors.surface.card, shape = ChatTheme.shapes.attachmentCornerRadius).padding(horizontal = 8.dp, vertical = 6.dp),
                             verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                          ) {
                              Icon(key = AllIconsKeys.FileTypes.Image, contentDescription = null, modifier = Modifier.size(14.dp), tint = ChatTheme.colors.component.attachmentRemoveIcon)
                              Column {
                                 Text(text = part.filename ?: "image", style = TextStyle(fontSize = ChatTheme.fonts.messageFileName, color = ChatTheme.colors.component.attachmentRemoveIcon, fontWeight = FontWeight.Medium))
                                 Text(text = part.mime, style = TextStyle(fontSize = ChatTheme.fonts.messageFileMime, color = ChatTheme.colors.component.mimeText))
                             }
                         }
                     }
                     is MessagePart.Error -> key(key) {
                         val errorText = buildAnnotatedString {
                             withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Error: ") }
                             append(part.message)
                         }
                         Text(text = errorText, color = ChatTheme.colors.accent.codeDeleted, modifier = Modifier.padding(horizontal = ChatTheme.dims.messagePaddingH, vertical = ChatTheme.dims.messagePaddingV))
                     }
                 }
              }
              // Show "Interrupted" banner when message was aborted by user
              if (message.state == MessageState.Aborted) {
                  Row(
                      modifier = Modifier.fillMaxWidth().padding(horizontal = ChatTheme.dims.messagePaddingH, vertical = 8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                      Spacer(Modifier.weight(1f).height(1.dp).background(ChatTheme.colors.component.interruptedDivider))
                      Text(
                          text = "Interrupted",
                          color = ChatTheme.colors.text.muted,
                          fontSize = ChatTheme.fonts.messageInterrupted,
                          modifier = Modifier.padding(horizontal = ChatTheme.dims.messagePaddingH),
                      )
                      Spacer(Modifier.weight(1f).height(1.dp).background(ChatTheme.colors.component.interruptedDivider))
                  }
              }
              // Show thinking indicator when streaming with no content yet.
              // Only show if no CollapsibleThinkingPill was rendered in the for-loop above
              // (hasThinking guard prevents duplicate indicators).
              when (message.renderPhase()) {
                  MessageRenderPhase.THINKING -> if (!hasThinking) ThinkingIndicator()
                  else -> { /* HAS_CONTENT or COMPLETE — no standalone indicator needed */ }
              }
         }
     }
}

// ── Helper Composables for AssistantMessage ──────────────────────────────────

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
                fontSize = ChatTheme.fonts.messageAgentBadge,
                color = ChatTheme.colors.component.thinkingChevron,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .background(
                    color = ChatTheme.colors.border.default,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
                fontSize = ChatTheme.fonts.messageStepFinish,
                color = ChatTheme.colors.component.mimeText,
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
                color = ChatTheme.colors.component.retryBg,
                shape = ChatTheme.shapes.retryPillCornerRadius
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "↻ Retry $attempt/$maxAttempts",
            style = TextStyle(
                fontSize = ChatTheme.fonts.messageAgentBadge,
                color = ChatTheme.colors.component.retryText,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (error != null) {
            Text(
                text = "— $error",
                style = TextStyle(
                    fontSize = ChatTheme.fonts.messageFileMime,
                    color = ChatTheme.colors.component.retryErrorDetail,
                ),
            )
        }
    }
}

@Composable
private fun CompactionPill(summary: String?) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                color = ChatTheme.colors.component.compactionBg,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(key = AllIconsKeys.General.BalloonInformation, contentDescription = null, modifier = Modifier.size(12.dp), tint = ChatTheme.colors.component.compactionIcon)
            Text(
                text = "Context compacted${if (summary != null) " — $summary" else ""}",
                style = TextStyle(
                    fontSize = ChatTheme.fonts.messageStepFinish,
                    color = ChatTheme.colors.component.compactionText,
                ),
            )
        }
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
    val addedColor = ChatTheme.colors.accent.codeAdded
    val deletedColor = ChatTheme.colors.accent.codeDeleted
    val pathColor = retrieveColorOrUnspecified("Link.activeForeground").copy(alpha = 0.5f)
    val headerColor = retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.55f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header label
        Text(
            text = "Modified files",
            fontSize = ChatTheme.fonts.reviewStatusLabel,
            fontWeight = FontWeight.Medium,
            color = headerColor,
            modifier = Modifier.padding(vertical = ChatTheme.dims.messagePaddingV),
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
    val hoverBg = ChatTheme.colors.component.hoverBg
    val normalColor = ChatTheme.colors.text.primary
    val scope = rememberCoroutineScope()

    // Resolve file type icon
    val fileIconKey = remember(change.fileName) {
        getFileTypeIcon(change.fileName)
    }

    // Resolve virtual file for diff/open actions
    val projectResolved = project ?: com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
    val virtualFile = remember(projectResolved, change.filePath) {
        projectResolved?.basePath?.let { basePath ->
            // CWE-22 path traversal guard: change.filePath originates from LLM tool
            // output (SessionState.kt:900-918), which is untrusted. A prompt injection
            // could emit filePath = "../../etc/passwd". Validate that the resolved
            // canonical path stays within the project basePath before resolving via
            // LocalFileSystem — prevents opening arbitrary system files in the editor.
            val absPath = "$basePath/${change.filePath}".replace('/', java.io.File.separatorChar)
            val canonicalBase = java.io.File(basePath).canonicalPath
            val canonicalTarget = java.io.File(absPath).canonicalPath
            if (canonicalTarget.startsWith(canonicalBase + java.io.File.separator) ||
                canonicalTarget == canonicalBase) {
                LocalFileSystem.getInstance().findFileByPath(canonicalTarget)
            } else {
                logger.warn { "[ACP] FileChangeCard: refusing to open path outside project: ${change.filePath}" }
                null
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
            .background(if (isHovered) hoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable {
                if (projectResolved != null && virtualFile != null) {
                    openDiffForPath(projectResolved, change.filePath, virtualFile, scope)
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
                fontSize = ChatTheme.fonts.reviewFileName,
                fontWeight = FontWeight.Medium,
                color = normalColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = change.filePath,
                fontSize = ChatTheme.fonts.reviewFilePath,
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
                    fontSize = ChatTheme.fonts.reviewLineDelta,
                    fontWeight = FontWeight.Medium,
                    color = addedColor,
                )
            }
            if (change.deletions > 0) {
                if (change.additions > 0) Spacer(Modifier.width(4.dp))
                Text(
                    text = "-${change.deletions}",
                    fontSize = ChatTheme.fonts.reviewLineDelta,
                    fontWeight = FontWeight.Medium,
                    color = deletedColor,
                )
            }
        }

        // Open file target icon
        if (virtualFile != null && projectResolved != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                key = AllIconsKeys.General.Locate,
                contentDescription = "Open file",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { openFileInEditor(projectResolved, virtualFile) }
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
        // Only support 6-char hex (RRGGBB) — force alpha to 0xFF.
        // 8-char hex (AARRGGBB) would truncate on Long.toInt() and produce
        // wrong colors, so reject it and fall back to defaultColor.
        if (clean.length != 6) return defaultColor
        val argb = clean.toLong(16) or 0xFF000000
        Color(argb.toInt())
    } catch (_: Exception) {
        defaultColor
    }
}

/**
 * A queued message rendered as a user-style bubble at the bottom of the chat.
 * Shows the message text with a "Queued" badge and a cancel (X) button.
 * Styled to look like a pending user message — right-aligned, same bubble shape,
 * but with a dashed border and muted colors to indicate it hasn't been sent yet.
 */
@Composable
private fun QueuedMessageBubble(
    message: com.opencode.acp.chat.model.QueuedMessage,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        // Queued badge + cancel button row
        Row(
            modifier = Modifier.padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "Queued" label
            Text(
                text = "Queued",
                fontSize = 10.sp,
                color = ChatTheme.colors.text.muted,
            )
            // Cancel button
            Icon(
                key = AllIconsKeys.Actions.Close,
                contentDescription = "Remove from queue",
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable { onCancel() }
                    .padding(1.dp),
                tint = ChatTheme.colors.text.muted,
            )
        }

        // Message bubble — same shape as user messages but with dashed border
        Box(
            modifier = Modifier
                .background(
                    color = ChatTheme.colors.border.selectionBg.copy(alpha = 0.6f),
                    shape = ChatTheme.shapes.messageBubbleCornerRadius
                )
                .border(
                    width = 1.dp,
                    color = ChatTheme.colors.text.muted.copy(alpha = 0.4f),
                    shape = ChatTheme.shapes.messageBubbleCornerRadius
                )
                .padding(horizontal = ChatTheme.dims.messagePaddingH, vertical = 6.dp)
        ) {
            Text(
                text = message.text,
                style = TextStyle(fontSize = ChatTheme.fonts.messageBody * 1.5f),
                color = ChatTheme.colors.text.primary.copy(alpha = 0.7f),
            )
        }

        // Attached files indicator
        if (message.files.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                message.files.forEach { file ->
                    if (file.mime.startsWith("image/")) {
                        var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                        LaunchedEffect(file.path) {
                            bitmap = withContext(Dispatchers.IO) { decodeFileToBitmap(file.path) }
                        }
                        bitmap?.let { bm ->
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ChatTheme.colors.border.default.copy(alpha = 0.5f))
                            ) {
                                ComposeImage(
                                    bitmap = bm,
                                    contentDescription = file.name,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    } else {
                        // File attachment chip
                        Box(
                            modifier = Modifier
                                .background(
                                    color = ChatTheme.colors.accent.userBubbleBg.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = file.name,
                                fontSize = 10.sp,
                                color = ChatTheme.colors.text.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}
