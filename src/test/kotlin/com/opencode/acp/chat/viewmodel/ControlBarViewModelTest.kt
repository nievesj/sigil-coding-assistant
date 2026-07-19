package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.AgentInfo
import com.opencode.acp.adapter.ModelData
import com.opencode.acp.adapter.ProviderData
import com.opencode.acp.adapter.ProviderResponse
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ControlBarViewModel] (TDD `docs/tdd/ui-testability-refactor.md` §9 step 6, Phase 3).
 *
 * Tests agent/model/thinking-effort selection state and the agent/provider
 * loading logic extracted from [ChatViewModel.initialize]. Uses MockK to mock
 * [OpenCodeServiceApi] and a mock [OpenCodeSettingsState] (via mockkObject on
 * the companion `getInstance()`).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ControlBarViewModelTest {

    private lateinit var service: OpenCodeServiceApi
    private lateinit var settings: OpenCodeSettingsState
    private lateinit var viewModel: ControlBarViewModel

    @BeforeEach
    fun setUp() {
        service = mockk<OpenCodeServiceApi>(relaxed = true)
        settings = mockk<OpenCodeSettingsState>(relaxed = true)
        every { settings.lastSelectedModelKey } returns ""
        every { settings.lastSelectedAgent } returns ""
        every { settings.lastSelectedThinkingEffort } returns ""

        // Mock OpenCodeSettingsState.getInstance() — it's a companion object method.
        // mockkObject replaces the companion; stub getInstance to return our mock
        // settings, and stub modelKey to preserve the real "$provider/$model" format
        // (ControlBarViewModel calls OpenCodeSettingsState.modelKey(...) internally).
        mockkObject(OpenCodeSettingsState.Companion)
        every { OpenCodeSettingsState.getInstance() } returns settings
        every { OpenCodeSettingsState.modelKey(any(), any()) } answers {
            firstArg<String>() + "/" + secondArg<String>()
        }

        viewModel = ControlBarViewModel(service = service, settings = settings)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OpenCodeSettingsState.Companion)
    }

    private fun makeAgent(name: String, mode: String? = null, hidden: Boolean? = null): AgentInfo =
        AgentInfo(name = name, description = "desc-$name", mode = mode, hidden = hidden)

    private fun makeProvider(
        id: String,
        name: String,
        models: Map<String, ModelData> = emptyMap(),
    ): ProviderData = ProviderData(id = id, name = name, models = models)

    private fun makeModelData(id: String, name: String, reasoning: Boolean = false): ModelData =
        ModelData(id = id, name = name, reasoning = reasoning)

    // ── selectAgent / selectModel / selectThinkingEffort ───────────────────

    @Test
    fun `selectAgent updates controlState selectedAgent and persists to settings`() {
        val agent = OpenCodeAgentInfo(id = "orchestrator", name = "orchestrator")
        viewModel.selectAgent(agent)
        viewModel.controlState.value.selectedAgent shouldBe agent
        verify { settings.lastSelectedAgent = "orchestrator" }
    }

    @Test
    fun `selectModel updates controlState selectedModel and persists to settings`() {
        val model = ProviderModel(
            providerID = "anthropic",
            modelID = "claude-sonnet",
            displayName = "Anthropic / Claude Sonnet",
        )
        viewModel.selectModel(model)
        viewModel.controlState.value.selectedModel shouldBe model
        verify { settings.lastSelectedModelKey = "anthropic/claude-sonnet" }
    }

    @Test
    fun `selectThinkingEffort updates controlState thinkingEffort and persists to settings`() {
        viewModel.selectThinkingEffort(ThinkingEffort.HIGH)
        viewModel.controlState.value.thinkingEffort shouldBe ThinkingEffort.HIGH
        verify { settings.lastSelectedThinkingEffort = "HIGH" }
    }

    // ── loadAgentsAndProviders — agent loading ─────────────────────────────

    @Test
    fun `loadAgentsAndProviders with agent timeout keeps defaults`() = runTest {
        // listAgents suspends longer than the 30s timeout — withTimeoutOrNull returns null.
        coEvery { service.listAgents() } coAnswers { kotlinx.coroutines.delay(60_000L); emptyList() }
        coEvery { service.listProviders() } returns null

        viewModel.loadAgentsAndProviders()
        // runTest auto-advances virtual time for delays inside suspend functions.
        // The 30s withTimeoutOrNull fires before the 60s delay completes.

        viewModel.controlState.value.agents shouldBe emptyList()
        viewModel.controlState.value.selectedAgent shouldBe null
    }

    @Test
    fun `loadAgentsAndProviders populates agents from service`() = runTest {
        coEvery { service.listAgents() } returns listOf(
            makeAgent("orchestrator"),
            makeAgent("fixer", mode = "subagent"),   // filtered out
            makeAgent("hidden-one", hidden = true),  // filtered out
            makeAgent("builder"),
        )
        coEvery { service.listProviders() } returns null

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.agents.map { it.id } shouldBe listOf("orchestrator", "builder")
    }

    @Test
    fun `loadAgentsAndProviders selects orchestrator as default agent`() = runTest {
        coEvery { service.listAgents() } returns listOf(
            makeAgent("builder"),
            makeAgent("orchestrator"),
        )
        coEvery { service.listProviders() } returns null

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.selectedAgent?.id shouldBe "orchestrator"
    }

    @Test
    fun `loadAgentsAndProviders falls back to first agent when orchestrator absent`() = runTest {
        coEvery { service.listAgents() } returns listOf(
            makeAgent("builder"),
            makeAgent("fixer"),
        )
        coEvery { service.listProviders() } returns null

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.selectedAgent?.id shouldBe "builder"
    }

    // ── loadAgentsAndProviders — provider loading & restoration ────────────

    @Test
    fun `loadAgentsAndProviders populates models from connected providers`() = runTest {
        coEvery { service.listAgents() } returns emptyList()
        val connected = makeProvider(
            id = "anthropic", name = "Anthropic",
            models = mapOf("claude-sonnet" to makeModelData("claude-sonnet", "Claude Sonnet")),
        )
        val disconnected = makeProvider(
            id = "openai", name = "OpenAI",
            models = mapOf("gpt-4" to makeModelData("gpt-4", "GPT-4")),
        )
        coEvery { service.listProviders() } returns ProviderResponse(
            all = listOf(connected, disconnected),
            connected = listOf("anthropic"),
        )

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.models.map { it.modelID } shouldBe listOf("claude-sonnet")
        // allModels includes disconnected providers too
        viewModel.controlState.value.allModels.map { it.modelID } shouldBe listOf("claude-sonnet", "gpt-4")
        // First connected model is auto-selected when no saved key
        viewModel.controlState.value.selectedModel?.modelID shouldBe "claude-sonnet"
    }

    @Test
    fun `loadAgentsAndProviders restores saved model from lastSelectedModelKey`() = runTest {
        every { settings.lastSelectedModelKey } returns "anthropic/claude-opus"
        coEvery { service.listAgents() } returns emptyList()
        coEvery { service.listProviders() } returns ProviderResponse(
            all = listOf(
                makeProvider(
                    id = "anthropic", name = "Anthropic",
                    models = mapOf(
                        "claude-sonnet" to makeModelData("claude-sonnet", "Claude Sonnet"),
                        "claude-opus" to makeModelData("claude-opus", "Claude Opus"),
                    ),
                ),
            ),
            connected = listOf("anthropic"),
        )

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.selectedModel?.modelID shouldBe "claude-opus"
        viewModel.controlState.value.selectedModel?.providerID shouldBe "anthropic"
    }

    @Test
    fun `loadAgentsAndProviders restores saved agent from lastSelectedAgent`() = runTest {
        every { settings.lastSelectedAgent } returns "builder"
        coEvery { service.listAgents() } returns listOf(
            makeAgent("orchestrator"),
            makeAgent("builder"),
        )
        coEvery { service.listProviders() } returns ProviderResponse(
            all = listOf(
                makeProvider(
                    id = "anthropic", name = "Anthropic",
                    models = mapOf("claude-sonnet" to makeModelData("claude-sonnet", "Claude Sonnet")),
                ),
            ),
            connected = listOf("anthropic"),
        )

        viewModel.loadAgentsAndProviders()

        // Orchestrator is selected first as default, then overridden by saved agent.
        viewModel.controlState.value.selectedAgent?.id shouldBe "builder"
    }

    @Test
    fun `loadAgentsAndProviders restores saved thinking effort from lastSelectedThinkingEffort`() = runTest {
        every { settings.lastSelectedThinkingEffort } returns "HIGH"
        coEvery { service.listAgents() } returns emptyList()
        coEvery { service.listProviders() } returns ProviderResponse(
            all = listOf(
                makeProvider(
                    id = "anthropic", name = "Anthropic",
                    models = mapOf("claude-sonnet" to makeModelData("claude-sonnet", "Claude Sonnet")),
                ),
            ),
            connected = listOf("anthropic"),
        )

        viewModel.loadAgentsAndProviders()

        viewModel.controlState.value.thinkingEffort shouldBe ThinkingEffort.HIGH
    }

    @Test
    fun `loadAgentsAndProviders with provider failure keeps defaults`() = runTest {
        coEvery { service.listAgents() } returns emptyList()
        coEvery { service.listProviders() } throws RuntimeException("network down")

        viewModel.loadAgentsAndProviders()

        // Should not throw — degrades gracefully
        viewModel.controlState.value.models shouldBe emptyList()
        viewModel.controlState.value.selectedModel shouldBe null
    }
}