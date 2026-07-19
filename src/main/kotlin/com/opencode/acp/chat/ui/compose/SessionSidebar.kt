package com.opencode.acp.chat.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.window.Dialog

internal val sidebarLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

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
    hiddenChildIds: Set<String> = emptySet(),
    clearAllState: ClearAllState = ClearAllState.Idle,
    compactionState: com.opencode.acp.chat.model.CompactionState = com.opencode.acp.chat.model.CompactionState.Idle,
    onCompact: () -> Unit = {},
    checkpointReady: Boolean = false,
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
                        // Pass full sessions list (top-level + children) so buildSessionTree
                        // can build the complete parent/child tree. Hidden children are filtered
                        // inside buildSessionTree via hiddenChildIds.
                        if (state.sessions.isEmpty()) {
                            EmptyContent()
                        } else {
                            SessionList(
                                sessions = state.sessions,
                                totalCount = state.topLevelSessions.size,
                                hasMore = state.hasMore,
                                selectedId = state.selectedId,
                                onSessionSelected = onSessionSelected,
                                onSessionArchived = onSessionArchived,
                                onLoadMore = onLoadMore,
                                onClearAll = onClearAll,
                                streamingSessionIds = streamingSessionIds,
                                pendingCreationSessionIds = pendingCreationSessionIds,
                                hiddenChildIds = hiddenChildIds,
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
                    compactionState = compactionState,
                    onCompact = onCompact,
                    checkpointReady = checkpointReady,
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

/**
 * Builds a flat list from a tree of sessions, preserving parent-child order.
 * Parents appear first, then their children indented one level deeper.
 * Orphaned children (parent not in the list) are promoted to top level.
 * Hidden children (completed child sessions) are filtered out before building.
 *
 * Cycle safety: The `processed` set (line ~327) prevents infinite recursion
 * if the session tree has a cycle (e.g., A→B→A parent links from a server data bug).
 * Each session is visited at most once.
 *
 * The global `processed` set also prevents duplicate rendering: a session that
 * appears under two parents is a data inconsistency — rendering it once (under
 * the first parent encountered) is the correct behavior. The duplicate is logged
 * via sidebarLogger.warn so the inconsistency is visible without crashing.
 *
 * Extracted to [SessionTreeBuilder.buildSessionTree] (TDD §9 step 12).
 */

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
    hiddenChildIds: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    clearAllState: ClearAllState = ClearAllState.Idle,
) {
    val fullTree = remember(sessions, hiddenChildIds) { SessionTreeBuilder.buildSessionTree(sessions, hiddenChildIds) }

    // Persist expand/collapse state across session switches.
    // Keyed on nothing (persists for the lifetime of SessionList) — selectedId
    // changes merge new ancestors via LaunchedEffect below instead of wiping state.
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

    // Track parents the user explicitly collapsed — suppresses auto-expand for them.
    val userCollapsed = remember { mutableStateMapOf<String, Boolean>() }

    // Merge ancestors of the newly selected session into expandedIds without
    // clearing existing manual expand/collapse state. Respects userCollapsed.
    LaunchedEffect(selectedId, fullTree) {
        for (pid in SessionTreeBuilder.defaultExpandedParents(fullTree, selectedId)) {
            if (pid !in expandedIds && userCollapsed[pid] != true) {
                expandedIds[pid] = true
            }
        }
    }

    // Auto-expand parent when a NEW child appears — either actively streaming
    // (any parent) or under the selected/active session (regardless of streaming
    // status, to handle fast-completing subtasks that finish before loadSessions()
    // returns and the streaming flag is cleared).
    // On initial load, previousChildMap is empty — skip the first emission to
    // avoid mass-expanding all parents that happen to have children.
    val previousChildMap = remember { mutableStateOf<Map<String, List<SessionItem>>>(emptyMap()) }
    val childSessionMap = remember(sessions) {
        // Only group children under parents that are actually present in the
        // sessions list. A parentID pointing to a hidden/deleted/missing session
        // would otherwise create a group key that never renders, leaving those
        // children orphaned in the map (and skipped by the auto-expand logic).
        val sessionIds = sessions.map { it.id }.toSet()
        sessions.filter { it.parentID != null && it.parentID in sessionIds }.groupBy { it.parentID!! }
    }
    var initialized = remember { mutableStateOf(false) }
    LaunchedEffect(childSessionMap, streamingSessionIds) {
        if (!initialized.value) {
            // First emission — seed previousChildMap without triggering auto-expand.
            // This prevents all parents from expanding on initial connect.
            previousChildMap.value = childSessionMap
            initialized.value = true
            return@LaunchedEffect
        }
        // Capture the OLD child map and update previousChildMap FIRST so that
        // cancellation of this LaunchedEffect (when childSessionMap or
        // streamingSessionIds changes mid-loop) cannot leave previousChildMap
        // stale. The next launch reads the already-updated value, avoiding a
        // TOCTOU race where a cancelled emission skips the trailing write.
        val prevMap = previousChildMap.value
        previousChildMap.value = childSessionMap
        val newChildParents = mutableSetOf<String>()
        childSessionMap.forEach { (parentId, children) ->
            val visibleChildren = children.filter { it.id !in hiddenChildIds }
            if (visibleChildren.isNotEmpty()) {
                val prev = prevMap[parentId].orEmpty()
                val prevVisible = prev.filter { it.id !in hiddenChildIds }
                // Only expand when genuinely new children appeared (size grew)
                if (visibleChildren.size > prevVisible.size) {
                    // Auto-expand if this parent has actively streaming children...
                    val hasStreamingChild = visibleChildren.any { it.id in streamingSessionIds }
                    // ...or if this is the selected/active session (handles fast-completing
                    // subtasks where the child finishes before loadSessions() returns).
                    val isSelectedParent = parentId == selectedId
                    if (hasStreamingChild || isSelectedParent) {
                        newChildParents += parentId
                    }
                }
            }
        }
        for (parentId in newChildParents) {
            if (userCollapsed[parentId] != true && parentId !in expandedIds) {
                expandedIds[parentId] = true
            }
        }
    }

    // Compute the capped set of child session IDs allowed to show a spinner.
    // Child sessions beyond the cap show a static icon (SessionIndicator.NONE-equivalent).
    // The global cap (MAX_VISIBLE_CHILD_SPINNERS) prevents the GDI nativeBlit hang
    // (JDK-8301926) caused by too many concurrent animations generating continuous
    // frame pressure through Skiko SOFTWARE render → GDI BitBlt → DWM composition.
    // See AGENTS.md "GDI nativeBlit Hang" for the full investigation.
    val streamingChildIds = remember(streamingSessionIds, sessions, hiddenChildIds) {
        val childIds = sessions.filter { it.parentID != null && it.id !in hiddenChildIds }.map { it.id }.toSet()
        val streamingChildren = streamingSessionIds.filter { it in childIds }
        // Precompute a session-by-id map so the sort comparator is O(1) lookup
        // instead of O(m) linear scan per streaming child (avoids O(n*m)).
        val sessionById = sessions.associateBy { it.id }
        // Prioritize by recency (updatedAt desc) so the most recently active
        // streaming children get spinners first when the cap is hit.
        streamingChildren
            .sortedByDescending { id -> sessionById[id]?.updatedAt ?: 0L }
            .take(com.opencode.acp.chat.model.ChatConstants.MAX_VISIBLE_CHILD_SPINNERS)
            .toSet()
    }

    // Compute visible items by walking the tree with indices (O(n) instead of
    // the previous O(n²) filter that used indexOf + subList per item).
    // An item is visible if all its ancestors are expanded.
    // NOTE: The inner ancestor-walk is O(depth) per item, so overall O(n * depth).
    // For typical session trees (depth 2-3) this is effectively O(n). Deep subagent
    // chains (depth 10+) would degrade — if that becomes common, precompute a
    // parent-index map for O(1) ancestor lookup.
    // Review note: O(n*depth) is acceptable for current usage (depth 2-3). If deep subagent chains (depth 10+) become common, precompute a depth→first-index map for O(1) ancestor lookup. No fix needed now.
    //
    // Use expandedIds.toMap() as the remember key. Map equality is structural
    // (compares entries, not identity), so remember recomputes only when the
    // actual expanded-state content changes. The previous entries.toSet()
    // approach created new Entry wrapper objects on every call, which could
    // defeat equality checks; toMap() returns a stable structural snapshot.
    val expandedKey = expandedIds.toMap()
    val visibleItems = remember(fullTree, expandedKey) {
        // Precompute parent index: for each item at index i, parentIdx[i] = index of nearest
        // preceding item with strictly smaller depth (the ancestor), or -1 if none.
        val parentIdx = IntArray(fullTree.size) { -1 }
        for (i in fullTree.indices) {
            var p = i - 1
            while (p >= 0 && fullTree[p].depth >= fullTree[i].depth) p--
            parentIdx[i] = p
        }
        fullTree.filterIndexed { idx, item ->
            if (item.depth == 0) return@filterIndexed true
            // Walk up the ancestor chain via parentIdx — each ancestor must be expanded
            var i = idx
            while (i > 0) {
                val p = parentIdx[i]
                if (p < 0) break
                val parent = fullTree[p]
                if (expandedIds[parent.session.id] != true) {
                    return@filterIndexed false
                }
                i = p
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
            // Stable keys (session.id) are safe here because SessionRow reads the
            // SnapshotStateMap (expandedIds/userCollapsed) internally via
            // retrieveColorOrUnspecified and the isExpanded/isHovered state reads.
            // These reads create per-item snapshot subscriptions that trigger
            // recomposition when the underlying state changes, bypassing the
            // LazyColumn key-diffing stale-data issue documented in AGENTS.md.
            items(
                count = visibleItems.size,
                key = { visibleItems[it].session.id },
            ) { index ->
                val item = visibleItems[index]
                val isExpanded = expandedIds[item.session.id] == true
                val sessionId = item.session.id
                val isChild = item.session.parentID != null
                val indicator = when {
                    sessionId in pendingCreationSessionIds -> SessionIndicator.CREATING
                    sessionId in streamingSessionIds && (!isChild || sessionId in streamingChildIds) -> SessionIndicator.STREAMING
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
                        if (expandedIds[sessionId] == true) {
                            expandedIds[sessionId] = false
                            userCollapsed[sessionId] = true
                        } else {
                            expandedIds[sessionId] = true
                            userCollapsed.remove(sessionId)
                        }
                    },
                    onArchive = { onSessionArchived(sessionId) },
                )
            }
        }

        // Fixed footer at bottom of sidebar — always visible, no scrolling required
        if (totalCount > 0) {
            // visibleCount shows only top-level sessions (children are indented under parents)
            val topLevelVisible = visibleItems.count { it.depth == 0 }
            SessionListFooter(
                visibleCount = topLevelVisible,
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
        .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.accent.blue.copy(alpha = 0.15f)
    val hoverBg = (retrieveColorOrUnspecified("MenuItem.selectionBackground")
        .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.accent.blue.copy(alpha = 0.10f)).copy(alpha = 0.5f)
    val bgColor = when {
        isSelected -> selectedBg
        isHovered -> hoverBg
        else -> Color.Transparent
    }

    val indentDp = (depth * 16).dp

    // Text colors: use IntelliJ theme keys for proper dark-theme contrast.
    // Fall back to ChatTheme tokens when the IntelliJ key resolves to Unspecified
    // (happens on some LaFs / themes that don't define the key).
    val titleColor = if (isSelected) {
        retrieveColorOrUnspecified("List.selectionForeground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.inverse
    } else {
        retrieveColorOrUnspecified("Panel.foreground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText
    }
    val metaColor = if (isSelected) {
        (retrieveColorOrUnspecified("List.selectionForeground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.inverse).copy(alpha = 0.75f)
    } else {
        (retrieveColorOrUnspecified("Panel.foreground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText).copy(alpha = 0.6f)
    }
    val sepColor = if (isSelected) {
        (retrieveColorOrUnspecified("List.selectionForeground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.inverse).copy(alpha = 0.45f)
    } else {
        (retrieveColorOrUnspecified("Panel.foreground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText).copy(alpha = 0.4f)
    }
    val iconTint = if (isSelected) {
        (retrieveColorOrUnspecified("List.selectionForeground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.inverse).copy(alpha = 0.8f)
    } else {
        (retrieveColorOrUnspecified("Panel.foreground")
            .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText).copy(alpha = 0.55f)
    }

    val creatingDim = indicator == SessionIndicator.CREATING

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (creatingDim) Modifier.alpha(0.5f) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) (retrieveColorOrUnspecified("List.selectionForeground")
                    .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.inverse).copy(alpha = 0.3f) else Color.Transparent,
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
                    if (indicator != SessionIndicator.NONE) {
                        SessionSpinner(modifier = Modifier.size(16.dp), tint = iconTint, active = true)
                    } else {
                        Icon(
                            key = if (isExpanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = iconTint,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            } else if (depth > 0) {
                // Child session indicator
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (indicator != SessionIndicator.NONE) {
                        SessionSpinner(modifier = Modifier.size(14.dp), tint = iconTint, active = true)
                    } else {
                        Icon(
                            key = AllIconsKeys.Actions.Forward,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            } else {
                // Leading indicator for top-level leaf sessions
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (indicator != SessionIndicator.NONE) {
                        SessionSpinner(modifier = Modifier.size(14.dp), tint = iconTint, active = true)
                    } else {
                        Icon(
                            key = AllIconsKeys.FileTypes.Text,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = iconTint,
                        )
                    }
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
                    // Archive button — shown for all sessions (including children and orphans)
                    // Guard against unresolved theme key: retrieveColorOrUnspecified returns
                    // Color.Unspecified when the key is missing, and .copy(alpha=...) on
                    // Unspecified produces a near-transparent black smudge. Fall back to
                    // ChatTheme.colors.text.error (a red-ish error color) before applying alpha.
                    val errorColor = retrieveColorOrUnspecified("Component.errorFocusColor")
                        .takeIf { it != Color.Unspecified } ?: ChatTheme.colors.text.error
                    val archiveHoverBg = errorColor.copy(alpha = 0.12f)
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
                                (retrieveColorOrUnspecified("Panel.foreground").takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText).copy(alpha = 0.45f)
                            },
                        )
                    }
                }

                // Metadata row: [Sub-task ·] timestamp · cost · tokens
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "Sub-task" label for child sessions
                    if (session.parentID != null) {
                        Text(
                            text = "Sub-task",
                            fontSize = 11.sp,
                            color = metaColor,
                            maxLines = 1,
                        )
                        Text(
                            text = "\u00B7",
                            fontSize = 11.sp,
                            color = sepColor,
                            maxLines = 1,
                        )
                    }

                    // Timestamp
                    Text(
                        text = SessionTreeBuilder.formatRelativeTime(session.updatedAt),
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
                        text = TokenFormatters.formatCost(session.cost),
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
                        text = TokenFormatters.formatTokens(session.inputTokens + session.outputTokens),
                        fontSize = 11.sp,
                        color = metaColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Session Spinner ──────────────────────────────────────────────────────────

/**
 * A small rotating arc spinner shown in place of the session row's leading icon
 * while [SessionIndicator] is CREATING or STREAMING. When the indicator is NONE,
 * the caller renders the normal icon instead.
 *
 * Uses [rememberThrottledInfiniteAnimation] to reduce GPU command flush pressure.
 * The throttle FPS is configurable via Settings → Tools → Sigil → "Animation FPS".
 */
@Composable
private fun SessionSpinner(modifier: Modifier = Modifier, tint: Color = ChatTheme.colors.text.muted, active: Boolean = true) {
    val rotationState = rememberThrottledInfiniteAnimation(
        active = active,
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 1000,
        repeatMode = RepeatMode.Restart,
        label = "sessionSpinner",
    )

    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val diameter = size.width
        val radius = diameter / 2f - stroke / 2f

        drawArc(
            color = tint.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = tint,
            startAngle = rotationState.value - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
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
            color = (retrieveColorOrUnspecified("Panel.foreground").takeIf { it != Color.Unspecified } ?: ChatTheme.colors.component.inputText).copy(alpha = 0.6f),
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

// formatRelativeTime extracted to SessionTreeBuilder.formatRelativeTime
// (TDD §9 step 12). TokenFormatters.formatCost/formatTokens remain in TokenFormatters.kt.
