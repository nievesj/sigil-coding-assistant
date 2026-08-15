package com.opencode.acp.config.settings

import com.opencode.acp.adapter.ModelData
import com.opencode.acp.adapter.ModelLimit
import com.opencode.acp.adapter.ProviderData
import com.opencode.acp.adapter.ProviderResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OpenCodeAgentConfigurable.buildProviderModels] — the pure
 * mapping of a `GET /provider` response into the [ProviderModel] list shown
 * in the Agents settings pickers.
 *
 * Regression coverage for the "no models shown in Agents settings" bug: the
 * mapping must only surface models of **connected** providers (mirroring the
 * chat screen), preserve variants for the thinking-level dropdown, and sort
 * by display name.
 */
class AgentConfigurableProviderModelsTest {

    private fun provider(
        id: String,
        name: String,
        models: Map<String, ModelData>,
    ): ProviderData = ProviderData(id = id, name = name, models = models)

    private fun model(
        id: String,
        name: String,
        reasoning: Boolean = false,
        context: Int = 0,
        variants: List<String> = emptyList(),
    ): ModelData = ModelData(
        id = id,
        name = name,
        reasoning = reasoning,
        limit = ModelLimit(context = context),
        variants = if (variants.isEmpty()) null else variants.associateWith { emptyMap<String, kotlinx.serialization.json.JsonElement>() },
    )

    @Test
    fun `only models of connected providers are included`() {
        val response = ProviderResponse(
            all = listOf(
                provider("opencode-go", "OpenCode Go", models = mapOf("m1" to model("m1", "Model One"))),
                provider("ollama-cloud", "Ollama Cloud", models = mapOf("m2" to model("m2", "Model Two"))),
            ),
            connected = listOf("opencode-go"),
        )

        val result = OpenCodeAgentConfigurable.buildProviderModels(response)

        result.map { it.providerID } shouldContainExactly listOf("opencode-go")
        result.map { it.modelID } shouldContainExactly listOf("m1")
    }

    @Test
    fun `models are sorted by display name`() {
        val response = ProviderResponse(
            all = listOf(
                provider(
                    "opencode-go",
                    "OpenCode Go",
                    models = mapOf(
                        "zebra" to model("zebra", "Zebra"),
                        "alpha" to model("alpha", "Alpha"),
                        "mimo" to model("mimo", "Mimo"),
                    ),
                ),
            ),
            connected = listOf("opencode-go"),
        )

        val result = OpenCodeAgentConfigurable.buildProviderModels(response)

        result.map { it.modelID } shouldContainExactly listOf("alpha", "mimo", "zebra")
    }

    @Test
    fun `variants and context window are carried into ProviderModel`() {
        val response = ProviderResponse(
            all = listOf(
                provider(
                    "opencode-go",
                    "OpenCode Go",
                    models = mapOf(
                        "mimo-v2.5-pro" to model(
                            "mimo-v2.5-pro",
                            "Mimo v2.5 Pro",
                            reasoning = true,
                            context = 200_000,
                            variants = listOf("low", "medium", "high"),
                        ),
                    ),
                ),
            ),
            connected = listOf("opencode-go"),
        )

        val result = OpenCodeAgentConfigurable.buildProviderModels(response)

        result.size shouldBe 1
        val pm = result.first()
        pm.providerID shouldBe "opencode-go"
        pm.modelID shouldBe "mimo-v2.5-pro"
        pm.displayName shouldBe "OpenCode Go / Mimo v2.5 Pro"
        pm.reasoning shouldBe true
        pm.contextWindow shouldBe 200_000
        pm.variants shouldContainExactly listOf("low", "medium", "high")
    }

    @Test
    fun `empty response yields empty list`() {
        val response = ProviderResponse(all = emptyList(), connected = emptyList())

        OpenCodeAgentConfigurable.buildProviderModels(response) shouldBe emptyList()
    }

    @Test
    fun `disconnected providers contribute no models even when listed`() {
        val response = ProviderResponse(
            all = listOf(
                provider("opencode-go", "OpenCode Go", models = mapOf("m1" to model("m1", "Model One"))),
            ),
            connected = emptyList(),
        )

        OpenCodeAgentConfigurable.buildProviderModels(response) shouldBe emptyList()
    }
}
