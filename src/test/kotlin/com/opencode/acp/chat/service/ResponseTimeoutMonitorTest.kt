package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.SessionState
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [ResponseTimeoutMonitor] (TDD §8.1/§8.2 — ResponseTimeoutMonitor scenarios).
 *
 * Uses mockk to stub [SessionManager] (which requires an IntelliJ [com.intellij.openapi.project.Project]).
 * The monitor re-fetches `getActiveSession()` each iteration, so we control the mock's
 * return value per-iteration via an [AtomicReference] that the mock reads.
 *
 * NOTE: These tests use System.currentTimeMillis() for lastActivityTimeMs (real wall-clock
 * time) alongside runTest virtual time for coroutine delays. This works because
 * kotlinx-coroutines-test does NOT freeze System.currentTimeMillis() — only coroutine
 * delays are virtual. Real time passes during test execution, so the elapsed-time
 * comparisons in the monitor (which also use System.currentTimeMillis()) produce
 * correct results. The tests are not flaky because the time gaps (20s, 100s, 200s)
 * are much larger than any realistic test execution delay.
 */
class ResponseTimeoutMonitorTest {

    private val testScope = TestScope()

    /** Controls what getActiveSession() returns on each call. */
    private val activeSessionRef = AtomicReference<SessionState?>(null)

    /** Controls what responseTimeoutSecondsProvider returns. */
    private val responseTimeoutRef = AtomicReference(300)

    /** Controls what toolStuckTimeoutSecondsProvider returns. */
    private val toolStuckTimeoutRef = AtomicReference(300)

    /** Controls what effectiveLastActivityMsProvider returns. */
    private val effectiveLastActivityRef = AtomicReference(System.currentTimeMillis())

    /** Controls what childActivityProvider returns. */
    private val childActivityRef = AtomicReference(false)

    private lateinit var sessionManager: SessionManager
    private lateinit var monitor: ResponseTimeoutMonitor

    @BeforeEach
    fun setUp() {
        sessionManager = mockk(relaxed = true)
        every { sessionManager.getActiveSession() } answers { activeSessionRef.get() }
        monitor = ResponseTimeoutMonitor(
            scope = testScope,
            sessionManager = sessionManager,
            responseTimeoutSecondsProvider = { responseTimeoutRef.get() },
            toolStuckTimeoutSecondsProvider = { toolStuckTimeoutRef.get() },
        )
        // Note: providers are passed to startMonitoring() per-call so individual
        // tests can override them. The defaults (backward-compatible) are used
        // when tests don't pass them.
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `no SSE activity beyond timeout triggers onTimeout`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // lastActivity was 20 seconds ago; timeout is 10s
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 20_000
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)
        responseTimeoutRef.set(10)

        var timeoutMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { timeoutMsg = it },
            onToolStuck = { },
        )
        // Advance past the 5s check interval + a bit more.
        // Do NOT use advanceUntilIdle() — the monitor has an infinite while(isActive) loop
        // that keeps scheduling delay(5000), so advanceUntilIdle() would never return.
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        timeoutMsg shouldNotBe null
        timeoutMsg!! shouldContain "No activity"
    }

    @Test
    fun `running tool skips activity timeout`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // lastActivity was 100 seconds ago (way past 10s timeout)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 100_000
        // But a tool is InProgress
        every { session.snapshotToolState() } returns Triple(
            listOf(PartState.InProgress),
            emptyList(),
            emptyMap()
        )
        activeSessionRef.set(session)
        responseTimeoutRef.set(10)

        var timeoutCalled = false
        var stuckCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { stuckCalled = true },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        timeoutCalled shouldBe false
        stuckCalled shouldBe false
    }

    @Test
    fun `tool stuck beyond ceiling triggers onToolStuck`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        // Tool started 200 seconds ago; stuck ceiling is 60s
        val pillValue = mockk<ToolCallPill>()
        every { pillValue.startTimeMs } returns System.currentTimeMillis() - 200_000
        val pill = java.util.AbstractMap.SimpleEntry("tool1", pillValue)
        every { session.snapshotToolState() } returns Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>>(
            listOf(PartState.InProgress),
            listOf(pill),
            mapOf("tool1" to (PartState.InProgress as PartState))
        )
        activeSessionRef.set(session)
        toolStuckTimeoutRef.set(60)

        var stuckMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { stuckMsg = it },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        stuckMsg shouldNotBe null
        stuckMsg!! shouldContain "Tool stuck"
    }

    @Test
    fun `evicted session skips iteration without false-positive timeout`() = testScope.runTest {
        // Session was evicted — getActiveSession returns null
        activeSessionRef.set(null)
        responseTimeoutRef.set(10)

        var timeoutCalled = false
        var stuckCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { stuckCalled = true },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        timeoutCalled shouldBe false
        stuckCalled shouldBe false
    }

    @Test
    fun `responseTimeoutSeconds clamped to minimum 10`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // lastActivity was 15 seconds ago
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 15_000
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)
        // Set to 0 — should be clamped to 10s
        responseTimeoutRef.set(0)

        var timeoutMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { timeoutMsg = it },
            onToolStuck = { },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        timeoutMsg shouldNotBe null
        // The message should say "No activity for 10s" (clamped value)
        timeoutMsg!! shouldContain "10s"
    }

    @Test
    fun `toolStuckTimeoutSeconds clamped to minimum 60`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        // Tool started 70 seconds ago
        val pillValue = mockk<ToolCallPill>()
        every { pillValue.startTimeMs } returns System.currentTimeMillis() - 70_000
        val pill = java.util.AbstractMap.SimpleEntry("tool1", pillValue)
        every { session.snapshotToolState() } returns Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>>(
            listOf(PartState.InProgress),
            listOf(pill),
            mapOf("tool1" to (PartState.InProgress as PartState))
        )
        activeSessionRef.set(session)
        // Set to 0 — should be clamped to 60s; 70s > 60s → stuck
        toolStuckTimeoutRef.set(0)

        var stuckMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { stuckMsg = it },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        stuckMsg shouldNotBe null
        stuckMsg!! shouldContain "60s"
    }

    @Test
    fun `settings change mid-response takes effect on next iteration`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)

        // First iteration: activity is recent (no timeout)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 1_000
        responseTimeoutRef.set(300)

        var timeoutCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { },
        )

        // First check at 5s — no timeout (activity is recent)
        advanceTimeBy(6_000)
        runCurrent()
        timeoutCalled shouldBe false

        // Now lower the timeout to 10s and set lastActivity to 20s ago
        responseTimeoutRef.set(10)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 20_000

        // Second check at 10s — should timeout now
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        timeoutCalled shouldBe true
    }

    @Test
    fun `monitor job can be cancelled cleanly`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)

        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { },
        )
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()
        // Should not throw
        job.isCancelled shouldBe true
    }

    // --- Regression tests: prevent re-introduction of withTimeoutOrNull around sendMessageAsync ---
    // The bug was: sendMessageInternal wrapped sendMessageAsync in withTimeoutOrNull(responseTimeoutSeconds).
    // Since the server POST blocks until generation finishes (can be minutes), this cancelled the POST
    // client-side even though the server was actively working. The monitor (with its tool-running guard)
    // is the correct timeout mechanism — it must be the ONLY one.

    @Test
    fun `long POST with tools running does not timeout - regression for withTimeoutOrNull removal`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // Simulate: POST started 120s ago, no SSE events yet (server is generating).
        // With the old bug, withTimeoutOrNull(60s) would have cancelled at 60s.
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 120_000
        // But a tool is running — the monitor MUST skip the activity timeout.
        every { session.snapshotToolState() } returns Triple(
            listOf(PartState.InProgress),
            emptyList(),
            emptyMap()
        )
        activeSessionRef.set(session)
        responseTimeoutRef.set(60) // Same as the user's migrated setting

        var timeoutCalled = false
        var stuckCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { stuckCalled = true },
        )
        // Simulate 60s passing (the old withTimeoutOrNull deadline)
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        // And another 60s (total 120s — well past the old deadline)
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        // The monitor must NOT have fired — tools are running, server is busy.
        timeoutCalled shouldBe false
        stuckCalled shouldBe false
    }

    @Test
    fun `long POST with pending tool permission does not timeout`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // POST started 120s ago, server sent a tool-use and is waiting for user permission.
        // No SSE events for 120s (the permission prompt is displayed client-side).
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 120_000
        // PartState.Pending = waiting for user permission — also counts as "active"
        every { session.snapshotToolState() } returns Triple(
            listOf(PartState.Pending),
            emptyList(),
            emptyMap()
        )
        activeSessionRef.set(session)
        responseTimeoutRef.set(60)

        var timeoutCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { },
        )
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        // Pending permission = server is blocked on user, not stuck — must not timeout.
        timeoutCalled shouldBe false
    }

    @Test
    fun `monitor fires correctly when no tools and no SSE activity beyond timeout`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // POST started 120s ago, no tools running, no SSE events.
        // This is the legitimate timeout case — server is truly unresponsive.
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 120_000
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)
        responseTimeoutRef.set(60)

        var timeoutMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { timeoutMsg = it },
            onToolStuck = { },
        )
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        // The monitor MUST fire — no tools, no activity, past timeout.
        timeoutMsg shouldNotBe null
        timeoutMsg!! shouldContain "No activity"
        timeoutMsg!! shouldContain "60s"
    }

    @Test
    fun `tool stuck ceiling suspended when child actively generating`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // Tool started 400s ago — well past the 60s stuck ceiling
        val pill = ToolCallPill(
            toolCallId = "tc_stuck",
            toolName = "task",
            title = "subagent",
            kind = com.agentclientprotocol.model.ToolKind.OTHER,
            status = com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS,
            input = null,
            output = null,
            metadata = null,
            startTimeMs = System.currentTimeMillis() - 400_000,
        )
        every { session.snapshotToolState() } returns Triple(
            listOf(PartState.InProgress),
            listOf(mapOf("tc_stuck" to pill).entries.first()),
            mapOf("tc_stuck" to PartState.InProgress)
        )
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 400_000
        activeSessionRef.set(session)
        toolStuckTimeoutRef.set(60)

        var stuckCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { stuckCalled = true },
            effectiveLastActivityMsProvider = { System.currentTimeMillis() - 400_000 },
            childActivityProvider = { true },  // child is actively generating
        )
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        // Ceiling should be SUSPENDED — no abort despite 400s > 60s ceiling
        stuckCalled shouldBe false
    }

    @Test
    fun `tool stuck ceiling fires when no child activity`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        val pill = ToolCallPill(
            toolCallId = "tc_stuck",
            toolName = "task",
            title = "subagent",
            kind = com.agentclientprotocol.model.ToolKind.OTHER,
            status = com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS,
            input = null,
            output = null,
            metadata = null,
            startTimeMs = System.currentTimeMillis() - 400_000,
        )
        every { session.snapshotToolState() } returns Triple(
            listOf(PartState.InProgress),
            listOf(mapOf("tc_stuck" to pill).entries.first()),
            mapOf("tc_stuck" to PartState.InProgress)
        )
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 400_000
        activeSessionRef.set(session)
        toolStuckTimeoutRef.set(60)

        var stuckMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { stuckMsg = it },
            effectiveLastActivityMsProvider = { System.currentTimeMillis() - 400_000 },
            childActivityProvider = { false },  // no child activity — safety net fires
        )
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        stuckMsg shouldNotBe null
        stuckMsg!! shouldContain "Tool stuck"
    }

    @Test
    fun `activity timeout uses effective tree activity not just parent`() = testScope.runTest {
        val session = mockk<SessionState>(relaxed = true)
        // Parent's own activity is stale (100s ago, past 10s timeout)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 100_000
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)
        responseTimeoutRef.set(10)

        var timeoutCalled = false
        val job = monitor.startMonitoring(
            onTimeout = { timeoutCalled = true },
            onToolStuck = { },
            // Effective activity is fresh (child is generating) — should NOT timeout
            effectiveLastActivityMsProvider = { System.currentTimeMillis() - 1_000 },
            childActivityProvider = { false },
        )
        advanceTimeBy(6_000)
        runCurrent()
        job.cancel()

        // Should NOT timeout because effective activity (child) is fresh
        timeoutCalled shouldBe false
    }
}