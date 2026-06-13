package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

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
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val requestIdCounter = AtomicLong(0)

    suspend fun discoverTools(serverUrl: String): List<McpToolDescriptor> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = serverUrl.substringBeforeLast("/sse")
                val tools = discoverToolsViaSse(baseUrl)
                logger.info { "[ACP] McpToolDiscovery: discovered ${tools.size} tools from $serverUrl" }
                tools
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
     * Full SSE transport flow using raw HttpURLConnection for proper streaming.
     */
    private fun discoverToolsViaSse(baseUrl: String): List<McpToolDescriptor> {
        // Step 1: Open SSE connection
        val sseUrl = URI("$baseUrl/sse").toURL()
        val conn = sseUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.doInput = true

        try {
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                logger.warn { "[ACP] McpToolDiscovery: SSE connection returned HTTP $responseCode" }
                return emptyList()
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))

            // Step 2: Read endpoint event to get message URL
            var messageUrl: String? = null
            var isEndpointEvent = false
            while (true) {
                val line = reader.readLine() ?: break
                logger.debug { "[ACP] McpToolDiscovery SSE: $line" }
                when {
                    line.startsWith("event: endpoint") -> isEndpointEvent = true
                    line.startsWith("event: session") -> isEndpointEvent = true
                    line.startsWith("data: ") && isEndpointEvent -> {
                        messageUrl = line.removePrefix("data: ").trim()
                        break
                    }
                    line.isBlank() -> isEndpointEvent = false
                }
            }

            if (messageUrl == null) {
                logger.warn { "[ACP] McpToolDiscovery: no endpoint event in SSE response" }
                return emptyList()
            }

            logger.info { "[ACP] McpToolDiscovery: got endpoint URL: $messageUrl" }

            // Step 3: POST tools/list JSON-RPC request
            val requestId = requestIdCounter.incrementAndGet()
            val requestBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("method", "tools/list")
            }.toString()

            val postUrl = URI("$baseUrl$messageUrl").toURL()
            val postConn = postUrl.openConnection() as HttpURLConnection
            postConn.requestMethod = "POST"
            postConn.setRequestProperty("Content-Type", "application/json")
            postConn.doOutput = true
            postConn.connectTimeout = 5000
            postConn.readTimeout = 5000

            OutputStreamWriter(postConn.outputStream).use { it.write(requestBody) }
            val postCode = postConn.responseCode
            logger.info { "[ACP] McpToolDiscovery: POST tools/list returned HTTP $postCode" }
            postConn.disconnect()

            // Step 4: Read the JSON-RPC response from the SSE stream
            var responseData: String? = null
            var isMessageEvent = false
            while (true) {
                val line = reader.readLine() ?: break
                logger.debug { "[ACP] McpToolDiscovery SSE response: $line" }
                when {
                    line.startsWith("event: message") -> isMessageEvent = true
                    line.startsWith("data: ") && isMessageEvent -> {
                        responseData = line.removePrefix("data: ").trim()
                        break
                    }
                    line.isBlank() -> isMessageEvent = false
                    line.startsWith("event:") -> isMessageEvent = false
                }
            }

            if (responseData == null) {
                logger.warn { "[ACP] McpToolDiscovery: no tools/list response received from SSE stream" }
                return emptyList()
            }

            logger.info { "[ACP] McpToolDiscovery: got tools/list response (${responseData.length} chars)" }
            return parseToolsListResponse(responseData)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseToolsListResponse(responseBody: String): List<McpToolDescriptor> {
        return try {
            val obj = json.parseToJsonElement(responseBody).jsonObject
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
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] McpToolDiscovery: failed to parse tools/list response" }
            emptyList()
        }
    }
}
