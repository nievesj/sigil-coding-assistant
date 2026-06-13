package com.opencode.acp.mcp

import com.opencode.acp.adapter.OpenCodeClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages registration of MCP servers with OpenCode.
 *
 * IMPORTANT LIMITATIONS:
 * - DELETE /mcp/:name does NOT exist in the OpenCode server API.
 *   Toggling a server off only updates local state — the MCP server
 *   remains registered server-side until the OpenCode process restarts.
 * - GET /mcp does NOT return dynamically added servers (GH #19244/#7482).
 *   We track registration state locally via [registeredServers].
 * - Re-registering with POST /mcp while already registered may create
 *   a duplicate or error. We skip registration if already registered locally.
 */
class McpRegistrar(
    private val client: OpenCodeClient
) {
    /** Set of server names that have been successfully registered. Thread-safe. */
    private val registeredServers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Register an MCP server with OpenCode.
     * Returns true if registration succeeded or was already done.
     */
    suspend fun register(serverInfo: McpServerInfo): Boolean {
        if (serverInfo.name in registeredServers) {
            logger.info { "[ACP] McpRegistrar: '${serverInfo.name}' already registered locally — skipping" }
            return true
        }

        val config = buildJsonObject {
            put("name", serverInfo.name)
            put("config", buildJsonObject {
                put("type", "remote")
                put("url", serverInfo.url)
                // oauth: false is CRITICAL — OpenCode enables OAuth auto-detection
                // by default for all remote servers. MCP servers on localhost
                // do not use OAuth. Without this, OpenCode will attempt
                // an OAuth flow that hangs or fails, preventing tool registration.
                put("oauth", false)
                put("enabled", true)
                put("timeout", 5000)
            })
        }

        return try {
            val success = client.addMcpServer(config)
            if (success) {
                registeredServers.add(serverInfo.name)
                logger.info { "[ACP] McpRegistrar: registered MCP server '${serverInfo.name}' at ${serverInfo.url}" }
            }
            success
        } catch (e: Exception) {
            logger.error(e) { "[ACP] McpRegistrar: registration failed for '${serverInfo.name}'" }
            false
        }
    }

    /**
     * Mark a server as unregistered locally.
     * NOTE: This does NOT remove the server from OpenCode — there is no
     * DELETE /mcp/:name endpoint. The server remains registered until
     * the OpenCode process restarts.
     */
    fun markUnregistered(name: String) {
        registeredServers.remove(name)
        logger.info { "[ACP] McpRegistrar: marked '$name' as unregistered locally" }
    }

    /**
     * Mark all servers as unregistered locally.
     */
    fun markAllUnregistered() {
        registeredServers.clear()
        logger.info { "[ACP] McpRegistrar: marked all servers as unregistered locally" }
    }

    /**
     * Reset state on OpenCode server restart.
     * Called when ProcessManager detects the OpenCode process was restarted.
     */
    fun resetOnServerRestart() {
        registeredServers.clear()
    }

    /** Test-only: check if a server name is in the registered set. */
    internal fun isRegisteredForTest(name: String): Boolean = name in registeredServers

    /** Test-only: add a server name to the registered set. */
    internal fun setRegisteredForTest(name: String) { registeredServers.add(name) }
}