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

    // ── Send POST hard ceiling ────────────────────────────────────────────
    /**
     * Hard ceiling for the POST /session/:id/message HTTP call (sendMessageAsync).
     *
     * sendMessageAsync uses TimeoutProfile.INFINITE (the POST blocks until the LLM finishes
     * generating, which can be minutes for complex tool chains). The activity monitor
     * handles intelligent timeouts (no SSE activity for responseTimeoutSeconds). This
     * constant is a BELT-AND-SUSPENDERS backstop for the case where the TCP connection is
     * half-open (no FIN/RST, just silent) AND the activity monitor's onTimeout callback
     * cannot cancel the coroutine (e.g., the monitor job itself was cancelled).
     *
     * 30 minutes is generous enough that legitimate long generations (subagents, complex
     * tool chains) will not be killed — the activity monitor resets on SSE events, so a
     * healthy long generation never hits this ceiling. The ceiling only fires for dead TCP
     * where no data is moving.
     *
    * See AGENTS.md "SSE Reconnection" section for why the Java HTTP engine has no
    * socket-level idle timeout.
     *
     * WATCHDOG NOTE: If both the activity monitor and this ceiling fail (e.g., monitor
     * job cancelled by scope cancellation + ceiling not yet reached), the user is stuck
     * for up to 30 minutes with sendMutex locked and all subsequent sends silently failing.
     * A secondary UI-level watchdog could detect this: if sendMutex has been held for
     * > responseTimeoutSeconds * 2 without any SSE activity, show a notification suggesting
     * the user disconnect/reconnect. This is deferred — the activity monitor + ceiling
     * cover the known failure modes, and the connection-state observer already resets
     * _streamPhase on disconnect.
     */
    const val SEND_POST_HARD_CEILING_MS = 30 * 60 * 1000L  // 30 minutes
}
