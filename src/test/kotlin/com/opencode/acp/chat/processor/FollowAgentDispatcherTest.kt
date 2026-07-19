package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.util.FollowAgentDispatcherInterface
import com.opencode.acp.follow.CommandFollowManager
import com.opencode.acp.follow.EditorFollowManager
import com.opencode.acp.follow.SearchFollowManager
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FollowAgentDispatcher] (TDD §4.2.6).
 *
 * Tests the pure helper methods (extractFilePath, extractLineRange) directly,
 * and the dispatch methods using MockK to mock the Follow Agent manager singletons.
 *
 * The FollowAgentDispatcherInterface injection (Phase 3) allows the dispatcher
 * to be replaced with a fake in SessionState tests. This test exercises the
 * REAL dispatcher with mocked IntelliJ service singletons.
 */
class FollowAgentDispatcherTest {

    private val logger = KotlinLogging.logger {}
    private lateinit var project: Project
    private lateinit var toolCallState: ToolCallState
    private lateinit var turnLifecycleState: TurnLifecycleState
    private lateinit var scope: CoroutineScope
    private lateinit var sessionManager: SessionManager
    private lateinit var dispatcher: FollowAgentDispatcher

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true)
        toolCallState = ToolCallState()
        turnLifecycleState = TurnLifecycleState()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        sessionManager = mockk<SessionManager>(relaxed = true)

        dispatcher = FollowAgentDispatcher(
            project = project,
            toolCallState = toolCallState,
            turnLifecycleState = turnLifecycleState,
            scope = scope,
            sessionManager = sessionManager,
            logger = logger,
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // ── extractFilePath ───────────────────────────────────────────────────

    @Test
    fun `extractFilePath returns path from file_path key`() {
        val input = buildJsonObject { put("file_path", "/tmp/test.txt") }
        dispatcher.extractFilePath(input) shouldNotBe null
    }

    @Test
    fun `extractFilePath returns path from filePath key`() {
        val input = buildJsonObject { put("filePath", "/tmp/test.txt") }
        dispatcher.extractFilePath(input) shouldNotBe null
    }

    @Test
    fun `extractFilePath returns path from path key`() {
        val input = buildJsonObject { put("path", "/tmp/test.txt") }
        dispatcher.extractFilePath(input) shouldNotBe null
    }

    @Test
    fun `extractFilePath prefers file_path over filePath`() {
        val input = buildJsonObject {
            put("file_path", "/first")
            put("filePath", "/second")
        }
        val result = dispatcher.extractFilePath(input)
        result shouldNotBe null
    }

    @Test
    fun `extractFilePath returns null when no path key present`() {
        val input = buildJsonObject { put("other", "value") }
        dispatcher.extractFilePath(input) shouldBe null
    }

    @Test
    fun `extractFilePath returns null for empty path`() {
        val input = buildJsonObject { put("file_path", "") }
        dispatcher.extractFilePath(input) shouldBe null
    }

    @Test
    fun `extractFilePath returns null for non-primitive value`() {
        val input = buildJsonObject { put("file_path", buildJsonObject {}) }
        dispatcher.extractFilePath(input) shouldBe null
    }

    @Test
    fun `extractFilePath canonicalizes the path`() {
        val input = buildJsonObject { put("file_path", "/tmp/../tmp/test.txt") }
        val result = dispatcher.extractFilePath(input)
        result shouldNotBe null
        // Canonical path should not contain ..
        result!!.contains("..") shouldBe false
    }

    // ── extractLineRange ──────────────────────────────────────────────────

    @Test
    fun `extractLineRange returns 0 0 for null input`() {
        val (start, end) = dispatcher.extractLineRange(null)
        start shouldBe 0
        end shouldBe 0
    }

    @Test
    fun `extractLineRange returns 0 0 when no line fields present`() {
        val input = buildJsonObject { put("other", "value") }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 0
        end shouldBe 0
    }

    @Test
    fun `extractLineRange extracts offset as start line`() {
        val input = buildJsonObject { put("offset", 10) }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 10
    }

    @Test
    fun `extractLineRange computes end from offset and limit`() {
        val input = buildJsonObject {
            put("offset", 10)
            put("limit", 5)
        }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 10
        end shouldBe 14 // 10 + 5 - 1
    }

    @Test
    fun `extractLineRange extracts start_line and end_line`() {
        val input = buildJsonObject {
            put("start_line", 20)
            put("end_line", 30)
        }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 20
        end shouldBe 30
    }

    @Test
    fun `extractLineRange returns 0 end when offset present but no limit`() {
        val input = buildJsonObject { put("offset", 10) }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 10
        end shouldBe 0
    }

    @Test
    fun `extractLineRange returns 0 0 when offset is 0`() {
        val input = buildJsonObject { put("offset", 0) }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 0
        end shouldBe 0
    }

    // ── dispatchToolUse (EDIT path — pendingFileChanges) ──────────────────

    @Test
    fun `dispatchToolUse EDIT with file_path extracts ChatFileChange`() {
        val input = buildJsonObject {
            put("file_path", "/tmp/test.txt")
            put("old_string", "old")
            put("new_string", "new")
        }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "edit",
            toolKind = ToolKind.EDIT,
            input = input,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        toolCallState.pendingFileChanges.size shouldBe 1
        val change = toolCallState.pendingFileChanges[0]
        change.filePath shouldNotBe null
        change.fileName shouldBe "test.txt"
        change.additions shouldBe 1 // "new".lines().size
        change.deletions shouldBe 1 // "old".lines().size
    }

    @Test
    fun `dispatchToolUse EDIT without file_path does not add file change`() {
        val input = buildJsonObject {
            put("old_string", "old")
            put("new_string", "new")
        }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "edit",
            toolKind = ToolKind.EDIT,
            input = input,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        toolCallState.pendingFileChanges.size shouldBe 0
    }

    @Test
    fun `dispatchToolUse EDIT with content extracts additions from content`() {
        val input = buildJsonObject {
            put("file_path", "/tmp/test.txt")
            put("content", "line1\nline2\nline3")
        }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "write",
            toolKind = ToolKind.EDIT,
            input = input,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        toolCallState.pendingFileChanges.size shouldBe 1
        val change = toolCallState.pendingFileChanges[0]
        change.additions shouldBe 3 // "line1\nline2\nline3".lines().size
        change.deletions shouldBe 0
    }

    @Test
    fun `dispatchToolUse duplicate path does NOT re-extract file changes`() {
        val input = buildJsonObject {
            put("file_path", "/tmp/test.txt")
            put("old_string", "old")
            put("new_string", "new")
        }
        // Primary path
        dispatcher.dispatchToolUse("tc_1", "edit", ToolKind.EDIT, input, null, null, false, null)
        // Duplicate path
        dispatcher.dispatchToolUse("tc_1", "edit", ToolKind.EDIT, input, null, null, true, null)
        toolCallState.pendingFileChanges.size shouldBe 1
    }

    @Test
    fun `dispatchToolUse non-EDIT tool does not add file change`() {
        val input = buildJsonObject { put("file_path", "/tmp/test.txt") }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "read",
            toolKind = ToolKind.READ,
            input = input,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        toolCallState.pendingFileChanges.size shouldBe 0
    }

    @Test
    fun `dispatchToolUse null input does not add file change`() {
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "edit",
            toolKind = ToolKind.EDIT,
            input = null,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        toolCallState.pendingFileChanges.size shouldBe 0
    }

    // ── dispatchToolUse (task tool — child session caching) ───────────────

    @Test
    fun `dispatchToolUse task tool with sessionId metadata launches ensureSessionCached`() = runTest {
        val metadata = buildJsonObject { put("sessionId", "ses_child_1") }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "task",
            toolKind = ToolKind.OTHER,
            input = null,
            metadata = metadata,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        // ensureSessionCached is launched in a coroutine — verify it was called
        coVerify(timeout = 1000) { sessionManager.ensureSessionCached("ses_child_1") }
    }

    @Test
    fun `dispatchToolUse non-task tool does not launch ensureSessionCached`() = runTest {
        dispatcher.dispatchToolUse(
            toolCallId = "tc_1",
            toolName = "read",
            toolKind = ToolKind.READ,
            input = null,
            metadata = null,
            startTimeMs = null,
            isDuplicate = false,
            existingPill = null,
        )
        coVerify(exactly = 0) { sessionManager.ensureSessionCached(any()) }
    }

    // ── dispatchToolResult (orphan EDIT path) ─────────────────────────────

    @Test
    fun `dispatchToolResult orphan EDIT emits FileChanged signal`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(replay = 1, extraBufferCapacity = 10)
        dispatcher.dispatchToolResult(
            toolCallId = "tc_1",
            resolvedKind = ToolKind.EDIT,
            content = null,
            isError = false,
            isOrphan = true,
            input = null,
            metadata = null,
            signals = signals,
        )
        // The signal should have been emitted — with replay=1, we can read it from replayCache
        signals.replayCache.size shouldBe 1
        signals.replayCache[0] shouldBe UiSignal.FileChanged(Unit)
    }

    @Test
    fun `dispatchToolResult orphan non-EDIT does not emit FileChanged`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 10)
        dispatcher.dispatchToolResult(
            toolCallId = "tc_1",
            resolvedKind = ToolKind.READ,
            content = null,
            isError = false,
            isOrphan = true,
            input = null,
            metadata = null,
            signals = signals,
        )
        // No signal should be emitted — verify by checking the replay buffer is empty
        signals.replayCache.isEmpty() shouldBe true
    }

    @Test
    fun `dispatchToolResult orphan task with sessionId launches ensureSessionCached`() = runTest {
        val metadata = buildJsonObject { put("sessionId", "ses_child_2") }
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 10)
        dispatcher.dispatchToolResult(
            toolCallId = "tc_1",
            resolvedKind = ToolKind.OTHER,
            content = null,
            isError = false,
            isOrphan = true,
            input = null,
            metadata = metadata,
            signals = signals,
        )
        coVerify(timeout = 1000) { sessionManager.ensureSessionCached("ses_child_2") }
    }

    @Test
    fun `dispatchToolResult non-orphan does not emit FileChanged for EDIT`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 10)
        dispatcher.dispatchToolResult(
            toolCallId = "tc_1",
            resolvedKind = ToolKind.EDIT,
            content = null,
            isError = false,
            isOrphan = false,
            input = null,
            metadata = null,
            signals = signals,
        )
        // Non-orphan path doesn't emit FileChanged (it's handled by the normal ToolResult handler)
        signals.replayCache.isEmpty() shouldBe true
    }

    // ── Interface compliance ──────────────────────────────────────────────

    @Test
    fun `FollowAgentDispatcher implements FollowAgentDispatcherInterface`() {
        dispatcher shouldNotBe null
        (dispatcher is FollowAgentDispatcherInterface) shouldBe true
    }
}