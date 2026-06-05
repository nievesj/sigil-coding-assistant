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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.SessionListState
import com.opencode.acp.chat.model.SidebarTab
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.foundation.theme.JewelTheme
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
    project: Project,
    modifier: Modifier = Modifier,
    fileChangeSignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
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
                                sessions = state.sessions,
                                selectedId = state.selectedId,
                                onSessionSelected = onSessionSelected,
                                onSessionArchived = onSessionArchived,
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
            iconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Console),
            isSelected = selectedTab == SidebarTab.SESSIONS,
            onClick = { onTabSelected(SidebarTab.SESSIONS) },
            modifier = Modifier.weight(1f)
        )
        SidebarTabButton(
            label = "Context",
            iconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Folder),
            isSelected = selectedTab == SidebarTab.CONTEXT,
            onClick = { onTabSelected(SidebarTab.CONTEXT) },
            modifier = Modifier.weight(1f)
        )
        SidebarTabButton(
            label = "Review",
            iconKey = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Checked),
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
    val selectedColor = if (linkColor == Color.Unspecified) Color(0xFF3574F0) else linkColor
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
                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.ChevronDown),
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
            key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.Add),
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
    selectedId: String?,
    onSessionSelected: (String) -> Unit,
    onSessionArchived: (String) -> Unit,
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

    // Read expandedIds directly in composable body to trigger recomposition.
    // Don't wrap in remember — it's cheap and must re-evaluate on every toggle.
    val visibleItems = fullTree.filter { item ->
        if (item.depth == 0) return@filter true
        var idx = fullTree.indexOf(item)
        while (idx > 0) {
            val parent = fullTree.subList(0, idx).lastOrNull { it.depth < fullTree[idx].depth }
                ?: break
            if (expandedIds[parent.session.id] != true) {
                return@filter false
            }
            idx = fullTree.indexOf(parent)
        }
        true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
    ) {
        items(
            count = visibleItems.size,
            key = { visibleItems[it].session.id },
        ) { index ->
            val item = visibleItems[index]
            val isExpanded = expandedIds[item.session.id] == true
            SessionRow(
                session = item.session,
                depth = item.depth,
                hasChildren = item.hasChildren,
                isExpanded = isExpanded,
                isSelected = item.session.id == selectedId,
                onClick = { onSessionSelected(item.session.id) },
                onToggle = {
                    if (expandedIds[item.session.id] == true) expandedIds[item.session.id] = false
                    else expandedIds[item.session.id] = true
                },
                onArchive = { onSessionArchived(item.session.id) },
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
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
                    key = IntelliJIconKey.fromPlatformIcon(
                        if (isExpanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
                    ),
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
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Forward),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconTint,
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else {
                // Spacer so text aligns with rows that have chevrons
                Spacer(Modifier.width(24.dp))
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
                    // Archive button — always visible, subtle until hover
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Close),
                        contentDescription = "Archive",
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onArchive)
                            .padding(2.dp),
                        tint = if (isHovered) {
                            retrieveColorOrUnspecified("Component.errorFocusColor")
                        } else {
                            retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.35f)
                        },
                    )
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
            key = IntelliJIconKey.fromPlatformIcon(AllIcons.General.BalloonError),
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
