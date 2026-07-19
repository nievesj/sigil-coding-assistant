package com.opencode.acp.chat.ui.compose

import com.opencode.acp.chat.model.SessionItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure-logic session-tree utilities extracted from `SessionSidebar.kt` (TDD §9 step 12).
 *
 * These functions have no Compose dependencies and are unit-testable in isolation.
 *
 * MUST NOT touch (per TDD §4.7.2):
 * - These are already pure functions — their logic is preserved exactly
 * - The `SessionList` composable stays in `SessionSidebar.kt`
 * - The `expandedIds` State-read pattern inside `items()` lambda stays in `SessionSidebar.kt`
 */
object SessionTreeBuilder {

    /** A flattened tree item with depth and whether it has children. */
    data class TreeItem(
        val session: SessionItem,
        val depth: Int,
        val hasChildren: Boolean,
    )

    /**
     * Builds a flat list from a tree of sessions, preserving parent-child order.
     * Parents appear first, then their children indented one level deeper.
     * Orphaned children (parent not in the list) are promoted to top level.
     * Hidden children (completed child sessions) are filtered out before building.
     *
     * Cycle safety: The `processed` set prevents infinite recursion if the session
     * tree has a cycle (e.g., A→B→A parent links from a server data bug).
     * Each session is visited at most once.
     *
     * The global `processed` set also prevents duplicate rendering: a session that
     * appears under two parents is a data inconsistency — rendering it once (under
     * the first parent encountered) is the correct behavior. The duplicate is logged
     * via `sidebarLogger.warn` so the inconsistency is visible without crashing.
     */
    fun buildSessionTree(
        sessions: List<SessionItem>,
        hiddenChildIds: Set<String> = emptySet(),
    ): List<TreeItem> {
        // Filter out hidden children BEFORE building the tree.
        // Hidden children are skipped entirely; their parent still renders (with other visible
        // children, or as a leaf if all children are hidden).
        val visibleSessions = sessions.filter { it.id !in hiddenChildIds }
        val sessionMap = visibleSessions.associateBy { it.id }
        val childrenMap = visibleSessions.mapNotNull { s -> s.parentID?.let { p -> s to p } }.groupBy({ it.second }, { it.first })
        val processed = mutableSetOf<String>()
        val result = mutableListOf<TreeItem>()

        fun addWithChildren(session: SessionItem, depth: Int) {
            if (session.id in processed) {
                sidebarLogger.warn { "[ACP] buildSessionTree: cycle detected for session ${session.id}" }
                return
            }
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

        // Add parent sessions (no parentID) first, sorted by most-recently-updated (updatedAt desc)
        val parents = visibleSessions
            .filter { it.parentID == null }
            .sortedByDescending { it.updatedAt }
        for (parent in parents) {
            addWithChildren(parent, 0)
        }

        // Add any orphaned children (parent not in the list) at top level
        for (session in visibleSessions) {
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
    fun defaultExpandedParents(
        fullTree: List<TreeItem>,
        selectedId: String?,
    ): Set<String> {
        if (selectedId == null) return emptySet()
        val selectedIdx = fullTree.indexOfFirst { it.session.id == selectedId }
        if (selectedIdx < 0) return emptySet()
        // Walk backwards from the selected item, collecting ALL ancestors
        // (items with strictly decreasing depth). Each ancestor that has
        // children must be expanded so the selected item is visible.
        val expanded = mutableSetOf<String>()
        var currentDepth = fullTree[selectedIdx].depth
        for (i in selectedIdx downTo 0) {
            val item = fullTree[i]
            if (item.depth < currentDepth) {
                // This is an ancestor — it must be expanded
                if (item.hasChildren) {
                    expanded.add(item.session.id)
                }
                currentDepth = item.depth
                if (currentDepth == 0) break
            }
        }
        return expanded
    }

    /**
     * Formats a timestamp as a relative time string.
     * - "just now" for < 1 minute
     * - "5m ago" for < 1 hour
     * - "2h ago" for < 24 hours
     * - "Yesterday" for previous day
     * - "MM/dd" for older dates
     */
    fun formatRelativeTime(epochMillis: Long): String {
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
                // Use a thread-safe, immutable java.time DateTimeFormatter cached in
                // the companion object. Unlike SimpleDateFormat (not thread-safe and
                // allocated per call), DateTimeFormatter can be shared across threads
                // and reused — eliminating per-call allocation for sidebar recompositions
                // that may format 100+ sessions per frame during streaming.
                DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
            }
        }
    }
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd", Locale.US).withZone(ZoneId.systemDefault())