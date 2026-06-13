package com.opencode.acp.mcp

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val logger = KotlinLogging.logger {}
private val mcpJson = Json { ignoreUnknownKeys = true }

/**
 * Orchestrates MCP server discovery, registration, and tool listing
 * for all configured servers.
 *
 * Manages a registry of [McpServerConfig] entries. Each server has its own
 * connection state machine and lifecycle. Created by [OpenCodeService]
 * after [ProcessManager.initialize] succeeds.
 *
 * Built-in configs:
 * - "intellij" (BUILTIN_IDE) — URL from settings (copied from Settings → Tools → MCP Server),
 *   toggled by the enableIntellijMcp setting.
 *
 * Additional configs:
 * - Parsed from the additionalMcpServers JSON string in settings.
 *   Each entry is `{ "name": "...", "url": "..." }`.
 */
class McpManager(
    private val client: OpenCodeClient,
    private val settings: OpenCodeSettingsState,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient
) {
    private val discovery = McpServerDiscovery(httpClient)
    private val registrar = McpRegistrar(client)
    private val toolList = McpToolList(scope, client)

    private val _serverStatuses = MutableStateFlow<Map<String, McpConnectionStatus>>(emptyMap())
    /** Per-server connection status. Key = server name. */
    val serverStatuses: StateFlow<Map<String, McpConnectionStatus>> = _serverStatuses.asStateFlow()
    /** Per-server tool lists. Key = server name. */
    val tools: StateFlow<Map<String, List<McpToolDescriptor>>> = toolList.tools

    /**
     * Build the list of enabled server configs from settings.
     *
     * For the built-in IntelliJ MCP:
     * - Included only if enableIntellijMcp is true
     * - URL override from mcpServerUrl if set
     *
     * For additional servers:
     * - Parsed from additionalMcpServers JSON
     * - Always included (enabled by default)
     */
    fun resolveConfigs(): List<McpServerConfig> {
        val configs = mutableListOf<McpServerConfig>()

        // Built-in IntelliJ MCP
        if (settings.enableIntellijMcp) {
            configs.add(McpServerConfig(
                name = ChatConstants.MCP_SERVER_NAME_INTELLIJ,
                type = McpServerType.BUILTIN_IDE,
                url = settings.mcpServerUrl.takeIf { it.isNotBlank() } ?: "",
                enabled = true
            ))
        }

        // Additional MCP servers from settings JSON
        if (settings.additionalMcpServers.isNotBlank()) {
            try {
                val array = mcpJson.parseToJsonElement(settings.additionalMcpServers).jsonArray
                for (element in array) {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                    configs.add(McpServerConfig(
                        name = name,
                        type = McpServerType.MANUAL_URL,
                        url = url,
                        enabled = true
                    ))
                }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] McpManager: failed to parse additionalMcpServers JSON" }
            }
        }

        return configs
    }

    /**
     * Initialize all enabled MCP servers.
     * Called after ProcessManager.initialize() succeeds.
     * Discovers, verifies, and registers each server independently.
     */
    suspend fun initialize() {
        val configs = resolveConfigs()
        for (config in configs) {
            registerServer(config)
        }
        val statuses = _serverStatuses.value
        val connected = statuses.values.count { it.state == McpConnectionState.CONNECTED }
        val errors = statuses.values.count { it.state == McpConnectionState.ERROR }
        logger.info { "[ACP] McpManager: initialization complete — $connected/${statuses.size} connected, $errors errors" }
    }

    /**
     * Discover, verify, and register a single MCP server.
     */
    private suspend fun registerServer(config: McpServerConfig) {
        updateState(config.name, McpConnectionState.DETECTING)

        // Discover MCP server
        val serverInfo = discovery.discover(config)
        if (serverInfo == null) {
            val errorMsg = when (config.type) {
                McpServerType.BUILTIN_IDE -> "IntelliJ MCP server not found. " +
                    "Copy the SSE URL from Settings → Tools → MCP Server and paste it in OpenCode settings."
                McpServerType.MANUAL_URL -> "MCP server '${config.name}' at ${config.url} is not responding."
            }
            updateState(config.name, McpConnectionState.ERROR, error = errorMsg)
            return
        }

        logger.info { "[ACP] MCP: found server '${config.name}' at ${serverInfo.url} (source: ${serverInfo.source})" }

        // Register with OpenCode
        updateState(config.name, McpConnectionState.REGISTERING)
        val registered = registrar.register(serverInfo)

        if (!registered) {
            updateState(config.name, McpConnectionState.ERROR,
                error = "Failed to register '${config.name}' with OpenCode. Check that the OpenCode server is running.")
            return
        }

        // Fetch tool list for display
        toolList.fetch(serverInfo)

        updateState(config.name, McpConnectionState.CONNECTED,
            serverInfo = serverInfo, toolCount = toolList.tools.value[config.name]?.size ?: 0)
        logger.info { "[ACP] MCP: registered '${config.name}' with OpenCode (${toolList.tools.value[config.name]?.size ?: 0} tools available)" }
    }

    /**
     * Disconnect a specific server — marks as unregistered locally.
     * The MCP server remains registered with OpenCode until the process restarts.
     */
    suspend fun disconnect(name: String) {
        registrar.markUnregistered(name)
        toolList.removeTools(name)
        updateState(name, McpConnectionState.DISCONNECTED)
        logger.info { "[ACP] MCP: disconnected '$name' (server-side registration persists until OpenCode restart)" }
    }

    /**
     * Disconnect the IntelliJ MCP server (convenience method).
     */
    suspend fun disconnectIntellij() {
        disconnect(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
    }

    /**
     * Retry a specific server after error.
     */
    suspend fun retry(name: String): Boolean {
        val configs = resolveConfigs()
        val config = configs.find { it.name == name } ?: return false
        registerServer(config)
        return _serverStatuses.value[name]?.state == McpConnectionState.CONNECTED
    }

    /**
     * Retry the IntelliJ MCP server (convenience method).
     */
    suspend fun retryIntellij(): Boolean {
        return retry(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
    }

    /**
     * Reset all state on OpenCode server restart.
     */
    suspend fun resetOnServerRestart() {
        registrar.resetOnServerRestart()
        toolList.reset()
        _serverStatuses.value = emptyMap()
    }

    private fun updateState(name: String, state: McpConnectionState, serverInfo: McpServerInfo? = null, toolCount: Int = 0, error: String? = null) {
        val current = _serverStatuses.value.toMutableMap()
        current[name] = McpConnectionStatus(
            name = name,
            state = state,
            serverInfo = serverInfo,
            toolCount = toolCount,
            error = error
        )
        _serverStatuses.value = current
    }
}