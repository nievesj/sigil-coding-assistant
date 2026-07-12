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
import androidx.compose.runtime.State
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
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.model.PartState
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

/** Font scale for queued message bubbles — slightly larger than body text for emphasis. */
private const val QUEUED_MESSAGE_FONT_SCALE = 1.5f

@Composable
fun MessageList(
    messagesState: State<Map<String, ChatMessage>>,
    modifier: Modifier = Modifier,
    project: Project? = null,
    onImagePreview: ((String) -> Unit)? = null,
    getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null,
    queuedMessages: List<com.opencode.acp.chat.model.QueuedMessage> = emptyList(),
    onCancelQueuedMessage: ((String) -> Unit)? = null,
    onOpenSubtask: ((String) -> Unit)? = null,
) {
    // Derive the indexed list from State for count/key computation.
    // The item content lambda reads messagesState.value directly (below) to
    // create a per-item snapshot subscription — this is what drives recomposition
    // when a message's parts change, without needing the LazyColumn key to change.
    //
    // Filter out empty streaming assistant messages — they create 0-height LazyColumn
    // items that trigger mass recycling of visible items (the "jump" flicker). The
    // message gets parts within milliseconds; the ThinkingIndicator overlay still
    // shows activity during the gap.
    val messages by remember {
        derivedStateOf {
            messagesState.value.values.filter { msg ->
                !(msg.role == MessageRole.ASSISTANT && msg.parts.isEmpty() && msg.isStreaming)
            }.toList()
        }
    }
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
    //
    // KEY INSIGHT: With Arrangement.Bottom, when content grows (pill expand,
    // streaming text), items shift upward — firstVisibleItemScrollOffset
    // decreases. The old approach (sumOf visible item sizes) was unreliable
    // because the sum can DECREASE when fewer items fit after expansion.
    //
    // The correct discriminator is isScrollInProgress: content growth does NOT
    // set it (programmatic scrolls hold scrollMutex, so the guard returns early).
    // Only a genuine user drag sets isScrollInProgress without scrollMutex locked.
    // We combine this with distance-from-bottom: if the gap between the last
    // visible item's bottom and the viewport bottom increased AND the user is
    // actively dragging, it's a real scroll-up.
    LaunchedEffect(Unit) {
        var previousDistanceFromBottom = 0
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            if (lastVisible == null) 0
            else info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
        }.collect { distanceFromBottom ->
            if (scrollMutex.isLocked) {
                // Programmatic scroll in progress — ignore.
                previousDistanceFromBottom = distanceFromBottom
                return@collect
            }
            if (isAtBottom) {
                autoScrollEnabled = true
            } else if (listState.isScrollInProgress &&
                       distanceFromBottom > previousDistanceFromBottom + 8) {
                // Genuine user scroll-up: gap from last item bottom to viewport
                // bottom increased AND list is actively being scrolled by user
                // (not programmatic — scrollMutex is not locked). The +8 pixel
                // threshold absorbs rounding noise from Arrangement.Bottom.
                autoScrollEnabled = false
            }
            previousDistanceFromBottom = distanceFromBottom
        }
    }

    // Re-enable auto-scroll when user scrolls back to the very bottom.
    // Uses pixel-based isAtBottom to avoid the canScrollForward toggle noise.
    // Guard: don't re-enable when the list shrank (message loss/compaction) —
    // that would arm auto-scroll with nothing to follow. Only re-enable on
    // legitimate user scroll-back, which happens when the list is stable or
    // growing.
    //
    // NOTE: reads messagesState.value.size (a snapshot-state read) inside
    // snapshotFlow so the flow re-emits when the message count changes.
    // Reading the plain `messages` List (captured at first composition by
    // LaunchedEffect(Unit)) would freeze the size at its initial value,
    // making the grew guard a no-op.
    LaunchedEffect(Unit) {
        var prevSize = messagesState.value.size
        snapshotFlow { isAtBottom to messagesState.value.size }
            .collect { (atBottom, size) ->
                val grew = size >= prevSize
                prevSize = size
                if (atBottom && grew) autoScrollEnabled = true
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
        var lastSeenIndex = -1
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            (lastItem?.index ?: -1) to (lastItem?.size ?: 0)
        }.collect { (lastIndex, lastSize) ->
            // Reset baseline when the last visible item identity changes.
            // Without this, switching to a shorter last item would never
            // trigger a scroll (lastSize < lastHeight).
            if (lastIndex != lastSeenIndex) {
                lastHeight = 0
                lastSeenIndex = lastIndex
            }
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
        val oldCount = prevMessageCount
        val messageDelta = messages.size - oldCount
        prevMessageCount = messages.size
        val isBulkLoad = messageDelta > 1 || (oldCount == 0 && messages.size > 1 && scrollRequest == 1)
        // isBulkLoad: session switch loaded N messages at once (delta > 1),
        // or first render with existing messages (prevCount=0, now >1).

        // Treat delta 0 (streaming growth) and delta 1 (single new message) as
        // non-bulk. delta 1 gets animation; delta 0 gets instant.
        val isStreamingGrowth = messageDelta == 0
        // Messages removed (compaction, message.removed, or lost-message bug) — snap to
        // the new bottom instantly. Without this, delta < 0 falls into the animated-scroll
        // branch, which no-ops because the target is already visible, leaving autoScroll
        // armed with nothing to follow (the "stuck scroll" symptom).
        val isShrink = messageDelta < 0

        // New message appearing (delta == 1) during streaming — use instant scroll.
        // The animated scroll causes all visible items to shift upward (the "jump"),
        // which is visually jarring during streaming. Instant scroll snaps to the
        // new bottom without animating the shift.
        val isNewStreamingMessage = messageDelta == 1 && messages.lastOrNull()?.isStreaming == true

        scrollMutex.withLock {
            try {
                if (isBulkLoad || isStreamingGrowth || isShrink || isNewStreamingMessage) {
                    // Instant snap — no animation. For bulk loads, history appears
                    // at the bottom immediately. For streaming, the content growth
                    // is the motion; animating creates a feedback loop.
                    // For shrink (messages removed), snap to the new bottom instantly
                    // so autoScroll tracks the shortened list.
                    // For new streaming messages, instant scroll avoids the visual
                    // "jump" caused by animating all visible items shifting upward.
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
                    if (isBulkLoad || isStreamingGrowth || isShrink || isNewStreamingMessage) {
                        listState.scrollToItem(totalItems - 1)
                    } else {
                        listState.animateScrollToItem(totalItems - 1)
                    }
                } catch (e2: Exception) {
                    if (e2 is kotlinx.coroutines.CancellationException) throw e2
                    logger.warn(e2) { "[ACP] scroll fallback also failed — giving up (double scroll failure indicates a real problem)" }
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

    // Pinned activity indicator — shown at the bottom of the chat for the
    // entire duration the last assistant message is streaming. The label
    // dynamically reflects what the LLM is doing (Thinking…, Running bash…,
    // Writing…). Uses derivedStateOf reading messagesState.value directly
    // (not the captured `messages` list) to avoid recomputing on every
    // streaming chunk.
    val activityLabel by remember {
        derivedStateOf {
            val last = messagesState.value.values.lastOrNull()
            if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
                deriveActivityLabel(last)
            } else {
                null
            }
        }
    }

    Box(modifier = modifier) {
        // SelectionContainer is intentionally NOT wrapping the LazyColumn.
        // During programmatic scrollToItem (streaming growth), Compose's selection
        // system briefly highlights all visible text — a "blue box with text"
        // flash lasting ~half a second. Scoping selection to individual messages
        // (inside MessageItem) prevents the global selection glitch.
        // Arrangement.Bottom anchors items to the BOTTOM of the viewport.
        // fillMaxSize() is CRITICAL — without it, the LazyColumn wraps content
        // height and Arrangement.Bottom has no effect.
        // Fixed bottom padding reserves space for the indicator overlay so
        // content doesn't jump when the indicator appears/disappears.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp, start = 8.dp, top = 8.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                items(
                    count = messages.size,
                    key = { index -> messages[index].id }
                ) { index ->
                    // Read State INSIDE the item content lambda to create a per-item
                    // snapshot subscription. The bundled Compose Foundation does NOT
                    // re-invoke item content lambdas for stable keys on its own.
                    // The snapshot system invalidates this item's composition when
                    // messagesState changes, re-invoking this lambda with fresh data
                    // — bypassing LazyColumn's key-diffing. This eliminates both the
                    // flicker (no dispose+recreate) and the stale-data bug (fresh read).
                    val currentMessage = messagesState.value[messages[index].id] ?: messages[index]
                    MessageItem(currentMessage, project, onImagePreview, getStreamingText = getStreamingText, onOpenSubtask = onOpenSubtask)
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

        // Scrollbar — sibling of LazyColumn (not a child, so it receives pointer events).
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

        // Pinned activity indicator — fixed at the bottom of the chat viewport.
        // Only visible during the initial "thinking" phase (no content parts yet).
        // Once the message has content, per-part UI handles display and this
        // suppresses itself to avoid visual duplication.
        val label = activityLabel
        if (label != null) {
            ThinkingIndicator(
                label = label,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
fun MessageItem(
    message: ChatMessage,
    project: Project? = null,
    onImagePreview: ((String) -> Unit)? = null,
    getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null,
    onOpenSubtask: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, onImagePreview, modifier)
        MessageRole.ASSISTANT -> AssistantMessage(message, project, getStreamingText = getStreamingText, onOpenSubtask = onOpenSubtask, modifier = modifier)
    }
}

@Composable
fun UserMessage(message: ChatMessage, onImagePreview: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
fun AssistantMessage(message: ChatMessage, project: Project? = null, getStreamingText: ((String) -> kotlinx.coroutines.flow.StateFlow<String>?)? = null, onOpenSubtask: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
     val streamingAlpha = if (message.isStreaming) 0.85f else 1f

     if (message.parts.isEmpty()) {
         logger.warn { "[ACP] AssistantMessage: EMPTY PARTS! msg=${message.id} isStreaming=${message.isStreaming} state=${message.state}" }
     }

     // Set up markdown styling once for the entire message (needed by Text and Code parts)
     // Cache the fallback project to avoid repeated ProjectManager lookups on every recomposition.
     val currentProject = project ?: remember {
         com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
     }
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
           Column(modifier = modifier.fillMaxWidth().graphicsLayer { alpha = streamingAlpha }) {
                // Render parts in LinkedHashMap insertion order — the order events arrived.
                // Pre-compute: if message has any ToolCall parts, suppress Patch/FileChange cards
               // (ToolPill already shows file info, line counts, and expandable content)
               val hasToolCallInMessage = message.parts.values.any { it is MessagePart.ToolCall }
               var hasToolCall = hasToolCallInMessage
                // Render parts in LinkedHashMap insertion order — chronological event order.
                // resegmentTextPartsDirect preserves insertion positions via textSegments.
                // NOTE: The standalone "thinking…" indicator is NOT rendered here. It is
                // pinned at the bottom of the MessageList Box, decoupled from the message's
                // composition tree. This eliminates the flicker caused by the
                // ThinkingIndicator ↔ CollapsibleThinkingPill structural swap.
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
                             key(key) {
                                  ToolPill(part.pill, getStreamingText = getStreamingText, onOpenSubtask = onOpenSubtask)
                             }
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
                                   onUrlClick = { url -> openUrlSafely(url) },
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
    count >= 1_000_000 -> "${String.format(java.util.Locale.US, "%.1f", count / 1_000_000.0)}M"
    count >= 1_000 -> "${String.format(java.util.Locale.US, "%.1f", count / 1_000.0)}k"
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
            try {
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
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] FileChangeCard: canonicalization failed for path: ${change.filePath}" }
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
/**
 * Derives a human-readable activity label from the streaming assistant
 * message's current parts. Used by the pinned activity indicator at the
 * bottom of the chat to show what the LLM is doing right now.
 *
 * Priority (last active part wins):
 * 1. Tool call awaiting permission → "Awaiting permission…"
 * 2. Tool call in progress         → "Running {toolName}…"
 * 3. Thinking part streaming       → "Thinking…"
 * 4. Text part present             → "Writing…"
 * 5. No content yet                → "Thinking…"
 */
private fun deriveActivityLabel(message: ChatMessage): String {
    val parts = message.parts.values

    // Check for tool calls — find the most recent one that's active
    val toolCalls = parts.filterIsInstance<MessagePart.ToolCall>()
    if (toolCalls.isNotEmpty()) {
        val lastTool = toolCalls.last()
        when (lastTool.state) {
            PartState.Pending -> return "Awaiting permission…"
            PartState.InProgress -> {
                val name = lastTool.pill.toolName
                return "Running $name…"
            }
            else -> { /* completed/failed — fall through to check other parts */ }
        }
    }

    // Check for active thinking
    val hasActiveThinking = parts.any { it is MessagePart.Thinking && it.state is PartState.Streaming }
    if (hasActiveThinking) return "Thinking…"

    // Check for text content (LLM is writing the response)
    val hasText = parts.any { it is MessagePart.Text }
    if (hasText) return "Writing…"

    // No content parts yet — still waiting for first token
    return "Thinking…"
}

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

/**
 * Open a URL safely — only allows http and https schemes to prevent SSRF via
 * LLM-generated markdown links (e.g., file://, javascript:, data: URIs).
 * Logs and silently drops non-http(s) URLs.
 */
private fun openUrlSafely(url: String) {
    val trimmed = url.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        try {
            val parsed = java.net.URI(trimmed)
            val host = parsed.host
            // Block link-local (169.254.x.x) and loopback addresses to prevent
            // browser-level SSRF (e.g., cloud metadata endpoints, internal services)
            // unless the host matches the configured OpenCode server.
            val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            val isLinkLocal = host.startsWith("169.254.")
            if (isLoopback || isLinkLocal) {
                // Allow only if it matches the configured OpenCode server port
                val serverPort = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().port
                val serverUrl = "http://127.0.0.1:$serverPort"
                if (!trimmed.startsWith(serverUrl)) {
                    logger.warn { "[ACP] Blocked internal URL from markdown: ${trimmed.take(100)}" }
                    return
                }
            }
            BrowserUtil.open(trimmed)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to parse URL from markdown: ${trimmed.take(100)}" }
        }
    } else {
        logger.warn { "[ACP] Blocked non-http URL from markdown: ${trimmed.take(100)}" }
    }
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
                style = TextStyle(fontSize = ChatTheme.fonts.messageBody * QUEUED_MESSAGE_FONT_SCALE),
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
