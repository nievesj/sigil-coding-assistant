# Technical Design Document: Centralize Network Calls in OpenCodeClient

> **Status:** Draft
> **Author:** Orchestrator
> **Last Updated:** 2026-06-10
> **Related docs:** AGENTS.md (SSE Reconnection), `sse-timeout-fix.md`, `OpenCodeClient.kt`, `OpenCodeService.kt`

---

## 1. TL;DR

`OpenCodeClient` is the sole consumer of the `HttpClient`, yet it doesn't own it — `OpenCodeConnectionManager` creates the client, configures its plugins, and passes it in as a constructor parameter. This split is pointless indirection. Meanwhile, `executeCommand()` (the only LLM-backed call besides `sendMessageAsync`) inherits the 60-second client-wide default and will timeout under load. `compactSession()` has the same risk but is currently dead code (never called — auto-compaction is server-side).

This TDD proposes:
1. **Move `HttpClient` ownership into `OpenCodeClient`** — the class that actually uses it should create, configure, and close it.
2. **Add per-request timeout overrides** to LLM-backed calls (`executeCommand`, `compactSession`) using the same `timeout {}` pattern already used by `sendMessageAsync`.
3. **Rename `OpenCodeConnectionManager` → `ProcessManager`** — it manages the server process lifecycle, not networking.
4. **Fix exception swallowing** in `postSuccess`, `deleteSuccess`, and `healthCheck` — propagate `CancellationException` instead of silently returning `false`.
5. **Make timeout buffer configurable** — `longTimeoutBufferSeconds` (default 30) replaces the hardcoded `LONG_BUFFER_MS` constant.
6. **Fix `disconnect()` to cancel `sseReconnectJob`** — eliminates the gap where `shutdown()` left the reconnection coroutine running against a closed client.

---

## 2. Context & Scope

### 2.1 Current Architecture

The plugin has a single `HttpClient(Java)` created in `OpenCodeConnectionManager.kt:100-116`:

```kotlin
val client = HttpClient(Java) {
    install(ContentNegotiation) { json(Json { ... }) }
    install(SSE)
    install(io.ktor.client.plugins.HttpTimeout) {
        requestTimeoutMillis = 60_000   // client-wide default for REST
        connectTimeoutMillis = 10_000   // TCP connection timeout
    }
}
```

This client is passed to `OpenCodeClient` at construction:

```kotlin
val opencodeClient = OpenCodeClient(
    baseUrl = "http://$host:$port",
    httpClient = client,
    authToken = authToken
)
```

**`OpenCodeConnectionManager` never makes a single HTTP call.** It delegates even health checks to `OpenCodeClient`. The `HttpClient` is 100% consumed by `OpenCodeClient`. The indirection is pointless.

There is also a **second `HttpClient`** in `Main.kt:29-31` (standalone ACP server path) with **no timeout configuration at all**:

```kotlin
val httpClient = HttpClient(Java) {
    // TODO: configure timeouts, connection pool
}
```

### 2.2 Endpoint Inventory

There are **23 HTTP call sites** in `OpenCodeClient` across 13 unique endpoint paths, using 4 internal helper methods and 9 direct `httpClient` calls:

**Helper methods (internal wrappers — 14 of the 23 call sites route through these):**

| Helper | Call Sites | Timeout Source |
|--------|------------|----------------|
| `getJson()` | 7 | Client-wide 60s default |
| `postJson()` | 2 | Client-wide 60s default |
| `postSuccess()` | 4 | Client-wide 60s default |
| `deleteSuccess()` | 1 | Client-wide 60s default |

**All 23 call sites:**

| # | Method | HTTP | Endpoint | Via | Timeout |
|---|--------|------|----------|-----|---------|
| 1 | `healthCheck()` | GET | `/global/health` | direct | 60s default |
| 2 | `createSession()` | POST | `/session` | direct | 60s default |
| 3 | `listSessions()` | GET | `/session` | direct | 60s default |
| 4 | `getSession()` | GET | `/session/{id}` | `getJson` | 60s default |
| 5 | `deleteSession()` | DELETE | `/session/{id}` | `deleteSuccess` | 60s default |
| 6 | `abortSession()` | POST | `/session/{id}/abort` | `postSuccess` | 60s default |
| 7 | `forkSession()` | POST | `/session/{id}/fork` | `postJson` | 60s default |
| 8 | `shareSession()` | GET | `/session/{id}/share` | `getJson` | 60s default |
| 9 | `unshareSession()` | DELETE | `/session/{id}/share` | direct | 60s default |
| 10 | `compactSession()` | POST | `/session/{id}/summarize` | `postSuccess` | 60s default |
| 11 | `revertMessage()` | POST | `/session/{id}/revert` | `postSuccess` | 60s default |
| 12 | `unrevertMessages()` | POST | `/session/{id}/unrevert` | `postSuccess` | 60s default |
| 13 | `sendMessageAsync()` | POST | `/session/{id}/message` | direct | **INFINITE** |
| 14 | `listMessages()` | GET | `/session/{id}/message` | `getJson` | 60s default |
| 15 | `getTodos()` | GET | `/session/{id}/todo` | `getJson` | 60s default |
| 16 | `executeCommand()` | POST | `/session/{id}/command` | `postJson` | 60s default |
| 17 | `listAgents()` | GET | `/agent` | `getJson` | 60s default |
| 18 | `listProviders()` | GET | `/provider` | `getJson` | 60s default |
| 19 | `listCommands()` | GET | `/command` | `getJson` | 60s default |
| 20 | `respondPermission()` | POST | `/permission/{id}/reply` | direct | 60s default |
| 21 | `respondQuestion()` | POST | `/question/{id}/reply` | direct | 60s default |
| 22 | `rejectQuestion()` | POST | `/question/{id}/reject` | direct | 60s default |
| 23 | `subscribeGlobalEvents()` | SSE | `/event` | direct | No request timeout (correct) |

> **Note:** Both `postSuccess()` and `deleteSuccess()` have the same exception-swallowing pattern — they catch all `Exception` (including `CancellationException`, which is the superclass of `TimeoutCancellationException` in Kotlin coroutines) and return `false`. The fix in §4.5 addresses `postSuccess`; `deleteSuccess` has the same issue (see §4.5.1).

**Current timeout map (before this change):**

```
60s default (client-wide — all 22 REST calls except sendMessageAsync):
  Via getJson (7):       getSession, shareSession, listMessages, getTodos,
                         listAgents, listProviders, listCommands
  Via postJson (2):      forkSession, executeCommand
  Via postSuccess (4):   abortSession, compactSession, revertMessage, unrevertMessages
  Via deleteSuccess (1): deleteSession
  Direct (8):            healthCheck, createSession, listSessions, unshareSession,
                         respondPermission, respondQuestion, rejectQuestion,
                         sendMessageAsync (overridden — see below)

INFINITE (per-request override):
  sendMessageAsync — uses timeout { requestTimeoutMillis = INFINITE_TIMEOUT_MS }

No request timeout (correct for SSE):
  subscribeGlobalEvents
```

> **Note on `unshareSession`:** Unlike the other 8 direct HTTP calls, `unshareSession` does NOT use `deleteSuccess` — it has its own inline `httpClient.delete` with custom error handling that re-throws exceptions (does not swallow them). It returns `OpenCodeSession`, not `Boolean`.

### 2.3 Problem Statement

**Problem 1: `executeCommand` will timeout under load.**

`executeCommand()` uses `postJson()` which inherits the 60-second client-wide default. Commands like `/review` and `/init` trigger LLM generation that can take minutes. Unlike `sendMessageAsync`, there is no per-request timeout override. The failure mode is **loud** (exception propagates to `CommandManager`, which catches and logs it), but the user sees the command fail with no explanation.

**Problem 2: `compactSession` has the same risk but is dead code.** *(Hypothetical — not a current risk)*

`compactSession()` uses `postSuccess()` which also inherits the 60-second default. However, **`compactSession` is never called anywhere in the codebase** — auto-compaction is handled server-side (see AGENTS.md: "Auto-Compaction is Server-Side"). The method exists for potential future use. If it were called, the failure mode would be **silent** (`postSuccess` catches all exceptions and returns `false`). The LONG profile is applied for future-proofing, not because this is a current risk.

**Problem 3: `HttpClient` ownership is misplaced.**

`OpenCodeConnectionManager` creates and configures the `HttpClient`, but never uses it. `OpenCodeClient` is the sole consumer. This split means:
- Timeout configuration lives in one class but timeout *decisions* live in another.
- `ConnectionManager` stores both `httpClient` and `openCodeClient` references and closes both separately on `disconnect()`.
- The `Main.kt` standalone path creates a bare `HttpClient` with no timeout configuration because the burden of proper setup falls on the caller.

**Problem 4: `postSuccess` swallows timeout exceptions.**

Four endpoints use `postSuccess()`: `abortSession`, `compactSession`, `revertMessage`, `unrevertMessages`. This helper catches **all** exceptions (including timeouts) and returns `false`. The caller cannot distinguish "server returned non-2xx" from "request timed out" from "network error." For `abortSession`, a timeout means the user's cancel button silently does nothing.

**Problem 5: `deleteSuccess` has the same swallowing pattern.**

One endpoint uses `deleteSuccess()`: `deleteSession`. This helper catches **all** exceptions (including timeouts) and returns `false` — the same pattern as `postSuccess`. If `deleteSession` times out, the caller silently receives `false` with no indication of why. The risk is lower than `postSuccess` (delete is a fast server-side operation, unlikely to approach 60s), but the pattern is inconsistent with the `postSuccess` fix.

### 2.4 Scope

**In scope:**
- Move `HttpClient` creation and configuration into `OpenCodeClient`
- Add per-request timeout overrides to LLM-backed calls (`executeCommand`, `compactSession`)
- Rename `OpenCodeConnectionManager` → `ProcessManager`
- Define timeout profiles as an internal concern of `OpenCodeClient`
- Fix `postSuccess` to propagate timeout exceptions (not swallow them)
- Fix `deleteSuccess` to propagate timeout exceptions (same pattern as `postSuccess`)
- Fix `healthCheck` to propagate `CancellationException` (consistency with `postSuccess`/`deleteSuccess`)
- Make `longTimeoutBufferSeconds` configurable in settings (was hardcoded `LONG_BUFFER_MS = 30_000L`)
- Fix `disconnect()` to cancel `sseReconnectJob` (pre-existing gap, now in scope)

**Out of scope:**
- Changing the OpenCode server's synchronous POST behavior (server-side change)
- Adding request retry logic (separate concern)
- Changing the SSE connection or health-check architecture (already fixed)
- Moving connection state into a UI manager (connection state is owned by the service layer, observed by UI)

---

## 3. Goals & Non-Goals

### Goals

1. **`OpenCodeClient` owns its `HttpClient`:** The class that makes all HTTP calls creates, configures, and closes the client. No external `httpClient` constructor parameter.
2. **No LLM-backed call uses the 60-second default:** `executeCommand` and `compactSession` get per-request timeout overrides, same pattern as `sendMessageAsync`.
3. **`ProcessManager` is a process manager:** It launches/kills the `opencode` binary, tracks connection state, and finds available ports. It does not touch networking.
4. **Timeout failures are distinguishable:** `postSuccess`, `deleteSuccess`, and `healthCheck` no longer swallow `CancellationException` — callers can tell why a call failed. Errors must be surfaced in chat, not silently swallowed.
5. **Timeout magic numbers are configurable:** `longTimeoutBufferSeconds` is a user-configurable setting, not a hardcoded constant.
6. **`disconnect()` is safe for all call paths:** `sseReconnectJob` is cancelled in `dispose()` before `shutdown()`, eliminating the gap where reconnection could run against a closed client.

### Non-Goals

- **Retry logic is out of scope.** Network retries are a separate concern.
- **Request logging/metrics are out of scope.** Observability improvements are separate.
- **Changing the server's blocking POST behavior is out of scope.** That requires a server-side change.
- **Moving connection state to a UI manager is out of scope.** Connection state is written by the service layer (`ProcessManager`, `OpenCodeService`) and observed by the UI. The UI manager would read it, not own it.

---

## 4. Proposed Solution

### 4.1 Architectural Change: HttpClient Ownership

**Before:**
```
ProcessManager (was ConnectionManager)
  ├── creates HttpClient(Java) with plugins + timeouts
  ├── creates OpenCodeClient(baseUrl, httpClient, authToken)
  ├── stores httpClient reference
  ├── stores openCodeClient reference
  └── closes both separately on disconnect()
```

**After:**
```
ProcessManager
  ├── creates OpenCodeClient(baseUrl, authToken)
  └── stores openCodeClient reference only

OpenCodeClient
  ├── creates HttpClient(Java) internally with plugins + timeouts
  ├── owns timeout profiles and per-request overrides
  └── closes HttpClient in own close()
```

### 4.2 Timeout Profiles — Internal to OpenCodeClient

Timeout profiles are an API-level concern, not an infrastructure concern. `OpenCodeClient` knows which endpoints need which timeout — `ProcessManager` does not.

```kotlin
// Inside OpenCodeClient — private implementation detail
private enum class TimeoutProfile {
    /** Fast server-side operations: health check, list sessions, get session.
     *  60 seconds — these should never block for LLM generation. */
    SHORT,
    /** Operations that may trigger LLM generation: compactSession, executeCommand.
     *  responseTimeoutSeconds + longTimeoutBufferSeconds — matches the expected generation duration. */
    LONG,
    /** sendMessageAsync — blocks for the entire LLM generation.
     *  INFINITE_TIMEOUT_MS — the activity monitor handles generation timeouts. */
    INFINITE
}

private fun HttpRequestBuilder.applyTimeoutProfile(profile: TimeoutProfile) {
    val settings = OpenCodeSettingsState.getInstance().state
    val timeoutMs = when (profile) {
        TimeoutProfile.SHORT -> SHORT_TIMEOUT_MS
        TimeoutProfile.LONG -> settings.responseTimeoutSeconds * 1000L + settings.longTimeoutBufferSeconds * 1000L
        TimeoutProfile.INFINITE -> HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
    timeout { requestTimeoutMillis = timeoutMs }
}
```

### 4.3 OpenCodeClient Constructor Change

**Before:**
```kotlin
class OpenCodeClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val authToken: String? = null
)
```

**After:**
```kotlin
class OpenCodeClient(
    private val baseUrl: String,
    private val authToken: String? = null
) : AutoCloseable {

    private val httpClient = HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(SSE)
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = SHORT_TIMEOUT_MS   // 60s — client-wide default for SHORT ops
            connectTimeoutMillis = CONNECT_TIMEOUT_MS  // 10s — TCP connection timeout
        }
    }
    // ... rest of class ...
}
```

> **Dual `Json` instance note:** `OpenCodeClient` has two `Json` configurations that must remain separate:
> 1. **Instance-level** `private val json = Json { ignoreUnknownKeys = true; isLenient = true; classDiscriminator = "type"; encodeDefaults = true }` — used for manual `json.decodeFromString<T>(body)` and `json.encodeToString(...)` calls in `getJson`, `postJson`, `postSuccess`, `deleteSuccess`, and direct HTTP methods.
> 2. **ContentNegotiation plugin** `Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }` — used for automatic request/response serialization by Ktor's `ContentNegotiation` plugin.
>
> The key difference: the instance-level `Json` has `classDiscriminator = "type"` for polymorphic deserialization; the ContentNegotiation `Json` does not. **Do not merge these** — merging would break polymorphic deserialization in the manual helpers.

**New imports required in `OpenCodeClient.kt`:**

```kotlin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
// io.ktor.client.plugins.HttpTimeout is already covered by existing wildcard import
// io.ktor.client.engine.java.Java is already imported
```

### 4.4 Per-Request Timeout Overrides

Each LLM-backed call adds a `timeout {}` block, same pattern as the existing `sendMessageAsync`:

```kotlin
// executeCommand — currently uses postJson with 60s default
suspend fun executeCommand(
    sessionId: String,
    command: String,
    args: String
): OpenCodeMessage {
    val response = httpClient.post("$baseUrl/session/$sessionId/command") {
        applyAuth()
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(/* ... */))
        applyTimeoutProfile(TimeoutProfile.LONG)
    }
    // ... existing response handling ...
}

// compactSession — currently uses postSuccess with 60s default
suspend fun compactSession(
    sessionId: String,
    providerID: String,
    modelID: String,
    auto: Boolean = false
): Boolean {
    val response = httpClient.post("$baseUrl/session/$sessionId/summarize") {
        applyAuth()
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(/* ... */))
        applyTimeoutProfile(TimeoutProfile.LONG)
    }
    // ... existing response handling ...
}
```

### 4.5 Fix `postSuccess` Exception Swallowing

**Before** — catches all exceptions, returns `false`:
```kotlin
private suspend fun postSuccess(path: String, request: Any? = null): Boolean = try {
    val response = httpClient.post("$baseUrl$path") { /* ... */ }
    response.status.isSuccess()
} catch (e: Exception) {
    logger.warn(e) { "POST $path failed" }
    false
}
```

**After** — propagates `CancellationException` (which is the superclass of `TimeoutCancellationException` in Kotlin coroutines — a single catch handles both):
```kotlin
private suspend fun postSuccess(path: String, request: Any? = null): Boolean = try {
    val response = httpClient.post("$baseUrl$path") { /* ... */ }
    response.status.isSuccess()
} catch (e: CancellationException) {
    throw e  // Propagate coroutine cancellation (includes TimeoutCancellationException)
} catch (e: Exception) {
    logger.warn(e) { "POST $path failed" }
    false
}
```

This lets callers of `abortSession`, `revertMessage`, and `unrevertMessages` distinguish timeout/cancellation failures from server errors. `compactSession` (if ever called) also benefits. Note: `TimeoutCancellationException` extends `CancellationException`, so the single `catch (e: CancellationException)` block handles both coroutine cancellation and HTTP timeouts.

### 4.5.1 Fix `deleteSuccess` Exception Swallowing

`deleteSuccess()` has the same `catch (e: Exception)` pattern as `postSuccess()`. The only caller is `deleteSession()`.

**Before** — catches all exceptions, returns `false`:
```kotlin
private suspend fun deleteSuccess(path: String): Boolean = try {
    val response = httpClient.delete("$baseUrl$path") { applyAuth() }
    response.status.isSuccess()
} catch (e: Exception) {
    logger.warn(e) { "DELETE $path failed" }
    false
}
```

**After** — propagates `CancellationException`:
```kotlin
private suspend fun deleteSuccess(path: String): Boolean = try {
    val response = httpClient.delete("$baseUrl$path") { applyAuth() }
    response.status.isSuccess()
} catch (e: CancellationException) {
    throw e  // Propagate coroutine cancellation (includes timeout)
} catch (e: Exception) {
    logger.warn(e) { "DELETE $path failed" }
    false
}
```

The risk is lower than `postSuccess` (delete is a fast server-side operation), but the pattern should be consistent. The only caller (`deleteSession`) returns `Boolean` to its caller — a `CancellationException` propagates safely through the coroutine scope.

### 4.6 ProcessManager Changes

**Rename:** `OpenCodeConnectionManager` → `ProcessManager`

**Remove:** `httpClient` field and all direct `HttpClient` references. The class no longer creates, stores, or closes the `HttpClient`.

**Before:**
```kotlin
class OpenCodeConnectionManager(private val scope: CoroutineScope) {
    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    // ...

    suspend fun initialize(projectBasePath: String = "."): Boolean {
        // ...
        val client = HttpClient(Java) { /* ... */ }
        httpClient = client
        val opencodeClient = OpenCodeClient(
            baseUrl = "http://$host:$port",
            httpClient = client,
            authToken = authToken
        )
        openCodeClient = opencodeClient
        // ...
    }

    fun disconnect() {
        // ...
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        // ...
    }
}
```

**After:**
```kotlin
class ProcessManager(private val scope: CoroutineScope) {
    private var openCodeClient: OpenCodeClient? = null
    // ... (no httpClient field) ...

    suspend fun initialize(projectBasePath: String = "."): Boolean {
        // ...
        val opencodeClient = OpenCodeClient(
            baseUrl = "http://$host:$port",
            authToken = authToken
        )
        openCodeClient = opencodeClient
        // ...
    }

    fun disconnect() {
        // ...
        openCodeClient?.close()
        openCodeClient = null
        // ... (no httpClient?.close())
    }
}
```

> **`close()` contract change:** Currently `OpenCodeClient.close()` is a no-op (the `HttpClient` lifecycle is managed by the caller). After this change, `close()` closes the internal `HttpClient`. The contract becomes: **`close()` is idempotent. After `close()`, no HTTP calls can be made on this `OpenCodeClient` instance.** Callers must not call `close()` while HTTP requests are in flight.

> **`authToken` ownership:** The `authToken` property remains on `ProcessManager` (as it is today on `ConnectionManager`). It is passed to `OpenCodeClient` at construction and never changes for the lifetime of that client instance. If `authToken` ever needs to change (e.g., server restart with new credentials), a new `OpenCodeClient` instance must be created. Currently `authToken` is always `null` in production — the OpenCode plugin runs without authentication on localhost.

> **SSE/HttpClient close ordering:** `OpenCodeService` manages the SSE lifecycle: it cancels `sseJob` and `sseHealthCheckJob` before calling `connectionManager.disconnect()`. After this change, `disconnect()` calls `openCodeClient?.close()` which closes the `HttpClient` that the SSE flow depends on. The existing sequencing (cancel SSE jobs → disconnect) is correct and must be preserved. If `disconnect()` is called while SSE is active, the SSE flow will fail with a "client is closed" exception — this is the same behavior as the current code (which nulls `httpClient` after closing it). The contract is: **`OpenCodeService` must cancel SSE jobs before calling `disconnect()`.**

### 4.7 Main.kt Standalone Path Fix

**Before:**
```kotlin
val httpClient = HttpClient(Java) {
    // TODO: configure timeouts, connection pool
}
val openCodeClient = OpenCodeClient(
    baseUrl = "http://${config.openCodeHost}:${config.openCodePort}",
    httpClient = httpClient,
    authToken = config.openCodePassword
)
```

**After:**
```kotlin
val openCodeClient = OpenCodeClient(
    baseUrl = "http://${config.openCodeHost}:${config.openCodePort}",
    authToken = config.openCodePassword
)
```

The standalone path automatically gets proper timeout configuration because `OpenCodeClient` creates its own `HttpClient` internally.

**Import cleanup in `Main.kt`:** After this change, remove `import io.ktor.client.HttpClient` and `import io.ktor.client.engine.java.Java` — `Main.kt` no longer references either class directly.

### 4.8 Endpoint-to-Profile Mapping

| Endpoint | Method | Profile | Rationale |
|----------|--------|---------|-----------|
| `/global/health` | GET | SHORT | Lightweight liveness check |
| `/session` | GET | SHORT | List sessions |
| `/session/{id}` | GET | SHORT | Get session detail |
| `/session/{id}/message` | GET | SHORT | List messages |
| `/session/{id}/todo` | GET | SHORT | Fetch todos |
| `/agent` | GET | SHORT | List agents |
| `/provider` | GET | SHORT | List providers |
| `/command` | GET | SHORT | List commands |
| `/session/{id}/share` | GET | SHORT | Retrieve (or auto-create) share link |
| `/session` | POST | SHORT | Create session |
| `/session/{id}/fork` | POST | SHORT | Fork session |
| `/session/{id}/abort` | POST | SHORT | Abort generation |
| `/session/{id}/revert` | POST | SHORT | Revert message |
| `/session/{id}/unrevert` | POST | SHORT | Unrevert messages |
| `/session/{id}` | DELETE | SHORT | Delete session |
| `/session/{id}/share` | DELETE | SHORT | Remove share link |
| `/permission/{id}/reply` | POST | SHORT | Respond to permission |
| `/question/{id}/reply` | POST | SHORT | Respond to question |
| `/question/{id}/reject` | POST | SHORT | Reject question |
| `/session/{id}/summarize` | POST | **LONG** | Compaction triggers LLM call (dead code — never called, but future-proofed) |
| `/session/{id}/command` | POST | **LONG** | Commands can trigger LLM calls |
| `/session/{id}/message` | POST | **INFINITE** | Blocks for entire generation |
| `/event` | SSE | N/A | No request timeout (correct) |

> **SSE endpoint path:** The code uses `$baseUrl/event` (i.e., `GET /event`). AGENTS.md documents both `GET /event` (global) and `GET /global/event` as valid paths. The server may accept both, but the plugin only uses `/event`. This is not affected by this TDD.

### 4.9 Constants

```kotlin
// Inside OpenCodeClient companion object
private const val SHORT_TIMEOUT_MS = 60_000L       // SHORT profile — fast server-side ops
private const val CONNECT_TIMEOUT_MS = 10_000L     // TCP connection timeout (applies to ALL profiles)
```

The `LONG` profile timeout is computed as `responseTimeoutSeconds * 1000L + longTimeoutBufferSeconds * 1000L` — both values are user-configurable in Settings → Tools → OpenCode.

> **`connectTimeoutMillis` is profile-independent:** Unlike `requestTimeoutMillis`, Ktor's `connectTimeoutMillis` cannot be overridden per-request via `timeout {}`. It is set once at the client-wide level (10s) and applies to all requests. This is acceptable because TCP connection establishment is fast regardless of how long the server takes to process the request — the 10s timeout only covers the TCP handshake, not server processing time.

> **`longTimeoutBufferSeconds` (default 30):** Configurable in `OpenCodeSettingsState`. The buffer accounts for server-side overhead beyond the LLM generation time: request queuing, tool execution between LLM calls, network latency spikes, and server-side bookkeeping (storing results, updating session state). The `responseTimeoutSeconds` setting (default 300s) represents the user's expected maximum LLM generation time; the buffer ensures the HTTP timeout doesn't fire just because the server took a few extra seconds on non-generation work. Users can increase this if they experience LONG-profile timeouts due to server overhead.

> **`responseTimeoutSeconds` minimum enforcement:** `OpenCodeSettingsState.loadState()` coerces `responseTimeoutSeconds` to a minimum of 60s. The `longTimeoutBufferSeconds` setting should also have a minimum (e.g., 10s) enforced in `loadState()`. This means the LONG profile timeout is always at least 70s (60s + 10s buffer), which is safely above the SHORT profile (60s). The `applyTimeoutProfile` method does NOT add its own floor — it relies on the settings migration to enforce the minimums.

---

## 5. Assumptions & Dependencies

**Assumptions:**
- The OpenCode server's `POST /session/:id/message` will remain a synchronous blocking endpoint. The activity monitor in `OpenCodeService` is the correct place to handle generation timeouts.
- `executeCommand` can block for similar durations as `sendMessageAsync` (LLM generation time) when commands like `/review` trigger full agent runs. Simpler commands (`/init`) are fast but the timeout must accommodate the worst case.
- `compactSession` is dead code (auto-compaction is server-side per AGENTS.md). The LONG profile is applied for future-proofing, not because it's a current risk.
- `OpenCodeSettingsState.getInstance().state` is thread-safe for reads from coroutine contexts (IntelliJ's `PersistentStateComponent` guarantees this).
- `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` is a stable Ktor API. It's defined in `io.ktor.client.plugins.HttpTimeoutConfig` and has been present since Ktor 2.x.
- `HttpRequestBuilder.timeout {}` correctly overrides the client-wide `HttpTimeout` plugin configuration on a per-request basis (verified in Ktor docs).
- `CancellationException` propagation from `postSuccess` and `deleteSuccess` is safe — all callers are called from coroutine contexts where `CancellationException` propagates safely via cooperative cancellation (see §9.2 for detailed caller analysis).

**Dependencies:**
- `OpenCodeSettingsState.responseTimeoutSeconds` must be imported by `OpenCodeClient`. Currently, `OpenCodeClient.kt` does **not** import `OpenCodeSettingsState` — the setting is read by `OpenCodeService` (line 502), not `OpenCodeClient`. After this change, `OpenCodeClient.applyTimeoutProfile()` reads the setting directly, so a new import is required: `import com.opencode.acp.config.settings.OpenCodeSettingsState`.

---

## 6. Alternatives Considered

**Alternative: Centralize timeout profiles in ProcessManager (was ConnectionManager)**
- *What it is:* ProcessManager exposes `applyTimeout(builder, profile)` and `OpenCodeClient` calls it for each request.
- *Why plausible:* Single point of configuration.
- *Why rejected:* ProcessManager is a process lifecycle manager, not a networking class. It doesn't make HTTP calls and shouldn't know about endpoint-specific timeout semantics. Adding a networking concern to a process class violates SRP. `OpenCodeClient` already has the `timeout {}` API — it just needs to use it consistently.

**Alternative: Make all POST calls use INFINITE_TIMEOUT_MS**
- *What it is:* Set `requestTimeoutMillis = INFINITE_TIMEOUT_MS` on the client-wide default for all requests.
- *Why plausible:* Eliminates all timeout concerns.
- *Why rejected:* A hung server would cause any REST call to hang forever. The 60-second default for fast operations (list sessions, health check) is a reasonable safety net. Only LLM-backed calls need longer timeouts.

**Alternative: Add a MEDIUM profile (e.g., 120s) for operations that are slow but not LLM-backed**
- *What it is:* Three-tier profile: SHORT (60s), MEDIUM (120s), LONG (responseTimeoutSeconds + 30s).
- *Why plausible:* Some operations (e.g., `listMessages` for sessions with hundreds of messages, `listSessions` with 3 fallback calls) might approach 60s under load.
- *Why rejected:* The only known path where 60s may be insufficient is `listSessions` via `loadSessions()` — in the worst case it makes 3 sequential 60s calls (180s total, see §7.5). However, this is a cold path (only triggered on error or empty result), and the SHORT profile catches each individual call correctly (a hung server returns within 60s, not at 180s). For true server-side slowness (not a hang), the user can adjust `responseTimeoutSeconds`. A dedicated MEDIUM profile is premature without observed production timeouts.

---

## 7. Cross-Cutting Concerns

### 7.1 Reliability & Availability

The current architecture has a reliability gap: `executeCommand` can timeout under load because it inherits the 60-second default. This fix closes that gap by applying the `LONG` profile to `executeCommand`. `compactSession` is also future-proofed with the `LONG` profile, though it's currently dead code.

The `postSuccess` fix (propagating `CancellationException`) improves reliability for `abortSession` — a timeout during abort no longer silently returns `false`, which would leave the user unable to cancel a runaway generation.

### 7.2 Observability

Timeout-related log messages should include the profile used, so debugging timeout issues is straightforward:

```
[ACP] POST /session/{id}/command timed out after 330s (profile=LONG)
```

This requires adding the profile name to the exception handler in `OpenCodeClient`. The `applyTimeoutProfile` method can log the profile at debug level when applied:

```kotlin
private fun HttpRequestBuilder.applyTimeoutProfile(profile: TimeoutProfile) {
    val timeoutMs = /* ... */
    logger.debug { "[ACP] Applying timeout profile $profile (${timeoutMs}ms)" }
    timeout { requestTimeoutMillis = timeoutMs }
}
```

### 7.3 SSE Health Check Interaction

`OpenCodeService.launchHealthCheck()` wraps `client.healthCheck()` in `withTimeout(10_000)`. This coroutine-level timeout overrides the HTTP-level SHORT profile (60s). The effective timeout for health checks is **10s** (from `withTimeout`), not 60s (from the SHORT profile). This is correct and intentional — health checks should be fast. The TDD documents this interaction explicitly to avoid confusion.

### 7.4 Runtime Setting Changes

If the user changes `responseTimeoutSeconds` in Settings while a LONG-profile request is in flight, the in-flight request uses the timeout value that was read when `applyTimeoutProfile` was called. The next request will read the new value. This is acceptable — in-flight requests cannot be retroactively re-timed.

### 7.5 `listSessions` Multi-Call Pattern

`SessionManager.loadSessions()` can make up to 3 `listSessions` calls (filtered → unfiltered on exception → unfiltered on empty result). Each uses the SHORT profile (60s). Under extreme server load, this could take up to 180s total. This is acceptable — the SHORT profile is a safety net, and the fallback pattern is a cold path (only triggered on error or empty result).

### 7.6 `respondPermission`/`respondQuestion`/`rejectQuestion` — No Response Status Check

These three direct `httpClient.post` calls don't check the response status. They fire the POST and ignore the response body. This is a pre-existing gap unrelated to timeout centralization. The SHORT profile is appropriate for these calls — they are fast server-side operations. A separate TDD should address the missing status checks.

Additionally, `PermissionManager.respondPermission()` wraps `client.respondPermission()` in a catch-all `Exception` block (line 40) that swallows `TimeoutCancellationException`. This is a pre-existing bug — a timeout during permission response silently keeps the prompt open for retry, even though the timeout means the connection may be dead. This is out of scope for this TDD but should be noted for a future fix.

Similarly, `PermissionManager.respondQuestion()` (line 46-49) and `rejectQuestion()` (line 51-55) call `client.respondQuestion()`/`client.rejectQuestion()` with **no try/catch at all**. If these POSTs timeout, the `TimeoutCancellationException` propagates to the caller and the coroutine is cooperatively cancelled. The question prompt remains in the UI with no error feedback — same silent failure pattern as `respondPermission`. This is another pre-existing gap that a separate TDD should address.

### 7.7 SSE/HttpClient Close Ordering Contract

After `HttpClient` ownership moves into `OpenCodeClient`, calling `openCodeClient?.close()` closes the `HttpClient` that the SSE flow depends on. `OpenCodeService` manages this sequencing through two paths:

1. **`stopConnection()`** (called by the "Stop" button): cancels `sseReconnectJob`, `sseHealthCheckJob`, and `sseJob` before calling `connectionManager.disconnect()`. This path is correct.
2. **`shutdown()` → `disconnect()`** (called by `dispose()` on IDE close): currently does NOT cancel `sseReconnectJob`. This is being fixed in this TDD — `disconnect()` will cancel `sseReconnectJob` alongside `processWatcherJob` (see §7.12).

If `disconnect()` is called while SSE is active (without cancelling the SSE job first), the SSE flow will fail with a "client is closed" or `IllegalStateException` when it next tries to read from the `HttpClient`. This is the same behavior as the current code (which nulls `httpClient` after closing it), but the contract becomes more important because the `HttpClient` is no longer a separate reference that can be closed independently.

**Contract:** After this TDD, `ProcessManager.disconnect()` cancels `sseReconnectJob` and `processWatcherJob` internally. `OpenCodeService.stopConnection()` still cancels `sseJob` and `sseHealthCheckJob` before calling `disconnect()`. The `shutdown()` → `disconnect()` path is now safe because `disconnect()` handles `sseReconnectJob` itself.

### 7.8 `CommandManager` Timeout Swallowing After LONG Profile Fix

After applying the LONG profile to `executeCommand`, if a command STILL exceeds `responseTimeoutSeconds + longTimeoutBufferSeconds`, the `TimeoutCancellationException` propagates through `CommandManager.executeServerCommand()` which re-throws `CancellationException` (line 41-42). The re-thrown exception propagates to the coroutine scope, which handles it via cooperative cancellation — the coroutine is cancelled, but the user gets **no feedback** about why the command failed.

This is a pre-existing gap (same behavior as the current 60s timeout, just at a higher threshold). The `CommandManager` catches `Exception` and logs a warning, but `CancellationException` is re-thrown before the catch block. Per user requirement: "at the very least an error needs to be displayed in chat." A future improvement should catch `TimeoutCancellationException` specifically and surface a user-visible error message in the chat UI.

### 7.9 `OpenCodeService.cancel()` — No Explicit `CancellationException` Handling

`OpenCodeService.cancel()` (line 564-574) calls `client.abortSession(currentSessionId)` with no try/catch. After the `postSuccess` fix, a timeout during abort throws `CancellationException`. Since `cancel()` has no try/catch, the exception propagates to the coroutine scope.

This is **safe** — Kotlin coroutines handle `CancellationException` cooperatively (no crash, just coroutine cancellation). However, the practical effect is that `responseDeferred?.complete(Unit)` (line 571) never executes, so the user's cancel button effectively does nothing on timeout. This is the same outcome as the current silent `false` return, but via a different mechanism.

The TDD's §9.2 claim that "all callers handle CancellationException" should be read as "all callers are safe due to coroutine cooperative cancellation semantics" — not that they have explicit catch blocks.

### 7.10 `healthCheck()` Exception Swallowing

`OpenCodeClient.healthCheck()` catches all `Exception` and returns `false`:

```kotlin
suspend fun healthCheck(): Boolean = try {
    val response = httpClient.get("$baseUrl/global/health") { applyAuth() }
    response.status.isSuccess()
} catch (e: Exception) {
    logger.warn(e) { "Health check failed" }
    false
}
```

This swallows `CancellationException` (the superclass of `TimeoutCancellationException`). There are two scenarios:

1. **HTTP timeout (server hung):** Returning `false` is correct — a timed-out health check IS a "not healthy" result.
2. **Coroutine cancellation (scope cancelled, e.g. IDE shutdown):** Swallowing `CancellationException` breaks cooperative cancellation. The coroutine should propagate the cancellation, not swallow it and return `false`.

The caller (`launchHealthCheck`) wraps the call in its own try/catch that handles `CancellationException` separately (line 253-254), so the practical impact is nil today. However, for correctness and consistency with the `postSuccess`/`deleteSuccess` fix, `healthCheck()` should add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)`. This is a medium-priority consistency improvement — not critical, but should be done in the same change if feasible.

### 7.11 `HttpClient` Construction Failure

After the move, `HttpClient(Java) { ... }` is created inside `OpenCodeClient`'s constructor. If construction fails (e.g., Java engine not available on the platform), the exception propagates from the `OpenCodeClient` constructor → `ProcessManager.initialize()` → caller. This is the same behavior as the current code (where `HttpClient` construction fails in `ConnectionManager.initialize()`). No new error handling is needed, but the TDD notes that `OpenCodeClient` construction is now a fallible operation (previously, the `HttpClient` was created separately and passed in, so `OpenCodeClient` construction itself could not fail due to engine issues).

### 7.12 `HttpClient` Lifecycle During Reconnection

The SSE reconnection flow (`triggerGlobalSseReconnect()`) calls `connectionManager.client` to get the `OpenCodeClient` for health checks and SSE re-subscription. After the change, the `OpenCodeClient` owns its `HttpClient`. If `disconnect()` is called during reconnection (e.g., user closes tool window while reconnecting), the `HttpClient` is closed and the reconnection coroutine fails.

Currently, `disconnect()` cancels `processWatcherJob` but does NOT cancel `sseReconnectJob`. The `stopConnection()` method (Stop button) correctly cancels `sseReconnectJob` before calling `disconnect()`, but `shutdown()` → `disconnect()` does NOT cancel `sseReconnectJob`. This means:
- **Stop button path:** Safe — `stopConnection()` cancels all SSE jobs before `disconnect()`.
- **IDE close path (`dispose()` → `shutdown()` → `disconnect()`):** Gap — `sseReconnectJob` is not cancelled. The reconnection coroutine could attempt to use a closed client after `HttpClient` ownership moves into `OpenCodeClient`.

**Fix (in scope for this TDD):** `disconnect()` must cancel `sseReconnectJob` alongside `processWatcherJob`. This eliminates the gap for all call paths (both `stopConnection()` and `shutdown()`).

### 7.13 `OpenCodeAgentSession.abortSession` — ACP Path Beneficiary

The ACP SDK path (`OpenCodeAgentSession.kt:295`) also calls `openCodeClient.abortSession()`. The `postSuccess` fix benefits this path too — a timeout during abort in the ACP path now throws `CancellationException` instead of silently returning `false`. The ACP path is not the primary focus of this TDD, but the fix applies uniformly because it modifies the underlying `OpenCodeClient.postSuccess()` method.

### 7.14 Timeout Decision Tree for Future Endpoints

When a new endpoint is added to `OpenCodeClient`, the developer must decide which timeout profile to use. The following decision tree prevents mistakes:

```
Does the endpoint trigger LLM generation?
  ├─ YES — Does it block for the entire generation (like sendMessageAsync)?
  │    └─ YES → INFINITE (the activity monitor handles generation timeouts)
  │    └─ NO  → LONG (returns after generation, like executeCommand)
  └─ NO  → SHORT (fast server-side operation)
```

All SSE endpoints use no request timeout (correct — SSE is a long-lived stream, not a request/response).

### 7.15 `initialize()` Failure — Mid-Path Resource Leak

`ProcessManager.initialize()` creates the `OpenCodeClient` (which now creates its own `HttpClient`) at line ~408, then launches the binary and polls the health check. If the binary fails to start or the health check times out, `initialize()` returns `false` — but the `OpenCodeClient` and its internal `HttpClient` are never closed.

This is a **pre-existing leak** (the current code creates `HttpClient` before binary launch too), not introduced by this TDD. After this change, the leak is contained to a single object (`OpenCodeClient`) instead of two (`HttpClient` + `OpenCodeClient`), so the situation slightly improves. A future improvement could add a try/finally that calls `openCodeClient?.close()` on initialization failure.

### 7.16 `ContentNegotiation` Plugin Necessity

`OpenCodeClient` has two serialization paths:
1. **Instance-level `Json`** (line 48-53): used for manual `json.decodeFromString<T>(body)` and `json.encodeToString(...)` calls in `getJson`, `postJson`, `postSuccess`, `deleteSuccess`, and direct HTTP methods.
2. **`ContentNegotiation` plugin** (installed in the `HttpClient`): provides automatic request/response serialization.

However, the code **never uses** Ktor's automatic serialization. Every call site uses `response.bodyAsText()` + manual `json.decodeFromString()`, and `setBody(json.encodeToString(...))` for requests. The `ContentNegotiation` plugin is installed but effectively unused.

**Decision:** Keep `ContentNegotiation` for now — removing it is a separate cleanup concern. The plugin has minimal overhead (it only activates when `Content-Type: application/json` is present and a `receive<T>()` call is made). Removing it would require verifying that no code path relies on automatic deserialization, which is out of scope for this TDD. A follow-up cleanup could remove it if confirmed unused.

### 7.17 `OpenCodeClient.close()` Behavior with In-Flight Requests

The TDD states: "Callers must not call `close()` while HTTP requests are in flight." However, it does not specify what happens if this contract is violated.

Ktor's `HttpClient.close()` cancels the `HttpClient`'s coroutine scope, which causes in-flight requests to fail with `CancellationException`. This is safe (no crash, cooperative cancellation), but callers that catch generic `Exception` (like the old `postSuccess`) would silently return `false` instead of propagating the cancellation.

After the `postSuccess`/`deleteSuccess` fix (catching `CancellationException` before generic `Exception`), in-flight requests that are cancelled by `close()` will properly propagate the cancellation. The contract is self-enforcing: violating it produces `CancellationException`, which propagates correctly through the fixed exception handling.

**No additional enforcement is needed.** The existing `catch (e: CancellationException) { throw e }` pattern handles this correctly.

---

## 8. Implementation Plan

### Step 1: Move HttpClient into OpenCodeClient

1. Add `HttpClient(Java)` creation inside `OpenCodeClient`'s init block or as a private val.
2. Remove the `httpClient` constructor parameter.
3. Move the `ContentNegotiation`, `SSE`, and `HttpTimeout` plugin configuration from `ProcessManager` into `OpenCodeClient`.
4. Add new imports to `OpenCodeClient.kt`: `ContentNegotiation`, `json` (from `io.ktor.serialization.kotlinx.json`), `OpenCodeSettingsState`.
5. Update `OpenCodeClient.close()` to close the `HttpClient` (currently a no-op). Add a comment documenting the contract: "Closes the internal HttpClient. After close(), no HTTP calls can be made."
6. Update `ProcessManager.initialize()` to create `OpenCodeClient(baseUrl, authToken)` without the `httpClient` parameter.
7. Remove the `httpClient` field from `ProcessManager`.
8. Remove `httpClient?.close()` from `ProcessManager.disconnect()`.
9. Remove `import io.ktor.client.*` and related Ktor imports from `ProcessManager.kt` (no longer needed).

### Step 2: Add Timeout Profiles to OpenCodeClient

1. Define `TimeoutProfile` enum as a private inner enum in `OpenCodeClient`.
2. Define `applyTimeoutProfile()` as a private extension on `HttpRequestBuilder`.
3. Add `SHORT_TIMEOUT_MS`, `CONNECT_TIMEOUT_MS`, `LONG_BUFFER_MS` constants to `OpenCodeClient` companion.

### Step 3: Apply Per-Request Timeouts

1. Add `applyTimeoutProfile(TimeoutProfile.LONG)` to `executeCommand` (inside `postJson` — requires converting from `postJson` helper to direct call, or adding a profile parameter to `postJson`).
2. Add `applyTimeoutProfile(TimeoutProfile.LONG)` to `compactSession` (same approach).
3. Verify `sendMessageAsync` already uses `applyTimeoutProfile(TimeoutProfile.INFINITE)` (or equivalent `timeout { requestTimeoutMillis = INFINITE_TIMEOUT_MS }`).

### Step 4: Fix `postSuccess`, `deleteSuccess`, and `healthCheck` Exception Swallowing

1. Add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` in `postSuccess`.
2. Add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` in `deleteSuccess`.
3. Add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` in `healthCheck` (consistency fix — see §7.10).
4. Verify all callers handle `CancellationException` correctly (see §9.2 for detailed analysis).

### Step 5: Rename ConnectionManager → ProcessManager

1. Rename `OpenCodeConnectionManager.kt` → `ProcessManager.kt`.
2. Rename the class `OpenCodeConnectionManager` → `ProcessManager`.
3. Update all references in `OpenCodeService.kt` (line 27 doc comment, line 41 instantiation) and any other files. Note: `ChatToolWindowFactory.kt` does NOT reference `OpenCodeConnectionManager` directly — it only references `OpenCodeService`.
4. Update AGENTS.md references.

### Step 6: Fix Main.kt Standalone Path

1. Remove the bare `HttpClient(Java)` creation.
2. Create `OpenCodeClient(baseUrl, authToken)` directly — it now creates its own `HttpClient`.
3. Remove unused imports: `import io.ktor.client.HttpClient` and `import io.ktor.client.engine.java.Java`.

### Step 7: Update Helper Methods

The `getJson`, `postJson`, `postSuccess`, `deleteSuccess` helpers currently don't accept a `TimeoutProfile` parameter. Two approaches:

**Option A (recommended):** Add an optional `profile` parameter with default `SHORT`:
```kotlin
private suspend inline fun <reified T> postJson(
    path: String,
    request: Any,
    profile: TimeoutProfile = TimeoutProfile.SHORT
): T {
    val response = httpClient.post("$baseUrl$path") {
        applyAuth()
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(/* ... */))
        applyTimeoutProfile(profile)
    }
    // ...
}
```

Then `executeCommand` calls `postJson(path, request, profile = TimeoutProfile.LONG)`.

**Option B:** Convert `executeCommand` and `compactSession` from using helpers to direct `httpClient.post` calls with `applyTimeoutProfile`. This avoids changing the helper signatures but duplicates the POST boilerplate.

Option A is preferred — it keeps the helpers DRY and makes the profile choice explicit at each call site.

### Step 8: Make `longTimeoutBufferSeconds` Configurable

1. Add `var longTimeoutBufferSeconds: Int = 30` to `OpenCodeSettingsState`.
2. Add minimum enforcement in `loadState()`: `longTimeoutBufferSeconds = state.longTimeoutBufferSeconds.coerceAtLeast(10)`.
3. Add UI field in `OpenCodeSettingsPanel` (e.g., "Long timeout buffer (seconds)" — default 30, min 10).
4. Update `OpenCodeClient.applyTimeoutProfile()` to read `settings.longTimeoutBufferSeconds` instead of `LONG_BUFFER_MS`.
5. Remove `LONG_BUFFER_MS` constant from `OpenCodeClient` companion object.

### Step 9: Fix `disconnect()` to Cancel `sseReconnectJob`

1. In `ProcessManager.disconnect()`, add `sseReconnectJob?.cancel()` and `sseReconnectJob = null` alongside the existing `processWatcherJob` cancellation.
2. This requires `ProcessManager` to have a reference to `sseReconnectJob`. Two approaches:
   - **Option A:** Pass `sseReconnectJob` as a parameter to `disconnect()`.
   - **Option B:** Have `OpenCodeService` cancel `sseReconnectJob` before calling `disconnect()` (move the cancellation from `stopConnection()` into a shared helper).
   - **Option C (recommended):** `OpenCodeService.stopConnection()` already cancels `sseReconnectJob`. For `shutdown()` → `disconnect()`, add `sseReconnectJob?.cancel()` in `OpenCodeService.dispose()` before calling `connectionManager.shutdown()`. This keeps the SSE job references in `OpenCodeService` (where they belong) rather than leaking them into `ProcessManager`.
3. Verify that `stopConnection()` and `dispose()` both cancel `sseReconnectJob` before `disconnect()`/`shutdown()`.

### Step 10: Migration Checklist

Verify each item after implementation:

```
□ OpenCodeClient.kt: HttpClient created internally, no httpClient constructor param
□ OpenCodeClient.kt: New imports added (ContentNegotiation, json, OpenCodeSettingsState)
□ OpenCodeClient.kt: TimeoutProfile enum + applyTimeoutProfile method defined
□ OpenCodeClient.kt: executeCommand uses LONG profile
□ OpenCodeClient.kt: compactSession uses LONG profile
□ OpenCodeClient.kt: sendMessageAsync uses INFINITE profile (existing — regression check)
□ OpenCodeClient.kt: postSuccess propagates CancellationException
□ OpenCodeClient.kt: deleteSuccess propagates CancellationException
□ OpenCodeClient.kt: healthCheck propagates CancellationException (consistency fix — see §7.10)
□ OpenCodeClient.kt: applyTimeoutProfile reads longTimeoutBufferSeconds from settings (not hardcoded)
□ OpenCodeClient.kt: close() closes HttpClient (no longer a no-op)
□ OpenCodeClient.kt: Dual Json instances preserved (instance-level has classDiscriminator = "type")
□ ProcessManager.kt: Renamed from OpenCodeConnectionManager
□ ProcessManager.kt: No httpClient field, no HttpClient imports
□ ProcessManager.kt: disconnect() only closes openCodeClient
□ OpenCodeService.kt: References updated to ProcessManager
□ OpenCodeService.kt: dispose() cancels sseReconnectJob before shutdown() (fixes §7.12 gap)
□ OpenCodeSettingsState.kt: longTimeoutBufferSeconds added (default 30, min 10)
□ OpenCodeSettingsPanel.kt: longTimeoutBufferSeconds UI field added
□ Main.kt: No httpClient creation, OpenCodeClient(baseUrl, authToken), unused Ktor imports removed
□ AGENTS.md: References updated
□ SSE close ordering: stopConnection() cancels all SSE jobs before disconnect() (existing — regression check)
□ SSE close ordering: dispose() cancels sseReconnectJob before shutdown() (new fix — see §7.12)
```

---

## 9. Testing Strategy

### 9.1 Key Scenarios

1. **`sendMessageAsync` with long generation (>5 min):** Verify the POST does not timeout. The activity monitor handles the generation timeout. (Existing behavior — regression check.)

2. **`executeCommand` with LLM-backed command (e.g., `/review`):** Verify the POST uses the `LONG` profile and does not timeout at 60 seconds. The timeout is `responseTimeoutSeconds + 30s`.

3. **`compactSession` with large conversation:** Verify the POST uses the `LONG` profile. (Dead code path — test by calling the method directly if needed for future-proofing.)

4. **Health check during SSE silence:** Verify the 10-second `withTimeout` on the health-check probe still works independently of the SHORT profile's 60s timeout. The `withTimeout` is the effective timeout, not the HTTP-level one.

5. **All SHORT-profile calls:** Verify they still timeout at 60 seconds if the server is hung (safety net).

6. **`postSuccess` timeout propagation:** Verify that a timeout on `abortSession` throws `CancellationException` instead of silently returning `false`. The caller (`OpenCodeService.cancel()`) does NOT have an explicit try/catch — the `CancellationException` propagates safely via cooperative coroutine cancellation (see §7.9).

7. **`deleteSuccess` timeout propagation:** Verify that a timeout on `deleteSession` throws `CancellationException` instead of silently returning `false`.

8. **`Main.kt` standalone path:** Verify the `OpenCodeClient` created without an explicit `httpClient` parameter has proper timeout configuration (no more `// TODO`).

9. **ProcessManager rename:** Verify all references updated, no compilation errors.

### 9.2 Regression Risks

- **`OpenCodeClient` constructor change:** Only two callers exist: `ProcessManager.initialize()` and `Main.kt`. Both are updated in the same change.
- **`postSuccess` exception propagation change:** Four callers (`abortSession`, `compactSession`, `revertMessage`, `unrevertMessages`). **Only `abortSession` is live code** — the other three are dead code (never called). Caller analysis:
   - `abortSession` → called from `OpenCodeService.cancel()` (no try/catch) and `OpenCodeAgentSession` (ACP path, has catch-all `catch (_: Exception) { }` at line 296). Both are safe — `CancellationException` propagates via cooperative coroutine cancellation (no crash, just coroutine cancellation). In the ACP path, the catch-all still swallows `CancellationException` under both old and new code (same behavior, no regression). See §7.9 for practical effect in the Chat UI path.
   - `compactSession` → dead code (never called — auto-compaction is server-side).
   - `revertMessage` → dead code (never called anywhere in the codebase — grep confirms zero call sites).
   - `unrevertMessages` → dead code (never called anywhere in the codebase — grep confirms zero call sites).
   
   **Impact summary:** The `postSuccess` fix only has a runtime effect on `abortSession`. The other three callers benefit from future-proofing if they are ever wired up.
- **`deleteSuccess` exception propagation change:** One caller (`deleteSession`). Returns `Boolean` to its caller — `CancellationException` propagates safely through the coroutine scope.
- **`OpenCodeClient.close()` becomes meaningful:** Currently a no-op. After the change, it closes the `HttpClient`. Callers that previously called `close()` without effect (e.g., `ProcessManager.disconnect()`) now actually close the HTTP client. This is the intended behavior, but any code that calls `close()` and then attempts to use the client will fail. Verify that no code path uses `OpenCodeClient` after `disconnect()`.

---

## 10. Open Questions — Resolved

All open questions have been decided. This section is retained for traceability.

1. **~~Should `abortSession` use a LONG profile?~~** **Decision: No, keep SHORT.** The source of truth is the server, not the client. If the server can't abort within 60s, that's a server-side problem — the client should not wait longer. If the abort POST times out, the user sees the cancel button fail, which is the correct signal that the server is unresponsive.

2. **~~Should the `LONG` profile's `+30s` buffer be configurable?~~** **Decision: Yes, make all magic numbers configurable.** `LONG_BUFFER_MS` becomes a setting in `OpenCodeSettingsState` (e.g., `longTimeoutBufferSeconds`, default 30). This allows users to tune the buffer for their workload without code changes.

3. **~~Should `healthCheck()` propagate `CancellationException`?~~** **Decision: Yes, propagate.** Errors should not be silently swallowed. At the very least, an error needs to be displayed in the chat UI. `healthCheck()` must add `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)`, consistent with the `postSuccess`/`deleteSuccess` fix.

4. **~~Should `connectTimeoutMillis` vary by profile?~~** **Decision: No, 10s is fine for all requests.** The question used "profile" to mean the timeout tier (SHORT/LONG/INFINITE). `connectTimeoutMillis` controls only the TCP handshake time (how long to wait for the server to accept the connection), not how long the server takes to process the request. 10s is sufficient for TCP handshake regardless of whether the server will take 1s or 10min to process the request. Ktor does not support per-request `connectTimeoutMillis` overrides anyway — it's client-wide only.

5. **~~Should `sseReconnectJob` be cancelled in `disconnect()`?~~** **Decision: Yes.** `disconnect()` must cancel `sseReconnectJob` in addition to `processWatcherJob`. This eliminates the gap where `shutdown()` → `disconnect()` leaves the reconnection coroutine running against a closed client.

---

## 11. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `executeCommand` takes longer than `responseTimeoutSeconds + longTimeoutBufferSeconds` | User sees command fail with no explanation | The `LONG` profile uses `responseTimeoutSeconds + longTimeoutBufferSeconds`. If this isn't enough, the user can increase either setting. `CommandManager` re-throws `CancellationException` — the coroutine is cancelled cooperatively. A future improvement should catch `TimeoutCancellationException` specifically and surface a user-visible error message in chat (per user requirement: "at the very least an error needs to be displayed in chat"). |
| Changing `OpenCodeClient` constructor breaks existing callers | Compilation error | Only two callers: `ProcessManager.initialize()` and `Main.kt`. Both are updated in the same change. |
| `postSuccess`/`deleteSuccess` propagation change causes uncaught `CancellationException` | Coroutine cancellation (not a crash) | All callers are in coroutine contexts where `CancellationException` propagates safely via cooperative cancellation. `OpenCodeService.cancel()` has no explicit try/catch for `abortSession`, but the coroutine scope handles it. See §7.9. |
| `OpenCodeClient.close()` becomes meaningful — code uses client after close | `IllegalStateException` on HTTP calls after close | Verify no code path uses `OpenCodeClient` after `disconnect()`. The `ProcessManager.client` property returns `null` after disconnect, so callers that check for null are safe. |
| SSE close ordering — `disconnect()` called while SSE is active | SSE flow fails with "client is closed" | `OpenCodeService.stopConnection()` cancels all SSE jobs before calling `disconnect()`. `dispose()` now cancels `sseReconnectJob` before calling `shutdown()` (see §7.12 fix). Documented in §7.7. |
| `ProcessManager` rename misses a reference | Compilation error | Full codebase search for `OpenCodeConnectionManager` before and after. IDE refactoring tool can automate this. Only `OpenCodeService.kt` references the class directly. |
| `Main.kt` standalone path breaks | ACP server mode fails to start | The standalone path is rarely used (development/testing only). The fix is trivial — remove the `httpClient` parameter. |
| Dual `Json` instances accidentally merged | Polymorphic deserialization breaks in `postJson`/`getJson` | Add a comment in `OpenCodeClient` warning against merging the two `Json` configurations. The instance-level `Json` has `classDiscriminator = "type"`; the ContentNegotiation `Json` does not. |

---

## 12. Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-06-10 | Orchestrator | Initial draft |
| 2026-06-10 | Orchestrator | Revised: HttpClient ownership moved to OpenCodeClient, ConnectionManager renamed to ProcessManager, compactSession identified as dead code, postSuccess swallowing fixed, endpoint counts corrected, implementation plan added |
| 2026-06-10 | Orchestrator | Review fixes: added deleteSuccess swallowing fix (§4.5.1), dual Json instance note (§4.3), SSE/HttpClient close ordering contract (§4.6, §7.7), corrected "already imports OpenCodeSettingsState" claim (§5), corrected "all callers handle CancellationException" (§9.2), added CommandManager timeout swallowing (§7.8), cancel() no try/catch (§7.9), healthCheck swallowing (§7.10), HttpClient construction failure (§7.11), reconnection lifecycle (§7.12), ACP path beneficiary (§7.13), timeout decision tree (§7.14), removed ChatToolWindowFactory from Step 5, added imports step, added migration checklist (Step 8), added open questions 3-5, expanded risks table, added current timeout map diagram (§2.2), added connectTimeoutMillis/LONG_BUFFER_MS/responseTimeoutSeconds notes (§4.9), added SSE endpoint path note (§4.8), separated compactSession dead-code from live risks (§2.3), added PermissionManager catch-all note (§7.6) |
| 2026-06-10 | Orchestrator | Critical review fixes: fixed §2.2 endpoint inventory (rewritten with all 23 call sites, helper breakdown, timeout map), fixed §10.4 connectTimeoutMillis claim (cannot be overridden per-request, contradicted §4.9), fixed §7.7/§7.12 SSE close ordering (distinguished stopConnection() vs shutdown()→disconnect() paths, sseReconnectJob gap), strengthened §7.10 healthCheck recommendation (CancellationException breaks cooperative cancellation), added §7.16 ContentNegotiation plugin analysis (installed but unused), added §7.17 close() in-flight request behavior (self-enforcing via CancellationException propagation), clarified CancellationException vs TimeoutCancellationException (§4.5 — TimeoutCancellationException extends CancellationException), added §9.2 impact summary (only abortSession is live code) |
| 2026-06-10 | Orchestrator | Open questions resolved: (1) abortSession stays SHORT — server is source of truth, (2) longTimeoutBufferSeconds made configurable in settings, (3) healthCheck propagates CancellationException — errors must not be silently swallowed, (4) connectTimeoutMillis stays 10s client-wide — "profile" means timeout tier, not a Ktor concept, (5) disconnect() now cancels sseReconnectJob — gap fixed. Added Steps 8-9 to implementation plan. Updated scope, §7.7, §7.12, §4.2, §4.9, risks table, migration checklist. |
