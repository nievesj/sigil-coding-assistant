package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.DropdownItem
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.util.ChatColors
import com.opencode.acp.config.settings.OpenCodeSettingsState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.ComboPopup
import kotlin.math.roundToInt

class ControlBarComponent(
    private val onAgentChanged: (OpenCodeAgentInfo) -> Unit,
    private val onModelChanged: (ProviderModel) -> Unit,
    private val onThinkingChanged: (ThinkingEffort) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {
    private val agentCombo: JComboBox<Any>
    private val modelCombo: JComboBox<DropdownItem>
    private val thinkingCombo: JComboBox<ThinkingEffort>

    init {
        // Agent combo
        agentCombo = JComboBox<Any>().apply {
            renderer = ListCellRenderer { list, value, _, isSelected, _ ->
                JBLabel((value as? OpenCodeAgentInfo)?.name ?: "Select Agent").apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(12f).toFloat())
                    background = if (isSelected) list.selectionBackground else list.background
                    isOpaque = true
                }
            }
            addActionListener {
                (selectedItem as? OpenCodeAgentInfo)?.let(onAgentChanged)
            }
        }

        // Model combo with sealed DropdownItem hierarchy
        modelCombo = JComboBox<DropdownItem>().apply {
            renderer = ModelRenderer()

            // Action listener — convert selected DropdownItem.ModelItem back to ProviderModel
            addActionListener {
                val selected = selectedItem
                when (selected) {
                    is DropdownItem.ModelItem -> onModelChanged(selected.model)
                }
            }

            // Popup listener attaches star click handler when dropdown opens
            addPopupMenuListener(StarPopupListener())
        }

        // Thinking combo
        thinkingCombo = JComboBox(ThinkingEffort.entries.toTypedArray()).apply {
            renderer = ListCellRenderer { list, value, _, isSelected, _ ->
                JBLabel(value?.label ?: "Default").apply {
                    background = if (isSelected) list.selectionBackground else list.background
                    isOpaque = true
                }
            }
            addActionListener {
                (selectedItem as? ThinkingEffort)?.let(onThinkingChanged)
            }
        }

        add(JBLabel("Agent:"))
        add(agentCombo)
        add(JBLabel("Model:"))
        add(modelCombo)
        add(JBLabel("Thinking:"))
        add(thinkingCombo)
    }

    fun updateState(state: ControlBarState) {
        agentCombo.model = DefaultComboBoxModel(state.agents.toTypedArray())
        agentCombo.selectedItem = state.selectedAgent
        lastState = state

        val groupedItems = buildGroupedModelList(state.models)
        modelCombo.model = DefaultComboBoxModel(groupedItems.toTypedArray())

        // Match selectedModel across lists: DropdownItem.ModelItem.equals() compares by ProviderModel
        val match = groupedItems.find { it is DropdownItem.ModelItem && it.model == state.selectedModel }
        modelCombo.selectedItem = match

        thinkingCombo.selectedItem = state.thinkingEffort
        thinkingCombo.isEnabled = state.selectedModel?.reasoning == true
    }

    fun rebuildModelCombo() {
        val state = lastState ?: return
        val groupedItems = buildGroupedModelList(state.models)
        modelCombo.model = DefaultComboBoxModel(groupedItems.toTypedArray())
        val match = groupedItems.find { it is DropdownItem.ModelItem && it.model == state.selectedModel }
        modelCombo.selectedItem = match
    }

    private var lastState: ControlBarState? = null

    /**
     * Builds grouped dropdown list: favorites section first, then grouped by provider.
     */
    private fun buildGroupedModelList(models: List<ProviderModel>): List<DropdownItem> {
        if (models.isEmpty()) return emptyList()
        val settings = OpenCodeSettingsState.getInstance()

        // Clean stale favorites
        settings.cleanupStaleFavorites(models)

        val result = mutableListOf<DropdownItem>()

        // 1. Favorites section
        val favorites = models.filter { settings.isFavoriteModel(it.providerID, it.modelID) }
        if (favorites.isNotEmpty()) {
            result.add(DropdownItem.ProviderHeader("★ Favorites"))
            for (model in favorites) {
                val (provider, name) = parseDisplayName(model.displayName)
                result.add(DropdownItem.ModelItem(model, provider, name, isFavorite = true))
            }
        }

        // 2. Grouped by provider (non-favorites only)
        val nonFavorites = models.filter { !settings.isFavoriteModel(it.providerID, it.modelID) }
        var lastProvider: String? = null
        for (model in nonFavorites) {
            val providerName = model.displayName.substringBefore(" / ").trim()
            if (providerName != lastProvider) {
                result.add(DropdownItem.ProviderHeader(providerName))
                lastProvider = providerName
            }
            val modelName = model.displayName.substringAfter(" / ").trim()
            result.add(DropdownItem.ModelItem(model, "", modelName, isFavorite = false))
        }

        return result
    }

    /** Parse displayName with fallback for malformed values. */
    private fun parseDisplayName(displayName: String): Pair<String, String> {
        val parts = displayName.split(" / ", limit = 2)
        return if (parts.size == 2) Pair(parts[0].trim(), parts[1].trim())
        else Pair(displayName, displayName)
    }

    /** Escape HTML to prevent injection in renderer labels. */
    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ─────────────────────────────────────────────
    // Renderer
    // ─────────────────────────────────────────────

    /**
     * Renderer using a JPanel with name label (center) and star label (right).
     * The star label is interactive — clicks on it toggle favorites.
     */
    private inner class ModelRenderer : ListCellRenderer<DropdownItem> {
        private val headerLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
            foreground = ChatColors.textLink()
            border = JBUI.Borders.empty(
                JBUI.scale(6), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4)
            )
        }

        private val modelPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(
                JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4)
            )
            isOpaque = true
        }

        private val nameLabel = JBLabel()
        private val starLabel = JBLabel().apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(14f).toFloat())

            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(20))
        }

        init {
            modelPanel.add(nameLabel, BorderLayout.CENTER)
            modelPanel.add(starLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out DropdownItem>,
            value: DropdownItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            when (value) {
                is DropdownItem.ProviderHeader -> headerLabel.apply {
                    text = value.name
                    background = if (isSelected) list.selectionBackground else list.background
                    isOpaque = true
                    return this
                }

                is DropdownItem.ModelItem -> {
                    val displayText = if (value.isFavorite && value.providerName.isNotEmpty()) {
                        "${value.modelName} (${value.providerName})"
                    } else {
                        value.modelName
                    }
                    nameLabel.text = escapeHtml(displayText)
                    nameLabel.font = if (value.isFavorite) {
                        nameLabel.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(12f).toFloat())
                    } else {
                        nameLabel.font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(12f).toFloat())
                    }
                    nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

                    starLabel.text = if (value.isFavorite) "★" else "☆"
                    starLabel.foreground = if (value.isFavorite) {
                        if (index == hoveredStarIndex) ChatColors.starHover
                        else ChatColors.starFavorite
                    } else {
                        ChatColors.starMuted()
                    }

                    modelPanel.apply {
                        background = if (isSelected) list.selectionBackground else list.background
                    }
                    return modelPanel
                }

                else -> {
                    headerLabel.text = value?.toString() ?: ""
                    return headerLabel
                }
            }
        }
    }

    private var hoveredStarIndex: Int = -1

    // ─────────────────────────────────────────────
    // Star Click Detection via PopupMenuListener
    // ─────────────────────────────────────────────

    /**
     * Attaches star click/hover handlers to the popup's JList when it becomes visible.
     * Uses reflection to get ComboPopup.list as primary method, AccessibleContext as fallback.
     */
    private inner class StarPopupListener : PopupMenuListener {
        private var attached = false

        override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
            if (attached) return
            attached = true

            val list = getPopupList() ?: return

            // Single click handler for star zone — close popup first to prevent selection
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val idx = list.locationToIndex(e.point)
                    if (idx < 0) return
                    val item = list.model.getElementAt(idx)
                    if (item !is DropdownItem.ModelItem) return
                    val bounds = list.getCellBounds(idx, idx) ?: return
                    // Star is at the right edge of the cell
                    val starEdge = bounds.x + bounds.width - JBUI.scale(28)
                    if (e.x >= starEdge) {
                        // Close popup before toggling to prevent selection race
                        modelCombo.isPopupVisible = false
                        OpenCodeSettingsState.getInstance().toggleFavoriteModel(
                            item.model.providerID,
                            item.model.modelID
                        )
                        rebuildModelCombo()
                    }
                }
            })

            // Hover handler for star highlight
            list.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val idx = list.locationToIndex(e.point)
                    val newHover = if (idx >= 0) {
                        val b = list.getCellBounds(idx, idx)
                        if (b != null && e.x >= b.x + b.width - JBUI.scale(28)) idx else -1
                    } else -1
                    if (newHover != hoveredStarIndex) {
                        hoveredStarIndex = newHover
                        list.repaint()
                    }
                }
            })

            // Mouse exit — reset hover
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    if (hoveredStarIndex != -1) {
                        hoveredStarIndex = -1
                        list.repaint()
                    }
                }
            })
        }

        override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {}

        override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
            attached = false
        }

        /** Get the popup's JList via reflection (primary) or AccessibleContext (fallback). */
        private fun getPopupList(): javax.swing.JList<*>? {
            // Primary: reflect ComboPopup from BasicComboBoxUI
            try {
                val ui = modelCombo.ui
                val popupField = BasicComboBoxUI::class.java.getDeclaredField("popup")
                popupField.isAccessible = true
                val popup = popupField.get(ui) as? ComboPopup
                if (popup != null) return popup.list
            } catch (_: Exception) {
                // Fall through
            }

            // Fallback: AccessibleContext
            try {
                val child = modelCombo.accessibleContext.getAccessibleChild(0)
                return (child as? ComboPopup)?.list
            } catch (_: Exception) {
                return null
            }
        }
    }
}
