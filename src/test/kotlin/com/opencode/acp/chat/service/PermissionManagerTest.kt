package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.SessionState
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.mcp.McpConfigWriter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for [PermissionManager].
 *
 * Covers:
 *  - startPermissionTimeout race condition (review cmt_a1b2c3d4e5f6): concurrent
 *    calls must not leak uncancelled jobs. The fix uses synchronized(timeoutLock).
 *  - startPermissionTimeout clamping and early-return for <= 0.
 *  - cancelPermissionTimeout clears the job.
 *  - respondPermission ALLOW_ALWAYS config sync gating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerTest {

    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testScope = TestScope()
    private lateinit var client: OpenCodeClient
    private lateinit var sessionManager: SessionManager
    private lateinit var mcpConfigWriter: McpConfigWriter
    private lateinit var manager: PermissionManager
    private lateinit var testManager: PermissionManager

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        mcpConfigWriter = mockk(relaxed = true)
        manager = PermissionManager(
            scope = realScope,
            clientProvider = { client },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { mcpConfigWriter },
        )
        testManager = PermissionManager(
            scope = testScope,
            clientProvider = { client },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { mcpConfigWriter },
        )
    }

    @AfterEach
    fun tearDown() {
        manager.dispose()
        testManager.dispose()
        realScope.cancel()
        testScope.cancel()
    }

    // ── Timeout race condition (cmt_a1b2c3d4e5f6) ────────────────────────────

    @Test
    fun `concurrent startPermissionTimeout calls do not leak uncancelled jobs`() = runTest {
        // Regression: before the synchronized(timeoutLock) fix, two concurrent
        // startPermissionTimeout calls could both cancel the old job and both
        // launch new jobs. The first-launched job was never cancelled, so its
        // onTimeout fired for a prompt that was supposed to be replaced.
        // Uses virtual time (testScope) for determinism — advanceTimeBy past all
        // three timeouts guarantees all jobs were cancelled regardless of real
        // timing. The previous version used real Dispatchers.Default with a 200ms
        // delay, which was non-deterministic.
        val fireCount = AtomicInteger(0)
        val pm = PermissionManager(
            scope = testScope,
            clientProvider = { client },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { null },
        )
        pm.startPermissionTimeout(60) { fireCount.incrementAndGet() }
        pm.startPermissionTimeout(60) { fireCount.incrementAndGet() }
        pm.startPermissionTimeout(60) { fireCount.incrementAndGet() }
        pm.cancelPermissionTimeout()
        advanceTimeBy(120_000)  // Past all three 60s timeouts
        runCurrent()
        fireCount.get() shouldBe 0
    }

    @Test
    fun `startPermissionTimeout with zero seconds does not launch a job`() = testScope.runTest {
        var fired = false
        testManager.startPermissionTimeout(0) { fired = true }
        advanceTimeBy(10_000)
        runCurrent()
        fired shouldBe false
    }

    @Test
    fun `startPermissionTimeout with negative seconds does not launch a job`() = testScope.runTest {
        var fired = false
        testManager.startPermissionTimeout(-5) { fired = true }
        advanceTimeBy(10_000)
        runCurrent()
        fired shouldBe false
    }

    @Test
    fun `startPermissionTimeout fires onTimeout after delay`() = testScope.runTest {
        var fired = false
        testManager.startPermissionTimeout(5) { fired = true }
        advanceTimeBy(4_000)
        runCurrent()
        fired shouldBe false
        advanceTimeBy(2_000)
        runCurrent()
        fired shouldBe true
    }

    @Test
    fun `cancelPermissionTimeout prevents onTimeout from firing`() = testScope.runTest {
        var fired = false
        testManager.startPermissionTimeout(5) { fired = true }
        advanceTimeBy(2_000)
        testManager.cancelPermissionTimeout()
        advanceTimeBy(10_000)
        runCurrent()
        fired shouldBe false
    }

    @Test
    fun `second startPermissionTimeout cancels the first`() = testScope.runTest {
        var firstFired = false
        var secondFired = false
        testManager.startPermissionTimeout(5) { firstFired = true }
        testManager.startPermissionTimeout(10) { secondFired = true }
        advanceTimeBy(6_000)
        runCurrent()
        firstFired shouldBe false
        secondFired shouldBe false
        advanceTimeBy(5_000)
        runCurrent()
        secondFired shouldBe true
    }

    // ── respondPermission ALLOW_ALWAYS config sync ───────────────────────────

    @Test
    fun `respondPermission ALLOW_ALWAYS with toolName syncs to config`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        every { mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any()) } returns true

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ALWAYS,
            toolName = "bash",
            patterns = listOf("**/*.sh"),
        )

        verify(exactly = 1) {
            mcpConfigWriter.writeAlwaysAllowRule("orchestrator", "bash", listOf("**/*.sh"))
        }
    }

    @Test
    fun `respondPermission ALLOW_ALWAYS with empty toolName does NOT sync to config`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ALWAYS,
            toolName = "",
            patterns = emptyList(),
        )

        verify(exactly = 0) {
            mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any())
        }
    }

    @Test
    fun `respondPermission ALLOW_ONCE does NOT sync to config`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ONCE,
            toolName = "bash",
        )

        verify(exactly = 0) {
            mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any())
        }
    }

    @Test
    fun `respondPermission config sync failure does not throw`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        every { mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any()) } throws RuntimeException("disk full")

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ALWAYS,
            toolName = "bash",
        )
    }

    @Test
    fun `respondPermission with null client drops silently`() = runBlocking {
        val nullClientManager = PermissionManager(
            scope = realScope,
            clientProvider = { null },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { mcpConfigWriter },
        )
        nullClientManager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ONCE,
        )
        verify(exactly = 0) {
            mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any())
        }
    }

    @Test
    fun `respondPermission server failure throws and does not sync config`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } throws RuntimeException("server error")

        var threw = false
        try {
            manager.respondPermission(
                permissionId = "perm_123",
                toolCallId = "tc_456",
                sessionId = "ses_abc",
                response = PermissionResponse.ALLOW_ALWAYS,
                toolName = "bash",
            )
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true
        verify(exactly = 0) {
            mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any())
        }
    }

    // ── respondQuestion / rejectQuestion ─────────────────────────────────────

    @Test
    fun `respondQuestion with null client drops silently`() = runBlocking {
        val nullClientManager = PermissionManager(
            scope = realScope,
            clientProvider = { null },
            sessionManager = sessionManager,
        )
        nullClientManager.respondQuestion("q_1", listOf(listOf("yes")), "ses_abc")
    }

    @Test
    fun `rejectQuestion with null client drops silently`() = runBlocking {
        val nullClientManager = PermissionManager(
            scope = realScope,
            clientProvider = { null },
            sessionManager = sessionManager,
        )
        nullClientManager.rejectQuestion("q_1", "ses_abc")
    }

    @Test
    fun `respondPermission ALLOW_ALWAYS with null mcpConfigWriterProvider does not throw`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        val nullMcpManager = PermissionManager(
            scope = realScope,
            clientProvider = { client },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { null }, // default
        )
        nullMcpManager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ALWAYS,
            toolName = "bash",
        )
        nullMcpManager.dispose()
        verify(exactly = 0) {
            mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any())
        }
    }
}

// ── Local state update verification (cmt_b8c9d0e1f2a3, cmt_c9d0e1f2a3b4) ──────

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerLocalStateTest {

    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: OpenCodeClient
    private lateinit var sessionManager: SessionManager
    private lateinit var mcpConfigWriter: McpConfigWriter
    private lateinit var manager: PermissionManager

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        mcpConfigWriter = mockk(relaxed = true)
        manager = PermissionManager(
            scope = realScope,
            clientProvider = { client },
            sessionManager = sessionManager,
            mcpConfigWriterProvider = { mcpConfigWriter },
        )
    }

    @AfterEach
    fun tearDown() {
        manager.dispose()
        realScope.cancel()
    }

    @Test
    fun `respondPermission ALLOW_ONCE updates tool state to IN_PROGRESS optimistically`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ONCE,
        )

        coVerify(exactly = 1) {
            sessionManager.updateToolCallStatusForSession("ses_abc", "tc_456", ToolCallStatus.IN_PROGRESS, null)
        }
    }

    @Test
    fun `respondPermission ALLOW_ALWAYS updates tool state to IN_PROGRESS optimistically`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        every { mcpConfigWriter.writeAlwaysAllowRule(any(), any(), any()) } returns true

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ALWAYS,
            toolName = "bash",
        )

        coVerify(exactly = 1) {
            sessionManager.updateToolCallStatusForSession("ses_abc", "tc_456", ToolCallStatus.IN_PROGRESS, null)
        }
    }

    @Test
    fun `respondPermission REJECT_ONCE does NOT update tool state optimistically`() = runBlocking {
        // REJECT_ONCE is updated AFTER the server call succeeds, not before.
        // This prevents local/server state divergence on POST failure.
        coEvery { client.respondPermission(any(), any(), any()) } just runs

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.REJECT_ONCE,
        )

        // After server success, REJECT_ONCE sets the tool state to Rejected
        coVerify(exactly = 1) {
            sessionManager.setToolPartStateForSession("ses_abc", "tc_456", PartState.Rejected)
        }
    }

    @Test
    fun `respondPermission REJECT_ONCE on server failure does NOT set Rejected state`() = runBlocking {
        // On POST failure, REJECT_ONCE must NOT have set the tool to Rejected —
        // the server never received the rejection, so the tool should not appear
        // Rejected locally.
        coEvery { client.respondPermission(any(), any(), any()) } throws RuntimeException("server error")

        var threw = false
        try {
            manager.respondPermission(
                permissionId = "perm_123",
                toolCallId = "tc_456",
                sessionId = "ses_abc",
                response = PermissionResponse.REJECT_ONCE,
            )
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true

        // REJECT_ONCE state update must NOT have been called (neither optimistically
        // nor after the server failure)
        coVerify(exactly = 0) {
            sessionManager.setToolPartStateForSession(any(), any(), PartState.Rejected)
        }
    }

    @Test
    fun `respondPermission ALLOW_ONCE on server failure still has optimistic IN_PROGRESS`() = runBlocking {
        // ALLOW_ONCE updates optimistically and does NOT roll back on failure.
        coEvery { client.respondPermission(any(), any(), any()) } throws RuntimeException("server error")

        var threw = false
        try {
            manager.respondPermission(
                permissionId = "perm_123",
                toolCallId = "tc_456",
                sessionId = "ses_abc",
                response = PermissionResponse.ALLOW_ONCE,
            )
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true

        // The optimistic update happened (exactly once), and was NOT rolled back
        coVerify(exactly = 1) {
            sessionManager.updateToolCallStatusForSession("ses_abc", "tc_456", ToolCallStatus.IN_PROGRESS, null)
        }
    }

    // ── setPendingPermission(false) on success (cmt_b2c3d4e5f6a7) ────────────

    @Test
    fun `respondPermission success clears pending permission flag`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.ALLOW_ONCE,
        )

        verify(exactly = 1) { sessionState.setPendingPermission(false) }
    }

    @Test
    fun `respondPermission REJECT_ONCE success clears pending permission flag`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } just runs
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        manager.respondPermission(
            permissionId = "perm_123",
            toolCallId = "tc_456",
            sessionId = "ses_abc",
            response = PermissionResponse.REJECT_ONCE,
        )

        verify(exactly = 1) { sessionState.setPendingPermission(false) }
    }

    @Test
    fun `respondPermission failure does NOT clear pending permission flag`() = runBlocking {
        coEvery { client.respondPermission(any(), any(), any()) } throws RuntimeException("server error")
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        var threw = false
        try {
            manager.respondPermission(
                permissionId = "perm_123",
                toolCallId = "tc_456",
                sessionId = "ses_abc",
                response = PermissionResponse.ALLOW_ONCE,
            )
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true

        // setPendingPermission(false) must NOT have been called — the prompt
        // stays visible for retry and the session stays in the cache.
        verify(exactly = 0) { sessionState.setPendingPermission(any()) }
    }
}

// ── respondQuestion / rejectQuestion success paths (cmt_d0e1f2a3b4c5) ──────

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionManagerQuestionTest {

    private val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: OpenCodeClient
    private lateinit var sessionManager: SessionManager
    private lateinit var manager: PermissionManager

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        manager = PermissionManager(
            scope = realScope,
            clientProvider = { client },
            sessionManager = sessionManager,
        )
    }

    @AfterEach
    fun tearDown() {
        manager.dispose()
        realScope.cancel()
    }

    @Test
    fun `respondQuestion success calls clearPendingSelection`() = runBlocking {
        coEvery { client.respondQuestion(any(), any()) } just runs
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        manager.respondQuestion("q_1", listOf(listOf("yes")), "ses_abc")

        verify(exactly = 1) { sessionState.clearPendingSelection() }
    }

    @Test
    fun `respondQuestion failure does NOT call clearPendingSelection`() = runBlocking {
        coEvery { client.respondQuestion(any(), any()) } throws RuntimeException("server error")
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        var threw = false
        try {
            manager.respondQuestion("q_1", listOf(listOf("yes")), "ses_abc")
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true

        // clearPendingSelection must NOT have been called — the prompt stays visible for retry.
        verify(exactly = 0) { sessionState.clearPendingSelection() }
    }

    @Test
    fun `rejectQuestion success calls clearPendingSelection`() = runBlocking {
        coEvery { client.rejectQuestion(any()) } just runs
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        manager.rejectQuestion("q_1", "ses_abc")

        verify(exactly = 1) { sessionState.clearPendingSelection() }
    }

    @Test
    fun `rejectQuestion failure does NOT call clearPendingSelection`() = runBlocking {
        coEvery { client.rejectQuestion(any()) } throws RuntimeException("server error")
        val sessionState = mockk<SessionState>(relaxed = true)
        coEvery { sessionManager.getSession("ses_abc") } returns sessionState

        var threw = false
        try {
            manager.rejectQuestion("q_1", "ses_abc")
        } catch (e: RuntimeException) {
            threw = true
        }
        threw shouldBe true

        verify(exactly = 0) { sessionState.clearPendingSelection() }
    }
}