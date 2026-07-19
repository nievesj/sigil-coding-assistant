package com.opencode.acp.chat.model

object ChatConstants {
    const val TOOL_WINDOW_ID = "Sigil - Coding Assistant"
    const val MAX_MESSAGE_HISTORY = 500
    const val PERMISSION_TIMEOUT_MS = 60_000L
    /** Timeout for child session permission prompts (seconds). Longer than main — parent is still working. */
    const val CHILD_PERMISSION_TIMEOUT_SECONDS = 120
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
    /** Initial delay (ms) before retrying MCP server connection after a failure.
     *  The JetBrains MCP Server starts asynchronously — it may not be ready when
     *  the plugin's initialize() runs. This is the first retry delay. */
    const val MCP_RETRY_INITIAL_DELAY_MS = 2_000L
    /** Maximum delay (ms) between MCP server connection retries (exponential backoff cap). */
    const val MCP_RETRY_MAX_DELAY_MS = 10_000L
    /** Total time (ms) to keep retrying MCP server connection before giving up.
     *  60 seconds covers the typical JetBrains MCP Server startup window. */
    const val MCP_RETRY_TOTAL_TIMEOUT_MS = 60_000L

    // ── Context Pruner ─────────────────────────────────────────────────
    /** Resource path of the TS plugin inside the JAR. */
    const val PRUNER_RESOURCE_PATH = "/opencode-plugins/sigil-pruner.ts"
    /** Target filename in .opencode/plugins/. */
    const val PRUNER_PLUGIN_FILENAME = "sigil-pruner.ts"
    /** Config file name written by PrunerConfigWriter. */
    const val PRUNER_CONFIG_FILENAME = "sigil-pruner.json"
    /** Heartbeat file name written by the TS plugin. */
    const val PRUNER_HEARTBEAT_FILENAME = "sigil-pruner.heartbeat"
    /** API version for compatibility handshake between Kotlin config and TS plugin. */
    const val PRUNER_API_VERSION = 1

    // ── Child Sessions ──────────────────────────────────────────────────
    /** Maximum number of concurrently-animating child session spinners.
     *  Caps animation count to avoid GDI nativeBlit hang risk (AGENTS.md).
     *  Child sessions beyond this cap show a static forward-arrow icon instead. */
    const val MAX_VISIBLE_CHILD_SPINNERS = 5
}
