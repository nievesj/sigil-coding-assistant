# Technical Design Document: OpenCodeService Decomposition

> **Status:** Draft (post-council-review)
> **Last Updated:** 2026-07-15
> **Related docs:** `docs/tdd/Done/session-manager.md`, `docs/tdd/Done/sse-timeout-fix.md`, `AGENTS.md` (SSE reconnection, attachment validation sections)

---

## 1. TL;DR

`OpenCodeService.kt` is a 1303-line IntelliJ project-level service that acts as a coordinator but has accumulated three subsystems with real logic: SSE connection lifecycle (~270 lines), attachment security validation (~120 lines), and response timeout monitoring (~80 lines). This TDD extracts these into three new classes — `SseConnectionManager`, `AttachmentValidator`, and `ResponseTimeoutMonitor` — leaving `OpenCodeService` as a coordinator (~650–750 lines) that wires collaborators together and delegates calls without owning subsystem logic.

---

## 2. Context & Scope

### 2.1 Current State

`OpenCodeService` is a `@Service(Service.Level.PROJECT)` class that owns the OpenCode connection, session management, and message sending. It already delegates to five collaborators:

| Collaborator | Responsibility |
|---|---|
| `ProcessManager` | Server process lifecycle, connection state, HTTP client |
| `SessionManager` | Per-session state, SSE event routing, session lifecycle |
| `CommandManager` | Slash command fetching and execution |
| `PermissionManager` | Permission/question response handling |
| `ChildPermissionRelay` | Child→parent permission mapping |

Despite this decomposition, three subsystems remain inline in `OpenCodeService`:

1. **SSE connection lifecycle** (lines 477–747, ~270 lines): Global SSE subscription, exponential-backoff reconnection with jitter, circuit breaker, periodic health-check probes, background session recovery after reconnection. Owns 5 `@Volatile` fields + 1 `AtomicLong` + 3 `Job?` references. This is a full subsystem masquerading as private methods.

2. **Attachment security validation** (lines 833–948, ~115 lines inside `sendMessageInternal`): CWE-22 path traversal guard, sensitive-path denylist, TOCTOU symlink-swap detection, image size limits, base64 data-URI encoding, MIME normalization. This is security-critical logic buried inside a 310-line method.

3. **Response timeout monitoring** (lines 967–1046, ~80 lines inside `sendMessageInternal`): Activity-aware timeout with running-tool guard, tool-stuck ceiling detection, session eviction safety, mid-response timeout reconfiguration. Inline as a coroutine launched inside `sendMessageInternal`.

### 2.2 Problem Statement

`OpenCodeService` is a borderline god class. While its coordinator role is legitimate (IntelliJ project services are natural hubs), the three inline subsystems make it hard to test, hard to reason about, and hard to modify. The `sendMessageInternal` method alone is 310 lines, mixing security validation, encoding, timeout monitoring, and error mapping. SSE reconnection logic is the most complex code in the class and has no test coverage because it's inaccessible as private methods.

---

## 3. Goals & Non-Goals

### Goals

1. **Extract `SseConnectionManager`** — Move all SSE subscription, reconnection, health-check, and background-recovery logic into a dedicated class. Target: ~270 lines extracted.
2. **Extract `AttachmentValidator`** — Move all file attachment validation (traversal, denylist, TOCTOU, size, encoding) into a dedicated class. Target: ~120 lines extracted.
3. **Extract `ResponseTimeoutMonitor`** — Move the activity-aware timeout + tool-stuck detection into a dedicated class. Target: ~80 lines extracted.
4. **Reduce `OpenCodeService` to ~650–750 lines** — Pure coordination: wire collaborators, expose state flows, delegate calls. The original ~400-line target requires a future MCP lifecycle extraction (deferred — see Non-Goals).
5. **Zero behavioral changes** — The refactored code must produce identical observable behavior. SSE reconnection timing, attachment rejection rules, and timeout thresholds must not change.
6. **Enable unit testing** — The three extracted classes should be independently testable without spinning up the full service.

### Non-Goals

- **Extracting MCP lifecycle orchestration** (~180 lines, lines 244–426) — Already delegates to `McpManager`; the remaining orchestration is wiring logic that's natural for the coordinator. Can be revisited if it grows. Extracting this is required to reach the original ~400-line target.
- **Extracting global signal routing** (~40 lines, lines 141–178) — Too small to justify a separate class. Stays in `OpenCodeService`.
- **Changing the public API of `OpenCodeService`** — All existing callers (`ChatViewModel`, settings panels, tool window factory) must compile without changes.
- **Changing SSE wire-format parsing** — That lives in `OpenCodeClient.subscribeGlobalEvents()` and is out of scope.
- **Changing SSE event routing** — That lives in `SessionManager.processEvent()` and is out of scope.

---

## 4. Proposed Solution

**Extract three new classes from `OpenCodeService`, each owning a single subsystem with clear boundaries.** `OpenCodeService` becomes a pure coordinator that constructs the new classes, wires their dependencies, and delegates. The key architectural choice is that each extracted class receives its dependencies via constructor injection (coroutine scope, client provider, callbacks, settings providers) — no service-locator lookups, no circular references. This is the right approach because it preserves testability (each class can be constructed with fakes in isolation), keeps `OpenCodeService` free of subsystem state, and avoids the hidden coupling that service-locator lookups introduce.

### 4.1 API / Interface Design

No external API changes. All three new classes are `internal` to the `com.opencode.acp.chat.service` package. `OpenCodeService`'s public surface remains identical.

The new classes expose internal interfaces consumed only by `OpenCodeService`:

| Class | Key Methods | Called By |
|---|---|---|
| `SseConnectionManager` | `start()`, `stop()`, `triggerReconnect()` | `OpenCodeService.initialize()`, `stopConnection()`, `dispose()` |
| `AttachmentValidator` | `validateAndEncode(files): AttachmentValidationResult` | `OpenCodeService.sendMessageInternal()` |
| `ResponseTimeoutMonitor` | `startMonitoring(onTimeout, onToolStuck): Job` | `OpenCodeService.sendMessageInternal()` |
| `SessionManager` (existing) | `recoverBackgroundSessions(client)` (NEW method) | `OpenCodeService` (via `onReconnectSuccess` callback after `SseConnectionManager.triggerReconnect()` succeeds) |

### 4.2 Technology Stack

| Layer | Technology | Version | Rationale |
|---|---|---|---|
| Language | Kotlin | 2.x | Project standard |
| Coroutines | kotlinx.coroutines | bundled with IntelliJ Platform 2026.1 | Existing dependency |
| Logging | kotlin-logging (oshai) | existing | Project convention — `logger.info { "[ACP] ..." }` |
| Testing | JUnit 5 + kotlinx-coroutines-test | existing | Project standard |

### 4.3 Implementation Blueprint

> Blueprint code is illustrative, not authoritative. Developers must validate types against the real compiler.

#### 4.3.1 Data Models & Schemas

No new persistent data models. The extracted classes use existing types (`SseEvent`, `AttachedFile`, `OpenCodePart`, `SessionState`, `ChatMessage`). One new result type for attachment validation:

```kotlin
package com.opencode.acp.chat.service

/**
 * Result of validating and encoding file attachments.
 * [parts] contains the wire-format parts to send to the server.
 * [rejectedFiles] contains filenames that were skipped, with reasons for logging.
 */
internal data class AttachmentValidationResult(
    val parts: List<com.opencode.acp.adapter.OpenCodePart>,
    val acceptedFileNames: List<String>,
    val rejectedFiles: List<RejectedAttachment>,
)

internal data class RejectedAttachment(
    val name: String,
    val path: String,
    val reason: String,
)
```

#### 4.3.2 Class & Interface Definitions

##### A. `SseConnectionManager`

Owns the global SSE subscription lifecycle: connection, reconnection with exponential backoff, health-check probes, and debug event logging. Background session recovery, todo refresh, and context recomputation are delegated to `OpenCodeService` via the `onReconnectSuccess` callback (see §10 Q5 resolution).

```kotlin
package com.opencode.acp.chat.service

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.processor.SessionManager
import kotlinx.coroutines.*

/**
 * Owns the global SSE subscription lifecycle.
 *
 * - Subscribes to [OpenCodeClient.subscribeGlobalEvents] and routes events to [SessionManager.processEvent]
 * - Automatic reconnection with exponential backoff + jitter on stream end
 * - Periodic health-check probes when the connection is silent (Java HTTP has no socket idle timeout)
 * - Circuit breaker: transitions to ERROR after [MAX_RECONNECT_ATTEMPTS] failed attempts
 * - Debug event-summary logging (the `when` block from handleSseEvent moves here — no onEvent callback)
 *
 * @param scope Coroutine scope for launching SSE/health-check/reconnect jobs
 * @param clientProvider Returns the current OpenCodeClient (null if not connected)
 * @param sessionManager Routes SSE events to SessionManager.processEvent
 * @param onConnectionError Called when the circuit breaker trips (transitions ProcessManager to ERROR)
 * @param onReconnectSuccess Called after reconnection succeeds — OpenCodeService uses this to invoke
 *   sessionManager.recoverBackgroundSessions(client), sessionManager.fetchTodos(), and
 *   sessionManager.computeSessionContext(). This keeps SseConnectionManager focused on transport
 *   and avoids coupling it to SessionState recovery internals.
 */
internal class SseConnectionManager(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val sessionManager: SessionManager,
    private val onConnectionError: () -> Unit,
    private val onReconnectSuccess: () -> Unit,
) {
    /** Start the SSE subscription + health check. Idempotent — cancels previous jobs first. */
    fun start()

    /** Stop all SSE activity: subscription, reconnection loop, health checks.
     *  Must be non-blocking (EDT-safe): cancels jobs only, never calls join().
     *  Must be idempotent: safe to call from both stopConnection() and dispose(). */
    fun stop()

    /** Manually trigger reconnection (e.g., after health check detects a dead connection). */
    fun triggerReconnect()

    /** Whether the SSE subscription is currently active. */
    val isActive: Boolean

    companion object {
        // Constants moved from OpenCodeService.companion
        const val MAX_BACKOFF_SHIFT = 10
        const val MAX_RECONNECT_ATTEMPTS = 50
    }
}
```

**What moves here:**
- `startGlobalSseSubscription()` → `start()`
- `launchSseJob()` → private method
- `launchHealthCheck()` → private method
- `triggerGlobalSseReconnect()` → `triggerReconnect()` + private reconnect loop
- `handleSseEvent()` → private method that logs the debug summary AND calls `sessionManager.processEvent(event)` directly (no `onEvent` callback — the debug `when` block moves into the class)
- Fields: `sseJob`, `sseReconnectJob`, `sseReconnectAttempt`, `sseLastEventTimeMs`, `sseHealthCheckJob`
- Constants: `MAX_BACKOFF_SHIFT`, `MAX_RECONNECT_ATTEMPTS`
- `ChatConstants` references (`RECONNECT_DELAY_MS`, `RECONNECT_MAX_DELAY_MS`, `SSE_HEALTH_CHECK_INTERVAL_MS`, `SSE_HEALTH_CHECK_TIMEOUT_MS`) stay in `ChatConstants` — referenced, not moved
- **Concurrency invariant:** All 5 migrated fields (`sseJob`, `sseReconnectJob`, `sseReconnectAttempt`, `sseLastEventTimeMs`, `sseHealthCheckJob`) must retain their `@Volatile` / `AtomicLong` annotations inside `SseConnectionManager`. These are read by `stop()` on the EDT and written by coroutines on `Dispatchers.Default` — the `@Volatile` annotations provide cross-thread visibility.

**What stays in `OpenCodeService`:**
- The call to `sseConnectionManager.start()` in `initialize()`
- The call to `sseConnectionManager.stop()` in `stopConnection()`
- Construction of `SseConnectionManager` with the `onConnectionError` callback that calls `connectionManager.setConnectionError(ServerUnreachable)`, and the `onReconnectSuccess` callback that calls `sessionManager.recoverBackgroundSessions(client)`, `sessionManager.fetchTodos()`, and `sessionManager.computeSessionContext()` — all three post-reconnect calls stay in `OpenCodeService` (the callback fires after `SseConnectionManager.triggerReconnect()` succeeds; `recoverBackgroundSessions` moves to `SessionManager` per §10 Q5 resolution)

##### B. `AttachmentValidator`

Owns file attachment security validation and encoding. Stateless (all methods are pure functions of input). Receives path strings instead of `Project` for testability.

```kotlin
package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.adapter.OpenCodePart

/**
 * Validates and encodes file attachments for sending to the OpenCode server.
 *
 * Security checks:
 * - CWE-22 path traversal: rejects paths escaping project/user-home/attachments directories
 * - Sensitive-path denylist: rejects .env, .git, .idea, node_modules, build/target/out (root-level)
 * - TOCTOU symlink-swap: re-verify canonical path after reading image bytes
 * - Size limit: rejects images > [MAX_ATTACHMENT_SIZE_BYTES]
 *
 * Encoding:
 * - Images: base64 data-URI (server requires data: URIs, not file://)
 * - Other files: canonical-path file:// URL with normalized MIME
 *
 * @param projectBasePath IDE project root directory, CANONICALIZED (for path-boundary checks).
 *   The caller MUST pass `java.io.File(project.basePath).canonicalPath` — the `startsWith`
 *   boundary check depends on canonical form to handle symlinked project roots.
 * @param userHomePath User home directory, CANONICALIZED (for attachment directory checks).
 *   The caller MUST pass `java.io.File(System.getProperty("user.home")).canonicalPath`.
 */
internal class AttachmentValidator(
    private val projectBasePath: String?,
    private val userHomePath: String?,
) {

    /**
     * Validate and encode a list of attached files into wire-format parts.
     *
     * @param files User-attached files (path, name, mime)
     * @return Validation result with accepted parts + rejected file names (for logging)
     */
    fun validateAndEncode(files: List<AttachedFile>): AttachmentValidationResult

    companion object {
        const val MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024  // 10MB

        // Denylist segments — moved here from inline setOf() in sendMessageInternal
        val DENYLIST_SEGMENTS = setOf(
            ".env", ".env.local", ".env.production",
            ".git", ".hg", ".svn",
            ".idea",
            "node_modules",
        )
        val ROOT_ONLY_DENYLIST = setOf("build", "target", "out")
    }
}
```

**What moves here:**
- The entire `files.forEach { file -> ... }` block in `sendMessageInternal` (lines 833–948)
- `MAX_ATTACHMENT_SIZE_BYTES` constant
- `DENYLIST_SEGMENTS` and `ROOT_ONLY_DENYLIST` sets
- Path traversal guard logic (canonical path, `isInsideProject`, `isInsideProjectAttachments`, `isInsideUserHomeAttachments`)
- Denylist segment matching logic
- Image data-URI encoding (base64 + TOCTOU re-verify)
- Non-image file:// URL generation + MIME normalization
- `pathToFileUrl()` and `normalizeAttachmentMime()` calls (already exist in `util/`)
- Note: `project.basePath` and `System.getProperty("user.home")` extraction AND CANONICALIZATION happens at the `OpenCodeService` construction site, not inside the validator. The validator receives already-canonical paths and uses them directly in `startsWith` checks.

**What stays in `OpenCodeService.sendMessageInternal()`:**
- The call to `attachmentValidator.validateAndEncode(files)` replacing the inline block
- The summary log line that uses `result.acceptedFileNames`

##### C. `ResponseTimeoutMonitor`

Owns the activity-aware response timeout and tool-stuck detection. Launched per-send, cancelled when the response completes. Receives settings via injected providers (not static singleton access) and re-fetches the active session each iteration (not a captured reference).

```kotlin
package com.opencode.acp.chat.service

import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.SessionState
import kotlinx.coroutines.*

/**
 * Activity-aware response timeout monitor.
 *
 * Launched per sendMessage() call. Periodically checks:
 * 1. If tools are running (InProgress/Pending): skip activity timeout, but enforce
 *    a hard tool-stuck ceiling based on tool start time (not lastActivityTimeMs).
 * 2. If no tools running: check elapsed time since last SSE activity.
 *    If > responseTimeoutSeconds, abort streaming.
 *
 * The timeout is re-read each iteration from the injected providers — changes take
 * effect mid-response. Both providers' return values are clamped internally:
 * - `responseTimeoutSeconds` clamped to [10, 3600]
 * - `toolStuckTimeoutSeconds` clamped to [60, 3600]
 * This matches the current inline behavior (OpenCodeService.kt:970, 1010).
 *
 * Session eviction safety: the monitor re-fetches `sessionManager.getActiveSession()`
 * on EACH iteration (NOT a captured reference). If the session was evicted from the
 * cache (e.g., LRU eviction during a long send), `getActiveSession()` returns null
 * and the monitor skips that iteration. This matches the current inline behavior
 * (OpenCodeService.kt:978-984) and handles both session switching and LRU eviction.
 *
 * @param scope Coroutine scope for the monitor job
 * @param sessionManager Provides session lookup (re-fetched each iteration for eviction safety)
 * @param responseTimeoutSecondsProvider Returns the configured response timeout (seconds, raw — clamped internally)
 * @param toolStuckTimeoutSecondsProvider Returns the configured tool-stuck ceiling (seconds, raw — clamped internally)
 */
internal class ResponseTimeoutMonitor(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val responseTimeoutSecondsProvider: () -> Int,
    private val toolStuckTimeoutSecondsProvider: () -> Int,
) {
    /**
     * Start monitoring for timeout/stuck-tool conditions on the currently active session.
     *
     * The monitor re-fetches `sessionManager.getActiveSession()` on each iteration to
     * handle session eviction gracefully. If the active session is null (evicted or
     * switched), the monitor skips that iteration — no false-positive timeout.
     *
     * @param onTimeout Called with an error message when the response times out (no SSE activity).
     * @param onToolStuck Called with an error message when a tool is stuck beyond the ceiling.
     * @return The monitor Job — caller must cancel it when the response completes.
     */
    fun startMonitoring(
        onTimeout: (String) -> Unit,
        onToolStuck: (String) -> Unit,
    ): Job

    companion object {
        const val ACTIVITY_CHECK_INTERVAL_MS = 5_000L

        // Clamping bounds — match current inline behavior (OpenCodeService.kt:970, 1010)
        const val RESPONSE_TIMEOUT_MIN_SECONDS = 10
        const val RESPONSE_TIMEOUT_MAX_SECONDS = 3600
        const val TOOL_STUCK_TIMEOUT_MIN_SECONDS = 60
        const val TOOL_STUCK_TIMEOUT_MAX_SECONDS = 3600
    }
}
```

**What moves here:**
- The `activityMonitorJob = scope.launch { ... }` block in `sendMessageInternal` (lines 967–1046)
- `ACTIVITY_CHECK_INTERVAL_MS` constant
- The running-tool guard logic (snapshot tool state, check InProgress/Pending)
- The tool-stuck ceiling logic (oldest tool start time vs `toolStuckTimeoutSeconds`)
- The activity timeout logic (`lastActivityTimeMs` vs `responseTimeoutSeconds`)
- Settings access via injected providers (`responseTimeoutSecondsProvider`, `toolStuckTimeoutSecondsProvider`) instead of `OpenCodeSettingsState.getInstance()` static access — enables unit testing without IntelliJ platform. **Both provider values are clamped inside `ResponseTimeoutMonitor`** (`responseTimeoutSeconds.coerceIn(10, 3600)`, `toolStuckTimeoutSeconds.coerceIn(60, 3600)`) — matching the current inline behavior (OpenCodeService.kt:970, 1010).
- Session eviction safety: re-fetches `sessionManager.getActiveSession()` on EACH iteration (NOT a captured session reference). If the session was evicted from the cache (e.g., LRU eviction during a long send), `getActiveSession()` returns null and the monitor skips that iteration. This matches the current inline behavior (OpenCodeService.kt:978-984) and handles both session switching and LRU eviction.

**What stays in `OpenCodeService.sendMessageInternal()`:**
- The call to `timeoutMonitor.startMonitoring(onTimeout = { ... }, onToolStuck = { ... })` — no `session` parameter (the monitor re-fetches internally)
- The `finally { monitorJob.cancel() }` cleanup
- The `deferred.await()` that blocks until streaming completes

##### D. `OpenCodeService` (after refactoring)

```kotlin
@Service(Service.Level.PROJECT)
class OpenCodeService(private val project: Project) : Disposable {

    // Sub-components (unchanged)
    val connectionManager = ProcessManager(scope).apply { onMcpReset = { resetMcpOnServerRestart() } }
    val sessionManager = SessionManager(scope, project).also { errorSurfacer = { msg -> it.emitGlobalError(msg) } }
    val commandManager = CommandManager(...)
    val permissionManager = PermissionManager(...)
    val childPermissionRelay = ChildPermissionRelay(sessionManager)

    // NEW: extracted subsystems
    private val sseConnectionManager = SseConnectionManager(
        scope = scope,
        clientProvider = { connectionManager.client },
        sessionManager = sessionManager,
        onConnectionError = { connectionManager.setConnectionError(ConnectionErrorReason.ServerUnreachable) },
        onReconnectSuccess = {
            // All three post-reconnect calls stay in OpenCodeService — SseConnectionManager
            // signals success via this callback and stays focused on transport.
            sessionManager.recoverBackgroundSessions(connectionManager.client)
            sessionManager.fetchTodos()
            sessionManager.computeSessionContext()
        },
    )
    private val attachmentValidator = AttachmentValidator(
        // MUST canonicalize — the startsWith boundary check depends on canonical form.
        // Without this, symlinked project roots (e.g., /Users/dev/proj -> /data/proj)
        // would cause all legitimate files to be rejected.
        projectBasePath = project.basePath?.let { java.io.File(it).canonicalPath },
        userHomePath = System.getProperty("user.home")?.let { java.io.File(it).canonicalPath },
    )
    private val responseTimeoutMonitor = ResponseTimeoutMonitor(
        scope = scope,
        sessionManager = sessionManager,
        responseTimeoutSecondsProvider = { OpenCodeSettingsState.getInstance().state.responseTimeoutSeconds },
        toolStuckTimeoutSecondsProvider = { OpenCodeSettingsState.getInstance().state.toolStuckTimeoutSeconds },
    )

    // State flows (unchanged — 16 pass-through delegates)
    val messages = sessionManager.activeMessages
    // ... etc

    // initialize() — unchanged except: startGlobalSseSubscription() -> sseConnectionManager.start()
    // stopConnection() — unchanged except: SSE job cancellation -> sseConnectionManager.stop()
    // sendMessageInternal() — ~150 lines instead of ~310 (validation + timeout delegated)
    // dispose() — unchanged except: SSE cleanup -> sseConnectionManager.stop()

    // MCP lifecycle (lines 244-426) — STAYS HERE (already delegates to McpManager)
    // Global signal routing (lines 141-178) — STAYS HERE (too small to extract)
    // Session management delegates (lines 430-449) — STAYS HERE (thin delegates)
    // Data fetching delegates (lines 1209-1257) — STAYS HERE (thin delegates)
}
```

**Estimated line count after refactoring:**
- Sub-component construction + wiring: ~50 lines (increased: 3 new class constructions with provider lambdas)
- State flow pass-throughs: ~20 lines
- `initialize()`: ~50 lines (unchanged except 1 line)
- MCP lifecycle: ~180 lines (stays)
- Global signal routing: ~40 lines (stays)
- `sendMessageInternal()`: ~150 lines (down from ~310 — validation and timeout delegated, but orchestration remains: user message creation, assistant message creation, deferred wiring, send, await, error handling, abort logic)
- `sendMessage()` mutex wrapper: ~15 lines (unchanged)
- Session/data delegates: ~40 lines (unchanged)
- Actions (cancel, steerCancel, injectLocalMessage): ~80 lines (unchanged)
- `dispose()`: ~15 lines (unchanged)
- Companion object: ~5 lines (constants moved to extracted classes)
- **Total: ~645 lines** (down from 1303)

> Note: The original ~400-line goal is unattainable without also extracting MCP lifecycle orchestration (~180 lines), which is deferred as a non-goal. The ~645 estimate accounts for wiring code that stays in `OpenCodeService` (delegation calls, result handling, `try/finally` blocks) and new wiring added for the extracted classes. The three extractions remove ~470 lines of inline logic but add ~35 lines of construction and delegation wiring.

---

## 5. Assumptions & Dependencies

**Assumptions:**
- The SSE reconnection logic, attachment validation rules, and timeout thresholds are correct as currently implemented — this is a pure extraction, not a bug-fix.
- `OpenCodeClient.subscribeGlobalEvents()` signature does not change.
- `SessionManager.processEvent()` signature does not change.
- `SessionState`'s public API (`responseDeferred`, `lastActivityTimeMs`, `snapshotToolState`, `isStreaming`, `errorMessage`, `activeMessageId`, `completeStreaming`) does not change.
- The `sendMutex` in `OpenCodeService` continues to serialize `sendMessage()` calls — the `ResponseTimeoutMonitor` is launched within the mutex-held scope and cancelled before the mutex releases.
- `SseConnectionManager.stop()` is non-blocking (EDT-safe): cancels jobs only, never calls `join()`. This preserves the current behavior where `stopConnection()` is called from UI buttons on the EDT.
- None of the three new classes implement `Disposable` — they are plain classes owned by `OpenCodeService`, which orchestrates cleanup via `stop()` + `scope.cancel()` in `dispose()`.

**Dependencies:**
- `ChatConstants.kt` — SSE/health-check constants (referenced by `SseConnectionManager`, not moved)
- `MimeTypes.kt` — MIME type guessing (referenced by `AttachmentValidator`, not moved)
- `pathToFileUrl()` in `util/` — URL conversion (referenced by `AttachmentValidator`, not moved)
- `normalizeAttachmentMime()` in `util/` — MIME normalization (referenced by `AttachmentValidator`, not moved)
- `OpenCodeSettingsState` — `responseTimeoutSeconds`, `toolStuckTimeoutSeconds` (read by `OpenCodeService` and passed to `ResponseTimeoutMonitor` via provider lambdas)
- `SessionManager.recoverBackgroundSessions(client: OpenCodeClient?)` — new method on `SessionManager` (extracted from `OpenCodeService.recoverBackgroundSessions()`). The `client` parameter is passed by `OpenCodeService` from `connectionManager.client` inside the `onReconnectSuccess` callback. `SessionManager` does NOT need a `clientProvider` constructor dependency — the client is passed as a method parameter at call time, keeping `SessionManager` decoupled from `ProcessManager`/`OpenCodeClient`.

---

## 6. Alternatives Considered

**Alternative: Move SSE lifecycle into `ProcessManager`**
- *What it is:* Merge SSE subscription/reconnection into the existing `ProcessManager` class, since it already owns connection state and the HTTP client.
- *Why plausible:* `ProcessManager` already owns `connectionState` and `setConnectionError()` — the SSE circuit breaker already calls into it. Co-locating connection + SSE would reduce the number of classes.
- *Why rejected:* `ProcessManager` owns process lifecycle (launch, kill, port discovery) — a different concern from SSE stream management (subscribe, reconnect, health-check, recover sessions). Merging would create a new god class (~800 lines) and mix two distinct responsibilities. The SSE logic needs `SessionManager` for event routing and recovery, while `ProcessManager` deliberately does not depend on `SessionManager` (avoiding a circular dependency). A separate `SseConnectionManager` that depends on `SessionManager` only through a narrow interface (event routing + recovery) keeps the dependency graph acyclic and the responsibilities clean.

**Alternative: Extract only `SseConnectionManager`, leave attachment validation and timeout inline**
- *What it is:* Only extract the SSE subsystem (the largest block), leave the other two as private methods in `OpenCodeService`.
- *Why plausible:* SSE is the most complex subsystem and the highest-value extraction. Attachment validation and timeout monitoring are smaller and less likely to need independent testing.
- *Why rejected:* Attachment validation is security-critical logic (CWE-22, denylist, TOCTOU) that deserves isolated unit tests. Leaving it inline in a 310-line method means it can only be tested through a full `sendMessage()` integration test. The timeout monitor has enough conditional logic (running-tool guard, tool-stuck ceiling, eviction safety) to benefit from isolated testing. Extracting all three achieves the coordinator-size goal; extracting one leaves `sendMessageInternal` at ~200 lines with security and timeout logic still entangled in the send path.

**Alternative: Extract attachment validation into a top-level function instead of a class**
- *What it is:* Make `validateAndEncode(files, project)` a free function in `util/` rather than a class.
- *Why plausible:* The validation is stateless — it doesn't need instance state beyond `project`, which could be a parameter.
- *Why rejected:* The denylist sets and size limit are configuration that belongs with the logic. A class gives them a home and makes them testable as a unit. A free function in `util/` would be less discoverable and would scatter the security constants. The class also matches the existing pattern (`McpConfigWriter`, `McpToolDiscovery` are classes with stateless-ish methods).

---

## 7. Cross-Cutting Concerns

### 7.1 Security

The `AttachmentValidator` extraction must preserve all existing security guarantees:
- CWE-22 path traversal: `canonicalPath.startsWith(projectBase + File.separator)` boundary check
- Denylist: `.env`, `.git`, `.idea`, `node_modules`, root-level `build`/`target`/`out`
- TOCTOU: canonical path re-verification after `readBytes()`
- Size limit: 10MB for images
- Symlink exfiltration: denylist applies to ALL paths regardless of location

**Risk:** Moving security logic introduces the risk of accidentally weakening a check during extraction. **Mitigation:** The extraction must be a verbatim move of the validation logic, not a rewrite. Unit tests for `AttachmentValidator` should cover each rejection path (traversal, denylist, TOCTOU, size, blank path, unreadable file) before the extraction is merged.

**Additional test coverage required:**
- **Symlink-to-`.env` attack:** A symlink in `.opencode/attachments/` pointing to `project/.env` must be rejected by the denylist even though the canonical path is inside `attachments/`. The code's own comments (line 890–892) call out this attack vector.
- **Windows drive-letter paths:** The `rootSegmentIndex` logic (line 886) has platform-specific behavior. Test `C:\Project\build\file.txt` (root `build` rejected) vs `C:\Project\src\build\file.txt` (non-root `build` allowed).
- **`pathToFileUrl` failure:** `pathToFileUrl` returning null (line 937–940) must produce a rejected file, not a crash.

### 7.2 Reliability & Availability

The `SseConnectionManager` extraction must preserve the existing reconnection behavior:
- Exponential backoff: 1s → 2s → 4s → ... → 30s cap
- ±20% jitter on each delay
- Circuit breaker: 50 attempts → ERROR state
- Health-check probes: 60s silence → GET /global/health → 10s timeout
- Background session recovery: finalize sessions that completed during disconnect, with in-progress tool safety check

**Risk:** The reconnection logic has subtle ordering dependencies (capture old jobs before reassigning, `withTimeoutOrNull` on join to avoid hanging on half-open TCP). **Mitigation:** The extraction must preserve the exact ordering. The `isActive` check after the catch block (distinguishing user-initiated stop from unexpected stream end) must be preserved.

**SSE reconnection ordering invariants (must be preserved verbatim):**

1. **Capture old jobs before reassigning** — `val oldSseJob = sseJob` and `val oldHealthCheckJob = sseHealthCheckJob` MUST be captured BEFORE `sseJob = launchSseJob(...)` and `sseHealthCheckJob = launchHealthCheck(...)`. The current code (lines 620–623) documents a prior bug where `sseJob?.join()` was called after reassignment, joining the NEW job instead of the old one.
2. **`withTimeoutOrNull(5000)` on join** — After cancelling old jobs, use `withTimeoutOrNull(5000) { oldSseJob?.join() }` to avoid hanging on half-open TCP connections (no socket-level timeout per AGENTS.md). If the join times out, the old job becomes a harmless zombie — it's cancelled and the scope reclaims it on dispose.
3. **`isActive` check after catch block** — After the `catch (e: Exception)` block in `launchSseJob`, check `if (isActive)` before triggering reconnection. This distinguishes user-initiated stop (scope/job cancelled → `isActive` = false → skip reconnect) from unexpected stream end (scope active → `isActive` = true → reconnect). Note: `isActive` here is `coroutineContext.isActive` (job-level), not scope-level.
4. **Health-check triggers reconnection explicitly** — When the health check fails, it cancels `sseJob` AND calls `triggerReconnect()` explicitly. The post-catch `isActive` check in `launchSseJob` would NOT fire because the job was cancelled (not ended naturally). Both paths must be preserved.
5. **`@Volatile` / `AtomicLong` preservation** — All 5 migrated fields (`sseJob`, `sseReconnectJob`, `sseReconnectAttempt`, `sseLastEventTimeMs`, `sseHealthCheckJob`) must retain their `@Volatile` / `AtomicLong` annotations inside `SseConnectionManager`. They are read by `stop()` on the EDT and written by coroutines on `Dispatchers.Default`.
6. **`stop()` is non-blocking and idempotent** — `stop()` cancels jobs only (no `join()`), is safe to call from EDT, and is safe to call multiple times (from both `stopConnection()` and `dispose()`).

**`recoverBackgroundSessions` relocation:** This method moves to `SessionManager.recoverBackgroundSessions(client: OpenCodeClient?)` (per council review — it operates on session state, not SSE transport). `OpenCodeService` calls it from the `onReconnectSuccess` callback (alongside `sessionManager.fetchTodos()` and `sessionManager.computeSessionContext()`) after `SseConnectionManager.triggerReconnect()` succeeds. This keeps `SseConnectionManager` focused on transport and avoids coupling it to `SessionState` internals (`completeStreaming`, `responseDeferred`, `OpenCodePart.ToolUse`/`ToolResult`). The `client` is passed as a method parameter from `connectionManager.client` — `SessionManager` does not need a persistent client reference.

### 7.3 Observability

All three extracted classes must use the existing `logger.info { "[ACP] ..." }` convention for operational logs (startup, connection, errors, warnings). The `[ACP]` prefix must be preserved for grep-friendly filtering in `idea.log`. No new logging mechanisms.

**Exception — SSE event debug summary:** The `handleSseEvent` debug summary `when` block that moves into `SseConnectionManager` retains `logger.debug { ... }` (NOT `logger.info`). This fires on EVERY SSE event and would flood `idea.log` at the default INFO log level. It is only visible when the user enables DEBUG logging (Settings → Tools → Sigil → Plugin log level). This matches the current behavior (OpenCodeService.kt:761 uses `logger.debug`).

---

## 8. Testing Strategy

### 8.1 Key Scenarios

**`SseConnectionManager`:**
- SSE stream ends unexpectedly → reconnection triggered with exponential backoff
- SSE stream ends via `stop()` → no reconnection (scope cancelled, `isActive` = false)
- Health check succeeds after silence → timer reset, connection stays open
- Health check fails after silence → SSE job cancelled, reconnection triggered
- Circuit breaker: 50 failed reconnection attempts → `onConnectionError` callback invoked
- Background session recovery: streaming session with last assistant message → finalized
- Background session recovery: streaming session with in-progress tools → NOT finalized (safety check)
- Background session recovery: no streaming sessions → no-op

**`AttachmentValidator`:**
- File inside project → accepted, file:// URL generated
- File inside `.opencode/attachments/` → accepted
- File outside project + outside user-home attachments → rejected (CWE-22)
- File with `.env` segment → rejected (denylist)
- File with `node_modules` segment → rejected (denylist)
- File with root-level `build` segment → rejected (root-only denylist)
- Image > 10MB → rejected (size limit)
- Image with TOCTOU symlink swap (canonical path changes after read) → rejected
- File with blank path (pre-rev2 legacy) → rejected
- Unreadable/deleted file → rejected
- Image → data: URI with base64 encoding
- Non-image → file:// URL with normalized MIME

**`ResponseTimeoutMonitor`:**
- No SSE activity for > `responseTimeoutSeconds` → `onTimeout` called
- Tool running (InProgress) → activity timeout skipped
- Tool stuck for > `toolStuckTimeoutSeconds` → `onToolStuck` called
- Session evicted mid-monitor → no false-positive timeout (graceful skip)
- Settings change mid-response (timeout lowered) → new value takes effect on next iteration

### 8.2 Concurrency Invariant Tests

**`SseConnectionManager`:**
- Reconnect triggered while reconnect is in-flight → no duplicate `sseJob` (old reconnect job cancelled first)
- `stop()` called while `sseReconnectJob` is in `delay()` backoff → delay cancelled, no new `sseJob` launched
- `stop()` called twice (from `stopConnection()` then `dispose()`) → no exception, no reconnection triggered (idempotency)
- `handleSseEvent` debug logging throws → verify the manager logs and triggers reconnect (current behavior: exception propagates into `collect`, caught, stream ends → reconnect)
- Health-check cancels SSE job, then reconnect job must not launch a second SSE job before the old one is joined/cancelled
- Parent scope cancelled mid-`withTimeoutOrNull` join → clean cancellation, no zombie jobs

**`ResponseTimeoutMonitor`:**
- `responseTimeoutSeconds` set to 0 → clamped to 10s internally; timeout fires after 10s of inactivity (NOT immediate — matches current `coerceIn(10, 3600)` behavior)
- `responseTimeoutSeconds` set to 10 (minimum clamp bound) → timeout fires after 10s of inactivity (boundary)
- `toolStuckTimeoutSeconds` set to 0 → clamped to 60s internally; tool-stuck fires after 60s (NOT immediate — matches current `coerceIn(60, 3600)` behavior)
- Monitor started twice for the same response → second call returns a second Job (caller responsible for cancellation)
- `sessionManager.getActiveSession()` returns null (session evicted from cache mid-monitor) → monitor skips iteration gracefully (no false-positive timeout)
- `snapshotToolState()` lock contention with `processEvent` → no deadlock (concurrency stress test: 1000 events + monitor running)

### 8.3 Test Infrastructure

**Mocking strategy:**
- **`OpenCodeClient`** — concrete class, not an interface. Use MockK (or hand-rolled fake) to mock `subscribeGlobalEvents()` (return a `Flow<SseEvent>` that emits controlled events), `healthCheck()` (return `true`/`false`), and `listMessages()` (return controlled message lists). Confirm MockK is in test dependencies; if not, add it or extract an `OpenCodeClientInterface` (out of scope for this TDD).
- **`SessionManager`** — extract a `SessionEventRouter` interface (`processEvent`, `getStreamingSessions`, `recoverBackgroundSessions`, `fetchTodos`, `computeSessionContext`) that `SessionManager` implements. Tests use a fake implementation with controllable session states. Alternatively, use MockK to mock specific methods.
- **`OpenCodeSettingsState`** — static singleton access replaced by injected `() -> Int` providers in `ResponseTimeoutMonitor`. Tests pass lambda suppliers returning fixed values. No mocking needed.
- **`Project`** — `AttachmentValidator` now takes `projectBasePath: String?` and `userHomePath: String?` instead of `Project`. Tests pass string literals. No mocking needed.
- **Coroutine testing** — use `kotlinx-coroutines-test`'s `TestScope` + `advanceTimeBy()` for `ResponseTimeoutMonitor` and `SseConnectionManager` tests. Verify `TestScope` works in the IntelliJ Platform test runner (it does — it's a pure coroutine utility).

**Required fakes:**
- `FakeOpenCodeClient` — exposes a `MutableSharedFlow<SseEvent>` for `subscribeGlobalEvents()`, configurable `healthCheck` result, configurable `listMessages` response
- `FakeSessionManager` or `SessionEventRouter` fake — controllable `getActiveSession()`, `getStreamingSessions()`, `recoverBackgroundSessions`

**Integration smoke test:**
- Full `sendMessage()` → SSE event → `deferred.await()` → `SendMessageResult` flow with a fake `OpenCodeClient`
- Exercises the delegation chain: `OpenCodeService` → `AttachmentValidator` → `SseConnectionManager` → `ResponseTimeoutMonitor`
- Verifies "zero behavioral changes" promise at the integration level

---

## 9. Extraction Order & Rollout Plan

### 9.1 Extraction Order

Extract in three sequential commits, lowest-risk first:

| Step | Class | Risk | Rationale |
|------|-------|------|-----------|
| 1 | `AttachmentValidator` | Low | Pure function, no coroutines, no concurrency. Builds confidence in the extraction pattern. Security tests are highest value. |
| 2 | `ResponseTimeoutMonitor` | Medium | Introduces coroutine extraction but narrow surface (per-send lifecycle). Tests settings-provider injection pattern. |
| 3 | `SseConnectionManager` | High | Most complex: cross-coroutine `@Volatile` fields, reconnection ordering, background recovery. Only after the pattern is proven on the simpler two. |

Each step is a separate commit with tests passing between. If a step introduces a regression, `git revert` that commit without affecting the others.

### 9.2 Rollback Strategy

- **No feature flags** — this is a pure extraction with no behavioral changes. Rollback = `git revert` the extraction commit.
- **Verification** — after each extraction step, run:
  1. The unit tests for the extracted class
  2. A smoke test: plugin starts, send a message with an image attachment, kill the OpenCode process and verify reconnection, lower timeout and verify abort
  3. Full compilation (all callers of `OpenCodeService` must compile without changes)
- **Compile-time verification** — after extracting `SseConnectionManager`, grep `OpenCodeService.kt` for `sseJob`, `sseReconnectJob`, `sseHealthCheckJob`, `sseReconnectAttempt`, `sseLastEventTimeMs` and confirm zero references remain. Any remaining reference indicates an incomplete extraction.

---

## 10. Open Questions

**Resolved (council review 2026-07-15):**

1. **Should `SseConnectionManager` own the `handleSseEvent` debug-logging wrapper?** **RESOLVED: Drop `onEvent` callback; move debug logging into `SseConnectionManager` directly.** The council voted 3–2 to drop the `onEvent` callback. The debug summary `when` block moves into `SseConnectionManager.handleSseEvent()` as a private method that logs the summary AND calls `sessionManager.processEvent(event)`. This eliminates the confusing dual-path design (events routed through both `onEvent` AND `sessionManager.processEvent`), and keeps `SseConnectionManager` as the single owner of the SSE event stream.

2. **Should `ResponseTimeoutMonitor` be a class or a function?** **RESOLVED: Class (unanimous).** Groups the constant (`ACTIVITY_CHECK_INTERVAL_MS`) with the logic, holds injected settings providers, and matches the project's class-based pattern.

3. **Should `AttachmentValidator` be `internal` or `private`?** **RESOLVED: `internal` (unanimous).** Testability is a primary goal. The project already uses `internal` for `SessionManager.getActiveSession()`, `getStreamingSessions()`, etc.

4. **Should the `sendMutex` move into `ResponseTimeoutMonitor`?** **RESOLVED: No — stays in `OpenCodeService` (unanimous).** The mutex serializes the entire `sendMessage()` flow (including attachment validation and `deferred.await()`), not just the timeout monitor.

5. **Should `recoverBackgroundSessions()` be in `SseConnectionManager` or `SessionManager`?** **RESOLVED: Move to `SessionManager` (council vote 3–2).** The method is 70 lines of `SessionState` internals manipulation (`completeStreaming`, `responseDeferred`, `activeMessageId`, `OpenCodePart.ToolUse`/`ToolResult` scanning) with only a thin SSE-reconnection trigger. It is session-state recovery logic, not connection transport. `OpenCodeService` calls `sessionManager.recoverBackgroundSessions(client)` from the `onReconnectSuccess` callback after `SseConnectionManager.triggerReconnect()` succeeds (see §4.3.2.A). This keeps `SseConnectionManager` focused on transport and avoids coupling it to `SessionState` internals.

**New questions identified during council review:**

6. **Should `onConnectionError` be typed as `(ConnectionErrorReason) -> Unit` instead of `() -> Unit`?** **RESOLVED: Keep `() -> Unit`.** `ServerUnreachable` is the only error reason the circuit breaker produces. The callback stays `() -> Unit` and `OpenCodeService` hardcodes `connectionManager.setConnectionError(ServerUnreachable)`. Widen to `(ConnectionErrorReason) -> Unit` only if future error reasons are needed.

7. **Should `AttachmentValidator` inject `projectBasePath: String?` and `userHomePath: String?` instead of `Project`?** **RESOLVED: Yes — inject path strings.** This makes the validator trivially unit-testable without mocking the IntelliJ `Project` interface (~50 methods). The caller (`OpenCodeService`) extracts `project.basePath` and `System.getProperty("user.home")` at construction time. **The caller MUST canonicalize both paths** (`java.io.File(it).canonicalPath`) before passing them — the `startsWith` boundary check depends on canonical form to handle symlinked project roots.

8. **How should post-reconnect work (`recoverBackgroundSessions`, `fetchTodos`, `computeSessionContext`) be triggered after `SseConnectionManager` owns the reconnect loop?** **RESOLVED: `onReconnectSuccess: () -> Unit` callback.** `SseConnectionManager` gets a second constructor callback (`onReconnectSuccess`) alongside `onConnectionError`. When reconnection succeeds, `SseConnectionManager` invokes `onReconnectSuccess`. `OpenCodeService` provides a lambda that calls all three: `sessionManager.recoverBackgroundSessions(connectionManager.client)`, `sessionManager.fetchTodos()`, and `sessionManager.computeSessionContext()`. This keeps `SseConnectionManager` focused on transport (no `SessionManager` recovery coupling) and gives all three post-reconnect calls a single home in `OpenCodeService`.

---

## 11. Document History

| Date | Author | Change |
|---|---|---|
| 2026-07-15 | orchestrator | Initial draft — Mini TDD for extracting SseConnectionManager, AttachmentValidator, ResponseTimeoutMonitor from OpenCodeService |
| 2026-07-15 | orchestrator | Council review revision — dropped `onEvent` callback (Q1), moved `recoverBackgroundSessions` to `SessionManager` (Q5), injected settings providers into `ResponseTimeoutMonitor`, injected path strings into `AttachmentValidator`, added concurrency invariants checklist (§7.2), added test infrastructure plan (§8.3), added extraction order & rollback plan (§9), revised line-count estimate from ~570 to ~645 |
| 2026-07-15 | ai-review | Adversarial review fixes — resolved `recoverBackgroundSessions` caller contradiction via `onReconnectSuccess` callback (Q8); preserved re-fetch + null-check session eviction strategy in `ResponseTimeoutMonitor` (not captured session); specified `coerceIn` clamping bounds; documented `AttachmentValidator` canonicalization requirement; fixed logging convention (`logger.debug` for SSE event summary); completed 6 truncated sentences; renumbered sections sequentially (§4.1-4.3, §7.1-7.3, §8.1-8.3, §11) |