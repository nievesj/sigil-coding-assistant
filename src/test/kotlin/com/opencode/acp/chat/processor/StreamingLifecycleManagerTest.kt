package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
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

    private lateinit var textStreamingState: TextStreamingState
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

        textStreamingState = TextStreamingState()
        textStreaming = TextStreamingManager(
            textStreamingState = textStreamingState,
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

    // ── §8.3 Regression Guards: Streaming Jump Fix #2 ──────────────────────

    /**
     * §8.3 Guard 1: The `new_message` branch in [StreamingLifecycleManager.finalizeStreaming]
     * must finalize IMMEDIATELY (no 300ms debounce).
     *
     * Regression context: The `new_message` path used to share the 300ms debounced
     * finalization with normal `stop` events. The debounce delayed the old message's
     * `isStreaming=true→false` transition by 300ms, splitting state changes across two
     * frames and causing a mass LazyColumn dispose+recreate when the debounce finally
     * fired — the visible "streaming jump" flicker.
     *
     * This test proves immediacy by advancing the virtual clock by LESS than the 300ms
     * debounce window and asserting that finalization has already completed. If the
     * `new_message` branch were ever re-routed through the debounced path, this test
     * would fail (the message would still be streaming at 100ms).
     */
    @Test
    fun `§8_3 guard 1 - new_message branch finalizes immediately without 300ms debounce`() = runTest {
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
        val msgId = "msg_new_message_immediate"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // Trigger finalization with the new_message stop reason.
        lifecycle.finalizeStreaming(msgId, "new_message")

        // Advance the clock by only 100ms — well under the 300ms debounce window.
        // Do NOT call advanceUntilIdle() here: it would advance past the debounce
        // delay and mask a regression where new_message was re-routed through the
        // debounced branch. We deliberately stay inside the debounce window.
        advanceTimeBy(100.milliseconds)
        runCurrent()

        // The message must already be finalized — the new_message branch is synchronous
        // (stateLock.withLock runs inline on the calling thread).
        turnLifecycleState.isStreaming shouldBe false
        messages.value[msgId] shouldNotBe null
        messages.value[msgId]!!.isStreaming shouldBe false
        messages.value[msgId]!!.state shouldBe MessageState.Completed

        // No StreamingCompleted should have been emitted (the new message's completion
        // will emit it; emitting here would prematurely set _streamPhase=IDLE).
        val completedSignals = signals.replayCache.filterIsInstance<UiSignal.StreamingCompleted>()
        completedSignals shouldHaveSize 0

        // Sanity check: no pending debounce job should be left behind to fire later.
        // If a debounce job were scheduled, advancing past 300ms would finalize again
        // (no-op due to isStreaming=false guard) — but its presence would indicate
        // the wrong branch was taken. We assert no job was scheduled.
        turnLifecycleState.pendingStopJob shouldBe null
    }

    /**
     * §8.3 Guard 1 (negative control): The default `stop` reason DOES go through the
     * 300ms debounce. This test is the counterpart to the new_message immediacy guard —
     * it proves the debounce still exists for normal stops, so the new_message branch's
     * immediacy is a deliberate special case, not a global removal of debouncing.
     *
     * If this test and the new_message immediacy test both pass, the two branches are
     * genuinely distinct: `stop` waits 300ms, `new_message` does not.
     */
    @Test
    fun `§8_3 guard 1 - stop reason still debounced (negative control for new_message immediacy)`() = runTest {
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
        val msgId = "msg_stop_debounced_control"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        lifecycle.finalizeStreaming(msgId, "stop")

        // At 100ms (inside the debounce window), the message must STILL be streaming.
        advanceTimeBy(100.milliseconds)
        runCurrent()
        turnLifecycleState.isStreaming shouldBe true
        messages.value[msgId]!!.isStreaming shouldBe true
        messages.value[msgId]!!.state shouldBe MessageState.Created

        // A pending debounce job must exist — proving the debounced branch was taken.
        turnLifecycleState.pendingStopJob shouldNotBe null

        // Advance past the 300ms window to let the debounce fire.
        advanceTimeBy(250.milliseconds)
        runCurrent()

        turnLifecycleState.isStreaming shouldBe false
        messages.value[msgId]!!.isStreaming shouldBe false
        messages.value[msgId]!!.state shouldBe MessageState.Completed
    }

    /**
     * §8.3 Guard 2: [TextStreamingManager.resegmentFinal] must call `segmentHealed`
     * (NOT `segment`) so that finalizing a message does not change part keys.
     *
     * Regression context: On finalization, `resegmentFinal` previously used
     * `MarkdownSegmenter.segment()` (the non-healed path). If the segment structure
     * differed — healed closes unclosed markdown that non-healed treats as literal
     * text — part keys changed (e.g., `text_0_0` → `text_0_0 + text_0_1`), causing
     * every `key()` block in `AssistantMessage` to dispose+recreate — the "jump"
     * flicker. The fix: `resegmentFinal` always uses `segmentHealed` (passes
     * `overrideIsStreaming = true` to `resegmentDirect`).
     *
     * This test verifies the healing behavior end-to-end through the real
     * `StreamingLifecycleManager` → `TextStreamingManager` → `MarkdownSegmenter`
     * → `StreamHealer` chain. It seeds the reveal buffer with text containing
     * unclosed bold markdown (`**bold without closing`), then finalizes via
     * `finalizeStreaming` and asserts that the healed text part contains the
     * StreamHealer-closed marker (`**bold without closing**`).
     *
     * If `resegmentFinal` were reverted to use `segment()` (non-healed), the unclosed
     * `**` would remain unclosed in the rendered text part, and this test would fail.
     */
    @Test
    fun `§8_3 guard 2 - resegmentFinal heals unclosed markdown via segmentHealed`() = runTest {
        val msgId = "msg_resegment_final_healed"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        turnLifecycleState.isStreaming = true

        // Seed the reveal buffer with text that has UNCLOSED bold markdown.
        // StreamHealer.heal() should append a closing "**" to balance the unclosed pair.
        val unclosedBold = "This is **bold without closing"
        textStreamingState.revealBuffer.append(unclosedBold)
        textStreamingState.revealedLen = unclosedBold.length
        // Bypass the "no change since last resegment" short-circuit by ensuring
        // lastSegmentedLen differs from revealedLen (so resegmentDirect does work).
        textStreamingState.lastSegmentedLen = 0

        // Drive finalization through the real StreamingLifecycleManager.
        // The `idle` branch calls resegmentDirect(msgId) at the top of finalizeStreaming
        // (line 80) with the default overrideIsStreaming = turnLifecycleState.isStreaming.
        // At the point of that call, isStreaming is still true, so segmentHealed is used.
        // Then the idle branch finalizes the message. This exercises the same
        // resegmentDirect code path that resegmentFinal uses (overrideIsStreaming = true).
        streamingLifecycle.finalizeStreaming(msgId, "idle")
        advanceUntilIdle()

        // The message should be finalized.
        messages.value[msgId]!!.isStreaming shouldBe false
        messages.value[msgId]!!.state shouldBe MessageState.Completed

        // Locate the text part produced by resegmentDirect.
        val textPart = messages.value[msgId]!!.parts.values.filterIsInstance<MessagePart.Text>().single()

        // The healed content must have the closing "**" appended by StreamHealer.
        // segment() (non-healed) would leave the text as-is: "This is **bold without closing"
        // segmentHealed() runs StreamHealer.heal() first, producing: "This is **bold without closing**"
        textPart.content shouldBe "This is **bold without closing**"
    }

    /**
     * §8.3 Guard 2 (direct): Call [TextStreamingManager.resegmentFinal] directly and
     * verify it produces healed segments. This is a tighter test than the end-to-end
     * version above — it isolates `resegmentFinal` from the rest of `finalizeStreaming`
     * and proves the method itself uses `segmentHealed` (via `overrideIsStreaming = true`).
     *
     * The end-to-end test above exercises `resegmentDirect` via the `idle` branch's
     * top-of-function call (which uses the default `overrideIsStreaming = isStreaming`).
     * This test exercises `resegmentFinal` directly, which hard-codes
     * `overrideIsStreaming = true` — the actual fix for the streaming jump.
     */
    @Test
    fun `§8_3 guard 2 - resegmentFinal directly uses segmentHealed even when isStreaming is false`() = runTest {
        val msgId = "msg_resegment_final_direct"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId
        // CRITICAL: isStreaming is FALSE here. resegmentDirect's default for
        // overrideIsStreaming is turnLifecycleState.isStreaming, which would pick
        // the non-healed `segment()` path. resegmentFinal MUST override this to true
        // (healed) regardless of the isStreaming flag — that's the fix.
        turnLifecycleState.isStreaming = false

        // Unclosed inline code: odd number of single backticks.
        // StreamHealer closes it by appending a trailing "`".
        val unclosedInlineCode = "This has `unclosed code"
        textStreamingState.revealBuffer.append(unclosedInlineCode)
        textStreamingState.revealedLen = unclosedInlineCode.length
        textStreamingState.lastSegmentedLen = 0

        // Call resegmentFinal directly — the method under test for Guard 2.
        textStreaming.resegmentFinal(msgId)
        advanceUntilIdle()

        val textPart = messages.value[msgId]!!.parts.values.filterIsInstance<MessagePart.Text>().single()

        // If resegmentFinal used segment() (non-healed), the content would be unchanged:
        //   "This has `unclosed code"
        // With segmentHealed(), StreamHealer closes the unclosed inline code:
        //   "This has `unclosed code`"
        textPart.content shouldBe "This has `unclosed code`"
    }

    /**
     * §8.3 Guard 2 (key stability): The streaming jump fix's core requirement is that
     * part keys do NOT change between the streaming (healed) and final (healed) passes.
     * If both passes use `segmentHealed`, they produce identical segment structures,
     * so part keys are stable and Compose does not dispose+recreate visible items.
     *
     * This test verifies key stability directly: it runs `resegmentDirect` with
     * `overrideIsStreaming = true` (streaming pass), records the keys, then runs
     * `resegmentFinal` (final pass, also healed) and asserts the keys are unchanged.
     *
     * Regression: if `resegmentFinal` were reverted to `segment()` (non-healed), the
     * final pass could produce a different segment count (e.g., healed closes an
     * unclosed fence that non-healed treats as literal text), changing keys like
     * `text_0_0` → `text_0_0 + code_0_1`, which would trigger the jump.
     */
    @Test
    fun `§8_3 guard 2 - resegmentFinal preserves part keys from streaming pass (no jump)`() = runTest {
        val msgId = "msg_key_stability"
        messageMap.add(ChatMessage(
            id = msgId, role = MessageRole.ASSISTANT, parts = linkedMapOf(),
            isStreaming = true, state = MessageState.Created, timestamp = 1_000_000L,
        ))
        turnLifecycleState.activeMessageId = msgId

        // Content with unclosed bold — the kind of input where segment() and
        // segmentHealed() could diverge in segment structure.
        val content = "Intro **bold** text with **unclosed"
        textStreamingState.revealBuffer.append(content)
        textStreamingState.revealedLen = content.length
        textStreamingState.lastSegmentedLen = 0

        // Streaming pass: isStreaming = true → resegmentDirect uses segmentHealed.
        turnLifecycleState.isStreaming = true
        textStreaming.resegmentDirect(msgId)
        advanceUntilIdle()

        val streamingKeys = messages.value[msgId]!!.parts.keys
            .filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") }
            .toList()

        // Reset the resegment short-circuit so the final pass actually re-runs.
        textStreamingState.lastSegmentedLen = 0

        // Final pass: isStreaming = false, but resegmentFinal forces overrideIsStreaming = true
        // (healed). Keys must match the streaming pass exactly.
        turnLifecycleState.isStreaming = false
        textStreaming.resegmentFinal(msgId)
        advanceUntilIdle()

        val finalKeys = messages.value[msgId]!!.parts.keys
            .filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") }
            .toList()

        // The streaming and final passes must produce identical keys — no jump.
        finalKeys shouldBe streamingKeys

        // And the content must be healed (closing "**" appended).
        val textPart = messages.value[msgId]!!.parts.values.filterIsInstance<MessagePart.Text>().single()
        textPart.content shouldBe "Intro **bold** text with **unclosed**"
    }
}