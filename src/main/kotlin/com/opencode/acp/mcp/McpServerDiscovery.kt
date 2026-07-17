package com.opencode.acp.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val serverVerifier: ServerVerifier = HttpServerVerifier(),
) {

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
        return serverVerifier.verify(info.url)
    }

    /**
     * Parse a URL into McpServerInfo.
     * Validates format and extracts port.
     */
    private fun parseUrl(url: String, name: String, source: DiscoverySource): McpServerInfo? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val parsed = URI(url).toURL()
            if (parsed.host != "localhost" && parsed.host != "127.0.0.1") {
                logger.debug { "[ACP] McpServerDiscovery: MCP server URL host '${parsed.host}' is not localhost — user-initiated SSRF risk" }
            }
            val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
            McpServerInfo(name = name, port = port, url = url, source = source)
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] McpServerDiscovery: URL parse failed for '$url'" }
            null
        }
    }
}