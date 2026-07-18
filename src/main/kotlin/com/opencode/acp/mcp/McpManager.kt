package com.opencode.acp.mcp

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val settings: OpenCodeMcpSettingsState,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    internal val discovery: McpServerDiscovery = McpServerDiscovery(),
    internal val registrar: McpRegistrar = McpRegistrar(client),
    /**
     * Clock used for the background-retry total-timeout check. Defaults to
     * [System.currentTimeMillis] in production; tests using virtual time
     * (runTest) inject a clock backed by the test scheduler's `currentTime` so
     * the elapsed-time check advances with `advanceTimeBy`.
     */
    internal val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _serverStatuses = MutableStateFlow<Map<String, McpConnectionStatus>>(emptyMap())
    /** Per-server connection status. Key = server name. */
    val serverStatuses: StateFlow<Map<String, McpConnectionStatus>> = _serverStatuses.asStateFlow()

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

        // Built-in IntelliJ MCP — only include if a URL is configured.
        // Adding a config with an empty URL causes a cryptic "not found" error
        // in McpServerDiscovery.discover() — the user won't know the real
        // problem is a missing URL in settings.
        if (settings.enableIntellijMcp && settings.mcpServerUrl.isNotBlank()) {
            configs.add(McpServerConfig(
                name = ChatConstants.MCP_SERVER_NAME_INTELLIJ,
                type = McpServerType.BUILTIN_IDE,
                url = settings.mcpServerUrl,
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
                    // Defense-in-depth: validate URL is loopback/localhost only.
                    // The plugin's threat model assumes localhost-only communication
                    // (see OpenCodeService.kt:377-383). A malicious or corrupted settings
                    // file could point at internal endpoints (cloud metadata, admin ports).
                    if (!isLoopbackUrl(url)) {
                        logger.warn { "[ACP] McpManager: skipping MCP server '$name' — URL '$url' is not loopback/localhost" }
                        continue
                    }
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
     * Expose connected server URLs for ToolRegistry discovery.
     * Returns a map of serverName → SSE URL for all connected MCP servers.
     * Uses a unique key per server to prevent collisions.
     */
    fun getServerUrls(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val usedKeys = mutableSetOf<String>()
        for (status in _serverStatuses.value.values) {
            if (status.state != McpConnectionState.CONNECTED) continue
            val info = status.serverInfo ?: continue
            val baseKey = info.name.ifEmpty { "mcp_${status.name}" }
            // Collision-safe: only suffix with index if the base key is already used
            var key = baseKey
            var idx = 0
            while (key in usedKeys) {
                idx++
                key = "${baseKey}_$idx"
            }
            usedKeys.add(key)
            result[key] = info.url
        }
        return result
    }

    /**
     * Initialize all enabled MCP servers.
     * Called after ProcessManager.initialize() succeeds.
     * Discovers, verifies, and registers each server independently.
     *
     * For BUILTIN_IDE servers (JetBrains MCP Server), if the initial connection
     * fails, a background retry with exponential backoff is launched on the scope.
     * The JetBrains MCP Server starts asynchronously and may not be ready when
     * this method runs. The background retry continues for up to
     * [ChatConstants.MCP_RETRY_TOTAL_TIMEOUT_MS] (60s) so the chat UI isn't
     * blocked waiting for it. When the server eventually connects, [onServerConnected]
     * is called so the caller can re-run tool discovery.
     *
     * @param onServerConnected Called when a server connects after a delayed retry.
     *   The caller (OpenCodeService) uses this to re-run [discoverToolsInBackground]
     *   so MCP tools become available without restarting the session.
     */
    suspend fun initialize(onServerConnected: (suspend () -> Unit)? = null) {
        val configs = resolveConfigs()
        for (config in configs) {
            registerServer(config, onServerConnected)
        }
        val statuses = _serverStatuses.value
        val connected = statuses.values.count { it.state == McpConnectionState.CONNECTED }
        val errors = statuses.values.count { it.state == McpConnectionState.ERROR }
        logger.info { "[ACP] McpManager: initialization complete — $connected/${statuses.size} connected, $errors errors" }
    }

    /**
     * Discover, verify, and register a single MCP server.
     *
     * For BUILTIN_IDE servers, if discovery fails (server not ready), a background
     * retry with exponential backoff is launched. This handles the startup race
     * where the JetBrains MCP Server hasn't finished starting when the plugin
     * initializes. The retry runs on [scope] and calls [onServerConnected] when
     * the server eventually connects, so the caller can re-discover tools.
     */
    private suspend fun registerServer(
        config: McpServerConfig,
        onServerConnected: (suspend () -> Unit)? = null,
    ) {
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

            // For BUILTIN_IDE servers, launch a background retry — the JetBrains MCP
            // Server starts asynchronously and may not be ready yet. Don't block the
            // chat UI waiting for it; retry in the background and re-discover tools
            // when it connects.
            if (config.type == McpServerType.BUILTIN_IDE && onServerConnected != null) {
                launchBackgroundRetry(config, onServerConnected)
            }
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

        // No toolList.fetch() — tool count comes from ToolRegistry after discoverAll()
        updateState(config.name, McpConnectionState.CONNECTED,
            serverInfo = serverInfo, toolCount = 0)
        logger.info { "[ACP] MCP: registered '${config.name}' with OpenCode" }
    }

    /**
     * Background retry for BUILTIN_IDE servers that failed initial discovery.
     *
     * Uses exponential backoff: 2s → 4s → 8s → 10s (cap), for up to 60s total.
     * When the server connects, calls [onServerConnected] so the caller can
     * re-run tool discovery (MCP tools become available without session restart).
     *
     * The retry is cancelled when the scope is cancelled (IDE shutdown, tool
     * window dispose) or when the server connects successfully.
     */
    private fun launchBackgroundRetry(
        config: McpServerConfig,
        onServerConnected: suspend () -> Unit,
    ) {
        scope.launch {
            var delayMs = ChatConstants.MCP_RETRY_INITIAL_DELAY_MS
            val startTime = clock()
            var attempt = 1

            logger.info { "[ACP] McpManager: starting background retry for '${config.name}' — " +
                "JetBrains MCP Server may not be ready yet (attempt 1 in ${delayMs}ms)" }

            while (isActive) {
                delay(delayMs)

                val elapsed = clock() - startTime
                // NOTE: The total-timeout check happens AFTER the delay, so the total
                // retry window can exceed MCP_RETRY_TOTAL_TIMEOUT_MS by up to one delay
                // interval (max 10s). This is acceptable — the extra attempt is a
                // best-effort retry, not a correctness issue.
                if (elapsed > ChatConstants.MCP_RETRY_TOTAL_TIMEOUT_MS) {
                    logger.warn { "[ACP] McpManager: background retry for '${config.name}' gave up after " +
                        "${elapsed}ms — JetBrains MCP Server never became available" }
                    return@launch
                }

                logger.info { "[ACP] McpManager: background retry attempt ${attempt} for '${config.name}' " +
                    "(elapsed ${elapsed}ms, next delay ${delayMs}ms)" }

                updateState(config.name, McpConnectionState.DETECTING)
                val serverInfo = discovery.discover(config)
                if (serverInfo == null) {
                    // Still not ready — back off
                    updateState(config.name, McpConnectionState.ERROR,
                        error = "IntelliJ MCP server not found. Retrying in ${delayMs}ms...")
                    attempt++
                    delayMs = (delayMs * 2).coerceAtMost(ChatConstants.MCP_RETRY_MAX_DELAY_MS)
                    continue
                }

                // Server is now available — register it
                logger.info { "[ACP] MCP: found server '${config.name}' at ${serverInfo.url} " +
                    "(source: ${serverInfo.source}) — connected after ${attempt} retry attempt(s)" }
                updateState(config.name, McpConnectionState.REGISTERING)
                val registered = registrar.register(serverInfo)
                if (registered) {
                    updateState(config.name, McpConnectionState.CONNECTED,
                        serverInfo = serverInfo, toolCount = 0)
                    logger.info { "[ACP] MCP: registered '${config.name}' with OpenCode (via background retry)" }
                    // Re-run tool discovery so MCP tools become available immediately
                    try {
                        onServerConnected()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "[ACP] McpManager: onServerConnected callback failed for '${config.name}'" }
                    }
                } else {
                    updateState(config.name, McpConnectionState.ERROR,
                        error = "Failed to register '${config.name}' with OpenCode after retry")
                    logger.warn { "[ACP] McpManager: background retry for '${config.name}' — " +
                        "server found but registration failed" }
                }
                return@launch
            }
        }
    }

    /**
     * Disconnect a specific server — marks as unregistered locally.
     * The MCP server remains registered with OpenCode until the process restarts.
     */
    suspend fun disconnect(name: String) {
        registrar.markUnregistered(name)
        updateState(name, McpConnectionState.DISCONNECTED)
        logger.info { "[ACP] MCP: disconnected '$name' (server-side registration persists until OpenCode restart)" }
    }

    /**
     * Retry a specific server after error.
     */
    suspend fun retry(name: String): Boolean {
        val configs = resolveConfigs()
        val config = configs.find { it.name == name } ?: return false
        return try {
            registerServer(config, onServerConnected = null)
            _serverStatuses.value[name]?.state == McpConnectionState.CONNECTED
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] McpManager.retry: failed for '$name'" }
            false
        }
    }

    /**
     * Update tool counts in serverStatuses from ToolRegistry after discovery.
     */
    fun updateToolCounts(registry: ToolRegistry?) {
        if (registry == null) return
        val mcpToolsByServer = registry.getMcpToolsByServer()
        _serverStatuses.update { current ->
            val updated = current.toMutableMap()
            for ((name, status) in updated) {
                if (status.state == McpConnectionState.CONNECTED) {
                    val toolCount = mcpToolsByServer[name]?.size ?: 0
                    updated[name] = status.copy(toolCount = toolCount)
                }
            }
            updated
        }
    }

    /**
     * Reset all state on OpenCode server restart.
     */
    suspend fun resetOnServerRestart() {
        registrar.resetOnServerRestart()
        _serverStatuses.value = emptyMap()
    }

    private fun updateState(name: String, state: McpConnectionState, serverInfo: McpServerInfo? = null, toolCount: Int = 0, error: String? = null) {
        // Use atomic update to prevent lost updates when concurrent coroutines
        // (e.g., background retry + initialize loop) modify the map simultaneously.
        _serverStatuses.update { current ->
            current.toMutableMap().apply {
                this[name] = McpConnectionStatus(
                    name = name,
                    state = state,
                    serverInfo = serverInfo,
                    toolCount = toolCount,
                    error = error
                )
            }
        }
    }

    /**
     * Validate that a URL points to a loopback or localhost address.
     * Defense-in-depth against SSRF (CWE-918) — the plugin's threat model
     * assumes localhost-only communication. Rejects cloud metadata endpoints
     * (169.254.169.254), internal admin ports, and external addresses.
     */
    private fun isLoopbackUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://localhost") ||
            lower.startsWith("https://127.0.0.1") ||
            lower.startsWith("https://localhost")
    }
}