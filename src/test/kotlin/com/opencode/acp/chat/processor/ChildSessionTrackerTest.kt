package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.SessionItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ChildSessionTracker] (TDD §4.2.6).
 *
 * Tests child session ephemeral state: hidden/known/pending sets,
 * child→parent reverse index, agent labels, and pruning.
 * No mocking — uses real SessionItem instances and a simple active-session provider.
 */
class ChildSessionTrackerTest {

    private var activeSessionId: String? = null
    private lateinit var tracker: ChildSessionTracker

    @BeforeEach
    fun setUp() {
        activeSessionId = null
        tracker = ChildSessionTracker { activeSessionId }
    }

    private fun makeSession(id: String, parentID: String? = null) = SessionItem(
        id = id,
        title = "Session $id",
        updatedAt = 0L,
        cost = 0.0,
        inputTokens = 0L,
        outputTokens = 0L,
        parentID = parentID,
    )

    // ── markChildSessionComplete / unhide ─────────────────────────────────

    @Test
    fun `markChildSessionComplete adds to hidden set`() {
        tracker.markChildSessionComplete("child_1")
        tracker.hiddenChildSessionIds.value shouldBe setOf("child_1")
    }

    @Test
    fun `markChildSessionComplete skips active session`() {
        activeSessionId = "child_1"
        tracker.markChildSessionComplete("child_1")
        tracker.hiddenChildSessionIds.value shouldBe emptySet()
    }

    @Test
    fun `markChildSessionComplete does not skip non-active session`() {
        activeSessionId = "other_session"
        tracker.markChildSessionComplete("child_1")
        tracker.hiddenChildSessionIds.value shouldBe setOf("child_1")
    }

    @Test
    fun `unhideChildSession removes from hidden set`() {
        tracker.markChildSessionComplete("child_1")
        tracker.markChildSessionComplete("child_2")
        tracker.unhideChildSession("child_1")
        tracker.hiddenChildSessionIds.value shouldBe setOf("child_2")
    }

    @Test
    fun `unhideChildSession on non-hidden session is no-op`() {
        tracker.unhideChildSession("never_hidden")
        tracker.hiddenChildSessionIds.value shouldBe emptySet()
    }

    // ── knownChildSessionIds ──────────────────────────────────────────────

    @Test
    fun `addKnownChild adds to known set`() {
        tracker.addKnownChild("child_1")
        tracker.isKnownChild("child_1") shouldBe true
    }

    @Test
    fun `isKnownChild returns false for unknown session`() {
        tracker.isKnownChild("unknown") shouldBe false
    }

    @Test
    fun `addKnownChildrenFromItems adds only child sessions (with parentID)`() {
        val items = listOf(
            makeSession("parent_1"),
            makeSession("child_1", parentID = "parent_1"),
            makeSession("child_2", parentID = "parent_1"),
        )
        tracker.addKnownChildrenFromItems(items)
        tracker.isKnownChild("child_1") shouldBe true
        tracker.isKnownChild("child_2") shouldBe true
        tracker.isKnownChild("parent_1") shouldBe false
    }

    // ── childToParent reverse index ───────────────────────────────────────

    @Test
    fun `updateChildToParent builds reverse index from items`() {
        val items = listOf(
            makeSession("child_1", parentID = "parent_1"),
            makeSession("child_2", parentID = "parent_2"),
        )
        tracker.updateChildToParent(items)
        tracker.childToParent.value shouldBe mapOf(
            "child_1" to "parent_1",
            "child_2" to "parent_2",
        )
    }

    @Test
    fun `updateChildToParent skips items without parentID`() {
        val items = listOf(
            makeSession("parent_1"),
            makeSession("child_1", parentID = "parent_1"),
        )
        tracker.updateChildToParent(items)
        tracker.childToParent.value shouldBe mapOf("child_1" to "parent_1")
    }

    @Test
    fun `updateChildToParent merges with existing entries`() {
        tracker.updateChildToParent(listOf(makeSession("child_1", parentID = "parent_1")))
        tracker.updateChildToParent(listOf(makeSession("child_2", parentID = "parent_2")))
        tracker.childToParent.value shouldBe mapOf(
            "child_1" to "parent_1",
            "child_2" to "parent_2",
        )
    }

    @Test
    fun `getParentSession uses reverse index first`() {
        tracker.updateChildToParent(listOf(makeSession("child_1", parentID = "parent_1")))
        tracker.getParentSession("child_1", emptyMap()) shouldBe "parent_1"
    }

    @Test
    fun `getParentSession falls back to scanning childSessionMap`() {
        val childMap = mapOf(
            "parent_1" to listOf(makeSession("child_1", parentID = "parent_1")),
        )
        tracker.getParentSession("child_1", childMap) shouldBe "parent_1"
    }

    @Test
    fun `getParentSession returns null when not found`() {
        tracker.getParentSession("unknown", emptyMap()) shouldBe null
    }

    // ── childAgentLabels ──────────────────────────────────────────────────

    @Test
    fun `setChildAgentLabel stores label`() {
        tracker.setChildAgentLabel("child_1", "fixer")
        tracker.getChildAgentLabel("child_1") shouldBe "fixer"
    }

    @Test
    fun `getChildAgentLabel returns null for unknown child`() {
        tracker.getChildAgentLabel("unknown") shouldBe null
    }

    @Test
    fun `setChildAgentLabel overwrites existing label`() {
        tracker.setChildAgentLabel("child_1", "fixer")
        tracker.setChildAgentLabel("child_1", "explorer")
        tracker.getChildAgentLabel("child_1") shouldBe "explorer"
    }

    // ── childPendingPermissions ──────────────────────────────────────────

    @Test
    fun `markChildPendingPermission adds to pending set`() {
        tracker.markChildPendingPermission("child_1")
        tracker.hasPendingPermission("child_1") shouldBe true
    }

    @Test
    fun `clearChildPendingPermission removes from pending set`() {
        tracker.markChildPendingPermission("child_1")
        tracker.clearChildPendingPermission("child_1")
        tracker.hasPendingPermission("child_1") shouldBe false
    }

    @Test
    fun `hasPendingPermission returns false for unknown session`() {
        tracker.hasPendingPermission("unknown") shouldBe false
    }

    // ── pruneDeleted ──────────────────────────────────────────────────────

    @Test
    fun `pruneDeleted removes hidden IDs not in current set`() {
        tracker.markChildSessionComplete("child_1")
        tracker.markChildSessionComplete("child_2")
        tracker.pruneDeleted(setOf("child_1"))
        tracker.hiddenChildSessionIds.value shouldBe setOf("child_1")
    }

    @Test
    fun `pruneDeleted removes known IDs not in current set`() {
        tracker.addKnownChild("child_1")
        tracker.addKnownChild("child_2")
        tracker.pruneDeleted(setOf("child_1"))
        tracker.isKnownChild("child_1") shouldBe true
        tracker.isKnownChild("child_2") shouldBe false
    }

    @Test
    fun `pruneDeleted with empty current set clears all`() {
        tracker.markChildSessionComplete("child_1")
        tracker.addKnownChild("child_2")
        tracker.pruneDeleted(emptySet())
        tracker.hiddenChildSessionIds.value shouldBe emptySet()
        tracker.isKnownChild("child_2") shouldBe false
    }

    @Test
    fun `pruneDeleted with all current IDs preserves all`() {
        tracker.markChildSessionComplete("child_1")
        tracker.addKnownChild("child_2")
        tracker.pruneDeleted(setOf("child_1", "child_2"))
        tracker.hiddenChildSessionIds.value shouldBe setOf("child_1")
        tracker.isKnownChild("child_2") shouldBe true
    }
}