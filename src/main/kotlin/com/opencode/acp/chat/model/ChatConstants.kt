package com.opencode.acp.chat.model

object ChatConstants {
    const val TOOL_WINDOW_ID = "AI Coding Assistant - OpenCode"
    const val MAX_MESSAGE_HISTORY = 500
    const val PERMISSION_TIMEOUT_MS = 60_000L
    const val RECONNECT_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
    /** Interval (ms) between SSE health-check probes. When the SSE connection has
     *  been silent for this long (no events received), the plugin sends a lightweight
     *  GET /global/health to verify the server and connection are alive. If the health
     *  check fails, reconnection is triggered. This replaces the old idle-detection
     *  approach that killed healthy connections during normal user thinking time. */
    const val SSE_HEALTH_CHECK_INTERVAL_MS = 60_000L
    /** Timeout (ms) for the SSE health-check probe HTTP request. */
    const val SSE_HEALTH_CHECK_TIMEOUT_MS = 10_000L

    // ── MCP integration ────────────────────────────────────────────────
    /** Timeout (ms) for verifying an MCP server is responding (SSE endpoint check). */
    const val MCP_VERIFY_TIMEOUT_MS = 3_000L
    /** Timeout (ms) for fetching the MCP tool list from a server. */
    const val MCP_FETCH_TOOLS_TIMEOUT_MS = 5_000L
    /** Server name for the built-in IntelliJ MCP server. */
    const val MCP_SERVER_NAME_INTELLIJ = "intellij"
}
