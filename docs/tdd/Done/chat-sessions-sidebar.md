# Technical Design Document: Chat Sessions Sidebar

> **Status:** Draft v4
> **Last Updated:** 2026-06-03
> **Related docs:** [OpenCode Server API](https://opencode.ai/docs/server/)

---

## 1. TL;DR

Add a collapsible sidebar to the chat tool window that lists all OpenCode sessions with metadata (cost, tokens, message count), allows switching between them, creating new sessions, and archiving (deleting) sessions. The sidebar is toggled via a chevron button in a header row above the message list. Sidebar open/closed state persists across tool window reopens via `OpenCodeSettingsState`. Session switching loads message history from the server via `listMessages()` and re-subscribes the SSE stream.

---

## 2. Context & Scope

### 2.1 Current State

The plugin currently creates a single ephemeral session on tool window open (`ChatViewModel.initialize()` → `opencodeClient.createSession()`). There is no way to:
- View past sessions
- Switch between sessions
- Create additional sessions
- Delete old sessions

The OpenCode server already supports all these operations via REST API (`GET /session`, `POST /session`, `DELETE /session/:id`, etc.), and `OpenCodeClient` already has wrapper methods for them (`listSessions()`, `createSession()`, `deleteSession()`, `listMessages()`). However, `listMessages()` is currently **never called** anywhere in the codebase — the ViewModel relies entirely on SSE events to populate messages.

The tool window header currently shows "OpenCode Chat" (from `plugin.xml` `id` attribute) and a "Chat" tab label (from `addComposeTab("Chat")` in `ChatToolWindowFactory.kt:26`).

### 2.2 Problem Statement

Users cannot manage or revisit previous chat sessions. Each tool window open creates a new session with no way to return to prior conversations. The single-session design wastes context and forces users to restart from scratch.

---

## 3. Goals & Non-Goals

### Goals
- Display a list of all OpenCode sessions in a collapsible sidebar with metadata (cost, tokens)
- Allow switching between sessions (loads message history from server)
- Allow creating new sessions from the sidebar
- Allow archiving (deleting) sessions with a button per session
- Toggle sidebar visibility via a header chevron button
- Persist sidebar open/closed state across tool window reopens
- Change the tab label from "Chat" to "OpenCode"

### Non-Goals
- Session editing/renaming (can be added later)
- Session search/filtering (can be added later)
- Session forking or sharing (available via API, not in scope for initial UI)
- Multi-window session sync

---

## 4. Proposed Solution

**Replace the current single-column `ChatScreen` layout with a `Row` containing a collapsible sidebar and the main chat area. The sidebar contains a session list fetched from the OpenCode server, with actions to create, switch, and archive sessions. A header row with a toggle chevron sits above the message area.**

### 4.3 API / Interface Design

The following OpenCode server endpoints are already implemented in `OpenCodeClient`:

| Method | Path | Purpose | Client Method | Notes |
|--------|------|---------|---------------|-------|
| GET | `/session` | List all sessions | `listSessions()` | Returns `List<OpenCodeSession>` |
| POST | `/session` | Create new session | `createSession(title?)` | Returns `OpenCodeSession` |
| DELETE | `/session/:id` | Delete session | `deleteSession(id)` | Returns `Boolean` |
| GET | `/session/:id/message` | Load messages | `listMessages(id, limit?)` | Returns `List<OpenCodeMessage>` |

**Pre-existing bug to fix:** `OpenCodeClient.listMessages()` (line 337–346) does **not** check `response.status.isSuccess()` — a 404 or 500 will throw a serialization exception instead of a meaningful HTTP error. Refactor to use the existing `getJson()` helper which already handles HTTP status checks.

No new API endpoints are needed.

### 4.5 Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| UI | Jetpack Compose via Jewel bridge | Existing pattern in codebase |
| State | `MutableStateFlow` in ViewModel | Existing pattern |
| Networking | Ktor HttpClient via `OpenCodeClient` | Existing client |

### 4.7 Implementation Blueprint

#### 4.7.1 Data Models

```kotlin
// New display model for session list items
data class SessionItem(
    val id: String,
    val title: String,
    val createdAt: Long,      // from OpenCodeSession.time.created (epoch millis)
    val cost: Double,         // from OpenCodeSession.cost (USD)
    val inputTokens: Long,    // from OpenCodeSession.tokens.input
    val outputTokens: Long    // from OpenCodeSession.tokens.output
)

// Sidebar state — sealed to distinguish loading/error/loaded
sealed interface SessionListState {
    data object Loading : SessionListState
    data class Loaded(val sessions: List<SessionItem>, val selectedId: String?) : SessionListState
    data class Error(val message: String) : SessionListState
}

// Extension to convert API model to display model
fun OpenCodeSession.toSessionItem() = SessionItem(
    id = id,
    title = title.ifBlank { "New session" },
    createdAt = time?.created ?: 0L,
    cost = cost,
    inputTokens = tokens?.input ?: 0L,
    outputTokens = tokens?.output ?: 0L
)

// Extension to convert API message DTO to UI model
// This is a NEW function — does not currently exist in the codebase.
fun OpenCodeMessage.toChatMessage(): ChatMessage {
    val role = when (info.role) {
        "user" -> MessageRole.USER
        "system" -> MessageRole.ASSISTANT  // render system messages as assistant
        else -> MessageRole.ASSISTANT
    }

    // Check for errors — surface failed messages (skip if error info is blank)
    val errorSuffix = info.error?.let { err ->
        val retries = err.retries?.let { " ($it retries)" } ?: ""
        val description = err.message?.ifBlank { null } ?: err.name.ifBlank { null }
        description?.let { "\n\n**Error${retries}:** $it" }
    } ?: ""

    // Concatenate all Text parts into a single content string.
    // File parts are noted as attachments (images/files cannot be rehydrated
    // from history — this is a known fidelity loss).
    val textParts = parts.filterIsInstance<OpenCodePart.Text>()
    val fileParts = parts.filterIsInstance<OpenCodePart.File>()
    val textContent = textParts.joinToString("") { it.text }
    val fileNote = if (fileParts.isNotEmpty()) {
        val names = fileParts.mapNotNull { it.filename }.ifEmpty { listOf("${fileParts.size} file(s)") }
        "\n\n📎 ${names.joinToString(", ")}"
    } else ""
    val content = textContent + fileNote + errorSuffix

    // Build ToolCallPill list from ToolUse parts, matching with ToolResult parts.
    // Use groupBy (not associateBy) to handle multiple ToolResults per toolUseId.
    val toolUseParts = parts.filterIsInstance<OpenCodePart.ToolUse>()
    val toolResultsByUseId = parts.filterIsInstance<OpenCodePart.ToolResult>()
        .groupBy { it.toolUseId }

    val toolCalls = toolUseParts.map { toolUse ->
        val results = toolResultsByUseId[toolUse.id]
        val hasResult = results != null && results.isNotEmpty()
        val anyError = results?.any { it.isError } == true
        val status = when {
            !hasResult -> ToolCallStatus.COMPLETED  // no result = completed (historical)
            anyError -> ToolCallStatus.FAILED
            else -> ToolCallStatus.COMPLETED
        }
        ToolCallPill(
            toolCallId = toolUse.id,
            toolName = toolUse.name,
            title = toolUse.name,  // no title in historical data, use tool name
            kind = ToolKind.OTHER,
            status = status
        )
    }

    // Parse createdAt timestamp — try ISO-8601 first, fall back to epoch millis
    val timestamp = info.createdAt?.let { raw ->
        try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            raw.toLongOrNull() ?: 0L  // try as epoch millis string
        }
    } ?: 0L

    return ChatMessage(
        id = info.id,
        role = role,
        content = content,
        timestamp = timestamp,
        toolCalls = toolCalls,
        thinkingContent = "",   // thinking is not persisted in OpenCodeMessage
        isStreaming = false      // historical messages are never streaming
    )
}
```

No schema changes needed — sessions are stored server-side.

#### 4.7.2 Class & Interface Definitions

**ChatViewModel additions:**

```kotlin
// New state flows
private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

private val _isSidebarVisible = MutableStateFlow(
    OpenCodeSettingsState.getInstance().sidebarVisible
)
val isSidebarVisible: StateFlow<Boolean> = _isSidebarVisible.asStateFlow()

// Mutex to prevent concurrent session switches.
// **NOT reentrant** — do NOT call switchSession() or createAndSwitchSession()
// from code that already holds this mutex. Use the inline reset pattern instead.
private val switchMutex = Mutex()

// New methods
suspend fun loadSessions()                          // GET /session → populate _sessionListState
fun toggleSidebar()                                 // toggle _isSidebarVisible + persist
suspend fun createAndSwitchSession(title: String?)  // POST /session → switch to new
suspend fun switchSession(sessionId: String)        // load messages, update active
suspend fun archiveSession(sessionId: String)       // DELETE /session/:id, refresh list
```

**New composable: `SessionSidebar`**

```kotlin
@Composable
fun SessionSidebar(
    state: SessionListState,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onArchive: (String) -> Unit,
    modifier: Modifier = Modifier
)
// Note: selectedId is derived from SessionListState.Loaded.selectedId —
// no separate parameter to avoid inconsistency.
```

**New composable: `ChatHeader`**

```kotlin
@Composable
fun ChatHeader(
    isSidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Revised `ChatScreen` layout:**

```kotlin
// Current layout (before):
Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ConnectionBanner(...)
        MessageList(weight(1f), ...)
        PermissionPrompt(...)
        InputArea(...)
    }
    // Image preview overlay (centered)
    previewImageUri?.let { ... }
}

// New layout (after):
Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            // Sidebar — animated horizontal expand/collapse, explicit width
            AnimatedVisibility(
                visible = isSidebarVisible,
                enter = expandHorizontally(expandFrom = Alignment.Start),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start)
            ) {
                SessionSidebar(
                    state = state,
                    onSessionClick = onSessionClick,
                    onNewSession = onNewSession,
                    onArchive = onArchive,
                    modifier = Modifier.width(SIDEBAR_WIDTH_DP.dp)
                )
            }
            // Main chat area
            Column(modifier = Modifier.weight(1f)) {
                ChatHeader(isSidebarVisible, onToggleSidebar)
                ConnectionBanner(...)  // below header, above messages
                MessageList(weight(1f), ...)
            }
        }
        // Bottom section spans full width (including sidebar)
        PermissionPrompt(...)
        InputArea(...)
    }
    // Image preview overlay (centered on entire plugin window)
    previewImageUri?.let { ... }
}
```

Key layout decisions:
- Root is `Box` (preserves image preview overlay covering the full window)
- `ChatHeader` is **inside** the main chat column, not spanning the full width
- `ConnectionBanner` is inside the main chat column, below `ChatHeader`, above `MessageList`
- `PermissionPrompt` and `InputArea` span **full width** (below both sidebar and chat)
- `AnimatedVisibility` uses **`expandHorizontally`/`shrinkHorizontally`** (NOT the default vertical animation)
- `SessionSidebar` has **explicit `Modifier.width(260.dp)`** so `expandHorizontally` has a measurable target

**Tab label change:**

```kotlin
// ChatToolWindowFactory.kt line 26 — change "Chat" to "OpenCode"
toolWindow.addComposeTab("OpenCode") {
    ChatScreen(viewModel, project)
}
```

**Sidebar state persistence in `OpenCodeSettingsState`:**

```kotlin
// Add to OpenCodeSettingsState.kt:
var sidebarVisible: Boolean = true  // default: sidebar open
```

```kotlin
// ChatViewModel — toggleSidebar() persists the new state
fun toggleSidebar() {
    val newValue = !_isSidebarVisible.value
    _isSidebarVisible.value = newValue
    OpenCodeSettingsState.getInstance().sidebarVisible = newValue
}
```

#### 4.7.3 Function Signatures

**Shared SSE subscription helper — eliminates duplication:**

```kotlin
/**
 * Starts an SSE subscription for the given session, filtering events by sessionId.
 * Returns the Job so the caller can cancel it.
 *
 * IMPORTANT: The sessionId is captured as an immutable local variable to prevent
 * the filter closure from reading a mutated `this.sessionId` if another switch happens.
 */
private fun startSseSubscription(client: OpenCodeClient, targetSessionId: String): Job {
    val capturedId = targetSessionId  // immutable local for filter closure
    return scope.launch {
        client.subscribeGlobalEvents()
            .filter { event -> event.sessionId == capturedId }
            .collect { event -> handleSseEvent(event) }
    }
}
```

**State reset protocol — `resetSessionState()` helper:**

```kotlin
private fun resetSessionState() {
    // 1. Messages — bulk replace (must rebuild index after)
    _messages.value = emptyList()

    // 2. Index maps — clear before rebuilding
    messageIndex.clear()
    toolCallIndex.clear()

    // 3. Streaming state
    _isStreaming.value = false
    activeAssistantMessageId = null
    firstTextChunkReceived = false

    // 4. Deferred/async
    responseDeferred?.complete(Unit)
    responseDeferred = null

    // 5. Permission state
    _permissionPrompt.value = null
    permissionTimeoutJob?.cancel()
    permissionTimeoutJob = null

    // 6. User context
    lastUserText = null
}
```

**`switchSession()` — full implementation:**

```kotlin
suspend fun switchSession(targetSessionId: String) {
    switchMutex.withLock {
        val client = openCodeClient ?: return

        // Skip if already on this session
        if (sessionId == targetSessionId) return

        // Save the old session ID so we can revert on failure
        val previousSessionId = sessionId

        try {
            // 1. Cancel any in-flight SSE subscription
            sseJob?.cancel()
            sseJob = null

            // 2. Cancel any in-flight response (inside try-catch for network errors)
            try { cancel() } catch (_: Exception) { /* best-effort */ }

            // 3. Reset all session-specific state
            resetSessionState()

            // 4. Load message history from server BEFORE updating sessionId.
            //    If this fails, we stay on the previous session.
            val messages = client.listMessages(targetSessionId, limit = null)

            // 5. NOW update the active session ID (load succeeded)
            sessionId = targetSessionId

            // 6. Convert DTOs to UI model and rebuild index
            val chatMessages = messages.map { it.toChatMessage() }
            _messages.value = chatMessages
            chatMessages.forEachIndexed { i, msg -> messageIndex[msg.id] = i }
            // Rebuild toolCallIndex from loaded history for SSE ToolResult routing
            chatMessages.forEach { msg ->
                msg.toolCalls.forEach { pill -> toolCallIndex[pill.toolCallId] = msg.id }
            }

            // 7. Re-subscribe SSE for the new session
            sseJob = startSseSubscription(client, targetSessionId)

            // 8. Update session list selection state
            updateSessionSelection(targetSessionId)

            logger.info { "Switched to session $targetSessionId (${chatMessages.size} messages loaded)" }
        } catch (e: CancellationException) {
            throw e  // never swallow cancellation
        } catch (e: Exception) {
            logger.error(e) { "Failed to switch session $targetSessionId" }
            // Revert to previous session — state was already reset, so reload it
            sessionId = previousSessionId
            updateSessionSelection(previousSessionId)
            if (previousSessionId != null) {
                try {
                    val msgs = client.listMessages(previousSessionId, limit = null)
                    val chatMsgs = msgs.map { it.toChatMessage() }
                    _messages.value = chatMsgs
                    chatMsgs.forEachIndexed { i, msg -> messageIndex[msg.id] = i }
                    // Rebuild toolCallIndex from loaded history
                    chatMsgs.forEach { msg ->
                        msg.toolCalls.forEach { pill -> toolCallIndex[pill.toolCallId] = msg.id }
                    }
                    sseJob = startSseSubscription(client, previousSessionId)
                } catch (_: Exception) {
                    // Revert failed too — leave empty state, user can create new session
                }
            }
        }
    }
}
```

**`loadSessions()`:**

```kotlin
suspend fun loadSessions() {
    val client = openCodeClient ?: return
    try {
        val sessionList = client.listSessions()
        // Note: sessionId read is outside switchMutex — a concurrent switch could
        // change sessionId between this read and the state update. This is acceptable:
        // worst case, the selected highlight is briefly stale and gets corrected on
        // the next loadSessions() call.
        val currentId = sessionId
        // TODO: Filter by current project's projectID when multi-project support is added.
        // Currently shows all sessions across all projects.
        val items = sessionList
            .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
            .map { it.toSessionItem() }
        _sessionListState.value = SessionListState.Loaded(
            sessions = items,
            selectedId = currentId
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to load sessions" }
        _sessionListState.value = SessionListState.Error(
            e.message ?: "Failed to load sessions"
        )
    }
}
```

**`createAndSwitchSession()` — inline reset (no nested mutex):**

```kotlin
suspend fun createAndSwitchSession(title: String? = null) {
    val client = openCodeClient ?: return

    // Create session on server (outside mutex — no shared state involved)
    val session = try {
        client.createSession(title)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Failed to create session" }
        return  // don't leave the user stranded — current session is still valid
    }

    // Switch to the new session (acquire mutex for state mutation)
    switchMutex.withLock {
        try { cancel() } catch (_: Exception) { /* best-effort */ }
        sseJob?.cancel()
        sseJob = null
        resetSessionState()
        sessionId = session.id

        // New session has no messages — empty state is correct
        sseJob = startSseSubscription(client, session.id)
    }

    // Refresh list outside mutex
    loadSessions()
    logger.info { "Created and switched to new session: ${session.id}" }
}
```

**`archiveSession()` — no nested mutex, no deadlock:**

```kotlin
suspend fun archiveSession(targetSessionId: String) {
    val client = openCodeClient ?: return
    try {
        val success = client.deleteSession(targetSessionId)
        if (!success) {
            logger.warn { "Server returned false for deleteSession($targetSessionId)" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Failed to archive session $targetSessionId" }
        try { loadSessions() } catch (e2: Exception) { logger.warn(e2) { "Also failed to refresh sessions" } }
        return
    }

    // If we deleted the active session, create a new one.
    // createAndSwitchSession() acquires its own mutex — no deadlock.
    if (targetSessionId == sessionId) {
        val previousSessionId = sessionId
        createAndSwitchSession()
        // Check if createAndSwitchSession() succeeded — if sessionId is still
        // pointing to the deleted session, creation failed. Set to null and
        // update UI to reflect the stranded state.
        if (sessionId == previousSessionId) {
            logger.error { "Failed to create replacement session after archiving active session" }
            sessionId = null
            resetSessionState()
            updateSessionSelection(null)
        }
        logger.info { "Archived active session $targetSessionId, switched to new session $sessionId" }
    } else {
        loadSessions()
        logger.info { "Archived session $targetSessionId" }
    }
}
```

**`initialize()` behavior change:**

Currently, `initialize()` always creates a new session. With session management, it should instead:

```kotlin
// At the end of initialize(), AFTER health check + agent/model loading:

// Load existing sessions instead of always creating a new one
loadSessions()

val existingSessions = (_sessionListState.value as? SessionListState.Loaded)?.sessions
if (!existingSessions.isNullOrEmpty()) {
    // Resume the most recent session
    switchSession(existingSessions.first().id)
    // If switchSession failed, sessionId will still be null — create new
    if (sessionId == null) {
        createAndSwitchSession()
    }
} else {
    // No sessions exist — create a new one
    createAndSwitchSession()
}

// Final check: if we still have no active session, set error state
// instead of falsely reporting CONNECTED.
if (sessionId == null) {
    _connectionState.value = ConnectionState.ERROR
    logger.error { "Failed to establish an active session" }
}
```

**`updateSessionSelection()` — local update without network call:**

```kotlin
private fun updateSessionSelection(selectedId: String?) {
    val current = _sessionListState.value
    if (current is SessionListState.Loaded) {
        _sessionListState.value = current.copy(selectedId = selectedId)
    }
}
```

**Pre-existing fix: refactor `OpenCodeClient.listMessages()` to use `getJson()`:**

```kotlin
// In OpenCodeClient.kt, replace the manual implementation with:
suspend fun listMessages(sessionId: String, limit: Int? = null): List<OpenCodeMessage> {
    val url = buildString {
        append("/session/$sessionId/message")
        if (limit != null) append("?limit=$limit")
    }
    return getJson(url)  // uses existing getJson() which checks response.status.isSuccess()
}
```

#### 4.7.5 Enums, Constants & Configuration

```kotlin
// Sidebar layout constants (add to ChatConstants.kt)
const val SIDEBAR_WIDTH_DP = 260     // dp, fixed width for session sidebar
```

---

## 5. Assumptions & Dependencies

- The OpenCode server is running and accessible (health check passes)
- `OpenCodeClient.listSessions()` returns all sessions (we sort client-side by `time.created` descending)
- Session titles are auto-generated by the server if not provided (fallback to "New session")
- The `DELETE /session/:id` endpoint truly deletes (not soft-delete) — confirmed by server API docs
- `OpenCodeMessage` may have zero `Text` parts (e.g., file-only messages) — `toChatMessage()` handles this with empty content
- `info.createdAt` could be ISO-8601 or epoch millis string — `toChatMessage()` tries both formats
- `SessionTime.created` is always epoch millis (`Long`) — confirmed by `OpenCodeModels.kt`

---

## 6. Alternatives Considered

**Alternative: Dropdown/session picker instead of sidebar**
- *What it is:* A dropdown in the header to select sessions, similar to model picker
- *Why plausible:* Less UI space, simpler implementation
- *Why rejected:* Doesn't show enough session metadata, harder to archive from dropdown, doesn't match the requested design (sidebar with list)

**Alternative: Tab-based sessions**
- *What it is:* Each session gets its own tab in the tool window
- *Why plausible:* Familiar IDE pattern
- *Why rejected:* IntelliJ tool window tabs are managed by the platform, not composable; fighting the platform for custom tab behavior is fragile

**Alternative: Singleton SSE flow with `shareIn`**
- *What it is:* Use `subscribeGlobalEvents().shareIn(scope, SharingStarted.WhileSubscribed())` to create a single shared SSE connection that multiple collectors can filter from
- *Why plausible:* Avoids creating new HTTP connections per session switch; more efficient
- *Why rejected (for now):* `SseEventListener.kt` already has an unused `shareIn` pattern. However, adding `shareIn` introduces lifecycle complexity (When does the upstream stop? How do we handle the last collector unsubscribing?). The per-switch approach is simpler and correct — SSE connections are lightweight. Can be optimized later if connection churn becomes a problem.

---

## 7. Cross-Cutting Concerns

### 7.2 Reliability & Availability

- If `loadSessions()` fails (server down), show `SessionListState.Error` — don't block the chat
- If `archiveSession()` fails, keep the session in the list, refresh list from server, log error
- SSE re-subscription: the `subscribeGlobalEvents()` `callbackFlow` has **no reconnection logic** (known gap, tracked in AGENTS.md as `ConnectionState.RECONNECTING`). This TDD does not add reconnection — it's a separate concern.
- If `switchSession()` fails (network error loading messages), revert to previous session — don't corrupt state

### 7.4 Observability

- Log session switch events at INFO level (with message count loaded)
- Log session archive events at INFO level
- Log session creation at INFO level
- Log sidebar toggle at DEBUG level

---

## 8. Testing Strategy

### 8.2 Key Scenarios

1. **Sidebar toggle:** Click chevron → sidebar appears/disappears with horizontal animation, chat area fills the space, state persisted
2. **Sidebar persistence:** Close and reopen tool window → sidebar state restored from `OpenCodeSettingsState`
3. **Session list load:** On connect, sidebar shows all sessions from server, sorted by most recently updated first, current session highlighted, with cost/tokens metadata
4. **Create new session:** Click "New session" → new session created, becomes active, list refreshes, message area empty
5. **Switch session:** Click a session → messages load from server, active indicator updates, SSE re-subscribes, old state cleared, `toolCallIndex` rebuilt from history
6. **Switch fails (network error):** `listMessages()` throws → revert to previous session, messages reloaded, sidebar selection reverted
7. **Archive session:** Click trash icon → session deleted, list refreshes; if active session was deleted, new session created automatically
8. **Archive active session — creation fails:** Delete active session, server down → `sessionId` set to null, `ConnectionState.ERROR`, user can retry
9. **Archive while streaming:** Archive active session while it's streaming → streaming cancelled first, then delete + create new
10. **Empty state:** No sessions → show "New session" button prominently
11. **Error state:** Server unreachable → sidebar shows error message, chat still works on current session
12. **Rapid session switching:** Click 3 sessions quickly → mutex serializes them, only the last one's messages are loaded, no stale state
13. **Switch to same session:** Click already-selected session → no-op (no network call, no state reset)
14. **Server returns empty messages:** Switch to session with 0 messages → empty state shown, no crash
15. **Failed message in history:** Switch to session with a message that has `info.error` set → error displayed in message content
16. **History with file attachments:** Switch to session with `OpenCodePart.File` parts → filenames shown as attachment note
17. **Initialize with no sessions + creation fails:** `createAndSwitchSession()` fails → `ConnectionState.ERROR`, not false CONNECTED
18. **Image preview after session switch:** Image overlay still covers full window including sidebar area

---

## 9. Deployment & Rollout Plan

> **Omitted** per Mini TDD guidelines.

---

## 10. Open Questions

1. ~~Should archived sessions be recoverable?~~ Server hard-deletes — confirmed by API docs.
2. ~~Should the sidebar remember its open/closed state?~~ Yes — persist in `OpenCodeSettingsState`.
3. ~~What should the session title default to?~~ "New session" — confirmed.
4. ~~Should we show session metadata?~~ Yes — show cost and tokens per session.
5. ~~Tab label change?~~ Yes — change from "Chat" to "OpenCode".

---

## 11. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| SSE connection leak on rapid switching | Multiple HTTP SSE connections open | `sseJob?.cancel()` cancels the collector coroutine, which triggers `awaitClose` → cancels the Ktor SSE request. Verified in `OpenCodeClient.kt:430-467`. |
| Stale `messageIndex` after switch | `IndexOutOfBoundsException` in `appendTextToMessage` | `resetSessionState()` clears `messageIndex` and `toolCallIndex` before loading new messages. Rebuild index after bulk replace. |
| `toolCallIndex` leakage across sessions | Tool results routed to wrong messages | `resetSessionState()` clears `toolCallIndex`. Rebuilt from loaded history after each switch. |
| `firstTextChunkReceived` session-poisoned | Echo-strip logic misfires on new session | `resetSessionState()` resets to `false`. |
| Permission timeout orphaned on switch | Timeout fires in wrong session | `resetSessionState()` cancels `permissionTimeoutJob`. |
| `cancel()` + `sendMessage` race during switch | Truncated message shown as complete | `switchMutex` prevents concurrent switches. `cancel()` completes `responseDeferred` before state reset. |
| `deleteSession()` returns false silently | User thinks session was deleted but it wasn't | Check return value, log warning, refresh list from server anyway. |
| `listMessages()` throws on HTTP error | Serialization exception instead of meaningful error | Refactor to use `getJson()` which already handles HTTP status. |
| `archiveSession()` deadlock | `switchMutex` is non-reentrant | `archiveSession()` does NOT hold the mutex. It calls `createAndSwitchSession()` which acquires its own lock. No nested locking. |
| `switchSession()` fails mid-way | `sessionId` points to wrong session, empty messages | Set `sessionId` AFTER `listMessages()` succeeds. On failure, revert to `previousSessionId` and reload. |
| `cancel()` throws in `switchSession()` | Partial state mutation, exception escapes | `cancel()` is wrapped in its own `try { } catch (_: Exception) {}` inside the main try block. |
| `toChatMessage()` timestamp parse failure | All messages show timestamp 0, sort incorrectly | Try ISO-8601 first, fall back to epoch millis string. Catch defaults to 0L. |
| Archive active session + creation fails | `sessionId` points to deleted session, user stranded | Check if `sessionId` changed after `createAndSwitchSession()`. If not, set to null and `ConnectionState.ERROR`. |
| `initialize()` false CONNECTED state | UI shows connected but no active session | Final check: if `sessionId == null` after all attempts, set `ConnectionState.ERROR`. |
| `cancel()` inside mutex causes UI freeze | Sidebar unresponsive during network abort | `cancel()` is best-effort and wrapped in try-catch. Documented as a known limitation — `abortSession()` is a network call. If latency is high, the mutex blocks all switches. |
| Historical file parts lost | File attachments show as empty in history | `toChatMessage()` appends `📎 filename1, filename2` note for `OpenCodePart.File` parts. Images cannot be rehydrated (known fidelity loss). |

---

## 12. Document History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-03 | — | Initial draft |
| 2026-06-03 | — | v2: Addressed adversarial review findings — added state reset protocol, `toChatMessage()` mapping, `switchMutex`, `SessionListState` sealed class, `initialize()` behavior change, SSE filter fix (captured local), `listMessages()` error handling fix, explicit layout tree |
| 2026-06-03 | — | v3: Addressed second adversarial review + user decisions. Fixed: `archiveSession()` deadlock (no nested mutex), `sessionId` ordering (set after `listMessages` succeeds, revert on failure), `cancel()` inside try-catch, `ConnectionBanner` placement in layout, `AnimatedVisibility` horizontal animation, `toChatMessage()` error handling + duplicate ToolResult handling + timestamp dual-format parsing, extracted `startSseSubscription()` helper, refactored `listMessages()` to use `getJson()`. Added: sidebar state persistence in `OpenCodeSettingsState`, session metadata (cost, tokens) in sidebar, tab label changed to "OpenCode", `limit = null` for full history. |
| 2026-06-03 | — | v4: Final adversarial review fixes. Fixed: `archiveSession()` handles `createAndSwitchSession()` failure (sets `sessionId=null` + `ERROR` state), `initialize()` prevents false `CONNECTED` when no session exists, `switchSession()` revert path calls `updateSessionSelection()`, `toolCallIndex` rebuilt from loaded history, `toChatMessage()` handles `OpenCodePart.File` (📎 note), blank error suffix skipped, `updateSessionSelection()` accepts `null`. Layout: `Box` root preserved for image overlay, `SessionSidebar` has explicit `Modifier.width(260.dp)`, removed redundant `selectedSessionId` param. Sort by `time?.updated` (not `created`). Added project-level filtering TODO. |
