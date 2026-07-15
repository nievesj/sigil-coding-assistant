package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.PartState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TextStreamingManager] (TDD §7.1.1).
 *
 * Uses [TestScope] / [runTest] for coroutine control. No IntelliJ Platform
 * dependencies — [TextStreamingManager] operates on [TextStreamingState],
 * [TurnLifecycleState], [ReentrantLock], and a [MessageMapManager].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TextStreamingManagerTest {

    private val logger = KotlinLogging.logger {}

    private lateinit var textStreamingState: TextStreamingState
    private lateinit var turnLifecycleState: TurnLifecycleState
    private lateinit var stateLock: ReentrantLock
    private lateinit var scope: TestScope
    private lateinit var toolCallState: ToolCallState
    private lateinit var messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>
    private lateinit var messageMap: MessageMapManager
    private lateinit var manager: TextStreamingManager

    private var firstTextSegmented: Boolean = false

    @BeforeEach
    fun setUp() {
        textStreamingState = TextStreamingState()
        turnLifecycleState = TurnLifecycleState()
        stateLock = ReentrantLock()
        scope = TestScope()
        toolCallState = ToolCallState()
        messages = MutableStateFlow(LinkedHashMap())
        messageMap = MessageMapManager(
            toolCallState = toolCallState,
            stateLock = stateLock,
            messages = messages,
            isClosed = { false },
            logger = logger,
        )
        firstTextSegmented = false
        manager = TextStreamingManager(
            textStreamingState = textStreamingState,
            turnLifecycleState = turnLifecycleState,
            stateLock = stateLock,
            scope = scope,
            messageMap = messageMap,
            firstTextSegmentedGet = { firstTextSegmented },
            firstTextSegmentedSet = { firstTextSegmented = it },
            logger = logger,
        )
    }

    private fun seedMessage(msgId: String): ChatMessage {
        val msg = ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf<String, MessagePart>(),
            timestamp = System.currentTimeMillis(),
            isStreaming = true,
        )
        messageMap.add(msg)
        return msg
    }

    @Test
    fun `flushReveal - all remaining text revealed, revealJob cancelled`() = runTest {
        val msgId = "msg_flush"
        seedMessage(msgId)

        // Put content into the reveal buffer but only reveal part of it.
        textStreamingState.revealBuffer.append("Hello world, this is streamed text.")
        textStreamingState.revealedLen = 5 // only "Hello" revealed so far
        textStreamingState.sourceComplete = false

        manager.flushReveal()

        textStreamingState.revealedLen shouldBe textStreamingState.revealBuffer.length
        textStreamingState.sourceComplete shouldBe true
        textStreamingState.revealJob shouldBe null
    }

    @Test
    fun `resegmentDirect streaming - produces text parts in message map`() = runTest {
        val msgId = "msg_resegment_stream"
        seedMessage(msgId)
        turnLifecycleState.isStreaming = true

        // Put markdown text into the reveal buffer and reveal all of it.
        textStreamingState.revealBuffer.append("Hello **bold** world.")
        textStreamingState.revealedLen = textStreamingState.revealBuffer.length

        manager.resegmentDirect(msgId, overrideIsStreaming = true)

        val msg = messages.value[msgId]
        msg shouldNotBe null
        val hasTextPart = msg!!.parts.values.any { it is MessagePart.Text }
        hasTextPart shouldBe true
    }

    @Test
    fun `resegmentFinal - uses segmentHealed (consistency with streaming)`() = runTest {
        val msgId = "msg_resegment_final"
        seedMessage(msgId)
        turnLifecycleState.isStreaming = false

        textStreamingState.revealBuffer.append("Final **bold** content.")
        textStreamingState.revealedLen = textStreamingState.revealBuffer.length

        manager.resegmentFinal(msgId)

        val msg = messages.value[msgId]
        msg shouldNotBe null
        val hasTextPart = msg!!.parts.values.any { it is MessagePart.Text }
        hasTextPart shouldBe true
    }

    @Test
    fun `scheduleResegment - first call immediate, firstTextSegmented becomes true`() = runTest {
        val msgId = "msg_schedule"
        seedMessage(msgId)
        turnLifecycleState.isStreaming = true

        textStreamingState.revealBuffer.append("Scheduled text.")
        textStreamingState.revealedLen = textStreamingState.revealBuffer.length

        // First call is non-debounced (immediate).
        manager.scheduleResegment(msgId)
        advanceUntilIdle()

        firstTextSegmented shouldBe true

        // The immediate resegment should have produced text parts.
        val msg = messages.value[msgId]
        msg shouldNotBe null
        val hasTextPart = msg!!.parts.values.any { it is MessagePart.Text }
        hasTextPart shouldBe true
    }

    @Test
    fun `freezeThinking - active thinking phase frozen into parts map`() = runTest {
        val msgId = "msg_freeze"
        seedMessage(msgId)

        // Set up an active thinking phase.
        val thinkingKey = "thinking_0"
        textStreamingState.activeThinkingKey = thinkingKey
        textStreamingState.thinkingBuffer.append("Reasoning about the problem.")
        textStreamingState.thinkingRevealBuffer.append("Reasoning about the problem.")
        textStreamingState.thinkingRevealedLen = textStreamingState.thinkingRevealBuffer.length
        turnLifecycleState.activeMessageId = msgId

        manager.freezeThinking()

        // The thinking part should be in the message map with Completed state.
        val msg = messages.value[msgId]
        msg shouldNotBe null
        val thinkingPart = msg!!.parts[thinkingKey] as? MessagePart.Thinking
        thinkingPart shouldNotBe null
        thinkingPart!!.state shouldBe PartState.Completed
        thinkingPart.content shouldBe "Reasoning about the problem."

        // activeThinkingKey should be cleared.
        textStreamingState.activeThinkingKey shouldBe null
    }
}