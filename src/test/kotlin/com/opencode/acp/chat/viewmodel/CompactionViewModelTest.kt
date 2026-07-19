package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.CompactionError
import com.opencode.acp.chat.model.CompactionState
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.chat.service.ProcessManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CompactionViewModel] (TDD `docs/tdd/ui-testability-refactor.md` §9 step 6, Phase 3).
 *
 * Tests manual compaction state transitions, the in-flight guard, and error
 * handling. Uses MockK to mock [OpenCodeServiceApi], [ProcessManager], and
 * [OpenCodeClient]. Uses [runTest] for virtual time control over the
 * `withTimeout` and `scope.launch` coroutines.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompactionViewModelTest {

    private lateinit var service: OpenCodeServiceApi
    private lateinit var connectionManager: ProcessManager
    private lateinit var client: OpenCodeClient
    private lateinit var viewModel: CompactionViewModel

    private val selectedModel: ProviderModel = ProviderModel(
        providerID = "anthropic",
        modelID = "claude-sonnet",
        displayName = "Anthropic / Claude Sonnet",
    )

    @BeforeEach
    fun setUp() {
        service = mockk<OpenCodeServiceApi>(relaxed = true)
        connectionManager = mockk<ProcessManager>(relaxed = true)
        client = mockk<OpenCodeClient>(relaxed = true)
        every { service.connectionManager } returns connectionManager
        every { service.sessionId } returns "ses_123"
        every { connectionManager.client } returns client
    }

    private fun makeViewModel(scope: TestScope, model: ProviderModel? = selectedModel): CompactionViewModel =
        CompactionViewModel(
            service = service,
            scope = scope,
            selectedModelProvider = { model },
        )

    // ── Pre-flight error paths (synchronous, no coroutine launched) ──────

    @Test
    fun `compactSession with no active session returns NoActiveSession`() = runTest {
        every { service.sessionId } returns null
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
            .error shouldBe CompactionError.NoActiveSession
    }

    @Test
    fun `compactSession with no client returns NotConnected`() = runTest {
        every { connectionManager.client } returns null
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
            .error shouldBe CompactionError.NotConnected
    }

    @Test
    fun `compactSession with no selected model returns ServerError`() = runTest {
        viewModel = makeViewModel(this, model = null)

        viewModel.compactSession()
        advanceUntilIdle()

        val err = viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
        err.error.shouldBeInstanceOf<CompactionError.ServerError>()
        err.error.message shouldBe "No model selected"
    }

    // ── Success path ──────────────────────────────────────────────────────

    @Test
    fun `compactSession success transitions Idle to InProgress to Idle`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } returns true
        viewModel = makeViewModel(this)

        viewModel.compactionState.value shouldBe CompactionState.Idle
        viewModel.compactSession()
        // After launching, the coroutine runs on the test dispatcher
        advanceUntilIdle()

        viewModel.compactionState.value shouldBe CompactionState.Idle
    }

    @Test
    fun `compactSession failure returns false transitions to ServerError`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } returns false
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        val err = viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
        err.error.shouldBeInstanceOf<CompactionError.ServerError>()
        err.error.message shouldBe "Compaction failed"
    }

    // ── Exception cascade ──────────────────────────────────────────────────

    @Test
    fun `compactSession with ConnectException returns NotConnected`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } throws java.net.ConnectException("refused")
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
            .error shouldBe CompactionError.NotConnected
    }

    @Test
    fun `compactSession with SocketTimeoutException returns Timeout`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } throws java.net.SocketTimeoutException("read timed out")
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
            .error shouldBe CompactionError.Timeout
    }

    @Test
    fun `compactSession with withTimeout firing transitions to TimedOut and holds guard`() = runTest {
        // Make the call suspend longer than COMPACT_TIMEOUT_MS so withTimeout fires
        // (TimeoutCancellationException, not SocketTimeoutException).
        coEvery { client.compactSession(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(CompactionConstants.COMPACT_TIMEOUT_MS + 10_000L)
            true
        }
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        // Advance past the withTimeout (120s) but NOT past the backoff (30s after timeout).
        // advanceTimeBy runs coroutines that become due — the withTimeout fires at 120s,
        // the catch block sets TimedOut and launches the backoff coroutine (delay 30s).
        // The backoff is due at 150s; we stop at 121s, so the backoff hasn't fired.
        // Do NOT call advanceUntilIdle() — it would advance past the backoff to 150s.
        advanceTimeBy(CompactionConstants.COMPACT_TIMEOUT_MS + 1_000L)

        // Should be TimedOut (not Error(Timeout)) — the withTimeout path.
        // The backoff coroutine is still suspended (only 1s past timeout, backoff is 30s).
        viewModel.compactionState.value shouldBe CompactionState.TimedOut
    }

    @Test
    fun `compactSession TimedOut releases guard on onSessionCompacted`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(CompactionConstants.COMPACT_TIMEOUT_MS + 10_000L)
            true
        }
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceTimeBy(CompactionConstants.COMPACT_TIMEOUT_MS + 1_000L)
        viewModel.compactionState.value shouldBe CompactionState.TimedOut

        // Simulate session.compacted SSE arriving — should release guard + clear TimedOut.
        viewModel.onSessionCompacted()
        viewModel.compactionState.value shouldBe CompactionState.Idle

        // Guard is released, so a new compaction can start and time out again.
        viewModel.compactSession()
        advanceTimeBy(CompactionConstants.COMPACT_TIMEOUT_MS + 1_000L)
        viewModel.compactionState.value shouldBe CompactionState.TimedOut
    }

    @Test
    fun `compactSession TimedOut auto-releases guard after backoff`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(CompactionConstants.COMPACT_TIMEOUT_MS + 10_000L)
            true
        }
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        // Advance past the withTimeout to trigger TimedOut.
        advanceTimeBy(CompactionConstants.COMPACT_TIMEOUT_MS + 1_000L)
        viewModel.compactionState.value shouldBe CompactionState.TimedOut

        // Advance past the backoff period — guard should auto-release, state → Idle.
        advanceTimeBy(CompactionConstants.COMPACT_TIMEOUT_BACKOFF_MS + 1_000L)
        advanceUntilIdle()

        viewModel.compactionState.value shouldBe CompactionState.Idle
    }

    @Test
    fun `compactSession with timeout-containing message returns Timeout`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } throws RuntimeException("request timeout exceeded")
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
            .error shouldBe CompactionError.Timeout
    }

    @Test
    fun `compactSession with generic exception returns ServerError`() = runTest {
        coEvery { client.compactSession(any(), any(), any(), any()) } throws RuntimeException("server 500")
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        advanceUntilIdle()

        val err = viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()
        err.error.shouldBeInstanceOf<CompactionError.ServerError>()
        err.error.message shouldBe "server 500"
    }

    // ── In-flight guard ────────────────────────────────────────────────────

    @Test
    fun `compactionInProgress guard rejects concurrent calls`() = runTest {
        // Make the first call suspend forever (delay inside the withTimeout) so the
        // guard stays set when the second call arrives. We use a suspending mock
        // that never returns until the test advances time.
        coEvery { client.compactSession(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000L)
            true
        }
        viewModel = makeViewModel(this)

        viewModel.compactSession()
        // Don't advance time — the first call is still suspended in delay().
        // The guard should still be set, so the second call is rejected.
        viewModel.compactSession()

        // The state should be InProgress (from the first call), not changed by the second.
        viewModel.compactionState.value shouldBe CompactionState.InProgress

        // Now let the first call complete so the guard is released and the test can finish.
        advanceUntilIdle()
        viewModel.compactionState.value shouldBe CompactionState.Idle
    }

    // ── resetCompactionState ───────────────────────────────────────────────

    @Test
    fun `resetCompactionState sets state to Idle`() = runTest {
        viewModel = makeViewModel(this)
        // Force an error state by triggering the no-model path
        every { service.sessionId } returns null
        viewModel.compactSession()
        advanceUntilIdle()
        viewModel.compactionState.value.shouldBeInstanceOf<CompactionState.Error>()

        viewModel.resetCompactionState()
        viewModel.compactionState.value shouldBe CompactionState.Idle
    }
}