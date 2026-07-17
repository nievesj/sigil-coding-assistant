package com.opencode.acp.chat.util

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageId
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.ModelIdentity
import com.opencode.acp.chat.model.SessionId
import java.util.UUID

/**
 * Factory functions for test data. Standardizes test object creation across
 * all test files.
 */
object TestFixtures {
    fun makeSessionId() = SessionId("ses_test_${UUID.randomUUID()}")
    fun makeMessageId() = MessageId("msg_test_${UUID.randomUUID()}")
    fun makeModelIdentity() = ModelIdentity("test-provider", "test-model")

    fun makeMessage(
        id: String = makeMessageId().value,
        role: MessageRole = MessageRole.ASSISTANT,
        parts: Map<String, MessagePart> = emptyMap(),
        timestamp: Long = 0L,
        isStreaming: Boolean = false,
    ) = ChatMessage(
        id = id,
        role = role,
        parts = parts,
        timestamp = timestamp,
        isStreaming = isStreaming,
    )
}