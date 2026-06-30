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
 * Discovers MCP server endpoints by resolving [McpServerConfig] entries
 * to verified [McpServerInfo] instances.
 *
 * For BUILTIN_IDE configs: the user must provide the SSE URL from
 * Settings → Tools → MCP Server in IntelliJ ("Copy SSE Config").
 * The JetBrains MCP Server runs on its own port (e.g., 64342),
 * separate from the IDE's built-in web server (port 63342).
 * BuiltInServerManager.getPort() returns the web server port, NOT the MCP port.
 *
 * Verification is done by connecting to the SSE endpoint using raw
 * [HttpURLConnection] — NOT Ktor's HttpClient. The Java HTTP engine
 * buffers the entire response body before returning, which hangs on
 * SSE endpoints (infinite streams). HttpURLConnection lets us check
 * the status code and Content-Type header, then disconnect immediately
 * without reading the body.
 */
class McpServerDiscovery(
    @Suppress("UNUSED_PARAMETER") httpClient: io.ktor.client.HttpClient
) {
    companion object {
        private const val VERIFY_TIMEOUT_MS = ChatConstants.MCP_VERIFY_TIMEOUT_MS
    }

    /**
     * Resolve a single McpServerConfig to a verified McpServerInfo.
     * Returns null if the server cannot be found or verified.
     */
    suspend fun discover(config: McpServerConfig): McpServerInfo? {
        return when (config.type) {
            McpServerType.BUILTIN_IDE -> discoverBuiltinIde(config)
            McpServerType.MANUAL_URL -> discoverManualUrl(config)
        }
    }

    /**
     * Discover IntelliJ's built-in MCP server.
     *
     * The JetBrains MCP Server runs on its own port (shown in
     * Settings → Tools → MCP Server). The user must provide this URL
     * in the OpenCode settings (mcpServerUrl field).
     *
     * The URL should be the SSE endpoint (e.g., http://127.0.0.1:64342/sse).
     */
    private suspend fun discoverBuiltinIde(config: McpServerConfig): McpServerInfo? {
        if (config.url.isBlank()) {
            logger.warn { "[ACP] McpServerDiscovery: IntelliJ MCP URL not configured. " +
                "Copy the SSE URL from Settings → Tools → MCP Server and paste it in OpenCode settings." }
            return null
        }
        val parsed = parseUrl(config.url, config.name, DiscoverySource.BUILTIN_IDE) ?: return null
        return if (verifyMcpServer(parsed)) parsed else null
    }

    /**
     * Discover a manually configured MCP server.
     * Validates URL format and verifies the endpoint.
     */
    private suspend fun discoverManualUrl(config: McpServerConfig): McpServerInfo? {
        val url = config.url.takeIf { it.isNotBlank() } ?: return null
        val parsed = parseUrl(url, config.name, DiscoverySource.MANUAL) ?: return null
        return if (verifyMcpServer(parsed)) parsed else null
    }

    /**
     * Verify the MCP server is responding by checking its SSE endpoint.
     *
     * Uses raw [HttpURLConnection] instead of Ktor's HttpClient because the
     * Java HTTP engine buffers the entire response body before returning.
     * For an SSE endpoint (infinite stream), this means the Ktor request
     * never completes and hangs until the timeout. HttpURLConnection lets
     * us check the status code and Content-Type, then disconnect immediately.
     *
     * A 200 with Content-Type: text/event-stream means the MCP server is alive.
     * A 404 means no MCP server at that URL.
     * A connection refused means the port is wrong.
     */
    private suspend fun verifyMcpServer(info: McpServerInfo): Boolean {
        return withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            // Run blocking HttpURLConnection on Dispatchers.IO so the blocked thread
            // is an IO thread, not a Default thread. The blocking responseCode call
            // does not respect coroutine cancellation — withContext ensures the
            // blocked thread comes from the IO pool (which is designed for blocking).
            withContext(Dispatchers.IO) {
                try {
                    val conn = URI(info.url).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = VERIFY_TIMEOUT_MS.toInt()
                    conn.readTimeout = VERIFY_TIMEOUT_MS.toInt()
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.doInput = true
                    try {
                        val status = conn.responseCode
                        val contentType = conn.contentType ?: ""
                        val success = status == 200 && contentType.contains("text/event-stream", ignoreCase = true)
                        if (success) {
                            logger.info { "[ACP] McpServerDiscovery: verified MCP server at ${info.url} (status $status, $contentType)" }
                        } else {
                            logger.warn { "[ACP] McpServerDiscovery: MCP server at ${info.url} returned status $status, content-type '$contentType'" }
                        }
                        success
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] McpServerDiscovery: failed to connect to MCP server at ${info.url}" }
                    false
                }
            }
        } ?: run {
            logger.warn { "[ACP] McpServerDiscovery: verification timed out after ${VERIFY_TIMEOUT_MS}ms for ${info.url}" }
            false
        }
    }

    /**
     * Parse a URL into McpServerInfo.
     * Validates format and extracts port.
     */
    private fun parseUrl(url: String, name: String, source: DiscoverySource): McpServerInfo? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val parsed = URI(url).toURL()
            val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
            McpServerInfo(name = name, port = port, url = url, source = source)
        } catch (e: Exception) {
            null
        }
    }
}