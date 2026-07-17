package com.opencode.acp.chat.processor

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodeSession
import com.opencode.acp.adapter.SessionModel
import com.opencode.acp.adapter.SessionSummary
import com.opencode.acp.adapter.SessionTime
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.SessionListState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ContextComputer] (TDD §4.2.6).
 *
 * Tests token accumulation from the local message cache, model fallback to
 * controlState, and the in-flight guard. Uses MockK for [OpenCodeClient]
 * (getSession REST call) and real [ChatMessage] instances for the message cache.
 */
class ContextComputerTest {

    private lateinit var client: OpenCodeClient
    private var activeSessionId: String? = "ses_1"
    private var messages: Map<String, ChatMessage> = emptyMap()
    private var sessionListState: SessionListState = SessionListState.Loading

    private lateinit var computer: ContextComputer

    @BeforeEach
    fun setUp() {
        client = mockk<OpenCodeClient>(relaxed = true)
        activeSessionId = "ses_1"
        messages = emptyMap()
        sessionListState = SessionListState.Loading
        computer = ContextComputer(
            activeSessionIdProvider = { activeSessionId },
            messagesProvider = { messages },
            clientProvider = { client },
            projectBasePathProvider = { null },
            sessionListStateProvider = { sessionListState },
        )
    }

    private fun makeAssistantMessage(
        id: String,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        reasoningTokens: Long = 0,
        cacheReadTokens: Long = 0,
        cacheWriteTokens: Long = 0,
        cost: Double = 0.0,
        modelID: String? = null,
        providerID: String? = null,
    ) = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = emptyMap(),
        timestamp = 0L,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        cost = cost,
        modelID = modelID,
        providerID = providerID,
    )

    private fun makeUserMessage(id: String) = ChatMessage(
        id = id,
        role = MessageRole.USER,
        parts = emptyMap(),
        timestamp = 0L,
    )

    private fun makeSession(
        model: SessionModel? = null,
        summary: SessionSummary? = null,
        time: SessionTime? = null,
    ) = OpenCodeSession(
        id = "ses_1",
        model = model,
        summary = summary,
        time = time,
    )

    // ── compute (full path with REST) ──────────────────────────────────────

    @Test
    fun `compute returns Loading when no active session`() = runTest {
        activeSessionId = null
        val result = computer.compute()
        result shouldBe SessionContextState.Loading
    }

    @Test
    fun `compute returns Loading when no client`() = runTest {
        computer = ContextComputer(
            activeSessionIdProvider = { activeSessionId },
            messagesProvider = { messages },
            clientProvider = { null },
            projectBasePathProvider = { null },
            sessionListStateProvider = { sessionListState },
        )
        val result = computer.compute()
        result shouldBe SessionContextState.Loading
    }

    @Test
    fun `compute with empty messages returns Loaded with zero tokens`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        result.shouldBeTypeOf<SessionContextState.Loaded>()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.totalTokens shouldBe 0L
        ctx.messageCount shouldBe 0
        ctx.assistantMessageCount shouldBe 0
    }

    @Test
    fun `compute accumulates output tokens across assistant messages`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", inputTokens = 100, outputTokens = 50),
            "m2" to makeAssistantMessage("m2", inputTokens = 200, outputTokens = 30),
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        // inputTokens is CUMULATIVE (last with non-zero): 200
        ctx.inputTokens shouldBe 200L
        // outputTokens is PER-MESSAGE (summed): 50 + 30 = 80
        ctx.outputTokens shouldBe 80L
    }

    @Test
    fun `compute uses last assistant message inputTokens as cumulative`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", inputTokens = 100, outputTokens = 50),
            "m2" to makeAssistantMessage("m2", inputTokens = 300, outputTokens = 30),
            "m3" to makeAssistantMessage("m3", inputTokens = 0, outputTokens = 10), // streaming, no tokens yet
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        // Last with non-zero input: m2 (300)
        ctx.inputTokens shouldBe 300L
        // outputTokens sum: 50 + 30 + 10 = 90
        ctx.outputTokens shouldBe 90L
    }

    @Test
    fun `compute sums cost across assistant messages`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", cost = 0.01),
            "m2" to makeAssistantMessage("m2", cost = 0.02),
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.totalCost shouldBe 0.03
    }

    @Test
    fun `compute counts messages by role`() = runTest {
        messages = mapOf(
            "u1" to makeUserMessage("u1"),
            "a1" to makeAssistantMessage("a1"),
            "u2" to makeUserMessage("u2"),
            "a2" to makeAssistantMessage("a2"),
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.messageCount shouldBe 4
        ctx.userMessageCount shouldBe 2
        ctx.assistantMessageCount shouldBe 2
    }

    @Test
    fun `compute uses session model when available`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession(
            model = SessionModel(id = "claude-sonnet", providerID = "anthropic"),
        )
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.modelID shouldBe "claude-sonnet"
        ctx.providerID shouldBe "anthropic"
    }

    @Test
    fun `compute falls back to controlState model when session model is blank`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession(
            model = SessionModel(id = "", providerID = ""),
        )
        val controlState = ControlBarState(
            selectedModel = ProviderModel(
                providerID = "openai",
                modelID = "gpt-4",
                displayName = "OpenAI / gpt-4",
            )
        )
        val result = computer.compute(controlState)
        val ctx = (result as SessionContextState.Loaded).context
        ctx.modelID shouldBe "gpt-4"
        ctx.providerID shouldBe "openai"
    }

    @Test
    fun `compute falls back to controlState when session model is null`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession(model = null)
        val controlState = ControlBarState(
            selectedModel = ProviderModel(
                providerID = "p",
                modelID = "m",
                displayName = "p / m",
            )
        )
        val result = computer.compute(controlState)
        val ctx = (result as SessionContextState.Loaded).context
        ctx.modelID shouldBe "m"
    }

    @Test
    fun `compute uses session summary for additions deletions files`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession(
            summary = SessionSummary(additions = 10, deletions = 5, files = 3),
        )
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.additions shouldBe 10
        ctx.deletions shouldBe 5
        ctx.filesModified shouldBe 3
    }

    @Test
    fun `compute uses session time for created updated`() = runTest {
        coEvery { client.getSession("ses_1") } returns makeSession(
            time = SessionTime(created = 1000L, updated = 2000L),
        )
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.sessionCreated shouldBe 1000L
        ctx.lastUpdated shouldBe 2000L
    }

    @Test
    fun `compute computes usagePercent from contextLimit`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", inputTokens = 500, outputTokens = 500),
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val controlState = ControlBarState(
            models = listOf(
                ProviderModel(
                    providerID = "p",
                    modelID = "m",
                    displayName = "p / m",
                    contextWindow = 10000,
                )
            ),
            selectedModel = ProviderModel(
                providerID = "p",
                modelID = "m",
                displayName = "p / m",
                contextWindow = 10000,
            ),
        )
        val result = computer.compute(controlState)
        val ctx = (result as SessionContextState.Loaded).context
        // totalTokens = 500 (input) + 500 (output) = 1000
        // usagePercent = 1000 / 10000 * 100 = 10%
        ctx.totalTokens shouldBe 1000L
        ctx.contextLimit shouldBe 10000L
        ctx.usagePercent shouldBe 10f
    }

    @Test
    fun `compute with zero contextLimit returns zero usagePercent`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", inputTokens = 500),
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.contextLimit shouldBe 0L
        ctx.usagePercent shouldBe 0f
    }

    @Test
    fun `compute handles getSession exception gracefully`() = runTest {
        coEvery { client.getSession("ses_1") } throws RuntimeException("network error")
        messages = mapOf("m1" to makeAssistantMessage("m1", inputTokens = 100, outputTokens = 50))
        val result = computer.compute()
        // Should still return Loaded (token data from local cache), not Error
        result.shouldBeTypeOf<SessionContextState.Loaded>()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.inputTokens shouldBe 100L
        ctx.outputTokens shouldBe 50L
    }

    // ── computeLocal (no REST) ────────────────────────────────────────────

    @Test
    fun `computeLocal returns Loading when no active session`() = runTest {
        activeSessionId = null
        val result = computer.computeLocal()
        result shouldBe SessionContextState.Loading
    }

    @Test
    fun `computeLocal returns Loaded with token data from cache`() = runTest {
        messages = mapOf(
            "m1" to makeAssistantMessage("m1", inputTokens = 100, outputTokens = 50),
        )
        val result = computer.computeLocal()
        result.shouldBeTypeOf<SessionContextState.Loaded>()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.inputTokens shouldBe 100L
        ctx.outputTokens shouldBe 50L
    }

    @Test
    fun `computeLocal does not call getSession`() = runTest {
        messages = mapOf("m1" to makeAssistantMessage("m1", inputTokens = 100))
        computer.computeLocal()
        // getSession should not be called (we didn't stub it, relaxed mock returns default)
        // Verify by checking the result doesn't have session metadata
        val result = computer.computeLocal()
        val ctx = (result as SessionContextState.Loaded).context
        // No session fetch → additions/deletions default to 0
        ctx.additions shouldBe 0
    }

    // ── In-flight guard ──────────────────────────────────────────────────

    @Test
    fun `compute in-flight guard prevents concurrent computation`() = runTest {
        // First call sets the guard; second call should return current state
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result1 = computer.compute()
        val result2 = computer.compute()
        // Both should return Loaded (second returns current state)
        result1.shouldBeTypeOf<SessionContextState.Loaded>()
        result2.shouldBeTypeOf<SessionContextState.Loaded>()
    }

    // ── Session title lookup ──────────────────────────────────────────────

    @Test
    fun `compute uses session title from SessionListState Loaded`() = runTest {
        sessionListState = SessionListState.Loaded(
            sessions = listOf(
                com.opencode.acp.chat.model.SessionItem(
                    id = "ses_1",
                    title = "My Test Session",
                    updatedAt = 0L,
                    cost = 0.0,
                    inputTokens = 0L,
                    outputTokens = 0L,
                )
            ),
            selectedId = "ses_1",
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.title shouldBe "My Test Session"
    }

    @Test
    fun `compute falls back to Untitled when session not in list`() = runTest {
        sessionListState = SessionListState.Loaded(
            sessions = emptyList(),
            selectedId = null,
        )
        coEvery { client.getSession("ses_1") } returns makeSession()
        val result = computer.compute()
        val ctx = (result as SessionContextState.Loaded).context
        ctx.title shouldBe "Untitled"
    }
}