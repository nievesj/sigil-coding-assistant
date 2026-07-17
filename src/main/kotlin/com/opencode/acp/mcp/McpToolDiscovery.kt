package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Discovers tools from MCP servers using the MCP SSE transport protocol.
 *
 * Uses java.net.HttpURLConnection for SSE streaming (Ktor's Java engine
 * buffers the response body, breaking SSE line-by-line reads).
 *
 * The MCP SSE transport flow:
 * 1. GET /sse → SSE stream stays open
 * 2. Server sends: event: endpoint\ndata: /message?sessionId=xxx
 * 3. Client POSTs JSON-RPC to /message?sessionId=xxx → server returns "Accepted"
 * 4. Server sends response as SSE event: event: message\ndata: {"jsonrpc":"2.0",...}
 * 5. Client reads the response from the SSE stream
 */
class McpToolDiscovery(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val sseTransport: SseTransport = HttpUrlSseTransport(),
) {

    suspend fun discoverTools(serverUrl: String): List<McpToolDescriptor> {
        return withContext(Dispatchers.IO) {
            try {
                if (!serverUrl.contains("/sse")) {
                    logger.warn { "[ACP] McpToolDiscovery: serverUrl '$serverUrl' does not contain '/sse' — SSE connection may fail" }
                }
                val baseUrl = serverUrl.substringBeforeLast("/sse")
                val tools = discoverToolsViaSse(baseUrl)
                logger.info { "[ACP] McpToolDiscovery: discovered ${tools.size} tools from $serverUrl" }
                tools
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] McpToolDiscovery: failed to discover tools from $serverUrl" }
                emptyList()
            }
        }
    }

    suspend fun discoverAllTools(serverUrls: Map<String, String>): Map<String, List<McpToolDescriptor>> {
        return coroutineScope {
            serverUrls.map { (name, url) ->
                async {
                    val tools = discoverTools(url)
                    name to tools
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * Full SSE transport flow using the injected [sseTransport].
     * Delegates the SSE connect/POST/read cycle to [SseTransport] for testability.
     */
    private suspend fun discoverToolsViaSse(baseUrl: String): List<McpToolDescriptor> {
        val connection = sseTransport.connect(baseUrl)
        try {
            val response = connection.sendRequest(baseUrl, "tools/list", null)
                ?: run {
                    logger.warn { "[ACP] McpToolDiscovery: no tools/list response received from SSE stream" }
                    return emptyList()
                }
            logger.info { "[ACP] McpToolDiscovery: got tools/list response (${response.toString().length} chars)" }
            return parseToolsListResponse(response.toString())
        } finally {
            connection.close()
        }
    }

    private fun parseToolsListResponse(responseBody: String): List<McpToolDescriptor> {
        return try {
            val obj = json.parseToJsonElement(responseBody).jsonObject
            val error = obj["error"]?.jsonObject
            if (error != null) {
                logger.warn { "[ACP] McpToolDiscovery: JSON-RPC error from server: ${error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown"}" }
                return emptyList()
            }
            val result = obj["result"]?.jsonObject ?: return emptyList()
            val toolsArray = result["tools"]?.jsonArray ?: return emptyList()

            toolsArray.mapNotNull { element ->
                val toolObj = element.jsonObject
                val name = toolObj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val description = toolObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val inputSchema = toolObj["inputSchema"]?.toString()

                McpToolDescriptor(
                    name = name,
                    description = description,
                    inputSchema = inputSchema
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] McpToolDiscovery: failed to parse tools/list response" }
            emptyList()
        }
    }
}
