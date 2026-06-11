# Technical Design Document: Context Indicator

> **Status:** Draft
> **Last Updated:** 2026-06-04
> **Related docs:** [OpenCode Server API](https://opencode.ai/docs/server/)

---

## 1. TL;DR

Add a context usage indicator to the chat input area — a small fillable circle that shows token usage and context window utilization for the active session. On hover, a compact tooltip displays tokens, usage %, and cost. On click, a full context details panel opens as a tab in the sessions sidebar. The data comes from `GET /session/:id` (new integration) and `GET /provider` (already used). Updates are triggered by `SseEvent.MessageComplete` scoped to the current session (assistant messages only), with an initial fetch on session activation, stale-result protection, and cancellation of in-flight requests on session switch.

---

## 2. Context & Scope

### 2.1 Current State

The plugin currently tracks session cost and tokens only in the sessions sidebar list (`SessionSidebar.kt`), using data from `GET /session` (list). The active chat view has **no indicator** of context usage, token consumption, or cost during a conversation. Users must mentally track how much context they've used.

The OpenCode API already returns rich session context data:
- `Session.tokens` — input, output, reasoning, cache (read/write)
- `Session.cost` — total USD cost
- `Session.model` — which model was used (`SessionModel` with `id`, `providerID`, `variant`)
- `ProviderModel.contextWindow` — token limit (already fetched via `GET /provider`)
- `GET /session/:id` — returns full session details including all the above

### 2.2 Problem Statement

Users have no visibility into context window usage during an active conversation. They don't know when they're approaching the context limit, how much a response cost, or how many tokens they've consumed. This leads to unexpected context truncation and cost surprises.

---

## 3. Goals & Non-Goals

### Goals
1. Display a context usage indicator (fillable circle) in the input area showing usage % relative to context window
2. Show a compact hover tooltip with tokens, usage %, cost, and model info
3. Show a full context details panel as a tab in the sessions sidebar (with auto-expansion if sidebar is hidden)
4. Update context data after each assistant response is fully persisted (on `SseEvent.MessageComplete`) and on session activation

### Non-Goals
- Real-time token counting during streaming (requires client-side estimation, not worth the complexity)
- Per-message cost breakdown (available from API but out of scope for v1)
- Context breakdown bar by role (User/Assistant/Tool Calls %) — the API doesn't expose per-role token counts
- Alerts when approaching context limits (future enhancement)
- Context compaction/summarization controls (already exists via separate API)
- Adding optional fields to `MessageInfo` for per-message cost/tokens (deferred to per-message feature work)

---

## 4. Proposed Solution

**Add a `ContextIndicator` composable to the input area's selector row, backed by a `sessionContextState` StateFlow in the ViewModel. The state uses a sealed class (`Loading` / `Loaded` / `Error`) to distinguish lifecycle states. Data is fetched via the new `getSession()` method on `OpenCodeClient`, triggered by `SseEvent.MessageComplete` (assistant messages only, scoped to current session) and on session activation. The indicator shows a fillable circle (usage %) with hover tooltip and click-to-sidebar behavior. The sessions sidebar gains a tab row with "Sessions" and "Context" tabs, expanding to ≥320dp when Context is active. When the sidebar is hidden and the user clicks the indicator, the sidebar auto-expands and switches to the Context tab.**

### 4.3 API / Interface Design

**Endpoints to consume:**

| Method | Path | Purpose | Status | Notes |
|--------|------|---------|--------|-------|
| `GET` | `/session/:id` | Get full session details | **New integration** | Returns cost, tokens, model, time. New `getSession()` method on `OpenCodeClient`. **Must confirm field names against live spec at `127.0.0.1:4096/doc` before coding.** |
| `GET` | `/provider` | Get model list with limits | Already used | `limit.context` = context window size |

**⚠️ API field name verification required:** The TDD assumes `session.tokens.input`, `session.tokens.cache.read`, `session.time.created`, `session.cost` paths. The exact field names (camelCase vs snake_case, nesting structure) MUST be confirmed against the live OpenAPI spec at `http://127.0.0.1:4096/doc` before implementation. If the actual API uses different paths, every `?:` fallback will silently produce `0L` — showing legitimate-looking "0%" instead of an error.

**Data flow:**

```
Initial load:
  switchSession() → cancel in-flight fetchJob → reset state → fetchSessionContext() → GET /session/:id → _sessionContextState → UI

After each assistant response:
  SseEvent.MessageComplete (sessionId matches current, role=assistant) → fetchSessionContext() → GET /session/:id → _sessionContextState → UI

Session switch:
  switchSession() → cancel in-flight fetchJob → reset state → fetchSessionContext() → GET /session/:id → _sessionContextState → UI

Error:
  fetchSessionContext() throws OR critical fields missing → _sessionContextState = Error(message) → UI shows "—" indicator with retry callback

Stale result:
  fetchSessionContext() completes but sessionId changed → discard result, do not update state
```

### 4.5 Technology Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| Language | Kotlin | Matches existing codebase |
| UI | Compose for Desktop (Jewel) | Matches existing UI framework |
| HTTP | Ktor Client | Already used in `OpenCodeClient` |
| State | Kotlin StateFlow | Matches existing ViewModel pattern |

### 4.7 Implementation Blueprint

#### 4.7.1 Data Models

**New types in `ChatModels.kt`:**

```kotlin
/** Sealed state for session context — distinguishes loading, loaded, and error. */
sealed interface SessionContextState {
    data object Loading : SessionContextState
    data class Loaded(val context: SessionContext) : SessionContextState
    data class Error(val message: String, val retryable: Boolean = true) : SessionContextState
}

/** Context information for the active session, derived from GET /session/:id. */
data class SessionContext(
    val sessionId: String,
    val title: String,               // falls back to "Untitled" if blank
    val providerID: String,          // from session.model.providerID (ground truth)
    val modelID: String,             // from session.model.id (ground truth)
    val providerName: String,        // resolved from provider list; raw ID if unavailable
    val modelName: String,           // resolved from provider list; raw ID if unavailable
    val contextLimit: Long,          // from resolved ProviderModel.contextWindow (0 = unknown → "N/A")
    val totalTokens: Long,           // input + output + reasoning + cacheRead + cacheWrite
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val usagePercent: Float,         // totalTokens / contextLimit * 100 (can exceed 100f)
    val totalCost: Double,
    val sessionCreated: Long,        // epoch millis (from session.time.created)
    val lastUpdated: Long            // epoch millis (from session.time.updated)
)
```

**Key design decisions:**
- **Sealed `SessionContextState`**: The UI can distinguish "still loading" from "loaded with zero tokens" from "API failed." Follows the same pattern as `SessionListState`.
- **`Error.retryable`**: When `true`, the UI shows a retry link. Used by both the indicator and the sidebar context panel.
- **No `isStreaming` field**: Streaming state is read from `viewModel.isStreaming` directly in the UI, not snapshotted into `SessionContext` (which would always be stale).
- **No `messageCount`/`userMessages`/`assistantMessages`**: These cannot be populated from `GET /session/:id` and are not needed for v1. Removed to avoid dead fields.
- **`totalTokens` includes cache tokens**: `totalTokens = input + output + reasoning + cacheRead + cacheWrite`. Cache read tokens count against context on providers like Anthropic. If the API provides a `tokens.total` field in the future, prefer that instead.
- **`usagePercent` can exceed 100f**: If token usage exceeds context window, the UI shows the overrun. No `coerceIn`. The progress bar fills to 100% with a "+" overflow indicator.
- **`contextLimit` is `Long`**: Avoids type asymmetry with `totalTokens: Long` and future-proofs for models with very large windows.
- **Model identity from server**: `providerID` and `modelID` come from `session.model` (ground truth), not from client-side string parsing of `displayName`.
- **`contextLimit == 0` means unknown**: The UI must treat `contextLimit == 0L` as "context window unknown" — show "N/A" for usage, hide the progress bar. This is not the same as "0% used".
- **`title` fallback**: `title` is stored as-is from the API. If blank, the UI displays "Untitled".

**No changes to `MessageInfo` in v1.** The per-message `cost`/`tokens`/`modelID`/`providerID`/`finish` fields are deferred to future per-message feature work. Adding optional fields to a serializable model changes deserialization behavior with zero v1 benefit.

#### 4.7.2 Class & Interface Definitions

**ViewModel changes (`ChatViewModel.kt`):**

```kotlin
// New state — sealed class to distinguish loading/loaded/error
private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

// Job for in-flight context fetch — cancelled on session switch and close
private var contextFetchJob: Job? = null

// Modified resetSessionState() — must cancel contextFetchJob
private fun resetSessionState() {
    contextFetchJob?.cancel()          // NEW: cancel in-flight context fetch
    contextFetchJob = null
    _sessionContextState.value = SessionContextState.Loading  // NEW: reset context state
    _messages.value = emptyList()
    messageIndex.clear()
    toolCallIndex.clear()
    _isStreaming.value = false
    activeAssistantMessageId = null
    firstTextChunkReceived = false
    responseDeferred?.complete(Unit)
    responseDeferred = null
    _permissionPrompt.value = null
    permissionTimeoutJob?.cancel()
    permissionTimeoutJob = null
    lastUserText = null
}

// Modified close() — must cancel contextFetchJob before nulling client
override fun close() {
    contextFetchJob?.cancel()          // NEW: cancel before nulling client
    contextFetchJob = null
    sseJob?.cancel()
    sseJob = null
    permissionTimeoutJob?.cancel()
    permissionTimeoutJob = null
    responseDeferred?.complete(Unit)
    responseDeferred = null
    activeAssistantMessageId = null
    openCodeClient?.close()
    openCodeClient = null
    httpClient?.close()
    httpClient = null
    sessionId = null
    openCodeProcess?.destroyForcibly()
    openCodeProcess = null
}

// Resolve provider/model display names from the server-ground-truth IDs
private fun resolveModelNames(sessionModel: SessionModel?): Pair<String, String> {
    if (sessionModel == null) return Pair("", "Unknown")
    val models = controlState.value.models
    if (models.isEmpty()) {
        // Providers not loaded (listProviders() failed during init)
        logger.warn { "Model list empty — falling back to raw IDs" }
        return Pair(sessionModel.providerID.ifEmpty { "Unknown" }, sessionModel.id.ifEmpty { "Unknown" })
    }
    val providerName = models
        .firstOrNull { it.providerID == sessionModel.providerID }
        ?.let { it.displayName.substringBefore(" / ") }
        ?: sessionModel.providerID.ifEmpty { "Unknown" }
    val modelName = models
        .firstOrNull { it.providerID == sessionModel.providerID && it.modelID == sessionModel.id }
        ?.let { it.displayName.substringAfter(" / ").trim() }
        ?: sessionModel.id.ifEmpty { "Unknown" }
    return Pair(providerName, modelName)
}

// Resolve context limit from server-ground-truth model IDs
private fun resolveContextLimit(sessionModel: SessionModel?): Long {
    if (sessionModel == null) return 0L
    return controlState.value.models
        .firstOrNull { it.providerID == sessionModel.providerID && it.modelID == sessionModel.id }
        ?.contextWindow?.toLong() ?: 0L
}

// Called on MessageComplete (assistant only, current session) and on session activation
private fun fetchSessionContext() {
    contextFetchJob?.cancel()
    contextFetchJob = scope.launch {
        val client = openCodeClient ?: return@launch
        val capturedSessionId = sessionId ?: return@launch  // capture atomically
        try {
            val session = withTimeout(30_000L) {            // 30s timeout
                client.getSession(capturedSessionId)
            }

            // Stale check: if sessionId changed during fetch, discard result
            if (sessionId != capturedSessionId) {
                logger.debug { "Discarding stale context fetch for $capturedSessionId (now on $sessionId)" }
                return@launch
            }

            val tokens = session.tokens

            // Validate critical fields — if tokens object exists but all fields are null,
            // that's suspicious (API shape mismatch). Treat as error, not zero.
            if (tokens != null && tokens.input == 0L && tokens.output == 0L &&
                tokens.reasoning == 0L && tokens.cache.read == 0L && tokens.cache.write == 0L &&
                session.cost == 0.0) {
                logger.warn { "Session $capturedSessionId: all token/cost fields zero — possible API shape mismatch" }
            }

            val totalTokens = (tokens?.input ?: 0L) +
                              (tokens?.output ?: 0L) +
                              (tokens?.reasoning ?: 0L) +
                              (tokens?.cache?.read ?: 0L) +
                              (tokens?.cache?.write ?: 0L)
            val contextLimit = resolveContextLimit(session.model)
            val usagePercent = if (contextLimit > 0L) {
                (totalTokens.toFloat() / contextLimit.toFloat() * 100f)
            } else 0f

            val (providerName, modelName) = resolveModelNames(session.model)

            _sessionContextState.value = SessionContextState.Loaded(SessionContext(
                sessionId = session.id,
                title = session.title.ifBlank { "Untitled" },
                providerID = session.model?.providerID ?: "",
                modelID = session.model?.id ?: "",
                providerName = providerName,
                modelName = modelName,
                contextLimit = contextLimit,
                totalTokens = totalTokens,
                inputTokens = tokens?.input ?: 0L,
                outputTokens = tokens?.output ?: 0L,
                reasoningTokens = tokens?.reasoning ?: 0L,
                cacheReadTokens = tokens?.cache?.read ?: 0L,
                cacheWriteTokens = tokens?.cache?.write ?: 0L,
                usagePercent = usagePercent,
                totalCost = session.cost,
                sessionCreated = session.time?.created ?: 0L,
                lastUpdated = session.time?.updated ?: 0L
            ))
        } catch (e: TimeoutCancellationException) {
            logger.warn { "Context fetch timed out for $capturedSessionId" }
            _sessionContextState.value = SessionContextState.Error(
                "Context fetch timed out", retryable = true
            )
        } catch (e: CancellationException) {
            // Job was cancelled (session switch, close) — don't update state
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch session context for $capturedSessionId" }
            _sessionContextState.value = SessionContextState.Error(
                "Failed to load context: ${e.message ?: "Unknown error"}", retryable = true
            )
        }
    }
}
```

**OpenCodeClient addition (`OpenCodeClient.kt`):**

```kotlin
/**
 * GET /session/{id}
 *
 * Returns the full session object including tokens, cost, and model.
 * This is a new method — not previously used by the plugin.
 *
 * IMPORTANT: Confirm the response field names against the live OpenAPI spec at
 * http://127.0.0.1:4096/doc before relying on specific paths like
 * session.tokens.input, session.tokens.cache.read, session.time.created, etc.
 * If the actual API uses different field names, the deserializer will silently
 * produce nulls, and the fallback will show zeros instead of an error.
 */
suspend fun getSession(sessionId: String): OpenCodeSession =
    getJson("/session/$sessionId")
```

**SSE event handler change (`ChatViewModel.kt`):**

```kotlin
// In handleSseEvent():
is SseEvent.MessageComplete -> {
    // Only refresh context for the current session and only after assistant messages.
    // MessageComplete fires for ALL messages (user + assistant). User message
    // completions don't change token counts, so skip them to avoid unnecessary
    // network calls.
    val currentId = sessionId
    if (currentId != null && event.sessionId == currentId && event.role == "assistant") {
        scope.launch { fetchSessionContext() }
    }
}
```

**SSE parser change (`OpenCodeClient.kt`):**

The SSE parser (`parseSseEvent()`) must be updated to emit `SseEvent.MessageComplete` for the `message.complete` event type from the server. Currently, the parser handles `message.part.delta`, `message.part.updated`, `message.updated`, `stop`, etc., but does NOT produce `MessageComplete`. Verify the actual event type name from the server (likely `"message.complete"` or `"message.completed"`) and add a parser branch. If `MessageComplete` is not a real server event, fall back to using `SseEvent.Stop` + a debounced fetch (500ms delay) as the trigger, or parse `message.updated` events where the message has a `finish` field.

**New composable (`ContextIndicator.kt`):**

```kotlin
@Composable
fun ContextIndicator(
    state: SessionContextState,
    isStreaming: Boolean,
    onShowDetails: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
)
```

- **`onRetry` callback**: When `state` is `Error(retryable=true)`, the indicator shows a "!" icon and the tooltip includes a "Click to retry" hint. Clicking calls `onRetry()` which re-triggers `fetchSessionContext()`.
- **`isStreaming`**: Drives a subtle pulsing animation on the circle, indicating "update pending." Does NOT affect the displayed token counts.
- **Tooltip positioning**: `Popup` with `alignment = Alignment.TopEnd` and `offset = IntOffset(0, -8)` to avoid clipping near screen edges.

**Sidebar tab refactoring (`SessionSidebar.kt`):**

```kotlin
enum class SidebarTab { SESSIONS, CONTEXT }

@Composable
fun SessionSidebar(
    state: SessionListState,
    contextState: SessionContextState,
    activeTab: SidebarTab,
    onTabChanged: (SidebarTab) -> Unit,
    onContextRetry: () -> Unit,
    onNewSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onSessionArchived: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Sidebar width behavior:**
- Default (Sessions tab): 260dp (existing `SIDEBAR_WIDTH_DP`)
- Context tab active: 320dp (wider to accommodate progress bar + token breakdown)
- Width transitions with `animateDpAsState` for smooth resize

**Context details panel specification:**

The Context tab in the sidebar renders the following layout:

```
┌─────────────────────────────┐
│ Model Name                  │
│ Provider Name               │
│                             │
│ ┌─────────────────────────┐│
│ │ Context Usage    67.3%   ││
│ │ ████████████░░░░░░░░░░░ ││ (fillable progress bar)
│ │ 98,304 / 128,000 tokens ││
│ └─────────────────────────┘│
│                             │
│ Tokens                      │
│   Input         45,120      │
│   Output        12,840      │
│   Reasoning      3,200      │
│   Cache Read    35,144      │
│   Cache Write    2,000      │
│                             │
│ Cost                        │
│   Total         $0.04      │
│                             │
│ Session                     │
│   Created    2h ago        │
│   Updated    just now      │
└─────────────────────────────┘
```

- **Progress bar**: Uses `usagePercent` (can exceed 100%). Colors: 0–50% green, 50–75% yellow, 75%+ red. All colors use theme-aware `JBColor` with dark/light variants.
- **Overflow (>100%)**: Bar fills to 100%, percentage text shows actual value (e.g., "115%"), and a red "+" overflow indicator appears at the bar's right edge.
- **Unknown context limit**: When `contextLimit == 0`, progress bar is hidden, usage displays "N/A", and the token count row shows total tokens without a denominator.
- **Labels**: Use existing `formatTokens()` and `formatCost()` helpers from `SessionSidebar.kt`.
- **Title fallback**: If `context.title` is blank, display "Untitled".
- **Error state**: Shows "Failed to load context" with a retry link (calls `onContextRetry()`).
- **Loading state**: Shows a centered "Loading..." text (same pattern as `SessionList`).
- **Session timestamps**: Use existing `formatRelativeTime()` helper.

#### 4.7.5 Enums, Constants & Configuration

```kotlin
// In ChatConstants.kt
const val CONTEXT_INDICATOR_SIZE_DP = 28     // 28dp to match selector row chip height
const val SIDEBAR_CONTEXT_WIDTH_DP = 320     // wider sidebar when Context tab active
const val CONTEXT_FETCH_TIMEOUT_MS = 30_000L // 30s timeout for GET /session/:id
```

**Usage % color thresholds** (theme-aware via `JBColor`):

| Range | Dark Theme | Light Theme | Meaning |
|-------|-----------|------------|---------|
| 0–50% | `#6BBE50` | `#4A8C3A` | Healthy |
| 50–75% | `#E5A617` | `#C48E14` | Getting full |
| 75%+ | `#E5534B` | `#C44040` | Near limit |

Colors use `JBColor(Color(0xFF6BBE50), Color(0xFF4A8C3A))` pattern — consistent with existing codebase theming.

**Accessibility**: All color thresholds also have a visual shape indicator:
- Healthy (0–50%): filled circle (default)
- Getting full (50–75%): circle with a small "!" icon inside
- Near limit (75%+): circle with a 2dp outer ring (double-border effect — inner circle at normal size, outer ring at `CONTEXT_INDICATOR_SIZE_DP + 4dp`)

The tooltip always shows the numeric percentage, not just the color. Screen reader content description: "Context usage: {percent}%, {used} of {limit} tokens".

---

## 5. Assumptions & Dependencies

**Assumptions:**
- The OpenCode server is running and accessible at `localhost:4096`
- `GET /session/:id` exists and returns up-to-date token counts and cost after `SseEvent.MessageComplete` — **this must be verified against the live spec before implementation**
- `SseEvent.MessageComplete` is a real server event and is emitted by the SSE parser — **the current parser does NOT emit this event; parser update required**
- `ProviderModel.contextWindow` is accurate for connected providers
- Model switches mid-session update `session.model` on the server side, and the next `fetchSessionContext()` call picks up the new model

**Dependencies:**
- OpenCode Server v1.15+ (for `Session.tokens`, `Session.cost`, `Session.model` fields)
- Jewel Compose (for UI components — `Icon`, `Text`, `Popup`)
- Ktor Client (for HTTP calls — already in use)
- SSE parser update: `parseSseEvent()` in `OpenCodeClient.kt` must emit `SseEvent.MessageComplete` for the server's message completion event

---

## 6. Alternatives Considered

**Alternative: Client-side token estimation**
- *What it is:* Count tokens by estimating from message text length (chars / 4)
- *Why plausible:* No API call needed, real-time updates during streaming
- *Why rejected:* Inaccurate (doesn't account for tokenization differences, tool calls, system prompts), doesn't include cost data, doesn't reflect actual server-side token counts

**Alternative: SSE event-based accumulation (parse `message.updated` events)**
- *What it is:* Parse `message.updated` SSE events to accumulate tokens per message
- *Why plausible:* Real-time, no extra API calls
- *Why rejected:* The current SSE handler only processes a subset of events. Would require significant refactoring of the SSE pipeline. `GET /session/:id` triggered by `MessageComplete` is simpler and guarantees server-consistent data.

**Alternative: Polling-based refresh**
- *What it is:* Poll `GET /session/:id` every N seconds during streaming
- *Why plausible:* Updates during long tool-call chains, not just on completion
- *Why rejected:* Adds network load and complexity. The indicator's primary value is "how full am I?" which changes significantly at completion boundaries, not incrementally. Can be added later if needed.

**Alternative: Use `SseEvent.Stop` + debounce instead of `MessageComplete`**
- *What it is:* Use the existing `SseEvent.Stop` (which the parser already emits) with a 500ms delay
- *Why plausible:* `Stop` fires at the end of each response, and the parser already handles it
- *Why rejected:* `Stop` fires before the server has committed token counts to the session record. The 500ms delay is a guess. `MessageComplete` (if it exists) fires after full persistence. **Fallback:** If `MessageComplete` is not a real server event, use `Stop` + 500ms debounce as a pragmatic alternative.

---

## 7. Cross-Cutting Concerns

### 7.2 Reliability & Availability

- **Graceful degradation:** If `GET /session/:id` fails, `_sessionContextState` becomes `Error(message, retryable=true)`. The indicator shows "—" with a "!" icon, and the sidebar context tab shows the error message with a retry link. The sidebar sessions tab continues to work normally.
- **Race condition prevention:** `contextFetchJob` is cancelled before each new fetch. On session switch, the old job is cancelled and `_sessionContextState` is reset to `Loading`. The `fetchSessionContext()` function captures `sessionId` atomically at launch and discards the result if `sessionId` changed during the fetch.
- **Session lifecycle:** `_sessionContextState` is reset to `Loading` in `resetSessionState()`. `contextFetchJob` is cancelled in both `resetSessionState()` and `close()`.
- **Initial fetch:** Context is fetched immediately on session activation (`switchSession()`), not only after the first response completes.
- **Close safety:** `contextFetchJob` is cancelled in `close()` before `openCodeClient` is nulled, preventing in-flight requests from using a closed client. `CancellationException` is re-thrown to avoid updating state during teardown.
- **Timeout:** `getSession()` has a 30s timeout via `withTimeout()`. On timeout, the state becomes `Error("Context fetch timed out", retryable=true)`.

### 7.3 Performance & Scalability

- **Scoped SSE events:** Only `MessageComplete` events for the current session AND assistant role trigger a fetch. User message completions are ignored.
- **Single API call per completion:** One `GET /session/:id` per qualifying `MessageComplete` event and one per session switch. Negligible load.
- **Job cancellation:** In-flight context fetches are cancelled when they're no longer needed (session switch, close), not left to complete and overwrite.
- **StateFlow:** Only recomposes composables that read `sessionContextState`. No unnecessary recomposition.

### 7.4 Accessibility

- **Color blindness:** Color thresholds are augmented with visual shape indicators (circle fill level + icon overlays + outer ring), not color alone. The tooltip always shows the numeric percentage.
- **Keyboard navigation:** The context indicator is focusable (`Modifier.focusable()`) and clickable via keyboard (Enter/Space). The sidebar Context tab is accessible via Tab key.
- **Screen readers:** The indicator has a content description like "Context usage: 67%, 98,304 of 128,000 tokens. Click to view details."

---

## 8. Testing Strategy

### 8.2 Key Scenarios

1. **Indicator displays correct usage %** — mock `SessionContextState.Loaded` with known token counts and context limit; verify circle fill level and color
2. **Hover tooltip shows correct data** — verify tokens, usage %, cost text, and model name match context values
3. **Click opens Context tab in sidebar** — verify tab switches, panel renders, and sidebar auto-expands if hidden
4. **Context refreshes after response** — emit a `SseEvent.MessageComplete(sessionId=current, role="assistant")`, verify `sessionContextState` emits `Loaded` with updated tokens
5. **Graceful handling of API failure** — mock `getSession()` to throw, verify indicator shows "—" with "!" icon and context tab shows error with retry link
6. **Session switch updates context** — verify state resets to `Loading`, old fetch is cancelled, and new data loads for the switched session
7. **Color thresholds and visual indicators** — verify green/circle at 25%, yellow/circle-with-"!" at 60%, red/double-ring at 85% usage
8. **Context limit unknown (0)** — when `contextLimit == 0`, progress bar is hidden and usage shows "N/A"
9. **Usage exceeds 100%** — when `usagePercent > 100` (cache tokens push over limit), percentage displays as "107%" with red color and double ring; progress bar fills to 100% with "+" overflow indicator
10. **Loading state** — on initial session load, before context fetch completes, indicator shows a loading spinner
11. **Model switch mid-session** — switch models, send a message, verify context reflects the new model's context window, not the old model's
12. **Sidebar hidden + context click** — with sidebar hidden, click the context indicator; verify sidebar auto-expands and switches to the Context tab
13. **Concurrent fetch cancellation** — start a context fetch, switch sessions before it completes; verify old data doesn't overwrite new session's context
14. **Stale result discard** — start a context fetch for session A, switch to session B before it completes; verify session A's data is discarded
15. **SSE event scoping** — emit `MessageComplete` for a different session; verify no context fetch is triggered
16. **SSE event role filtering** — emit `MessageComplete(role="user")` for current session; verify no context fetch is triggered
17. **Timeout** — mock `getSession()` to hang for 30s; verify state becomes `Error("timed out", retryable=true)`
18. **Empty models list** — mock `controlState.models` to empty; verify fallback to raw provider/model IDs
19. **Retry from error** — in `Error(retryable=true)` state, click retry; verify `fetchSessionContext()` is re-triggered
20. **Sidebar width transition** — switch from Sessions to Context tab; verify sidebar animates from 260dp to 320dp

---

## 9. Open Questions

1. **Context breakdown bar:** The reference image shows a colored bar (User 26.1%, Assistant 17.6%, Tool Calls 55.7%). The API doesn't expose per-role token counts. For v1 we skip the breakdown bar. Future: fetch messages via `GET /session/:id/message` and count per-role character lengths as a rough approximation, or request the API to expose per-role token breakdowns.

2. **Sidebar tab persistence:** The active tab state is stored in a `remember` in `ChatScreen.kt`, not persisted across IDE restarts. Each restart defaults to the Sessions tab. This is consistent with how other UI state (sidebar visibility) is already persisted in `OpenCodeSettingsState`.

3. **Indicator placement:** **Decision: Option (a)** — the circle goes at the end of the selector row, after `ThinkingSelector` and before `Spacer(weight=1f)`. This keeps all controls in one row and avoids adding vertical space. The indicator is right-aligned with `modifier = Modifier.align(Alignment.End)`.

4. **Streaming state:** **Decision: Option (a)** — show the last known values from `SessionContext` with a subtle pulsing animation. The pulse is driven by `viewModel.isStreaming` (a separate StateFlow that's always accurate), not by a stale `isStreaming` field inside `SessionContext`. This gives the user a "live updating" signal without inaccurate data.

5. **Multi-tab sidebar and AGENTS.md:** AGENTS.md lists "Multi-Tab Sessions" as a deferred v2 feature. **This feature does NOT defer it.** The sidebar tab infrastructure (SidebarTab enum, tab row composable) introduced here is the foundation for the v2 multi-session tab architecture — not a temporary workaround. The Context tab is the second tab alongside Sessions, and the tab row component is designed to be extensible: additional tabs (e.g., per-session tabs in v2) can be added without restructuring. AGENTS.md should be updated to reflect that tab infrastructure is now in progress.

6. **`SseEvent.MessageComplete` existence:** The current SSE parser does NOT emit `SseEvent.MessageComplete`. Before implementation, verify: (a) does the OpenCode server actually send a `message.complete` event type? (b) what is the exact event type string? (c) does it include `sessionId` and `role` fields? If the event doesn't exist, fall back to `SseEvent.Stop` + 500ms debounce.

7. **`GET /session/:id` existence and shape:** The existing `OpenCodeClient` has `listSessions()` but no individual `getSession()`. Before implementation, verify: (a) does `GET /session/:id` exist on the server? (b) does it return the same schema as the list endpoint, or richer data? (c) confirm exact field paths for `tokens.input`, `tokens.cache.read`, `time.created`, `cost`.

---

## Document History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-04 | — | Initial draft |
| 2026-06-04 | — | Revised based on adversarial review round 1: sealed state class, race condition fix, cache tokens in total, MessageComplete trigger, model-from-server, sidebar auto-expand, accessibility, resolved open questions |
| 2026-06-04 | — | Multi-tab sidebar is NOT deferred — this feature builds the tab infrastructure that v2 multi-session tabs will extend |
| 2026-06-04 | — | Revised based on adversarial review round 2: atomic sessionId capture + stale check, SSE event scoping to current session + assistant role filter, 30s fetch timeout, contextFetchJob cancelled in resetSessionState() and close(), onRetry callback on ContextIndicator, sidebar width 260→320dp on Context tab, 28dp indicator size, API field name verification warning, MessageComplete parser existence warning, empty models list fallback, overflow indicator for >100%, title fallback to "Untitled", CancellationException re-throw, 20 test scenarios |