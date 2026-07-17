package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.SseQuestionInfo
import com.opencode.acp.SseQuestionOption
import com.opencode.acp.SseTodoItem
import com.opencode.acp.chat.model.TodoItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SseEventPipeline] (TDD §4.2.6).
 *
 * Tests event routing: non-streaming events return early, ResetTurn resets
 * and applies turn identity, and streaming events dispatch to the correct
 * SessionState handler methods.
 *
 * Uses [mockk<SessionState>(relaxed = true)] with real [TurnLifecycleState]
 * and [ToolCallState] injected via stubbing so the pipeline's state reads/writes
 * work against real objects. Handler calls are verified with [verify].
 */
class SseEventPipelineTest {

    private val logger = KotlinLogging.logger {}
    private lateinit var pipeline: SseEventPipeline

    private lateinit var sessionState: SessionState
    private val turnLifecycleState = TurnLifecycleState()
    private val toolCallState = ToolCallState()

    @BeforeEach
    fun setUp() {
        sessionState = mockk<SessionState>(relaxed = true)
        // Inject real state objects so the pipeline can read/write them
        every { sessionState.turnLifecycleState } returns turnLifecycleState
        every { sessionState.toolCallState } returns toolCallState
        every { sessionState.sessionId } returns "ses_test"
        // pendingTurnIdentity is a var delegate — stub getter/setter
        every { sessionState.pendingTurnIdentity } returns null
        every { sessionState.pendingTurnIdentity = any() } just runs

        pipeline = SseEventPipeline(logger)
    }

    private fun ctx(): SseEventContext = SseEventContext(sessionState)

    // ── Non-streaming events (return early) ────────────────────────────────

    @Test
    fun `TodoUpdated emits TodoUpdated signal and returns`() {
        val event = SseEvent.TodoUpdated(
            sessionId = "ses_test",
            todos = listOf(SseTodoItem(content = "Task", status = "pending", priority = "high")),
        )
        pipeline.process(event, ctx())
        verify {
            sessionState.emitSignal(match<UiSignal.TodoUpdated> {
                it.todos.size == 1 && it.todos[0].content == "Task" &&
                    it.todos[0].status == "pending" && it.todos[0].priority == "high"
            })
        }
    }

    @Test
    fun `SessionCreated emits SessionCreated signal and returns`() {
        val event = SseEvent.SessionCreated(sessionId = "ses_test")
        pipeline.process(event, ctx())
        verify { sessionState.emitSignal(UiSignal.SessionCreated("ses_test")) }
    }

    @Test
    fun `SessionIdle with no streaming returns without finalizing`() {
        turnLifecycleState.isStreaming = false
        val event = SseEvent.SessionIdle(sessionId = "ses_test")
        pipeline.process(event, ctx())
        // finalizeStreaming should NOT be called
        verify(exactly = 0) { sessionState.finalizeStreaming(any(), any()) }
    }

    @Test
    fun `SessionIdle with streaming finalizes stuck state`() {
        turnLifecycleState.isStreaming = true
        turnLifecycleState.activeMessageId = "msg_1"
        val event = SseEvent.SessionIdle(sessionId = "ses_test")
        pipeline.process(event, ctx())
        verify { sessionState.finalizeStreaming("msg_1", "idle") }
    }

    @Test
    fun `MessageRemoved with null messageId returns early`() {
        val event = SseEvent.MessageRemoved(sessionId = "ses_test", messageId = null)
        pipeline.process(event, ctx())
        verify(exactly = 0) { sessionState.removeMessageByServerId(any()) }
    }

    @Test
    fun `MessageRemoved with messageId calls removeMessageByServerId`() {
        val event = SseEvent.MessageRemoved(sessionId = "ses_test", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.removeMessageByServerId("srv_1") }
    }

    @Test
    fun `QuestionAsked calls handleQuestionAsked and returns`() {
        val event = SseEvent.QuestionAsked(
            sessionId = "ses_test",
            requestId = "req_1",
            questions = listOf(
                SseQuestionInfo(
                    question = "Pick one",
                    header = "Choice",
                    options = listOf(SseQuestionOption(label = "A", description = "desc")),
                )
            ),
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleQuestionAsked(event) }
    }

    @Test
    fun `SessionError calls handleSessionError and returns`() {
        val event = SseEvent.SessionError(sessionId = "ses_test", errorMessage = "boom")
        pipeline.process(event, ctx())
        verify { sessionState.handleSessionError(event) }
    }

    @Test
    fun `Ignored event returns early without handler calls`() {
        val event = SseEvent.Ignored(sessionId = "ses_test", eventType = "unknown", reason = "test")
        pipeline.process(event, ctx())
        // No handler should be called — verify no interactions with handler methods
        verify(exactly = 0) { sessionState.handleTextChunk(any(), any()) }
        verify(exactly = 0) { sessionState.handleToolUse(any(), any()) }
    }

    @Test
    fun `UserMessage returns early`() {
        val event = SseEvent.UserMessage(sessionId = "ses_test", text = "hello")
        pipeline.process(event, ctx())
        verify(exactly = 0) { sessionState.handleTextChunk(any(), any()) }
    }

    @Test
    fun `Plan returns early`() {
        val event = SseEvent.Plan(sessionId = "ses_test", entries = emptyList())
        pipeline.process(event, ctx())
        verify(exactly = 0) { sessionState.handleTextChunk(any(), any()) }
    }

    @Test
    fun `MessageComplete returns early`() {
        val event = SseEvent.MessageComplete(sessionId = "ses_test", messageId = "m1")
        pipeline.process(event, ctx())
        verify(exactly = 0) { sessionState.handleTextChunk(any(), any()) }
    }

    @Test
    fun `SessionCompacted returns early without state mutation`() {
        val event = SseEvent.SessionCompacted(sessionId = "ses_test")
        pipeline.process(event, ctx())
        verify(exactly = 0) { sessionState.removeMessageByServerId(any()) }
        verify(exactly = 0) { sessionState.finalizeStreaming(any(), any()) }
    }

    // ── ResetTurn ──────────────────────────────────────────────────────────

    @Test
    fun `ResetTurn resets turn state and applies new turn identity`() {
        // Pre-populate some stale state
        turnLifecycleState.activeMessageId = "old_msg"
        turnLifecycleState.isStreaming = true
        toolCallState.toolPartStates["old_tool"] = com.opencode.acp.chat.model.PartState.InProgress

        val event = SseEvent.ResetTurn(
            sessionId = "ses_test",
            newTurnMessageId = "new_msg",
            newTurnServerMessageId = "srv_new",
            newTurnModelID = "model-1",
            newTurnProviderID = "provider-1",
        )
        pipeline.process(event, ctx())

        // resetTurnState should have been called (clears toolPartStates)
        verify { sessionState.resetTurnState() }
        // New identity applied
        turnLifecycleState.activeMessageId shouldBe "new_msg"
        turnLifecycleState.activeServerMessageId shouldBe "srv_new"
        turnLifecycleState.modelID shouldBe "model-1"
        turnLifecycleState.providerID shouldBe "provider-1"
        turnLifecycleState.isStreaming shouldBe true
    }

    @Test
    fun `ResetTurn with null newTurnMessageId does not set isStreaming`() {
        val event = SseEvent.ResetTurn(
            sessionId = "ses_test",
            newTurnMessageId = null,
        )
        pipeline.process(event, ctx())
        verify { sessionState.resetTurnState() }
        turnLifecycleState.activeMessageId shouldBe null
        turnLifecycleState.isStreaming shouldBe false
    }

    @Test
    fun `ResetTurn does not overwrite activeServerMessageId when event carries none`() {
        // Simulate updateServerMessageId having already set the server ID
        turnLifecycleState.activeServerMessageId = "already_set"
        val event = SseEvent.ResetTurn(
            sessionId = "ses_test",
            newTurnMessageId = "msg_1",
            newTurnServerMessageId = null, // event doesn't carry a server ID
        )
        pipeline.process(event, ctx())
        // Should NOT overwrite the existing value
        turnLifecycleState.activeServerMessageId shouldBe "already_set"
    }

    // ── Streaming events dispatch to handlers ─────────────────────────────

    @Test
    fun `TextChunk with active message dispatches to handleTextChunk`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.TextChunk(sessionId = "ses_test", text = "hello", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleTextChunk(event, "msg_1") }
    }

    @Test
    fun `TextReplace dispatches to handleTextReplace`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.TextReplace(sessionId = "ses_test", text = "full", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleTextReplace(event, "msg_1") }
    }

    @Test
    fun `ThinkingChunk dispatches to handleThinkingChunk`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.ThinkingChunk(sessionId = "ses_test", text = "hmm", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleThinkingChunk(event, "msg_1") }
    }

    @Test
    fun `ThinkingReplace dispatches to handleThinkingReplace`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.ThinkingReplace(sessionId = "ses_test", text = "full think", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleThinkingReplace(event, "msg_1") }
    }

    @Test
    fun `ToolUse dispatches to handleToolUse`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.ToolUse(
            sessionId = "ses_test",
            toolCallId = "tc_1",
            toolName = "bash",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleToolUse(event, "msg_1") }
    }

    @Test
    fun `ToolResult dispatches to handleToolResult`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.ToolResult(
            sessionId = "ses_test",
            toolCallId = "tc_1",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleToolResult(event, "msg_1") }
    }

    @Test
    fun `Stop dispatches to handleStop`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Stop(sessionId = "ses_test", stopReason = "end_turn", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleStop(event, "msg_1") }
    }

    @Test
    fun `MessageFinalized dispatches to handleMessageFinalized`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.MessageFinalized(
            sessionId = "ses_test",
            messageId = "srv_1",
            inputTokens = 100,
            outputTokens = 50,
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleMessageFinalized(event, "msg_1") }
    }

    @Test
    fun `Error dispatches to handleError`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Error(sessionId = "ses_test", message = "fail", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleError(event, "msg_1") }
    }

    @Test
    fun `Patch dispatches to handlePatch`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Patch(
            sessionId = "ses_test",
            hash = "abc",
            files = listOf("a.kt"),
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handlePatch(event, "msg_1") }
    }

    @Test
    fun `Agent dispatches to handleAgent`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Agent(sessionId = "ses_test", agentName = "fixer", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleAgent(event, "msg_1") }
    }

    @Test
    fun `Retry dispatches to handleRetry`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Retry(
            sessionId = "ses_test",
            attempt = 1,
            maxAttempts = 3,
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleRetry(event, "msg_1") }
    }

    @Test
    fun `Compaction dispatches to handleCompaction`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Compaction(sessionId = "ses_test", summary = "s", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleCompaction(event, "msg_1") }
    }

    @Test
    fun `StepFinish dispatches to handleStepFinish`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.StepFinish(sessionId = "ses_test", messageId = "srv_1")
        pipeline.process(event, ctx())
        verify { sessionState.handleStepFinish(event, "msg_1") }
    }

    @Test
    fun `AssistantFile dispatches to handleAssistantFile`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.AssistantFile(
            sessionId = "ses_test",
            mime = "text/plain",
            url = "file:///tmp/x",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleAssistantFile(event, "msg_1") }
    }

    @Test
    fun `AssistantImage dispatches to handleAssistantImage`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.AssistantImage(
            sessionId = "ses_test",
            mime = "image/png",
            url = "file:///tmp/img.png",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleAssistantImage(event, "msg_1") }
    }

    @Test
    fun `Permission dispatches to handlePermission`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.Permission(
            sessionId = "ses_test",
            permissionId = "perm_1",
            toolCallId = "tc_1",
            action = "execute",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handlePermission(event, "msg_1") }
    }

    @Test
    fun `PermissionReplied dispatches to handlePermissionReplied`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.PermissionReplied(
            sessionId = "ses_test",
            permissionId = "perm_1",
            reply = "allow",
            messageId = "srv_1",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handlePermissionReplied(event) }
    }

    // ── Auto-create logic ──────────────────────────────────────────────────

    @Test
    fun `content event with no active message auto-creates assistant message`() {
        turnLifecycleState.activeMessageId = null
        turnLifecycleState.isStreaming = false
        val event = SseEvent.TextChunk(
            sessionId = "ses_test",
            text = "hello",
            messageId = "srv_new",
        )
        pipeline.process(event, ctx())
        verify { sessionState.createAssistantMessage(null, null, "srv_new", true) }
    }

    @Test
    fun `content event with no active message and no serverId auto-creates with null serverId`() {
        turnLifecycleState.activeMessageId = null
        turnLifecycleState.isStreaming = false
        val event = SseEvent.TextChunk(sessionId = "ses_test", text = "hi", messageId = null)
        pipeline.process(event, ctx())
        verify { sessionState.createAssistantMessage(null, null, null, true) }
    }

    // ── Server-ID routing ───────────────────────────────────────────────────

    @Test
    fun `event with mismatched serverId is skipped when activeServerId is set`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.activeServerMessageId = "srv_active"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.TextChunk(
            sessionId = "ses_test",
            text = "hello",
            messageId = "srv_different", // different from active
        )
        pipeline.process(event, ctx())
        // Should NOT call handleTextChunk (skipped by routing check)
        verify(exactly = 0) { sessionState.handleTextChunk(any(), any()) }
    }

    @Test
    fun `event with matching serverId is dispatched`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.activeServerMessageId = "srv_active"
        turnLifecycleState.isStreaming = true
        val event = SseEvent.TextChunk(
            sessionId = "ses_test",
            text = "hello",
            messageId = "srv_active", // matches active
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleTextChunk(event, "msg_1") }
    }

    @Test
    fun `ToolResult is cross-message event and bypasses serverId routing check`() {
        turnLifecycleState.activeMessageId = "msg_1"
        turnLifecycleState.activeServerMessageId = "srv_active"
        turnLifecycleState.isStreaming = true
        // ToolResult with a different serverId — should still dispatch (cross-message)
        val event = SseEvent.ToolResult(
            sessionId = "ses_test",
            toolCallId = "tc_1",
            messageId = "srv_different",
        )
        pipeline.process(event, ctx())
        verify { sessionState.handleToolResult(event, "msg_1") }
    }

    // ── pendingTurnIdentity application ─────────────────────────────────────

    @Test
    fun `pendingTurnIdentity is applied at start of processing and cleared`() {
        val pending = MessageLifecycleManager.PendingTurnIdentity(
            messageId = "pending_msg",
            serverMessageId = "srv_pending",
            modelID = "m",
            providerID = "p",
        )
        every { sessionState.pendingTurnIdentity } returns pending
        // After reading, the pipeline sets it to null
        every { sessionState.pendingTurnIdentity = null } just runs

        // Send a content event that would normally auto-create, but pendingTurnIdentity
        // should be applied first, setting activeMessageId
        val event = SseEvent.TextChunk(sessionId = "ses_test", text = "hi", messageId = "srv_pending")
        pipeline.process(event, ctx())

        // pendingTurnIdentity should have been cleared
        verify { sessionState.pendingTurnIdentity = null }
        // resetTurnState should have been called (from pending application)
        verify { sessionState.resetTurnState() }
        // activeMessageId should be set from pending
        turnLifecycleState.activeMessageId shouldBe "pending_msg"
        turnLifecycleState.activeServerMessageId shouldBe "srv_pending"
        turnLifecycleState.modelID shouldBe "m"
        turnLifecycleState.providerID shouldBe "p"
        turnLifecycleState.isStreaming shouldBe true
    }
}