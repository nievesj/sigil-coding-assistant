package com.opencode.acp.chat.processor

import com.opencode.acp.adapter.MessageInfo
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodeMessage
import com.opencode.acp.adapter.OpenCodePart
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SessionRecoveryManager] (TDD §4.2.3).
 *
 * Tests background session recovery after SSE reconnection:
 * - null client → immediate return
 * - empty streaming sessions → no-op
 * - assistant last message with no in-progress tools → finalize + complete deferred
 * - user last message → skip finalization (assistant hasn't started)
 * - in-progress tool calls (ToolUse without matching ToolResult) → skip finalization
 * - null activeMessageId → logs warning, doesn't crash (deferred still completed)
 *
 * Uses MockK for [OpenCodeClient] and [SessionState]. The [SessionRecoveryManager]
 * takes a `streamingSessionsProvider: () -> List<SessionState>` constructor parameter,
 * which we control via a mutable list captured in the closure.
 */
class SessionRecoveryManagerTest {

    private fun makeMessage(role: String, vararg parts: OpenCodePart): OpenCodeMessage =
        OpenCodeMessage(
            info = MessageInfo(id = "msg_${role}_${System.nanoTime()}", role = role),
            parts = parts.toList(),
        )

    private fun makeSession(
        sessionId: String,
        activeMessageId: String? = "msg_active",
        responseDeferred: CompletableDeferred<Unit>? = CompletableDeferred(),
    ): SessionState {
        val session = mockk<SessionState>(relaxed = true)
        every { session.sessionId } returns sessionId
        every { session.activeMessageId } returns activeMessageId
        every { session.responseDeferred } returns responseDeferred
        every { session.responseDeferred = any() } returns Unit
        return session
    }

    @Test
    fun `recoverBackgroundSessions with null client returns immediately`() = runTest {
        var providerCalled = false
        val manager = SessionRecoveryManager(streamingSessionsProvider = {
            providerCalled = true
            emptyList()
        })
        // Should not throw; provider may or may not be called (client == null returns first)
        manager.recoverBackgroundSessions(null)
        // Provider is called before the client null-check? No — client null-check is first.
        // The implementation checks client == null before calling the provider.
        providerCalled shouldBe false
    }

    @Test
    fun `recoverBackgroundSessions with empty streaming sessions is a no-op`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        coEvery { client.listMessages(any(), any()) } returns emptyList()
        val manager = SessionRecoveryManager(streamingSessionsProvider = { emptyList() })
        manager.recoverBackgroundSessions(client)
        // listMessages should never be called since there are no streaming sessions
        coVerify(exactly = 0) { client.listMessages(any(), any()) }
    }

    @Test
    fun `recoverBackgroundSessions finalizes assistant message with no in-progress tools`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        val deferred = CompletableDeferred<Unit>()
        val session = makeSession("ses_1", activeMessageId = "msg_active", responseDeferred = deferred)

        // Last message is assistant, no tool parts at all
        coEvery { client.listMessages("ses_1", any()) } returns listOf(
            makeMessage("user", OpenCodePart.Text("hello")),
            makeMessage("assistant", OpenCodePart.Text("hi there")),
        )

        val manager = SessionRecoveryManager(streamingSessionsProvider = { listOf(session) })
        manager.recoverBackgroundSessions(client)

        verify(exactly = 1) { session.completeStreaming("msg_active") }
        // responseDeferred should be completed and nulled
        deferred.isCompleted shouldBe true
        verify(exactly = 1) { session.responseDeferred = null }
    }

    @Test
    fun `recoverBackgroundSessions skips finalization when last message is user`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        val deferred = CompletableDeferred<Unit>()
        val session = makeSession("ses_2", activeMessageId = "msg_active", responseDeferred = deferred)

        // Last message is user — assistant hasn't started responding yet
        coEvery { client.listMessages("ses_2", any()) } returns listOf(
            makeMessage("assistant", OpenCodePart.Text("previous answer")),
            makeMessage("user", OpenCodePart.Text("new question")),
        )

        val manager = SessionRecoveryManager(streamingSessionsProvider = { listOf(session) })
        manager.recoverBackgroundSessions(client)

        // Should NOT finalize — SSE reconnection will deliver the assistant's response
        verify(exactly = 0) { session.completeStreaming(any()) }
        deferred.isCompleted shouldBe false
    }

    @Test
    fun `recoverBackgroundSessions skips finalization when there are in-progress tool calls`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        val deferred = CompletableDeferred<Unit>()
        val session = makeSession("ses_3", activeMessageId = "msg_active", responseDeferred = deferred)

        // Last message is assistant, but there's a ToolUse without a matching ToolResult
        coEvery { client.listMessages("ses_3", any()) } returns listOf(
            makeMessage("user", OpenCodePart.Text("run the tool")),
            makeMessage(
                "assistant",
                OpenCodePart.Text("calling tool now"),
                OpenCodePart.ToolUse(id = "tool_1", name = "bash"),
            ),
        )

        val manager = SessionRecoveryManager(streamingSessionsProvider = { listOf(session) })
        manager.recoverBackgroundSessions(client)

        // Should NOT finalize — tool is still in progress, SSE will deliver the result
        verify(exactly = 0) { session.completeStreaming(any()) }
        deferred.isCompleted shouldBe false
    }

    @Test
    fun `recoverBackgroundSessions finalizes when tool calls are all completed`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        val deferred = CompletableDeferred<Unit>()
        val session = makeSession("ses_4", activeMessageId = "msg_active", responseDeferred = deferred)

        // Last message is assistant; ToolUse has a matching ToolResult → not in-progress
        coEvery { client.listMessages("ses_4", any()) } returns listOf(
            makeMessage("user", OpenCodePart.Text("run the tool")),
            makeMessage(
                "assistant",
                OpenCodePart.Text("calling tool"),
                OpenCodePart.ToolUse(id = "tool_1", name = "bash"),
            ),
            makeMessage(
                "assistant",
                OpenCodePart.ToolResult(toolUseId = "tool_1", content = emptyList()),
                OpenCodePart.Text("done"),
            ),
        )

        val manager = SessionRecoveryManager(streamingSessionsProvider = { listOf(session) })
        manager.recoverBackgroundSessions(client)

        verify(exactly = 1) { session.completeStreaming("msg_active") }
        deferred.isCompleted shouldBe true
    }

    @Test
    fun `recoverBackgroundSessions with null activeMessageId logs warning and does not crash`() = runTest {
        val client = mockk<OpenCodeClient>(relaxed = true)
        val deferred = CompletableDeferred<Unit>()
        // activeMessageId is null — completeStreaming should NOT be called, but
        // responseDeferred is still completed and nulled
        val session = makeSession("ses_5", activeMessageId = null, responseDeferred = deferred)

        coEvery { client.listMessages("ses_5", any()) } returns listOf(
            makeMessage("user", OpenCodePart.Text("hi")),
            makeMessage("assistant", OpenCodePart.Text("hello")),
        )

        val manager = SessionRecoveryManager(streamingSessionsProvider = { listOf(session) })
        // Should not throw
        manager.recoverBackgroundSessions(client)

        // completeStreaming is NOT called (no active message id)
        verify(exactly = 0) { session.completeStreaming(any()) }
        // But responseDeferred is still completed and nulled (recovery proceeds)
        deferred.isCompleted shouldBe true
        verify(exactly = 1) { session.responseDeferred = null }
    }
}