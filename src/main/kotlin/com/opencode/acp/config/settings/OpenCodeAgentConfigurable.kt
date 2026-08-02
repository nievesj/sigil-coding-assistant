package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.opencode.acp.chat.model.DropdownItem
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.service.OpenCodeService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.ArrayList
import javax.swing.AbstractListModel
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.UIManager
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

private val logger = KotlinLogging.logger {}

/**
 * Child configurable for Custom Agents settings.
 * Appears as "Agents" under the "Sigil" settings node.
 *
 * Exposes:
 *  - `coding-assistant` agent enable/disable toggle
 *  - `council` subagent enable/disable toggle
 *  - Delegation allowlist (which agents can be invoked via the `task` tool)
 *  - Council member models (selected from connected providers' model lists)
 *
 * Council member models use a searchable, provider-grouped dropdown that
 * mirrors the model picker in the main chat screen (using the same
 * [DropdownItem] sealed type: [DropdownItem.ProviderHeader] section headers
 * + [DropdownItem.ModelItem] entries). If the server is not running, members
 * are shown as text placeholders until models become available.
 *
 * Changes take effect on the next OpenCode server restart — the agent config is
 * written to `.opencode/agents/` before the server launches.
 */
class OpenCodeAgentConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enableCodingAssistantCheckbox: JBCheckBox? = null
    private var enableCouncilCheckbox: JBCheckBox? = null

    // Delegation allowlist checkboxes
    private var exploreCheckbox: JBCheckBox? = null
    private var generalCheckbox: JBCheckBox? = null
    private var councilCheckbox: JBCheckBox? = null

    // Council member rows
    private val memberRows: MutableList<MemberRow> = mutableListOf()
    private var membersPanel: JPanel? = null
    private var addMemberButton: JButton? = null

    // Available models from connected providers (fetched asynchronously)
    private var availableModels: List<ProviderModel> = emptyList()

    // Restart hint
    private var restartHintLabel: JBLabel? = null

    /**
     * Helper pairing the model picker and thinking-level dropdown with the
     * remove button and the row panel.
     */
    private data class MemberRow(
        val modelPicker: ModelPickerComboBox,
        val thinkingCombo: JComboBox<ThinkingEffort>,
        val removeButton: JButton,
        val rowPanel: JPanel,
        // Persisted thinking variant awaiting re-application once the model list
        // loads asynchronously. Set in addMemberRow from the saved settings; cleared
        // after refreshMemberPickers applies it. Without this, the variant is lost
        // when models arrive late (the combo was empty at reset time).
        var pendingThinkingVariant: String = "",
    )

    override fun getDisplayName(): String = "Agents"

    override fun createComponent(): JComponent {
        val settings = OpenCodeAgentSettingsState.getInstance()

        enableCodingAssistantCheckbox = JBCheckBox("Enable Coding Assistant", settings.enableCodingAssistant).apply {
            toolTipText = "A primary agent optimized for hands-on coding that prefers IntelliJ MCP tools " +
                    "(symbol search, call hierarchy, build, debugger) and falls back to generic tools when MCP is unavailable. " +
                    "Can delegate to subagents including the council agent for multi-model review."
        }
        enableCouncilCheckbox = JBCheckBox("Enable Council", settings.enableCouncil).apply {
            toolTipText = "A subagent that fans out a review prompt to N configured models via parallel subtasks, " +
                    "then spawns a dedicated synthesis subtask to produce a consolidated consensus report."
            // Auto-check the 'council' delegation checkbox when Council is enabled.
            // Without this, a user can enable Council (writing the council agent file)
            // but forget to check the delegation checkbox, leaving the council agent
            // unreachable (buildTaskPermissionYaml omits 'council' from the task
            // allowlist). The user can still uncheck it manually if desired.
            addItemListener { e ->
                if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                    councilCheckbox?.isSelected = true
                }
            }
        }

        // Delegation allowlist
        exploreCheckbox = JBCheckBox("explore", "explore" in settings.taskAllowedAgents).apply {
            toolTipText = "Built-in read-only agent for fast codebase search and pattern matching."
        }
        generalCheckbox = JBCheckBox("general", "general" in settings.taskAllowedAgents).apply {
            toolTipText = "Built-in general-purpose agent for running multiple units of work in parallel."
        }
        councilCheckbox = JBCheckBox("council", "council" in settings.taskAllowedAgents).apply {
            toolTipText = "Multi-model council review agent. Only available when Council is enabled above."
        }

        // Council members panel
        membersPanel = JPanel(GridLayout(0, 1, 4, 4))
        memberRows.clear()
        for (member in settings.councilMembers) {
            addMemberRow(member.providerID, member.modelID)
        }
        // If no members, add one empty row so the user has a starting point
        if (memberRows.isEmpty()) {
            addMemberRow("", "")
        }

        addMemberButton = JButton("+ Add Member").apply {
            addActionListener { addMemberRow("", "") }
        }

        restartHintLabel = JBLabel("ℹ Changes take effect on next server restart").apply {
            foreground = JBColor.GRAY
        }

        // Build the allowlist panel (horizontal checkboxes)
        val allowlistPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(exploreCheckbox!!)
            add(generalCheckbox!!)
            add(councilCheckbox!!)
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(enableCodingAssistantCheckbox!!)
            .addComponent(JBLabel("A primary agent for hands-on coding that prefers IntelliJ MCP tools and degrades gracefully when MCP is off.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(enableCouncilCheckbox!!)
            .addComponent(JBLabel("A subagent that gathers reviews from multiple AI models and synthesizes a consensus report.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addVerticalGap(8)
            .addSeparator()
            .addVerticalGap(4)
            .addComponent(JBLabel("Delegation Allowlist (applies to both agents)").apply {
                font = font.deriveFont(Font.BOLD)
            })
            .addComponent(JBLabel("Which agents can be invoked via the task tool. Built-in agents (explore, general) and the council subagent.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(allowlistPanel)
            .addVerticalGap(8)
            .addSeparator()
            .addVerticalGap(4)
            .addComponent(JBLabel("Council Members").apply {
                font = font.deriveFont(Font.BOLD)
            })
            .addComponent(JBLabel("Models that participate in council reviews. Each member runs the review prompt in parallel, then a synthesis subtask produces the consensus report.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(membersPanel!!)
            .addComponent(addMemberButton!!)
            .addVerticalGap(8)
            .addComponent(restartHintLabel!!)
            .panel

        // Fetch available models from the OpenCode server asynchronously
        fetchAvailableModels()

        return panel!!
    }

    /**
     * Fetch the list of available models from connected providers via the
     * OpenCodeService. Populates [availableModels] and refreshes all member
     * pickers on the EDT.
     */
    private fun fetchAvailableModels() {
        val project = getActiveProject() ?: return
        val service = try {
            project.service<OpenCodeService>()
        } catch (e: Exception) {
            return
        }

        service.scope.launch {
            try {
                val providers = service.listProviders()
                if (providers == null) {
                    logger.debug { "[ACP] AgentConfigurable: listProviders returned null" }
                    return@launch
                }

                val connectedIds = providers.connected.toSet()
                val models = providers.all
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

                val modality = ModalityState.stateForComponent(panel ?: run {
                    logger.debug { "[ACP] AgentConfigurable: panel was null before model fetch completed — models not loaded (user likely closed settings)" }
                    return@launch
                })
                ApplicationManager.getApplication().invokeLater({
                    if (panel?.isDisplayable != true) return@invokeLater
                    availableModels = models
                    refreshMemberPickers()
                }, modality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] AgentConfigurable: failed to fetch available models" }
            }
        }
    }

    /**
     * Add a member row with a searchable, provider-grouped model picker and a
     * thinking-level dropdown.
     *
     * @param providerID The persisted provider ID (used to pre-select if models are loaded)
     * @param modelID The persisted model ID (used to pre-select if models are loaded)
     * @param thinkingVariant The persisted thinking variant (e.g. "high"); empty = default
     */
    private fun addMemberRow(providerID: String, modelID: String, thinkingVariant: String = "") {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(availableModels)

        // Thinking-level dropdown — filtered to the selected model's variants
        val thinkingCombo = JComboBox<ThinkingEffort>()
        thinkingCombo.renderer = ThinkingEffortRenderer()

        // Wire the model → thinking dependency BEFORE any selection so every
        // selection (initial + later) refreshes the thinking options.
        picker.onModelSelected = { newModel ->
            val previous = thinkingCombo.selectedItem as? ThinkingEffort
            updateThinkingOptions(thinkingCombo, newModel)
            // Preserve the previous thinking choice if still available
            if (previous != null) {
                thinkingCombo.selectedItem = previous
            }
        }

        // Pre-select the persisted model if available — fires onModelSelected
        // which populates the thinking combo.
        if (providerID.isNotBlank() && modelID.isNotBlank()) {
            picker.setSelectedModel(providerID, modelID)
        }

        // Pre-select the persisted variant (if the model supports it)
        if (thinkingVariant.isNotBlank()) {
            val effort = ThinkingEffort.entries.find { it.variant == thinkingVariant }
            if (effort != null && thinkingCombo.model.size > 0) {
                thinkingCombo.selectedItem = effort
            }
        }

        val removeButton = JButton("×")

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(picker)
            add(JBLabel("Thinking:"))
            add(thinkingCombo)
            add(removeButton)
        }

        val memberRow = MemberRow(picker, thinkingCombo, removeButton, row, pendingThinkingVariant = thinkingVariant)
        memberRows.add(memberRow)

        removeButton.addActionListener {
            memberRows.remove(memberRow)
            membersPanel?.remove(row)
            membersPanel?.revalidate()
            membersPanel?.repaint()
        }

        membersPanel?.add(row)
        membersPanel?.revalidate()
        membersPanel?.repaint()
    }

    /**
     * Update the thinking-level combo box to show only the options supported by
     * the given model's [ProviderModel.variants]. Always includes DEFAULT; adds
     * matching [ThinkingEffort] entries sorted by ordinal (mirrors the chat
     * screen's [ThinkingSelector] logic).
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
        // Re-select the previous choice if still available, else DEFAULT
        combo.selectedItem = if (previous != null && available.contains(previous)) previous else ThinkingEffort.DEFAULT
    }

    /**
     * Refresh all member pickers with the latest [availableModels],
     * preserving the current model and thinking selection if they still exist.
     */
    private fun refreshMemberPickers() {
        for (row in memberRows) {
            val currentModel = row.modelPicker.getSelectedModel()
            val currentThinking = row.thinkingCombo.selectedItem as? ThinkingEffort
            row.modelPicker.setAvailableModels(availableModels)
            if (currentModel != null) {
                row.modelPicker.setSelectedModel(currentModel.providerID, currentModel.modelID)
            }
            updateThinkingOptions(row.thinkingCombo, row.modelPicker.getSelectedModel())
            // Re-apply the persisted thinking variant if it hasn't been applied yet
            // (currentThinking is null or DEFAULT and a pending variant exists).
            if (currentThinking == null || currentThinking == ThinkingEffort.DEFAULT) {
                if (row.pendingThinkingVariant.isNotBlank()) {
                    val effort = ThinkingEffort.entries.find { it.variant == row.pendingThinkingVariant }
                    if (effort != null) {
                        row.thinkingCombo.selectedItem = effort
                    }
                    // Clear only after a successful apply; keep it if the model still
                    // doesn't support the variant so a later refresh can retry.
                    if (effort != null) {
                        row.pendingThinkingVariant = ""
                    }
                }
            } else {
                row.thinkingCombo.selectedItem = currentThinking
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeAgentSettingsState.getInstance()
        if (enableCodingAssistantCheckbox?.isSelected != settings.enableCodingAssistant) return true
        if (enableCouncilCheckbox?.isSelected != settings.enableCouncil) return true

        val currentAllowed = buildAllowedAgentsList()
        if (currentAllowed != settings.taskAllowedAgents.toSet()) return true

        // Compare council members (only valid members are persisted)
        val currentMembers = memberRows.mapNotNull { row ->
            val selected = row.modelPicker.getSelectedModel()
            if (selected != null && selected.providerID.isNotBlank() && selected.modelID.isNotBlank()) {
                val variant = (row.thinkingCombo.selectedItem as? ThinkingEffort)?.variant ?: ""
                Triple(selected.providerID, selected.modelID, variant)
            } else null
        }
        val settingsMembers = settings.councilMembers.map {
            Triple(it.providerID, it.modelID, it.thinkingVariant)
        }
        if (currentMembers != settingsMembers) return true

        return false
    }

    override fun apply() {
        val settings = OpenCodeAgentSettingsState.getInstance()
        settings.enableCodingAssistant = enableCodingAssistantCheckbox?.isSelected ?: true
        settings.enableCouncil = enableCouncilCheckbox?.isSelected ?: false
        settings.taskAllowedAgents = ArrayList(buildAllowedAgentsList().toList())

        // Save council members (only valid ones are persisted)
        val members = ArrayList<CouncilMember>()
        // Dedup by (providerID, modelID, thinkingVariant) — mirrors loadState's
        // dedup contract. Without this, a user who adds the same model twice in
        // the UI persists duplicates that cause the council prompt to emit
        // duplicate member lines until the next reload (loadState dedups on load).
        val seen = mutableSetOf<Triple<String, String, String>>()
        for (row in memberRows) {
            val selected = row.modelPicker.getSelectedModel()
            if (selected != null && selected.providerID.isNotBlank() && selected.modelID.isNotBlank()) {
                val variant = (row.thinkingCombo.selectedItem as? ThinkingEffort)?.variant ?: ""
                val key = Triple(selected.providerID, selected.modelID, variant)
                if (seen.add(key)) {
                    members.add(CouncilMember(selected.providerID, selected.modelID, variant))
                }
            }
        }
        settings.councilMembers = members
    }

    override fun reset() {
        val settings = OpenCodeAgentSettingsState.getInstance()
        enableCodingAssistantCheckbox?.isSelected = settings.enableCodingAssistant
        enableCouncilCheckbox?.isSelected = settings.enableCouncil
        exploreCheckbox?.isSelected = "explore" in settings.taskAllowedAgents
        generalCheckbox?.isSelected = "general" in settings.taskAllowedAgents
        councilCheckbox?.isSelected = "council" in settings.taskAllowedAgents

        // Clear and rebuild member rows
        memberRows.clear()
        membersPanel?.removeAll()
        for (member in settings.councilMembers) {
            addMemberRow(member.providerID, member.modelID, member.thinkingVariant)
        }
        if (memberRows.isEmpty()) {
            addMemberRow("", "")
        }
        membersPanel?.revalidate()
        membersPanel?.repaint()
    }

    override fun disposeUIResources() {
        // Null out Swing component references so the async fetchAvailableModels
        // callback (which checks panel?.isDisplayable) does not update a
        // disposed panel. Without this, the coroutine launched in
        // fetchAvailableModels could call refreshMemberPickers on disposed
        // components if the settings dialog is closed while the fetch is
        // still in flight.
        panel = null
        membersPanel = null
        memberRows.clear()
        enableCodingAssistantCheckbox = null
        enableCouncilCheckbox = null
        exploreCheckbox = null
        generalCheckbox = null
        councilCheckbox = null
        addMemberButton = null
        restartHintLabel = null
    }

    private fun buildAllowedAgentsList(): Set<String> {
        val result = mutableSetOf<String>()
        if (exploreCheckbox?.isSelected == true) result.add("explore")
        if (generalCheckbox?.isSelected == true) result.add("general")
        if (councilCheckbox?.isSelected == true) result.add("council")
        return result
    }

    /**
     * Find the active project for accessing the OpenCodeService.
     * Mirrors the pattern in OpenCodeMcpConfigurable.
     */
    private fun getActiveProject(): com.intellij.openapi.project.Project? {
        val focused = focusedProject
        if (focused != null && !focused.isDisposed) return focused
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }

    companion object {
        /**
         * The currently focused project, set by ChatToolWindowFactory or a focus
         * listener. Falls back to the first open project when null.
         */
        @Volatile
        var focusedProject: com.intellij.openapi.project.Project? = null
    }
}

// ── ModelPickerComboBox ────────────────────────────────────────────────────

/**
 * A searchable, provider-grouped model picker combo box for Swing settings panels.
 *
 * Mirrors the model picker in the main chat screen using the same [DropdownItem]
 * sealed type ([DropdownItem.ProviderHeader] section headers +
 * [DropdownItem.ModelItem] entries). Models are grouped by provider name with
 * non-selectable section headers. A built-in search field filters models by
 * display name or provider name.
 *
 * When no models are available (server not running), a placeholder text field
 * is shown so the user can type a `providerID/modelID` pair manually.
 */
internal class ModelPickerComboBox : JPanel(BorderLayout()) {

    private val allItems: MutableList<DropdownItem> = mutableListOf()
    private var filteredItems: MutableList<DropdownItem> = mutableListOf()
    private var selectedModel: ProviderModel? = null

    /** Called when the user selects a model from the dropdown. */
    var onModelSelected: ((ProviderModel?) -> Unit)? = null

    /** The button that shows the current selection and opens the dropdown. */
    private val dropdownButton = JButton("Select a model...").apply {
        horizontalAlignment = SwingConstants.LEFT
        toolTipText = "Click to select a model"
    }

    /** Search field inside the popup. */
    private val searchField = JBTextField().apply {
        columns = 25
        toolTipText = "Search models by name or provider"
    }

    private var suppressFilter = false

    private val listModel = object : AbstractListModel<DropdownItem>() {
        override fun getSize(): Int = filteredItems.size
        override fun getElementAt(index: Int): DropdownItem = filteredItems[index]
        fun refresh() {
            fireContentsChanged(this, 0, size - 1)
        }
    }

    private val list = JList(listModel).apply {
        cellRenderer = DropdownItemRenderer()
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
    }

    private val popup = JPopupMenu().apply {
        layout = java.awt.BorderLayout()
        add(searchField, java.awt.BorderLayout.NORTH)
        add(JScrollPane(list).apply { preferredSize = Dimension(300, 200) }, java.awt.BorderLayout.CENTER)
    }

    init {
        // Button click opens the dropdown
        dropdownButton.addActionListener {
            applyFilter()
            popup.show(dropdownButton, 0, dropdownButton.height)
            searchField.requestFocusInWindow()
        }

        // Search filters the list
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                applyFilter()
            }

            override fun removeUpdate(e: DocumentEvent) {
                applyFilter()
            }

            override fun changedUpdate(e: DocumentEvent) {
                applyFilter()
            }
        })

        // Enter in search selects first match
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val firstModel =
                        filteredItems.firstOrNull { it is DropdownItem.ModelItem } as? DropdownItem.ModelItem
                    if (firstModel != null) {
                        selectModel(firstModel.model)
                        popup.isVisible = false
                    }
                }
            }
        })

        // List selection — click selects and closes popup
        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val item = list.selectedValue as? DropdownItem.ModelItem
                if (item != null) {
                    selectModel(item.model)
                    popup.isVisible = false
                }
            }
        }

        add(dropdownButton, java.awt.BorderLayout.CENTER)
    }

    /** Test-only accessor. */
    internal fun allItemsForTest(): List<DropdownItem> = allItems.toList()

    /** Test-only: simulate selecting from the filtered list by index. */
    internal fun selectFromListForTest(index: Int) {
        val item = filteredItems.getOrNull(index) as? DropdownItem.ModelItem ?: return
        selectModel(item.model)
    }

    /**
     * Set the list of available models. Rebuilds the grouped [DropdownItem] list.
     */
    fun setAvailableModels(models: List<ProviderModel>) {
        allItems.clear()
        val grouped = models.groupBy { it.providerID }
        for ((providerID, providerModels) in grouped) {
            val providerName = providerModels.firstOrNull()?.displayName?.substringBefore(" / ")?.trim()
                ?: providerID
            allItems.add(DropdownItem.ProviderHeader(providerName))
            for (model in providerModels.sortedBy { it.modelID }) {
                val modelName = model.displayName.substringAfter(" / ").trim().ifBlank { model.modelID }
                allItems.add(
                    DropdownItem.ModelItem(
                        model = model,
                        providerName = providerName,
                        modelName = modelName,
                        isFavorite = false,
                        contextWindowLabel = if (model.contextWindow > 0) formatContextWindow(model.contextWindow) else "",
                    )
                )
            }
        }
        applyFilter()
        // Re-select current model if now in list (async model arrival)
        val current = selectedModel
        if (current != null) {
            val match = allItems.mapNotNull { (it as? DropdownItem.ModelItem)?.model }
                .find { it.providerID == current.providerID && it.modelID == current.modelID }
            if (match != null) selectModel(match)
        }
    }

    fun getSelectedModel(): ProviderModel? = selectedModel

    fun setSelectedModel(providerID: String, modelID: String) {
        val match = allItems.mapNotNull { (it as? DropdownItem.ModelItem)?.model }
            .find { it.providerID == providerID && it.modelID == modelID }
        if (match != null) {
            selectModel(match)
        } else {
            selectedModel = ProviderModel(providerID, modelID, "$providerID / $modelID")
            dropdownButton.text = "$providerID / $modelID"
            onModelSelected?.invoke(selectedModel)
        }
    }

    private fun selectModel(model: ProviderModel) {
        selectedModel = model
        suppressFilter = true
        searchField.text = model.displayName
        suppressFilter = false
        dropdownButton.text = model.displayName
        onModelSelected?.invoke(model)
    }

    private fun applyFilter() {
        if (suppressFilter) return
        val query = searchField.text.trim().lowercase()
        filteredItems.clear()
        if (query.isEmpty()) {
            filteredItems.addAll(allItems)
        } else {
            var currentHeader: DropdownItem.ProviderHeader? = null
            var headerHasMatch = false
            for (item in allItems) {
                when (item) {
                    is DropdownItem.ProviderHeader -> {
                        if (headerHasMatch && currentHeader != null) filteredItems.add(currentHeader)
                        currentHeader = item
                        headerHasMatch = false
                    }

                    is DropdownItem.ModelItem -> {
                        if (item.model.providerID.lowercase().contains(query) ||
                            item.model.modelID.lowercase().contains(query) ||
                            item.model.displayName.lowercase().contains(query) ||
                            item.providerName.lowercase().contains(query) ||
                            item.modelName.lowercase().contains(query)
                        ) {
                            headerHasMatch = true
                            filteredItems.add(item)
                        }
                    }
                }
            }
            if (headerHasMatch && currentHeader != null) filteredItems.add(currentHeader)
        }
        listModel.refresh()
    }

    private fun formatContextWindow(tokens: Int): String =
        if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else if (tokens >= 1000) "${tokens / 1000}K" else "$tokens"
}

/**
 * Renderer for [DropdownItem] list cells — bold headers, indented model names.
 */
private class DropdownItemRenderer : ListCellRenderer<DropdownItem> {
    private val headerLabel = JLabel().apply {
        font = font.deriveFont(Font.BOLD)
        border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        isOpaque = true
    }
    private val modelLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(1, 18, 1, 6)
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out DropdownItem>?, value: DropdownItem?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean,
    ): Component = when (value) {
        is DropdownItem.ProviderHeader -> {
            headerLabel.text = value.name
            headerLabel.background = UIManager.getColor("Panel.background") ?: JBColor.background()
            headerLabel
        }

        is DropdownItem.ModelItem -> {
            val sb = StringBuilder(value.modelName)
            if (value.contextWindowLabel.isNotEmpty()) sb.append("  (${value.contextWindowLabel})")
            modelLabel.text = sb.toString()
            if (isSelected) {
                modelLabel.background = UIManager.getColor("List.selectionBackground") ?: JBColor(0x3875D7, 0x2D5A8E)
                modelLabel.foreground = UIManager.getColor("List.selectionForeground") ?: Color.WHITE
            } else {
                modelLabel.background = JBColor.background()
                modelLabel.foreground = JBColor.foreground()
            }
            modelLabel
        }

        null -> JLabel("")
    }
}

/**
 * Renderer for [ThinkingEffort] combo box — shows label.
 */
private class ThinkingEffortRenderer : ListCellRenderer<ThinkingEffort> {
    private val label = JLabel().apply {
        border = BorderFactory.createEmptyBorder(1, 4, 1, 4)
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out ThinkingEffort>?, value: ThinkingEffort?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        label.text = value?.label ?: ""
        if (isSelected) {
            label.background = UIManager.getColor("List.selectionBackground") ?: JBColor(0x3875D7, 0x2D5A8E)
            label.foreground = UIManager.getColor("List.selectionForeground") ?: Color.WHITE
        } else {
            label.background = JBColor.background()
            label.foreground = JBColor.foreground()
        }
        return label
    }
}
