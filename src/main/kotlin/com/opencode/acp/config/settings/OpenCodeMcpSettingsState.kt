package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for MCP integration — extracted from [OpenCodeSettingsState]
 * (Phase A of a 3-phase XStream-safe migration).
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. The fields here are also retained (as `@Deprecated`) on
 * [OpenCodeSettingsState] for one release cycle so existing XML files
 * continue to load; [OpenCodeSettingsState.loadState] forwards the values
 * here during the transition.
 */
@Service(Service.Level.APP)
@State(
    name = "OpenCodeMcpSettings",
    storages = [Storage("opencode-mcp-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodeMcpSettingsState : PersistentStateComponent<OpenCodeMcpSettingsState> {

    /** Whether to enable IntelliJ MCP server integration. */
    var enableIntellijMcp: Boolean = false
    /** IntelliJ MCP server SSE URL. Copy from Settings → Tools → MCP Server → "Copy SSE Config". */
    var mcpServerUrl: String = ""
    /**
     * Additional MCP servers as JSON array: [{"name":"github","url":"http://127.0.0.1:8080/sse"}].
     * Stored as JSON string for XStream serialization compatibility.
     */
    var additionalMcpServers: String = ""

    // ── Tool Permissions ──────────────────────────────────────────────
    /**
     * Tool permission states as JSON string.
     * Format: {"toolName":{"enabled":true,"permission":"allow"},...}
     * Stored as JSON string for XStream serialization compatibility.
     */
    var toolPermissions: String = ""

    /**
     * Permissions saved before Disable All, so Enable All can restore ASK
     * settings that were active before the disable. Prevents ASK→ALLOW
     * promotion on a disable→enable cycle across IDE restarts.
     * Format: {"toolId":"allow",...} (tool ID → permission action string).
     * Empty when no Disable All has been performed.
     */
    var savedToolPermissionsBeforeDisable: String = ""

    /**
     * Discovered tools cache as JSON string.
     * Format: [{"name":"bash","description":"...","source":"builtin","serverName":"builtin"},...]
     * Allows showing previously discovered tools without re-discovery.
     */
    var discoveredToolsJson: String = ""

    override fun getState(): OpenCodeMcpSettingsState = this

    override fun loadState(state: OpenCodeMcpSettingsState) {
        enableIntellijMcp = state.enableIntellijMcp
        mcpServerUrl = state.mcpServerUrl
        additionalMcpServers = try {
            if (state.additionalMcpServers.isNotBlank()) {
                kotlinx.serialization.json.Json.parseToJsonElement(state.additionalMcpServers)
                state.additionalMcpServers
            } else ""
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn(e) { "[ACP] Invalid additionalMcpServers in OpenCodeMcpSettingsState, clearing" }
            ""
        }
        toolPermissions = state.toolPermissions
        savedToolPermissionsBeforeDisable = state.savedToolPermissionsBeforeDisable
        discoveredToolsJson = state.discoveredToolsJson
    }

    companion object {
        fun getInstance(): OpenCodeMcpSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeMcpSettingsState::class.java)
    }
}