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

/**
 * Permission level for a tool.
 */
enum class ToolPermission {
    ALLOW,        // Tool is allowed without prompting
    ASK,          // Tool requires user confirmation
    DENY;         // Tool is blocked

    /**
     * Convert to the OpenCode config action string.
     */
    fun toActionString(): String = when (this) {
        ALLOW -> "allow"
        ASK -> "ask"
        DENY -> "deny"
    }

    companion object {
        /**
         * Parse from an OpenCode config action string.
         */
        fun fromActionString(action: String): ToolPermission = when (action) {
            "allow" -> ALLOW
            "ask" -> ASK
            "deny" -> DENY
            else -> ALLOW
        }
    }
}