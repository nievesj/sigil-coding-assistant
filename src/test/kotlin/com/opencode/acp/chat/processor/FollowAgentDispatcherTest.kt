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
import io.kotest.matchers.string.shouldContain
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
        dispatcher.extractFilePath(input) shouldBe "/tmp/test.txt"
    }

    @Test
    fun `extractFilePath returns path from filePath key`() {
        val input = buildJsonObject { put("filePath", "/tmp/test.txt") }
        dispatcher.extractFilePath(input) shouldBe "/tmp/test.txt"
    }

    @Test
    fun `extractFilePath returns path from path key`() {
        val input = buildJsonObject { put("path", "/tmp/test.txt") }
        dispatcher.extractFilePath(input) shouldBe "/tmp/test.txt"
    }

    @Test
    fun `extractFilePath prefers file_path over filePath`() {
        val input = buildJsonObject {
            put("file_path", "/first")
            put("filePath", "/second")
        }
        val result = dispatcher.extractFilePath(input)
        result shouldBe "/first"
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
    fun `extractFilePath returns raw path without canonicalizing`() {
        // extractFilePath now returns the raw path — canonicalization is delegated
        // to EditorFollowManager.resolveVirtualFile, which canonicalizes against
        // project.basePath (the correct base for relative paths). Canonicalizing
        // here would resolve relative paths against the JVM's user.dir, which is
        // NOT guaranteed to match project.basePath.
        val input = buildJsonObject { put("file_path", "/tmp/../tmp/test.txt") }
        val result = dispatcher.extractFilePath(input)
        result shouldNotBe null
        // Raw path is returned as-is — .. sequences are preserved for the caller
        // (EditorFollowManager.resolveVirtualFile) to canonicalize with the
        // correct project base.
        result shouldBe "/tmp/../tmp/test.txt"
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
    fun `extractLineRange converts 0-indexed offset to 1-indexed start line`() {
        // OpenCode's read tool uses 'offset' as a 0-indexed line offset.
        // extractLineRange converts to 1-indexed for display/navigation.
        val input = buildJsonObject { put("offset", 10) }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 11 // 0-indexed 10 → 1-indexed 11
    }

    @Test
    fun `extractLineRange computes end from 0-indexed offset and limit`() {
        val input = buildJsonObject {
            put("offset", 10)
            put("limit", 5)
        }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 11 // 0-indexed 10 → 1-indexed 11
        end shouldBe 15 // 11 + 5 - 1
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
        start shouldBe 11 // 0-indexed 10 → 1-indexed 11
        end shouldBe 0
    }

    @Test
    fun `extractLineRange returns 1 0 when offset is 0`() {
        // 0-indexed offset 0 (first line) → 1-indexed startLine 1.
        // endLine is 0 because no limit is specified.
        val input = buildJsonObject { put("offset", 0) }
        val (start, end) = dispatcher.extractLineRange(input)
        start shouldBe 1 // 0-indexed 0 → 1-indexed 1
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
    fun `dispatchToolUse EDIT normalizes filePath against project base`() {
        // Set up a non-null basePath on the mock project
        every { project.basePath } returns "/project/root"
        val input = buildJsonObject {
            put("file_path", "src/main/Foo.kt")
            put("old_string", "old")
            put("new_string", "new")
        }
        dispatcher.dispatchToolUse(
            toolCallId = "tc_edit_norm",
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
        // The path should be normalized against the project base
        change.filePath shouldContain "Foo.kt"
        change.fileName shouldBe "Foo.kt"
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

    // ── dispatchToolUse (EXECUTE path — command console) ──────────────────

    @Test
    fun `dispatchToolUse EXECUTE with command calls CommandFollowManager`() {
        val input = buildJsonObject { put("command", "ls -la") }
        // Mockk the CommandFollowManager singleton — the dispatcher calls
        // CommandFollowManager.getInstance(project).followCommand(...).
        val mockCmdManager = mockk<CommandFollowManager>(relaxed = true)
        mockkObject(CommandFollowManager.Companion)
        every { CommandFollowManager.getInstance(any()) } returns mockCmdManager
        try {
            dispatcher.dispatchToolUse(
                toolCallId = "tc_exec_1",
                toolName = "bash",
                toolKind = ToolKind.EXECUTE,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockCmdManager.followCommand(
                    project = project,
                    toolCallId = "tc_exec_1",
                    command = "ls -la",
                    workdir = null,
                    description = null,
                    agentName = null,
                    modelName = null,
                )
            }
        } finally {
            unmockkObject(CommandFollowManager.Companion)
        }
    }

    @Test
    fun `dispatchToolUse EXECUTE with all fields passes them to CommandFollowManager`() {
        val input = buildJsonObject {
            put("command", "ls -la")
            put("workdir", "/tmp")
            put("description", "list files")
        }
        val mockCmdManager = mockk<CommandFollowManager>(relaxed = true)
        mockkObject(CommandFollowManager.Companion)
        every { CommandFollowManager.getInstance(any()) } returns mockCmdManager
        try {
            toolCallState.activeAgentName = "fixer"
            turnLifecycleState.modelID = "claude-3"
            dispatcher.dispatchToolUse(
                toolCallId = "tc_exec_full",
                toolName = "bash",
                toolKind = ToolKind.EXECUTE,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockCmdManager.followCommand(
                    project = project,
                    toolCallId = "tc_exec_full",
                    command = "ls -la",
                    workdir = "/tmp",
                    description = "list files",
                    agentName = "fixer",
                    modelName = "claude-3",
                )
            }
        } finally {
            unmockkObject(CommandFollowManager.Companion)
        }
    }

    // ── dispatchToolUse (SEARCH path — Find in Files) ─────────────────────

    @Test
    fun `dispatchToolUse SEARCH with pattern calls SearchFollowManager`() {
        val input = buildJsonObject { put("pattern", "TODO") }
        val mockSearchManager = mockk<SearchFollowManager>(relaxed = true)
        mockkObject(SearchFollowManager.Companion)
        every { SearchFollowManager.getInstance(any()) } returns mockSearchManager
        try {
            dispatcher.dispatchToolUse(
                toolCallId = "tc_search_1",
                toolName = "grep",
                toolKind = ToolKind.SEARCH,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockSearchManager.followSearch(
                    project = project,
                    pattern = "TODO",
                    searchPath = null,
                    includeGlob = null,
                    isRegex = false,
                    agentName = null,
                    modelName = null,
                )
            }
        } finally {
            unmockkObject(SearchFollowManager.Companion)
        }
    }

    @Test
    fun `dispatchToolUse SEARCH with query key calls SearchFollowManager`() {
        val input = buildJsonObject { put("query", "TODO") }
        val mockSearchManager = mockk<SearchFollowManager>(relaxed = true)
        mockkObject(SearchFollowManager.Companion)
        every { SearchFollowManager.getInstance(any()) } returns mockSearchManager
        try {
            dispatcher.dispatchToolUse(
                toolCallId = "tc_search_query",
                toolName = "grep",
                toolKind = ToolKind.SEARCH,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockSearchManager.followSearch(
                    project = project,
                    pattern = "TODO",
                    searchPath = null,
                    includeGlob = null,
                    isRegex = false,
                    agentName = null,
                    modelName = null,
                )
            }
        } finally {
            unmockkObject(SearchFollowManager.Companion)
        }
    }

    @Test
    fun `dispatchToolUse SEARCH with glob key passes it as includeGlob`() {
        val input = buildJsonObject {
            put("pattern", "TODO")
            put("glob", "*.kt")
        }
        val mockSearchManager = mockk<SearchFollowManager>(relaxed = true)
        mockkObject(SearchFollowManager.Companion)
        every { SearchFollowManager.getInstance(any()) } returns mockSearchManager
        try {
            dispatcher.dispatchToolUse(
                toolCallId = "tc_search_glob",
                toolName = "grep",
                toolKind = ToolKind.SEARCH,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockSearchManager.followSearch(
                    project = project,
                    pattern = "TODO",
                    searchPath = null,
                    includeGlob = "*.kt",
                    isRegex = false,
                    agentName = null,
                    modelName = null,
                )
            }
        } finally {
            unmockkObject(SearchFollowManager.Companion)
        }
    }

    @Test
    fun `dispatchToolUse SEARCH with pattern_regex flag passes isRegex true`() {
        val input = buildJsonObject {
            put("pattern", "foo.*bar")
            put("pattern_regex", true)
        }
        val mockSearchManager = mockk<SearchFollowManager>(relaxed = true)
        mockkObject(SearchFollowManager.Companion)
        every { SearchFollowManager.getInstance(any()) } returns mockSearchManager
        try {
            dispatcher.dispatchToolUse(
                toolCallId = "tc_search_regex",
                toolName = "grep",
                toolKind = ToolKind.SEARCH,
                input = input,
                metadata = null,
                startTimeMs = null,
                isDuplicate = false,
                existingPill = null,
            )
            verify {
                mockSearchManager.followSearch(
                    project = project,
                    pattern = "foo.*bar",
                    searchPath = null,
                    includeGlob = null,
                    isRegex = true,
                    agentName = null,
                    modelName = null,
                )
            }
        } finally {
            unmockkObject(SearchFollowManager.Companion)
        }
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

    // ── dispatchToolResult (non-orphan SEARCH and EXECUTE) ────────────────

    @Test
    fun `dispatchToolResult non-orphan SEARCH calls EditorFollowManager followToolResult`() {
        val mockEditorManager = mockk<EditorFollowManager>(relaxed = true)
        mockkObject(EditorFollowManager.Companion)
        every { EditorFollowManager.getInstance(any()) } returns mockEditorManager
        try {
            dispatcher.dispatchToolResult(
                toolCallId = "tc_search_1",
                resolvedKind = ToolKind.SEARCH,
                content = null,
                isError = false,
                isOrphan = false,
                input = null,
                metadata = null,
                signals = MutableSharedFlow(extraBufferCapacity = 10),
            )
            verify {
                mockEditorManager.followToolResult(
                    project = project,
                    toolCallId = "tc_search_1",
                    output = null,
                    kind = ToolKind.SEARCH,
                    agentName = null,
                    modelName = null,
                    input = null,
                )
            }
        } finally {
            unmockkObject(EditorFollowManager.Companion)
        }
    }

    @Test
    fun `dispatchToolResult non-orphan EXECUTE calls CommandFollowManager`() {
        val mockCmdManager = mockk<CommandFollowManager>(relaxed = true)
        mockkObject(CommandFollowManager.Companion)
        every { CommandFollowManager.getInstance(any()) } returns mockCmdManager
        try {
            dispatcher.dispatchToolResult(
                toolCallId = "tc_exec_1",
                resolvedKind = ToolKind.EXECUTE,
                content = null,
                isError = false,
                isOrphan = false,
                input = null,
                metadata = null,
                signals = MutableSharedFlow(extraBufferCapacity = 10),
            )
            verify {
                mockCmdManager.followCommandResult(
                    project = project,
                    toolCallId = "tc_exec_1",
                    output = null,
                    isError = false,
                )
                mockCmdManager.finishCommand(
                    project = project,
                    toolCallId = "tc_exec_1",
                    isError = false,
                )
            }
        } finally {
            unmockkObject(CommandFollowManager.Companion)
        }
    }

    // ── Interface compliance ──────────────────────────────────────────────

    @Test
    fun `FollowAgentDispatcher implements FollowAgentDispatcherInterface`() {
        dispatcher shouldNotBe null
        (dispatcher is FollowAgentDispatcherInterface) shouldBe true
    }
}