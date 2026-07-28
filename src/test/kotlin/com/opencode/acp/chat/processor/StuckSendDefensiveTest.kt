package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.StreamPhase
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.chat.viewmodel.MessageQueueManager
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.follow.EditorFollowManager
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression guards for the "stuck send" bug (see AGENTS.md "Stuck Send").
 *
 * After a timeout/HTTP error, `sendMutex` could be permanently locked and the
 * user's message silently disappeared. These tests cover the defensive fixes:
 *
 * 1. [SessionState.abortStreamingWithFallback] completes `responseDeferred`
 *    directly (mirrors `handleSessionError`) so `sendMessageInternal`'s
 *    `deferred.await()` never hangs forever.
 * 2. `ChatViewModel`'s connection-state observer resets `_streamPhase` to IDLE
 *    on DISCONNECTED/RECONNECTING/ERROR so the UI doesn't show a perpetual
 *    streaming indicator after the connection drops mid-send.
 * 3. [MessageQueueManager] does NOT re-queue a message that failed with the
 *    "Another message is already being sent" rejection — the mutex is stuck
 *    and retrying spins forever.
 */
class StuckSendDefensiveTest {

    // ── Test 1: SessionState.abortStreamingWithFallback ───────────────────

    /**
     * Minimal [SessionStateContext] for constructing a [SessionState] without
     * a real IntelliJ [Project]. Mirrors [SessionStateTest]'s testContext.
     */
    private val testContext = object : SessionStateContext {
        override fun emitSessionSignal(sessionId: String, signal: UiSignal) { /* no-op */ }
        override fun maybeTruncateToolOutput(
            toolName: String,
            output: List<JsonObject>,
        ): List<JsonObject> = output
    }

    private lateinit var sessionState: SessionState
    private lateinit var testScope: TestScope

    @BeforeEach
    fun setUpSessionState() {
        testScope = TestScope()
        sessionState = SessionState(
            sessionId = "test_session",
            scope = testScope.backgroundScope,
            sessionManager = testContext,
            followAgentFactory = { _, _ -> com.opencode.acp.chat.util.FakeFollowAgentDispatcher() },
        )
    }

    @AfterEach
    fun tearDownSessionState() {
        sessionState.close()
        testScope.cancel()
    }

    @Test
    fun `abortStreamingWithFallback completes responseDeferred`() {
        // Arrange: a fresh, un-completed deferred assigned to the session.
        val deferred = CompletableDeferred<Unit>()
        sessionState.responseDeferred = deferred
        sessionState.responseDeferred?.isCompleted shouldBe false

        // Act: abort with a fallback message id. The defensive backup should
        // complete the deferred directly (the StreamingCompleted signal may be
        // dropped by tryEmit if the signal buffer is full).
        sessionState.abortStreamingWithFallback(
            reason = "test abort",
            fallbackMessageId = "msg_fallback",
        )

        // Assert: the deferred is completed (so sendMessageInternal's await()
        // unblocks) and the field is nulled (so the next send starts clean).
        deferred.isCompleted shouldBe true
        sessionState.responseDeferred shouldBe null
    }

    @Test
    fun `abortStreamingWithFallback is idempotent when called twice`() {
        val first = CompletableDeferred<Unit>()
        sessionState.responseDeferred = first

        sessionState.abortStreamingWithFallback("first abort", "msg_1")
        first.isCompleted shouldBe true
        sessionState.responseDeferred shouldBe null

        // Second call must not throw — CompletableDeferred.complete() is
        // idempotent, and the null-safe ?. chain handles the already-nulled field.
        sessionState.abortStreamingWithFallback("second abort", "msg_2")
        sessionState.responseDeferred shouldBe null
    }

    @Test
    fun `abortStreamingWithFallback completes an already-completed deferred without throwing`() {
        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit) // pre-completed (e.g., by a concurrent path)
        sessionState.responseDeferred = deferred

        // Should not throw IllegalStateException despite the deferred being
        // already completed — complete() returns false but doesn't throw.
        sessionState.abortStreamingWithFallback("abort", "msg")
        deferred.isCompleted shouldBe true
        sessionState.responseDeferred shouldBe null
    }

    @Test
    fun `abortStreamingWithFallback completes deferred even when abort path throws`() {
        // First abort completes normally
        val first = CompletableDeferred<Unit>()
        sessionState.responseDeferred = first
        sessionState.abortStreamingWithFallback("first", "msg_1")
        first.isCompleted shouldBe true
        sessionState.responseDeferred shouldBe null

        // Second abort on already-aborted state — the abort path may throw
        // (state already cleared), but the try/finally must still complete
        // any new deferred and not propagate the exception.
        val second = CompletableDeferred<Unit>()
        sessionState.responseDeferred = second
        // This should not throw — the try/finally ensures the deferred is completed
        sessionState.abortStreamingWithFallback("second", "msg_2")
        second.isCompleted shouldBe true
        sessionState.responseDeferred shouldBe null
    }

    // ── Test 2: ChatViewModel _streamPhase reset on connection drop ───────

    /**
     * Writes to ChatViewModel's private `_streamPhase` MutableStateFlow via
     * reflection. `streamPhase` is exposed as a read-only StateFlow, so the
     * test cannot set `.value` directly. This simulates the "send in progress"
     * state without invoking the (mocked) service.
     */
    private fun setStreamPhase(viewModel: ChatViewModel, phase: StreamPhase) {
        val field = ChatViewModel::class.java.getDeclaredField("_streamPhase")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<StreamPhase>
        flow.value = phase
    }

    // NOTE: The following three tests use Thread.sleep polling loops to wait for the
    // connectionObserverJob to process connectionState changes. This is inherently
    // flaky under CI load — if the observer doesn't process the change within 10s
    // (500 × 20ms), the test fails. Converting to runTest with a TestCoroutineScheduler
    // would be deterministic but requires refactoring the connectionObserverJob to
    // use an injectable dispatcher (it currently uses the ViewModel's real scope).
    // The polling approach is acceptable for now — the 10s budget is generous and
    // the tests have been stable in practice. If they become flaky, convert to runTest.
    @Test
    fun `connectionState DISCONNECTED resets _streamPhase to IDLE`() {
        val harness = ViewModelHarness()
        val viewModel = harness.createViewModel()

        try {
            // Simulate a send in progress: _streamPhase is STREAMING.
            setStreamPhase(viewModel, StreamPhase.STREAMING)
            viewModel.streamPhase.value shouldBe StreamPhase.STREAMING

            // Emit DISCONNECTED — the connectionObserverJob should reset
            // _streamPhase to IDLE.
            harness.connectionState.value = ConnectionState.DISCONNECTED

            // The observer runs on the viewModel's scope (real Dispatcher). Poll
            // until the phase flips or time out. Budget: 500 × 20ms = 10s.
            var attempts = 0
            while (attempts < 500 && viewModel.streamPhase.value != StreamPhase.IDLE) {
                Thread.sleep(20)
                attempts++
            }
            viewModel.streamPhase.value shouldBe StreamPhase.IDLE
        } finally {
            harness.tearDown()
        }
    }

    @Test
    fun `connectionState ERROR resets _streamPhase to IDLE`() {
        val harness = ViewModelHarness()
        val viewModel = harness.createViewModel()

        try {
            setStreamPhase(viewModel, StreamPhase.SENDING)
            viewModel.streamPhase.value shouldBe StreamPhase.SENDING

            harness.connectionState.value = ConnectionState.ERROR

            var attempts = 0
            while (attempts < 500 && viewModel.streamPhase.value != StreamPhase.IDLE) {
                Thread.sleep(20)
                attempts++
            }
            viewModel.streamPhase.value shouldBe StreamPhase.IDLE
        } finally {
            harness.tearDown()
        }
    }

    @Test
    fun `connectionState RECONNECTING resets _streamPhase to IDLE`() {
        val harness = ViewModelHarness()
        val viewModel = harness.createViewModel()

        try {
            setStreamPhase(viewModel, StreamPhase.STREAMING)
            harness.connectionState.value = ConnectionState.RECONNECTING

            var attempts = 0
            while (attempts < 500 && viewModel.streamPhase.value != StreamPhase.IDLE) {
                Thread.sleep(20)
                attempts++
            }
            viewModel.streamPhase.value shouldBe StreamPhase.IDLE
        } finally {
            harness.tearDown()
        }
    }

    @Test
    fun `connectionState CONNECTED does NOT reset _streamPhase when already STREAMING`() {
        // Sanity check: the reset only fires on DISCONNECTED/RECONNECTING/ERROR,
        // not on CONNECTED. A CONNECTED emission during active streaming must
        // not clobber the phase. (Initial state is CONNECTED, so we start
        // STREAMING and re-emit CONNECTED — phase should stay STREAMING.)
        val harness = ViewModelHarness()
        val viewModel = harness.createViewModel()

        try {
            setStreamPhase(viewModel, StreamPhase.STREAMING)
            harness.connectionState.value = ConnectionState.CONNECTED

            // Give the observer a chance to (incorrectly) reset.
            Thread.sleep(200)
            viewModel.streamPhase.value shouldBe StreamPhase.STREAMING
        } finally {
            harness.tearDown()
        }
    }

    // ── Test 3: MessageQueueManager does not retry stuck-mutex rejection ─

    @Test
    fun `drainQueue does not re-queue message rejected by stuck sendMutex`() = runTest {
        val sent = mutableListOf<QueuedMessage>()
        val manager = MessageQueueManager { msg ->
            sent.add(msg)
            SendMessageResult.Error(
                "Another message is already being sent. Please wait for it to complete.",
                isStuckMutex = true,
            )
        }

        manager.queueMessage("stuck message")
        manager.drainQueue()

        // The message was sent (attempted) exactly once...
        sent shouldHaveSize 1
        // ...and NOT re-queued (the stuck-mutex guard drops it immediately).
        manager.queuedMessages.value shouldHaveSize 0
    }

    @Test
    fun `drainQueue re-queues a generic error (not stuck-mutex)`() = runTest {
        // Control: a generic network error IS re-queued (existing behavior).
        val sent = mutableListOf<QueuedMessage>()
        val manager = MessageQueueManager { msg ->
            sent.add(msg)
            SendMessageResult.Error("network failure")
        }

        manager.queueMessage("retry me")
        manager.drainQueue()

        sent shouldHaveSize 1
        manager.queuedMessages.value shouldHaveSize 1
        manager.queuedMessages.value[0].text shouldBe "retry me"
    }

    @Test
    fun `drainQueue stuck-mutex rejection does not block subsequent messages`() = runTest {
        // After a stuck-mutex rejection drops the first message, a subsequent
        // (different) message should still be sent normally — the queue is
        // not poisoned.
        val sent = mutableListOf<QueuedMessage>()
        val results = ArrayDeque<SendMessageResult>()
        results.add(SendMessageResult.Error("Another message is already being sent. Please wait for it to complete.", isStuckMutex = true))
        results.add(SendMessageResult.Success("msg_ok"))
        val manager = MessageQueueManager { msg ->
            sent.add(msg)
            results.removeFirst()
        }

        manager.queueMessage("doomed")
        manager.queueMessage("second")
        manager.drainQueue() // drops "doomed" (stuck-mutex guard)
        manager.drainQueue() // sends "second" (Success)

        sent shouldHaveSize 2
        sent.map { it.text } shouldBe listOf("doomed", "second")
        manager.queuedMessages.value shouldHaveSize 0
    }

    @Test
    fun `drainQueue propagates CancellationException without re-queuing`() = runTest {
        // Guards against a future change that catches CancellationException in the
        // generic catch (e: Exception) block — that would swallow cancellation and
        // re-queue the message, breaking coroutine cancellation semantics.
        val sent = mutableListOf<QueuedMessage>()
        val manager = MessageQueueManager { msg ->
            sent.add(msg)
            throw kotlinx.coroutines.CancellationException("test cancellation")
        }

        manager.queueMessage("cancel me")
        org.junit.jupiter.api.assertThrows<kotlinx.coroutines.CancellationException> {
            manager.drainQueue()
        }

        // The message was sent (attempted) exactly once...
        sent shouldHaveSize 1
        // ...and NOT re-queued (cancellation is not a failure).
        manager.queuedMessages.value shouldHaveSize 0
    }

    // ── Harness for ChatViewModel construction ───────────────────────────

    /**
     * Encapsulates the mockk setup needed to construct a real [ChatViewModel]
     * without a real IntelliJ application context. Mirrors
     * [ChatViewModelMessagesForwardingTest]'s setup, scoped to just what the
     * connectionObserverJob needs.
     */
    private class ViewModelHarness {
        val service: OpenCodeServiceApi = mockk(relaxed = true)
        private val project: Project = mockk(relaxed = true)
        private val settingsState: OpenCodeSettingsState = mockk(relaxed = true)
        private val followSettingsState: OpenCodeFollowSettingsState = mockk(relaxed = true)
        private val editorFollowManager: EditorFollowManager = mockk(relaxed = true)

        private val serviceMessagesFlow = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
        val serviceSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 256)
        val serviceGlobalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 256)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)

        private val scopes = mutableListOf<CoroutineScope>()

        init {
            every { service.messages } returns serviceMessagesFlow
            every { service.signals } returns serviceSignals
            every { service.globalSignals } returns serviceGlobalSignals
            every { service.connectionState } returns connectionState
            val scope = CoroutineScope(SupervisorJob())
            scopes += scope
            every { service.scope } returns scope

            mockkObject(OpenCodeSettingsState.Companion)
            every { OpenCodeSettingsState.getInstance() } returns settingsState
            every { settingsState.sidebarVisible } returns false

            mockkObject(EditorFollowManager.Companion)
            every { EditorFollowManager.getInstance(project) } returns editorFollowManager
            every { editorFollowManager.isFollowEnabled() } returns false

            mockkObject(OpenCodeFollowSettingsState.Companion)
            every { OpenCodeFollowSettingsState.getInstance() } returns followSettingsState
            every { followSettingsState.braveModeEnabled } returns false
        }

        // NOTE: service.sessionManager is NOT explicitly stubbed — the relaxed mock
        // returns a relaxed mock for it, and getActiveSession() returns null, which
        // is handled by ChatViewModel's null-safe calls at switchSession (lines 611-613).
        // If a future test using this harness calls a method that dereferences
        // sessionManager without a null check, add an explicit stub here.
        //
        // NOTE: Two scopes are created — one for service.scope (line 353, unused by
        // tests but needed for ChatViewModel construction) and one for the ViewModel
        // itself (line 371). Both are cancelled in tearDown.

        fun createViewModel(): ChatViewModel {
            val scope = CoroutineScope(SupervisorJob())
            scopes += scope
            return ChatViewModel(scope = scope, service = service, project = project)
        }

        fun tearDown() {
            scopes.forEach { it.cancel() }
            unmockkObject(OpenCodeSettingsState.Companion)
            unmockkObject(EditorFollowManager.Companion)
            unmockkObject(OpenCodeFollowSettingsState.Companion)
        }
    }
}