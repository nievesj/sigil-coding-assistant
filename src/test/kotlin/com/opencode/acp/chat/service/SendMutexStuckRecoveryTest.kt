package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.ChatConstants
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Unit tests for the PRIMARY stuck-send recovery fix in [OpenCodeService.sendMessageInternal].
 *
 * Root cause (council-confirmed): `sendMutex` becomes permanently locked when
 * `client.sendMessageAsync()` hangs on a half-open TCP connection (no FIN/RST, just silent).
 * The POST uses `TimeoutProfile.INFINITE` and the Java HTTP engine has no socket-level idle
 * timeout, so the `finally { sendMutex.unlock() }` never runs. Every subsequent send silently
 * fails at `sendMutex.tryLock()`.
 *
 * The fix launches `sendMessageAsync` in a cancellable child coroutine (`scope.async`) so the
 * activity monitor's `onTimeout`/`onToolStuck` callbacks can cancel the hanging POST, and wraps
 * the POST in `withTimeoutOrNull(SEND_POST_HARD_CEILING_MS)` as a belt-and-suspenders backstop.
 *
 * Constructing a full `OpenCodeService` is too heavy for a unit test (it requires an IntelliJ
 * `Project`, `ProcessManager`, `SessionManager`, `McpManager`, etc.). These tests verify the
 * cancellation behavior at the coroutine level — the key invariant is: **when the timeout
 * handler cancels the send job, the awaiter unblocks and the mutex is released.**
 *
 * See AGENTS.md "Stuck-send recovery" section.
 *
 * DIVERGENCE NOTE: These tests use `java.util.concurrent.locks.ReentrantLock` to simulate
 * `sendMutex` (which is `kotlinx.coroutines.sync.Mutex` in production). The semantics differ:
 * `ReentrantLock` is reentrant and non-suspend; `Mutex` is non-reentrant and suspend-aware.
 * The invariant being tested (cancelling the send job unblocks the awaiter, releasing the
 * mutex via the outer finally) is independent of the lock implementation. The `Mutex`-specific
 * behavior (structured cancellation releasing the lock) is not tested here because it
 * requires a full `OpenCodeService` instance. These tests verify the coroutine-level
 * cancellation propagation that the fix relies on.
 */
class SendMutexStuckRecoveryTest {

    // NOTE: These tests verify coroutine-level cancellation propagation but do NOT test
    // the scenario where abortStreamingWithFallback throws during onTimeout (see
    // OpenCodeService.kt review: onTimeout callbacks now wrap abortStreamingWithFallback
    // in try/catch with a direct deferred completion fallback). Testing that path
    // requires a full OpenCodeService instance, which is too heavy for unit tests.
    // The fix in OpenCodeService.sendMessageInternal (try/catch in onTimeout/onToolStuck
    // lambdas) ensures the deferred is always completed even if the abort throws.

    // Uses Dispatchers.Default (not a TestDispatcher) because the tests verify real
    // coroutine cancellation propagation across async/await boundaries. The delay(50)
    // calls are real wall-clock delays to give the async job time to start awaiting.
    // A TestDispatcher with runTest would work but requires careful advanceTimeBy
    // orchestration; the real-delay approach is simpler for these 4 tests and the
    // ~50ms per test is acceptable in a 1171-test suite. If these tests become flaky
    // on CI, convert to runTest with StandardTestDispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Simulates the half-open TCP scenario: `sendMessageAsync` never returns because the
     * underlying HTTP POST is blocked on a dead socket. The activity monitor's `onTimeout`
     * callback cancels the send job, which must unblock `sendJob.await()` via
     * `CancellationException` so the outer `finally { sendMutex.unlock() }` runs.
     *
     * This test verifies the core invariant: cancelling the send job releases the awaiter,
     * which releases the mutex.
     */
    @Test
    fun `timeout handler cancels send job and releases mutex`() = runBlocking {
        val mutex = ReentrantLock()
        // Simulate the never-completing POST (half-open TCP — no FIN/RST, just silent).
        val neverCompleting = CompletableDeferred<String>()
        val sendJobCancelled = AtomicBoolean(false)

        // Acquire the mutex as sendMessage() does.
        mutex.lock()
        try {
            // Launch the POST in a cancellable child coroutine, mirroring sendMessageInternal.
            val sendJob = scope.async {
                try {
                    neverCompleting.await()  // blocks forever — simulates half-open TCP
                } catch (e: CancellationException) {
                    sendJobCancelled.set(true)
                    throw e
                }
            }

            // Simulate the activity monitor's onTimeout callback firing.
            // In production this is triggered by no SSE activity for responseTimeoutSeconds.
            val timeoutMsg = "Response timeout: no SSE activity"
            val onTimeout = {
                sendJob.cancel(CancellationException(timeoutMsg))
            }

            // Give the send job a moment to start awaiting the never-completing deferred.
            delay(50)

            // Fire the timeout handler — this cancels sendJob.
            onTimeout()

            // await() must unblock via CancellationException (the EXPECTED path that releases
            // the mutex via the outer finally in sendMessage()).
            var cancellationThrown = false
            try {
                sendJob.await()
            } catch (e: CancellationException) {
                cancellationThrown = true
            }

            // The send job must have observed the cancellation.
            sendJobCancelled.get() shouldBe true
            // The awaiter must have unblocked (no longer active).
            sendJob.isActive shouldBe false
            // CancellationException is the expected propagation mechanism.
            cancellationThrown shouldBe true
        } finally {
            // The outer finally in sendMessage() does this — verify it can run.
            // Direct unlock (no isLocked check) — if the lock isn't held, unlock()
            // throws IllegalMonitorStateException, surfacing the bug immediately.
            mutex.unlock()
        }

        // After the timeout path, the mutex must be free for the next send.
        mutex.tryLock() shouldBe true
        mutex.unlock()
    }

    /**
     * Verifies the hard-ceiling backstop: `withTimeoutOrNull` cancels the hanging POST and
     * returns null when the activity monitor cannot fire (e.g., its job was cancelled).
     *
     * Uses a short timeout (100ms) instead of SEND_POST_HARD_CEILING_MS (30 min) for test speed.
     * The invariant is the same: `withTimeoutOrNull` returns null when the inner block times out.
     */
    @Test
    fun `hard ceiling backstop cancels hanging POST and returns null`() = runBlocking {
        val neverCompleting = CompletableDeferred<String>()

        // Mirror the sendJob structure: scope.async { withTimeoutOrNull(ceiling) { POST } }
        val sendJob = scope.async {
            withTimeoutOrNull(100) {
                neverCompleting.await()  // blocks forever
            }
        }

        // The await must return null (withTimeoutOrNull returns null on timeout).
        val result = sendJob.await()
        result shouldBe null
        sendJob.isActive shouldBe false
    }

    /**
     * Verifies that a normal (non-hanging) POST completes successfully through the cancellable
     * send-job path — the fix must not break the happy path.
     */
    @Test
    fun `normal POST completes successfully through cancellable send job`() = runBlocking {
        val sendJob = scope.async {
            withTimeoutOrNull(ChatConstants.SEND_POST_HARD_CEILING_MS) {
                delay(10)
                "msg_server_123"
            }
        }

        val result = sendJob.await()
        result shouldBe "msg_server_123"
        sendJob.isActive shouldBe false
    }

    /**
     * Verifies that cancelling the send job while `deferred.await()` (the response-completion
     * wait AFTER the POST returns) is in flight also unblocks the awaiter. This mirrors the
     * path where the POST returns quickly but the SSE stream stalls (no StreamingCompleted
     * event) and the activity monitor fires onTimeout during the deferred.await() phase.
     *
     * In production, the `finally { if (sendJob.isActive) sendJob.cancel() }` block ensures
     * no leaked coroutine. Here we verify the send job is already complete (POST returned)
     * so cancelling it is a no-op — the CancellationException propagates from deferred.await()
     * via the catch block at line 701, releasing the mutex.
     */
    @Test
    fun `send job already complete when timeout fires during deferred await`() = runBlocking {
        val mutex = ReentrantLock()
        val responseDeferred = CompletableDeferred<Unit>()  // never completed — simulates stalled SSE

        mutex.lock()
        try {
            val sendJob = scope.async {
                withTimeoutOrNull(ChatConstants.SEND_POST_HARD_CEILING_MS) {
                    delay(10)
                    "msg_server_456"
                }
            }

            // POST completes.
            val serverMessageId = sendJob.await()
            serverMessageId shouldBe "msg_server_456"
            sendJob.isActive shouldBe false

            // Now awaiting the response deferred (SSE StreamingCompleted) — this stalls.
            // The activity monitor fires onTimeout, which (in production) cancels sendJob
            // and calls abortStreamingWithFallback, which completes the deferred.
            // Here we simulate abortStreamingWithFallback completing the deferred with an error.
            val onTimeoutSimulated = {
                if (sendJob.isActive) sendJob.cancel()
                responseDeferred.complete(Unit)  // simulate abortStreamingWithFallback
            }

            // Schedule the timeout to fire while we're awaiting the deferred.
            scope.launch {
                delay(50)
                onTimeoutSimulated()
            }

            // The deferred completes (via the simulated abort), unblocking the await.
            responseDeferred.await()

            // In production, after the deferred completes via abortStreamingWithFallback,
            // sendMessageInternal checks `wasAborted` (sendSession.isStreaming == false &&
            // errorMessage != null) and returns SendMessageResult.Error. This test verifies
            // the mutex-release invariant; the error-semantics invariant is covered by
            // StuckSendDefensiveTest's abortStreamingWithFallback tests.
            // The deferred completing with Unit (not exceptionally) is correct: the abort
            // path signals "stop waiting" rather than "propagate this exception". The
            // wasAborted check in sendMessageInternal is what determines Error vs Success.

            // The finally block cancels sendJob if still active — it's already complete, no-op.
            if (sendJob.isActive) sendJob.cancel()
        } finally {
            mutex.unlock()
        }

        // Mutex is released.
        mutex.tryLock() shouldBe true
        mutex.unlock()
    }

    /**
     * Verifies the SEND_POST_HARD_CEILING_MS constant is 30 minutes. This guards against
     * accidental changes that would either kill legitimate long generations (too short)
     * or never fire (too long).
     */
    @Test
    fun `SEND_POST_HARD_CEILING_MS is 30 minutes`() {
        ChatConstants.SEND_POST_HARD_CEILING_MS shouldBe (30 * 60 * 1000L)
    }
}