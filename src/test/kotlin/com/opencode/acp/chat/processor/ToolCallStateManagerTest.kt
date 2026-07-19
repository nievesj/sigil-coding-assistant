package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ToolCallStateManager] (TDD §7.1.1).
 *
 * Uses mockk to stub [SessionManager] (which requires an IntelliJ [Project]).
 * [ToolCallStateManager] itself is a pure Kotlin class operating on
 * [ToolCallState], [ReentrantLock], and [MessageMapManager].
 */
class ToolCallStateManagerTest {

    private val logger = KotlinLogging.logger {}

    private lateinit var toolCallState: ToolCallState
    private lateinit var stateLock: ReentrantLock
    private lateinit var messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>
    private lateinit var messageMap: MessageMapManager
    private lateinit var sessionManager: SessionManager
    private lateinit var manager: ToolCallStateManager

    @BeforeEach
    fun setUp() {
        toolCallState = ToolCallState()
        stateLock = ReentrantLock()
        messages = MutableStateFlow(LinkedHashMap())
        messageMap = MessageMapManager(
            toolCallState = toolCallState,
            stateLock = stateLock,
            messages = messages,
            isClosed = { false },
            logger = logger,
        )
        sessionManager = mockk()
        // No truncation by default — return the output unchanged.
        every { sessionManager.maybeTruncateToolOutput(any(), any()) } answers {
            secondArg<List<JsonObject>>()
        }
        manager = ToolCallStateManager(
            toolCallState = toolCallState,
            stateLock = stateLock,
            messageMap = messageMap,
            sessionManager = sessionManager,
            logger = logger,
        )
    }

    private fun seedMessageWithToolCall(msgId: String, toolCallId: String, status: ToolCallStatus): ChatMessage {
        val pill = ToolCallPill(
            toolCallId = toolCallId,
            toolName = "read",
            title = "read",
            kind = ToolKind.READ,
            status = status,
        )
        val part = MessagePart.ToolCall(pill = pill, state = PartState.InProgress)
        val msg = ChatMessage(
            id = msgId,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(toolCallId to part),
            timestamp = System.currentTimeMillis(),
        )
        messageMap.add(msg)
        // add() only populates toolCallIndex; toolCallPills is populated
        // separately by SessionState's ToolUse handler in production. Populate
        // it here to match the production state the manager expects.
        toolCallState.toolCallPills[toolCallId] = pill
        return msg
    }

    @Test
    fun `updateStatus IN_PROGRESS to COMPLETED - pill status synced`() {
        val toolCallId = "tc_complete"
        seedMessageWithToolCall("msg_1", toolCallId, ToolCallStatus.IN_PROGRESS)

        manager.updateStatus(toolCallId, ToolCallStatus.COMPLETED)

        // Pill in toolCallState should be updated.
        toolCallState.toolCallPills[toolCallId]?.status shouldBe ToolCallStatus.COMPLETED
        // Part state should be Completed.
        toolCallState.toolPartStates[toolCallId] shouldBe PartState.Completed

        // The message part should reflect the new status.
        val msg = messages.value["msg_1"]
        msg shouldNotBe null
        val part = msg!!.parts[toolCallId] as? MessagePart.ToolCall
        part shouldNotBe null
        part!!.pill.status shouldBe ToolCallStatus.COMPLETED
        part.state shouldBe PartState.Completed
    }

    @Test
    fun `updateStatus IN_PROGRESS to FAILED - error text in PartState Failed`() {
        val toolCallId = "tc_failed"
        seedMessageWithToolCall("msg_2", toolCallId, ToolCallStatus.IN_PROGRESS)

        val errorOutput = listOf(JsonObject(mapOf("text" to JsonPrimitive("boom"))))
        manager.updateStatus(toolCallId, ToolCallStatus.FAILED, output = errorOutput)

        // Part state should be Failed with error text.
        val partState = toolCallState.toolPartStates[toolCallId]
        partState shouldNotBe null
        (partState is PartState.Failed) shouldBe true
        val failed = partState as PartState.Failed
        failed.reason shouldBe "boom"

        // The message part should reflect FAILED status.
        val msg = messages.value["msg_2"]
        msg shouldNotBe null
        val part = msg!!.parts[toolCallId] as? MessagePart.ToolCall
        part shouldNotBe null
        part!!.pill.status shouldBe ToolCallStatus.FAILED
        (part.state is PartState.Failed) shouldBe true
    }

    @Test
    fun `updateStatus for evicted tool (not in index) - skipped, no misrouting`() {
        // No message seeded — toolCallId is not in the index.
        val toolCallId = "tc_evicted"

        // Should not throw and should not misroute to any message.
        manager.updateStatus(toolCallId, ToolCallStatus.COMPLETED)

        // No message should have been updated (map is empty).
        messages.value.size shouldBe 0
        // toolPartStates still gets the state (it's set before the index check),
        // but no message was modified.
        toolCallState.toolPartStates[toolCallId] shouldBe PartState.Completed
    }

    @Test
    fun `setState Completed - pill status synced to matching ToolCallStatus`() {
        val toolCallId = "tc_setstate"
        seedMessageWithToolCall("msg_3", toolCallId, ToolCallStatus.IN_PROGRESS)

        manager.setState(toolCallId, PartState.Completed)

        toolCallState.toolCallPills[toolCallId]?.status shouldBe ToolCallStatus.COMPLETED

        val msg = messages.value["msg_3"]
        msg shouldNotBe null
        val part = msg!!.parts[toolCallId] as? MessagePart.ToolCall
        part shouldNotBe null
        part!!.state shouldBe PartState.Completed
    }

    @Test
    fun `snapshot - consistent partStates, pills, and toolPartStates`() {
        val toolCallId1 = "tc_snap_1"
        val toolCallId2 = "tc_snap_2"
        seedMessageWithToolCall("msg_a", toolCallId1, ToolCallStatus.IN_PROGRESS)
        seedMessageWithToolCall("msg_b", toolCallId2, ToolCallStatus.IN_PROGRESS)

        // Move toolCallId1 to Completed, leave toolCallId2 InProgress.
        manager.updateStatus(toolCallId1, ToolCallStatus.COMPLETED)

        val (partStates, pills, toolPartStatesMap) = manager.snapshot()

        // partStates is the values list from toolPartStates.
        partStates.isEmpty() shouldBe false
        // pills is the entries list from toolCallPills.
        pills.isEmpty() shouldBe false

        // Consistency: toolCallId1 should be Completed in both pills and toolPartStates.
        val pill1 = pills.firstOrNull { it.key == toolCallId1 }?.value
        pill1 shouldNotBe null
        pill1!!.status shouldBe ToolCallStatus.COMPLETED
        toolPartStatesMap[toolCallId1] shouldBe PartState.Completed

        // toolCallId2 should still be InProgress.
        val pill2 = pills.firstOrNull { it.key == toolCallId2 }?.value
        pill2 shouldNotBe null
        pill2!!.status shouldBe ToolCallStatus.IN_PROGRESS
        // toolCallId2 was seeded but never had updateStatus/setState called, so it
        // has no entry in toolPartStates (that map is only populated by
        // updateStatus/setState, not by seeding).
        toolPartStatesMap[toolCallId2] shouldBe null
    }
}