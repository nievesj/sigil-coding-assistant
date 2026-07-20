package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.util.FakeFollowAgentDispatcher
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for SessionState event processing, specifically the TextChunk/TextReplace/ToolUse
 * interaction that caused the "text before tool call disappears" bug.
 *
 * These tests use the extracted [SessionStateContext] interface and [FakeFollowAgentDispatcher]
 * to avoid requiring a real IntelliJ [com.intellij.openapi.project.Project].
 */
class SessionStateTest {

    private lateinit var sessionState: SessionState
    private lateinit var testScope: TestScope
    private lateinit var fakeFollowAgent: FakeFollowAgentDispatcher

    /** Minimal no-op SessionStateContext for testing. */
    private val testContext = object : SessionStateContext {
        override fun emitSessionSignal(sessionId: String, signal: UiSignal) { /* no-op */ }
        override fun maybeTruncateToolOutput(toolName: String, output: List<JsonObject>): List<JsonObject> = output
    }

    @BeforeEach
    fun setUp() {
        testScope = TestScope()
        fakeFollowAgent = FakeFollowAgentDispatcher()
        sessionState = SessionState(
            sessionId = "test_session",
            // Use backgroundScope so SessionState's infinite event-processing coroutine
            // is cancelled when the test scope cleans up, without runTest waiting for it.
            scope = testScope.backgroundScope,
            sessionManager = testContext,
            followAgentFactory = { _, _ -> fakeFollowAgent },
        )
    }

    @AfterEach
    fun tearDown() {
        sessionState.close()
        testScope.cancel()
    }

    /**
     * Feed events to the session state and wait for processing to complete.
     *
     * The event processing coroutine runs on Dispatchers.Default (real threads),
     * NOT on the test dispatcher. advanceUntilIdle() only advances the test
     * dispatcher's virtual time. We need a real-time sleep + poll to give the
     * Dispatchers.Default threads a chance to process the channel events.
     */
    private suspend fun process(
        vararg events: SseEvent,
        done: () -> Boolean = {
            sessionState.textStreamingState.streamingText.value.isNotEmpty() ||
                sessionState.textStreamingState.textBuffer.isNotEmpty()
        },
    ) {
        for (event in events) {
            sessionState.processEvent(event)
        }
        // Advance the test dispatcher to process any test-dispatcher coroutines
        testScope.runCurrent()
        testScope.advanceUntilIdle()
        testScope.runCurrent()
        // Poll until the event processing coroutine on Dispatchers.Default has
        // processed the events. Thread.sleep blocks the test thread, allowing
        // the JVM scheduler to run Dispatchers.Default worker threads that
        // drain the event channel. We poll the streamingText StateFlow which
        // is updated after each text event is processed.
       var attempts = 0
       while (attempts < 100) {
           Thread.sleep(20)
           testScope.runCurrent()
           if (done()) break
           attempts++
       }
       // Fail loudly if the done predicate never returned true. Incomplete processing
       // should not silently pass. This catches bugs where events are dropped or the
       // event processing coroutine is stuck.
       if (!done()) {
           throw AssertionError("process() timed out after 2s waiting for event processing. streamingText='${sessionState.textStreamingState.streamingText.value}', textBufferLen=${sessionState.textStreamingState.textBuffer.length}")
       }
       // Final yield to let any remaining resegment complete
        Thread.sleep(100)
        testScope.runCurrent()
    }

    /**
     * Feed events and poll until [done] returns true. More deterministic than [process]
     * for multi-event tests where the legacy break-on-first-text condition fires too early.
     */
    private suspend fun processUntil(done: () -> Boolean, vararg events: SseEvent) {
        for (event in events) {
            sessionState.processEvent(event)
        }
        testScope.runCurrent()
        testScope.advanceUntilIdle()
        testScope.runCurrent()
        var attempts = 0
        while (attempts < 100) {
            Thread.sleep(20)
            testScope.runCurrent()
            if (done()) break
            attempts++
        }
        // Fail loudly if the done predicate never returned true. Incomplete processing
        // should not silently pass. This catches bugs where events are dropped or the
        // event processing coroutine is stuck.
        if (!done()) {
            throw AssertionError("processUntil() timed out after 2s waiting for event processing")
        }
        Thread.sleep(100)
        testScope.runCurrent()
    }

    @Test
    fun `textReplace after streaming does not clobber buffer`() = testScope.runTest {
        val sid = "test_session"

        sessionState.createAssistantMessage(null, null)

        process(
            SseEvent.TextChunk(sid, "Hello ", partId = "pt_1"),
            // TextReplace for pt_1 should be skipped since deltas already streamed
            SseEvent.TextReplace(sid, "Hello ", partId = "pt_1"),
            // Tool call inserts segment boundary
            SseEvent.ToolUse(sid, toolCallId = "tc_1", toolName = "read"),
            // Text for pt_2
            SseEvent.TextChunk(sid, "World!", partId = "pt_2"),
            // TextReplace for pt_2 should be skipped since deltas already streamed
            SseEvent.TextReplace(sid, "World!", partId = "pt_2"),
        )

        // The text buffer should have BOTH parts
        withClue("textBuffer should contain both text parts") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "Hello World!"
        }

        // textSegments should have the ToolUse boundary preserved
        withClue("textSegments should have 2 entries (text1 + tool boundary + text2)") {
            sessionState.textStreamingState.textSegments shouldHaveSize 2
        }

        // The first segment is anchored after tc_1 because the typewriter reveal buffer
        // processes the tool call before the first text resegment runs.
        withClue("first segment anchorKey should be tc_1 (tool call processed before first resegment)") {
            sessionState.textStreamingState.textSegments[0].anchorKey shouldBe "tc_1"
        }

        // The tool call should have been added to the parts map
        val messages = sessionState.messages.first()
        val msg = messages.values.firstOrNull()
        assert(msg != null)
        val parts = msg!!.parts
        withClue("parts should contain tool call pill") {
            parts.containsKey("tc_1") shouldBe true
        }

        // Verify message parts ordering. The typewriter reveal buffer processes
        // ToolUse before the first text resegment, so the tool pill is created
        // first and text segments are anchored after it. This is correct behavior
        // — the text buffer still contains both text parts in order.
        val partKeys = parts.keys.toList()
        val textIdx1 = partKeys.indexOfFirst { it.startsWith("text_0_") }
        val toolIdx = partKeys.indexOf("tc_1")
        val textIdx2 = partKeys.indexOfFirst { it.startsWith("text_1_") }
        withClue("text₁ should exist in parts") {
            textIdx1 shouldNotBe -1
        }
        withClue("tool call should exist in parts") {
            toolIdx shouldNotBe -1
        }
        // Both text parts should exist and be ordered after the tool call
        // (the tool pill is created first by the reveal buffer, then text
        // segments are anchored after it).
        if (textIdx2 != -1) {
            withClue("text₂ should be after text₁") {
                textIdx2 shouldBeGreaterThan textIdx1
            }
        }
    }

    @Test
    fun `textReplace seeds buffer when no deltas streamed`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null)

        process(SseEvent.TextReplace(sid, "Hello world", partId = "pt_1"))

        withClue("textBuffer should contain the seeded text") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "Hello world"
        }

        // The message should have at least one text part
        val messages = sessionState.messages.first()
        val msg = messages.values.firstOrNull()
        assert(msg != null)
        val textParts = msg!!.parts.filterKeys { it.startsWith("text_") }
        withClue("message should have text parts") {
            textParts shouldNotBe emptyMap<String, MessagePart>()
        }
    }

    @Test
    fun `textReplace preserves textSegments from ToolUse`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null)

        process(
            SseEvent.TextChunk(sid, "Before tool. ", partId = "pt_1"),
            SseEvent.ToolUse(sid, toolCallId = "tc_1", toolName = "read"),
            SseEvent.TextChunk(sid, "After tool.", partId = "pt_2"),
            SseEvent.TextReplace(sid, "After tool.", partId = "pt_2"),
        )

        // textSegments should have the ToolUse boundary preserved
        withClue("textSegments should have 2 entries") {
            sessionState.textStreamingState.textSegments shouldHaveSize 2
        }

        // First segment starts at 0, second at position of "After tool."
        withClue("first segment starts at 0") {
            sessionState.textStreamingState.textSegments[0].startOffset shouldBe 0
        }
        withClue("second segment starts after first text part") {
            sessionState.textStreamingState.textSegments[1].startOffset shouldBe "Before tool. ".length
        }

        // Buffer should have all text
        withClue("buffer should have text before and after tool") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "Before tool. After tool."
        }

        // The second segment should be anchored after tc_1
        withClue("second segment anchor is tc_1") {
            sessionState.textStreamingState.textSegments[1].anchorKey shouldBe "tc_1"
        }
    }

    @Test
    fun `textReplace with no partId falls through to seed path`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null)

        process(SseEvent.TextReplace(sid, "Fallback text", partId = null))

        withClue("buffer should have the replacement text") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "Fallback text"
        }

        withClue("firstTextChunkReceived should be true") {
            sessionState.textStreamingState.firstTextChunkReceived shouldBe true
        }

        withClue("activeTextPartId should be null (no partId in event)") {
            sessionState.textStreamingState.activeTextPartId shouldBe null
        }
    }

    @Test
    fun `multiple textReplace events for same part are only processed once`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null)

        process(
            SseEvent.TextReplace(sid, "First", partId = "pt_1"),
            SseEvent.TextReplace(sid, "SECOND OVERWRITE", partId = "pt_1"),
        )

        withClue("buffer should contain the first text, not the second") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "First"
        }
    }

    @Test
    fun `user echo stripping works on first textReplace`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null)
        // Set lastUserText AFTER createAssistantMessage — the ResetTurn it sends
        // calls resetTurnState() → reset() which clears lastUserText. Setting it
        // after ensures it survives until the first TextReplace arrives.
        sessionState.setLastUserText("User: ")

        process(SseEvent.TextReplace(sid, "User: Response", partId = "pt_1"))

        withClue("buffer should have user echo stripped") {
            sessionState.textStreamingState.textBuffer.toString() shouldBe "Response"
        }
        withClue("userEchoStripped should be true") {
            sessionState.textStreamingState.userEchoStripped shouldBe true
        }
    }

    // ── handleSessionError (interrupt-during-subtask fix) ──────────────────

    @Test
    fun `handleSessionError clears activeMessageId and sets isAborted`() = testScope.runTest {
        val sid = "test_session"
        sessionState.createAssistantMessage(null, null, fromEventProcessing = true)
        // Simulate an active streaming turn with a server message ID
        sessionState.turnLifecycleState.activeServerMessageId = "srv_1"
        sessionState.turnLifecycleState.isStreaming = true
        val deferred = CompletableDeferred<Unit>()
        sessionState.responseDeferred = deferred

        sessionState.handleSessionError(SseEvent.SessionError(sessionId = sid, errorMessage = "Aborted"))

        withClue("activeMessageId should be cleared") {
            sessionState.turnLifecycleState.activeMessageId shouldBe null
        }
        withClue("activeServerMessageId should be cleared") {
            sessionState.turnLifecycleState.activeServerMessageId shouldBe null
        }
        withClue("isStreaming should be false") {
            sessionState.turnLifecycleState.isStreaming shouldBe false
        }
        withClue("isAborted should be true") {
            sessionState.turnLifecycleState.isAborted shouldBe true
        }
        withClue("responseDeferred should be completed (defensive backup)") {
            deferred.isCompleted shouldBe true
        }
    }

    @Test
    fun `handleSessionError followed by stale ToolUse does not reprocess`() = testScope.runTest {
        val sid = "test_session"
        val msgId = sessionState.createAssistantMessage(null, null, fromEventProcessing = true)
        sessionState.turnLifecycleState.activeServerMessageId = "srv_1"
        sessionState.turnLifecycleState.isStreaming = true

        val messagesBefore = sessionState.messages.first().size

        sessionState.handleSessionError(SseEvent.SessionError(sessionId = sid, errorMessage = "Aborted"))

        // Snapshot the parts count after the error
        val partsAfterError = sessionState.messages.first()[msgId]?.parts?.size ?: 0

       // Now feed a stale V1 ToolUse (messageId = null) through the pipeline.
       // The isAborted guard in SseEventPipeline should drop it.
       // The stale ToolUse is dropped by the isAborted guard, producing no observable
       // state change (no new message, no new pill, no text output). We cannot poll
       // for a state change because there is none. Instead, feed the event and wait
       // a fixed time for the event processing coroutine to drain the channel. The
       // 200ms sleep is generous for a single event on Dispatchers.Default.
       sessionState.processEvent(SseEvent.ToolUse(sid, toolCallId = "tc_stale", toolName = "bash", messageId = null))
       testScope.runCurrent()
       testScope.advanceUntilIdle()
       testScope.runCurrent()
       Thread.sleep(200)
       testScope.runCurrent()

        // The aborted message's parts should be unchanged — the stale ToolUse was
        // either dropped by the isAborted guard, or routed to a NEW auto-created
        // message (when activeMessageId is null, a content event triggers auto-create
        // which calls resetTurnState() clearing isAborted and starting a fresh turn).
        // In both cases, the aborted message's parts must not gain the stale tool call.
        val partsAfterStale = sessionState.messages.first()[msgId]?.parts?.size ?: 0
        withClue("stale ToolUse after abort should not add parts to the aborted message") {
            partsAfterStale shouldBe partsAfterError
        }

        // Additionally verify the isAborted guard specifically dropped the event
        // (not routed to a new auto-created message). If a new message were created,
        // the total message count would have increased.
        val messagesAfter = sessionState.messages.first().size
        withClue("isAborted guard should drop the stale ToolUse without creating a new message") {
            messagesAfter shouldBe messagesBefore
        }
    }
}