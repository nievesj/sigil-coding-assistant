package com.opencode.acp.mcp

/**
 * Configuration for an MCP server to register with OpenCode.
 * Each config has a unique name, a discovery type, and an SSE URL.
 */
data class McpServerConfig(
    val name: String,            // "intellij", "github", "slack" — unique ID for POST /mcp
    val type: McpServerType,     // How this server is discovered
    val url: String = "",        // SSE URL (e.g., http://127.0.0.1:64342/sse)
    val enabled: Boolean = true  // Whether to register this server
)

enum class McpServerType {
    BUILTIN_IDE,   // IntelliJ MCP Server (URL from settings)
    MANUAL_URL     // User-provided URL in settings
}

/**
 * Resolved information about a discovered MCP server.
 * Produced by McpServerDiscovery after verifying the server's SSE endpoint.
 */
data class McpServerInfo(
    val name: String,            // Matches McpServerConfig.name
    val port: Int,               // Port number (for informational purposes)
    val url: String,             // Full SSE URL, e.g. "http://127.0.0.1:64342/sse"
    val source: DiscoverySource
)

enum class DiscoverySource {
    BUILTIN_IDE,             // IntelliJ MCP Server (URL from settings)
    MANUAL                    // User-provided URL for third-party servers
}

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String? = null  // JSON schema as string
)

/**
 * Connection status for a single MCP server.
 * Each server in McpManager has its own independent state machine.
 */
data class McpConnectionStatus(
    val name: String,             // Server name (e.g., "intellij", "github")
    val state: McpConnectionState,
    val serverInfo: McpServerInfo? = null,
    val toolCount: Int = 0,
    val error: String? = null
)

enum class McpConnectionState {
    DISCONNECTED,  // Initial state, or toggle off
    DETECTING,     // Probing for MCP server
    REGISTERING,   // Registering with OpenCode server
    CONNECTED,     // MCP server registered and tools available
    ERROR          // Connection failed — user must retry manually
}