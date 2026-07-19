package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.ProviderData
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns `_controlState` (agent/model/thinking effort selection) and the
 * agent/provider loading logic extracted from [ChatViewModel.initialize].
 *
 * The `initialize()` mutex/tryLock guard stays in [ChatViewModel] — this class
 * only owns the state and the loading logic.
 *
 * Extracted per TDD `docs/tdd/ui-testability-refactor.md` §9 step 6 (Phase 3).
 */
class ControlBarViewModel(
    private val service: OpenCodeServiceApi,
    private val settings: OpenCodeSettingsState,
) {
    private val logger = KotlinLogging.logger {}

    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

    /**
     * Load agents and providers. Extracted from [ChatViewModel.initialize]
     * phases 1-3 (LOADING_AGENTS + LOADING_PROVIDERS). Each phase has a 30s
     * timeout and degrades gracefully on failure.
     *
     * The MCP loading phase (LOADING_MCP) is NOT part of this method — it
     * stays in [ChatViewModel.initialize] because it depends on
     * `service.toolRegistry`, `service.connectionManager.port`, and
     * `service.mcpManager`, which are not concerns of this class.
     */
    suspend fun loadAgentsAndProviders() {
        // Phase 1: Load agents
        try {
            val agents = withTimeoutOrNull(30_000) { service.listAgents() }
            if (agents == null) {
                logger.warn { "[ACP] Agent loading timed out after 30s — continuing with defaults" }
            } else {
                val filtered = agents.filter { it.mode != "subagent" && it.hidden != true }
                _controlState.value = _controlState.value.copy(
                    agents = filtered.map { info ->
                        OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                    }
                )
                val defaultAgentInfo = filtered.firstOrNull { it.name == "orchestrator" }
                    ?: filtered.firstOrNull()
                if (defaultAgentInfo != null) {
                    val agentInfo = _controlState.value.agents.find { it.id == defaultAgentInfo.id }
                    if (agentInfo != null) {
                        _controlState.value = _controlState.value.copy(selectedAgent = agentInfo)
                    }
                }
                logger.info { "[ACP] ControlBarViewModel: agents loaded (${filtered.size} filtered, defaultAgent=${defaultAgentInfo?.id})" }
            }
        } catch (e: Exception) {
            // Silent degradation: the agent picker will be empty. The user has no
            // indication that loading failed and may assume no agents are available.
            // TODO: surface a non-blocking notification (snackbar/status bar) on failure
            // for discoverability — requires design discussion (out of scope here).
            logger.warn { "[ACP] Agent loading failed: ${e.message} — agent picker will be empty. User should check connection and retry." }
        }
        // Always progress — agent loading is optional (chat works with defaults)

        // Phase 2: Load providers
        try {
            val providers = withTimeoutOrNull(30_000) { service.listProviders() }
            if (providers == null) {
                logger.warn { "[ACP] Provider loading timed out after 30s" }
            } else {
                val connectedIds = providers.connected.toSet()
                fun buildProviderModels(providerList: List<ProviderData>): List<ProviderModel> {
                    return providerList.flatMap { provider ->
                        provider.models.map { (_, modelData) ->
                            ProviderModel(
                                providerID = provider.id,
                                modelID = modelData.id,
                                displayName = "${provider.name} / ${modelData.name}",
                                reasoning = modelData.reasoning,
                                contextWindow = modelData.limit?.context ?: 0,
                                providerIconId = provider.id,
                                variants = modelData.variants?.keys?.toList() ?: emptyList()
                            )
                        }
                    }
                }
                val models = buildProviderModels(providers.all.filter { it.id in connectedIds })
                val allModels = buildProviderModels(providers.all)
                val savedKey = settings.lastSelectedModelKey
                val restoredModel = if (savedKey.isNotEmpty()) {
                    models.find { OpenCodeSettingsState.modelKey(it.providerID, it.modelID) == savedKey }
                } else null
                _controlState.value = _controlState.value.copy(
                    models = models,
                    allModels = allModels,
                    selectedModel = restoredModel ?: models.firstOrNull()
                )
                // Restore persisted agent selection
                val savedAgentId = settings.lastSelectedAgent
                if (savedAgentId.isNotEmpty()) {
                    val restoredAgent = _controlState.value.agents.find { it.id == savedAgentId }
                    if (restoredAgent != null) {
                        _controlState.value = _controlState.value.copy(selectedAgent = restoredAgent)
                    }
                }
                // Restore persisted thinking effort
                val savedEffortName = settings.lastSelectedThinkingEffort
                if (savedEffortName.isNotEmpty()) {
                    val restoredEffort = ThinkingEffort.entries.firstOrNull { it.name == savedEffortName }
                    if (restoredEffort != null) {
                        _controlState.value = _controlState.value.copy(thinkingEffort = restoredEffort)
                    }
                }
                logger.info { "[ACP] ControlBarViewModel: providers loaded (${providers.all.size} total)" }
            }
        } catch (e: Exception) {
            // Silent degradation: the model picker will be empty. The user has no
            // indication that loading failed and may assume no models are available.
            // TODO: surface a non-blocking notification (snackbar/status bar) on failure
            // for discoverability — requires design discussion (out of scope here).
            logger.warn { "[ACP] Provider loading failed: ${e.message} — model picker will be empty. User should check connection and retry." }
        }
    }

    fun selectAgent(agent: OpenCodeAgentInfo) {
        _controlState.value = _controlState.value.copy(selectedAgent = agent)
        settings.lastSelectedAgent = agent.id
    }

    fun selectModel(model: ProviderModel) {
        _controlState.value = _controlState.value.copy(selectedModel = model)
        settings.lastSelectedModelKey = OpenCodeSettingsState.modelKey(model.providerID, model.modelID)
    }

    fun selectThinkingEffort(effort: ThinkingEffort) {
        _controlState.value = _controlState.value.copy(thinkingEffort = effort)
        settings.lastSelectedThinkingEffort = effort.name
    }
}