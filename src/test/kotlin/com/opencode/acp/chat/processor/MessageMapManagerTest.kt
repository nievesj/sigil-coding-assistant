package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MessageMapManager] (TDD §7.1.1).
 *
 * No IntelliJ Platform dependencies — [MessageMapManager] is a pure Kotlin class
 * that operates on [ToolCallState], [ReentrantLock], and a [MutableStateFlow].
 */
class MessageMapManagerTest {

    private lateinit var toolCallState: ToolCallState
    private lateinit var stateLock: ReentrantLock
    private lateinit var messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>
    private lateinit var messageMap: MessageMapManager

    private val logger = KotlinLogging.logger {}

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
    }

    private fun makeMessage(
        id: String,
        serverMessageId: String? = null,
        toolCallParts: List<Pair<String, MessagePart.ToolCall>> = emptyList(),
    ): ChatMessage {
        val parts = linkedMapOf<String, MessagePart>()
        toolCallParts.forEach { (key, part) -> parts[key] = part }
        return ChatMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = parts,
            timestamp = System.currentTimeMillis(),
            serverMessageId = serverMessageId,
        )
    }

    private fun makeToolCallPart(toolCallId: String): MessagePart.ToolCall {
        val pill = ToolCallPill(
            toolCallId = toolCallId,
            toolName = "read",
            title = "read",
            kind = ToolKind.READ,
            status = ToolCallStatus.IN_PROGRESS,
        )
        return MessagePart.ToolCall(pill = pill, state = PartState.InProgress)
    }

    @Test
    fun `add 501 messages - oldest evicted, tool index cleaned`() {
        // Add a first message with a ToolCall part so we can verify its index entry
        // is cleaned when it gets evicted.
        val firstToolCallId = "tc_first"
        val firstMsg = makeMessage(
            id = "msg_0",
            toolCallParts = listOf(firstToolCallId to makeToolCallPart(firstToolCallId)),
        )
        messageMap.add(firstMsg)

        // Add 500 more plain messages (total 501 → 1 evicted, the first one).
        for (i in 1..500) {
            messageMap.add(makeMessage(id = "msg_$i"))
        }

        // The map should be capped at MAX_MESSAGE_HISTORY.
        messages.value.size shouldBe ChatConstants.MAX_MESSAGE_HISTORY

        // The first message (msg_0) should have been evicted.
        messages.value.containsKey("msg_0") shouldBe false

        // Its tool call index entry should have been cleaned up.
        toolCallState.toolCallIndex.containsKey(firstToolCallId) shouldBe false
        toolCallState.toolCallPills.containsKey(firstToolCallId) shouldBe false
        toolCallState.toolPartStates.containsKey(firstToolCallId) shouldBe false
    }

    @Test
    fun `add message with ToolCall parts - toolCallIndex updated`() {
        val toolCallId = "tc_1"
        val part = makeToolCallPart(toolCallId)
        val msg = makeMessage(
            id = "msg_1",
            toolCallParts = listOf(toolCallId to part),
        )

        messageMap.add(msg)

        toolCallState.toolCallIndex[toolCallId] shouldBe "msg_1"
    }

    @Test
    fun `removeByServerId - message removed, tool index cleaned`() {
        val toolCallId = "tc_remove"
        val part = makeToolCallPart(toolCallId)
        val msg = makeMessage(
            id = "msg_local_1",
            serverMessageId = "srv_1",
            toolCallParts = listOf(toolCallId to part),
        )
        messageMap.add(msg)

        // Precondition: the index has the entry.
        toolCallState.toolCallIndex[toolCallId] shouldBe "msg_local_1"

        messageMap.removeByServerId("srv_1")

        messages.value.containsKey("msg_local_1") shouldBe false
        toolCallState.toolCallIndex.containsKey(toolCallId) shouldBe false
        toolCallState.toolCallPills.containsKey(toolCallId) shouldBe false
        toolCallState.toolPartStates.containsKey(toolCallId) shouldBe false
    }

    @Test
    fun `replaceAll - tool index rebuilt from new messages`() {
        // Seed with an old message + tool call that should be cleared.
        val oldToolCallId = "tc_old"
        messageMap.add(
            makeMessage(
                id = "msg_old",
                toolCallParts = listOf(oldToolCallId to makeToolCallPart(oldToolCallId)),
            )
        )
        toolCallState.toolCallIndex[oldToolCallId] shouldBe "msg_old"

        // New set with a different tool call.
        val newToolCallId = "tc_new"
        val newPart = makeToolCallPart(newToolCallId)
        val newMsg = makeMessage(
            id = "msg_new",
            toolCallParts = listOf(newToolCallId to newPart),
        )

        messageMap.replaceAll(listOf(newMsg))

        messages.value.size shouldBe 1
        messages.value.containsKey("msg_new") shouldBe true
        // Old index entry cleared.
        toolCallState.toolCallIndex.containsKey(oldToolCallId) shouldBe false
        // New index entry built.
        toolCallState.toolCallIndex[newToolCallId] shouldBe "msg_new"
        toolCallState.toolCallPills[newToolCallId] shouldNotBe null
    }

    @Test
    fun `update reentrant - call from within stateLock_withLock - no deadlock`() {
        // Add a message first.
        val msgId = "msg_reentrant"
        messageMap.add(makeMessage(id = msgId))

        // Now call update from within stateLock.withLock — this would deadlock
        // if update used a non-reentrant lock.
        stateLock.withLock {
            messageMap.update(msgId) { msg ->
                msg.copy(parts = linkedMapOf("text_0_0" to com.opencode.acp.chat.model.MessagePart.Text("updated")))
            }
        }

        // If we get here, no deadlock occurred. Verify the update applied.
        val updated = messages.value[msgId]
        updated shouldNotBe null
        val textPart = updated!!.parts.values.firstOrNull { it is com.opencode.acp.chat.model.MessagePart.Text } as? com.opencode.acp.chat.model.MessagePart.Text
        textPart shouldNotBe null
        textPart!!.content shouldBe "updated"
    }

    @Test
    fun `add after closed - message dropped (isClosed guard)`() {
        val closedManager = MessageMapManager(
            toolCallState = toolCallState,
            stateLock = stateLock,
            messages = messages,
            isClosed = { true },
            logger = logger,
        )

        closedManager.add(makeMessage(id = "msg_dropped"))

        messages.value.size shouldBe 0
        messages.value.containsKey("msg_dropped") shouldBe false
    }
}