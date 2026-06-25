package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.ClearAllState
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.SessionIndicator
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.SessionListState
import com.opencode.acp.chat.model.SidebarTab
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.foundation.theme.JewelTheme
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ── SessionSidebar ──────────────────────────────────────────────────────────

@Composable
fun SessionSidebar(
    state: SessionListState,
    contextState: SessionContextState,
    selectedTab: SidebarTab,
    onTabSelected: (SidebarTab) -> Unit,
    onNewSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSessionArchived: (String) -> Unit,
    onRetry: () -> Unit,
    onContextRetry: () -> Unit,
    onShowDetails: () -> Unit,
    onLoadMore: () -> Unit,
    onClearAll: () -> Unit,
    project: Project,
    modifier: Modifier = Modifier,
    fileChangeSignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    commentChangeSignal: kotlinx.coroutines.flow.StateFlow<com.opencode.acp.review.ReviewIndex>,
    streamingSessionIds: Set<String> = emptySet(),
    pendingCreationSessionIds: Set<String> = emptySet(),
    clearAllState: ClearAllState = ClearAllState.Idle,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .background(JewelTheme.globalColors.panelBackground),
    ) {
        // Tab row
        SidebarTabRow(selectedTab = selectedTab, onTabSelected = onTabSelected)

        // Content based on selected tab
        when (selectedTab) {
            SidebarTab.SESSIONS -> {
                // New session button
                NewSessionButton(onClick = onNewSession)

                // Session list content based on state
                val listState = rememberLazyListState()
                // Scroll to top when a new session is created
                val prevPendingCount = remember { mutableStateOf(pendingCreationSessionIds.size) }
                LaunchedEffect(pendingCreationSessionIds.size) {
                    if (pendingCreationSessionIds.size > prevPendingCount.value) {
                        listState.animateScrollToItem(0)
                    }
                    prevPendingCount.value = pendingCreationSessionIds.size
                }
                when (state) {
                    is SessionListState.Loading -> LoadingContent()
                    is SessionListState.Error -> ErrorContent(
                        message = state.message,
                        onRetry = onRetry
                    )
                    is SessionListState.Loaded -> {
                        if (state.sessions.isEmpty()) {
                            EmptyContent()
                        } else {
                            SessionList(
                                sessions = state.displayedSessions,
                                totalCount = state.topLevelSessions.size,
                                hasMore = state.hasMore,
                                selectedId = state.selectedId,
                                onSessionSelected = onSessionSelected,
                                onSessionArchived = onSessionArchived,
                                onLoadMore = onLoadMore,
                                onClearAll = onClearAll,
                                streamingSessionIds = streamingSessionIds,
                                pendingCreationSessionIds = pendingCreationSessionIds,
                                listState = listState,
                                clearAllState = clearAllState,
                            )
                        }
                    }
                }
            }
            SidebarTab.CONTEXT -> {
                ContextPanel(
                    state = contextState,
                    onRetry = onContextRetry,
                    modifier = Modifier.weight(1f)
                )
            }
            SidebarTab.REVIEW -> {
                ReviewPanel(
                    project = project,
                    modifier = Modifier.weight(1f),
                    fileChangeSignal = fileChangeSignal,
                    commentChangeSignal = commentChangeSignal,
                )
            }
        }
    }
}

// ── Tab Row ──────────────────────────────────────────────────────────────────

@Composable
private fun SidebarTabRow(
    selectedTab: SidebarTab,
    onTabSelected: (SidebarTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SidebarTabButton(
            label = "Sessions",
            iconKey = AllIconsKeys.Nodes.Console,
            isSelected = selectedTab == SidebarTab.SESSIONS,
            onClick = { onTabSelected(SidebarTab.SESSIONS) },
            modifier = Modifier.weight(1f)
        )
        SidebarTabButton(
            label = "Context",
            iconKey = AllIconsKeys.Nodes.Folder,
            isSelected = selectedTab == SidebarTab.CONTEXT,
            onClick = { onTabSelected(SidebarTab.CONTEXT) },
            modifier = Modifier.weight(1f)
        )
        SidebarTabButton(
            label = "Review",
            iconKey = AllIconsKeys.Actions.Checked,
            isSelected = selectedTab == SidebarTab.REVIEW,
            onClick = { onTabSelected(SidebarTab.REVIEW) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SidebarTabButton(
    label: String,
    iconKey: IconKey,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val linkColor = retrieveColorOrUnspecified("Link.activeForeground")
    val selectedColor = if (linkColor == Color.Unspecified) ChatTheme.colors.accent.blue else linkColor
    val unselectedColor = retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.55f)
    val underlineColor = if (isSelected) selectedColor else Color.Transparent
    val bgColor = if (isHovered) selectedColor.copy(alpha = 0.12f) else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    key = iconKey,
                    contentDescription = label,
                    modifier = Modifier.size(12.dp),
                    tint = if (isSelected) selectedColor else unselectedColor,
                )
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) selectedColor else unselectedColor,
                )
                Icon(
                    key = AllIconsKeys.General.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(8.dp),
                    tint = if (isSelected) selectedColor else unselectedColor.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(underlineColor)
            )
        }
    }
}

// ── New Session Button ──────────────────────────────────────────────────────

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(retrieveColorOrUnspecified("Link.activeForeground").copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            key = AllIconsKeys.General.Add,
            contentDescription = "New session",
            modifier = Modifier.size(14.dp),
            tint = retrieveColorOrUnspecified("Link.activeForeground"),
        )
        Text(
            text = "New session",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = retrieveColorOrUnspecified("Link.activeForeground"),
        )
    }
}

// ── Session List ────────────────────────────────────────────────────────────

/** A flattened tree item with depth and whether it has children. */
private data class TreeItem(
    val session: SessionItem,
    val depth: Int,
    val hasChildren: Boolean,
)

/**
 * Builds a flat list from a tree of sessions, preserving parent-child order.
 * Parents appear first, then their children indented one level deeper.
 * Orphaned children (parent not in list) are promoted to top level.
 */
private fun buildSessionTree(sessions: List<SessionItem>): List<TreeItem> {
    val sessionMap = sessions.associateBy { it.id }
    val childrenMap = sessions.filter { it.parentID != null }.groupBy { it.parentID!! }
    val processed = mutableSetOf<String>()
    val result = mutableListOf<TreeItem>()

    fun addWithChildren(session: SessionItem, depth: Int) {
        if (session.id in processed) return
        processed.add(session.id)
        val children = childrenMap[session.id]
            ?.filter { it.id !in processed }
            ?.sortedBy { it.updatedAt }
            ?: emptyList()
        result.add(TreeItem(session, depth, hasChildren = children.isNotEmpty()))
        for (child in children) {
            addWithChildren(child, depth + 1)
        }
    }

    // Add parent sessions (no parentID) first, sorted by creation time
    val parents = sessions
        .filter { it.parentID == null }
        .sortedByDescending { it.updatedAt }
    for (parent in parents) {
        addWithChildren(parent, 0)
    }

    // Add any orphaned children (parent not in the list) at top level
    for (session in sessions) {
        if (session.id !in processed) {
            val hasParent = session.parentID != null && session.parentID in sessionMap
            if (!hasParent) {
                addWithChildren(session, 0)
            }
        }
    }

    return result
}

/**
 * Determines which parent session IDs should be expanded by default.
 * Only the parent of the currently selected session (if any) is expanded.
 */
private fun defaultExpandedParents(
    fullTree: List<TreeItem>,
    selectedId: String?,
): Set<String> {
    if (selectedId == null) return emptySet()
    // Find the selected item's ancestor chain
    val selectedIdx = fullTree.indexOfFirst { it.session.id == selectedId }
    if (selectedIdx < 0) return emptySet()
    // Walk backwards to find the root parent of the selected item
    val selectedDepth = fullTree[selectedIdx].depth
    var rootParentIdx = selectedIdx
    for (i in selectedIdx downTo 0) {
        if (fullTree[i].depth == 0) {
            rootParentIdx = i
            break
        }
        if (fullTree[i].depth < fullTree[rootParentIdx].depth) {
            rootParentIdx = i
        }
    }
    // Expand all ancestors along the path from root to selected
    val expanded = mutableSetOf<String>()
    var curIdx = rootParentIdx
    while (curIdx < fullTree.size) {
        val item = fullTree[curIdx]
        if (item.hasChildren) {
            expanded.add(item.session.id)
        }
        // Move to next item — if it's a child of current, continue
        if (curIdx + 1 < fullTree.size && fullTree[curIdx + 1].depth > item.depth) {
            curIdx++
        } else {
            break
        }
    }
    return expanded
}

@Composable
private fun SessionList(
    sessions: List<SessionItem>,
    totalCount: Int,
    hasMore: Boolean,
    selectedId: String?,
    onSessionSelected: (String) -> Unit,
    onSessionArchived: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClearAll: () -> Unit,
    streamingSessionIds: Set<String> = emptySet(),
    pendingCreationSessionIds: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    clearAllState: ClearAllState = ClearAllState.Idle,
) {
    val fullTree = remember(sessions) { buildSessionTree(sessions) }

    // Track which parent IDs are expanded using snapshot state.
    // Reset when sessions or selectedId change.
    val expandedIds = remember(sessions, selectedId) {
        mutableStateMapOf<String, Boolean>().apply {
            for (pid in defaultExpandedParents(fullTree, selectedId)) {
                put(pid, true)
            }
        }
    }

    // Compute visible items by walking the tree with indices (O(n) instead of
    // the previous O(n²) filter that used indexOf + subList per item).
    // An item is visible if all its ancestors are expanded.
    val visibleItems = remember(fullTree, expandedIds.toMap()) {
        fullTree.filterIndexed { idx, item ->
            if (item.depth == 0) return@filterIndexed true
            // Walk backwards from idx to find ancestors. Each ancestor must be expanded.
            var i = idx
            while (i > 0) {
                // Find the nearest preceding item with smaller depth (an ancestor)
                var parentIdx = i - 1
                while (parentIdx >= 0 && fullTree[parentIdx].depth >= fullTree[i].depth) {
                    parentIdx--
                }
                if (parentIdx < 0) break
                val parent = fullTree[parentIdx]
                if (expandedIds[parent.session.id] != true) {
                    return@filterIndexed false
                }
                i = parentIdx
            }
            true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            items(
                count = visibleItems.size,
                key = { visibleItems[it].session.id },
            ) { index ->
                val item = visibleItems[index]
                val isExpanded = expandedIds[item.session.id] == true
                val sessionId = item.session.id
                val indicator = when {
                    sessionId in pendingCreationSessionIds -> SessionIndicator.CREATING
                    sessionId in streamingSessionIds -> SessionIndicator.STREAMING
                    else -> SessionIndicator.NONE
                }
                SessionRow(
                    session = item.session,
                    depth = item.depth,
                    hasChildren = item.hasChildren,
                    isExpanded = isExpanded,
                    isSelected = sessionId == selectedId,
                    indicator = indicator,
                    onClick = { onSessionSelected(sessionId) },
                    onToggle = {
                        if (expandedIds[sessionId] == true) expandedIds[sessionId] = false
                        else expandedIds[sessionId] = true
                    },
                    onArchive = { onSessionArchived(sessionId) },
                )
            }
        }

        // Fixed footer at bottom of sidebar — always visible, no scrolling required
        if (totalCount > 0) {
            SessionListFooter(
                visibleCount = sessions.size,
                totalCount = totalCount,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
                onClearAll = onClearAll,
                clearAllState = clearAllState,
            )
        }
    }
}

// ── Session Row ─────────────────────────────────────────────────────────────

@Composable
private fun SessionRow(
    session: SessionItem,
    depth: Int = 0,
    hasChildren: Boolean = false,
    isExpanded: Boolean = false,
    isSelected: Boolean,
    indicator: SessionIndicator = SessionIndicator.NONE,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val selectedBg = retrieveColorOrUnspecified("List.selectionBackground")
    val hoverBg = retrieveColorOrUnspecified("MenuItem.selectionBackground").copy(alpha = 0.5f)
    val bgColor = when {
        isSelected -> selectedBg
        isHovered -> hoverBg
        else -> Color.Transparent
    }

    val indentDp = (depth * 16).dp

    // Text colors: use IntelliJ theme keys for proper dark-theme contrast
    val titleColor = if (isSelected) {
        retrieveColorOrUnspecified("List.selectionForeground")
    } else {
        retrieveColorOrUnspecified("Panel.foreground")
    }
    val metaColor = if (isSelected) {
        retrieveColorOrUnspecified("List.selectionForeground").copy(alpha = 0.75f)
    } else {
        retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.6f)
    }
    val sepColor = if (isSelected) {
        retrieveColorOrUnspecified("List.selectionForeground").copy(alpha = 0.45f)
    } else {
        retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.4f)
    }
    val iconTint = if (isSelected) {
        retrieveColorOrUnspecified("List.selectionForeground").copy(alpha = 0.8f)
    } else {
        retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.55f)
    }

    val creatingDim = indicator == SessionIndicator.CREATING

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (creatingDim) Modifier.alpha(0.5f) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .sessionShimmer(indicator)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) retrieveColorOrUnspecified("List.selectionForeground").copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .hoverable(interactionSource),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp + indentDp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse chevron for parent sessions — separate clickable
            if (hasChildren) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                Icon(
                    key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = iconTint,
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else if (depth > 0) {
                // Child session indicator
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Forward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconTint,
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else {
                // Leading indicator for top-level leaf sessions
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        key = AllIconsKeys.FileTypes.Text,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconTint,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            // Content area — clickable to select the session
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
            ) {
                // Title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.title.ifBlank { "Untitled" },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Archive button — centered, slightly larger, with a visible hover highlight
                    val archiveHoverBg = retrieveColorOrUnspecified("Component.errorFocusColor").copy(alpha = 0.12f)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isHovered) archiveHoverBg else Color.Transparent)
                            .clickable(onClick = onArchive),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            key = AllIconsKeys.Actions.Close,
                            contentDescription = "Archive",
                            modifier = Modifier.size(18.dp),
                            tint = if (isHovered) {
                                retrieveColorOrUnspecified("Component.errorFocusColor")
                            } else {
                                retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.45f)
                            },
                        )
                    }
                }

                // Metadata row: timestamp + cost + tokens
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Timestamp
                    Text(
                        text = formatRelativeTime(session.updatedAt),
                        fontSize = 11.sp,
                        color = metaColor,
                        maxLines = 1,
                    )

                    // Separator
                    Text(
                        text = "\u00B7",
                        fontSize = 11.sp,
                        color = sepColor,
                        maxLines = 1,
                    )

                    // Cost
                    Text(
                        text = formatCost(session.cost),
                        fontSize = 11.sp,
                        color = metaColor,
                        maxLines = 1,
                    )

                    // Separator
                    Text(
                        text = "\u00B7",
                        fontSize = 11.sp,
                        color = sepColor,
                        maxLines = 1,
                    )

                    // Tokens
                    Text(
                        text = formatTokens(session.inputTokens + session.outputTokens),
                        fontSize = 11.sp,
                        color = metaColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Shimmer Modifier ─────────────────────────────────────────────────────────

/**
 * Applies a horizontal-gradient shimmer band when [indicator] is CREATING or STREAMING.
 * Uses [composed] so the infinite transition is only created when [indicator] is not
 * [SessionIndicator.NONE] — no animation runs for idle sessions. The shimmer progress
 * [State] is read inside [drawBehind] to avoid recomposition every animation frame.
 */
private fun Modifier.sessionShimmer(indicator: SessionIndicator): Modifier = composed {
    if (indicator == SessionIndicator.NONE) return@composed Modifier

    val edge = Color.Transparent
    val soft = ChatTheme.colors.component.glowStart
    val peak = ChatTheme.colors.component.glowPeak

    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerProgressState = transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    Modifier.drawBehind {
        val progress = shimmerProgressState.value
        val bandWidth = size.width * 0.5f
        val startX = progress * size.width - bandWidth
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    edge,
                    soft,
                    peak,
                    soft,
                    edge
                ),
                startX = startX,
                endX = startX + bandWidth
            )
        )
    }
}

// ── Empty State ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No sessions",
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled,
        )
    }
}

// ── Loading State ───────────────────────────────────────────────────────────

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Loading sessions...",
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled,
        )
    }
}

// ── Error State ─────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            key = AllIconsKeys.General.BalloonError,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = retrieveColorOrUnspecified("Component.errorFocusColor"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = JewelTheme.globalColors.text.disabled,
            maxLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Link("Retry", onClick = onRetry)
    }
}

// ── Session List Footer ──────────────────────────────────────────────────────

@Composable
private fun SessionListFooter(
    visibleCount: Int,
    totalCount: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onClearAll: () -> Unit,
    clearAllState: ClearAllState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Status text
        val statusText = when (clearAllState) {
            is ClearAllState.InProgress -> "Deleting ${clearAllState.deleted} of ${clearAllState.total}..."
            is ClearAllState.Done -> when (clearAllState.result) {
                is com.opencode.acp.chat.model.ClearAllResult.Success -> "Deleted ${clearAllState.result.count} session(s)"
                is com.opencode.acp.chat.model.ClearAllResult.Partial -> "Deleted ${clearAllState.result.deleted}, ${clearAllState.result.failed} failed"
                is com.opencode.acp.chat.model.ClearAllResult.Failed -> clearAllState.result.message
            }
            is ClearAllState.Idle -> if (hasMore) "$visibleCount of $totalCount sessions loaded"
                                    else "All $totalCount sessions loaded"
        }
        Text(
            text = statusText,
            fontSize = 11.sp,
            color = retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // "Load more" button — disabled when no more to load or during clear-all
            val isLoading = clearAllState is ClearAllState.InProgress
            OutlinedButton(
                onClick = onLoadMore,
                enabled = hasMore && !isLoading,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (hasMore) "Load more" else "All loaded")
            }

            // "Clear all" button — only when there are sessions to clear beyond the active one
            if (totalCount > 1) {
                val errorColor = retrieveColorOrUnspecified("Component.errorFocusColor")
                OutlinedButton(
                    onClick = onClearAll,
                    enabled = clearAllState is ClearAllState.Idle,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear all", color = errorColor)
                }
            }
        }
    }
}

// ── Clear All Confirmation Dialog ─────────────────────────────────────────────

@Composable
fun ClearAllConfirmationDialog(
    sessionCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(JewelTheme.globalColors.panelBackground)
                .padding(16.dp),
        ) {
            Text(
                text = "Clear All Sessions",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = retrieveColorOrUnspecified("Panel.foreground"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Delete $sessionCount sessions? The active session will be kept. This cannot be undone.",
                fontSize = 12.sp,
                color = retrieveColorOrUnspecified("Panel.foreground"),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                val errorColor = retrieveColorOrUnspecified("Component.errorFocusColor")
                OutlinedButton(onClick = {
                    onDismiss()
                    onConfirm()
                }) {
                    Text("Delete $sessionCount sessions", color = errorColor)
                }
            }
        }
    }
}

// ── Formatting helpers ──────────────────────────────────────────────────────

/**
 * Formats a timestamp as a relative time string.
 * - "just now" for < 1 minute
 * - "5m ago" for < 1 hour
 * - "2h ago" for < 24 hours
 * - "Yesterday" for previous day
 * - "MM/dd" for older dates
 */
internal fun formatRelativeTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis

    if (diff < 0) return "just now"

    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        hours < 48 -> "Yesterday"
        else -> {
            val sdf = SimpleDateFormat("MM/dd", Locale.US)
            sdf.format(Date(epochMillis))
        }
    }
}

/**
 * Formats a cost value as "$0.00" or "$1.23".
 */
internal fun formatCost(cost: Double): String {
    return if (cost == 0.0) "$0.00" else "$${String.format(Locale.US, "%.2f", cost)}"
}

/**
 * Formats token counts with shorthand suffixes:
 * - "0" for 0
 * - "1.2k" for 1000+
 */
internal fun formatTokens(tokens: Long): String {
    return when {
        tokens == 0L -> "0"
        tokens < 1000L -> tokens.toString()
        else -> {
            val k = tokens / 1000.0
            if (k < 10.0) "${String.format(Locale.US, "%.1f", k)}k"
            else "${String.format(Locale.US, "%.0f", k)}k"
        }
    }
}
