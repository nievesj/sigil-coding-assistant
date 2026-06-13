package com.opencode.acp.config.settings

import com.agentclientprotocol.model.ToolKind
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP Integration and Tool Permissions settings panel.
 * Displayed as a child page under "OpenCode" in the Settings tree.
 */
class OpenCodeMcpPanel {

    // ── MCP Integration ────────────────────────────────────────────────────

    /** Whether to enable IntelliJ MCP server integration. */
    val enableIntellijMcpCheckbox: JBCheckBox = JBCheckBox("Enable IntelliJ MCP integration").apply {
        toolTipText = "Connect to the IDE\u2019s built-in MCP server so the LLM can use IDE-native tools " +
            "(refactoring, inspections, run configs, symbol search, etc.). " +
            "Requires MCP Server to be enabled in Settings \u2192 Tools \u2192 MCP Server."
        addActionListener {
            updateMcpStatusLabel()
        }
    }

    /** IntelliJ MCP server SSE URL. */
    val mcpServerUrlField: JBTextField = JBTextField().apply {
        toolTipText = "SSE URL from Settings \u2192 Tools \u2192 MCP Server (click \u2018Copy SSE Config\u2019). " +
            "Format: http://127.0.0.1:<port>/sse"
        emptyText.text = "http://127.0.0.1:64342/sse"
    }

    /** Additional MCP servers as JSON array. */
    val additionalMcpServersField: JTextArea = JTextArea(3, 40).apply {
        toolTipText = "Additional MCP servers as JSON array. Format: [{\"name\":\"server-name\",\"url\":\"http://127.0.0.1:port/sse\"}]"
    }

    val additionalMcpServersScrollPane: JScrollPane = JScrollPane(additionalMcpServersField)

    /** MCP connection status label (updated dynamically after connection attempt). */
    val mcpStatusLabel: JBLabel = JBLabel("MCP integration: disabled").apply {
        foreground = JBColor.GRAY
    }

    /** Retry MCP connection after error. */
    val mcpRetryButton: JButton = JButton("Retry MCP Connection").apply {
        toolTipText = "Retry connecting to configured MCP servers"
        isVisible = false
    }

    // ── Tool Permissions ──────────────────────────────────────────────────

    /** Header label showing tool count: "N / M tools enabled" */
    val toolCountLabel: JBLabel = JBLabel("0 / 0 tools enabled").apply {
        toolTipText = "Number of enabled tools / total tools discovered"
    }

    /** Button to enable all tools. */
    val enableAllToolsButton: JButton = JButton("Enable All").apply {
        toolTipText = "Enable all discovered tools"
    }

    /** Button to disable all tools. */
    val disableAllToolsButton: JButton = JButton("Disable All").apply {
        toolTipText = "Disable all discovered tools"
    }

    /** Filter field for searching tools by name or description. */
    val toolFilterField: JBTextField = JBTextField().apply {
        toolTipText = "Filter tools by name or ID"
        emptyText.text = "Filter tools by name or ID"
    }

    /** Source dropdown filter: "All tools", "Built-in only", or specific MCP server names. */
    val sourceFilterCombo: JComboBox<String> = JComboBox(arrayOf("All tools")).apply {
        toolTipText = "Filter tools by source"
    }

    /** Panel containing tool permission toggles (scrollable). */
    val toolPermissionsPanel: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
    }

    /** Scroll pane for tool permissions panel. */
    val toolPermissionsScrollPane: JScrollPane = JScrollPane(toolPermissionsPanel).apply {
        preferredSize = Dimension(0, 300)
    }

    /** Button to discover available tools from OpenCode and MCP servers. */
    val discoverToolsButton: JButton = JButton("Discover Tools").apply {
        toolTipText = "Discover available tools from OpenCode and connected MCP servers"
    }

    /** Status label for tool permission operations. */
    val toolPermissionsStatusLabel: JBLabel = JBLabel("").apply {
        isVisible = false
    }

    /** Map of tool name to permission combo box. */
    private val toolPermissionComboBoxes: MutableMap<String, JComboBox<String>> = mutableMapOf()

    /** Map of tool name to enabled checkbox. */
    private val toolEnabledCheckboxes: MutableMap<String, JBCheckBox> = mutableMapOf()

    /** Map of tool name to row panel (for filtering). */
    private val toolRowPanels: MutableMap<String, JPanel> = mutableMapOf()

    /** Current filter query. */
    private var currentFilterQuery: String = ""

    /** Current source filter. */
    private var currentSourceFilter: String = "All tools"

    /** All discovered tools data. */
    private val allToolsData: MutableMap<String, ToolPermissionInfo> = mutableMapOf()

    /** Snapshot of persisted tool permissions JSON from setState(), used by isModified(). */
    var savedToolPermissionsJson: String = ""

    /** Whether tool discovery is currently in progress. Exposed for configurable to check. */
    var isDiscovering: Boolean = false

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addTooltip("MCP Integration (OpenCode):")
        .addComponent(enableIntellijMcpCheckbox)
        .addLabeledComponent("IntelliJ MCP SSE URL:", mcpServerUrlField, 5)
        .addLabeledComponent("Additional MCP servers:", additionalMcpServersScrollPane, 5)
        .addComponent(mcpStatusLabel)
        .addComponent(mcpRetryButton)
        .addSeparator(5)
        .addTooltip("Tool Permissions (OpenCode):")
        .addComponent(toolCountLabel)
        .addComponent(toolPermissionsStatusLabel)
        .apply {
            val toolButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
            toolButtonPanel.add(enableAllToolsButton)
            toolButtonPanel.add(disableAllToolsButton)
            addComponent(toolButtonPanel)
        }
        .apply {
            val filterPanel = JPanel(BorderLayout(5, 0))
            filterPanel.add(toolFilterField, BorderLayout.CENTER)
            filterPanel.add(sourceFilterCombo, BorderLayout.EAST)
            addComponent(filterPanel)
        }
        .addComponent(toolPermissionsScrollPane)
        .addComponent(discoverToolsButton)
        .panel

    init {
        // Wire filter listeners
        toolFilterField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { applyFilters() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { applyFilters() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { applyFilters() }
        })

        sourceFilterCombo.addActionListener {
            currentSourceFilter = sourceFilterCombo.selectedItem?.toString() ?: "All tools"
            applyFilters()
        }

        // Wire enable all / disable all buttons
        enableAllToolsButton.addActionListener {
            val visibleTools = toolRowPanels.keys.toSet()
            for (toolName in visibleTools) {
                val current = allToolsData[toolName] ?: continue
                val newPermission = if (current.permission == "deny") "allow" else current.permission
                allToolsData[toolName] = current.copy(enabled = true, permission = newPermission)
            }
            applyFilters()
            updateToolCountLabel()
        }

        disableAllToolsButton.addActionListener {
            val visibleTools = toolRowPanels.keys.toSet()
            for (toolName in visibleTools) {
                val current = allToolsData[toolName] ?: continue
                allToolsData[toolName] = current.copy(enabled = false, permission = "deny")
            }
            applyFilters()
            updateToolCountLabel()
        }
    }

    fun setState(settings: OpenCodeSettingsState) {
        enableIntellijMcpCheckbox.isSelected = settings.enableIntellijMcp
        mcpServerUrlField.text = settings.mcpServerUrl
        additionalMcpServersField.text = settings.additionalMcpServers
        // Restore persisted tool permissions
        savedToolPermissionsJson = settings.toolPermissions
        // Load cached discovered tools (if any) before applying permissions
        loadDiscoveredToolsCache(settings.discoveredToolsJson)
        loadToolPermissionsFromSettings(settings.toolPermissions)
        updateMcpStatusLabel()
    }

    fun applyTo(settings: OpenCodeSettingsState) {
        settings.enableIntellijMcp = enableIntellijMcpCheckbox.isSelected
        settings.mcpServerUrl = mcpServerUrlField.text.trim()
        val additionalJson = additionalMcpServersField.text.trim()
        if (additionalJson.isNotBlank()) {
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(additionalJson)
                settings.additionalMcpServers = additionalJson
            } catch (e: Exception) {
                showStatus("Invalid JSON in additional MCP servers: ${e.message}", false)
            }
        } else {
            settings.additionalMcpServers = ""
        }
        if (allToolsData.isNotEmpty()) {
            settings.toolPermissions = generateToolPermissionsJson()
            savedToolPermissionsJson = settings.toolPermissions
        }
    }

    fun isModified(settings: OpenCodeSettingsState): Boolean {
        return enableIntellijMcpCheckbox.isSelected != settings.enableIntellijMcp ||
                mcpServerUrlField.text.trim() != settings.mcpServerUrl ||
                additionalMcpServersField.text.trim() != settings.additionalMcpServers ||
                (allToolsData.isNotEmpty() && generateToolPermissionsJson() != savedToolPermissionsJson)
    }

    private fun updateMcpStatusLabel() {
        if (enableIntellijMcpCheckbox.isSelected) {
            mcpStatusLabel.text = "MCP integration: enabled (status shown in status bar)"
            mcpStatusLabel.foreground = JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))
            mcpRetryButton.isVisible = true
        } else {
            mcpStatusLabel.text = "MCP integration: disabled"
            mcpStatusLabel.foreground = JBColor.GRAY
            mcpRetryButton.isVisible = false
        }
    }

    fun updateToolPermissions(tools: Map<String, ToolPermissionInfo>) {
        allToolsData.clear()
        allToolsData.putAll(tools)
        updateSourceFilterDropdown(tools)
        applyFilters()
        updateToolCountLabel()
    }

    private fun updateSourceFilterDropdown(tools: Map<String, ToolPermissionInfo>) {
        val previousSelection = sourceFilterCombo.selectedItem?.toString() ?: "All tools"
        sourceFilterCombo.removeAllItems()
        sourceFilterCombo.addItem("All tools")
        sourceFilterCombo.addItem("Built-in only")
        val mcpServers = tools.filter { it.value.source == "mcp" }
            .map { it.value.serverName }.distinct().sorted()
        for (server in mcpServers) {
            sourceFilterCombo.addItem("MCP: $server")
        }
        val restoreIndex = (0 until sourceFilterCombo.itemCount).firstOrNull {
            sourceFilterCombo.getItemAt(it) == previousSelection
        } ?: 0
        sourceFilterCombo.selectedIndex = restoreIndex
    }

    private fun applyFilters() {
        toolPermissionsPanel.removeAll()
        toolPermissionComboBoxes.clear()
        toolEnabledCheckboxes.clear()
        toolRowPanels.clear()

        val filteredTools = allToolsData.entries.filter { (toolName, info) ->
            val sourceMatch = when (currentSourceFilter) {
                "All tools" -> true
                "Built-in only" -> info.source == "builtin"
                else -> {
                    val serverFilter = currentSourceFilter.removePrefix("MCP: ")
                    info.source == "mcp" && info.serverName == serverFilter
                }
            }
            val queryMatch = currentFilterQuery.isBlank() ||
                toolName.lowercase().contains(currentFilterQuery.lowercase()) ||
                info.description.lowercase().contains(currentFilterQuery.lowercase())
            sourceMatch && queryMatch
        }.associate { it.key to it.value }

        val builtinTools = filteredTools.filter { it.value.source == "builtin" }
        val mcpTools = filteredTools.filter { it.value.source == "mcp" }

        if (builtinTools.isNotEmpty()) {
            toolPermissionsPanel.add(createToolSection("Built-in Tools (${builtinTools.size})", builtinTools))
        }
        val mcpByServer = mcpTools.entries.groupBy { it.value.serverName }
        for ((serverName, serverTools) in mcpByServer) {
            val serverMap = serverTools.associate { it.key to it.value }
            toolPermissionsPanel.add(createToolSection("MCP: $serverName (${serverMap.size})", serverMap))
        }
        toolPermissionsPanel.revalidate()
        toolPermissionsPanel.repaint()
    }

    private fun updateToolCountLabel() {
        val enabledCount = allToolsData.values.count { it.enabled }
        val totalCount = allToolsData.size
        toolCountLabel.text = "$enabledCount / $totalCount tools enabled"
    }

    private fun createToolSection(title: String, tools: Map<String, ToolPermissionInfo>): JPanel {
        val sectionPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(title)
        }
        val toolsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        for ((toolName, info) in tools.entries.sortedBy { it.key }) {
            val toolRow = createToolRow(toolName, info)
            toolsPanel.add(toolRow)
            toolRowPanels[toolName] = toolRow
        }
        sectionPanel.add(toolsPanel, BorderLayout.CENTER)
        return sectionPanel
    }

    private fun createToolRow(toolName: String, info: ToolPermissionInfo): JPanel {
        val row = JPanel(GridBagLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 45)
            border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = java.awt.Insets(2, 5, 2, 5)
        }
        val enabledCheckbox = JBCheckBox().apply {
            isSelected = info.enabled
            toolTipText = "Enable/disable $toolName"
            addActionListener {
                val enabled = isSelected
                toolEnabledCheckboxes[toolName]?.isSelected = enabled
                allToolsData[toolName]?.let { old ->
                    allToolsData[toolName] = old.copy(enabled = enabled, permission = if (enabled) "allow" else "deny")
                }
                toolPermissionComboBoxes[toolName]?.let { combo -> combo.selectedItem = if (enabled) "allow" else "deny" }
                updateToolCountLabel()
            }
        }
        toolEnabledCheckboxes[toolName] = enabledCheckbox

        val textPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val nameLabel = JBLabel(toolName).apply { toolTipText = info.description }
        val descLabel = JBLabel(info.description).apply { foreground = JBColor.GRAY; toolTipText = info.description }
        textPanel.add(nameLabel)
        textPanel.add(descLabel)

        val permissionCombo = JComboBox(arrayOf("allow", "ask", "deny")).apply {
            selectedItem = info.permission
            preferredSize = Dimension(80, 25)
            toolTipText = "Permission level for $toolName"
            addActionListener {
                val permission = selectedItem?.toString() ?: "allow"
                val enabled = permission != "deny"
                allToolsData[toolName]?.let { old ->
                    allToolsData[toolName] = old.copy(permission = permission, enabled = enabled)
                }
                toolEnabledCheckboxes[toolName]?.isSelected = enabled
                updateToolCountLabel()
            }
        }
        toolPermissionComboBoxes[toolName] = permissionCombo

        gbc.gridx = 0; gbc.weightx = 0.0; row.add(enabledCheckbox, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; row.add(textPanel, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; row.add(permissionCombo, gbc)
        return row
    }

    fun getAllToolPermissions(): Map<String, Pair<Boolean, String>> {
        return allToolsData.mapValues { (_, info) -> Pair(info.enabled, info.permission) }
    }

    fun showToolPermissionsStatus(msg: String, success: Boolean) {
        toolPermissionsStatusLabel.text = msg
        toolPermissionsStatusLabel.isVisible = true
        toolPermissionsStatusLabel.foreground = if (success) {
            JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))
        } else {
            JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
        }
    }

    fun showStatus(msg: String, success: Boolean) {
        mcpStatusLabel.text = msg
        mcpStatusLabel.isVisible = true
        mcpStatusLabel.foreground = if (success) {
            JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))
        } else {
            JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
        }
    }

    data class ToolPermissionInfo(
        val description: String,
        val source: String,
        val serverName: String = "builtin",
        val enabled: Boolean,
        val permission: String
    )

    private fun loadToolPermissionsFromSettings(json: String) {
        if (json.isBlank()) return
        try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            for ((toolName, element) in obj) {
                val toolObj = element.jsonObject
                val enabled = toolObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val permission = toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow"
                val existing = allToolsData[toolName]
                if (existing != null) {
                    allToolsData[toolName] = existing.copy(enabled = enabled, permission = permission)
                } else {
                    allToolsData[toolName] = ToolPermissionInfo(
                        description = toolName,
                        source = if (toolName.contains("_")) "mcp" else "builtin",
                        serverName = toolName.substringBefore("_", "builtin"),
                        enabled = enabled,
                        permission = permission
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore malformed JSON
        }
    }

    fun generateToolPermissionsJson(): String {
        if (allToolsData.isEmpty()) return ""
        val entries = allToolsData.entries.joinToString(",") { (toolName, info) ->
            val safeName = kotlinx.serialization.json.JsonPrimitive(toolName).toString()
            "$safeName:{\"enabled\":${info.enabled},\"permission\":\"${info.permission}\"}"
        }
        return "{$entries}"
    }

    /**
     * Generate JSON cache of discovered tools (name, description, source, serverName).
     */
    fun generateDiscoveredToolsJson(): String {
        if (allToolsData.isEmpty()) return ""
        val entries = allToolsData.entries.map { (toolName, info) ->
            val safeName = kotlinx.serialization.json.JsonPrimitive(toolName).toString()
            val safeDesc = kotlinx.serialization.json.JsonPrimitive(info.description).toString()
            val safeSource = kotlinx.serialization.json.JsonPrimitive(info.source).toString()
            val safeServer = kotlinx.serialization.json.JsonPrimitive(info.serverName).toString()
            "$safeName:{\"description\":$safeDesc,\"source\":$safeSource,\"serverName\":$safeServer}"
        }
        return "{${entries.joinToString(",")}}"
    }

    /**
     * Load tools from discovered tools cache JSON.
     */
    private fun loadDiscoveredToolsCache(json: String) {
        if (json.isBlank()) return
        try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            for ((toolName, element) in obj) {
                val toolObj = element.jsonObject
                val description = toolObj["description"]?.jsonPrimitive?.contentOrNull ?: toolName
                val source = toolObj["source"]?.jsonPrimitive?.contentOrNull ?: "builtin"
                val serverName = toolObj["serverName"]?.jsonPrimitive?.contentOrNull ?: "builtin"
                // Don't overwrite if already in allToolsData (from live discovery)
                if (!allToolsData.containsKey(toolName)) {
                    allToolsData[toolName] = ToolPermissionInfo(
                        description = description,
                        source = source,
                        serverName = serverName,
                        enabled = true,
                        permission = "allow"
                    )
                }
            }
            if (allToolsData.isNotEmpty()) {
                updateSourceFilterDropdown(allToolsData)
                applyFilters()
                updateToolCountLabel()
            }
        } catch (e: Exception) {
            // Ignore malformed JSON
        }
    }
}