package com.opencode.acp.chat.processor

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [FollowAgentDispatcher].
 *
 * BLOCKER: These tests require IntelliJ Platform infrastructure because
 * `EditorFollowManager.getInstance(project)`, `CommandFollowManager.getInstance(project)`,
 * and `SearchFollowManager.getInstance(project)` use IntelliJ service lookup.
 *
 * TO ENABLE: Use `BasePlatformTestCase` which provides a real `Project` instance,
 * or mock the Follow Agent managers via mockk and inject them.
 *
 * Tracked as test coverage gap — FollowAgentDispatcher consolidates ~170 lines of
 * previously-duplicated Follow Agent code (TDD §7.1.1).
 */
@Disabled("Requires IntelliJ Platform Project stub — see TODOs in file. " +
    "To enable: (1) use BasePlatformTestCase, or " +
    "(2) mock EditorFollowManager/CommandFollowManager/SearchFollowManager via mockk.")
class FollowAgentDispatcherTest {

    // Test cases (from TDD §7.1.1):
    // - dispatchToolUse primary path → EditorFollowManager.followToolCall called
    // - dispatchToolUse duplicate path with OTHER kind → kind re-detected from input
    // - dispatchToolUse EXECUTE → CommandFollowManager.followCommand called
    // - dispatchToolUse SEARCH → SearchFollowManager.followSearch called
    // - dispatchToolUse EDIT → ChatFileChange added to toolCallState.pendingFileChanges
    // - dispatchToolResult orphan path → FileChanged signal emitted for EDIT
    // - extractFilePath → canonicalized path from input JSON
    // - extractLineRange → start/end line pair from input JSON

    @Test
    fun `placeholder`() {
        // Tests deferred until IntelliJ Platform test infrastructure is available.
    }
}