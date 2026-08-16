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
import com.opencode.acp.config.AgentConstants
import com.opencode.acp.config.AgentDefinition
import com.opencode.acp.config.AgentRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
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

private val logger = KotlinLogging.logger {}

/**
 * Child configurable for Custom Agents settings.
 * Appears as "Agents" under the "Sigil" settings node.
 *
 * v2 (TDD `docs/tdd/custom-agents-v2.md` §4.7.2C): the UI is **data-driven** —
 * it iterates [AgentRegistry.ALL_AGENTS] to build enable-checkboxes, the
 * delegation allowlist, and per-agent model rows dynamically instead of v1's
 * hardcoded widgets. Adding a new agent = add a [AgentRegistry] entry + the
 * settings field; no new UI wiring.
 *
 * Exposes:
 *  - One enable checkbox per [AgentRegistry] agent (coding-assistant,
 *    council, coder, researcher, planner, tester, reviewer).
 *  - Delegation allowlist (which agents can be invoked via the `task` tool):
 *    built-ins (explore, general) + all registry subagents.
 *  - Council member models (v1, unchanged): a dynamic list of model pickers.
 *  - Per-agent model pickers for v2 subagents with
 *    [AgentDefinition.hasPerAgentModel] == true (coder, researcher, planner,
 *    tester, reviewer). null/empty = inherit parent's model (v1 default).
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

    /** Tracks the async model-fetch coroutine so it can be cancelled in disposeUIResources. */
    private var fetchModelsJob: kotlinx.coroutines.Job? = null

    /**
     * When true, the enable-checkbox ItemListener suppresses its auto-check of
     * the allowlist checkbox. Set during `reset()`/`apply()` programmatic
     * `setSelected` calls so the listener does not clobber the explicitly
     * restored allowlist state. The listener only fires on genuine user clicks
     * (when this flag is false).
     */
    private var suppressAutoCheck = false

    /** Per-agent enable checkboxes, keyed by agent name. */
    private val enableCheckboxes: MutableMap<String, JBCheckBox> = mutableMapOf()

    /** Delegation allowlist checkboxes, keyed by agent name (or "explore"/"general"). */
    private val allowlistCheckboxes: MutableMap<String, JBCheckBox> = mutableMapOf()

    /** Per-agent model picker rows (only for agents with hasPerAgentModel=true). */
    private val agentModelRows: MutableMap<String, AgentModelRow> = mutableMapOf()

    // Council member rows (v1, unchanged)
    private val memberRows: MutableList<MemberRow> = mutableListOf()
    private var membersPanel: JPanel? = null
    private var addMemberButton: JButton? = null

    // Available models from connected providers (fetched asynchronously)
    private var availableModels: List<ProviderModel> = emptyList()

    // Restart hint
    private var restartHintLabel: JBLabel? = null

    /**
     * Helper pairing the model picker and thinking-level dropdown with the
     * remove button and the row panel. Used for council member rows.
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

    /**
     * Helper for per-agent model rows (v2). One row per
     * [AgentDefinition.hasPerAgentModel] agent. The agent name is fixed (keyed
     * in [agentModelRows]); the row only carries the picker + thinking combo.
     */
    private data class AgentModelRow(
        val agentName: String,
        val modelPicker: ModelPickerComboBox,
        val thinkingCombo: JComboBox<ThinkingEffort>,
        val rowPanel: JPanel,
        var pendingThinkingVariant: String = "",
    )

    override fun getDisplayName(): String = "Agents"

    override fun createComponent(): JComponent {
        val settings = OpenCodeAgentSettingsState.getInstance()

        // ── Per-agent enable toggles (iterate registry) ────────────────
        for (def in AgentRegistry.ALL_AGENTS) {
            val label = "Enable " + prettyName(def.name)
            val cb = JBCheckBox(label, isEnabledInSettings(def.name, settings)).apply {
                toolTipText = def.description
            }
            enableCheckboxes[def.name] = cb
            // Auto-check the delegation allowlist checkbox when an enable
            // toggle flips ON (Q3 — mirrors v1 council behavior). Without
            // this, a user who enables `coder` but forgets to check the
            // delegation box leaves coding-assistant unable to reach it.
            // The user can still uncheck manually.
            // Auto-check the delegation allowlist checkbox when an enable
            // toggle flips ON (Q3). The reverse is INTENTIONAL: unchecking
            // an enable toggle does NOT uncheck the allowlist -- the
            // allowlist is sticky so a user who temporarily disables an
            // agent keeps the delegation entry for quick re-enable. The
            // emitted YAML is still correct (buildTaskPermissionYaml gates
            // each allowlisted agent on its enable flag at emit time).
            cb.addItemListener { e ->
                if (e.stateChange == java.awt.event.ItemEvent.SELECTED && !suppressAutoCheck) {
                    allowlistCheckboxes[def.name]?.isSelected = true
                }
            }
        }

        // ── Delegation allowlist (built-ins + registry subagents) ──────
        // Built-in agents first (explore, general), then all registry agents
        // EXCEPT coding-assistant (the primary — it's the delegator, not a
        // delegatee).
        val allowlistNames = listOf("explore", "general") +
                AgentRegistry.ALL_NAMES.filter { it != AgentConstants.CODING_ASSISTANT_AGENT_NAME }
        for (name in allowlistNames) {
            val cb = JBCheckBox(name, name in settings.taskAllowedAgents).apply {
                toolTipText = allowlistTooltip(name)
            }
            allowlistCheckboxes[name] = cb
        }

        // ── Council members panel (v1, unchanged) ──────────────────────
        membersPanel = JPanel(java.awt.GridLayout(0, 1, 4, 4))
        memberRows.clear()
        for (member in settings.councilMembers) {
            // Pass thinkingVariant so persisted thinking levels are restored on
            // initial settings-panel open (not just on reset()). Without this,
            // apply() would overwrite the persisted variant with DEFAULT.
            addMemberRow(member.providerID, member.modelID, member.thinkingVariant)
        }
        // If no members, add one empty row so the user has a starting point
        if (memberRows.isEmpty()) {
            addMemberRow("", "")
        }

        addMemberButton = JButton("+ Add Member").apply {
            addActionListener { addMemberRow("", "") }
        }

        // ── Per-agent model pickers (v2 — hasPerAgentModel agents only) ─
        // One row per agent with hasPerAgentModel=true. Built dynamically
        // from the registry; coder/researcher/planner/tester/reviewer each
        // get a row.
        // coding-assistant and council do NOT (primary uses chat's active
        // model; council has its own per-MEMBER list).
        for (def in AgentRegistry.ALL_AGENTS.filter { it.hasPerAgentModel }) {
            val binding = settings.modelFor(def.name)
            val providerID = binding?.providerID ?: ""
            val modelID = binding?.modelID ?: ""
            val variant = binding?.thinkingVariant ?: ""
            val row = addAgentModelRow(def.name, providerID, modelID, variant)
            agentModelRows[def.name] = row
        }

        restartHintLabel = JBLabel("ℹ Changes take effect on next server restart").apply {
            foreground = JBColor.GRAY
        }

        // ── Assemble panel ─────────────────────────────────────────────
        val builder = FormBuilder.createFormBuilder()

        // Enable toggles + descriptions (iterate registry in order)
        for (def in AgentRegistry.ALL_AGENTS) {
            builder.addComponent(enableCheckboxes[def.name]!!)
            builder.addComponent(JBLabel(def.description).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
        }

        builder.addVerticalGap(8)
            .addSeparator()
            .addVerticalGap(4)
            .addComponent(JBLabel("Delegation Allowlist (applies to coding-assistant)").apply {
                font = font.deriveFont(Font.BOLD)
            })
            .addComponent(JBLabel("Which agents can be invoked via the task tool. Built-in agents (explore, general) and plugin-defined subagents. Subagents auto-appear here when their enable toggle is on.").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })

        // Allowlist panel (horizontal checkboxes)
        val allowlistPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            for (name in allowlistNames) {
                add(allowlistCheckboxes[name]!!)
            }
        }
        builder.addComponent(allowlistPanel)

        // Per-agent model pickers (v2)
        if (agentModelRows.isNotEmpty()) {
            builder.addVerticalGap(8)
                .addSeparator()
                .addVerticalGap(4)
                .addComponent(JBLabel("Per-Agent Models (v2 subagents)").apply {
                    font = font.deriveFont(Font.BOLD)
                })
                .addComponent(JBLabel("Pin each subagent to a specific model. Empty = inherit the chat's active model (v1 default behavior).").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(font.size2D - 1f)
                })
            for (def in AgentRegistry.ALL_AGENTS.filter { it.hasPerAgentModel }) {
                val row = agentModelRows[def.name] ?: continue
                builder.addComponent(JBLabel(prettyName(def.name)).apply {
                    font = font.deriveFont(Font.BOLD)
                })
                builder.addComponent(row.rowPanel)
            }
        }

        // Council members (v1, unchanged)
        builder.addVerticalGap(8)
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

        panel = builder.panel

        // Fetch available models from the OpenCode server asynchronously
        fetchAvailableModels()

        return panel!!
    }

    /**
     * Pretty-print an agent name for the UI: "coding-assistant" → "Coding Assistant".
     */
    private fun prettyName(name: String): String =
        name.replace("-", " ").replaceFirstChar { it.uppercase() }

    /**
     * Tooltip for an allowlist checkbox.
     */
    private fun allowlistTooltip(name: String): String = when (name) {
        "explore" -> "Built-in read-only agent for fast codebase search and pattern matching."
        "general" -> "Built-in general-purpose agent for running multiple units of work in parallel."
        AgentConstants.COUNCIL_AGENT_NAME -> "Multi-model council review agent. Only available when Council is enabled above."
        AgentConstants.CODER_AGENT_NAME -> "Scoped implementation subagent for fan-out implementation."
        AgentConstants.RESEARCHER_AGENT_NAME -> "Semantic codebase investigator (read-only, PSI tools)."
        AgentConstants.PLANNER_AGENT_NAME -> "Task decomposer that produces a chunk plan for parallel coder fan-out."
        AgentConstants.TESTER_AGENT_NAME -> "Scoped test implementer that mirrors existing test patterns."
        AgentConstants.REVIEWER_AGENT_NAME -> "Adversarial code reviewer that writes .review/<path>.json findings. Identifies issues, never fixes."
        else -> "Agent: $name"
    }

    /**
     * Read the enable flag for [name] from [settings].
     *
     * Delegates to the centralized [AgentDefinition.enableFlagGetter] via
     * [AgentRegistry.enableFlagFor]. This replaces a hand-maintained `when`
     * that was duplicated across four call sites; a missing arm was a SILENT
     * failure (permanently-off checkbox). The mapping now lives once on each
     * [AgentDefinition] entry and cannot be forgotten.
     */
    private fun isEnabledInSettings(name: String, s: OpenCodeAgentSettingsState): Boolean =
        AgentRegistry.enableFlagFor(name, s)

    /**
     * Write the enable flag for [name] into [settings].
     *
     * Delegates to the centralized [AgentDefinition.enableFlagSetter]. This
     * replaces a hand-maintained `when` whose `else -> {}` arm SILENTLY
     * DISCARDED the user's Apply choice for any agent missing from the
     * expression — the checkbox read correctly but the value never
     * persisted. The setter is now wired once at registry construction.
     */
    private fun setEnabledInSettings(name: String, s: OpenCodeAgentSettingsState, value: Boolean) {
        AgentRegistry.byNameOrNull(name)?.enableFlagSetter?.invoke(s, value)
    }

    /**
     * Fetch the list of available models from connected providers via the
     * OpenCodeService. Populates [availableModels] and refreshes all member
     * pickers + per-agent model pickers on the EDT.
     */
    private fun fetchAvailableModels() {
        val project = getActiveProject() ?: return
        if (project.isDisposed) return
        val service = try {
            project.service<OpenCodeService>()
        } catch (e: Exception) {
            return
        }

        fetchModelsJob = service.scope.launch {
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

                // Compute the modality AFTER the async listProviders() call,
                // NOT before it. At createComponent() time the panel is not
                // yet added to the modal Settings dialog, so stateForComponent
                // returns NON_BLOCKING — and invokeLater(callback,
                // NON_BLOCKING) defers until the app is non-blocking, which
                // NEVER happens while the modal Settings dialog is open. The
                // callback (availableModels = models) would never fire and the
                // pickers would stay empty. By computing the modality here
                // (after the ~1s fetch, when the panel is displayable and
                // inside the dialog), stateForComponent returns the dialog's
                // actual modality so invokeLater fires immediately.
                val modality = (panel ?: this@OpenCodeAgentConfigurable.panel)?.let {
                    ModalityState.stateForComponent(it)
                } ?: run {
                    logger.debug { "[ACP] AgentConfigurable: panel was null after fetch — models not loaded" }
                    return@launch
                }
                ApplicationManager.getApplication().invokeLater({
                    if (panel?.isDisplayable != true) return@invokeLater
                    availableModels = models
                    refreshMemberPickers()
                    refreshAgentModelPickers()
                }, modality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] AgentConfigurable: failed to fetch available models" }
                val hint = restartHintLabel
                if (hint != null) {
                    // Compute the modality from the panel here too (after the
                    // failed fetch) so the error hint shows inside the modal
                    // Settings dialog. stateForComponent on a non-displayable
                    // panel returns NON_BLOCKING, which would defer the
                    // callback forever inside a modal dialog — so fall back to
                    // defaultModalityState() if the panel is gone.
                    val errModality =
                        (panel ?: this@OpenCodeAgentConfigurable.panel)?.let {
                            ModalityState.stateForComponent(it)
                        } ?: ModalityState.defaultModalityState()
                    ApplicationManager.getApplication().invokeLater({
                        if (panel?.isDisplayable == true) {
                            hint.text = "[!] Failed to load models - retry by reopening settings"
                            hint.foreground = JBColor.RED
                        }
                    }, errModality)
                }
            }
        }
    }

    // ── Council member rows (v1, unchanged) ─────────────────────────────

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

    // ── Per-agent model rows (v2) ───────────────────────────────────────

    /**
     * Add a per-agent model row for [agentName] with the persisted model
     * (providerID/modelID/variant), or empty if no model configured (inherit).
     *
     * Mirrors [addMemberRow] but the row is fixed to one agent (no remove
     * button — each agent has exactly one model slot).
     */
    private fun addAgentModelRow(
        agentName: String,
        providerID: String,
        modelID: String,
        thinkingVariant: String
    ): AgentModelRow {
        val picker = ModelPickerComboBox()
        picker.setAvailableModels(availableModels)

        val thinkingCombo = JComboBox<ThinkingEffort>()
        thinkingCombo.renderer = ThinkingEffortRenderer()

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

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(picker)
            add(JBLabel("Thinking:"))
            add(thinkingCombo)
            add(JBLabel("(empty = inherit)").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
        }

        return AgentModelRow(agentName, picker, thinkingCombo, row, pendingThinkingVariant = thinkingVariant)
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
     * Refresh all council member pickers with the latest [availableModels],
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

    /**
     * Refresh all per-agent model pickers with the latest [availableModels],
     * preserving the current model and thinking selection if they still exist.
     *
     * Mirrors [refreshMemberPickers] but iterates [agentModelRows].
     */
    private fun refreshAgentModelPickers() {
        for (row in agentModelRows.values) {
            val currentModel = row.modelPicker.getSelectedModel()
            val currentThinking = row.thinkingCombo.selectedItem as? ThinkingEffort
            row.modelPicker.setAvailableModels(availableModels)
            if (currentModel != null) {
                row.modelPicker.setSelectedModel(currentModel.providerID, currentModel.modelID)
            }
            updateThinkingOptions(row.thinkingCombo, row.modelPicker.getSelectedModel())
            if (currentThinking == null || currentThinking == ThinkingEffort.DEFAULT) {
                if (row.pendingThinkingVariant.isNotBlank()) {
                    val effort = ThinkingEffort.entries.find { it.variant == row.pendingThinkingVariant }
                    if (effort != null) {
                        row.thinkingCombo.selectedItem = effort
                    }
                    if (effort != null) {
                        row.pendingThinkingVariant = ""
                    }
                }
            } else {
                row.thinkingCombo.selectedItem = currentThinking
            }
        }
    }

    // ── apply / reset / isModified ─────────────────────────────────────

    override fun isModified(): Boolean {
        val settings = OpenCodeAgentSettingsState.getInstance()

        // Enable toggles
        for (def in AgentRegistry.ALL_AGENTS) {
            if (enableCheckboxes[def.name]?.isSelected != isEnabledInSettings(def.name, settings)) return true
        }

        // Allowlist
        val currentAllowed = buildAllowedAgentsList()
        if (currentAllowed != settings.taskAllowedAgents.toSet()) return true

        // Council members (only valid members are persisted)
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

        // Per-agent models: only compare bindings for agents that have a UI
        // row (agentModelRows.keys, i.e. hasPerAgentModel==true agents).
        // Bindings for agents WITHOUT a UI row (coding-assistant, council,
        // future agents) are preserved by apply() and must NOT be compared
        // here -- otherwise a stale binding for coding-assistant (from a
        // prior version or hand-edited XML) would make isModified() return
        // true, forcing a data-loss Apply that drops the binding.
        val uiAgentNames = agentModelRows.keys
        val currentAgentModels = buildAgentModelBindings()
        val settingsAgentModels = settings.agentModels
            .filter { it.agentName in uiAgentNames && it.hasModel() }
            .associateBy { it.agentName }
        if (currentAgentModels.size != settingsAgentModels.size) return true
        for ((name, binding) in currentAgentModels) {
            val existing = settingsAgentModels[name]
            if (existing == null) return true
            val existingModel = existing.model
            val newModel = binding.model
            // Both existingModel and newModel are guaranteed non-null here:
            // settingsAgentModels is filtered by hasModel() (line 651), and
            // buildAgentModelBindings() only emits entries with a non-blank
            // selected model (line 720-721). The dead null-branches that were
            // here have been removed to avoid misleading future maintainers.
            // The `!!` asserts document the non-null guarantee from the filters.
            if (existingModel!!.providerID != newModel!!.providerID ||
                existingModel.modelID != newModel.modelID ||
                existingModel.thinkingVariant != newModel.thinkingVariant
            ) return true
        }

        return false
    }

    override fun apply() {
        val settings = OpenCodeAgentSettingsState.getInstance()

        // Enable toggles
        for (def in AgentRegistry.ALL_AGENTS) {
            setEnabledInSettings(def.name, settings, enableCheckboxes[def.name]?.isSelected ?: def.defaultEnabled)
        }

        // Allowlist
        settings.taskAllowedAgents = java.util.ArrayList(buildAllowedAgentsList().toList())

        // Save council members (only valid ones are persisted)
        val members = java.util.ArrayList<CouncilMember>()
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

        // Save per-agent models (v2). MERGE: preserve bindings for agents
        // WITHOUT a UI row (coding-assistant, council -- hasPerAgentModel==false),
        // then add/overwrite with the UI bindings from buildAgentModelBindings().
        // This prevents apply() from silently dropping non-UI bindings that
        // may exist from a prior plugin version or hand-edited XML.
        val uiBindings = buildAgentModelBindings()
        val uiAgentNames = agentModelRows.keys
        settings.agentModels =
            java.util.ArrayList(mergeAgentModelBindings(settings.agentModels, uiBindings.values.toList(), uiAgentNames))
    }

    /**
     * Read the per-agent model pickers and build a `Map<agentName, AgentModelBinding>`.
     *
     * Agents with an empty picker (no model selected) are omitted (inherit
     * parent's model — no binding).
     */
    private fun buildAgentModelBindings(): Map<String, AgentModelBinding> {
        val result = LinkedHashMap<String, AgentModelBinding>()
        for ((agentName, row) in agentModelRows) {
            val selected = row.modelPicker.getSelectedModel()
            if (selected != null && selected.providerID.isNotBlank() && selected.modelID.isNotBlank()) {
                val variant = (row.thinkingCombo.selectedItem as? ThinkingEffort)?.variant ?: ""
                result[agentName] =
                    AgentModelBinding(agentName, CouncilMember(selected.providerID, selected.modelID, variant))
            }
        }
        return result
    }

    override fun reset() {
        val settings = OpenCodeAgentSettingsState.getInstance()

        // Enable toggles. suppressAutoCheck prevents the enable-checkbox
        // ItemListener from clobbering the allowlist restore below (the
        // listener fires synchronously on programmatic setSelected).
        suppressAutoCheck = true
        try {
            for (def in AgentRegistry.ALL_AGENTS) {
                enableCheckboxes[def.name]?.isSelected = isEnabledInSettings(def.name, settings)
            }
        } finally {
            suppressAutoCheck = false
        }

        // Allowlist
        for ((name, cb) in allowlistCheckboxes) {
            cb.isSelected = name in settings.taskAllowedAgents
        }

        // Clear and rebuild council member rows
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

        // Reset per-agent model pickers
        for (def in AgentRegistry.ALL_AGENTS.filter { it.hasPerAgentModel }) {
            val row = agentModelRows[def.name] ?: continue
            val binding = settings.modelFor(def.name)
            row.modelPicker.setAvailableModels(availableModels)
            if (binding != null) {
                row.modelPicker.setSelectedModel(binding.providerID, binding.modelID)
                updateThinkingOptions(row.thinkingCombo, row.modelPicker.getSelectedModel())
                if (binding.thinkingVariant.isNotBlank()) {
                    val effort = ThinkingEffort.entries.find { it.variant == binding.thinkingVariant }
                    if (effort != null) {
                        row.thinkingCombo.selectedItem = effort
                    }
                }
            } else {
                // Clear the picker — inherit
                row.modelPicker.setSelectedModel("", "")
                updateThinkingOptions(row.thinkingCombo, null)
            }
        }
    }

    override fun disposeUIResources() {
        // Cancel the async model-fetch coroutine so it does not outlive the
        // configurable (structured concurrency). Without this, repeated
        // open/close of the Settings dialog while the server is slow leaks
        // coroutines that hang on listProviders() until project close.
        fetchModelsJob?.cancel()
        fetchModelsJob = null
        // Null out Swing component references so the async fetchAvailableModels
        // callback (which checks panel?.isDisplayable) does not update a
        // disposed panel. The cancel() above stops new invocations; the null
        // guards here stop any callback already past the await.
        panel = null
        membersPanel = null
        memberRows.clear()
        agentModelRows.clear()
        enableCheckboxes.clear()
        allowlistCheckboxes.clear()
        addMemberButton = null
        restartHintLabel = null
    }

    private fun buildAllowedAgentsList(): Set<String> {
        val result = mutableSetOf<String>()
        for ((name, cb) in allowlistCheckboxes) {
            if (cb.isSelected) result.add(name)
        }
        return result
    }

    /**
     * Find the active project for accessing the OpenCodeService.
     * Mirrors the pattern in OpenCodeMcpConfigurable.
     */
    /**
     * Find the active project for accessing the OpenCodeService.
     *
     * Agent settings are APPLICATION-scoped (@Service(Service.Level.APP)) so
     * the settings dialog edits a shared state. The model list, however, is
     * fetched from an arbitrary open project OpenCodeService (best-effort:
     * `focusedProject` if set by ChatToolWindowFactory, else the first open
     * project). If two projects are open with different providers, the user
     * may see models from the "wrong" project. This is a known cosmetic
     * limitation of app-scoped agent settings; the agent files themselves
     * only store providerID/modelID (resolved server-side), so cross-project
     * model availability does not corrupt the persisted config.
     */
    private fun getActiveProject(): com.intellij.openapi.project.Project? {
        val focused = focusedProject
        if (focused != null && !focused.isDisposed) return focused
        val fallback = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        if (fallback != null) {
            logger.debug { "[ACP] AgentConfigurable: no focused project - using fallback open project '${fallback.name}' for model list (app-scoped settings may show models from a different project than the one the user opened Settings from)" }
        }
        return fallback
    }

    companion object {
        /**
         * Pure merge of per-agent model bindings: preserve non-UI bindings
         * (agentName NOT in [uiAgentNames]) then add/overwrite with the UI
         * bindings. Extracted from apply() so the merge logic is unit-testable
         * without the IntelliJ application context (review cmt_d8e9f0a1b2c3).
         *
         * **Dedup of preserved bindings:** the `existing` list may carry
         * duplicate agentNames (hand-edited XML or a prior plugin version
         * that bypassed loadState's dedup). Without dedup here, duplicates
         * for non-UI agents (coding-assistant, council) survive every Apply
         * (they are preserved again on the next merge) and accumulate in
         * persisted state. modelFor() uses `.find { ... }` (first wins) so
         * behavior is not broken today, but the bloat is real. Dedup
         * `preserved` by agentName (first wins) before concatenating,
         * mirroring the loadState dedup strategy. See review cmt_e5f6a7b8c9d0.
         *
         * @param existing the current settings.agentModels (may contain non-UI
         *   bindings from a prior plugin version or hand-edited XML; may
         *   contain duplicate agentNames among non-UI bindings)
         * @param uiBindings the bindings built from the UI rows
         *   (buildAgentModelBindings() result; one per agent by construction)
         * @param uiAgentNames the set of agent names that HAVE a UI row
         *   (agentModelRows.keys, i.e. hasPerAgentModel==true agents)
         * @return the merged list to assign to settings.agentModels
         */
        internal fun mergeAgentModelBindings(
            existing: List<AgentModelBinding>,
            uiBindings: List<AgentModelBinding>,
            uiAgentNames: Set<String>,
        ): List<AgentModelBinding> {
            // Preserve non-UI bindings, deduped by agentName (first wins).
            // AgentModelBinding has identity equals (no custom equals/hashCode),
            // so we dedup on the String agentName key, not via distinctBy { it }.
            val seen = mutableSetOf<String>()
            val preserved = existing.filter { it.agentName !in uiAgentNames }
                .filter { seen.add(it.agentName) }
            return preserved + uiBindings
        }

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
 *
 * Used by BOTH council member rows (v1) and per-agent model rows (v2).
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
        if (providerID.isBlank() && modelID.isBlank()) {
            // Clear the selection (used by reset to "inherit")
            selectedModel = null
            suppressFilter = true
            searchField.text = ""
            suppressFilter = false
            dropdownButton.text = "Select a model... (inherit)"
            onModelSelected?.invoke(null)
            return
        }
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
