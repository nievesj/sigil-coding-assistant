package com.opencode.acp.config.settings

import com.opencode.acp.chat.model.DropdownItem
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import javax.swing.JComboBox
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ModelPickerComboBox] — the searchable, provider-grouped
 * model picker used in the Agents settings panel.
 *
 * Tests the model→thinking dependency: selecting a model must update the
 * thinking-level dropdown to show only that model's supported variants.
 * Uses direct construction (no app context, no reflection) per the
 * established test pattern.
 */
class ModelPickerComboBoxTest {

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

    @Test
    fun `setAvailableModels groups models by provider with headers`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4"),
            model("anthropic", "claude-opus-4"),
            model("openai", "gpt-4o"),
        ))

        val items = picker.allItemsForTest()
        // 2 providers → 2 headers + 3 models = 5 items
        items shouldHaveSize 5
        items[0] shouldBe DropdownItem.ProviderHeader("anthropic")
        items[1] shouldBe DropdownItem.ModelItem(
            model = model("anthropic", "claude-opus-4"),
            providerName = "anthropic",
            modelName = "claude-opus-4",
            isFavorite = false,
        )
        items[2] shouldBe DropdownItem.ModelItem(
            model = model("anthropic", "claude-sonnet-4"),
            providerName = "anthropic",
            modelName = "claude-sonnet-4",
            isFavorite = false,
        )
        items[3] shouldBe DropdownItem.ProviderHeader("openai")
        items[4] shouldBe DropdownItem.ModelItem(
            model = model("openai", "gpt-4o"),
            providerName = "openai",
            modelName = "gpt-4o",
            isFavorite = false,
        )
    }

    @Test
    fun `setAvailableModels with context window shows label in model items`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", contextWindow = 200_000),
        ))

        val items = picker.allItemsForTest()
        val modelItem = items[1] as DropdownItem.ModelItem
        modelItem.contextWindowLabel shouldBe "200K"
    }

    @Test
    fun `setAvailableModels with 1M context window formats as M`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", contextWindow = 1_000_000),
        ))

        val items = picker.allItemsForTest()
        val modelItem = items[1] as DropdownItem.ModelItem
        modelItem.contextWindowLabel shouldBe "1M"
    }

    @Test
    fun `setSelectedModel selects a model that exists in the list`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))
        picker.setSelectedModel("anthropic", "claude-sonnet-4")

        picker.getSelectedModel()?.providerID shouldBe "anthropic"
        picker.getSelectedModel()?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `setSelectedModel creates placeholder when model not in list`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(emptyList())
        picker.setSelectedModel("openai", "gpt-4o")

        // Placeholder preserves the selection
        picker.getSelectedModel()?.providerID shouldBe "openai"
        picker.getSelectedModel()?.modelID shouldBe "gpt-4o"
    }

    @Test
    fun `setAvailableModels replaces placeholder with real model when it arrives`() {
        val picker = ModelPickerComboBox()
        // Server not running yet — placeholder
        picker.setAvailableModels(emptyList())
        picker.setSelectedModel("anthropic", "claude-sonnet-4")
        picker.getSelectedModel()?.variants shouldBe emptyList()

        // Server starts — models arrive
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))

        // Placeholder replaced with real model (which has variants)
        picker.getSelectedModel()?.providerID shouldBe "anthropic"
        picker.getSelectedModel()?.modelID shouldBe "claude-sonnet-4"
        picker.getSelectedModel()?.variants shouldContainExactly listOf("low", "high")
    }

    @Test
    fun `onModelSelected fires when selecting from the list`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))

        var firedModel: ProviderModel? = null
        picker.onModelSelected = { firedModel = it }

        picker.setSelectedModel("anthropic", "claude-sonnet-4")

        firedModel?.providerID shouldBe "anthropic"
        firedModel?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `onModelSelected fires with placeholder when model not in list`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(emptyList())

        var firedModel: ProviderModel? = null
        picker.onModelSelected = { firedModel = it }

        picker.setSelectedModel("openai", "gpt-4o")

        firedModel?.providerID shouldBe "openai"
        firedModel?.modelID shouldBe "gpt-4o"
    }

    @Test
    fun `onModelSelected fires when real model replaces placeholder`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(emptyList())
        picker.setSelectedModel("anthropic", "claude-sonnet-4")

        var firedModel: ProviderModel? = null
        picker.onModelSelected = { firedModel = it }

        // Models arrive — placeholder replaced
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))

        firedModel?.providerID shouldBe "anthropic"
        firedModel?.variants shouldContainExactly listOf("low", "high")
    }

    @Test
    fun `selectFromList fires onModelSelected and updates selectedModel`() {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(listOf(
            model("openai", "gpt-4o"),
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))

        var firedModel: ProviderModel? = null
        picker.onModelSelected = { firedModel = it }

        // Simulate clicking the second model in the filtered list
        // (index 0 = header, 1 = gpt-4o, 2 = header, 3 = claude-sonnet-4)
        picker.selectFromListForTest(3)

        firedModel?.providerID shouldBe "anthropic"
        firedModel?.modelID shouldBe "claude-sonnet-4"
        picker.getSelectedModel()?.modelID shouldBe "claude-sonnet-4"
    }

    @Test
    fun `switching models via list updates thinking combo`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(
            model("openai", "gpt-4o"),
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
        ))

        // Select the non-thinking model via list (index 1)
        picker.selectFromListForTest(1)
        thinkingCombo.itemCount shouldBe 1

        // Switch to the thinking-capable model via list (index 3)
        picker.selectFromListForTest(3)
        thinkingCombo.itemCount shouldBe 3
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.HIGH
    }

    // ── Model → Thinking dependency ─────────────────────────────────────────

    /**
     * Helper that mirrors the wiring in [OpenCodeAgentConfigurable.addMemberRow]:
     * a [ModelPickerComboBox] drives a thinking [JComboBox] via
     * [ModelPickerComboBox.onModelSelected].
     */
    private fun wiredThinkingCombo(picker: ModelPickerComboBox): JComboBox<ThinkingEffort> {
        val thinkingCombo = JComboBox<ThinkingEffort>()
        picker.onModelSelected = { newModel ->
            val variants = newModel?.variants ?: emptyList()
            val matched = variants.mapNotNull { v ->
                ThinkingEffort.entries.find { it.variant == v }
            }.filter { it != ThinkingEffort.DEFAULT }
                .distinctBy { it }
                .sortedBy { it.ordinal }
            val available = listOf(ThinkingEffort.DEFAULT) + matched
            thinkingCombo.removeAllItems()
            for (effort in available) {
                thinkingCombo.addItem(effort)
            }
            thinkingCombo.selectedItem = ThinkingEffort.DEFAULT
        }
        return thinkingCombo
    }

    @Test
    fun `thinking combo shows only DEFAULT when model has no variants`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(model("openai", "gpt-4o")))
        picker.setSelectedModel("openai", "gpt-4o")

        thinkingCombo.itemCount shouldBe 1
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
    }

    @Test
    fun `thinking combo shows DEFAULT plus model variants when model supports thinking`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "medium", "high")),
        ))
        picker.setSelectedModel("anthropic", "claude-sonnet-4")

        thinkingCombo.itemCount shouldBe 4
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MEDIUM
        thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.HIGH
    }

    @Test
    fun `switching from model with variants to model without variants resets thinking to DEFAULT only`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high")),
            model("openai", "gpt-4o"),
        ))

        // Select the thinking-capable model
        picker.setSelectedModel("anthropic", "claude-sonnet-4")
        thinkingCombo.itemCount shouldBe 3

        // Switch to the non-thinking model
        picker.setSelectedModel("openai", "gpt-4o")
        thinkingCombo.itemCount shouldBe 1
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
    }

    @Test
    fun `switching from model without variants to model with variants adds thinking options`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(
            model("openai", "gpt-4o"),
            model("anthropic", "claude-sonnet-4", variants = listOf("low", "high", "max")),
        ))

        // Start with the non-thinking model
        picker.setSelectedModel("openai", "gpt-4o")
        thinkingCombo.itemCount shouldBe 1

        // Switch to the thinking-capable model
        picker.setSelectedModel("anthropic", "claude-sonnet-4")
        thinkingCombo.itemCount shouldBe 4
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.LOW
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.HIGH
        thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.MAX
    }

    @Test
    fun `thinking combo updates when models arrive async and placeholder is replaced`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)

        // Server not running — placeholder (no variants)
        picker.setAvailableModels(emptyList())
        picker.setSelectedModel("anthropic", "claude-sonnet-4")
        thinkingCombo.itemCount shouldBe 1

        // Server starts — real model arrives with variants
        picker.setAvailableModels(listOf(
            model("anthropic", "claude-sonnet-4", variants = listOf("medium", "high")),
        ))
        thinkingCombo.itemCount shouldBe 3
        thinkingCombo.getItemAt(0) shouldBe ThinkingEffort.DEFAULT
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.MEDIUM
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.HIGH
    }

    @Test
    fun `different models with different variants produce different thinking options`() {
        val picker = ModelPickerComboBox()
        val thinkingCombo = wiredThinkingCombo(picker)
        picker.setAvailableModels(listOf(
            model("google", "gemini-pro", variants = listOf("minimal", "low")),
            model("anthropic", "claude-opus", variants = listOf("high", "max", "xhigh")),
        ))

        // Model A
        picker.setSelectedModel("google", "gemini-pro")
        thinkingCombo.itemCount shouldBe 3
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.MINIMAL
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.LOW

        // Model B
        picker.setSelectedModel("anthropic", "claude-opus")
        thinkingCombo.itemCount shouldBe 4
        thinkingCombo.getItemAt(1) shouldBe ThinkingEffort.HIGH
        thinkingCombo.getItemAt(2) shouldBe ThinkingEffort.MAX
        thinkingCombo.getItemAt(3) shouldBe ThinkingEffort.XHIGH
    }
}