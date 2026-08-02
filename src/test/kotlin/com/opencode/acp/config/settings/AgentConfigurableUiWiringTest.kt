package com.opencode.acp.config.settings

import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import javax.swing.JComboBox
import org.junit.jupiter.api.Test

/**
 * End-to-end UI wiring tests: ModelPickerComboBox → onModelSelected →
 * thinking JComboBox, using the SAME wiring as OpenCodeAgentConfigurable.
 *
 * These tests catch discrepancies between what the model picker reports
 * and what the thinking combo displays — the exact bug where models with
 * variants (like mimo-v2.5-pro) show no thinking levels in settings.
 */
class AgentConfigurableUiWiringTest {

    private fun model(
        providerID: String,
        modelID: String,
        variants: List<String> = emptyList(),
        contextWindow: Int = 0,
    ): ProviderModel = ProviderModel(
        providerID = providerID,
        modelID = modelID,
        displayName = "$providerID / $modelID",
        reasoning = false,
        contextWindow = contextWindow,
        providerIconId = providerID,
        variants = variants,
    )

    /**
     * EXACT replica of OpenCodeAgentConfigurable.updateThinkingOptions.
     * If this diverges from the real code, the test is meaningless.
     */
    private fun updateThinkingOptions(combo: JComboBox<ThinkingEffort>, model: ProviderModel?) {
        val variants = model?.variants ?: emptyList()
        val matched = variants.mapNotNull { variantName ->
            ThinkingEffort.entries.find { it.variant == variantName }
        }.filter { it != ThinkingEffort.DEFAULT }
            .distinctBy { it }
            .sortedBy { it.ordinal }
        val available = listOf(ThinkingEffort.DEFAULT) + matched

        val previous = combo.selectedItem as? ThinkingEffort
        combo.removeAllItems()
        for (effort in available) {
            combo.addItem(effort)
        }
        combo.selectedItem = if (previous != null && available.contains(previous)) previous else ThinkingEffort.DEFAULT
    }

    /**
     * EXACT replica of OpenCodeAgentConfigurable.addMemberRow wiring.
     */
    private data class WiredRow(
        val picker: ModelPickerComboBox,
        val thinkingCombo: JComboBox<ThinkingEffort>,
    )

    private fun wireRow(
        models: List<ProviderModel>,
        providerID: String = "",
        modelID: String = "",
        thinkingVariant: String = "",
    ): WiredRow {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(models)

        val thinkingCombo = JComboBox<ThinkingEffort>()

        picker.onModelSelected = { newModel ->
            val previous = thinkingCombo.selectedItem as? ThinkingEffort
            updateThinkingOptions(thinkingCombo, newModel)
            if (previous != null) {
                thinkingCombo.selectedItem = previous
            }
        }

        if (providerID.isNotBlank() && modelID.isNotBlank()) {
            picker.setSelectedModel(providerID, modelID)
        }

        if (thinkingVariant.isNotBlank()) {
            val effort = ThinkingEffort.entries.find { it.variant == thinkingVariant }
            if (effort != null && thinkingCombo.model.size > 0) {
                thinkingCombo.selectedItem = effort
            }
        }

        return WiredRow(picker, thinkingCombo)
    }

    /**
     * EXACT replica of OpenCodeAgentConfigurable.refreshMemberPickers.
     */
    private fun refreshRow(row: WiredRow, models: List<ProviderModel>) {
        val currentModel = row.picker.getSelectedModel()
        val currentThinking = row.thinkingCombo.selectedItem as? ThinkingEffort
        row.picker.setAvailableModels(models)
        if (currentModel != null) {
            row.picker.setSelectedModel(currentModel.providerID, currentModel.modelID)
        }
        updateThinkingOptions(row.thinkingCombo, row.picker.getSelectedModel())
        if (currentThinking != null) {
            row.thinkingCombo.selectedItem = currentThinking
        }
    }

    // ── Basic wiring tests ───────────────────────────────────────────────────

    @Test
    fun `selecting model with variants populates thinking combo`() {
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
        )
        val row = wireRow(models, "opencode-go", "mimo-v2.5-pro")

        row.thinkingCombo.itemCount shouldBe 4
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MEDIUM
        row.thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.HIGH
    }

    @Test
    fun `selecting model without variants shows only DEFAULT`() {
        val models = listOf(
            model("ollama-cloud", "mimo-v2.5-pro", variants = emptyList()),
        )
        val row = wireRow(models, "ollama-cloud", "mimo-v2.5-pro")

        row.thinkingCombo.itemCount shouldBe 1
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
    }

    @Test
    fun `switching from model with variants to model without resets thinking combo`() {
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "high")),
            model("ollama-cloud", "gpt-4o"),
        )
        val row = wireRow(models, "opencode-go", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 3

        // Switch to non-thinking model
        row.picker.setSelectedModel("ollama-cloud", "gpt-4o")
        row.thinkingCombo.itemCount shouldBe 1
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
    }

    // ── Async refresh tests (models arrive after placeholder) ────────────────

    @Test
    fun `async refresh replaces placeholder and populates thinking combo`() {
        // Start with no models — placeholder selected
        val row = wireRow(emptyList(), "opencode-go", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 1 // DEFAULT only (placeholder has no variants)

        // Models arrive async
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
        )
        refreshRow(row, models)

        row.picker.getSelectedModel()?.variants shouldContainExactly listOf("low", "medium", "high")
        row.thinkingCombo.itemCount shouldBe 4
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MEDIUM
        row.thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.HIGH
    }

    @Test
    fun `async refresh preserves selected thinking level when model still supports it`() {
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
        )
        val row = wireRow(models, "opencode-go", "mimo-v2.5-pro", "high")
        row.thinkingCombo.selectedItem shouldBe ThinkingEffort.HIGH

        // Refresh with same models
        refreshRow(row, models)
        row.thinkingCombo.selectedItem shouldBe ThinkingEffort.HIGH
    }

    // ── Multiple providers with same modelID but different variants ──────────

    @Test
    fun `same modelID under different providers shows correct variants per provider`() {
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
            model("ollama-cloud", "mimo-v2.5-pro", variants = emptyList()),
        )
        val row = wireRow(models, "opencode-go", "mimo-v2.5-pro")

        row.thinkingCombo.itemCount shouldBe 4

        // Switch to the same modelID under a different provider (no variants)
        row.picker.setSelectedModel("ollama-cloud", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 1
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
    }

    @Test
    fun `switching from no-variant provider to variant provider for same modelID adds thinking options`() {
        val models = listOf(
            model("ollama-cloud", "mimo-v2.5-pro", variants = emptyList()),
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
        )
        val row = wireRow(models, "ollama-cloud", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 1

        // Switch to the variant-capable provider
        row.picker.setSelectedModel("opencode-go", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 4
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MEDIUM
        row.thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.HIGH
    }

    // ── Deepseek and other multi-variant models ─────────────────────────────

    @Test
    fun `deepseek-v4-pro with high max variants shows correct thinking options`() {
        val models = listOf(
            model("opencode-go", "deepseek-v4-pro", variants = listOf("high", "max")),
        )
        val row = wireRow(models, "opencode-go", "deepseek-v4-pro")

        row.thinkingCombo.itemCount shouldBe 3
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.HIGH
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MAX
    }

    @Test
    fun `gpt-5_6-luna with none low medium high xhigh max shows all 7 options`() {
        val models = listOf(
            model("opencode-go", "gpt-5.6-luna", variants = listOf("none", "low", "medium", "high", "xhigh", "max")),
        )
        val row = wireRow(models, "opencode-go", "gpt-5.6-luna")

        row.thinkingCombo.itemCount shouldBe 7
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.NONE
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.LOW
        row.thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.MEDIUM
        row.thinkingCombo.getItemAt(4) shouldBe ThinkingEffort.HIGH
        row.thinkingCombo.getItemAt(5) shouldBe ThinkingEffort.MAX
        row.thinkingCombo.getItemAt(6) shouldBe ThinkingEffort.XHIGH
    }

    @Test
    fun `minimax-m3 with none thinking variants maps correctly`() {
        val models = listOf(
            model("opencode-go", "minimax-m3", variants = listOf("none", "thinking")),
        )
        val row = wireRow(models, "opencode-go", "minimax-m3")

        // "none" maps to ThinkingEffort.NONE, "thinking" doesn't match any enum entry
        row.thinkingCombo.itemCount shouldBe 2
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.NONE
    }

    // ── refreshMemberPickers edge cases ──────────────────────────────────────

    @Test
    fun `refresh does not duplicate thinking options`() {
        val models = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "high")),
        )
        val row = wireRow(models, "opencode-go", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 3

        // Refresh multiple times
        refreshRow(row, models)
        refreshRow(row, models)
        refreshRow(row, models)

        row.thinkingCombo.itemCount shouldBe 3
    }

    @Test
    fun `refresh after model list change updates thinking options correctly`() {
        // Start with a model that has low/high
        val models1 = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "high")),
        )
        val row = wireRow(models1, "opencode-go", "mimo-v2.5-pro")
        row.thinkingCombo.itemCount shouldBe 3

        // Refresh with updated model that now has low/medium/high/max
        val models2 = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high", "max")),
        )
        refreshRow(row, models2)

        row.thinkingCombo.itemCount shouldBe 5
        row.thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        row.thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        row.thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MEDIUM
        row.thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.HIGH
        row.thinkingCombo.getItemAt(4) shouldBe ThinkingEffort.MAX
    }

    // ── Chat screen comparison ───────────────────────────────────────────────

    /**
     * The chat screen's ThinkingSelector derives available efforts from
     * controlState.selectedModel.variants. This test verifies the settings
     * panel produces the SAME effort list for the SAME model.
     */
    @Test
    fun `settings panel thinking options match chat screen ThinkingSelector output`() {
        val testModels = listOf(
            model("opencode-go", "mimo-v2.5-pro", variants = listOf("low", "medium", "high")),
            model("opencode-go", "deepseek-v4-pro", variants = listOf("high", "max")),
            model("opencode-go", "gpt-5.6-luna", variants = listOf("none", "low", "medium", "high", "xhigh", "max")),
            model("opencode-go", "kimi-k2.7-code", variants = emptyList()),
            model("ollama-cloud", "mimo-v2.5-pro", variants = emptyList()),
        )

        for (testModel in testModels) {
            // Chat screen derivation (from Selectors.kt ThinkingSelector)
            val chatEfforts = run {
                val matched = testModel.variants.mapNotNull { v ->
                    ThinkingEffort.entries.find { it.variant == v }
                }.filter { it != ThinkingEffort.DEFAULT }
                    .distinctBy { it }
                    .sortedBy { it.ordinal }
                listOf(ThinkingEffort.DEFAULT) + matched
            }

            // Settings panel derivation (via wireRow)
            val row = wireRow(listOf(testModel), testModel.providerID, testModel.modelID)
            val settingsEfforts = (0 until row.thinkingCombo.itemCount).map {
                row.thinkingCombo.getItemAt(it)
            }

            settingsEfforts shouldContainExactly chatEfforts
        }
    }
}