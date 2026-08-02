package com.opencode.acp.config.settings

import com.opencode.acp.adapter.ModelData
import com.opencode.acp.adapter.ProviderData
import com.opencode.acp.adapter.ProviderResponse
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Tests that the settings panel and the chat screen produce identical
 * [ProviderModel] lists (including variants) from the same [ProviderResponse].
 *
 * Both paths call [ProviderData.models] → [ModelData.variants]?.keys?.toList().
 * This test verifies they agree, and that variants survive deserialization.
 */
class ModelVariantConsistencyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    // Realistic /provider response with variants on some models
    private val sampleProviderJson = """
    {
      "all": [
        {
          "id": "opencode-go",
          "name": "OpenCode Go",
          "env": [],
          "models": {
            "mimo-v2.5-pro": {
              "id": "mimo-v2.5-pro",
              "name": "MiMo V2.5 Pro",
              "reasoning": true,
              "tool_call": true,
              "attachment": true,
              "temperature": true,
              "release_date": "2026-04-22",
              "limit": { "context": 1048576, "output": 128000 },
              "variants": {
                "low": { "reasoningEffort": "low" },
                "medium": { "reasoningEffort": "medium" },
                "high": { "reasoningEffort": "high" }
              }
            },
            "mimo-v2.5": {
              "id": "mimo-v2.5",
              "name": "MiMo V2.5",
              "reasoning": true,
              "variants": {
                "low": { "reasoningEffort": "low" },
                "high": { "reasoningEffort": "high" }
              }
            }
          }
        },
        {
          "id": "ollama-cloud",
          "name": "Ollama Cloud",
          "env": [],
          "models": {
            "mimo-v2.5-pro": {
              "id": "mimo-v2.5-pro",
              "name": "MiMo V2.5 Pro",
              "reasoning": false
            },
            "glm-5.2": {
              "id": "glm-5.2",
              "name": "GLM 5.2",
              "reasoning": true,
              "variants": {
                "high": { "reasoningEffort": "high" },
                "max": { "reasoningEffort": "max" }
              }
            }
          }
        }
      ],
      "connected": ["opencode-go", "ollama-cloud"],
      "default": {}
    }
    """.trimIndent()

    /**
     * Mirrors [ControlBarViewModel.buildProviderModels] — the chat screen path.
     */
    private fun buildChatScreenModels(providers: ProviderResponse): List<ProviderModel> {
        val connectedIds = providers.connected.toSet()
        return providers.all.filter { it.id in connectedIds }.flatMap { provider ->
            provider.models.map { (_, modelData) ->
                ProviderModel(
                    providerID = provider.id,
                    modelID = modelData.id,
                    displayName = "${provider.name} / ${modelData.name}",
                    reasoning = modelData.reasoning,
                    contextWindow = modelData.limit?.context ?: 0,
                    providerIconId = provider.id,
                    variants = modelData.variants?.keys?.toList() ?: emptyList(),
                )
            }
        }
    }

    /**
     * Mirrors [OpenCodeAgentConfigurable.fetchAvailableModels] — the settings panel path.
     */
    private fun buildSettingsPanelModels(providers: ProviderResponse): List<ProviderModel> {
        val connectedIds = providers.connected.toSet()
        return providers.all
            .filter { it.id in connectedIds }
            .flatMap { provider ->
                provider.models.map { (_, modelData) ->
                    ProviderModel(
                        providerID = provider.id,
                        modelID = modelData.id,
                        displayName = "${provider.name} / ${modelData.name}",
                        reasoning = modelData.reasoning,
                        contextWindow = modelData.limit?.context ?: 0,
                        providerIconId = provider.id,
                        variants = modelData.variants?.keys?.toList() ?: emptyList(),
                    )
                }
            }.sortedBy { it.displayName }
    }

    @Test
    fun `deserialization preserves variants for models that have them`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val ogoMimo = response.all.find { it.id == "opencode-go" }!!
            .models["mimo-v2.5-pro"]!!
        ogoMimo.variants.shouldNotBeNull()
        ogoMimo.variants!!.keys shouldContainExactly setOf("low", "medium", "high")
    }

    @Test
    fun `deserialization produces null variants for models without them`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val ocMimo = response.all.find { it.id == "ollama-cloud" }!!
            .models["mimo-v2.5-pro"]!!
        ocMimo.variants shouldBe null
    }

    @Test
    fun `chat screen and settings panel produce identical variants for same model+provider`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)
        val settingsModels = buildSettingsPanelModels(response)

        // Both should produce the same number of models
        chatModels.size shouldBe settingsModels.size

        // Compare each model's variants by (providerID, modelID)
        for (chatModel in chatModels) {
            val settingsModel = settingsModels.find {
                it.providerID == chatModel.providerID && it.modelID == chatModel.modelID
            }
            settingsModel.shouldNotBeNull()
            settingsModel.variants shouldContainExactly chatModel.variants
            settingsModel.reasoning shouldBe chatModel.reasoning
            settingsModel.contextWindow shouldBe chatModel.contextWindow
        }
    }

    @Test
    fun `mimo-v2_5-pro under opencode-go has low medium high variants in both paths`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)
        val settingsModels = buildSettingsPanelModels(response)

        val chatMimo = chatModels.find {
            it.providerID == "opencode-go" && it.modelID == "mimo-v2.5-pro"
        }!!
        val settingsMimo = settingsModels.find {
            it.providerID == "opencode-go" && it.modelID == "mimo-v2.5-pro"
        }!!

        chatMimo.variants shouldContainExactly listOf("low", "medium", "high")
        settingsMimo.variants shouldContainExactly listOf("low", "medium", "high")
    }

    @Test
    fun `mimo-v2_5-pro under ollama-cloud has empty variants in both paths`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)
        val settingsModels = buildSettingsPanelModels(response)

        val chatMimo = chatModels.find {
            it.providerID == "ollama-cloud" && it.modelID == "mimo-v2.5-pro"
        }!!
        val settingsMimo = settingsModels.find {
            it.providerID == "ollama-cloud" && it.modelID == "mimo-v2.5-pro"
        }!!

        chatMimo.variants shouldBe emptyList()
        settingsMimo.variants shouldBe emptyList()
    }

    @Test
    fun `thinking efforts derived from variants match between paths`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)
        val settingsModels = buildSettingsPanelModels(response)

        // For each model, derive ThinkingEffort list (same logic as ThinkingSelector)
        fun deriveEfforts(model: ProviderModel): List<ThinkingEffort> {
            val matched = model.variants.mapNotNull { v ->
                ThinkingEffort.entries.find { it.variant == v }
            }.filter { it != ThinkingEffort.DEFAULT }
                .distinctBy { it }
                .sortedBy { it.ordinal }
            return listOf(ThinkingEffort.DEFAULT) + matched
        }

        for (chatModel in chatModels) {
            val settingsModel = settingsModels.find {
                it.providerID == chatModel.providerID && it.modelID == chatModel.modelID
            }!!
            deriveEfforts(chatModel) shouldContainExactly deriveEfforts(settingsModel)
        }
    }

    @Test
    fun `glm-5_2 under ollama-cloud has high max thinking efforts in both paths`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)
        val settingsModels = buildSettingsPanelModels(response)

        val chatGlm = chatModels.find {
            it.providerID == "ollama-cloud" && it.modelID == "glm-5.2"
        }!!
        val settingsGlm = settingsModels.find {
            it.providerID == "ollama-cloud" && it.modelID == "glm-5.2"
        }!!

        chatGlm.variants shouldContainExactly listOf("high", "max")
        settingsGlm.variants shouldContainExactly listOf("high", "max")
    }

    @Test
    fun `duplicate modelID across providers produces separate entries with different variants`() {
        val response = json.decodeFromString<ProviderResponse>(sampleProviderJson)

        val chatModels = buildChatScreenModels(response)

        // mimo-v2.5-pro appears under both opencode-go (with variants) and ollama-cloud (without)
        val mimoEntries = chatModels.filter { it.modelID == "mimo-v2.5-pro" }
        mimoEntries.size shouldBe 2

        val ogoMimo = mimoEntries.find { it.providerID == "opencode-go" }!!
        val ocMimo = mimoEntries.find { it.providerID == "ollama-cloud" }!!

        ogoMimo.variants shouldContainExactly listOf("low", "medium", "high")
        ocMimo.variants shouldBe emptyList()
    }
}
