package com.opencode.acp.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.DropdownItem
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import com.opencode.acp.chat.util.ChatColors
import com.opencode.acp.config.settings.OpenCodeSettingsState
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.Border
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.ComboPopup
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter

/**
 * Unified chat input card.
 *
 * Rounded card containing:
 *   - Text area with circular attach (+) button left, circular send (▲) button right
 *   - Bottom row: rounded pill selectors for Agent ✦ Model ✦ Thinking
 *
 * Uses ONLY native IntelliJ components — no paintComponent() overrides.
 * Rounded borders are achieved via standard Swing Border implementations.
 */
class InputAreaComponent(
    private val onSend: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onAgentChanged: (OpenCodeAgentInfo) -> Unit,
    private val onModelChanged: (ProviderModel) -> Unit,
    private val onThinkingChanged: (ThinkingEffort) -> Unit
) : JPanel(BorderLayout()) {

    private val textArea: JBTextArea = JBTextArea(2, 50)
    private val sendButton: JButton
    private val cancelButton: JButton

    private val agentCombo: ComboBox<OpenCodeAgentInfo>
    private val modelCombo: ComboBox<DropdownItem>
    private val thinkingCombo: ComboBox<ThinkingEffort>

    private var hoveredStarIndex: Int = -1
    private var lastState: ControlBarState? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12))

        // ── Text area ──
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.font = JBUI.Fonts.label()
        textArea.background = ChatColors.editorBg()
        textArea.foreground = ChatColors.textPrimary()
        textArea.border = JBUI.Borders.empty()

        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        textArea.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val text = textArea.text.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    textArea.text = ""
                }
            }
        })
        textArea.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline"
        )
        textArea.actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                textArea.insert("\n", textArea.caretPosition)
            }
        })
        textArea.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel")
        textArea.actionMap.put("cancel", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { onCancel() }
        })

        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            viewport.background = ChatColors.editorBg()
            preferredSize = Dimension(0, JBUI.scale(40))
        }

        // ── Circular icon buttons ──
        val attachButton = createCircleButton(AllIcons.General.Add, "Attach file", 28)
        attachButton.addActionListener { /* TODO: file attachment */ }

        sendButton = createCircleButton(AllIcons.Actions.MoveUp, "Send message (Enter)", 32).apply {
            border = CircleBorder(ChatColors.textLink(), ChatColors.textLink(), 1)
            addActionListener {
                val text = textArea.text.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    textArea.text = ""
                }
            }
        }

        cancelButton = createCircleButton(AllIcons.Actions.Suspend, "Cancel", 32).apply {
            border = CircleBorder(ChatColors.editorBg(), ChatColors.border(), 1)
            addActionListener { onCancel() }
            isVisible = false
        }

        // ── Top row: attach | text | send ──
        val topPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(attachButton, BorderLayout.WEST)
            add(scrollPane, BorderLayout.CENTER)
            val btnPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(Box.createVerticalGlue())
                add(sendButton)
                add(cancelButton)
                add(Box.createVerticalGlue())
            }
            add(btnPanel, BorderLayout.EAST)
        }

        // ── Selectors ──
        agentCombo = createAgentCombo()
        modelCombo = createModelCombo()
        thinkingCombo = createThinkingCombo()

        // ── Bottom row: rounded pill selectors with sparkle separator ──
        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(wrapCombo(agentCombo))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(JBLabel("✦").apply {
                font = JBUI.Fonts.smallFont()
                foreground = ChatColors.textMuted()
            })
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(wrapCombo(modelCombo))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(wrapCombo(thinkingCombo))
            add(Box.createHorizontalGlue())
        }

        // ── Rounded card wrapper ──
        val card = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(4))).apply {
            isOpaque = true
            background = ChatColors.editorBg()
            border = JBUI.Borders.merge(
                RoundedLineBorder(ChatColors.border(), arc = 12, thickness = 1),
                JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(10), JBUI.scale(6), JBUI.scale(10)),
                true
            )
            add(topPanel, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }

        add(card, BorderLayout.CENTER)
    }

    private fun createCircleButton(icon: Icon, tooltip: String, size: Int): JButton {
        return JButton(icon).apply {
            margin = JBUI.insets(0)
            isOpaque = false
            isContentAreaFilled = false
            isFocusPainted = false
            val dim = Dimension(JBUI.scale(size), JBUI.scale(size))
            preferredSize = dim
            minimumSize = dim
            maximumSize = dim
            toolTipText = tooltip
        }
    }

    private fun wrapCombo(combo: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = ChatColors.editorBg()
            border = JBUI.Borders.merge(
                RoundedLineBorder(ChatColors.border(), arc = 6, thickness = 1),
                JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6)),
                true
            )
            add(combo.apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }, BorderLayout.CENTER)
        }
    }

    private fun createAgentCombo(): ComboBox<OpenCodeAgentInfo> {
        return ComboBox<OpenCodeAgentInfo>().apply {
            renderer = createFlatRenderer { value ->
                (value as? OpenCodeAgentInfo)?.name ?: "Agent"
            }
            addActionListener { (selectedItem as? OpenCodeAgentInfo)?.let(onAgentChanged) }
        }
    }

    private fun createModelCombo(): ComboBox<DropdownItem> {
        return ComboBox<DropdownItem>().apply {
            renderer = ModelRenderer()
            addActionListener {
                when (val s = selectedItem) {
                    is DropdownItem.ModelItem -> onModelChanged(s.model)
                }
            }
            addPopupMenuListener(StarPopupListener())
        }
    }

    private fun createThinkingCombo(): ComboBox<ThinkingEffort> {
        return ComboBox(ThinkingEffort.entries.toTypedArray()).apply {
            renderer = createFlatRenderer { value ->
                (value as? ThinkingEffort)?.label ?: "Default"
            }
            addActionListener { (selectedItem as? ThinkingEffort)?.let(onThinkingChanged) }
        }
    }

    private fun <T> createFlatRenderer(textProvider: (Any?) -> String): ListCellRenderer<T> {
        return ListCellRenderer { list, value, index, isSelected, _ ->
            JBLabel(textProvider(value)).apply {
                font = JBUI.Fonts.smallFont()
                foreground = if (isSelected) list.selectionForeground else ChatColors.textSecondary()
                background = if (isSelected) list.selectionBackground else list.background
                isOpaque = true
                if (index == -1) {
                    border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4))
                }
            }
        }
    }

    // ──────────────── Public API ────────────────

    fun clear() { textArea.text = "" }

    fun setInputEnabled(enabled: Boolean) {
        textArea.isEnabled = enabled
        sendButton.isEnabled = enabled
        sendButton.border = if (enabled) {
            CircleBorder(ChatColors.textLink(), ChatColors.textLink(), 1)
        } else {
            CircleBorder(ChatColors.editorBg(), ChatColors.border(), 1)
        }
    }

    fun showCancelMode(isStreaming: Boolean) {
        sendButton.isVisible = !isStreaming
        cancelButton.isVisible = isStreaming
    }

    fun updateState(state: ControlBarState) {
        lastState = state
        agentCombo.model = DefaultComboBoxModel(state.agents.toTypedArray())
        agentCombo.selectedItem = state.selectedAgent

        val items = buildGroupedModelList(state.models)
        modelCombo.model = DefaultComboBoxModel(items.toTypedArray())
        val match = items.find { it is DropdownItem.ModelItem && it.model == state.selectedModel }
        modelCombo.selectedItem = match

        thinkingCombo.selectedItem = state.thinkingEffort
        thinkingCombo.isEnabled = state.selectedModel?.reasoning == true
    }

    // ──────────────── Model list building ────────────────

    private fun buildGroupedModelList(models: List<ProviderModel>): List<DropdownItem> {
        if (models.isEmpty()) return emptyList()
        val settings = OpenCodeSettingsState.getInstance()
        settings.cleanupStaleFavorites(models)

        val result = mutableListOf<DropdownItem>()
        val favorites = models.filter { settings.isFavoriteModel(it.providerID, it.modelID) }
        if (favorites.isNotEmpty()) {
            result.add(DropdownItem.ProviderHeader("★ Favorites"))
            for (model in favorites) {
                val (provider, name) = parseDisplayName(model.displayName)
                result.add(DropdownItem.ModelItem(model, provider, name, isFavorite = true))
            }
        }

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

    private fun parseDisplayName(displayName: String): Pair<String, String> {
        val parts = displayName.split(" / ", limit = 2)
        return if (parts.size == 2) Pair(parts[0].trim(), parts[1].trim())
        else Pair(displayName, displayName)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ──────────────── Model Renderer (with star favorites) ────────────────

    private inner class ModelRenderer : ListCellRenderer<DropdownItem> {
        private val headerLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
            foreground = ChatColors.textLink()
            border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
        }
        private val closedLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = ChatColors.textSecondary()
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4))
        }
        private val modelPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
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
            if (index == -1) {
                closedLabel.text = when (value) {
                    is DropdownItem.ModelItem -> value.modelName
                    is DropdownItem.ProviderHeader -> value.name
                    else -> "Model"
                }
                return closedLabel
            }

            when (value) {
                is DropdownItem.ProviderHeader -> {
                    headerLabel.text = value.name
                    headerLabel.background = if (isSelected) list.selectionBackground else list.background
                    headerLabel.isOpaque = true
                    return headerLabel
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
                        if (index == hoveredStarIndex) ChatColors.starHover else ChatColors.starFavorite
                    } else {
                        ChatColors.starMuted()
                    }
                    modelPanel.background = if (isSelected) list.selectionBackground else list.background
                    return modelPanel
                }
                else -> {
                    headerLabel.text = value?.toString() ?: ""
                    return headerLabel
                }
            }
        }
    }

    // ──────────────── Star click detection ────────────────

    private inner class StarPopupListener : PopupMenuListener {
        private var attached = false

        override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
            if (attached) return
            attached = true
            val list = getPopupList() ?: return

            list.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    val idx = list.locationToIndex(e.point)
                    if (idx < 0) return
                    val item = list.model.getElementAt(idx)
                    if (item !is DropdownItem.ModelItem) return
                    val bounds = list.getCellBounds(idx, idx) ?: return
                    val starEdge = bounds.x + bounds.width - JBUI.scale(28)
                    if (e.x >= starEdge) {
                        modelCombo.isPopupVisible = false
                        OpenCodeSettingsState.getInstance().toggleFavoriteModel(
                            item.model.providerID,
                            item.model.modelID
                        )
                        rebuildModelCombo()
                    }
                }
            })

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
        override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) { attached = false }

        private fun getPopupList(): javax.swing.JList<*>? {
            try {
                val ui = modelCombo.ui
                var clazz: Class<*>? = ui.javaClass
                while (clazz != null) {
                    try {
                        val field = clazz.getDeclaredField("popup")
                        field.isAccessible = true
                        val popup = field.get(ui) as? ComboPopup
                        if (popup != null) return popup.list
                        break
                    } catch (_: NoSuchFieldException) {
                        clazz = clazz.superclass
                    }
                }
            } catch (_: Exception) { }
            try {
                val child = modelCombo.accessibleContext.getAccessibleChild(0)
                return (child as? ComboPopup)?.list
            } catch (_: Exception) { return null }
        }
    }

    fun rebuildModelCombo() {
        val state = lastState ?: return
        val items = buildGroupedModelList(state.models)
        modelCombo.model = DefaultComboBoxModel(items.toTypedArray())
        val match = items.find { it is DropdownItem.ModelItem && it.model == state.selectedModel }
        modelCombo.selectedItem = match
    }

    // ═══════════════════════════════════════════════════════════
    //  Rounded borders — standard Swing Border, not paintComponent
    // ═══════════════════════════════════════════════════════════

    /**
     * Anti-aliased rounded rectangle border.
     * Arc diameter controls corner roundness.
     */
    private class RoundedLineBorder(
        private val color: Color,
        private val arc: Int,
        private val thickness: Int = 1
    ) : Border {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val t = JBUI.scale(thickness).toFloat()
            g2.color = color
            g2.stroke = BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val off = t / 2f
            val arcPx = JBUI.scale(arc)
            g2.drawRoundRect(
                (x + off).toInt(),
                (y + off).toInt(),
                (width - t).toInt() - 1,
                (height - t).toInt() - 1,
                arcPx,
                arcPx
            )
            g2.dispose()
        }

        override fun getBorderInsets(c: Component): Insets = JBUI.insets(thickness)
        override fun isBorderOpaque() = false
    }

    /**
     * Filled circle border — draws a filled circle with optional stroke.
     * Used for circular icon buttons (send, attach, cancel).
     */
    private class CircleBorder(
        private val fillColor: Color,
        private val strokeColor: Color,
        private val thickness: Int = 1
    ) : Border {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = minOf(width, height)
            val pad = JBUI.scale(thickness)
            val dia = size - pad * 2
            val ox = x + (width - size) / 2 + pad
            val oy = y + (height - size) / 2 + pad

            g2.color = fillColor
            g2.fillOval(ox, oy, dia, dia)

            if (strokeColor != fillColor || thickness > 0) {
                g2.color = strokeColor
                g2.stroke = BasicStroke(JBUI.scale(thickness).toFloat())
                g2.drawOval(ox, oy, dia, dia)
            }
            g2.dispose()
        }

        override fun getBorderInsets(c: Component): Insets = JBUI.insets(thickness)
        override fun isBorderOpaque() = false
    }
}
