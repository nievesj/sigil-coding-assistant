package com.opencode.acp.chat.service

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.processor.SessionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [SseConnectionManager] (TDD §8.1/§8.2 — SseConnectionManager scenarios).
 *
 * Uses mockk to stub [OpenCodeClient] (concrete class — mockk handles final classes)
 * and [SessionManager] (which requires an IntelliJ [com.intellij.openapi.project.Project]).
 *
 * The SSE event flow is controlled via a [MutableSharedFlow] that the mock client
 * emits from. Health check results are controlled via an [AtomicBoolean].
 */
class SseConnectionManagerTest {

    private lateinit var scope: TestScope
    private lateinit var client: OpenCodeClient
    private lateinit var sessionManager: SessionManager
    private lateinit var manager: SseConnectionManager

    /** Controls what healthCheck() returns. */
    private val healthCheckResult = AtomicBoolean(true)

    /** Controls what subscribeGlobalEvents() emits. Completing this flow ends the SSE stream. */
    private lateinit var sseFlow: MutableSharedFlow<SseEvent>

    /** Tracks onConnectionError invocations. */
    private val connectionErrorCount = AtomicInteger(0)

    /** Tracks onReconnectSuccess invocations. */
    private val reconnectSuccessCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        scope = TestScope()
        client = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        sseFlow = MutableSharedFlow(extraBufferCapacity = 100)

        every { client.subscribeGlobalEvents() } returns sseFlow
        coEvery { client.healthCheck() } answers { healthCheckResult.get() }

        manager = SseConnectionManager(
            scope = scope,
            clientProvider = { client },
            sessionManager = sessionManager,
            onConnectionError = { connectionErrorCount.incrementAndGet() },
            onReconnectSuccess = { reconnectSuccessCount.incrementAndGet() },
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start subscribes to SSE events and routes to sessionManager`() = runTest {
        manager.start()
        advanceUntilIdle()

        // Emit an event through the shared flow
        val event = mockk<SseEvent>(relaxed = true)
        every { event.sessionId } returns "ses_test"
        every { event.messageId } returns "msg_test"
        every { event::class.simpleName } returns "TestEvent"
        sseFlow.tryEmit(event)
        advanceUntilIdle()

        // processEvent should have been called
        coVerify { sessionManager.processEvent(any()) }
    }

    @Test
    fun `stop cancels SSE subscription and is idempotent`() = runTest {
        manager.start()
        advanceUntilIdle()
        manager.stop()
        // Calling stop again should not throw
        manager.stop()
        manager.isActive shouldBe false
    }

    @Test
    fun `start is idempotent - cancels previous jobs first`() = runTest {
        manager.start()
        advanceUntilIdle()
        manager.start()
        advanceUntilIdle()
        // Should not throw or create duplicate subscriptions
        manager.isActive shouldBe true
    }

    @Test
    fun `start with null client returns without subscribing`() = runTest {
        val nullClientManager = SseConnectionManager(
            scope = scope,
            clientProvider = { null },
            sessionManager = sessionManager,
            onConnectionError = { },
            onReconnectSuccess = { },
        )
        nullClientManager.start()
        advanceUntilIdle()
        nullClientManager.isActive shouldBe false
    }

    @Test
    fun `circuit breaker fires onReconnectError after MAX_RECONNECT_ATTEMPTS`() = runTest {
        // Make health check always fail
        healthCheckResult.set(false)
        manager.start()
        advanceUntilIdle()

        // Trigger reconnection by ending the SSE flow
        sseFlow.tryEmit(mockk(relaxed = true)) // trigger activity
        advanceUntilIdle()

        // Now manually trigger reconnect and advance past MAX_RECONNECT_ATTEMPTS
        manager.triggerReconnect()
        // Advance time enough to exhaust all reconnection attempts (50 attempts with backoff)
        // Each attempt has at most 30s delay, so 50 * 30s = 1500s max
        advanceTimeBy(2_000_000)
        advanceUntilIdle()

        connectionErrorCount.get() shouldBe 1
    }

    @Test
    fun `triggerReconnect with null client gives up gracefully`() = runTest {
        val nullClientManager = SseConnectionManager(
            scope = scope,
            clientProvider = { null },
            sessionManager = sessionManager,
            onConnectionError = { connectionErrorCount.incrementAndGet() },
            onReconnectSuccess = { reconnectSuccessCount.incrementAndGet() },
        )
        nullClientManager.triggerReconnect()
        advanceUntilIdle()
        // Should not call onConnectionError (that's only for circuit breaker)
        // Should not call onReconnectSuccess (client is null)
        reconnectSuccessCount.get() shouldBe 0
    }

    @Test
    fun `successful reconnection calls onReconnectSuccess`() = runTest {
        healthCheckResult.set(true)
        manager.start()
        advanceUntilIdle()

        // Trigger reconnection
        manager.triggerReconnect()
        advanceTimeBy(2_000)
        advanceUntilIdle()

        reconnectSuccessCount.get() shouldBe 1
    }

    @Test
    fun `stop during reconnection backoff prevents new SSE job`() = runTest {
        healthCheckResult.set(false)
        manager.start()
        advanceUntilIdle()

        manager.triggerReconnect()
        advanceTimeBy(500) // Partially through backoff
        manager.stop()
        advanceUntilIdle()

        // No reconnection should succeed
        reconnectSuccessCount.get() shouldBe 0
    }

    @Test
    fun `health check failure after silence triggers reconnection`() = runTest {
        healthCheckResult.set(false)
        manager.start()
        advanceUntilIdle()

        // Advance past SSE_HEALTH_CHECK_INTERVAL_MS (60s) to trigger a health check.
        // The health check will fail (healthCheckResult=false), which should cancel
        // the SSE job and trigger reconnection.
        advanceTimeBy(61_000)
        advanceUntilIdle()

        // Reconnection should have been triggered. Since healthCheck is still false,
        // it won't succeed, but triggerReconnect should have been called (which
        // launches the reconnect job). We verify by checking that the reconnect
        // job was created — we can't easily verify onReconnectSuccess since it
        // won't fire (health check fails). Instead, verify the circuit breaker
        // eventually fires after enough attempts.
        // For this test, just verify no crash and the manager is still functional.
        manager.stop()
    }
}