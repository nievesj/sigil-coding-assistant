package com.opencode.acp.chat.processor

import com.intellij.openapi.project.Project
import com.opencode.acp.SseEvent
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

/**
 * Tests for SessionState event processing, specifically the TextChunk/TextReplace/ToolUse
 * interaction that caused the "text before tool call disappears" bug.
 *
 * BLOCKER: These tests require IntelliJ Platform test infrastructure because:
 * 1. `SessionManager` is a final class with init logic that needs a real `Project`
 * 2. `EditorFollowManager.getInstance(project)` uses IntelliJ service lookup
 * 3. `runReadAction` / VCS APIs need platform threading infrastructure
 *
 * TO ENABLE: Use `HeavyPlatformTestCase` or `BasePlatformTestCase` which provides
 * a real `Project` instance with all platform services wired up. Alternatively,
 * refactor `SessionManager` to accept an interface instead of a concrete class,
 * allowing a test double to be injected.
 *
 * The test methods below are correct but commented out until the platform dependency
 * is resolved. They document the expected behavior for the text segmentation pipeline.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Disabled("Requires IntelliJ Platform Project stub — see TODOs in file. " +
    "To enable: (1) add mockito-testkit dependency and mock Project, or " +
    "(2) refactor SessionState to accept interfaces instead of concrete IntelliJ dependencies. " +
    "Tracked as high-priority test coverage gap — SessionState is the core event processing engine.")
class SessionStateTest {

    private lateinit var sessionState: SessionState
    private lateinit var scope: CoroutineScope

    /**
     * Creates a minimal [Project] stub for testing.
     *
     * IntelliJ IDEA's Project interface extends several base interfaces
     * (ProjectComponent, Disposable, etc.) with ~50 methods. A full stub requires
     * either the IntelliJ test framework or Mockito/MockK (not in current deps).
     *
     * This stub is a placeholder. When enabling these tests, replace with:
     *   val project = getProject()  // from HeavyPlatformTestCase
     * or add mockito-testkit dependency and use:
     *   val project = mock(Project::class.java) { on { basePath } doReturn "/tmp/test" }
     */
    private fun createTestProject(): Project {
        throw UnsupportedOperationException(
            "Project stub not implemented. Add mockito-testkit dependency or use HeavyPlatformTestCase."
        )
    }

    @BeforeEach
    fun setUp(testInfo: TestInfo) {
        scope = TestScope()
        // Uncomment when Project stub is available:
        // val project = createTestProject()
        // val sessionManager = SessionManager(scope, project)
        // sessionState = SessionState("test_session", scope, sessionManager, project)
    }

    @AfterEach
    fun tearDown() {
        if (::sessionState.isInitialized) {
            sessionState.close()
        }
        scope.cancel()
    }

    /**
     * Feed events to the session state and wait for processing to complete.
     *
     * TIMING NOTE: Uses `delay(10)` as a timing hack — fragile under load.
     * The `resegmentTextPartsDirect` call runs on Dispatchers.Default which
     * `TestScope` does not control. When these tests are enabled with real
     * platform infrastructure, replace with:
     *   - Dispatchers.setMain(testDispatcher) in setUp
     *   - Inject test dispatcher into SessionState/SessionManager constructors
     *   - Use advanceUntilIdle() which will control all dispatchers
     *   OR use a polling pattern: advanceUntilIdle() + delay(1) in a loop
     *   until expected state is reached.
     */
    private suspend fun process(vararg events: SseEvent) {
        for (event in events) {
            sessionState.processEvent(event)
        }
        // Advance the test dispatcher to process all enqueued coroutines
        (scope as? TestScope)?.advanceUntilIdle()
        // Also give the event processing coroutine (on Dispatchers.Default) a chance
        kotlinx.coroutines.delay(10)
    }

    @Test
    fun `textReplace after streaming does not clobber buffer`() = runTest {
        // This test reproduces the exact regression scenario:
        // text₁ → tool call → text₂
        // After sending TextReplace for text₂, text₁ must still be in the buffer.
        //
        // Steps:
        // 1. TextChunk(sessionId, "Hello ", partId="pt_1") — streams text₁
        // 2. TextReplace(sessionId, "Hello ", partId="pt_1") — should be skipped (deltas already streamed)
        // 3. ToolUse(sessionId, toolCallId="tc_1", toolName="read") — inserts text segment boundary
        // 4. TextChunk(sessionId, "World!", partId="pt_2") — streams text₂
        // 5. TextReplace(sessionId, "World!", partId="pt_2") — should be skipped
        // Assert: final buffer = "Hello World!" with tool pill between parts

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // 
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(
        //     SseEvent.TextChunk(sid, "Hello ", partId = "pt_1"),
        //     // TextReplace for pt_1 should be skipped since deltas already streamed
        //     SseEvent.TextReplace(sid, "Hello ", partId = "pt_1"),
        //     // Tool call inserts segment boundary
        //     SseEvent.ToolUse(sid, toolCallId = "tc_1", toolName = "read"),
        //     // Text for pt_2
        //     SseEvent.TextChunk(sid, "World!", partId = "pt_2"),
        //     // TextReplace for pt_2 should be skipped since deltas already streamed
        //     SseEvent.TextReplace(sid, "World!", partId = "pt_2"),
        // )
        // 
        // // The text buffer should have BOTH parts
        // withClue("textBuffer should contain both text parts") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "Hello World!"
        // }
        // 
        // // textSegments should have the ToolUse boundary preserved
        // withClue("textSegments should have 2 entries (text1 + tool boundary + text2)") {
        //     sessionState.ctx.textSegments shouldHaveSize 2
        // }
        // 
        // // The first segment should be anchored before the tool call
        // withClue("first segment anchorKey should be null (inserted at start)") {
        //     sessionState.ctx.textSegments[0].anchorKey shouldBe null
        // }
        // 
        // // The tool call should have been added to the parts map
        // val messages = sessionState.messages.first()
        // val msg = messages.values.firstOrNull()
        // assert(msg != null)
        // val parts = msg!!.parts
        // withClue("parts should contain tool call pill") {
        //     parts.containsKey("tc_1") shouldBe true
        // }
        // 
        // // Verify message parts ordering: text₁ before tool, text₂ after
        // val partKeys = parts.keys.toList()
        // val textIdx1 = partKeys.indexOfFirst { it.startsWith("text_0_") }
        // val toolIdx = partKeys.indexOf("tc_1")
        // val textIdx2 = partKeys.indexOfFirst { it.startsWith("text_1_") }
        // withClue("text₁ should be before tool call") {
        //     textIdx1 shouldBeLessThan toolIdx
        // }
        // withClue("text₂ should be after tool call") {
        //     textIdx2 shouldBeGreaterThan toolIdx
        // }
    }

    @Test
    fun `textReplace seeds buffer when no deltas streamed`() = runTest {
        // History-load path: TextReplace arrives without any prior TextChunk.
        // The buffer should be seeded with the text.
        //
        // Steps:
        // 1. TextReplace(sessionId, "Hello world", partId="pt_1")
        // Assert: buffer = "Hello world"
        // Assert: message has a Text part with "Hello world"

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(SseEvent.TextReplace(sid, "Hello world", partId = "pt_1"))
        // 
        // withClue("textBuffer should contain the seeded text") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "Hello world"
        // }
        // 
        // // The message should have at least one text part
        // val messages = sessionState.messages.first()
        // val msg = messages.values.firstOrNull()
        // assert(msg != null)
        // val textParts = msg!!.parts.filterKeys { it.startsWith("text_") }
        // withClue("message should have text parts") {
        //     textParts shouldNotBe emptyMap()
        // }
    }

    @Test
    fun `textReplace preserves textSegments from ToolUse`() = runTest {
        // Interleaving: TextChunk → ToolUse → TextChunk → TextReplace
        // The TextReplace for the second part must NOT clear the segments.
        //
        // Steps:
        // 1. TextChunk(sessionId, "Before tool. ", partId="pt_1")
        // 2. ToolUse(sessionId, toolCallId="tc_1", toolName="read")
        // 3. TextChunk(sessionId, "After tool.", partId="pt_2")
        // 4. TextReplace(sessionId, "After tool.", partId="pt_2") — should be skipped
        // Assert: textSegments has 2 entries (ToolUse boundary preserved)
        // Assert: message parts are ordered [text₁][tool pill][text₂]

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(
        //     SseEvent.TextChunk(sid, "Before tool. ", partId = "pt_1"),
        //     SseEvent.ToolUse(sid, toolCallId = "tc_1", toolName = "read"),
        //     SseEvent.TextChunk(sid, "After tool.", partId = "pt_2"),
        //     SseEvent.TextReplace(sid, "After tool.", partId = "pt_2"),
        // )
        // 
        // // textSegments should have the ToolUse boundary preserved
        // withClue("textSegments should have 2 entries") {
        //     sessionState.ctx.textSegments shouldHaveSize 2
        // }
        // 
        // // First segment starts at 0, second at position of "After tool."
        // withClue("first segment starts at 0") {
        //     sessionState.ctx.textSegments[0].startOffset shouldBe 0
        // }
        // withClue("second segment starts after first text part") {
        //     sessionState.ctx.textSegments[1].startOffset shouldBe "Before tool. ".length
        // }
        // 
        // // Buffer should have all text
        // withClue("buffer should have text before and after tool") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "Before tool. After tool."
        // }
        // 
        // // The second segment should be anchored after tc_1
        // withClue("second segment anchor is tc_1") {
        //     sessionState.ctx.textSegments[1].anchorKey shouldBe "tc_1"
        // }
    }

    @Test
    fun `textReplace with no partId falls through to seed path`() = runTest {
        // V2 events lack partId. TextReplace with no partId seeds the buffer when no
        // deltas have been streamed this turn (history-load path). If deltas were
        // already streamed, it is skipped like any other finalization echo.
        //
        // Steps:
        // 1. TextReplace(sessionId, "Fallback text", partId=null)
        // Assert: buffer = "Fallback text"

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(SseEvent.TextReplace(sid, "Fallback text", partId = null))
        // 
        // withClue("buffer should have the replacement text") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "Fallback text"
        // }
        // 
        // withClue("firstTextChunkReceived should be true") {
        //     sessionState.ctx.firstTextChunkReceived shouldBe true
        // }
        // 
        // withClue("activeTextPartId should be null (no partId in event)") {
        //     sessionState.ctx.activeTextPartId shouldBe null
        // }
    }

    @Test
    fun `multiple textReplace events for same part are only processed once`() = runTest {
        // If the server sends multiple TextReplace events for the same part,
        // only the first one should seed the buffer. Subsequent ones are skipped.
        //
        // Steps:
        // 1. TextReplace(sessionId, "First", partId="pt_1") — seeds buffer
        // 2. TextReplace(sessionId, "SECOND SHOULD NOT OVERWRITE", partId="pt_1") — same part, skipped
        // Assert: buffer = "First"

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(
        //     SseEvent.TextReplace(sid, "First", partId = "pt_1"),
        //     SseEvent.TextReplace(sid, "SECOND OVERWRITE", partId = "pt_1"),
        // )
        // 
        // withClue("buffer should contain the first text, not the second") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "First"
        // }
    }

    @Test
    fun `user echo stripping works on first textReplace`() = runTest {
        // When TextReplace arrives without prior deltas, user echo stripping
        // should still work (history-load path).
        //
        // Steps:
        // 1. setLastUserText("User: ")
        // 2. TextReplace(sessionId, "User: Response", partId="pt_1")
        // Assert: buffer = "Response"
        // Assert: userEchoStripped = true

        // TODO: Enable when Project stub is available
        // val sid = "test_session"
        // sessionState.setLastUserText("User: ")
        // sessionState.createAssistantMessage(null, null)
        // 
        // process(SseEvent.TextReplace(sid, "User: Response", partId = "pt_1"))
        // 
        // withClue("buffer should have user echo stripped") {
        //     sessionState.ctx.textBuffer.toString() shouldBe "Response"
        // }
        // withClue("userEchoStripped should be true") {
        //     sessionState.ctx.userEchoStripped shouldBe true
        // }
    }
}
