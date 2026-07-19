package com.opencode.acp.chat.ui.compose

import com.opencode.acp.chat.model.SessionItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SessionTreeBuilder] (TDD §9 step 12 — SessionTreeBuilderTest).
 *
 * Verifies the pure tree-building, default-expansion, and relative-time
 * formatting logic extracted from `SessionSidebar.kt`.
 */
class SessionTreeBuilderTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun session(
        id: String,
        title: String = "Session $id",
        updatedAt: Long = 1000L,
        cost: Double = 0.0,
        inputTokens: Long = 0L,
        outputTokens: Long = 0L,
        parentID: String? = null,
    ): SessionItem = SessionItem(
        id = id,
        title = title,
        updatedAt = updatedAt,
        cost = cost,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        parentID = parentID,
    )

    private fun ids(tree: List<SessionTreeBuilder.TreeItem>): List<String> =
        tree.map { it.session.id }

    // ── buildSessionTree ─────────────────────────────────────────────────────

    @Test
    fun `buildSessionTree empty list returns empty tree`() {
        SessionTreeBuilder.buildSessionTree(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `buildSessionTree single root returns tree with one item`() {
        val s = session("a")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(s))
        tree.size shouldBe 1
        tree[0].session.id shouldBe "a"
        tree[0].depth shouldBe 0
        tree[0].hasChildren shouldBe false
    }

    @Test
    fun `buildSessionTree parent and child renders parent with child in children`() {
        val parent = session("parent", updatedAt = 2000L)
        val child = session("child", parentID = "parent", updatedAt = 1000L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(parent, child))
        ids(tree) shouldBe listOf("parent", "child")
        tree[0].depth shouldBe 0
        tree[0].hasChildren shouldBe true
        tree[1].depth shouldBe 1
        tree[1].hasChildren shouldBe false
    }

    @Test
    fun `buildSessionTree multiple roots returns tree with multiple top-level items`() {
        val a = session("a", updatedAt = 100L)
        val b = session("b", updatedAt = 200L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(a, b))
        // Sorted by updatedAt desc → b first, then a
        ids(tree) shouldBe listOf("b", "a")
        tree[0].depth shouldBe 0
        tree[1].depth shouldBe 0
    }

    @Test
    fun `buildSessionTree hidden child is excluded from tree`() {
        val parent = session("parent")
        val visibleChild = session("visible", parentID = "parent")
        val hiddenChild = session("hidden", parentID = "parent")
        val tree = SessionTreeBuilder.buildSessionTree(
            listOf(parent, visibleChild, hiddenChild),
            hiddenChildIds = setOf("hidden"),
        )
        ids(tree) shouldBe listOf("parent", "visible")
    }

    @Test
    fun `buildSessionTree orphan child becomes top-level`() {
        // Child references a parent that is not in the list.
        val orphan = session("orphan", parentID = "missing-parent")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(orphan))
        ids(tree) shouldBe listOf("orphan")
        tree[0].depth shouldBe 0
    }

    @Test
    fun `buildSessionTree deep nesting renders three levels`() {
        val root = session("root", updatedAt = 3000L)
        val mid = session("mid", parentID = "root", updatedAt = 2000L)
        val leaf = session("leaf", parentID = "mid", updatedAt = 1000L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(root, mid, leaf))
        ids(tree) shouldBe listOf("root", "mid", "leaf")
        tree[0].depth shouldBe 0
        tree[1].depth shouldBe 1
        tree[2].depth shouldBe 2
        tree[0].hasChildren shouldBe true
        tree[1].hasChildren shouldBe true
        tree[2].hasChildren shouldBe false
    }

    @Test
    fun `buildSessionTree children sorted by updatedAt ascending`() {
        val parent = session("parent")
        val young = session("young", parentID = "parent", updatedAt = 300L)
        val old = session("old", parentID = "parent", updatedAt = 100L)
        val mid = session("mid", parentID = "parent", updatedAt = 200L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(parent, young, old, mid))
        // Parent first, then children sorted by updatedAt asc: old, mid, young
        ids(tree) shouldBe listOf("parent", "old", "mid", "young")
    }

    @Test
    fun `buildSessionTree parents sorted by updatedAt descending`() {
        val a = session("a", updatedAt = 100L)
        val b = session("b", updatedAt = 300L)
        val c = session("c", updatedAt = 200L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(a, b, c))
        // Sorted by updatedAt desc: b (300), c (200), a (100)
        ids(tree) shouldBe listOf("b", "c", "a")
    }

    // ── defaultExpandedParents ───────────────────────────────────────────────

    @Test
    fun `defaultExpandedParents empty tree returns empty set`() {
        SessionTreeBuilder.defaultExpandedParents(emptyList(), "a") shouldBe emptySet()
    }

    @Test
    fun `defaultExpandedParents selected leaf expands parent`() {
        val parent = session("parent")
        val child = session("child", parentID = "parent")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(parent, child))
        SessionTreeBuilder.defaultExpandedParents(tree, "child") shouldBe setOf("parent")
    }

    @Test
    fun `defaultExpandedParents selected root returns empty set`() {
        val root = session("root")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(root))
        SessionTreeBuilder.defaultExpandedParents(tree, "root") shouldBe emptySet()
    }

    @Test
    fun `defaultExpandedParents selectedId not in tree returns empty set`() {
        val parent = session("parent")
        val child = session("child", parentID = "parent")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(parent, child))
        SessionTreeBuilder.defaultExpandedParents(tree, "nonexistent") shouldBe emptySet()
    }

    @Test
    fun `defaultExpandedParents null selectedId returns empty set`() {
        val parent = session("parent")
        val tree = SessionTreeBuilder.buildSessionTree(listOf(parent))
        SessionTreeBuilder.defaultExpandedParents(tree, null) shouldBe emptySet()
    }

    @Test
    fun `defaultExpandedParents deeply nested child expands all ancestors`() {
        val root = session("root", updatedAt = 3000L)
        val mid = session("mid", parentID = "root", updatedAt = 2000L)
        val leaf = session("leaf", parentID = "mid", updatedAt = 1000L)
        val tree = SessionTreeBuilder.buildSessionTree(listOf(root, mid, leaf))
        SessionTreeBuilder.defaultExpandedParents(tree, "leaf") shouldBe setOf("root", "mid")
    }

    // ── formatRelativeTime ───────────────────────────────────────────────────

    @Test
    fun `formatRelativeTime now returns just now`() {
        val now = System.currentTimeMillis()
        SessionTreeBuilder.formatRelativeTime(now) shouldBe "just now"
    }

    @Test
    fun `formatRelativeTime 1 minute ago returns 1m ago`() {
        val now = System.currentTimeMillis()
        // Use 61s to avoid edge-case flakiness if the clock advances between
        // capturing `now` and calling formatRelativeTime (60s exactly could
        // drop to 59s → "just now").
        val oneMinuteAgo = now - 61_000L
        SessionTreeBuilder.formatRelativeTime(oneMinuteAgo) shouldBe "1m ago"
    }

    @Test
    fun `formatRelativeTime 1 hour ago returns 1h ago`() {
        val now = System.currentTimeMillis()
        // Use 61m to avoid edge-case flakiness (60m exactly could drop to 59m
        // if the clock advances between capturing `now` and the call).
        val oneHourAgo = now - 61 * 60_000L
        SessionTreeBuilder.formatRelativeTime(oneHourAgo) shouldBe "1h ago"
    }

    @Test
    fun `formatRelativeTime 1 day ago returns Yesterday`() {
        val now = System.currentTimeMillis()
        // Use 25h to avoid edge-case flakiness (24h exactly could drop to 23h
        // if the clock advances between capturing `now` and the call).
        val oneDayAgo = now - 25 * 3_600_000L
        SessionTreeBuilder.formatRelativeTime(oneDayAgo) shouldBe "Yesterday"
    }

    @Test
    fun `formatRelativeTime future time returns just now`() {
        val now = System.currentTimeMillis()
        val future = now + 60_000L
        // Negative diff → "just now" (per implementation: `if (diff < 0) return "just now"`)
        SessionTreeBuilder.formatRelativeTime(future) shouldBe "just now"
    }

    @Test
    fun `formatRelativeTime epoch does not crash`() {
        // 0 (epoch) is a very old date — should return an MM/dd formatted string,
        // not crash. We don't assert the exact string because it depends on the
        // current date and timezone, but it must be non-empty and not one of the
        // relative strings.
        val result = SessionTreeBuilder.formatRelativeTime(0L)
        result.isNotBlank() shouldBe true
        // Epoch (1970-01-01) is far more than 48 hours ago, so it should NOT be
        // "just now", "Xm ago", "Xh ago", or "Yesterday".
        (result == "just now" || result.endsWith("m ago") || result.endsWith("h ago") || result == "Yesterday") shouldBe false
    }

    @Test
    fun `formatRelativeTime 5 minutes ago returns 5m ago`() {
        val now = System.currentTimeMillis()
        val fiveMinutesAgo = now - 5 * 60_000L
        SessionTreeBuilder.formatRelativeTime(fiveMinutesAgo) shouldBe "5m ago"
    }

    @Test
    fun `formatRelativeTime 2 hours ago returns 2h ago`() {
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - 2 * 3_600_000L
        SessionTreeBuilder.formatRelativeTime(twoHoursAgo) shouldBe "2h ago"
    }
}