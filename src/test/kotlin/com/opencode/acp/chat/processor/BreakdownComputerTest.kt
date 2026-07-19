package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.ToolCallPill
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the real [BreakdownComputer] object.
 *
 * Tests the 5-category token breakdown math (System + Tool Definitions, User,
 * Assistant, Tool Calls, Other) and the char-to-token ratio calibration (EMA).
 *
 * The object holds `@Volatile` mutable calibration state, so we call
 * [BreakdownComputer.resetCalibration] in @BeforeEach to start each test from
 * the default ratio (CompactionConstants.DEFAULT_CHARS_PER_TOKEN = 4.0).
 *
 * No fakes — the real object is exercised directly with real ChatMessage and
 * MessagePart instances.
 */
class BreakdownComputerTest {

    @BeforeEach
    fun resetCalibration() {
        BreakdownComputer.resetCalibration()
    }

    private fun userMessage(id: String, text: String): ChatMessage = ChatMessage(
        id = id,
        role = MessageRole.USER,
        parts = mapOf("p_$id" to MessagePart.Text(content = text)),
        timestamp = 0L,
    )

    private fun assistantMessage(
        id: String,
        text: String,
        inputTokens: Long = 0L,
        reasoningTokens: Long = 0L,
        cacheReadTokens: Long = 0L,
        cacheWriteTokens: Long = 0L,
    ): ChatMessage = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = mapOf("p_$id" to MessagePart.Text(content = text)),
        timestamp = 0L,
        inputTokens = inputTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
    )

    private fun assistantWithToolCall(
        id: String,
        text: String,
        toolName: String,
        toolInput: kotlinx.serialization.json.JsonObject,
        toolOutput: List<kotlinx.serialization.json.JsonObject>,
    ): ChatMessage = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = mapOf(
            "p_${id}_text" to MessagePart.Text(content = text),
            "p_${id}_tool" to MessagePart.ToolCall(
                pill = ToolCallPill(
                    toolCallId = "tc_$id",
                    toolName = toolName,
                    title = toolName,
                    kind = ToolKind.READ,
                    status = ToolCallStatus.COMPLETED,
                    input = toolInput,
                    output = toolOutput,
                    startTimeMs = 0L,
                ),
            ),
        ),
        timestamp = 0L,
    )

    // ── Calibration ─────────────────────────────────────────────────────────

    @Test
    fun `resetCalibration restores default chars-per-token ratio`() {
        // Calibrate to a different ratio, then reset and verify the breakdown
        // uses the default ratio again.
        BreakdownComputer.calibrate(estimatedChars = 1000L, actualTokens = 200L) // observed = 5.0
        // After reset, ratio should be back to default (4.0).
        BreakdownComputer.resetCalibration()
        val messages = mapOf("u1" to userMessage("u1", "abcd")) // 4 chars / 4.0 = 1 token
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.userTokens shouldBe 1L
    }

    @Test
    fun `calibrate applies EMA to chars-per-token ratio`() {
        // Default ratio = 4.0. Calibrate with observed = 8.0 (1000 chars / 125 tokens).
        // EMA: 0.8 * 4.0 + 0.2 * 8.0 = 3.2 + 1.6 = 4.8
        BreakdownComputer.calibrate(estimatedChars = 1000L, actualTokens = 125L)
        val messages = mapOf("u1" to userMessage("u1", "abcd")) // 4 chars / 4.8 = 0 (toLong truncates)
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        // 4 / 4.8 = 0.833 → toLong = 0
        breakdown.userTokens shouldBe 0L
    }

    @Test
    fun `calibrate ignores zero actualTokens`() {
        // Calibrate with 0 actualTokens → no-op. Ratio stays at default 4.0.
        BreakdownComputer.calibrate(estimatedChars = 1000L, actualTokens = 0L)
        val messages = mapOf("u1" to userMessage("u1", "abcd")) // 4 / 4.0 = 1
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.userTokens shouldBe 1L
    }

    @Test
    fun `calibrate ignores zero estimatedChars`() {
        BreakdownComputer.calibrate(estimatedChars = 0L, actualTokens = 100L)
        val messages = mapOf("u1" to userMessage("u1", "abcd"))
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.userTokens shouldBe 1L
    }

    // ── 5-category breakdown math ──────────────────────────────────────────

    @Test
    fun `user tokens are estimated from user message text chars`() {
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"),       // 4 chars / 4 = 1 token
            "u2" to userMessage("u2", "abcdefgh"),   // 8 chars / 4 = 2 tokens
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.userTokens shouldBe 3L
    }

    @Test
    fun `assistant tokens are estimated from assistant text chars only`() {
        val messages = mapOf(
            "a1" to assistantMessage("a1", "abcd", inputTokens = 0L),       // 4 / 4 = 1
            "a2" to assistantMessage("a2", "abcdefgh", inputTokens = 0L),   // 8 / 4 = 2
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.assistantTokens shouldBe 3L
    }

    @Test
    fun `system prompt tokens estimated from first assistant inputTokens minus prior user tokens`() {
        // First assistant inputTokens = full prompt = system + prior user.
        // prior user = "abcd" = 4 chars / 4 = 1 token.
        // system = inputTokens - priorUserTokens = 100 - 1 = 99.
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"),
            "a1" to assistantMessage("a1", "hello", inputTokens = 100L),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.systemPromptTokens shouldBe 99L
    }

    @Test
    fun `system prompt tokens are zero when no assistant message has inputTokens`() {
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"),
            "a1" to assistantMessage("a1", "hello", inputTokens = 0L),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.systemPromptTokens shouldBe 0L
    }

    @Test
    fun `tool tokens are estimated from tool input and output JSON byte sizes`() {
        val toolInput = buildJsonObject { put("path", "/foo.txt") }
        val toolOutput = listOf(buildJsonObject { put("text", "hello world") })
        val messages = mapOf(
            "a1" to assistantWithToolCall(
                id = "a1",
                text = "",
                toolName = "read",
                toolInput = toolInput,
                toolOutput = toolOutput,
            ),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.toolTokens shouldNotBe 0L
        // The tool breakdown map should contain an entry for "read".
        breakdown.toolBreakdown["read"] shouldNotBe null
        breakdown.toolBreakdown["read"]!!.callCount shouldBe 1
        breakdown.toolBreakdown["read"]!!.toolName shouldBe "read"
    }

    @Test
    fun `tool breakdown aggregates multiple calls to the same tool`() {
        val toolInput = buildJsonObject { put("path", "/foo.txt") }
        val toolOutput = listOf(buildJsonObject { put("text", "hello") })
        val messages = mapOf(
            "a1" to assistantWithToolCall("a1", "", "read", toolInput, toolOutput),
            "a2" to assistantWithToolCall("a2", "", "read", toolInput, toolOutput),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.toolBreakdown["read"]!!.callCount shouldBe 2
    }

    @Test
    fun `other tokens include reasoning cache read and cache write`() {
        val messages = mapOf(
            "a1" to assistantMessage(
                "a1",
                "hello",
                inputTokens = 0L,
                reasoningTokens = 50L,
                cacheReadTokens = 100L,
                cacheWriteTokens = 25L,
            ),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        // other = reasoning + cacheRead + cacheWrite = 50 + 100 + 25 = 175
        breakdown.otherTokens shouldBe 175L
        breakdown.otherBreakdown["Reasoning"]!!.estimatedTokens shouldBe 50L
        breakdown.otherBreakdown["Cache Read"]!!.estimatedTokens shouldBe 100L
        breakdown.otherBreakdown["Cache Write"]!!.estimatedTokens shouldBe 25L
    }

    @Test
    fun `cacheReadTokens uses max across assistant messages - cumulative assumption`() {
        val messages = mapOf(
            "a1" to assistantMessage("a1", "x", cacheReadTokens = 100L),
            "a2" to assistantMessage("a2", "y", cacheReadTokens = 300L),
            "a3" to assistantMessage("a3", "z", cacheReadTokens = 200L),
        )
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        // max(100, 300, 200) = 300
        breakdown.otherBreakdown["Cache Read"]!!.estimatedTokens shouldBe 300L
    }

    @Test
    fun `freeTokens is contextLimit minus total`() {
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"), // 1 token
        )
        val contextLimit = 100_000L
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = contextLimit)
        breakdown.totalTokens shouldBe 1L
        breakdown.freeTokens shouldBe (contextLimit - 1L)
    }

    @Test
    fun `normalization scales categories to sessionTotalTokens`() {
        // Without normalization, raw total would be small. With sessionTotalTokens,
        // categories are scaled so the breakdown total matches the server count.
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"), // 1 token (raw)
        )
        val sessionTotalTokens = 1000L
        val breakdown = BreakdownComputer.computeBreakdown(
            messages = messages,
            contextLimit = 100_000L,
            sessionTotalTokens = sessionTotalTokens,
        )
        breakdown.totalTokens shouldBe sessionTotalTokens
        // userTokens scaled: 1 * (1000 / 1) = 1000
        breakdown.userTokens shouldBe 1000L
    }

    @Test
    fun `normalization is skipped when sessionTotalTokens is zero`() {
        val messages = mapOf(
            "u1" to userMessage("u1", "abcd"),
        )
        val breakdown = BreakdownComputer.computeBreakdown(
            messages = messages,
            contextLimit = 100_000L,
            sessionTotalTokens = 0L,
        )
        breakdown.totalTokens shouldBe 1L
        breakdown.userTokens shouldBe 1L
    }

    @Test
    fun `empty messages produce zero breakdown`() {
        val breakdown = BreakdownComputer.computeBreakdown(emptyMap(), contextLimit = 100_000L)
        breakdown.systemPromptTokens shouldBe 0L
        breakdown.userTokens shouldBe 0L
        breakdown.assistantTokens shouldBe 0L
        breakdown.toolTokens shouldBe 0L
        breakdown.otherTokens shouldBe 0L
        breakdown.totalTokens shouldBe 0L
        breakdown.freeTokens shouldBe 100_000L
    }

    @Test
    fun `default chars-per-token is CompactionConstants DEFAULT_CHARS_PER_TOKEN`() {
        // Indirectly verify: 4 chars → 1 token at default ratio.
        val messages = mapOf("u1" to userMessage("u1", "abcd"))
        val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit = 100_000L)
        breakdown.userTokens shouldBe (4.0 / CompactionConstants.DEFAULT_CHARS_PER_TOKEN).toLong()
        breakdown.userTokens shouldBe 1L
    }
}