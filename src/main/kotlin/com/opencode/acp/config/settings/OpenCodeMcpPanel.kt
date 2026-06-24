package com.opencode.acp.config.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.opencode.acp.mcp.ToolInfo
import com.opencode.acp.mcp.ToolPermission
import com.opencode.acp.mcp.ToolSource
import kotlinx.coroutines.launch
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP Integration and Tool Permissions settings panel.
 * Displayed as a child page under "OpenCode" in the Settings tree.
 */
class OpenCodeMcpPanel {

    private val json = Json { ignoreUnknownKeys = true }

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
    private val allToolsData: MutableMap<String, ToolInfo> = mutableMapOf()

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
                val newPermission = if (current.permission == ToolPermission.DENY) ToolPermission.ALLOW else current.permission
                allToolsData[toolName] = current.copy(enabled = true, permission = newPermission)
            }
            applyFilters()
            updateToolCountLabel()
            // Sync visible subset to ToolRegistry so re-discovery preserves state
            syncBatchEnabledToRegistry(visibleTools, enabled = true)
        }

        disableAllToolsButton.addActionListener {
            val visibleTools = toolRowPanels.keys.toSet()
            for (toolName in visibleTools) {
                val current = allToolsData[toolName] ?: continue
                allToolsData[toolName] = current.copy(enabled = false, permission = ToolPermission.DENY)
            }
            applyFilters()
            updateToolCountLabel()
            // Sync visible subset to ToolRegistry so re-discovery preserves state
            syncBatchEnabledToRegistry(visibleTools, enabled = false)
        }
    }

    /**
     * Best-effort sync of a batch enable/disable to [com.opencode.acp.chat.service.OpenCodeService]'s
     * [com.opencode.acp.mcp.ToolRegistry]. Non-fatal: if no project is open or the registry
     * is unavailable, the local [allToolsData] (already updated by the caller) remains
     * authoritative for the current session.
     */
    private fun syncBatchEnabledToRegistry(toolNames: Set<String>, enabled: Boolean) {
        if (toolNames.isEmpty()) return
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val service = try {
            project.service<com.opencode.acp.chat.service.OpenCodeService>()
        } catch (e: Exception) {
            return
        }
        val registry = service.toolRegistry ?: return
        service.scope.launch {
            try {
                registry.syncEnabled(toolNames, enabled)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}
                    .warn(e) { "[ACP] Failed to sync batch enable/disable to ToolRegistry" }
            }
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
        val additionalJson = additionalMcpServersField.text.trim()
        if (additionalJson.isNotBlank()) {
            try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(additionalJson)
                val array = element.jsonArray
                // Validate structure: each element must be an object with non-blank "name" and "url"
                for ((index, item) in array.withIndex()) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    if (name.isNullOrBlank() || url.isNullOrBlank()) {
                        showStatus("MCP server #${index + 1}: 'name' and 'url' are required", false)
                        return
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        showStatus("MCP server '$name': URL must start with http:// or https://", false)
                        return
                    }
                }
            } catch (e: Exception) {
                showStatus("Invalid JSON in additional MCP servers: ${e.message}", false)
                return
            }
        }
        // All validation passed — safe to apply settings.
        settings.enableIntellijMcp = enableIntellijMcpCheckbox.isSelected
        settings.mcpServerUrl = mcpServerUrlField.text.trim()
        settings.additionalMcpServers = additionalJson
        if (allToolsData.isNotEmpty()) {
            // Use generateToolPermissionsJson() — the same serialization path as isModified().
            // This ensures the JSON compared in isModified() matches what is persisted here,
            // preventing fragile cross-path invariants that could silently break the diff check.
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

    fun updateToolPermissions(tools: Map<String, ToolInfo>) {
        allToolsData.clear()
        allToolsData.putAll(tools)
        updateSourceFilterDropdown(tools)
        applyFilters()
        updateToolCountLabel()
    }

    private fun updateSourceFilterDropdown(tools: Map<String, ToolInfo>) {
        val previousSelection = sourceFilterCombo.selectedItem?.toString() ?: "All tools"
        sourceFilterCombo.removeAllItems()
        sourceFilterCombo.addItem("All tools")
        sourceFilterCombo.addItem("Built-in only")
        val mcpServers = tools.filter { it.value.source == ToolSource.MCP }
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
                "Built-in only" -> info.source == ToolSource.BUILTIN
                else -> {
                    val serverFilter = currentSourceFilter.removePrefix("MCP: ")
                    info.source == ToolSource.MCP && info.serverName == serverFilter
                }
            }
            val queryMatch = currentFilterQuery.isBlank() ||
                toolName.lowercase().contains(currentFilterQuery.lowercase()) ||
                info.description.lowercase().contains(currentFilterQuery.lowercase())
            sourceMatch && queryMatch
        }.associate { it.key to it.value }

        val builtinTools = filteredTools.filter { it.value.source == ToolSource.BUILTIN }
        val mcpTools = filteredTools.filter { it.value.source == ToolSource.MCP }

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

    private fun createToolSection(title: String, tools: Map<String, ToolInfo>): JPanel {
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

    private fun createToolRow(toolName: String, info: ToolInfo): JPanel {
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
                    // Unchecking sets DENY for convenience; checking restores ALLOW
                    // (or preserves the current permission if it was already ALLOW/ASK)
                    val newPermission = if (!enabled) {
                        ToolPermission.DENY
                    } else if (old.permission == ToolPermission.DENY) {
                        ToolPermission.ALLOW
                    } else {
                        old.permission
                    }
                    allToolsData[toolName] = old.copy(enabled = enabled, permission = newPermission)
                }
                // Update combo to reflect the permission change (visual sync only)
                toolPermissionComboBoxes[toolName]?.selectedItem = allToolsData[toolName]?.permission?.toActionString() ?: "allow"
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
            selectedItem = info.permission.toActionString()
            preferredSize = Dimension(80, 25)
            toolTipText = "Permission level for $toolName"
            addActionListener {
                val permission = ToolPermission.fromActionString(selectedItem?.toString() ?: "allow")
                // Only update permission — enabled is controlled by the checkbox.
                // They are independent: a tool can be enabled=false, permission=allow
                // (disabled in UI but allowed if invoked by other means).
                allToolsData[toolName]?.let { old ->
                    allToolsData[toolName] = old.copy(permission = permission)
                }
                updateToolCountLabel()
            }
        }
        toolPermissionComboBoxes[toolName] = permissionCombo

        gbc.gridx = 0; gbc.weightx = 0.0; row.add(enabledCheckbox, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; row.add(textPanel, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; row.add(permissionCombo, gbc)
        return row
    }

    fun getAllToolPermissions(): Map<String, Pair<Boolean, ToolPermission>> {
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

    private fun loadToolPermissionsFromSettings(jsonStr: String) {
        if (jsonStr.isBlank()) return
        try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
            for ((toolName, element) in obj) {
                val toolObj = element.jsonObject
                val enabled = toolObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val permission = ToolPermission.fromActionString(toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow")
                val existing = allToolsData[toolName]
                if (existing != null) {
                    allToolsData[toolName] = existing.copy(enabled = enabled, permission = permission)
                } else {
                    // Tool not in cache — create placeholder with unknown source.
                    // Source will be corrected when discovery runs (the discovered
                    // tools cache cross-references source/serverName, and live
                    // discovery populates the correct source). Do NOT infer source
                    // from the tool name — built-in tools like "ast_grep_search"
                    // contain underscores and would be misclassified as MCP.
                    allToolsData[toolName] = ToolInfo.create(
                        name = toolName,
                        description = "Pending discovery: $toolName",
                        source = ToolSource.BUILTIN,
                        serverName = "builtin",
                        enabled = enabled,
                        permission = permission
                    )
                }
            }
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}
                .warn(e) { "[ACP] Failed to parse tool permissions JSON — reverting to defaults" }
        }
    }

    fun generateToolPermissionsJson(): String {
        if (allToolsData.isEmpty()) return ""
        val snapshot = allToolsData.toMap()  // Snapshot to avoid ConcurrentModificationException
        val root = buildJsonObject {
            for ((toolName, info) in snapshot) {
                put(toolName, buildJsonObject {
                    put("enabled", info.enabled)
                    put("permission", info.permission.toActionString())
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Generate JSON cache of discovered tools (name, description, source, serverName).
     */
    fun generateDiscoveredToolsJson(): String {
        if (allToolsData.isEmpty()) return ""
        val root = buildJsonObject {
            for ((toolName, info) in allToolsData) {
                put(toolName, buildJsonObject {
                    put("description", info.description)
                    put("source", info.source.name.lowercase())
                    put("serverName", info.serverName)
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Load tools from discovered tools cache JSON.
     */
    private fun loadDiscoveredToolsCache(jsonStr: String) {
        if (jsonStr.isBlank()) return
        try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
            for ((toolName, element) in obj) {
                val toolObj = element.jsonObject
                val description = toolObj["description"]?.jsonPrimitive?.contentOrNull ?: toolName
                val sourceStr = toolObj["source"]?.jsonPrimitive?.contentOrNull ?: "builtin"
                val source = if (sourceStr == "mcp") ToolSource.MCP else ToolSource.BUILTIN
                val serverName = toolObj["serverName"]?.jsonPrimitive?.contentOrNull ?: "builtin"
                // Don't overwrite if already in allToolsData (from live discovery)
                if (!allToolsData.containsKey(toolName)) {
                    allToolsData[toolName] = ToolInfo.create(
                        name = toolName,
                        description = description,
                        source = source,
                        serverName = serverName,
                        enabled = true,
                        permission = ToolPermission.ALLOW
                    )
                }
            }
            if (allToolsData.isNotEmpty()) {
                updateSourceFilterDropdown(allToolsData)
                applyFilters()
                updateToolCountLabel()
            }
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}
                .warn(e) { "[ACP] Failed to parse discovered tools cache" }
        }
    }
}
