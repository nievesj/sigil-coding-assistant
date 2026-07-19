package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * SSE transport interface for MCP tool discovery.
 *
 * Replaces raw [HttpURLConnection] usage in [McpToolDiscovery], enabling
 * testability without hitting real network endpoints. See TDD §4.2.4.
 *
 * The MCP SSE transport flow:
 * 1. GET /sse → SSE stream stays open
 * 2. Server sends: `event: endpoint\ndata: /message?sessionId=xxx`
 * 3. Client POSTs JSON-RPC to the message URL → server returns "Accepted"
 * 4. Server sends the response as an SSE event: `event: message\ndata: {...}`
 * 5. Client reads the response from the SSE stream
 */
interface SseTransport {
    /**
     * Open an SSE connection to the given base URL.
     *
     * @param baseUrl The server base URL (without the `/sse` suffix).
     * @return An [SseConnection] for sending JSON-RPC requests and reading
     *         responses from the SSE stream.
     */
    suspend fun connect(baseUrl: String): SseConnection
}

/**
 * A single SSE connection to an MCP server.
 *
 * The connection holds an open SSE stream and exposes a request/response
 * pattern for JSON-RPC calls. The server responds to POSTed requests by
 * emitting `event: message` events on the SSE stream.
 */
interface SseConnection {
    /**
     * Send a JSON-RPC request and wait for the response on the SSE stream.
     *
     * @param baseUrl The server base URL (for resolving the message endpoint URL).
     * @param method The JSON-RPC method (e.g. "tools/list").
     * @param params The JSON-RPC params (may be null).
     * @return The response JSON element, or null if no response was received.
     */
    suspend fun sendRequest(baseUrl: String, method: String, params: JsonElement?): JsonElement?

    /** Close the SSE connection and release resources. */
    fun close()
}

/**
 * Default [SseTransport] implementation using raw [HttpURLConnection].
 *
 * Uses java.net.HttpURLConnection for SSE streaming (Ktor's Java engine
 * buffers the response body, breaking SSE line-by-line reads).
 */
class HttpUrlSseTransport : SseTransport {

    override suspend fun connect(baseUrl: String): SseConnection {
        val sseUrl = URI("$baseUrl/sse").toURL()
        val conn = sseUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.doInput = true

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            logger.warn { "[ACP] HttpUrlSseTransport: SSE connection returned HTTP $responseCode" }
            conn.disconnect()
            throw java.io.IOException("SSE connection returned HTTP $responseCode")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream))

        // Read the endpoint event to get the message URL.
        var messageUrl: String? = null
        var isEndpointEvent = false
        while (true) {
            val line = reader.readLine() ?: break
            logger.debug { "[ACP] HttpUrlSseTransport SSE: $line" }
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
            logger.warn { "[ACP] HttpUrlSseTransport: no endpoint event in SSE response" }
            reader.close()
            conn.disconnect()
            throw java.io.IOException("No endpoint event in SSE response")
        }

        logger.info { "[ACP] HttpUrlSseTransport: got endpoint URL: $messageUrl" }
        return HttpUrlSseConnection(conn, reader, messageUrl)
    }
}

/**
 * [SseConnection] backed by a raw [HttpURLConnection] SSE stream.
 */
private class HttpUrlSseConnection(
    private val conn: HttpURLConnection,
    private val reader: BufferedReader,
    private val messageUrl: String,
) : SseConnection {
    private val requestIdCounter = AtomicLong(0)

    override suspend fun sendRequest(baseUrl: String, method: String, params: JsonElement?): JsonElement? {
        val requestId = requestIdCounter.incrementAndGet()
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

        // POST the JSON-RPC request to the message endpoint.
        // SSRF guard: messageUrl comes from the MCP server's SSE stream (untrusted).
        // Reject absolute URLs and protocol-relative URLs that could redirect requests
        // to an attacker-controlled server.
        require(messageUrl.startsWith("/") && !messageUrl.startsWith("//")) {
            "Invalid messageUrl from MCP server: '$messageUrl' — must be a relative path starting with '/'"
        }
        val postUrl = URI("$baseUrl$messageUrl").toURL()
        val postConn = postUrl.openConnection() as HttpURLConnection
        postConn.requestMethod = "POST"
        postConn.setRequestProperty("Content-Type", "application/json")
        postConn.doOutput = true
        postConn.connectTimeout = 5000
        postConn.readTimeout = 5000

        OutputStreamWriter(postConn.outputStream, Charsets.UTF_8).use { it.write(requestBody) }
        val postCode = postConn.responseCode
        logger.info { "[ACP] HttpUrlSseTransport: POST $method returned HTTP $postCode" }
        postConn.disconnect()

        // Read the JSON-RPC response from the SSE stream.
        var responseData: String? = null
        var isMessageEvent = false
        while (true) {
            val line = reader.readLine() ?: break
            logger.debug { "[ACP] HttpUrlSseTransport SSE response: $line" }
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
            logger.warn { "[ACP] HttpUrlSseTransport: no $method response received from SSE stream" }
            return null
        }

        logger.info { "[ACP] HttpUrlSseTransport: got $method response (${responseData.length} chars)" }
        return kotlinx.serialization.json.Json.parseToJsonElement(responseData)
    }

    override fun close() {
        try { reader.close() } catch (_: Exception) {}
        conn.disconnect()
    }
}