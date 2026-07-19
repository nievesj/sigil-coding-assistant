package com.opencode.acp.mcp

import com.opencode.acp.chat.model.ChatConstants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Verifies MCP server connectivity by checking the SSE endpoint.
 *
 * See TDD §4.2.4 — extracting this interface from [McpServerDiscovery] enables
 * testability without hitting real network endpoints. The default
 * implementation is [HttpServerVerifier], which uses raw [HttpURLConnection].
 */
interface ServerVerifier {
    /**
     * Verify that an MCP server is responding at the given URL.
     *
     * A 200 response with `Content-Type: text/event-stream` means the MCP
     * server is alive. A 404 means no MCP server at that URL. A connection
     * refused means the port is wrong.
     *
     * @param url The SSE endpoint URL (e.g. `http://127.0.0.1:64342/sse`).
     * @return true if the server is alive and serving an SSE endpoint.
     */
    suspend fun verify(url: String): Boolean
}

/**
 * Default [ServerVerifier] implementation using raw [HttpURLConnection].
 *
 * Uses raw [HttpURLConnection] instead of Ktor's HttpClient because the
 * Java HTTP engine buffers the entire response body before returning.
 * For an SSE endpoint (infinite stream), this means the Ktor request
 * never completes and hangs until the timeout. HttpURLConnection lets
 * us check the status code and Content-Type, then disconnect immediately.
 */
class HttpServerVerifier : ServerVerifier {

    private val verifyTimeoutMs: Long = ChatConstants.MCP_VERIFY_TIMEOUT_MS

    override suspend fun verify(url: String): Boolean {
        return withTimeoutOrNull(verifyTimeoutMs) {
            // Run blocking HttpURLConnection on Dispatchers.IO so the blocked thread
            // is an IO thread, not a Default thread. The blocking responseCode call
            // does not respect coroutine cancellation — withContext ensures the
            // blocked thread comes from the IO pool (which is designed for blocking).
            withContext(Dispatchers.IO) {
                try {
                    val conn = URI(url).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = verifyTimeoutMs.toInt()
                    conn.readTimeout = verifyTimeoutMs.toInt()
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.doInput = true
                    try {
                        val status = conn.responseCode
                        val contentType = conn.contentType ?: ""
                        val success = status == 200 && contentType.contains("text/event-stream", ignoreCase = true)
                        if (success) {
                            logger.info { "[ACP] HttpServerVerifier: verified MCP server at $url (status $status, $contentType)" }
                        } else {
                            logger.warn { "[ACP] HttpServerVerifier: MCP server at $url returned status $status, content-type '$contentType'" }
                        }
                        success
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] HttpServerVerifier: failed to connect to MCP server at $url" }
                    false
                }
            }
        } ?: run {
            logger.warn { "[ACP] HttpServerVerifier: verification timed out after ${verifyTimeoutMs}ms for $url" }
            false
        }
    }
}