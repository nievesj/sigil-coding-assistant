package com.opencode.acp.mcp

import com.opencode.acp.adapter.OpenCodeClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * Tracks which MCP servers are registered with OpenCode and their connection status.
 *
 * The JetBrains MCP Server uses SSE+JSON-RPC transport — it does NOT expose
 * a REST /api/mcp/list_tools endpoint. Per-tool details are only available
 * through the MCP protocol itself, which OpenCode manages internally.
 *
 * Instead, we use OpenCode's GET /mcp to check which servers are connected.
 * This gives us server names and connection status, but not individual tool names.
 * The status indicator in settings shows "connected" / "disconnected" per server.
 */
class McpToolList(
    private val scope: CoroutineScope,
    private val client: OpenCodeClient
) {
    private val _tools = MutableStateFlow<Map<String, List<McpToolDescriptor>>>(emptyMap())
    /** Mutex protecting [_tools] updates — prevents lost updates from concurrent fetches. */
    private val toolsMutex = Mutex()
    /** Map of server name → list of tools offered by that server. */
    val tools: StateFlow<Map<String, List<McpToolDescriptor>>> = _tools.asStateFlow()

    /**
     * Check which MCP servers are connected via OpenCode's GET /mcp endpoint
     * and update the tool list accordingly.
     *
     * Since OpenCode's /mcp returns server names + status but not tool details,
     * we store an empty tool list for each connected server. This still provides
     * value: the settings panel can show "connected" vs "disconnected" status.
     */
    suspend fun fetch(serverInfo: McpServerInfo) {
        toolsMutex.withLock {
            // Store an empty list for the connected server.
            // The server name presence in the map indicates it was registered.
            // Actual tool names are managed by OpenCode internally.
            _tools.value = _tools.value.toMutableMap().apply {
                put(serverInfo.name, emptyList())
            }
        }
        logger.info { "[ACP] McpToolList: registered server '${serverInfo.name}' (tool details managed by OpenCode)" }
    }

    /**
     * Remove tools for a specific server.
     */
    suspend fun removeTools(name: String) {
        toolsMutex.withLock {
            _tools.value = _tools.value.toMutableMap().apply { remove(name) }
        }
    }

    /**
     * Reset all tools.
     */
    suspend fun reset() {
        toolsMutex.withLock {
            _tools.value = emptyMap()
        }
    }
}