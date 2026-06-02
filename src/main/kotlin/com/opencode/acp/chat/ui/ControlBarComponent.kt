package com.opencode.acp.chat.ui

import com.intellij.ui.components.JBLabel
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.OpenCodeAgentInfo
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.ThinkingEffort
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class ControlBarComponent(
    private val onAgentChanged: (OpenCodeAgentInfo) -> Unit,
    private val onModelChanged: (ProviderModel) -> Unit,
    private val onThinkingChanged: (ThinkingEffort) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {
    private val agentCombo: JComboBox<OpenCodeAgentInfo>
    private val modelCombo: JComboBox<ProviderModel>
    private val thinkingCombo: JComboBox<ThinkingEffort>

    init {
        agentCombo = JComboBox()
        agentCombo.renderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            JBLabel(value?.name ?: "Select Agent")
        }
        agentCombo.addActionListener {
            (agentCombo.selectedItem as? OpenCodeAgentInfo)?.let(onAgentChanged)
        }

        modelCombo = JComboBox()
        modelCombo.renderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            JBLabel(value?.displayName ?: "Select Model")
        }
        modelCombo.addActionListener {
            (modelCombo.selectedItem as? ProviderModel)?.let(onModelChanged)
        }

        thinkingCombo = JComboBox(ThinkingEffort.entries.toTypedArray())
        thinkingCombo.renderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            JBLabel(value?.label ?: "Default")
        }
        thinkingCombo.addActionListener {
            (thinkingCombo.selectedItem as? ThinkingEffort)?.let(onThinkingChanged)
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
        modelCombo.model = DefaultComboBoxModel(state.models.toTypedArray())
        modelCombo.selectedItem = state.selectedModel
        thinkingCombo.selectedItem = state.thinkingEffort
        thinkingCombo.isEnabled = state.selectedModel?.reasoning == true
    }
}
