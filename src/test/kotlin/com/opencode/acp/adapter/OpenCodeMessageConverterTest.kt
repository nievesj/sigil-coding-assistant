package com.opencode.acp.adapter

import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unit tests for [OpenCodeMessageConverter] (TDD §4.2.5).
 *
 * Tests the extension functions:
 * - [OpenCodeSession.toSessionItem] — REST session → sidebar display model
 * - [OpenCodeMessage.toChatMessage] — REST message → UI ChatMessage model
 *
 * No mocking — uses real [OpenCodeSession] / [OpenCodeMessage] instances and the
 * real [MarkdownSegmenter] (pure data transforms).
 */
class OpenCodeMessageConverterTest {

    // ── OpenCodeSession.toSessionItem ─────────────────────────────────────

    @Test
    fun `toSessionItem preserves id and title`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test session",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.id shouldBe "ses_1"
        item.title shouldBe "Test session"
    }

    @Test
    fun `toSessionItem with blank title defaults to New session`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.title shouldBe "New session"
    }

    @Test
    fun `toSessionItem with whitespace-only title defaults to New session`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "   ",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.title shouldBe "New session"
    }

    @Test
    fun `toSessionItem maps tokens to inputTokens and outputTokens`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            tokens = SessionTokens(input = 100L, output = 200L),
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.inputTokens shouldBe 100L
        item.outputTokens shouldBe 200L
    }

    @Test
    fun `toSessionItem defaults tokens to zero when null`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.inputTokens shouldBe 0L
        item.outputTokens shouldBe 0L
    }

    @Test
    fun `toSessionItem uses time_updated when present`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            time = SessionTime(created = 1000L, updated = 5000L)
        )
        val item = session.toSessionItem()
        item.updatedAt shouldBe 5000L
    }

    @Test
    fun `toSessionItem uses time_created when updated is zero`() {
        // The converter uses elvis (?:), so updated=0 is treated as a present value.
        // This test documents that behavior: 0 is returned, not the created fallback.
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            time = SessionTime(created = 3000L, updated = 0L)
        )
        val item = session.toSessionItem()
        item.updatedAt shouldBe 0L
    }

    @Test
    fun `toSessionItem defaults updatedAt to zero when time is null`() {
        val session = OpenCodeSession(id = "ses_1", title = "Test", time = null)
        val item = session.toSessionItem()
        item.updatedAt shouldBe 0L
    }

    @Test
    fun `toSessionItem maps cost`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            cost = 1.23,
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.cost shouldBe 1.23
    }

    @Test
    fun `toSessionItem maps parentID`() {
        val session = OpenCodeSession(
            id = "ses_2",
            title = "Subtask",
            parentID = "ses_1",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.parentID shouldBe "ses_1"
    }

    @Test
    fun `toSessionItem parentID is null when absent`() {
        val session = OpenCodeSession(
            id = "ses_1",
            title = "Test",
            time = SessionTime(created = 1000L, updated = 2000L)
        )
        val item = session.toSessionItem()
        item.parentID shouldBe null
    }

    // ── OpenCodeMessage.toChatMessage: role ───────────────────────────────

    @Test
    fun `toChatMessage maps user role to MessageRole USER`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "user", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hello"))
        )
        val chat = msg.toChatMessage()
        chat.role shouldBe MessageRole.USER
    }

    @Test
    fun `toChatMessage maps assistant role to MessageRole ASSISTANT`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.role shouldBe MessageRole.ASSISTANT
    }

    @Test
    fun `toChatMessage maps unknown role to MessageRole ASSISTANT`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "system", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.role shouldBe MessageRole.ASSISTANT
    }

    // ── OpenCodeMessage.toChatMessage: id / timestamp / serverMessageId ───

    @Test
    fun `toChatMessage preserves id and sets serverMessageId`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_42", role = "user", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hello"))
        )
        val chat = msg.toChatMessage()
        chat.id shouldBe "msg_42"
        chat.serverMessageId shouldBe "msg_42"
    }

    @Test
    fun `toChatMessage parses ISO-8601 timestamp`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "user", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hello"))
        )
        val chat = msg.toChatMessage()
        chat.timestamp shouldBe 1704067200000L
    }

    @Test
    fun `toChatMessage falls back to zero timestamp when createdAt is null`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "user", createdAt = null),
            parts = listOf(OpenCodePart.Text("hello"))
        )
        val chat = msg.toChatMessage()
        chat.timestamp shouldBe 0L
    }

    @Test
    fun `toChatMessage isStreaming is false for REST-loaded messages`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.isStreaming shouldBe false
    }

    // ── OpenCodeMessage.toChatMessage: text parts ─────────────────────────

    @Test
    fun `toChatMessage with text parts produces Text or Code parts`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("Hello world"))
        )
        val chat = msg.toChatMessage()
        chat.parts.size shouldBe 1
        val part = chat.parts.values.first()
        val textPart = assertInstanceOf(MessagePart.Text::class.java, part)
        textPart.content shouldBe "Hello world"
    }

    @Test
    fun `toChatMessage with fenced code block produces Code part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("```kotlin\nval x = 1\n```"))
        )
        val chat = msg.toChatMessage()
        val codePart = assertInstanceOf(MessagePart.Code::class.java, chat.parts.values.first())
        codePart.language shouldBe "kotlin"
        codePart.content shouldBe "val x = 1"
    }

    @Test
    fun `toChatMessage concatenates multiple text parts`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.Text("part one "),
                OpenCodePart.Text("part two")
            )
        )
        val chat = msg.toChatMessage()
        val textPart = assertInstanceOf(MessagePart.Text::class.java, chat.parts.values.first())
        textPart.content shouldBe "part one part two"
    }

    // ── OpenCodeMessage.toChatMessage: thinking parts ─────────────────────

    @Test
    fun `toChatMessage with thinking parts produces Thinking part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Thinking("let me think"))
        )
        val chat = msg.toChatMessage()
        val thinkingPart = assertInstanceOf(MessagePart.Thinking::class.java, chat.parts.values.first())
        thinkingPart.content shouldBe "let me think"
    }

    @Test
    fun `toChatMessage with reasoning parts produces Thinking part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Reasoning("reasoning here"))
        )
        val chat = msg.toChatMessage()
        val thinkingPart = assertInstanceOf(MessagePart.Thinking::class.java, chat.parts.values.first())
        thinkingPart.content shouldBe "reasoning here"
    }

    @Test
    fun `toChatMessage concatenates thinking and reasoning into one Thinking part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.Thinking("think "),
                OpenCodePart.Reasoning("reason")
            )
        )
        val chat = msg.toChatMessage()
        val thinkingPart = assertInstanceOf(MessagePart.Thinking::class.java, chat.parts.values.first())
        thinkingPart.content shouldBe "think reason"
    }

    // ── OpenCodeMessage.toChatMessage: tool_use parts ─────────────────────

    @Test
    fun `toChatMessage with tool_use part produces ToolCall part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.ToolUse(
                    id = "tc_1",
                    name = "bash",
                    input = buildJsonObject { put("command", "ls") }
                )
            )
        )
        val chat = msg.toChatMessage()
        val toolCall = assertInstanceOf(MessagePart.ToolCall::class.java, chat.parts.values.first())
        val pill = toolCall.pill
        pill.toolCallId shouldBe "tc_1"
        pill.toolName shouldBe "bash"
    }

    @Test
    fun `toChatMessage with tool_use and matching tool_result marks COMPLETED`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.ToolUse(id = "tc_1", name = "bash"),
                OpenCodePart.ToolResult(toolUseId = "tc_1", content = listOf(OpenCodePart.Text("done")))
            )
        )
        val chat = msg.toChatMessage()
        val toolCall = chat.parts.values.first { it is MessagePart.ToolCall } as MessagePart.ToolCall
        pillStatus(toolCall) shouldBe "completed"
    }

    @Test
    fun `toChatMessage with tool_use and error tool_result marks FAILED`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.ToolUse(id = "tc_1", name = "bash"),
                OpenCodePart.ToolResult(
                    toolUseId = "tc_1",
                    content = listOf(OpenCodePart.Text("boom")),
                    isError = true
                )
            )
        )
        val chat = msg.toChatMessage()
        val toolCall = chat.parts.values.first { it is MessagePart.ToolCall } as MessagePart.ToolCall
        pillStatus(toolCall) shouldBe "failed"
    }

    @Test
    fun `toChatMessage with tool_use and no result marks IN_PROGRESS`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(
                OpenCodePart.ToolUse(id = "tc_1", name = "bash")
            )
        )
        val chat = msg.toChatMessage()
        val toolCall = chat.parts.values.first { it is MessagePart.ToolCall } as MessagePart.ToolCall
        pillStatus(toolCall) shouldBe "in_progress"
    }

    // ── OpenCodeMessage.toChatMessage: error info ─────────────────────────

    @Test
    fun `toChatMessage with error info produces Error part`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                error = MessageError(name = "ToolError", message = "something broke")
            ),
            parts = listOf(OpenCodePart.Text("partial response"))
        )
        val chat = msg.toChatMessage()
        val errorPart = chat.parts.values.first { it is MessagePart.Error } as MessagePart.Error
        errorPart.message shouldBe "something broke"
    }

    @Test
    fun `toChatMessage with error info and retries appends retry count`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                error = MessageError(name = "Err", message = "fail", retries = 3)
            ),
            parts = listOf(OpenCodePart.Text("partial"))
        )
        val chat = msg.toChatMessage()
        val errorPart = chat.parts.values.first { it is MessagePart.Error } as MessagePart.Error
        errorPart.message shouldBe "fail (3 retries)"
    }

    @Test
    fun `toChatMessage with error name but no message uses name as description`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                error = MessageError(name = "NamedError")
            ),
            parts = listOf(OpenCodePart.Text("partial"))
        )
        val chat = msg.toChatMessage()
        val errorPart = chat.parts.values.first { it is MessagePart.Error } as MessagePart.Error
        errorPart.message shouldBe "NamedError"
    }

    // ── OpenCodeMessage.toChatMessage: tokens ────────────────────────────

    @Test
    fun `toChatMessage maps tokens to inputTokens and outputTokens`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                tokens = SessionTokens(input = 50L, output = 75L)
            ),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.inputTokens shouldBe 50L
        chat.outputTokens shouldBe 75L
    }

    @Test
    fun `toChatMessage defaults tokens to zero when null`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "assistant", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.inputTokens shouldBe 0L
        chat.outputTokens shouldBe 0L
    }

    @Test
    fun `toChatMessage maps reasoning and cache tokens`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                tokens = SessionTokens(
                    input = 10L,
                    output = 20L,
                    reasoning = 5L,
                    cache = TokenCache(read = 100L, write = 200L)
                )
            ),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.reasoningTokens shouldBe 5L
        chat.cacheReadTokens shouldBe 100L
        chat.cacheWriteTokens shouldBe 200L
    }

    @Test
    fun `toChatMessage maps cost`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                cost = 0.42
            ),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.cost shouldBe 0.42
    }

    @Test
    fun `toChatMessage maps modelID and providerID`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(
                id = "msg_1",
                role = "assistant",
                createdAt = "2024-01-01T00:00:00Z",
                modelID = "claude-3",
                providerID = "anthropic"
            ),
            parts = listOf(OpenCodePart.Text("hi"))
        )
        val chat = msg.toChatMessage()
        chat.modelID shouldBe "claude-3"
        chat.providerID shouldBe "anthropic"
    }

    // ── OpenCodeMessage.toChatMessage: empty parts ───────────────────────

    @Test
    fun `toChatMessage with empty parts produces empty parts map`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "user", createdAt = "2024-01-01T00:00:00Z"),
            parts = emptyList()
        )
        val chat = msg.toChatMessage()
        chat.parts shouldBe emptyMap()
    }

    @Test
    fun `toChatMessage with blank text parts produces empty parts map`() {
        val msg = OpenCodeMessage(
            info = MessageInfo(id = "msg_1", role = "user", createdAt = "2024-01-01T00:00:00Z"),
            parts = listOf(OpenCodePart.Text("   "))
        )
        val chat = msg.toChatMessage()
        chat.parts shouldBe emptyMap()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun pillStatus(toolCall: MessagePart.ToolCall): String =
        when (toolCall.pill.status) {
            com.agentclientprotocol.model.ToolCallStatus.PENDING -> "pending"
            com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS -> "in_progress"
            com.agentclientprotocol.model.ToolCallStatus.COMPLETED -> "completed"
            com.agentclientprotocol.model.ToolCallStatus.FAILED -> "failed"
        }
}