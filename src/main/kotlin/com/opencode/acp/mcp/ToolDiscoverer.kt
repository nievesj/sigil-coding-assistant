package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

private val discovererLogger = KotlinLogging.logger {}

/**
 * Discovers tools from OpenCode's built-in endpoint and MCP servers.
 *
 * Extracted from [ToolRegistry] per TDD §4.2.5 (SRP: Split ToolRegistry).
 * Owns the HTTP discovery logic and the built-in tool description catalog.
 * Does NOT own the tool snapshot — that lives in [ToolRegistry], which
 * passes its mutex and snapshot reference so [discoverAll] can swap atomically.
 */
class ToolDiscoverer(
    private val httpClient: HttpClient,
    private val mcpToolDiscovery: McpToolDiscovery,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    /**
     * Guard against concurrent [discoverAll] invocations. The HTTP discovery calls
     * happen outside the mutex, so concurrent callers would both execute HTTP requests
     * and one's results would be silently discarded. This flag enforces single-caller
     * semantics at the class level, rather than relying solely on the external
     * `isDiscovering` flag in OpenCodeMcpConfigurable.
     */
    private val discovering = AtomicBoolean(false)

    /**
     * Discover all available tools from OpenCode and MCP servers.
     * Uses a two-phase approach: discover into a temporary map, then swap atomically.
     * This prevents partial data loss if discovery fails partway — the previous
     * full set is preserved until the new set is complete.
     *
     * NOTE: This method is NOT internally synchronized — the mutex only guards the
     * snapshot swap, not the discovery HTTP calls. Callers MUST serialize invocations
     * (e.g., via the `isDiscovering` flag in OpenCodeMcpConfigurable). Concurrent
     * calls may result in wasted HTTP requests and one call's results being discarded.
     *
     * @param opencodeBaseUrl The base URL of the OpenCode server (e.g., "http://127.0.0.1:4096")
     * @param mcpServerUrls Map of MCP server name to SSE URL
     * @param toolsMutex Mutex guarding the snapshot swap (owned by [ToolRegistry])
     * @param snapshotRef Current snapshot reference (owned by [ToolRegistry])
     * @param snapshotSetter Atomically assigns the new snapshot
     * @return the list of discovered tools
     */
    suspend fun discoverAll(
        opencodeBaseUrl: String,
        mcpServerUrls: Map<String, String>,
        toolsMutex: Mutex,
        snapshotRef: () -> Map<String, ToolInfo>,
        snapshotSetter: (Map<String, ToolInfo>) -> Unit
    ): List<ToolInfo> {
        // Guard: reject concurrent discovery calls to prevent wasted HTTP requests
        // and silent result discarding.
        if (!discovering.compareAndSet(false, true)) {
            discovererLogger.warn { "[ACP] ToolDiscoverer: discoverAll() called while already in flight — skipping" }
            return snapshotRef().values.toList()
        }
        try {
            // Phase 1: Discover into a temporary map (no lock needed)
            val newTools = mutableMapOf<String, ToolInfo>()
            discoverBuiltinTools(opencodeBaseUrl, newTools)
            discoverMcpTools(mcpServerUrls, newTools)

            // Phase 2: Atomic swap — merge persisted permissions from the old
            // snapshot into the new one, then assign under lock. This prevents
            // loadPermissions() changes from being silently overwritten when
            // discoverAll() completes (the race where loadPermissions runs during
            // the HTTP discovery phase and its changes are lost on swap).
            toolsMutex.withLock {
                val oldSnapshot = snapshotRef()
                // Re-apply permissions from the old snapshot to matching tools in the new set.
                // Match by both compound id and raw name to handle all key formats.
                for ((oldId, oldTool) in oldSnapshot) {
                    // Try exact id match first
                    val newTool = newTools[oldId]
                        // Then try raw name match (handles tools whose serverName changed)
                        ?: newTools.values.find { it.name == oldTool.name }
                    if (newTool != null) {
                        newTools[newTool.id] = newTool.copy(
                            enabled = oldTool.enabled,
                            permission = oldTool.permission
                        )
                    }
                }
                snapshotSetter(newTools.toMap())  // Immutable snapshot
            }

            discovererLogger.info { "[ACP] ToolDiscoverer: discovered ${newTools.size} total tools" }
            return newTools.values.toList()
        } finally {
            discovering.set(false)
        }
    }

    /**
     * Discover built-in tools from OpenCode's /experimental/tool/ids endpoint.
     */
    private suspend fun discoverBuiltinTools(opencodeBaseUrl: String, newTools: MutableMap<String, ToolInfo>) {
        try {
            val response = httpClient.get("$opencodeBaseUrl/experimental/tool/ids")
            if (!response.status.isSuccess()) {
                discovererLogger.warn { "[ACP] ToolDiscoverer: /experimental/tool/ids returned ${response.status}" }
                return
            }

            val body = response.bodyAsText()
            val toolIds = parseToolIds(body)

            for (toolId in toolIds) {
                val tool = ToolInfo.create(
                    name = toolId,
                    description = getBuiltinToolDescription(toolId),
                    source = ToolSource.BUILTIN,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
                newTools[tool.id] = tool
            }

            discovererLogger.info { "[ACP] ToolDiscoverer: discovered ${toolIds.size} built-in tools" }
        } catch (e: Exception) {
            discovererLogger.warn(e) { "[ACP] ToolDiscoverer: failed to discover built-in tools" }
        }
    }

    /**
     * Parse the response from /experimental/tool/ids.
     *
     * The server returns a bare JSON array: ["bash", "read", ...]
     * Fallback: wrapped format {"value": [...]}
     */
    private fun parseToolIds(body: String): List<String> {
        return try {
            val element = json.parseToJsonElement(body)
            val array = if (element is kotlinx.serialization.json.JsonArray) {
                element
            } else {
                element.jsonObject["value"]?.jsonArray ?: return emptyList()
            }
            array.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discovererLogger.warn(e) { "[ACP] ToolDiscoverer: failed to parse tool IDs" }
            emptyList()
        }
    }

    /**
     * Get a human-readable description for a built-in tool.
     */
    fun getBuiltinToolDescription(toolId: String): String {
        return when (toolId) {
            "bash" -> "Execute shell commands"
            "read" -> "Read file contents"
            "glob" -> "Find files by pattern"
            "grep" -> "Search file contents with regex"
            "edit" -> "Edit files with string replacement"
            "write" -> "Write new files"
            "task" -> "Launch specialized agents"
            "webfetch" -> "Fetch URLs and extract content"
            "todowrite" -> "Manage task lists"
            "websearch" -> "Search the web"
            "skill" -> "Load specialized workflows"
            "apply_patch" -> "Apply patches to files"
            "council_session" -> "Multi-LLM consensus engine"
            "auto_continue" -> "Toggle auto-continuation"
            "ast_grep_search" -> "AST-aware code search"
            "ast_grep_replace" -> "AST-aware code replacement"
            "subtask" -> "Run child worker sessions"
            "read_session" -> "Read conversation transcripts"
            else -> "Built-in tool: $toolId"
        }
    }

    /**
     * Discover tools from MCP servers using the MCP protocol.
     */
    private suspend fun discoverMcpTools(mcpServerUrls: Map<String, String>, newTools: MutableMap<String, ToolInfo>) {
        val mcpTools = mcpToolDiscovery.discoverAllTools(mcpServerUrls)

        for ((serverName, toolDescriptors) in mcpTools) {
            for (tool in toolDescriptors) {
                val toolInfo = ToolInfo.create(
                    name = tool.name,
                    description = tool.description,
                    source = ToolSource.MCP,
                    serverName = serverName,
                    enabled = true,
                    permission = ToolPermission.ALLOW
                )
                newTools[toolInfo.id] = toolInfo
            }
        }
    }
}