# Technical Design Document: IntelliJ MCP Integration

> **Status:** Draft v4 (Revised)
> **Author(s):** —
> **Last Updated:** 2026-06-11
> **Related docs:** [AGENTS.md](../../AGENTS.md)
> **Revision notes:** v4 — Generalized from single-server `IntellijMcpManager` to multi-server `McpManager`. The orchestrator now manages a registry of MCP server configs, making it straightforward to add additional MCPs (GitHub, Slack, custom) via settings. Discovery strategies are extensible. All other design decisions unchanged.

---

## ⚠️ Implementation Deviations from TDD (2026-06-11)

The TDD was written before probing the actual JetBrains MCP Server. Key deviations:

1. **JetBrains MCP Server port is NOT 63342 (built-in web server).** The MCP Server plugin runs on its own port (default 64342, visible in Settings → Tools → MCP Server). `BuiltInServerManager.getPort()` returns 63342 (web server), which is the WRONG port. The TDD's entire auto-detection strategy via `BuiltInServerManager` is broken.

2. **No `/api/mcp/list_tools` REST endpoint.** The JetBrains MCP Server uses the standard MCP SSE transport (`GET /sse` for events, `POST /message?sessionId=xxx` for JSON-RPC). There is no REST API for tool discovery. The TDD's verification and tool-listing strategy of calling `GET /api/mcp/list_tools` does not work.

3. **SSE URL must be provided by the user.** Since the MCP server port is separate from the web server port and cannot be auto-detected, the user must copy the SSE URL from Settings → Tools → MCP Server → "Copy SSE Config" and paste it into the OpenCode settings `mcpServerUrl` field.

4. **Verification is via SSE endpoint check, not REST API.** `McpServerDiscovery.verifyMcpServer()` sends a `GET` request to the SSE URL. A 200 response means the MCP server is running; a 404 or connection refused means it's not.

5. **Tool listing uses OpenCode's `GET /mcp`, not direct REST calls.** `McpToolList` no longer calls `/api/mcp/list_tools` on the MCP server. It stores an empty tool list per server (indicating registration). OpenCode's `GET /mcp` returns server names and connection status, but not individual tool names.

6. **`DiscoverySource` enum uses `BUILTIN_IDE` and `MANUAL`, not `BUILTIN_SERVER_MANAGER`.** Since auto-detection is removed, the discovery source simply indicates whether the URL came from IntelliJ MCP settings or from the additional servers config.

7. **`McpServerDiscovery` no longer uses `BuiltInServerManager` reflection.** The class now validates URL format and verifies the SSE endpoint via HTTP. No reflection, no classloader issues, no fallback port.

8. **MCP server registration uses `.opencode/opencode.json`, not just `POST /mcp`.** The TDD's primary registration approach (`POST /mcp` for dynamic registration) is ephemeral — registrations are lost on OpenCode restart. The implementation now writes MCP server configs to `.opencode/opencode.json` in the project directory **before** launching the OpenCode binary. This is the canonical, persistent approach: OpenCode reads `opencode.json` on startup and connects to MCP servers automatically. `POST /mcp` is still used as a supplement for immediate registration without restart.

9. **`McpConfigWriter` writes the config file atomically.** It merges plugin-managed `mcp` entries with existing config (preserving `model`, `agent`, `provider`, and non-plugin `mcp` entries). Disabled servers are removed, not set to `enabled: false`. The file is written before `ProcessManager.launchOpenCodeBinary()` so OpenCode sees it on first startup.

10. **`McpToolList` stores empty lists — no REST endpoint for tool discovery.** Since the JetBrains MCP Server doesn't expose `/api/mcp/list_tools`, `McpToolList` simply stores an empty list per server to indicate registration. Tool details are managed by OpenCode internally via the MCP protocol.

11. **§10 Tool Permissions — partially implemented with parallel discovery systems.** The settings panel has basic tool discovery (`GET /experimental/tool/ids` for built-in tools) and a per-tool checkbox + permission dropdown UI. However: (a) MCP tool discovery in settings is a stub returning `emptyMap()` — the existing `McpToolDiscovery` class (which correctly implements MCP `tools/list` via JSON-RPC over SSE) is NOT wired in; (b) there are two parallel data models (`ToolInfo` in `ToolRegistry.kt` vs `ToolPermissionInfo` in `OpenCodeSettingsPanel.kt`) that should be unified; (c) missing UI features: Enable All/Disable All buttons, filter/search bar, source dropdown, "N/M enabled" counter, per-server grouping headers, restart warning; (d) tool states are not persisted in `OpenCodeSettingsState` — must re-discover on each settings panel open.

12. **`OpenCodeSettingsConfigurable.discoverMcpTools()` is a stub.** It returns `emptyMap()` with a `TODO: Implement MCP protocol tool discovery` comment. The full implementation exists in `McpToolDiscovery.kt` but is not called from the settings flow. The fix is to get connected MCP server URLs from `McpManager` and call `McpToolDiscovery.discoverAllTools()`.

---

## 1. TL;DR

Register MCP servers with the plugin's OpenCode instance so the LLM can use IDE-native and external tools. The plugin supports multiple MCP servers: the built-in IntelliJ MCP server (auto-detected via `BuiltInServerManager`) and additional servers configured manually via URLs. `McpManager` orchestrates discovery, registration (`POST /mcp`), and tool listing for all configured servers. Tool routing is advisory in v1 — the LLM naturally prefers richer MCP tools over OpenCode's built-in filesystem tools. Enforceable tool hiding via `opencode.json` permission rules is deferred to v2.

---

## 2. Context & Scope

### 2.1 Current State

The plugin launches its own `opencode serve` process per project (`ProcessManager.kt`). OpenCode has built-in tools (`read`, `edit`, `write`, `grep`, `glob`, `bash`) that operate on the filesystem directly. These tools are unaware of the IDE — they don't use IntelliJ's index, PSI tree, refactoring engine, or run configurations.

IntelliJ IDEA 2025.2+ bundles an MCP server plugin (`com.intellij.mcpServer`) that exposes 70+ IDE-native tools. The MCP server runs on IntelliJ's **built-in web server** (default port 63342, configurable via `BuiltInServerOptions`). The SSE endpoint is `http://127.0.0.1:<port>/sse`. The MCP server also exposes a REST API at `http://127.0.0.1:<port>/api/mcp/list_tools` for tool discovery.

**Prerequisites:** The MCP server must be explicitly enabled by the user in Settings → Tools → MCP Server → Enable MCP Server. The plugin is bundled by default, but the server does not start automatically.

### 2.2 Problem Statement

When the user works in IntelliJ, the LLM uses OpenCode's generic filesystem tools instead of the IDE's rich toolset. This means:
- No refactoring support (rename, extract, inline)
- No code inspections or intentions
- No run configuration execution
- No symbol search across the project index
- No IDE-aware terminal context
- File edits bypass IntelliJ's code style and formatting

The user wants the LLM to use IntelliJ's MCP tools for file operations, search, and commands. Additionally, the user plans to add more MCP servers (e.g., GitHub, Slack, custom) in the future, so the architecture must support multiple servers from day one.

---

## 3. Goals & Non-Goals

### Goals

1. **Auto-detect IntelliJ MCP server** — read port from `BuiltInServerManager`, verify MCP server is responding, register via `POST /mcp`
2. **Support additional MCP servers** — users can add custom MCP server URLs (e.g., GitHub MCP, Slack MCP) via settings
3. **Settings toggle** — `enableIntellijMcp` (on/off) for the built-in IDE server, plus a list of additional MCP servers
4. **Per-server status** — settings panel shows connection status for each registered server
5. **Per-server tool visibility** — settings panel shows all available MCP tools (fetched from each server's REST API), user can see what's available
6. **Advisory tool preference** — when MCP is connected, log that tools are available; the LLM naturally prefers richer MCP tools over built-in ones
7. **Per-project isolation** — each project's OpenCode server independently discovers and registers MCP servers
8. **Manual configuration** — allow users to manually specify MCP server URLs

### Non-Goals

- **Custom MCP toolsets** — we consume existing MCP servers' tools, not add new ones (that's the `McpToolset` extension point approach)
- **MCP server management** — we don't start/stop/restart IntelliJ's MCP server, only detect and connect to it
- **Cross-IDE MCP bridging** — we don't connect one IDE's MCP server to another IDE's OpenCode instance
- **Enforceable tool hiding (v1)** — there is no reliable runtime API to hide OpenCode's built-in tools. `PATCH /config` has bugs, `DELETE /mcp/:name` doesn't exist, and `GET /mcp` doesn't return dynamic servers. Enforceable hiding requires writing `opencode.json` permission rules before server launch (deferred to v2)
- **Per-tool enable/disable (v1)** — OpenCode has no API to selectively disable individual MCP tools at runtime. The settings panel shows the tool list for informational purposes only. Per-tool filtering via `opencode.json` `tools` glob patterns is deferred to v2

---

## 4. Proposed Solution

**Discover MCP servers (auto-detect IntelliJ's built-in server + manually configured URLs), verify they're responding, register them with OpenCode via `POST /mcp`, and fetch tool lists from each server's REST API for the settings panel.**

The plugin adds these components: (1) `McpServerDiscovery` — discovers MCP server endpoints using pluggable strategies (BuiltInServerManager, manual URL), (2) `McpRegistrar` — handles registration with OpenCode for any named server, (3) `McpToolList` — fetches and displays the tool list from server REST APIs, (4) `McpManager` — orchestrator that manages a registry of MCP server configs. A settings panel section provides toggles, URL overrides, and per-server status display.

### 4.1 Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Plugin Architecture                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                        McpManager                                │ │
│  │  (orchestrates N servers: intellij + additional)                │ │
│  │                                                                  │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │ │
│  │  │ intellij     │  │ github-mcp   │  │ custom-mcp   │  ...    │ │
│  │  │ BUILTIN_IDE  │  │ MANUAL_URL   │  │ MANUAL_URL   │         │ │
│  │  │ DISCONNECTED │  │ CONNECTED    │  │ ERROR        │         │ │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘         │ │
│  │         │                 │                 │                  │ │
│  │  ┌──────┴─────────────────┴─────────────────┴──────┐           │ │
│  │  │            McpServerDiscovery                    │           │ │
│  │  │  Strategy 1: BuiltInServerManager (for intellij) │           │ │
│  │  │  Strategy 2: Manual URL (for any server)        │           │ │
│  │  └──────────────────────────────────────────────────┘           │ │
│  │                                                                  │ │
│  │  ┌────────────────┐  ┌────────────────┐                         │ │
│  │  │ McpRegistrar   │  │ McpToolList     │                         │ │
│  │  │ POST /mcp      │  │ GET /api/mcp/   │                         │ │
│  │  │ (any name)     │  │ list_tools      │                         │ │
│  │  └────────────────┘  └────────────────┘                         │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │              Per-Server Connection State Machine                │ │
│  │  DISCONNECTED → DETECTING → REGISTERING → CONNECTED             │ │
│  │       ↑           │            │            │                    │ │
│  │       └───────────┴────────────┴────────────┘                    │ │
│  │                    (on error)                                   │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

### 4.2 Connection State Machine

```kotlin
enum class McpConnectionState {
    DISCONNECTED,  // Initial state, or toggle off
    DETECTING,     // Probing for MCP server
    REGISTERING,   // Registering with OpenCode server
    CONNECTED,     // MCP server registered and tools available
    ERROR          // Connection failed — user must retry manually
}
```

State transitions (per server):
- `DISCONNECTED → DETECTING`: When the server is enabled (toggle on for intellij, or added for manual)
- `DETECTING → REGISTERING`: When MCP server found and verified
- `DETECTING → ERROR`: When MCP server not found or not responding
- `REGISTERING → CONNECTED`: When `POST /mcp` succeeds
- `REGISTERING → ERROR`: When `POST /mcp` fails
- `CONNECTED → DISCONNECTED`: When server is disabled (local state only — server-side registration persists until OpenCode restart)
- `ERROR → DETECTING`: On manual retry

**Design decision: No auto-retry.** If detection or registration fails, the user must manually retry. Auto-retry would add complexity (backoff, cancellation) for a feature that fails only when the MCP server is disabled or the port is wrong — both require user action to fix.

### 4.3 API / Interface Design

**OpenCode Server API (consumed):**

| Method | Path | Purpose | Request | Response | Status |
|--------|------|---------|---------|----------|--------|
| POST | `/mcp` | Register MCP server dynamically | `{ name: "<server-name>", config: { type: "remote", url: "<sse-url>", oauth: false } }` | `{ name, status }` | **Verified** — exists in `packages/opencode/src/server/routes/mcp.ts` |
| GET | `/mcp` | Get MCP server status | — | `{ [name]: MCPStatus }` | **Broken** — does NOT return dynamically added servers (GH #19244/#7482) |

**IntelliJ MCP Server REST API (consumed for tool discovery):**

| Method | Path | Purpose | Response |
|--------|------|---------|----------|
| GET | `/api/mcp/list_tools` | List all available MCP tools with metadata | `[{ name, description, inputSchema }]` |
| POST | `/api/mcp/{tool_name}` | Execute a tool (not used by plugin — for debugging only) | Tool result |

**IntelliJ MCP Server MCP endpoints (consumed by OpenCode):**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/sse` | SSE stream for MCP protocol (used by OpenCode after registration) |
| GET | `/stream` | HTTP Stream transport |

**IntelliJ Platform API (consumed for port discovery):**

| API | Purpose | Notes |
|-----|---------|-------|
| `BuiltInServerManager.getInstance().getPort()` | Read the built-in web server port | From `com.intellij.builtInWebServer` — this is the port the MCP server runs on |
| `BuiltInServerOptions.getInstance().builtInServerPort` | Read the configured port (may differ from actual if custom port is set) | Default: 63342 |

**Key insight:** The MCP server does NOT have its own port. It runs on IntelliJ's built-in web server (port 63342 by default). The `McpServerSettings` class referenced in earlier drafts does not exist — the MCP server's port IS the built-in web server's port.

### 4.4 Technology Stack

No changes — uses existing Kotlin, kotlinx.coroutines, ktor (HTTP client already in `OpenCodeClient`).

### 4.5 Implementation Blueprint

#### 4.5.1 Data Models

```kotlin
// McpModels.kt — new file

/**
 * Configuration for an MCP server to register with OpenCode.
 * Each config has a unique name, a discovery strategy, and an SSE URL.
 */
data class McpServerConfig(
    val name: String,                // "intellij", "github", "slack" — unique ID for POST /mcp
    val type: McpServerType,          // How this server is discovered
    val url: String = "",             // Resolved SSE URL (empty for BUILTIN_IDE — resolved at runtime)
    val enabled: Boolean = true       // Whether to register this server
)

enum class McpServerType {
    BUILTIN_IDE,   // Auto-detect from BuiltInServerManager
    MANUAL_URL     // User-provided URL in settings
}

/**
 * Resolved information about a discovered MCP server.
 * Produced by McpServerDiscovery after finding and verifying a server.
 */
data class McpServerInfo(
    val name: String,                // Matches McpServerConfig.name
    val port: Int,                    // Port number (for informational purposes)
    val url: String,                  // "http://127.0.0.1:<port>/sse"
    val source: DiscoverySource
)

enum class DiscoverySource {
    BUILTIN_SERVER_MANAGER,  // Read from BuiltInServerManager API
    MANUAL                    // User-provided URL
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
    val name: String,                 // Server name (e.g., "intellij", "github")
    val state: McpConnectionState,
    val serverInfo: McpServerInfo? = null,
    val toolCount: Int = 0,
    val error: String? = null
)
```

```kotlin
// OpenCodeSettingsState.kt — new fields
var enableIntellijMcp: Boolean = false
/** Manual override for IntelliJ MCP server URL. Empty = auto-detect from BuiltInServerManager. */
var mcpServerUrl: String = ""
/**
 * Additional MCP servers as JSON array: [{"name":"github","url":"http://127.0.0.1:8080/sse"}].
 * Stored as JSON string for XStream serialization compatibility.
 */
var additionalMcpServers: String = ""
```

```kotlin
// ChatConstants.kt — new constants
const val MCP_VERIFY_TIMEOUT_MS = 3_000L
const val MCP_FETCH_TOOLS_TIMEOUT_MS = 5_000L
const val MCP_SERVER_NAME_INTELLIJ = "intellij"
const val MCP_LIST_TOOLS_PATH = "/api/mcp/list_tools"
```

#### 4.5.2 Class & Interface Definitions

**A. McpServerDiscovery — Discovers MCP server endpoints**

```kotlin
/**
 * Discovers MCP server endpoints by resolving McpServerConfig entries
 * to verified McpServerInfo instances.
 *
 * For BUILTIN_IDE configs: reads the port from BuiltInServerManager API,
 * constructs the SSE URL, and verifies via GET /api/mcp/list_tools.
 *
 * For MANUAL_URL configs: validates the URL format and verifies via
 * GET /api/mcp/list_tools.
 *
 * Verification confirms both that the port is correct AND that an MCP
 * server is actually responding at that endpoint.
 */
class McpServerDiscovery(
    private val scope: CoroutineScope
) {
    companion object {
        private const val MCP_LIST_TOOLS_PATH = ChatConstants.MCP_LIST_TOOLS_PATH
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
     * Resolve all enabled configs. Returns map of name → McpServerInfo
     * for successfully discovered servers.
     */
    suspend fun discoverAll(configs: List<McpServerConfig>): Map<String, McpServerInfo> {
        val results = mutableMapOf<String, McpServerInfo>()
        for (config in configs.filter { it.enabled }) {
            val info = discover(config)
            if (info != null) {
                results[config.name] = info
            }
        }
        return results
    }

    /**
     * Discover IntelliJ's built-in MCP server.
     * Reads port from BuiltInServerManager, constructs SSE URL, verifies.
     */
    private suspend fun discoverBuiltinIde(config: McpServerConfig): McpServerInfo? {
        val port = getBuiltInServerPort()
        if (port <= 0) {
            logger.warn { "[ACP] McpServerDiscovery: BuiltInServerManager unavailable" }
            return null
        }
        val info = McpServerInfo(
            name = config.name,
            port = port,
            url = "http://127.0.0.1:$port/sse",
            source = DiscoverySource.BUILTIN_SERVER_MANAGER
        )
        return if (verifyMcpServer(info)) info else null
    }

    /**
     * Discover a manually configured MCP server.
     * Validates URL format and verifies the endpoint.
     */
    private suspend fun discoverManualUrl(config: McpServerConfig): McpServerInfo? {
        val url = config.url.takeIf { it.isNotBlank() } ?: return null
        val parsed = parseUrl(url, config.name) ?: return null
        return if (verifyMcpServer(parsed)) parsed else null
    }

    /**
     * Read the built-in web server port from IntelliJ's API.
     * Returns the actual port the IDE is using, or -1 if unavailable.
     */
    private fun getBuiltInServerPort(): Int {
        return try {
            com.intellij.builtInWebServer.BuiltInServerManager.getInstance().port
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] McpServerDiscovery: BuiltInServerManager unavailable" }
            -1
        }
    }

    /**
     * Verify the MCP server is responding by calling its REST API.
     * This confirms both that the port is correct AND that the MCP plugin is enabled.
     * A TCP connect test is insufficient — any service on that port would pass.
     */
    private suspend fun verifyMcpServer(info: McpServerInfo): Boolean {
        return withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            try {
                // The MCP server's REST API is on the same port as the built-in web server.
                // GET /api/mcp/list_tools returns 200 if MCP is enabled, 404 if not.
                val baseUrl = info.url.substringBeforeLast("/sse")  // strip /sse suffix
                val response = ktorHttpClient.get("$baseUrl$MCP_LIST_TOOLS_PATH")
                response.status.isSuccess()
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    /**
     * Parse a manual URL into McpServerInfo.
     * Validates format and extracts port.
     */
    private fun parseUrl(url: String, name: String): McpServerInfo? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val parsed = java.net.URL(url)
            val port = parsed.port.takeIf { it > 0 } ?: parsed.defaultPort
            McpServerInfo(name = name, port = port, url = url, source = DiscoverySource.MANUAL)
        } catch (e: Exception) {
            null
        }
    }
}
```

**Why no port probing:** Port probing (scanning a range of ports) was considered but rejected because:
1. The built-in web server port is known via `BuiltInServerManager` — no guessing needed
2. TCP connect succeeds for ANY service on that port, not just MCP — false positives
3. The MCP server's REST API (`/api/mcp/list_tools`) provides definitive verification
4. If `BuiltInServerManager` is unavailable, the user must provide the URL manually

**B. McpRegistrar — Handles registration with OpenCode (server-agnostic)**

```kotlin
/**
 * Manages registration of MCP servers with OpenCode.
 *
 * IMPORTANT LIMITATIONS:
 * - DELETE /mcp/:name does NOT exist in the OpenCode server API.
 *   Toggling a server off only updates local state — the MCP server
 *   remains registered server-side until the OpenCode process restarts.
 * - GET /mcp does NOT return dynamically added servers (GH #19244/#7482).
 *   We track registration state locally via [registeredServers].
 * - Re-registering with POST /mcp while already registered may create
 *   a duplicate or error. We skip registration if already registered locally.
 */
class McpRegistrar(
    private val client: OpenCodeClient
) {
    /** Set of server names that have been successfully registered. */
    private val registeredServers = mutableSetOf<String>()

    /**
     * Register an MCP server with OpenCode.
     * Returns true if registration succeeded or was already done.
     */
    suspend fun register(serverInfo: McpServerInfo): Boolean {
        if (serverInfo.name in registeredServers) {
            logger.info { "[ACP] McpRegistrar: '${serverInfo.name}' already registered locally — skipping" }
            return true
        }

        val config = buildJsonObject {
            put("name", serverInfo.name)
            put("config", buildJsonObject {
                put("type", "remote")
                put("url", serverInfo.url)
                // oauth: false is CRITICAL — OpenCode enables OAuth auto-detection
                // by default for all remote servers. MCP servers on localhost
                // do not use OAuth. Without this, OpenCode will attempt
                // an OAuth flow that hangs or fails, preventing tool registration.
                put("oauth", false)
                put("enabled", true)
                put("timeout", 5000)
            })
        }

        return try {
            val success = client.addMcpServer(config)
            if (success) {
                registeredServers.add(serverInfo.name)
                logger.info { "[ACP] McpRegistrar: registered MCP server '${serverInfo.name}' at ${serverInfo.url}" }
            }
            success
        } catch (e: Exception) {
            logger.error(e) { "[ACP] McpRegistrar: registration failed for '${serverInfo.name}'" }
            false
        }
    }

    /**
     * Mark a server as unregistered locally.
     * NOTE: This does NOT remove the server from OpenCode — there is no
     * DELETE /mcp/:name endpoint. The server remains registered until
     * the OpenCode process restarts.
     */
    fun markUnregistered(name: String) {
        registeredServers.remove(name)
        logger.info { "[ACP] McpRegistrar: marked '$name' as unregistered locally" }
    }

    /**
     * Mark all servers as unregistered locally.
     */
    fun markAllUnregistered() {
        registeredServers.clear()
        logger.info { "[ACP] McpRegistrar: marked all servers as unregistered locally" }
    }

    /**
     * Reset state on OpenCode server restart.
     * Called when ProcessManager detects the OpenCode process was restarted.
     */
    fun resetOnServerRestart() {
        registeredServers.clear()
    }
}
```

**C. McpToolList — Fetches tool list from MCP server REST APIs**

```kotlin
/**
 * Fetches the list of available MCP tools from a server's REST API.
 *
 * Why not use OpenCode's GET /mcp:
 * GET /mcp does NOT return dynamically added servers (GH #19244/#7482).
 * The tool list would always be empty. Instead, we call each MCP
 * server's REST API directly: GET /api/mcp/list_tools.
 * This gives us the authoritative tool list for the settings panel.
 *
 * NOTE: This list is for DISPLAY ONLY in the settings panel. There is no
 * OpenCode API to selectively disable individual MCP tools at runtime.
 * Per-tool filtering via opencode.json "tools" glob patterns is deferred to v2.
 */
class McpToolList(
    private val scope: CoroutineScope
) {
    private val _tools = MutableStateFlow<Map<String, List<McpToolDescriptor>>>(emptyMap())
    /** Map of server name → list of tools offered by that server. */
    val tools: StateFlow<Map<String, List<McpToolDescriptor>>> = _tools.asStateFlow()

    companion object {
        private const val LIST_TOOLS_PATH = ChatConstants.MCP_LIST_TOOLS_PATH
        private const val FETCH_TIMEOUT_MS = ChatConstants.MCP_FETCH_TOOLS_TIMEOUT_MS
    }

    /**
     * Fetch tools from a specific MCP server's REST API.
     * Called after registration succeeds.
     */
    suspend fun fetch(serverInfo: McpServerInfo) {
        val baseUrl = serverInfo.url.substringBeforeLast("/sse")
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                val response = ktorHttpClient.get("$baseUrl$LIST_TOOLS_PATH")
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val toolArray = json.parseToJsonElement(body).jsonArray
                    val tools = toolArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                        val schema = obj["inputSchema"]?.toString()
                        McpToolDescriptor(name = name, description = description, inputSchema = schema)
                    }
                    _tools.value = _tools.value.toMutableMap().apply { put(serverInfo.name, tools) }
                    logger.info { "[ACP] McpToolList: fetched ${tools.size} tools from '${serverInfo.name}'" }
                } else {
                    logger.warn { "[ACP] McpToolList: GET /api/mcp/list_tools from '${serverInfo.name}' returned ${response.status}" }
                    _tools.value = _tools.value.toMutableMap().apply { put(serverInfo.name, emptyList()) }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] McpToolList: failed to fetch tools from '${serverInfo.name}'" }
                _tools.value = _tools.value.toMutableMap().apply { put(serverInfo.name, emptyList()) }
            }
        } ?: run {
            logger.warn { "[ACP] McpToolList: fetch from '${serverInfo.name}' timed out after ${FETCH_TIMEOUT_MS}ms" }
            _tools.value = _tools.value.toMutableMap().apply { put(serverInfo.name, emptyList()) }
        }
    }

    /**
     * Remove tools for a specific server.
     */
    fun removeTools(name: String) {
        _tools.value = _tools.value.toMutableMap().apply { remove(name) }
    }

    /**
     * Reset all tools.
     */
    fun reset() {
        _tools.value = emptyMap()
    }
}
```

**D. McpManager — Multi-Server Orchestrator**

```kotlin
/**
 * Orchestrates MCP server discovery, registration, and tool listing
 * for all configured servers.
 *
 * Manages a registry of McpServerConfig entries. Each server has its own
 * connection state machine and lifecycle. Created by OpenCodeService
 * after ProcessManager.initialize() succeeds.
 *
 * Built-in configs:
 * - "intellij" (BUILTIN_IDE) — auto-detected from BuiltInServerManager,
 *   toggled by the enableIntellijMcp setting.
 *
 * Additional configs:
 * - Parsed from the additionalMcpServers JSON string in settings.
 *   Each entry is { "name": "...", "url": "..." }.
 */
class McpManager(
    private val client: OpenCodeClient,
    private val settings: OpenCodeSettingsState,
    private val scope: CoroutineScope
) {
    private val discovery = McpServerDiscovery(scope)
    private val registrar = McpRegistrar(client)
    private val toolList = McpToolList(scope)

    private val _serverStatuses = MutableStateFlow<Map<String, McpConnectionStatus>>(emptyMap())
    /** Per-server connection status. Key = server name. */
    val serverStatuses: StateFlow<Map<String, McpConnectionStatus>> = _serverStatuses.asStateFlow()
    /** Per-server tool lists. Key = server name. */
    val tools: StateFlow<Map<String, List<McpToolDescriptor>>> = toolList.tools

    /**
     * Build the list of enabled server configs from settings.
     *
     * For the built-in IntelliJ MCP:
     * - Included only if enableIntellijMcp is true
     * - URL override from mcpServerUrl if set
     *
     * For additional servers:
     * - Parsed from additionalMcpServers JSON
     * - Always included (enabled by default)
     */
    fun resolveConfigs(): List<McpServerConfig> {
        val configs = mutableListOf<McpServerConfig>()

        // Built-in IntelliJ MCP
        if (settings.enableIntellijMcp) {
            configs.add(McpServerConfig(
                name = ChatConstants.MCP_SERVER_NAME_INTELLIJ,
                type = McpServerType.BUILTIN_IDE,
                url = settings.mcpServerUrl.takeIf { it.isNotBlank() } ?: "",
                enabled = true
            ))
        }

        // Additional MCP servers from settings JSON
        if (settings.additionalMcpServers.isNotBlank()) {
            try {
                val array = json.parseToJsonElement(settings.additionalMcpServers).jsonArray
                for (element in array) {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                    configs.add(McpServerConfig(
                        name = name,
                        type = McpServerType.MANUAL_URL,
                        url = url,
                        enabled = true
                    ))
                }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] McpManager: failed to parse additionalMcpServers JSON" }
            }
        }

        return configs
    }

    /**
     * Initialize all enabled MCP servers.
     * Called after ProcessManager.initialize() succeeds.
     * Discovers, verifies, and registers each server independently.
     */
    suspend fun initialize() {
        val configs = resolveConfigs()
        for (config in configs) {
            registerServer(config)
        }
    }

    /**
     * Discover, verify, and register a single MCP server.
     */
    private suspend fun registerServer(config: McpServerConfig) {
        updateState(config.name, McpConnectionState.DETECTING)

        // Discover MCP server
        val serverInfo = discovery.discover(config)
        if (serverInfo == null) {
            val errorMsg = when (config.type) {
                McpServerType.BUILTIN_IDE -> "IntelliJ MCP server not found. Enable it in Settings → Tools → MCP Server, " +
                    "or provide a manual URL in OpenCode settings."
                McpServerType.MANUAL_URL -> "MCP server '${config.name}' at ${config.url} is not responding."
            }
            updateState(config.name, McpConnectionState.ERROR, error = errorMsg)
            return
        }

        logger.info { "[ACP] MCP: found server '${config.name}' at ${serverInfo.url} (source: ${serverInfo.source})" }

        // Register with OpenCode
        updateState(config.name, McpConnectionState.REGISTERING)
        val registered = registrar.register(serverInfo)

        if (!registered) {
            updateState(config.name, McpConnectionState.ERROR,
                error = "Failed to register '${config.name}' with OpenCode. Check that the OpenCode server is running.")
            return
        }

        // Fetch tool list for display
        toolList.fetch(serverInfo)

        updateState(config.name, McpConnectionState.CONNECTED,
            serverInfo = serverInfo, toolCount = toolList.tools.value[config.name]?.size ?: 0)
        logger.info { "[ACP] MCP: registered '${config.name}' with OpenCode (${toolList.tools.value[config.name]?.size ?: 0} tools available)" }
    }

    /**
     * Disconnect a specific server — marks as unregistered locally.
     * The MCP server remains registered with OpenCode until the process restarts.
     */
    fun disconnect(name: String) {
        registrar.markUnregistered(name)
        toolList.removeTools(name)
        updateState(name, McpConnectionState.DISCONNECTED)
        logger.info { "[ACP] MCP: disconnected '$name' (server-side registration persists until OpenCode restart)" }
    }

    /**
     * Disconnect the IntelliJ MCP server (convenience method).
     */
    fun disconnectIntellij() {
        disconnect(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
    }

    /**
     * Retry a specific server after error.
     */
    suspend fun retry(name: String): Boolean {
        val configs = resolveConfigs()
        val config = configs.find { it.name == name } ?: return false
        registerServer(config)
        return _serverStatuses.value[name]?.state == McpConnectionState.CONNECTED
    }

    /**
     * Retry the IntelliJ MCP server (convenience method).
     */
    suspend fun retryIntellij(): Boolean {
        return retry(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
    }

    /**
     * Reset all state on OpenCode server restart.
     */
    fun resetOnServerRestart() {
        registrar.resetOnServerRestart()
        toolList.reset()
        _serverStatuses.value = emptyMap()
    }

    private fun updateState(name: String, state: McpConnectionState, serverInfo: McpServerInfo? = null, toolCount: Int = 0, error: String? = null) {
        val current = _serverStatuses.value.toMutableMap()
        current[name] = McpConnectionStatus(
            name = name,
            state = state,
            serverInfo = serverInfo,
            toolCount = toolCount,
            error = error
        )
        _serverStatuses.value = current
    }
}
```

**E. OpenCodeClient — New method**

```kotlin
/**
 * Register an MCP server dynamically with the OpenCode server.
 * POST /mcp
 *
 * @param body the full JSON body including name and config (as JsonObject string)
 * @return true if the server responded with success
 */
suspend fun addMcpServer(body: JsonObject): Boolean {
    return try {
        val response = httpClient.post("$baseUrl/mcp") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "[ACP] POST /mcp failed" }
        false
    }
}
```

**Why raw JSON body instead of typed `McpServerConfig`:** The OpenCode server's Zod validator is strict about the `config` shape. Using a typed `McpServerConfig` data class with kotlinx.serialization risks field name mismatches (e.g., `oauth: Boolean` vs the Zod union `z.union([McpOAuth, z.literal(false)])`). Building the JSON manually with `buildJsonObject` gives us exact control over the wire format and avoids serialization surprises.

**F. Settings Panel — MCP section (sub-child of OpenCode settings)**

The MCP Integration section is nested under the OpenCode server settings in `OpenCodeSettingsPanel`, not as a separate top-level section. This reflects the dependency: MCP servers are registered with the plugin's OpenCode instance, so MCP configuration belongs under OpenCode configuration.

```kotlin
// OpenCodeSettingsPanel.kt — additions

// The panel layout groups MCP under OpenCode:
//   OpenCode binary: [field] [Discover]
//   Server port: [field]
//   Permission timeout: [field]
//   ... (other OpenCode settings) ...
//   ─────────────────────────────────────
//   MCP Integration (OpenCode):
//     [x] Enable IntelliJ MCP integration
//     IntelliJ MCP SSE URL: [field]
//     Additional MCP servers: [text area]
//     MCP integration: disabled [label]
//     [Retry MCP Connection] [button]
//   ─────────────────────────────────────
//   Tool Permissions (OpenCode):
//     [tool list panel]
//     [Discover Tools] [Apply Tool Permissions]
//     [status label]

/** IntelliJ MCP section */
val enableIntellijMcpCheckbox: JBCheckBox = JBCheckBox("Enable IntelliJ MCP integration").apply {
    toolTipText = "Connect to the IDE's built-in MCP server so the LLM can use IDE-native tools " +
        "(refactoring, inspections, run configs, symbol search, etc.). " +
        "Requires MCP Server to be enabled in Settings → Tools → MCP Server."
}

val mcpServerUrlField: JBTextField = JBTextField().apply {
    toolTipText = "Override IntelliJ MCP server URL (leave empty for auto-detection from IDE built-in server). " +
        "Format: http://127.0.0.1:<port>/sse"
    emptyText.text = "http://127.0.0.1:63342/sse"  // Placeholder showing default
}

val mcpRetryButton: JButton = JButton("Retry").apply {
    toolTipText = "Retry MCP server detection and registration"
}

// Connection status indicator
val mcpStatusLabel: JBLabel = JBLabel("Disconnected").apply {
    foreground = JBColor.GRAY
}

// Tool count label (populated after discovery)
val mcpToolCountLabel: JBLabel = JBLabel("")

// Additional MCP servers (JSON editor or simple table in future)
// For v1, this is a text field for JSON: [{"name":"github","url":"http://127.0.0.1:8080/sse"}]
val additionalMcpServersField: JBTextArea = JBTextArea(3, 40).apply {
    toolTipText = "Additional MCP servers as JSON array. Format: [{\"name\":\"server-name\",\"url\":\"http://127.0.0.1:port/sse\"}]"
    emptyText.text = """[{"name":"github","url":"http://127.0.0.1:8080/sse"}]"""
}
```

**Thread safety for dynamic panel construction:** The tool list is fetched in a coroutine on `Dispatchers.Default`. Any UI updates must be posted to EDT via `ApplicationManager.getApplication().invokeLater()` or a `StateFlow` collected in a `LaunchedEffect` on the Compose UI thread.

**G. OpenCodeService — Integration point**

```kotlin
// OpenCodeService.kt — after ProcessManager.initialize() succeeds

private var mcpManager: McpManager? = null

// In initialize(), after connection is established:
mcpManager = McpManager(connectionManager.client!!, settings, scope)
mcpManager!!.initialize()

// On settings change (called when enableIntellijMcp is toggled off):
fun disconnectMcp(name: String) {
    mcpManager?.disconnect(name)
}

fun disconnectIntellijMcp() {
    mcpManager?.disconnectIntellij()
}

// On retry (called from settings panel):
suspend fun retryMcp(name: String): Boolean {
    return mcpManager?.retry(name) ?: false
}

// On OpenCode server restart (called from ProcessManager process watcher):
fun resetMcpOnServerRestart() {
    mcpManager?.resetOnServerRestart()
}
```

**H. Tool Routing — Advisory Only (v1)**

```kotlin
/**
 * Tool routing in v1 is advisory only.
 *
 * WHY NOT ENFORCEABLE:
 * - DELETE /mcp/:name does not exist — can't remove MCP registration
 * - PATCH /config has bugs (writes to wrong file, cache issues)
 * - GET /mcp doesn't return dynamic servers — can't verify tool state
 * - No runtime API to hide individual built-in tools
 *
 * THE ONLY ENFORCEABLE APPROACH:
 * Write .opencode/opencode.json with permission deny rules BEFORE launching
 * the OpenCode server. This requires:
 * 1. Writing the file before ProcessManager.launchOpenCodeBinary()
 * 2. Including: { "permission": { "read": "deny", "edit": "deny", ... } }
 * 3. The .opencode/ directory is gitignored by convention
 *
 * This is deferred to v2 because:
 * - It modifies the project directory (even if gitignored)
 * - It must happen before server launch, complicating the init sequence
 * - It affects ALL sessions, not just the current one
 * - Toggling it off requires server restart to take effect
 *
 * V1 APPROACH:
 * Rely on natural tool preference. IntelliJ MCP tools are richer than
 * OpenCode's built-in tools (e.g., read_file with PSI awareness vs raw
 * filesystem read). The LLM will prefer them when available.
 */
```

#### 4.5.3 Key Flow

```
OpenCodeService.initialize()
  → ProcessManager.initialize() → health check passes
  → McpManager.initialize()
    → resolveConfigs() → [McpServerConfig(intellij, BUILTIN_IDE), ...additional]
    → for each config:
        → McpServerDiscovery.discover(config)
          → BUILTIN_IDE: read port from BuiltInServerManager.getInstance().getPort(),
              verify via GET /api/mcp/list_tools
          → MANUAL_URL: parse URL, verify via GET /api/mcp/list_tools
        → if found and verified: McpRegistrar.register(serverInfo)
          → POST /mcp { name: "<server-name>", config: { type: "remote", url, oauth: false } }
        → McpToolList.fetch(serverInfo)
          → GET /api/mcp/list_tools → populate settings panel
        → State → CONNECTED for this server
      → if not found: State → ERROR for this server, continue with others
```

#### 4.5.4 Changes to Existing Files

| File | Change |
|------|--------|
| `OpenCodeSettingsState.kt` | Add `enableIntellijMcp`, `mcpServerUrl`, `additionalMcpServers` fields + `loadState()` |
| `OpenCodeSettingsPanel.kt` | Add MCP section as sub-child of OpenCode settings: toggle, URL field, retry button, status label, tool list, additional servers field |
| `OpenCodeClient.kt` | Add `addMcpServer(body: JsonObject): Boolean` method |
| `OpenCodeService.kt` | Add `mcpManager` field, `disconnectMcp()`, `disconnectIntellijMcp()`, `retryMcp()`, `resetMcpOnServerRestart()` |
| `ProcessManager.kt` | Add `onMcpReset` callback, invoke in process watcher auto-restart path |
| `ChatConstants.kt` | Add MCP constants |
| `McpModels.kt` | **New file** — data models (`McpServerConfig`, `McpServerInfo`, `McpToolDescriptor`, `McpConnectionStatus`, enums) |
| `McpServerDiscovery.kt` | **New file** — multi-strategy endpoint discovery and verification |
| `McpRegistrar.kt` | **New file** — server-agnostic registration with OpenCode |
| `McpToolList.kt` | **New file** — per-server tool list fetching |
| `McpManager.kt` | **New file** — multi-server orchestrator |
| `build.gradle.kts` | Add `bundledPlugin("com.intellij.mcpServer")` as optional dependency for compile-time access to MCP server classes |

---

## 5. Assumptions & Dependencies

**Assumptions (verified):**
- IntelliJ's MCP server runs on the **built-in web server** port (default 63342, NOT 64342). The port is accessible via `BuiltInServerManager.getInstance().getPort()`.
- The MCP server's SSE endpoint is `http://127.0.0.1:<port>/sse`.
- The MCP server's REST API is at `http://127.0.0.1:<port>/api/mcp/list_tools` (returns tool list) and `http://127.0.0.1:<port>/api/mcp/{tool_name}` (executes a tool).
- `POST /mcp` on the OpenCode server accepts `{ name, config }` and registers the MCP server dynamically. **Verified** in `packages/opencode/src/server/routes/mcp.ts`.
- The user has enabled the MCP server in IntelliJ settings (Settings → Tools → MCP Server → Enable MCP Server). The plugin guides them if not.
- `oauth: false` must be explicitly set in the MCP config because OpenCode enables OAuth auto-detection by default for all remote servers. The IntelliJ MCP server on localhost does not use OAuth.
- Additional MCP servers (GitHub, Slack, etc.) expose an SSE endpoint at a user-configured URL and a REST API at the same host with `/api/mcp/list_tools` for tool discovery. If a server doesn't expose `/api/mcp/list_tools`, tool list fetching will fail gracefully (empty list, not a crash).

**Assumptions (unverified — need empirical testing):**
- `POST /mcp` with the same `name` as an already-registered server either overwrites or returns an error. If it errors, we must track local state to avoid duplicate registration attempts.
- The `BuiltInServerManager.getInstance().getPort()` call is safe to make from any thread. It likely reads from a persisted setting, but this should be verified.

**Dependencies:**
- IntelliJ built-in web server (`com.intellij.builtInWebServer`) — always available
- IntelliJ MCP Server plugin (`com.intellij.mcpServer`) — bundled, enabled by default since 2025.2, but the MCP server must be explicitly enabled by the user
- OpenCode server `POST /mcp` endpoint — verified in source code

**Known API limitations:**
- **`DELETE /mcp/:name` does NOT exist.** The OpenCode server has no endpoint to remove dynamically registered MCP servers. Toggling the setting off only updates local state — the server-side registration persists until the OpenCode process restarts.
- **`GET /mcp` does NOT return dynamically added servers** (GH #19244/#7482). Tool introspection via this endpoint is broken. We use each server's REST API (`/api/mcp/list_tools`) instead.
- **No runtime API for per-tool filtering.** OpenCode has no endpoint to selectively enable/disable individual MCP tools. The `tools` glob patterns in `opencode.json` work but require writing to the filesystem before server launch.

---

## 6. Alternatives Considered

**Alternative: Single-server IntellijMcpManager (previous design)**
- *What it is:* A dedicated `IntellijMcpManager` class hardcoded for the IntelliJ MCP server with `MCP_SERVER_NAME = "intellij"`.
- *Why rejected:* When adding additional MCPs (GitHub, Slack, custom), each would need its own manager class, duplicating discovery, registration, and state management logic. The generalized `McpManager` handles N servers with the same code path, making it trivial to add new servers via settings.

**Alternative: Prompt-based routing (no tool hiding)**
- *What it is:* Register MCP servers' tools but don't hide OpenCode's built-in tools. Add a system prompt instruction to prefer MCP tools.
- *Why rejected:* Not enforceable. LLMs routinely ignore prompt instructions when they have easier alternatives available. However, v1 effectively uses this approach because there's no reliable enforcement mechanism.

**Alternative: `McpToolset` extension point (contribute tools TO IntelliJ's MCP server)**
- *What it is:* Depend on `com.intellij.mcpServer` and implement `McpToolset` to add our own tools to IntelliJ's MCP server.
- *Why rejected:* Wrong direction. This makes our plugin's tools available via MCP to other clients. We want the opposite — consume IntelliJ's tools in our OpenCode instance.

**Alternative: Write `opencode.json` to project root**
- *What it is:* When the user enables MCP servers, write the MCP config to `opencode.json` in the project root.
- *Why rejected:* Modifies user's project files. Pollutes git history. `POST /mcp` dynamic registration is cleaner and doesn't touch the filesystem.

**Alternative: Use `PATCH /config` for tool routing**
- *What it is:* Use OpenCode's `PATCH /config` endpoint to deny built-in tools.
- *Why rejected:* `PATCH /config` has known bugs (PR #18591, #20636, #15045) where it writes to `config.json` instead of `opencode.json`. It also modifies the user's config file, which violates our principle of not touching user files.

**Alternative: Write `.opencode/opencode.json` with permission deny rules before server launch**
- *What it is:* Before launching the OpenCode process, write `.opencode/opencode.json` with `{ "permission": { "read": "deny", "edit": "deny", "grep": "deny", "glob": "deny", "bash": "deny" } }` to hide built-in tools.
- *Why deferred to v2:* This IS the only truly enforceable approach, but it has trade-offs:
  - Modifies the project directory (even though `.opencode/` is gitignored by convention)
  - Must happen before `ProcessManager.launchOpenCodeBinary()` — complicates the init sequence
  - Affects ALL sessions, not just the current one
  - Toggling off requires server restart to take effect
  - OpenCode maps `write`/`patch` under the `edit` permission key — denying `edit` covers all file modifications

**Alternative: Port probing (scanning a range of ports)**
- *What it is:* Probe ports 63342–63351 with TCP connect to find the MCP server.
- *Why rejected:* TCP connect succeeds for ANY service on that port — the built-in web server hosts many services (debugger, preview, etc.), not just MCP. A successful TCP connect doesn't confirm MCP is available. The `BuiltInServerManager` API gives us the exact port, and `GET /api/mcp/list_tools` confirms MCP is responding.

---

## 7. Cross-Cutting Concerns

### 7.1 Error Handling

| Scenario | Handling | User Impact |
|----------|----------|-------------|
| IntelliJ MCP server not enabled | `GET /api/mcp/list_tools` returns 404 → discovery fails | Settings panel shows error with guidance to enable MCP server |
| Additional MCP server unreachable | `GET /api/mcp/list_tools` times out or errors | Settings panel shows "not responding" for that server |
| BuiltInServerManager unavailable | `getPort()` throws → discovery returns null | Settings panel shows error — user must provide manual URL |
| `POST /mcp` fails | Log error, set state to ERROR for that server | Settings panel shows "Registration failed" with retry |
| MCP server disconnects after registration | OpenCode handles MCP reconnection internally | Tools temporarily unavailable, auto-reconnect by OpenCode |
| IntelliJ MCP plugin disabled | `GET /api/mcp/list_tools` returns 404 | Discovery fails gracefully — no IDE tools |
| Multiple IDE instances running | `BuiltInServerManager` returns port for current IDE instance | Each project registers independently with correct port |
| User toggles server off | Local state updated, server-side registration persists | MCP tools still available to LLM until OpenCode restart |
| User toggles server back on | Skip re-registration (already registered server-side) | MCP tools immediately available again |
| OpenCode server restarts | `ProcessManager` process watcher detects restart, calls `resetMcpOnServerRestart()` | Re-registration on next init cycle |
| Additional MCP servers JSON malformed | Parse error logged, additional servers skipped | IntelliJ MCP still works if enabled |

### 7.2 Observability

- Log at INFO: `[ACP] MCP: found server '<name>' at <url> (source: <source>)`
- Log at INFO: `[ACP] MCP: registered '<name>' with OpenCode (<N> tools available)`
- Log at WARN: `[ACP] MCP: '<name>' not found (enable in Settings → Tools → MCP Server)`
- Log at ERROR: `[ACP] MCP: registration failed for '<name>' — <reason>`
- Log at INFO: `[ACP] MCP: disconnected '<name>' (server-side registration persists until OpenCode restart)`
- Log at WARN: `[ACP] MCP: failed to parse additionalMcpServers JSON`

### 7.3 Configuration

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `enableIntellijMcp` | Boolean | `false` | Enable/disable IntelliJ MCP integration |
| `mcpServerUrl` | String | `""` | Manual IntelliJ MCP server URL override (empty = auto-detect from BuiltInServerManager) |
| `additionalMcpServers` | String | `""` | JSON array of additional MCP servers `[{\"name\":\"...\",\"url\":\"...\"}]` |

---

## 8. Testing Strategy

### 8.1 Key Scenarios

1. **Auto-detection succeeds** — IntelliJ MCP server enabled on port 63342 → plugin reads port from `BuiltInServerManager`, verifies via `/api/mcp/list_tools`, registers with OpenCode
2. **Auto-detection fails — MCP not enabled** — `GET /api/mcp/list_tools` returns 404 → settings panel shows error with guidance
3. **Auto-detection fails — BuiltInServerManager unavailable** — `getPort()` throws → settings panel shows error
4. **Manual configuration** — user provides URL → plugin verifies and uses that URL
5. **Manual URL invalid** — user provides malformed URL → parse fails, error shown
6. **Registration succeeds** — `POST /mcp` returns 200 → MCP tools available to LLM
7. **Registration fails** — `POST /mcp` returns error → settings panel shows "Registration failed" with retry
8. **Tool list populated** — after registration, `GET /api/mcp/list_tools` returns tool list → settings panel shows tools
9. **Tool list empty** — MCP server enabled but no tools registered → settings panel shows "0 tools"
10. **Toggle off** — local state updated, server-side registration persists → tools still available until restart
11. **Toggle back on** — skip re-registration (already registered) → tools immediately available
12. **Settings persistence** — toggle on, restart IDE → setting persists, re-registers on next init
13. **Multiple projects** — two projects open, both enable MCP → both register independently
14. **MCP server port change** — user changes built-in server port → plugin reads new port on next init
15. **OpenCode server restart** — process watcher detects restart → `resetMcpOnServerRestart()`, re-registration on next init
16. **Connection state machine** — verify all state transitions per server
17. **Additional MCP servers** — user adds `[{\"name\":\"github\",\"url\":\"http://127.0.0.1:8080/sse\"}]` → plugin discovers and registers both servers
18. **Malformed additionalMcpServers JSON** — parse error logged, additional servers skipped, IntelliJ MCP still works
19. **Mixed success/failure** — IntelliJ MCP succeeds, additional MCP fails → per-server status, partial registration
20. **Duplicate server names** — two servers with same name → second overwrites first in status map

### 8.2 Unit Tests

```kotlin
class McpServerDiscoveryTest {
    @Test
    fun `manual URL returns McpServerInfo when valid`() {
        val config = McpServerConfig(
            name = "github",
            type = McpServerType.MANUAL_URL,
            url = "http://127.0.0.1:8080/sse"
        )
        val discovery = McpServerDiscovery(TestScope())
        // Note: verifyMcpServer will fail in unit test without a real server.
        // Use a mock HTTP client or integration test for full discovery.
    }

    @Test
    fun `builtin IDE config uses BuiltInServerManager`() {
        val config = McpServerConfig(
            name = "intellij",
            type = McpServerType.BUILTIN_IDE
        )
        // Requires IDE test framework for BuiltInServerManager
    }

    @Test
    fun `manual URL returns null when empty`() {
        val config = McpServerConfig(
            name = "github",
            type = McpServerType.MANUAL_URL,
            url = ""
        )
        val discovery = McpServerDiscovery(TestScope())
        assertNull(runBlocking { discovery.discover(config) })
    }

    @Test
    fun `manual URL returns null when malformed`() {
        val config = McpServerConfig(
            name = "github",
            type = McpServerType.MANUAL_URL,
            url = "not-a-url"
        )
        val discovery = McpServerDiscovery(TestScope())
        assertNull(runBlocking { discovery.discover(config) })
    }
}

class McpRegistrarTest {
    @Test
    fun `register skips when already registered locally`() {
        val client = mockk<OpenCodeClient>()
        val registrar = McpRegistrar(client)
        // Manually add to registered set
        registrar.setRegisteredForTest("intellij")
        // Should not call client.addMcpServer
        val info = McpServerInfo("intellij", 63342, "http://127.0.0.1:63342/sse", DiscoverySource.BUILTIN_SERVER_MANAGER)
        assertTrue(runBlocking { registrar.register(info) })
        coVerify(exactly = 0) { client.addMcpServer(any()) }
    }

    @Test
    fun `markUnregistered removes specific server`() {
        val registrar = McpRegistrar(mockk())
        registrar.setRegisteredForTest("intellij")
        registrar.setRegisteredForTest("github")
        registrar.markUnregistered("intellij")
        assertFalse(registrar.isRegisteredForTest("intellij"))
        assertTrue(registrar.isRegisteredForTest("github"))
    }

    @Test
    fun `resetOnServerRestart clears all`() {
        val registrar = McpRegistrar(mockk())
        registrar.setRegisteredForTest("intellij")
        registrar.setRegisteredForTest("github")
        registrar.resetOnServerRestart()
        assertFalse(registrar.isRegisteredForTest("intellij"))
        assertFalse(registrar.isRegisteredForTest("github"))
    }
}

class McpManagerTest {
    @Test
    fun `resolveConfigs includes intellij when enabled`() {
        val settings = OpenCodeSettingsState().apply {
            enableIntellijMcp = true
        }
        val manager = McpManager(mockk(), settings, TestScope())
        val configs = manager.resolveConfigs()
        assertTrue(configs.any { it.name == "intellij" && it.type == McpServerType.BUILTIN_IDE })
    }

    @Test
    fun `resolveConfigs excludes intellij when disabled`() {
        val settings = OpenCodeSettingsState().apply {
            enableIntellijMcp = false
        }
        val manager = McpManager(mockk(), settings, TestScope())
        val configs = manager.resolveConfigs()
        assertFalse(configs.any { it.name == "intellij" })
    }

    @Test
    fun `resolveConfigs parses additional servers`() {
        val settings = OpenCodeSettingsState().apply {
            enableIntellijMcp = false
            additionalMcpServers = """[{"name":"github","url":"http://127.0.0.1:8080/sse"}]"""
        }
        val manager = McpManager(mockk(), settings, TestScope())
        val configs = manager.resolveConfigs()
        assertEquals(1, configs.size)
        assertEquals("github", configs[0].name)
        assertEquals(McpServerType.MANUAL_URL, configs[0].type)
    }

    @Test
    fun `resolveConfigs handles malformed JSON gracefully`() {
        val settings = OpenCodeSettingsState().apply {
            enableIntellijMcp = true
            additionalMcpServers = "not-json"
        }
        val manager = McpManager(mockk(), settings, TestScope())
        val configs = manager.resolveConfigs()
        // IntelliJ config still included, additional servers skipped
        assertEquals(1, configs.size)
        assertEquals("intellij", configs[0].name)
    }
}
```

---

## 9. Open Questions

1. **Does `POST /mcp` with a duplicate name overwrite or error?** If the user toggles the setting off and back on, we skip re-registration because the server is still registered. But if the OpenCode server was restarted (clearing dynamic registrations), we need to re-register. The `resetOnServerRestart()` method handles this. *Mitigation: Track local state, skip if already registered, reset on server restart.*

2. **Is `BuiltInServerManager.getInstance().getPort()` thread-safe?** We call it from a coroutine on `Dispatchers.Default`. It likely reads from a persisted setting, but this should be verified. *Mitigation: Wrap in try/catch and fall back to manual URL if it throws.*

3. **What happens if the user changes the built-in server port while the plugin is running?** The registered MCP URL becomes stale. The plugin doesn't detect port changes at runtime. *Decision: Re-register on next IDE restart or manual retry. Port changes are rare and require IDE restart anyway.*

4. **Should we write `.opencode/opencode.json` permission rules for enforceable tool hiding?** This is the only truly enforceable approach but has trade-offs (see §6, "Write `.opencode/opencode.json`" alternative). *Decision: Defer to v2. V1 relies on natural tool preference.*

5. **How to handle MCP tool name prefixing?** OpenCode prefixes MCP tool names with the server name (e.g., `intellij__read_file` or `intellij_read_file` — the convention varies by MCP SDK version). This affects tool filtering patterns in `opencode.json`. *Decision: Verify the actual prefix convention with the current OpenCode version before implementing v2 tool filtering.*

6. **Should additional MCP servers have individual enable/disable toggles?** *Decision: v1 uses a single JSON field. Per-server toggles can be added in a future version by extending the JSON schema to include `{"name":"...","url":"...","enabled":true/false}`. The `resolveConfigs()` method already supports the `enabled` field on `McpServerConfig`.*

7. **What if a manually configured MCP server doesn't expose `/api/mcp/list_tools`?** *Decision: The tool list fetch will fail gracefully (empty list for that server, logged warning). The server is still registered with OpenCode via `POST /mcp` — only the settings panel tool list display is affected.*

---

## 10. Tool Permissions Feature (v2)

### 10.1 Goal

Every tool available to the LLM (both built-in OpenCode tools and MCP tools from all connected servers) should be listed in a table in the Settings panel. The user can toggle each tool on/off and set its permission level (Allow/Ask/Deny). Changes are written to `.opencode/opencode.json` and take effect after an OpenCode server restart.

**Trade-off acknowledged**: This approach modifies the project directory (`.opencode/opencode.json`). This was explicitly deferred in §4.5.2H because it touches project files. In v2 we accept this trade-off because: (a) `.opencode/` is gitignored by convention, (b) it's the only enforceable mechanism — all other approaches (PATCH /config, DELETE /mcp, prompt instructions) are unreliable or nonexistent, (c) the file is written atomically and preserves non-plugin config keys.

### 10.2 Implementation Status

The tool permissions feature is **partially implemented**. What exists and what's still needed:

| Component | Status | Details |
|-----------|--------|---------|
| `ToolRegistry.kt` | ✅ Done | Aggregates built-in + MCP tools, provides `discoverAll()`, `exportPermissions()`, `setToolPermission()`, `setToolEnabled()` |
| `McpToolDiscovery.kt` | ✅ Done | MCP protocol `tools/list` via JSON-RPC over SSE. Supports single and parallel multi-server discovery |
| `McpConfigWriter.writeToolPermissions()` | ✅ Done | Writes per-agent permission rules to `.opencode/opencode.json`, merges with existing config, atomic write via temp file + rename |
| `ToolPermission` enum | ✅ Done | `ALLOW`, `ASK`, `DENY` with `toActionString()`/`fromActionString()` in `McpModels.kt` |
| Built-in tool discovery | ✅ Done | `GET /experimental/tool/ids` called in `OpenCodeSettingsConfigurable.discoverToolsFromOpenCode()`, with hardcoded description fallbacks |
| Settings panel UI | ⚠️ Basic | Has `toolPermissionsPanel` (vertical list), `discoverToolsButton`, `applyToolPermissionsButton`, per-tool checkbox + permission dropdown. **Missing**: Enable All/Disable All buttons, filter/search bar, source dropdown, grouped sections with headers, "N / M enabled" counter |
| MCP tool discovery in settings | ⚠️ Stub | `discoverMcpTools()` in `OpenCodeSettingsConfigurable` returns `emptyMap()`. The existing `McpToolDiscovery` class is NOT wired into the settings flow |
| Tool state persistence | ❌ Not done | Discovered tools are not persisted in `OpenCodeSettingsState`. Must re-discover on each settings open |
| Apply flow | ✅ Done | `applyToolPermissions()` reads UI state, converts to `Map<String, ToolPermission>`, writes via `McpConfigWriter.writeToolPermissions()` |

**Two parallel tool discovery systems exist** — they need to be unified:

1. **`ToolRegistry`** + **`McpToolDiscovery`** (in `com.opencode.acp.mcp`): Uses MCP protocol `tools/list` via JSON-RPC over SSE. Has `discoverAll(opencodeBaseUrl, mcpServerUrls)` that discovers both built-in and MCP tools. NOT wired into the settings panel.
2. **`OpenCodeSettingsConfigurable.discoverToolsFromOpenCode()`** (in `com.opencode.acp.config.settings`): Uses `GET /experimental/tool/ids` for built-in tools (working) and has a stub `discoverMcpTools()` (returns empty map). Creates `ToolPermissionInfo` objects (separate from `ToolInfo` in `ToolRegistry`).

### 10.3 API Endpoints for Tool Discovery

| Endpoint | Purpose | Response | Verification Status |
|----------|---------|----------|---------------------|
| `GET /experimental/tool/ids` | List all built-in tool IDs | `{"value": ["bash", "read", "glob", ...], "Count": 20}` | **Verified** — implemented in `OpenCodeSettingsConfigurable.discoverToolsFromOpenCode()` |
| MCP protocol `tools/list` | List tools from an MCP server | JSON-RPC response with `result.tools[].name`, `result.tools[].description` | **Verified** — implemented in `McpToolDiscovery.kt` |

**Built-in tools** (from `GET /experimental/tool/ids`): `bash`, `read`, `glob`, `grep`, `edit`, `write`, `task`, `webfetch`, `todowrite`, `websearch`, `skill`, `apply_patch`, `council_session`, `auto_continue`, `ast_grep_search`, `ast_grep_replace`, `subtask`, `read_session`

**MCP tools**: Discovered per server via MCP protocol `tools/list` JSON-RPC over SSE. For the IntelliJ MCP server, tools are prefixed with `intellij_` (e.g., `intellij_read_file`, `intellij_edit_file`).

**MCP protocol flow** (implemented in `McpToolDiscovery`):

```
1. GET http://127.0.0.1:<port>/sse → SSE stream
2. Parse: event: session → data: {"sessionId": "abc123"}
3. POST http://127.0.0.1:<port>/message?sessionId=abc123
   Body: {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
4. Parse response: {"jsonrpc": "2.0", "id": 1, "result": {"tools": [...]}}
5. Disconnect — the SSE connection is used only for discovery.
```

**Why the plugin creates its own SSE connection for discovery**: OpenCode manages persistent MCP connections for tool execution, but the plugin cannot query OpenCode for the tool list of a specific MCP server (GET /mcp doesn't return tool details). The plugin opens a short-lived SSE connection to each MCP server, sends one `tools/list` JSON-RPC request, parses the response, and disconnects. This is a read-only operation that doesn't interfere with OpenCode's persistent connection.

### 10.4 Permission Format in `opencode.json`

Permissions are written per-agent in the config file. The `orchestrator` agent is the primary target.

```json
{
  "mcp": {
    "intellij": { ... }
  },
  "agent": {
    "orchestrator": {
      "permission": {
        "read": "allow",
        "edit": "allow",
        "bash": "allow",
        "intellij_*": "allow",
        "intellij_read_file": "deny"
      }
    }
  }
}
```

**Key behavior**: OpenCode reads `.opencode/opencode.json` on startup. Changes require an OpenCode server restart to take effect (call `ProcessManager.shutdown()` then `initialize()`).

**Permission values**: `"allow"` | `"ask"` | `"deny"`

**Glob patterns**: `"intellij_*"` matches all tools prefixed with `intellij_`. Specific tool patterns (e.g., `"intellij_read_file"`) override globs (last-match-wins in OpenCode's permission resolution).

**Implementation note**: `McpConfigWriter.writeToolPermissions()` merges new permissions with existing ones — new values override existing keys, but permissions for tools not in the new map are preserved. This prevents accidental removal of manually configured permissions.

### 10.5 UI Design

#### Current Implementation

The settings panel (`OpenCodeSettingsPanel`) has a basic tool permissions section below the MCP Integration section (both nested under OpenCode server settings):

- `toolPermissionsPanel: JPanel` — vertical `BoxLayout` with per-tool rows (checkbox + label + dropdown)
- `discoverToolsButton: JButton("Discover Tools")` — triggers `discoverTools()` in configurable
- `applyToolPermissionsButton: JButton("Apply Tool Permissions")` — writes permissions to `opencode.json`
- Per-tool row: `JBCheckBox` (enabled) + `JBLabel` (name) + `JComboBox<String>("allow", "ask", "deny")`
- Tools grouped into "Built-in Tools" and "MCP Tools" sections via `createToolSection()`
- `ToolPermissionInfo` data class: `description`, `source` ("builtin"/"mcp"), `enabled`, `permission`

**What works**: Discover built-in tools, display them with checkbox + permission dropdown, apply permissions to `opencode.json`.

**What's missing** (compare to JetBrains MCP Tools reference design):

| Feature | Current | Target |
|---------|---------|--------|
| "N / M tools enabled" counter | ❌ Missing | Header shows count |
| Enable All / Disable All buttons | ❌ Missing | Bulk toggle buttons |
| Filter/search bar | ❌ Missing | Search by name or description |
| Source dropdown filter | ❌ Missing | Filter by "All", "Built-in", specific MCP server |
| MCP tool discovery | ❌ Stub (`discoverMcpTools()` returns `emptyMap()`) | Use `McpToolDiscovery` via SSE |
| Tool state persistence | ❌ Not persisted in settings state | Save in `OpenCodeSettingsState.toolPermissions` |
| Status label for restart requirement | ⚠️ Generic `showStatus()` | Show "Changes take effect after OpenCode restart" |
| Per-server grouping | ⚠️ Only 2 groups (Built-in/MCP) | Group by server name (e.g., "MCP: intellij (12 tools)") |
| Partial failure handling | ❌ Missing | Show per-group error state when one source fails |

#### Target Layout (top to bottom)

```
+-----------------------------------------------------------------+
| 18 / 20 tools enabled                                           |
|  [Enable All]  [Disable All]                                     |
|                                                                  |
|  Filter tools by name or ID              [All tools v]           |
|                                                                  |
|  +-- Built-in Tools ------------------------------------------+  |
|  | [x] bash        Execute shell commands          [Allow v]  |  |
|  | [x] read        Read file contents              [Allow v]  |  |
|  | [x] edit        Edit files with replacement     [Allow v]  |  |
|  | [x] grep        Search file contents with regex [Allow v]  |  |
|  | [x] glob        Find files by pattern           [Allow v]  |  |
|  | [x] write       Write new files                 [Allow v]  |  |
|  | [ ] apply_patch Apply patches to files          [Deny  v]  |  |
|  | ... (remaining built-in tools)                            |  |
|  +------------------------------------------------------------+  |
|                                                                  |
|  +-- MCP: intellij (12 tools) --------------------------------+  |
|  | [x] intellij_read_file       Read file with PSI awareness  |  |
|  |                                            [Allow v]       |  |
|  | [x] intellij_edit_file       Edit file with PSI awareness  |  |
|  |                                            [Allow v]       |  |
|  | [x] intellij_get_symbol_info Get symbol info               |  |
|  |                                            [Allow v]       |  |
|  | ... (remaining intellij tools)                            |  |
|  +------------------------------------------------------------+  |
|                                                                  |
|  +-- MCP: websearch (3 tools) --------------------------------+  |
|  | [x] websearch_web_search_exa  Web search via Exa           |  |
|  |                                            [Allow v]       |  |
|  | ...                                                       |  |
|  +------------------------------------------------------------+  |
|                                                                  |
|  Status: Ready. Changes take effect after OpenCode restart.      |
+-----------------------------------------------------------------+
```

#### Behavior

1. **On panel open**: Tools are NOT auto-discovered (avoids slow settings open). If tool states were persisted in `OpenCodeSettingsState.toolPermissions`, the table is populated from the saved state. Otherwise, the table is empty until the user clicks "Discover Tools".

2. **"Discover Tools" button**:
   - Button text changes to "Discovering..." and is disabled during discovery
   - Calls `GET /experimental/tool/ids` on OpenCode server (built-in tools) — **implemented**
   - Calls `McpToolDiscovery.discoverAllTools(mcpServerUrls)` for each connected MCP server — **stub, needs wiring**
   - On success: populates the table with all discovered tools, grouped by source. Merges with any previously persisted tool states (preserves user's permission choices for tools that still exist).
   - On partial failure: successfully discovered groups are shown; failed groups show an error header (e.g., "MCP: intellij — discovery failed: connection refused") with a per-group retry link.
   - On total failure: shows error in status label, table remains empty.
   - Each newly discovered tool defaults to `enabled = true`, `permission = "allow"` (unless a persisted state exists for that tool).
   - Persists the discovered tool states to `OpenCodeSettingsState.toolPermissions`.

3. **"Enable All" / "Disable All"** — **not yet implemented**: Toggle all visible checkboxes at once (respecting the current filter). Updates the "N / M tools enabled" counter.

4. **Filter field** — **not yet implemented**: Filters the table by tool name or description. Source dropdown filters by "All tools", "Built-in only", or specific MCP server name. Filter is applied to the in-memory tool list — no additional HTTP calls.

5. **Per-tool checkbox**: Enables/disables the tool. When unchecked, the tool is written as `permission: "deny"` in `opencode.json`. Updates the "N / M tools enabled" counter.

6. **Per-tool permission dropdown**: Sets the permission level (Allow/Ask/Deny). This overrides the checkbox state — if set to "Deny", the tool is denied regardless of checkbox. Setting "Allow" or "Ask" re-enables the checkbox.

7. **"Apply Tool Permissions" button** — **implemented**: Reads all tool states from the UI, converts to `Map<String, ToolPermission>`, calls `McpConfigWriter.writeToolPermissions(permissions)`. Shows success/failure status. Persists the final tool states to `OpenCodeSettingsState.toolPermissions`.

8. **Status label**: Shows "Tool permissions written to .opencode/opencode.json. Changes take effect after OpenCode restart." on success. Shows "Failed to write tool permissions: <reason>" on error.

### 10.6 Implementation Blueprint

#### Data Models

**Already implemented** in `McpModels.kt` and `ToolRegistry.kt`:

```kotlin
// McpModels.kt
enum class ToolPermission {
    ALLOW, ASK, DENY;
    fun toActionString(): String = when (this) {
        ALLOW -> "allow"; ASK -> "ask"; DENY -> "deny"
    }
    companion object {
        fun fromActionString(s: String): ToolPermission = when (s) {
            "allow" -> ALLOW; "ask" -> ASK; "deny" -> DENY; else -> ALLOW
        }
    }
}

// ToolRegistry.kt
data class ToolInfo(
    val name: String,
    val description: String,
    val source: ToolSource,  // BUILTIN or MCP
    val serverName: String,  // "builtin", "intellij", "websearch", etc.
    val enabled: Boolean = true,
    val permission: ToolPermission = ToolPermission.ALLOW
)
enum class ToolSource { BUILTIN, MCP }
```

**Also exists** in `OpenCodeSettingsPanel.kt` (separate, parallel — to be removed):

```kotlin
data class ToolPermissionInfo(
    val description: String,
    val source: String,  // "builtin" or "mcp"
    val enabled: Boolean,
    val permission: String  // "allow", "ask", or "deny"
)
```

**TODO**: Unify `ToolPermissionInfo` with `ToolInfo` — remove `ToolPermissionInfo` and use `ToolInfo` directly in the settings panel. Add `serverName` field to `ToolInfo` if not already present (needed for per-server grouping).

#### Remaining Work

**1. Wire `McpToolDiscovery` into settings flow** (`OpenCodeSettingsConfigurable.kt`):

Replace the stub `discoverMcpTools()` with actual MCP tool discovery:

```kotlin
private suspend fun discoverMcpTools(mcpServerUrls: Map<String, String>): Map<String, List<ToolInfo>> {
    if (mcpServerUrls.isEmpty()) return emptyMap()
    val mcpToolDiscovery = McpToolDiscovery(HttpClient(Java))
    return try {
        mcpToolDiscovery.discoverAllTools(mcpServerUrls)
    } catch (e: Exception) {
        logger.warn(e) { "[ACP] MCP tool discovery failed" }
        emptyMap()
    }
}
```

The caller (`discoverTools()`) merges built-in tools with MCP tools, groups by `serverName`, and populates the UI. Failed MCP servers are shown as error groups, not silent failures.

**2. Add Enable All / Disable All buttons** (`OpenCodeSettingsPanel.kt`):

```kotlin
val enableAllToolsButton: JButton = JButton("Enable All").apply {
    toolTipText = "Enable all visible tools (set permission to Allow)"
}
val disableAllToolsButton: JButton = JButton("Disable All").apply {
    toolTipText = "Disable all visible tools (set permission to Deny)"
}
```

Wire in `OpenCodeSettingsConfigurable`: iterate visible `toolEnabledCheckboxes` (respecting current filter) and set all to `true`/`false`, update corresponding `toolPermissionComboBoxes` to "allow"/"deny". Update counter.

**3. Add "N / M tools enabled" counter** (`OpenCodeSettingsPanel.kt`):

```kotlin
val toolCountLabel: JBLabel = JBLabel("0 / 0 tools enabled")
```

Update on every checkbox change and after discovery. Count includes only enabled tools (checkbox checked).

**4. Add filter/search field and source dropdown** (`OpenCodeSettingsPanel.kt`):

```kotlin
val toolFilterField: JBTextField = JBTextField().apply {
    emptyText.text = "Filter tools by name..."
}
val toolSourceFilterCombo: JComboBox<String> = JComboBox(arrayOf("All tools", "Built-in only"))
// MCP server names added dynamically after discovery
```

Filter logic: `toolFilterField` text matches against tool name (case-insensitive substring). `toolSourceFilterCombo` filters by `ToolSource` / `serverName`. Both filters are AND-combined. Filtering hides/shows rows in the existing panel — no data structure changes.

**5. Per-server grouping** — Group tools by `serverName` in `createToolSection()`. Section headers:
- Built-in: `"Built-in Tools (N tools)"`
- MCP: `"MCP: <serverName> (N tools)"` — e.g., `"MCP: intellij (12 tools)"`

Each group is a collapsible section (click header to toggle). Groups with discovery errors show the header in red with error text below.

**6. Persist tool states** — Add `toolPermissions: String` field to `OpenCodeSettingsState`:

```kotlin
var toolPermissions: String = ""  // JSON: {"bash":{"enabled":true,"permission":"allow"},...}
```

Schema: `Map<String, SerializedToolState>` where `SerializedToolState = { enabled: Boolean, permission: String }`. Serialize on Apply, deserialize on panel open. This avoids re-discovery on every settings open.

**7. Add restart warning status** — After applying tool permissions, show: "Tool permissions written to .opencode/opencode.json. Changes take effect after OpenCode restart."

**8. Handle partial discovery failure** — When `discoverTools()` runs, built-in and MCP discovery are independent. If MCP discovery fails for one server (timeout, connection refused), the built-in tools and other MCP servers' tools are still shown. The failed server's group shows an error header:

```
+-- MCP: intellij -- discovery failed: connection refused --+
|  [Retry]                                                   |
+------------------------------------------------------------+
```

The "Retry" link re-runs discovery for that specific server only.

### 10.7 Files to Modify

| File | Status | Change Needed |
|------|--------|----------------|
| `McpToolDiscovery.kt` | ✅ Done | Already implements MCP protocol `tools/list` via JSON-RPC over SSE |
| `ToolRegistry.kt` | ✅ Done | Already aggregates built-in + MCP tools. Add `serverName` field to `ToolInfo` for per-server grouping |
| `McpConfigWriter.kt` | ✅ Done | `writeToolPermissions()` already writes per-agent config |
| `McpModels.kt` | ✅ Done | `ToolPermission` enum with `ALLOW`/`ASK`/`DENY` already exists |
| `OpenCodeSettingsPanel.kt` | ⚠️ Partial | Has basic tool list. **Needs**: Enable All/Disable All buttons, filter field, source dropdown, "N/M enabled" counter, per-server grouping with error states, restart warning, remove `ToolPermissionInfo` in favor of `ToolInfo` |
| `OpenCodeSettingsConfigurable.kt` | ⚠️ Partial | Built-in discovery works. **Needs**: Wire `McpToolDiscovery` into `discoverMcpTools()`, unify with `ToolRegistry`, add Enable All/Disable All handlers, add filter logic, persist tool states on Apply |
| `OpenCodeSettingsState.kt` | ❌ Missing | **Needs**: `toolPermissions: String` field for persisting discovered tool states |
| `McpManager.kt` | ⚠️ Minor | **Needs**: Expose connected server SSE URLs (e.g., `getServerUrls(): Map<String, String>`) for `McpToolDiscovery` to use |

### 10.8 Constraints

- **No auto-discovery on settings open**: Tool discovery makes HTTP calls. The settings panel must open instantly. If persisted tool states exist, show them; otherwise show empty table until user clicks "Discover Tools".
- **Restart required**: OpenCode reads `.opencode/opencode.json` on startup only. Permission changes take effect after restart. The UI must make this clear — status label after Apply, not buried in docs.
- **Atomic writes**: Config file is written via temp file + rename to prevent corruption. `McpConfigWriter` already handles this.
- **Preserve non-plugin config**: The config writer must preserve `model`, `agent.*.prompt`, `agent.*.model`, and any non-plugin `mcp` entries. `McpConfigWriter` already handles this.
- **No emoji in UI**: Standard Swing components only. No emoji in tool names, descriptions, or section headers. Use text indicators (e.g., "[x]" not checkbox emoji).
- **Two discovery systems must unify**: `ToolRegistry` + `McpToolDiscovery` (in `com.opencode.acp.mcp`) and `OpenCodeSettingsConfigurable.discoverToolsFromOpenCode()` (in `com.opencode.acp.config.settings`) are parallel implementations. They should be unified so the settings panel uses `ToolRegistry.discoverAll()` instead of its own HTTP calls.
- **Concurrency**: "Discover Tools" must be disabled while discovery is in progress (prevent double-click). "Apply" must be disabled while discovery is in progress (prevent writing partial state). Use a `isDiscovering` flag checked by both buttons.
- **Partial failure tolerance**: Built-in and MCP discovery are independent. A failure in one MCP server must not prevent other tools from being shown or applied.
- **Tool name prefixing consistency**: MCP tool names must be prefixed with the server name (e.g., `intellij_read_file`) in both discovery and permission writing. The prefix convention must match what OpenCode uses when routing tool calls. Verify the actual prefix format (separator is `_` or `__`) before implementing.

### 10.9 Key Design Decisions

1. **Separate "Discover Tools" button rather than auto-discovery**: Settings panel must open instantly. HTTP calls for tool discovery would make it slow. The user explicitly triggers discovery.

2. **Separate "Apply Tool Permissions" button rather than integrating with IntelliJ settings Apply**: Tool permission writes go to `.opencode/opencode.json` (project file), not to IntelliJ's settings store. The IntelliJ Apply button saves to `opencode-settings.xml` (app-level). These are different stores with different lifecycles. A separate button makes this distinction clear to the user.

3. **`ToolPermissionInfo` vs `ToolInfo` duplication**: The settings panel uses `OpenCodeSettingsPanel.ToolPermissionInfo` (inner data class) while `ToolRegistry` uses `ToolInfo`. These serve the same purpose but evolved independently. They should be unified — remove `ToolPermissionInfo` and use `ToolInfo` directly in the settings panel.

4. **Checkbox / permission dropdown relationship**: When a tool is unchecked, it's written as `"deny"` in `opencode.json`. When checked, the dropdown value (`"allow"`/`"ask"`) is used. The dropdown overrides the checkbox — setting "Deny" in the dropdown effectively unchecks the tool. This is consistent with OpenCode's permission model where `"deny"` is the strongest restriction.

5. **Tool name prefixing for MCP tools**: MCP tool names are prefixed with the server name (e.g., `intellij_read_file`). The prefix must be consistent between discovery (`McpToolDiscovery`) and permission writing (`McpConfigWriter`). The separator character (`_` vs `__`) must match OpenCode's convention — verify empirically before implementing.

6. **Persist tool states to avoid re-discovery**: Tool states are saved in `OpenCodeSettingsState.toolPermissions` as JSON. On panel open, if persisted states exist, the table is populated immediately (no HTTP calls). "Discover Tools" refreshes the list and merges with persisted states (preserving user permission choices for tools that still exist). This avoids the slow re-discovery on every settings open while keeping the list fresh when the user explicitly requests it.

7. **Merge semantics for Apply**: `McpConfigWriter.writeToolPermissions()` merges new permissions with existing `agent.orchestrator.permission` entries. New values override existing keys. Permissions for tools NOT in the new map are preserved. This prevents the plugin from accidentally removing manually configured permissions (e.g., a user who added `"bash": "deny"` directly in the file).

### 10.10 Testing Strategy

| Scenario | Expected Behavior |
|----------|-------------------|
| Discover with OpenCode server running | Built-in tools populated in table |
| Discover with MCP server connected | MCP tools populated under server group header |
| Discover with MCP server disconnected | Built-in tools shown; MCP group shows error with retry link |
| Discover with OpenCode server down | Error in status label, table empty |
| Enable All with filter active | Only visible (filtered) tools are enabled |
| Disable All with filter active | Only visible (filtered) tools are disabled |
| Apply with all tools allowed | `opencode.json` has `agent.orchestrator.permission` with all "allow" |
| Apply with one tool denied | `opencode.json` has that tool as "deny", others preserved |
| Apply preserves existing config | `model`, `agent.*.prompt`, `mcp` entries unchanged in `opencode.json` |
| Apply with malformed `.opencode/opencode.json` | Error shown, file not corrupted (atomic write) |
| Panel open with persisted states | Table populated instantly from `OpenCodeSettingsState.toolPermissions` |
| Panel open without persisted states | Table empty, user must click "Discover Tools" |
| Filter by tool name | Only matching tools shown, counter updates |
| Filter by source dropdown | Only tools from selected source shown |
| Checkbox unchecked → Apply | Tool written as `"deny"` in `opencode.json` |
| Permission dropdown set to "Deny" → Apply | Tool written as `"deny"` regardless of checkbox state |
| Double-click "Discover Tools" | Second click ignored (button disabled during discovery) |
| Click "Apply" during discovery | Button disabled, click ignored |
| Restart OpenCode after Apply | New permissions take effect |

---

## 11. Document History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-11 | — | Initial draft |
| 2026-06-11 | — | v2: Fixed false assumptions, applied SOLID principles, added connection state machine, removed PATCH /config approach |
| 2026-06-11 | — | v3: Fixed critical port error (64342→63342), replaced nonexistent `McpServerSettings` API with `BuiltInServerManager`, removed `DELETE /mcp/:name` dependency, replaced broken `GET /mcp` tool fetching with IntelliJ REST API, removed unimplementable per-tool enable/disable and enforceable tool hiding from v1, simplified architecture (removed over-engineered interface hierarchy), added HTTP-based MCP verification instead of TCP probing, documented all API limitations |
| 2026-06-11 | — | v4: Generalized from single-server `IntellijMcpManager` to multi-server `McpManager`. `McpRegistrar` now tracks a set of registered server names (not just "intellij"). `McpToolList` stores per-server tool maps. `McpManager.resolveConfigs()` builds config list from settings (built-in + additional). Added `additionalMcpServers` JSON setting. `McpServerDiscovery` uses `McpServerConfig` with `McpServerType` enum. Settings panel includes additional servers text field. |
| 2026-06-11 | — | v5: Added §10 (Tool Permissions Feature). Spec for per-tool enable/disable UI with table layout matching JetBrains MCP Tools page. Tool discovery via `/experimental/tool/ids` (built-in) and MCP protocol `tools/list` (MCP servers). Per-agent permission rules written to `.opencode/opencode.json`. UI: enable/disable checkboxes, Allow/Ask/Deny dropdowns, filter, Enable All/Disable All. Config writer merges permissions into `agent.orchestrator.permission`. |
| 2026-06-11 | — | v6: Rewrote §10 to reflect actual implementation state. Marked what's done (ToolRegistry, McpToolDiscovery, McpConfigWriter.writeToolPermissions, built-in discovery, basic UI) vs what's missing (MCP discovery wiring, Enable All/Disable All, filter, counter, per-server grouping, persistence). Added implementation deviation items 11-12 for parallel discovery systems and stub discoverMcpTools(). |
| 2026-06-11 | — | v7: Reviewed and improved §10. Added §10.1 trade-off acknowledgment for modifying project directory. Added verification status column to §10.3 API table. Added §10.3 explanation of why plugin creates its own SSE connection for discovery. Added partial failure handling to §10.5 behavior (per-group error states, retry links). Removed emoji from §10.5 layout (contradicted §10.8 constraint). Added §10.8 constraints for concurrency (isDiscovering flag), partial failure tolerance, and tool name prefixing consistency. Added §10.9 decisions for merge semantics and persist-then-merge strategy. Added §10.10 testing strategy with 18 scenarios. Clarified checkbox/dropdown relationship in §10.5 behavior item 6. |
| 2026-06-11 | — | v8: Moved MCP Integration and Tool Permissions sections to be sub-children of OpenCode server settings in the settings panel hierarchy. Updated §4.5.2F to show the nested layout with "MCP Integration (OpenCode):" and "Tool Permissions (OpenCode):" labels. This reflects the dependency: MCP servers are registered with the plugin's OpenCode instance, so MCP configuration belongs under OpenCode configuration. |

---

## Appendix A: Design Principles

### Simplicity Over Abstraction (Updated for Multi-Server)
- No `McpServerDiscovery` interface with `CompositeDiscovery` and priority ordering — we have exactly 2 strategies (manual URL, BuiltInServerManager), not an extensible plugin system
- No `McpToolManager` with per-tool toggle state — there's no API to communicate tool preferences to OpenCode, so tracking it locally is cosmetic
- Four focused classes (`McpServerDiscovery`, `McpRegistrar`, `McpToolList`, `McpManager`) instead of per-server managers
- `McpManager` handles all servers through a single `registerServer()` code path — no server-specific logic duplication
- `additionalMcpServers` uses a JSON string instead of a separate table UI — simple, extensible, no new data structure needed in settings

### Honest About Limitations
- `DELETE /mcp/:name` doesn't exist → documented, toggle-off is local-only
- `GET /mcp` doesn't return dynamic servers → documented, use server REST APIs instead
- Per-tool filtering has no runtime API → removed from v1, deferred to v2
- Enforceable tool hiding requires `opencode.json` → documented as v2
- Additional MCP servers may not expose `/api/mcp/list_tools` → fails gracefully, empty tool list

### Fail Gracefully
- MCP server not found → plugin works normally with built-in tools
- Registration fails → retry button, no auto-retry loop
- Tool list fetch fails → empty list, no crash
- BuiltInServerManager unavailable → fall back to manual URL
- Malformed additional servers JSON → logged, skipped, other servers still work
- Additional server unreachable → per-server error, other servers unaffected

---

## Appendix B: Security Considerations

### Localhost-Only Binding
- IntelliJ's built-in web server binds to `127.0.0.1` by default
- Plugin validates manual URL starts with `http://127.0.0.1` or `http://localhost`
- Warning shown if user configures non-localhost URL

### OAuth Prevention
- `oauth: false` is explicitly set in the MCP config for ALL servers to prevent OpenCode from attempting OAuth auto-detection. Without this, OpenCode would try an OAuth flow that hangs or fails.

### Permission Bypass Prevention
- MCP tools respect IntelliJ's permission model
- "Brave mode" in IntelliJ settings controls confirmation prompts
- Plugin does not bypass IntelliJ's security model

---

## Appendix C: Migration Path

### Phase 1: Basic Integration (This TDD)
- Auto-detect IntelliJ MCP server via `BuiltInServerManager` API
- Verify MCP server responding via `/api/mcp/list_tools`
- Register with OpenCode via `POST /mcp`
- Settings panel with toggle, manual URL, tool list display
- Support for additional MCP servers via JSON config
- Advisory tool preference (no enforcement)
- Multi-server `McpManager` architecture (not single-server `IntellijMcpManager`)

### Phase 2: Enforceable Tool Hiding
- Write `.opencode/opencode.json` with permission deny rules before server launch
- Per-tool filtering via `tools` glob patterns in `opencode.json`
- Handle toggle-off by restarting OpenCode server

### Phase 3: Advanced Integration (Future)
- Real-time port change detection via `BuiltInServerOptions` settings listener
- Tool usage analytics
- Direct MCP protocol integration (bypass OpenCode)
- Per-server enable/disable toggles in settings UI
- Richer additional servers UI (table with add/remove/edit)