package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.MessageState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StreamingLifecycleManager] — specifically the `naturalCompletion`
 * flag on [UiSignal.StreamingCompleted].
 *
 * Regression guard: the IDE response-complete notification must only fire for
 * natural completions (Stop/idle/debounce), not for aborts/errors/timeouts.
 * The [StreamingLifecycleManager.emitStreamingCompleted] method accepts a
 * `naturalCompletion` parameter that controls this flag.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingLifecycleManagerTest {

    private val logger = KotlinLogging.logger {}

    private lateinit var stateLock: ReentrantLock
    private lateinit var turnLifecycleState: TurnLifecycleState
    private lateinit var toolCallState: ToolCallState
    private lateinit var messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>
    private lateinit var messageMap: MessageMapManager
    private lateinit var scope: TestScope
    private lateinit var signals: MutableSharedFlow<UiSignal>

    private lateinit var textStreaming: TextStreamingManager
    private lateinit var streamingLifecycle: StreamingLifecycleManager

    @BeforeEach
    fun setUp() {
        stateLock = ReentrantLock()
        turnLifecycleState = TurnLifecycleState()
        toolCallState = ToolCallState()
        messages = MutableStateFlow(LinkedHashMap())
        scope = TestScope()
        // Use replay=1 so we can read the last emitted signal after advanceUntilIdle.
        // The production code uses extraBufferCapacity only (no replay), but for testing
        // we need to capture the signal. The emitStreamingCompleted method calls
        // signals.tryEmit() which works the same with or without replay.
        //
        // NOTE: This test setup could mask timing-dependent bugs where a late subscriber
        // misses a signal (production has no replay, so late subscribers don't see prior
        // emissions). For emitStreamingCompleted this is fine — the ViewModel is always
        // subscribed before the signal is emitted. If timing-dependent bugs are suspected,
        // add a test variant with no replay and a pre-subscribed collector.
        signals = MutableSharedFlow(replay = 1, extraBufferCapacity = 64)

        messageMap = MessageMapManager(
            toolCallState = toolCallState,
            stateLock = stateLock,
            messages = messages,
            isClosed = { false },
            logger = logger,
        )

        textStreaming = TextStreamingManager(
            textStreamingState = TextStreamingState(),
            turnLifecycleState = turnLifecycleState,
            stateLock = stateLock,
            scope = scope,
            messageMap = messageMap,
            firstTextSegmentedGet = { false },
            firstTextSegmentedSet = { },
            logger = logger,
        )

        streamingLifecycle = StreamingLifecycleManager(
            turnLifecycleState = turnLifecycleState,
            toolCallState = toolCallState,
            stateLock = stateLock,
            scope = scope,
            messageMap = messageMap,
            textStreaming = textStreaming,
            signals = signals,
            completeResponseDeferred = { },
            logger = logger,
        )
    }

    /**
     * Emit a StreamingCompleted signal and capture it.
     *
     * We use a simple approach: emit, then read the signal from the SharedFlow's
     * replay buffer. Since we use extraBufferCapacity (not replay), we instead
     * verify the naturalCompletion flag by checking the emitStreamingCompleted
     * method's behavior through the streamingCompletedEmitted guard and the
     * signal content via a direct emission test.
     *
     * The key insight: emitStreamingCompleted is idempotent (guarded by
     * streamingCompletedEmitted). So we can test the flag by emitting once,
     * checking the guard, then resetting and emitting with a different flag
     * to confirm the parameter is wired through.
     */
    @Test
    fun `emitStreamingCompleted with default naturalCompletion is true`() = runTest {
        val msgId = "msg_test"
        messageMap.add(ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(),
            isStreaming = true,
            state = MessageState.Created,
            timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // Emit with default parameter (naturalCompletion = true)
        streamingLifecycle.emitStreamingCompleted(msgId)
        advanceUntilIdle()

        // Read the last emitted signal from the replay buffer
        val signal = signals.replayCache.lastOrNull() as? UiSignal.StreamingCompleted
        signal shouldNotBe null
        signal!!.naturalCompletion shouldBe true
    }

    @Test
    fun `emitStreamingCompleted with naturalCompletion false sets the flag`() = runTest {
        val msgId = "msg_test"
        messageMap.add(ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(),
            isStreaming = true,
            state = MessageState.Created,
            timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // Emit with naturalCompletion = false (abort path)
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion = false)
        advanceUntilIdle()

        // Read the last emitted signal from the replay buffer
        val signal = signals.replayCache.lastOrNull() as? UiSignal.StreamingCompleted
        signal shouldNotBe null
        signal!!.naturalCompletion shouldBe false
    }

    @Test
    fun `emitStreamingCompleted is idempotent - second call is no-op`() = runTest {
        val msgId = "msg_test"
        messageMap.add(ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(),
            isStreaming = true,
            state = MessageState.Created,
            timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // First emission with naturalCompletion = false
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion = false)
        advanceUntilIdle()
        val firstSignal = signals.replayCache.lastOrNull() as? UiSignal.StreamingCompleted
        firstSignal shouldNotBe null
        firstSignal!!.naturalCompletion shouldBe false

        // Second emission is a no-op (guard prevents double emit)
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion = true)
        advanceUntilIdle()

        // The replay cache should still have only ONE StreamingCompleted
        // (the second call was a no-op)
        val completedSignals = signals.replayCache.filterIsInstance<UiSignal.StreamingCompleted>()
        completedSignals shouldHaveSize 1
        completedSignals[0].naturalCompletion shouldBe false
    }

    // ── finalizeStreaming ─────────────────────────────────────────────────

    @Test
    fun `finalizeStreaming with tool-calls reason does not set isStreaming false`() = runTest {
        val msgId = "msg_tc"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true
        streamingLifecycle.finalizeStreaming(msgId, "tool-calls")
        advanceUntilIdle()
        turnLifecycleState.isStreaming shouldBe true
    }

    @Test
    fun `finalizeStreaming with idle reason finalizes immediately`() = runTest {
        val msgId = "msg_idle"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true
        streamingLifecycle.finalizeStreaming(msgId, "idle")
        advanceUntilIdle()
        turnLifecycleState.isStreaming shouldBe false
        messages.value[msgId]!!.isStreaming shouldBe false
        messages.value[msgId]!!.state shouldBe MessageState.Completed
    }

    @Test
    fun `finalizeStreaming with new_message reason finalizes without StreamingCompleted`() = runTest {
        val msgId = "msg_nm"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true
        var deferredCompleted = false
        val managerWithDeferred = StreamingLifecycleManager(
            turnLifecycleState = turnLifecycleState,
            toolCallState = toolCallState,
            stateLock = stateLock,
            scope = scope,
            messageMap = messageMap,
            textStreaming = textStreaming,
            signals = signals,
            completeResponseDeferred = { deferredCompleted = true },
            logger = logger,
        )
        managerWithDeferred.finalizeStreaming(msgId, "new_message")
        advanceUntilIdle()
        turnLifecycleState.isStreaming shouldBe false
        messages.value[msgId]!!.isStreaming shouldBe false
        deferredCompleted shouldBe true
        val completedSignals = signals.replayCache.filterIsInstance<UiSignal.StreamingCompleted>()
        completedSignals shouldHaveSize 0
    }

    // ── finalizeStreaming: default debounced branch ───────────────────────

    /**
     * Default stop reason (e.g. "stop") goes through the 300ms debounce path.
     * After the debounce elapses, the message is finalized and StreamingCompleted
     * is emitted with naturalCompletion=true.
     */
    @Test
    fun `finalizeStreaming with default stop reason finalizes after debounce`() = runTest {
        // Use the runTest scope so advanceTimeBy drives the debounce coroutine.
        val lifecycle = StreamingLifecycleManager(
            turnLifecycleState = turnLifecycleState,
            toolCallState = toolCallState,
            stateLock = stateLock,
            scope = this,
            messageMap = messageMap,
            textStreaming = textStreaming,
            signals = signals,
            completeResponseDeferred = { },
            logger = logger,
        )
        val msgId = "msg_stop"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // Trigger the debounced finalization with a non-special stop reason.
        lifecycle.finalizeStreaming(msgId, "stop")

        // Before the debounce elapses, the message should still be streaming.
        // Do NOT call advanceUntilIdle() here — it would advance past the 300ms
        // debounce delay and fire the debounce before we can assert.
        advanceTimeBy(100.milliseconds)
        turnLifecycleState.isStreaming shouldBe true
        messages.value[msgId]!!.isStreaming shouldBe true
        messages.value[msgId]!!.state shouldBe MessageState.Created

        // Advance past the 300ms debounce window (100 + 250 = 350ms total) and
        // run any tasks that became ready.
        advanceTimeBy(250.milliseconds)
        runCurrent()

        turnLifecycleState.isStreaming shouldBe false
        messages.value[msgId]!!.isStreaming shouldBe false
        messages.value[msgId]!!.state shouldBe MessageState.Completed

        val completedSignals = signals.replayCache.filterIsInstance<UiSignal.StreamingCompleted>()
        completedSignals shouldHaveSize 1
        completedSignals[0].messageId shouldBe msgId
        completedSignals[0].naturalCompletion shouldBe true
    }

    /**
     * If activeMessageId changes during the 300ms debounce window (e.g. a new
     * turn started via ResetTurn), the debounce job must NOT finalize the old
     * message — that would kill the new turn's isStreaming flag and emit a
     * stale StreamingCompleted for the old message.
     */
    @Test
    fun `finalizeStreaming debounced skips if activeMessageId changed during debounce`() = runTest {
        // Use the runTest scope so advanceTimeBy drives the debounce coroutine.
        val lifecycle = StreamingLifecycleManager(
            turnLifecycleState = turnLifecycleState,
            toolCallState = toolCallState,
            stateLock = stateLock,
            scope = this,
            messageMap = messageMap,
            textStreaming = textStreaming,
            signals = signals,
            completeResponseDeferred = { },
            logger = logger,
        )
        val oldMsgId = "msg_old"
        val newMsgId = "msg_new"
        messageMap.add(ChatMessage(
            id = oldMsgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        messageMap.add(ChatMessage(
            id = newMsgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = oldMsgId
        turnLifecycleState.isStreaming = true

        // Trigger the debounced finalization for the old message.
        lifecycle.finalizeStreaming(oldMsgId, "stop")

        // Before the debounce elapses, a new turn starts: activeMessageId changes.
        // Do NOT call advanceUntilIdle() here — it would advance past the 300ms
        // debounce delay and fire the debounce before we can change activeMessageId.
        advanceTimeBy(100.milliseconds)
        turnLifecycleState.activeMessageId = newMsgId
        // isStreaming stays true for the new turn.

        // Advance past the 300ms debounce window (100 + 250 = 350ms total) and
        // run any tasks that became ready.
        advanceTimeBy(250.milliseconds)
        runCurrent()

        // The old message must NOT have been finalized by the debounce job.
        messages.value[oldMsgId]!!.isStreaming shouldBe true
        messages.value[oldMsgId]!!.state shouldBe MessageState.Created

        // The new message is untouched by the debounce job.
        messages.value[newMsgId]!!.isStreaming shouldBe true
        messages.value[newMsgId]!!.state shouldBe MessageState.Created

        // No StreamingCompleted should have been emitted for the old message.
        val completedSignals = signals.replayCache.filterIsInstance<UiSignal.StreamingCompleted>()
        completedSignals shouldHaveSize 0
    }
}