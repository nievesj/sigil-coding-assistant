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
import kotlinx.coroutines.test.advanceUntilIdle
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

    private lateinit var scope: TestScope
    private lateinit var sessionManager: SessionManager
    private lateinit var monitor: ResponseTimeoutMonitor

    /** Controls what getActiveSession() returns on each call. */
    private val activeSessionRef = AtomicReference<SessionState?>(null)

    /** Controls what responseTimeoutSecondsProvider returns. */
    private val responseTimeoutRef = AtomicReference(300)

    /** Controls what toolStuckTimeoutSecondsProvider returns. */
    private val toolStuckTimeoutRef = AtomicReference(300)

    @BeforeEach
    fun setUp() {
        scope = TestScope()
        sessionManager = mockk(relaxed = true)
        every { sessionManager.getActiveSession() } answers { activeSessionRef.get() }
        monitor = ResponseTimeoutMonitor(
            scope = scope,
            sessionManager = sessionManager,
            responseTimeoutSecondsProvider = { responseTimeoutRef.get() },
            toolStuckTimeoutSecondsProvider = { toolStuckTimeoutRef.get() },
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `no SSE activity beyond timeout triggers onTimeout`() = runTest {
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
        // Advance past the 5s check interval + a bit more
        advanceTimeBy(6_000)
        advanceUntilIdle()
        job.cancel()

        timeoutMsg shouldNotBe null
        timeoutMsg!! shouldContain "No activity"
    }

    @Test
    fun `running tool skips activity timeout`() = runTest {
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
        advanceUntilIdle()
        job.cancel()

        timeoutCalled shouldBe false
        stuckCalled shouldBe false
    }

    @Test
    fun `tool stuck beyond ceiling triggers onToolStuck`() = runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        // Tool started 200 seconds ago; stuck ceiling is 60s
        // NOTE: The pill mock uses an empty-string key ("") and the statesSnapshot map
        // uses the same key. In real code, the pill key is the toolCallId and statesSnapshot
        // is keyed by toolCallId. The empty-string key is a test simplification that works
        // because the monitor filters pills by checking statesSnapshot[it.key].
        val pill = mockk<kotlin.collections.Map.Entry<String, ToolCallPill>>()
        val pillValue = mockk<ToolCallPill>()
        every { pill.value } returns pillValue
        every { pillValue.startTimeMs } returns System.currentTimeMillis() - 200_000
        every { session.snapshotToolState() } returns Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>>(
            listOf(PartState.InProgress),
            listOf(pill),
            mapOf("" to (PartState.InProgress as PartState))
        )
        activeSessionRef.set(session)
        toolStuckTimeoutRef.set(60)

        var stuckMsg: String? = null
        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { stuckMsg = it },
        )
        advanceTimeBy(6_000)
        advanceUntilIdle()
        job.cancel()

        stuckMsg shouldNotBe null
        stuckMsg!! shouldContain "Tool stuck"
    }

    @Test
    fun `evicted session skips iteration without false-positive timeout`() = runTest {
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
        advanceUntilIdle()
        job.cancel()

        timeoutCalled shouldBe false
        stuckCalled shouldBe false
    }

    @Test
    fun `responseTimeoutSeconds clamped to minimum 10`() = runTest {
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
        advanceUntilIdle()
        job.cancel()

        timeoutMsg shouldNotBe null
        // The message should say "No activity for 10s" (clamped value)
        timeoutMsg!! shouldContain "10s"
    }

    @Test
    fun `toolStuckTimeoutSeconds clamped to minimum 60`() = runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        // Tool started 70 seconds ago
        val pill = mockk<kotlin.collections.Map.Entry<String, ToolCallPill>>()
        val pillValue = mockk<ToolCallPill>()
        every { pill.value } returns pillValue
        every { pillValue.startTimeMs } returns System.currentTimeMillis() - 70_000
        every { session.snapshotToolState() } returns Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>>(
            listOf(PartState.InProgress),
            listOf(pill),
            mapOf("" to (PartState.InProgress as PartState))
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
        advanceUntilIdle()
        job.cancel()

        stuckMsg shouldNotBe null
        stuckMsg!! shouldContain "60s"
    }

    @Test
    fun `settings change mid-response takes effect on next iteration`() = runTest {
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
        advanceUntilIdle()
        timeoutCalled shouldBe false

        // Now lower the timeout to 10s and set lastActivity to 20s ago
        responseTimeoutRef.set(10)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis() - 20_000

        // Second check at 10s — should timeout now
        advanceTimeBy(6_000)
        advanceUntilIdle()
        job.cancel()

        timeoutCalled shouldBe true
    }

    @Test
    fun `monitor job can be cancelled cleanly`() = runTest {
        val session = mockk<SessionState>(relaxed = true)
        every { session.lastActivityTimeMs } returns System.currentTimeMillis()
        every { session.snapshotToolState() } returns Triple(emptyList(), emptyList(), emptyMap())
        activeSessionRef.set(session)

        val job = monitor.startMonitoring(
            onTimeout = { },
            onToolStuck = { },
        )
        advanceTimeBy(6_000)
        advanceUntilIdle()
        job.cancel()
        // Should not throw
        job.isCancelled shouldBe true
    }
}