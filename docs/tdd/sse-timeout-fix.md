# Technical Design Document: SSE Timeout During LLM Generation

> **Status:** Draft — Needs Investigation (reviewed, §4.1–4.2 corrected)
> **Author:** Orchestrator
> **Last Updated:** 2026-06-09
> **Related docs:** AGENTS.md (SSE Reconnection section), OpenCodeConnectionManager.kt, OpenCodeService.kt

---

## 1. TL;DR

The `deferred.await()` in `sendMessageInternal()` (`OpenCodeService.kt:416-418`) hangs until the configurable `responseTimeoutSeconds` (default 5 minutes) `withTimeout` fires, producing "Response timed out." The SSE subscription in `subscribeGlobalEvents()` (`OpenCodeClient.kt:546-616`) loses its connection during tool execution, after which no `StreamingCompleted` signal arrives to complete the deferred.

**The originally suspected cause — Ktor's `HttpTimeout.socketTimeoutMillis` (60s) closing the SSE socket — is proven incorrect** (see §4.1–4.2). Ktor's plugin sets the timeout on the SSE request's capability, but the Java engine does not enforce socket-level idle timeouts. The root cause needs further investigation: the SSE connection drops for an unknown reason during extended tool execution.

---

## 2. Context & Scope

### 2.1 Current State

The plugin uses a single shared `HttpClient(Java)` configured in `OpenCodeConnectionManager.kt:100-116`:

```kotlin
// OpenCodeConnectionManager.kt:100-116
val client = HttpClient(Java) {
    install(ContentNegotiation) { json(Json { ... }) }
    install(SSE)
    install(io.ktor.client.plugins.HttpTimeout) {
        requestTimeoutMillis = 60_000    // 60s — total request deadline for REST calls (NOT applied to SSE)
        connectTimeoutMillis = 10_000    // 10s — TCP connection (applied to ALL requests)
        // socketTimeoutMillis is NOT set — it's a no-op on the Java HTTP engine.
        // The Java engine has no socket-level idle timeout API. See §4.2.1.
        // SSE idle detection is handled client-side in OpenCodeService.
    }
}
```

> **Important:** `requestTimeoutMillis` is **not applied to SSE** — Ktor's `HttpTimeout` plugin skips it via the `supportsRequestTimeout` check (§4.1). `socketTimeoutMillis` is intentionally omitted — the Java engine does not enforce it (§4.2). Only `connectTimeoutMillis` and `requestTimeoutMillis` (for REST) are effectively enforced.

This client is used for:
- **SSE subscription** (`GET /event`) — long-lived, server may go quiet during tool execution
- **REST calls** (`POST /session/:id/message`, `GET /session/:id`, etc.) — short-lived

The SSE subscription (`OpenCodeClient.kt:546-616`) uses `httpClient.sse("$baseUrl/event")` with no additional configuration. This calls the `sse(urlString, ...)` overload where the trailing lambda is `block: suspend ClientSSESession.() -> Unit` (NOT `HttpRequestBuilder.() -> Unit`).

### 2.2 Problem Statement

During tool execution (bash commands, file edits, browser actions), the server may not send SSE events for minutes. At some point during these pauses, the SSE connection drops. The exact cause is unknown (see §5.1), but the effect is:

1. SSE stream ends normally — the `callbackFlow` in `subscribeGlobalEvents()` stops emitting
2. `startGlobalSseSubscription()` detects stream end and triggers `triggerGlobalSseReconnect()` (`OpenCodeService.kt:194-197`)
3. `deferred.await()` in `sendMessageInternal()` (`OpenCodeService.kt:416-418`) never receives a `StreamingCompleted` signal for the in-flight response
4. After 5 minutes, `withTimeout(300_000L)` fires → `TimeoutCancellationException` → "Response timed out"

---

## 3. Goals & Non-Goals

### Goals
1. Identify the actual cause of SSE disconnection during tool execution (not assume it's Ktor's HttpTimeout)
2. Fix or mitigate the disconnection so SSE stays alive during legitimate server-side pauses
3. REST calls retain sensible timeouts to detect genuine server failures
4. Existing reconnection logic continues to work for genuine network failures

### Non-Goals
- Changing the server to send SSE heartbeats during tool execution (server-side change, out of scope)
- Changing the 5-minute `withTimeout` on `deferred.await()` (this is a safety net, not the root cause)
- Blaming Ktor's `HttpTimeout` plugin without evidence (the plugin sets the timeout on SSE requests, but the Java engine doesn't enforce it — see §4.1–4.2)

---

## 4. Root Cause Investigation

### 4.1 Ktor's HttpTimeout: What Actually Applies to SSE

**Important version context:** This project uses **Ktor 3.1.3** (`libs.versions.toml`). [PR #4687](https://github.com/ktorio/ktor/pull/4687), merged into Ktor 3.1.x, changed the timeout behavior for SSE and WebSocket:

> "Previously all timeout settings were ignored for WS and SSE requests. I've changed this behavior to **ignore only request timeout**."

In Ktor 3.1.3, the `on(Send)` handler applies socket and connect timeouts to SSE, but skips request timeout. From the [Ktor 3.1.3 source](https://github.com/ktorio/ktor/blob/3.1.3/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpTimeout.kt):

```kotlin
on(Send) { request ->
    val supportsRequestTimeout = request.supportsRequestTimeout  // false for SSE
    var configuration = request.getCapabilityOrNull(HttpTimeoutCapability)
    if (configuration == null && hasNotNullTimeouts(supportsRequestTimeout)) {
        configuration = HttpTimeoutConfig()
        request.setCapability(HttpTimeoutCapability, configuration)
    }
    configuration?.apply {
        this.connectTimeoutMillis = this.connectTimeoutMillis ?: connectTimeoutMillis  // ← APPLIED
        this.socketTimeoutMillis = this.socketTimeoutMillis ?: socketTimeoutMillis      // ← APPLIED
        if (supportsRequestTimeout) {                                                   // ← false for SSE
            this.requestTimeoutMillis = this.requestTimeoutMillis ?: requestTimeoutMillis
            applyRequestTimeout(request, this.requestTimeoutMillis)                     // ← SKIPPED
        }
    }
    proceed(request)
}
```

The `supportsRequestTimeout` check (which excludes SSE via `body !is SSEClientContent`) **only controls `requestTimeoutMillis`** — it does NOT prevent socket and connect timeouts from being set on the request's `HttpTimeoutCapability`. The Ktor plugin sets `HttpTimeoutCapability` with `socketTimeoutMillis = 60_000` and `connectTimeoutMillis = 10_000` on the SSE request.

**However**, this does not mean the Java engine enforces these timeouts for SSE. See §4.2.

### 4.2 Proven: Java Engine Has No Idle Socket Timeout

The Java engine (`JavaHttpEngine.kt`) uses `java.net.http.HttpClient` (Java 11+). When building the client, it applies `connectTimeout` from Ktor's `HttpTimeoutCapability`:

```kotlin
// JavaHttpEngine.kt — getJavaHttpClient()
data.getCapabilityOrNull(HttpTimeoutCapability)?.let { timeoutAttribute ->
    timeoutAttribute.connectTimeoutMillis?.let {
        if (!isTimeoutInfinite(it)) connectTimeout(Duration.ofMillis(it))
    }
    // NOTE: socketTimeoutMillis and requestTimeoutMillis are NOT applied at engine level.
}
```

`java.net.http.HttpClient` has no idle socket timeout API. Its `HttpRequest.Builder.timeout()` sets a **total request deadline** (not an idle read timeout), and Ktor applies this only for non-SSE requests via `JavaHttpRequest.kt`:

```kotlin
// JavaHttpRequest.kt — per-request timeout (applied only if supportsRequestTimeout)
getCapabilityOrNull(HttpTimeoutCapability)?.let { timeoutAttributes ->
    timeoutAttributes.requestTimeoutMillis?.let {
        if (!isTimeoutInfinite(it)) timeout(Duration.ofMillis(it))
    }
}
```

Since `supportsRequestTimeout` is `false` for SSE, this `HttpRequest.Builder.timeout()` is NOT applied to SSE requests.

**Conclusion:** For SSE connections on the Java engine, **no Ktor-managed timeout is enforced** — not `socketTimeoutMillis` (no engine API), not `requestTimeoutMillis` (skipped by plugin), not the per-request deadline (skipped by plugin). Only `connectTimeoutMillis` is enforced (via `HttpClient.Builder.connectTimeout()`). The SSE connection has no client-side idle timeout and no total deadline.

This is distinct from the OkHttp engine issue ([#4682](https://github.com/ktorio/ktor/issues/4682), [PR #4687](https://github.com/ktorio/ktor/pull/4687)), where OkHttp's default 10-second socket timeout WAS applied but Ktor's plugin wasn't passing the configured value.

### 4.2.1 Timeout Enforcement Matrix (Java Engine)

| Timeout Type | REST Calls | SSE Calls | Enforcement Mechanism |
|---|---|---|---|
| `requestTimeoutMillis` | ✅ Enforced | ❌ Skipped | Coroutine-level timeout via `delay()` + cancellation; also per-request `HttpRequest.Builder.timeout()` via `JavaHttpRequest.kt`. Default 60s for REST calls. |
| `connectTimeoutMillis` | ✅ Enforced | ✅ Enforced | `HttpClient.Builder.connectTimeout()` in `getJavaHttpClient()` |
| `socketTimeoutMillis` | ❌ Not enforced | ❌ Not enforced | Set on `HttpTimeoutCapability` but Java engine has no socket idle timeout API |

> **Note on `socketTimeoutMillis` for REST:** Although `HttpTimeout` can throw `SocketTimeoutException` when the socket read exceeds the configured value, this is handled at the plugin level for engines that support it. The Java engine does NOT support socket timeout (confirmed by the Ktor documentation's timeout limitations table, which omits the Java engine entirely). On the Java engine, `socketTimeoutMillis` is effectively a no-op for ALL request types, not just SSE.

> **Note on `connectTimeoutMillis` for REST:** Despite the Ktor docs listing the Java engine as not supporting Connect Timeout, the source code (`JavaHttpEngine.getJavaHttpClient()`) explicitly applies `connectTimeout(Duration.ofMillis(...))` to `HttpClient.Builder`. This is likely a documentation gap — the Java engine DOES support connect timeout.

### 4.3 Actual Suspects (to investigate)

Since the Java engine does not enforce an idle socket timeout on SSE connections (§4.2), the connection drop must come from elsewhere. Candidates:

| Suspect | Likelihood | Rationale |
|---------|-----------|-----------|
| **Server-side idle timeout** | **High** | The OpenCode server (Node.js/Express) may have an HTTP server `timeout` (Node.js default: 120s). If the server closes the connection after 120s of inactivity, that would explain the drop. |
| **`java.net.http.HttpClient` internal keepalive/cleanup** | Medium | The underlying `HttpClient` implementation may clean up idle connections. Java 21+ has `HttpClient.Builder.keepAliveTime()` but the default varies. |
| **OS-level TCP keepalive** | Low | Windows default: 2 hours (`KeepAliveTime` = 7200000ms). Too long to match the observed ~60-120s window. |
| **Network proxy/firewall** | Low | Environment-specific, but many firewalls drop idle connections after 5-30 minutes. |
| **Server crash/tool failure** | Low | Would cause reconnection failure, not just SSE stream end. Reconnection succeeds, suggesting server remains healthy. |
| **Ktor SSE plugin internal reconnection** | Very Low | The SSE plugin supports `maxReconnectionAttempts` + `reconnectionTime` (`SSEConfig`). If the server sends `retry` fields, Ktor may close and reopen the connection, ending the current flow and triggering our reconnection logic. |

### 4.4 Recommended Investigation Steps

1. **Add timing logs around SSE disconnection.** Log the `System.currentTimeMillis()` when SSE connects and when it ends. Compare to the last SSE event timestamp to measure idle period before drop.

2. **Check Node.js server timeout.** The OpenCode server may have `server.timeout = X` in its HTTP server configuration. Node.js default is 120s (2 minutes) — close to the observed window. Test: `curl -N http://127.0.0.1:4096/event` and observe how long it stays open without events.

3. **Reproduce with a test.** Start a session, trigger a long-running tool (e.g., `timeout 180 && echo done`), and monitor the SSE connection lifecycle with the timing logs from step 1.

4. **Check for Ktor SSE retry fields.** The server may send `retry:` SSE fields requesting client-side reconnection. Ktor's SSE plugin respects these and may close/reopen the connection, ending our `subscribeGlobalEvents()` flow.

---

## 5. Proposed Solutions

Until the root cause is identified, these are ranked by likelihood:

### 5.1 Solution A: Server-Side Fix (most likely needed)

If the OpenCode server has an idle timeout (`server.timeout`) that kills the SSE connection:

**Fix:** Add `res.setTimeout(0)` (Node.js) to the SSE endpoint handler, or set `server.timeout = 0` for SSE routes. This is a server-side change — the plugin needs to request this from the OpenCode team or accept the behavior.

**Plugin impact:** None — the plugin's reconnection logic already handles this, though `deferred.await()` will still time out after 5 minutes for long tool executions.

### 5.2 Solution B: Per-Session SSE Subscription

**What:** Instead of one global SSE subscription (`GET /event`), subscribe per-session to `GET /session/:id/event` (if supported by the server).

**Why:** Each session gets its own SSE connection. If one drops, only that session is affected. Reduces blast radius.

**Risk:** Unknown if the server supports per-session SSE endpoints. Check OpenAPI spec at `http://127.0.0.1:4096/doc`.

### 5.3 Solution C: Short-Term Workaround — Reduce `withTimeout` Risk

**What:** Instead of relying entirely on SSE `StreamingCompleted` to unblock `deferred.await()`, also listen for tool status changes. If the active assistant message has a running tool and SSE goes quiet, poll `GET /session/:id/message` every N seconds to check if the last message has been finalized.

**Why:** Bypasses reliance on SSE staying alive. Messages are finalized server-side even if SSE drops.

**Risk:** Polling overhead; may get false positives if message is finalized before all tools complete.

### 5.4 Solution D: Application-Level SSE Keepalive (Alternative to C)

**What:** If the server supports it, use the SSE `retry` field to set a client reconnection interval. When the SSE stream ends (either due to server timeout or Ktor retry), the reconnection picks up quickly and the session can recover.

**Why:** Leverages Ktor's built-in `SSEConfig.maxReconnectionAttempts` + `reconnectionTime`. After reconnection, `recoverBackgroundSessions()` (`OpenCodeService.kt:275-313`) already checks and finalizes completed sessions.

**Risk:** `recoverBackgroundSessions()` re-fetches messages from REST, but the server may not expose in-progress messages via REST (only finalized messages appear in `GET /session/:id/message`). A completed session would recover, but a still-generating session would remain stuck.

---

## 6. Alternatives Considered (Rejected)

### 6.1 ~~Per-Request `socketTimeoutMillis = 0` Override~~ (REJECTED — Won't Work)

- **Why plausible:** Seemed like a simple one-line fix
- **Why rejected — three independent issues:**
  1. **`socketTimeoutMillis = 0` is invalid** — Ktor's `HttpTimeoutConfig.checkTimeoutValue()` requires positive values or `null`. Passing `0` throws `IllegalArgumentException`: *"Only positive timeout values are allowed, for infinite timeout use HttpTimeoutConfig.INFINITE_TIMEOUT_MS"*. The correct value for infinite timeout is `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` (= `Long.MAX_VALUE`), but even that wouldn't help because of issue #2.
  2. **Even with the correct value, the Java engine doesn't enforce it.** Ktor's HttpTimeout plugin sets `socketTimeoutMillis` on the SSE request's `HttpTimeoutCapability` (§4.1), but the Java engine's `java.net.http.HttpClient` has no socket-level idle timeout API. The value is set on the capability but never enforced for ANY request type — not just SSE. See §4.2.1.
  3. **Syntax error — wrong `sse()` overload.** The current code calls `httpClient.sse("$baseUrl/event") { ... }`, which uses the `sse(urlString, ..., block)` overload where the trailing lambda is `block: suspend ClientSSESession.() -> Unit`, NOT `request: HttpRequestBuilder.() -> Unit`. A `request { timeout { } }` block inside this lambda would call a method on `ClientSSESession`, which doesn't exist. To set per-request timeouts, you'd need the builder-form overload:

  ```kotlin
  // CORRECT syntax for per-request timeout override:
  httpClient.sse(request = {
      url("$baseUrl/event")
      timeout {
          socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS  // still wouldn't help on Java engine
      }
  }) {
      // block: suspend ClientSSESession.() -> Unit
      incoming.collect { event -> ... }
  }
  ```

  But even this correct syntax is pointless on the Java engine because `socketTimeoutMillis` is never enforced (§4.2.1).

### 6.2 ~~Increase Default `socketTimeoutSeconds`~~ (REJECTED — Targets Wrong Layer)

- **Why plausible:** Simple parameter change
- **Why rejected:** The setting sets `socketTimeoutMillis` on the `HttpTimeoutCapability`, which Ktor's plugin propagates to all requests (§4.1). However, the Java engine doesn't enforce socket-level idle timeouts (§4.2) — not for SSE, and not for REST either. `socketTimeoutMillis` is effectively a no-op on the Java engine for all request types (§4.2.1). Increasing the value has no effect. Also doesn't solve long tool execution (>5 min).

### 6.3 ~~Separate HTTP Clients~~ (REJECTED for Now — Premature)

- **Why plausible:** Clean separation of concerns between SSE and REST timeouts
- **Why rejected:** Doesn't address the root cause since SSE has no client-side timeout. Would add complexity without benefit unless the root cause is in the shared `HttpClient` instance (unlikely given §4.2).

---

## 7. Cross-Cutting Concerns

### 7.1 `sseSocketTimeoutSeconds` Setting Is Misleading — and Ineffective

The setting in `OpenCodeSettingsState.kt:39` named `sseSocketTimeoutSeconds` is used at `OpenCodeConnectionManager.kt:113` as the global `socketTimeoutMillis`. This name is misleading in two ways:

1. **It applies to ALL requests, not just SSE.** The value is set on the global `HttpTimeout` plugin config, which applies to every request made through the shared `HttpClient`. The name implies it's SSE-specific, but it affects REST calls too.

2. **It has NO effect on the Java engine.** The Java engine does not enforce `socketTimeoutMillis` for any request type — SSE or REST (§4.2.1). The value is set on the `HttpTimeoutCapability` but the Java engine has no socket idle timeout mechanism. `java.net.http.HttpClient` does not expose a socket read idle timeout.

**Recommendation:** Either:
- Remove the setting entirely (it's a no-op on the Java engine), or
- Rename to `restSocketTimeoutSeconds` and add a tooltip: "This setting has no effect on the Java HTTP engine. It is preserved for compatibility with other Ktor engines (OkHttp, CIO) that support socket idle timeouts."

### 7.2 Reliability

The existing reconnection logic (`triggerGlobalSseReconnect()`) and background session recovery (`recoverBackgroundSessions()`) already handle SSE stream end events. The gap is that `sendMessageInternal`'s `deferred.await()` has no alternate resolution path — it relies entirely on SSE delivering `StreamingCompleted`. The configurable `responseTimeoutSeconds` (default 5 minutes) `withTimeout` is the **only** client-side safety net preventing an infinite hang.

**Mitigation implemented:** `recoverBackgroundSessions()` now checks for in-progress tool calls before finalizing. If the last assistant message has `ToolUse` parts without matching `ToolResult` parts, the session is skipped — preventing incorrect finalization of sessions mid-tool-execution.

### 7.3 Observability

Add logging to diagnose the root cause:

```kotlin
// In subscribeGlobalEvents(), at connection start:
val connectTime = System.currentTimeMillis()
logger.info { "[ACP] SSE connected to $baseUrl/event at $connectTime" }

// At stream end:
val disconnectTime = System.currentTimeMillis()
val uptimeSeconds = (disconnectTime - connectTime) / 1000
logger.info { "[ACP] SSE stream ended after ${uptimeSeconds}s" }
```

Add per-event timestamp tracking to measure the idle gap before disconnection.

---

## 8. Testing Strategy

### 8.2 Key Scenarios

| Scenario | Expected Behavior | Timeout Mechanism |
|----------|-------------------|-------------------|
| SSE connected, LLM generating text (events flowing) | No disconnection — events keep arriving | No timeout enforced (§4.2.1) |
| SSE connected, tool execution (no events for >60s) | Monitor idle period before drop; log exact duration | No timeout enforced (§4.2.1); drop must come from server or OS |
| Reproduce with `curl -N` to server SSE endpoint | Measure idle timeout from server side | N/A (testing server behavior) |
| REST call (POST /message) with slow server | Times out after `requestTimeoutMillis` (60s total deadline) | `requestTimeoutMillis` enforced by HttpTimeout plugin + `HttpRequest.Builder.timeout()` |
| REST call that can't connect | Times out after `connectTimeoutMillis` (10s) | `connectTimeoutMillis` enforced by `HttpClient.Builder.connectTimeout()` |

---

## 9. Deployment & Rollout Plan

> **Omitted** per Mini TDD guidelines.

---

## 10. Open Questions

1. **Does the OpenCode server have an HTTP idle timeout for SSE connections?** Check `opencode serve` source for `server.timeout` or Express middleware that imposes connection limits. Node.js default is 120s.

2. **Does Ktor's SSE plugin interpret `retry:` fields from the server and auto-reconnect?** If so, this could explain the flow ending and restarting — our `callbackFlow` would end, triggering `triggerGlobalSseReconnect()`.

3. **Does `java.net.http.HttpClient` clean up idle connections?** Java 21+ has `HttpClient.Builder.keepAliveTime()` — verify the JDK version used by the IntelliJ platform.

4. **Should the `sseSocketTimeoutSeconds` setting be removed or renamed?** It's a no-op on the Java engine (§7.1). Keeping it risks confusion. Renaming to `restSocketTimeoutSeconds` would be more accurate, but the setting still wouldn't affect the Java engine.

5. **Can `recoverBackgroundSessions()` detect still-running sessions?** Currently, `recoverBackgroundSessions()` assumes the last REST message being "assistant" means the session completed. If the server exposes in-progress messages via REST, a running session could be incorrectly finalized.

6. **Should `requestTimeoutMillis` be lowered for REST calls?** **IMPLEMENTED:** `requestTimeoutMillis` has been lowered from 300s (5 min) to 60s. Since `socketTimeoutMillis` is a no-op on the Java engine, `requestTimeoutMillis` is the only timeout protecting REST calls from hanging. 60s is appropriate for interactive REST calls like `GET /session/:id` or `POST /session/:id/message`.

7. **What is the actual JDK version used by the IntelliJ platform runtime?** This affects `HttpClient` behavior — Java 21+ has `keepAliveTime()` which could cause idle connection cleanup.

---

## 11. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Root cause is server-side idle timeout (Node.js default 120s) | High | Medium — needs server change | Request server-side fix; add client-side workaround (polling or keepalive) |
| Investigation takes too long | Medium | Low — reconnection already works | Short-term: reduce `withTimeout` to surface the issue faster; add diagnostic logging |
| `recoverBackgroundSessions()` incorrectly finalizes still-running session | Low | High — user loses in-progress response | **IMPLEMENTED:** Check for in-progress tool calls (ToolUse without matching ToolResult) before finalizing. Skip finalization if tools are still running — let SSE reconnection deliver remaining events. |
| `sseSocketTimeoutSeconds` setting removal confuses users | Low | Low — setting is a no-op anyway | Add tooltip explaining it has no effect on the Java engine |

---

## 12. Document History

| Date | Author              | Changes |
|------|---------------------|---------|
| 2026-06-09 | --                  | Initial draft (included incorrect HttpTimeout fix) |
| 2026-06-09 | --                  | Complete rewrite — HttpTimeout fix proven invalid; shifted to root-cause investigation |
| 2026-06-09 | Reviewer            | Fixed §4.1–4.2: corrected misinterpretation of `supportsRequestTimeout` (it only skips `requestTimeoutMillis`, not socket/connect timeouts); cited Ktor 3.1.3 source; clarified that Java engine doesn't enforce socket-level idle timeouts; updated §6.1–6.2, §7.1 rejection rationale |
| 2026-06-09 | Reviewer (2nd pass) | Added §4.2.1 timeout enforcement matrix; corrected §7.1 (`socketTimeoutMillis` is a no-op on Java engine for ALL requests, not just SSE); added §6.1 correct `sse()` overload syntax; clarified §2.1 `requestTimeoutMillis` not applied to SSE; added open questions about removing setting, JDK version, per-request REST timeouts; clarified §8.2 timeout mechanisms; corrected `connectTimeoutMillis` enforcement (it IS applied by Java engine) |
| 2026-06-09 | Implementation      | Lowered `requestTimeoutMillis` from 300s to 60s for REST calls (§10 Q6); implemented tool-call-aware `recoverBackgroundSessions()` — skips finalization if ToolUse parts lack matching ToolResult (§11 Risk 3); updated §2.1 to reflect `socketTimeoutMillis` removal; added deprecation timeline comment for `sseSocketTimeoutSeconds` |