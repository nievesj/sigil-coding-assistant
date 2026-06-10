# Technical Design Document: Sidebar Session Pagination & Management

> **Status:** Draft
> **Last Updated:** 2026-06-09
> **Related docs:** [chat-sessions-sidebar.md](chat-sessions-sidebar.md)

---

## 1. TL;DR

The sidebar currently loads all sessions in a single `GET /session` call, which becomes slow and cluttered for users with hundreds of sessions. This change adds client-side pagination: show the 10 most recent sessions on init, display "X of Y loaded" with a "Load more" button that appends 10 more, and a settings toggle to allow loading all sessions (with a performance warning). A "Clear all sessions" action (with confirmation dialog) deletes every session except the currently active one.

---

## 2. Context & Scope

### 2.1 Current State

`SessionManager.loadSessions()` calls `OpenCodeClient.listSessions(directory)` which returns **all** sessions from the server in one shot. The results are sorted by `updatedAt` desc and stored in `SessionListState.Loaded(sessions, selectedId)`. The sidebar's `SessionSidebar` composable filters to `topLevelSessions` (sessions with `parentID == null`) and passes them to `SessionList`, which renders every session in a `LazyColumn` with tree expansion for parent/child sessions.

**Current `SessionListState.Loaded` (no pagination fields):**
```kotlin
// ChatModels.kt:233 — CURRENT
data class Loaded(val sessions: List<SessionItem>, val selectedId: String?) : SessionListState
```

**Current `SessionItem` fields:**
```kotlin
// ChatModels.kt:222 — CURRENT
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,      // epoch millis (NOT a nested `time` object)
    val cost: Double,
    val inputTokens: Long,
    val outputTokens: Long,
    val parentID: String? = null,
)
```

The OpenCode server's `GET /session` endpoint **does** support pagination parameters (`limit`, `start`, `search`, `scope`, `path`, `roots`). However, this TDD proposes client-side pagination for the following reasons:

1. **The plugin needs all sessions in memory anyway** — `SessionManager` builds `_childSessionMap` (parent→children mapping) from the full list. Server-side pagination would require multiple round-trips to build the tree.
2. **`limit` is count-based, not offset-based** — the `start` parameter is a cursor, making it harder to implement "load more" append semantics.
3. **Session counts are low-to-moderate** — typical users have 10–100 sessions. Fetching all is fast; the bottleneck is visual clutter, not network time.
4. **Simplicity** — client-side pagination avoids state synchronization between server cursor and client display limit.

The existing `OpenCodeClient.listSessions(directory)` does **not** pass `limit`/`start` — it fetches all sessions. This is intentional.

> **Additional API note:** `GET /session/{sessionID}/children` exists and returns child sessions for a parent. This could enable lazy child loading in the future but is out of scope for this TDD.

**`loadSessions()` call sites** (critical for pagination design — see §4.1):
- `OpenCodeService.initialize()` (line 124) — initial load on tool window open (calls `sessionManager.loadSessions()`)
- `SessionManager.archiveSession()` (line 310) — after deleting a single session
- `ChatViewModel` line 105 — after every `StreamingCompleted` signal (every chat response)
- `ChatViewModel` line 144 — on `SessionCreated` SSE signal (new session created)
- `ChatScreen.kt` line 316 — on retry from error state

### 2.2 Problem Statement

Users with many sessions (100+) experience:
1. **Slow initial load** — fetching and sorting all sessions takes noticeable time
2. **Cluttered sidebar** — only the most recent sessions are relevant, but all are shown

> **Note on memory:** All `SessionItem` objects are already held in the `StateFlow` regardless of pagination. For 1000 sessions at ~200 bytes each, that's ~200KB — negligible. This TDD does **not** claim to reduce memory; it addresses load time and visual clutter. The `LazyColumn` already virtualizes rendering, so UI cost is constant regardless of total count.

---

## 3. Goals & Non-Goals

### Goals
- Show only the 10 most recent sessions on initial sidebar open
- Display "X of Y sessions loaded" status text below the session list
- "Load more" button appends 10 additional sessions per click
- Settings toggle: "Load all sessions" (default off) with a performance warning tooltip
- "Clear all sessions" action (with confirmation dialog) that deletes every session except the current one
- Preserve existing behavior when "Load all sessions" is enabled (shows everything, no pagination)
- **Preserve `displayLimit` across `loadSessions()` calls** — user's "load more" progress must not reset when sessions refresh (see §4.1)

### Non-Goals
- Server-side pagination (the API supports `limit`/`start` but client-side is simpler for our use case — see §2.1)
- Infinite scroll / lazy loading on scroll (button-based is simpler and sufficient)
- Session search/filtering (separate feature)
- Batch delete API (will delete one-by-one via existing `DELETE /session/:id`)
- Reducing in-memory `SessionItem` count (all sessions remain in `StateFlow`; memory is negligible)

---

## 4. Proposed Solution

**Fetch all sessions from the server (unchanged API), but paginate the display client-side.** The `SessionListState.Loaded` model gains a `displayLimit` field. The sidebar renders only the first `displayLimit` top-level sessions and shows a footer with count + "Load more" button. The "Load all sessions" setting sets `displayLimit = Int.MAX_VALUE`, bypassing pagination entirely. "Clear all sessions" iterates all non-active sessions and calls `deleteSession()` on each, then refreshes.

### 4.1 Critical Design Decision: `displayLimit` Persistence

**Problem:** `loadSessions()` is called from many places — after every streaming response, on session creation, on archive, and on retry. If `displayLimit` resets to `DEFAULT_DISPLAY_LIMIT` on every `loadSessions()` call, the user's "load more" progress is wiped constantly. For example: a user loads 30 sessions, sends a chat message, `StreamingCompleted` triggers `loadSessions()`, and the sidebar snaps back to showing 10.

**Solution: High-water mark pattern.** `SessionManager` stores `_displayLimit` as a separate `MutableStateFlow<Int>`. On `loadSessions()`, the new `Loaded` is created with the **current** `_displayLimit` value (not reset). `loadMoreSessions()` increments `_displayLimit` and updates `Loaded.displayLimit` simultaneously. The limit only resets to `DEFAULT_DISPLAY_LIMIT` on explicit user actions: initial load (first `loadSessions()` after `initialize()`) and session switch.

```kotlin
// In SessionManager
private val _displayLimit = MutableStateFlow(DEFAULT_DISPLAY_LIMIT)

suspend fun loadSessions(directory: String? = projectBasePath) {
    // ... existing fetch logic (unchanged) ...

    val loadAll = OpenCodeSettingsState.getInstance().loadAllSessions
    val limit = if (loadAll) Int.MAX_VALUE else _displayLimit.value

    _sessionListState.value = SessionListState.Loaded(
        sessions = allItems,
        selectedId = currentId,
        displayLimit = limit,
    )
    // ... existing childSessionMap logic (unchanged) ...
}

fun loadMoreSessions() {
    val current = _sessionListState.value
    if (current is SessionListState.Loaded) {
        val newLimit = current.displayLimit + DEFAULT_DISPLAY_LIMIT
        _displayLimit.value = newLimit
        _sessionListState.value = current.copy(displayLimit = newLimit)
    }
}

/** Reset display limit to default (called on initialize and session switch). */
fun resetDisplayLimit() {
    _displayLimit.value = DEFAULT_DISPLAY_LIMIT
}
```

**When `resetDisplayLimit()` is called:**
- `OpenCodeService.initialize()` — first load, fresh state. Insert `sessionManager.resetDisplayLimit()` before the `sessionManager.loadSessions()` call (line 124). Note: `SessionManager` itself has no `initialize()` method; initialization is coordinated by `OpenCodeService`.
- `SessionManager.switchSession()` — user clicked a different session, fresh view

**When `resetDisplayLimit()` is NOT called:**
- `archiveSession()` → `loadSessions()` — preserve user's loaded count
  - **Exception:** When the **active** session is archived, `archiveSession()` calls `switchSession(next)` which calls `resetDisplayLimit()`. This is correct UX — losing the active session resets the view.
- `StreamingCompleted` → `loadSessions()` — preserve user's loaded count
- `SessionCreated` → `loadSessions()` — preserve user's loaded count
- `switchSession()` failure — if `switchSession()` throws and restores `previousSessionId`, `resetDisplayLimit()` is NOT called (the catch block does not call it). The previous session's display limit is preserved.

### 4.2 API / Interface Design

No new server endpoints. The existing `GET /session` is called without `limit`/`start` — pagination is purely client-side.

| Method | Path | Purpose | Notes |
|--------|------|---------|-------|
| GET | `/session` | List all sessions | **No change** — still called without `limit`/`start`, returns all sessions |
| DELETE | `/session/:id` | Delete a session | Used by both archive and clear-all. Per docs: "permanently remove all associated data" — likely cascades to children |
| GET | `/session/:id/children` | Get child sessions | Available but unused — could enable lazy child loading in the future |

### 4.3 Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| UI | Jetpack Compose via Jewel | Existing sidebar pattern |
| State | `MutableStateFlow` in SessionManager | Existing pattern |
| Persistence | `OpenCodeSettingsState` (IntelliJ PersistentStateComponent) | Existing settings pattern |

### 4.4 Implementation Blueprint

#### 4.4.1 Data Models

**Modify `SessionListState.Loaded`** — add `displayLimit` field (new field marked with `// NEW`):

```kotlin
sealed interface SessionListState {
    data object Loading : SessionListState
    data class Loaded(
        val sessions: List<SessionItem>,    // ALL sessions from server (unchanged)
        val selectedId: String?,            // unchanged
        val displayLimit: Int = DEFAULT_DISPLAY_LIMIT,  // NEW — how many top-level sessions to show
    ) : SessionListState {
        /** Top-level sessions (no parentID) sorted by updatedAt desc.
         *  NOTE: Currently this filtering happens in SessionSidebar.kt:123 as a local val.
         *  Moving it here centralizes the logic and makes it available for pagination. */
        val topLevelSessions: List<SessionItem>
            get() = sessions.filter { it.parentID == null }

        /** Sessions currently visible to the UI (sliced to displayLimit). */
        val displayedSessions: List<SessionItem>
            get() = topLevelSessions.take(displayLimit)

        /** Whether more sessions can be loaded. */
        val hasMore: Boolean
            get() = displayedSessions.size < topLevelSessions.size

        companion object {
            const val DEFAULT_DISPLAY_LIMIT = 10
        }
    }
    data class Error(val message: String) : SessionListState
}
```

**Why `DEFAULT_DISPLAY_LIMIT = 10` (not 5):** 5 is too few for users who regularly switch between recent sessions. 10 shows the last ~day of active sessions for typical usage without requiring "Load more" clicks. 20 would show too many on small screens. 10 is a reasonable balance — adjustable via the "Load all sessions" toggle if needed.

**Why `topLevelSessions` moves from composable to `Loaded`:** The current `SessionSidebar.kt:123` filters `state.sessions.filter { it.parentID == null }` as a local variable. Moving this to `Loaded` as a computed property centralizes the logic and makes `displayedSessions` and `hasMore` derivable without duplicating the filter in the UI layer. The composable will be updated to use `state.displayedSessions` instead of its local `topLevelSessions`.

**⚠️ `createAndSwitchSession()` optimistic update:** `SessionManager.createAndSwitchSession()` optimistically prepends a new session to `_sessionListState` (line 198–203) before `loadSessions()` overwrites it with the server-sorted list. When `Loaded` gains the `displayLimit` field, this optimistic update must preserve the current `_displayLimit.value`:

```kotlin
// Inside createAndSwitchSession(), lines 198–203 — UPDATED:
val current = _sessionListState.value
if (current is SessionListState.Loaded) {
    _sessionListState.value = current.copy(
        sessions = listOf(newItem) + current.sessions,
        selectedId = session.id,
        displayLimit = _displayLimit.value  // MUST preserve high-water mark
    )
}
```

Without `displayLimit` in the copy, the optimistic update creates a `Loaded` with the default `displayLimit = 10`, causing a one-frame flicker of the list shrinking to 10 before `loadSessions()` restores the correct limit.

**Add to `OpenCodeSettingsState`:**

```kotlin
/** Whether to load all sessions at once (bypasses pagination). Shows performance warning. */
var loadAllSessions: Boolean = false
```

#### 4.4.2 Class & Interface Definitions

**SessionManager changes:**

```kotlin
class SessionManager(private val scope: CoroutineScope) {
    companion object {
        const val MAX_CACHED_SESSIONS = 10  // existing
    }

    // Existing fields unchanged:
    // _sessionListState, _childSessionMap, _activeSessionId, sessions, etc.

    // NEW: High-water mark for display limit (see §4.1)
    // Uses SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT as the canonical constant.
    private val _displayLimit = MutableStateFlow(SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT)

    /** Load sessions from server. Preserves current displayLimit (see §4.1). */
    suspend fun loadSessions(directory: String? = projectBasePath) {
        val c = client ?: return
        // ... existing fetch logic (unchanged) ...

        val allItems = sessionList
            .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
            .map { it.toSessionItem() }

        val loadAll = OpenCodeSettingsState.getInstance().loadAllSessions
        val limit = if (loadAll) Int.MAX_VALUE else _displayLimit.value

        _sessionListState.value = SessionListState.Loaded(
            sessions = allItems,
            selectedId = currentId,
            displayLimit = limit,
        )
        // ... existing childSessionMap logic (unchanged) ...
    }

    /** Increase display limit by DEFAULT_DISPLAY_LIMIT. Updates both _displayLimit
     *  (high-water mark) and Loaded.displayLimit (current state). */
    fun loadMoreSessions() {
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            val newLimit = current.displayLimit + SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT
            _displayLimit.value = newLimit
            _sessionListState.value = current.copy(displayLimit = newLimit)
        }
    }

    /** Reset display limit to default. Called on initialize() and switchSession(). */
    fun resetDisplayLimit() {
        _displayLimit.value = SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(displayLimit = SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT)
        }
    }

    // ── Integration points (to be added to existing methods) ──
    // In switchSession(), inside switchMutex.withLock block, AFTER _activeSessionId.value = targetSessionId:
    //   resetDisplayLimit()
    //   (only on success path — do NOT call if the catch block restores previousSessionId)
    // In OpenCodeService.initialize(), BEFORE sessionManager.loadSessions():
    //   sessionManager.resetDisplayLimit()

    /** Delete all sessions except the active one.
     *  Only deletes top-level sessions (parentID == null) — child sessions are
     *  expected to be cascade-deleted by the server ("permanently remove all
     *  associated data" per OpenAPI docs). This avoids wasteful child deletion
     *  and misleading failure counts when children are already gone.
     *  Shows confirmation dialog before proceeding (see §4.4.4).
     *  Shows progress indicator during deletion (see §4.4.4). */
    suspend fun clearAllSessions(): ClearAllResult {
        val c = client ?: return ClearAllResult.Failed("Client not initialized")
        val currentId = _activeSessionId.value
        val loaded = _sessionListState.value as? SessionListState.Loaded
            ?: return ClearAllResult.Failed("Sessions not loaded")

        // Filter to top-level sessions only — server cascades child deletion
        val toDelete = loaded.topLevelSessions.filter { it.id != currentId }
        if (toDelete.isEmpty()) return ClearAllResult.Success(0)

        var deleted = 0
        var failed = 0
        for (sessionItem in toDelete) {
            try {
                val ok = c.deleteSession(sessionItem.id)
                if (ok) {
                    // Remove from session cache (LinkedHashMap<String, SessionState>)
                    sessionsLock.withLock { sessions.remove(sessionItem.id) }?.close()
                    deleted++
                } else {
                    // deleteSuccess() catches HTTP errors and returns false — never throws.
                    // This includes 404 (already deleted by server cascade) and network errors.
                    failed++
                    logger.warn { "DELETE /session/${sessionItem.id} returned false during clear-all" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unexpected non-HTTP exception (e.g., serialization error)
                failed++
                logger.warn(e) { "Failed to delete session ${sessionItem.id} during clear-all" }
            }
        }
        loadSessions()
        return if (failed == 0) ClearAllResult.Success(deleted)
               else ClearAllResult.Partial(deleted, failed)
    }
}

/** Result of clearAllSessions(). */
sealed class ClearAllResult {
    data class Success(val count: Int) : ClearAllResult()
    data class Partial(val deleted: Int, val failed: Int) : ClearAllResult()
    data class Failed(val message: String) : ClearAllResult()
}
```

**Key design notes:**
- `clearAllSessions()` iterates `Loaded.topLevelSessions` (sessions with `parentID == null` only). The OpenAPI docs state `DELETE /session/:id` "permanently removes all associated data" — this implies server-side cascade to child sessions. Deleting only top-level sessions avoids wasteful child deletion and misleading `failed` counts when children are already cascade-deleted.
- **CRITICAL:** `OpenCodeClient.deleteSession()` → `deleteSuccess()` catches all exceptions and returns `false` — it never throws. The `clearAllSessions()` loop must check the boolean return value, not rely on try-catch for failure detection. Counting `false` returns as failures (not successes) is essential for accurate `ClearAllResult` reporting.
- `sessions.remove(sessionItem.id)?.close()` — `sessions` here is the `LinkedHashMap<String, SessionState>` cache (line 46), not the `List<SessionItem>` from `Loaded.sessions`. The variable name `sessionItem` (not `session`) disambiguates this.
- `clearAllSessions()` returns a `ClearAllResult` so the UI can show appropriate feedback (success, partial failure, or total failure).
- **Ownership:** `clearAllSessions()` is a `SessionManager` method (owns the session cache and client). The `ChatViewModel` wraps it with `ClearAllState` progress tracking: it calls `clearAllSessions()` in a coroutine, updates `_clearAllState` from `Idle` → `InProgress(deleted, total)` → `Done(result)`. The `SessionListFooter` composable observes `clearAllState` and disables buttons + shows progress when `InProgress`.

#### 4.4.3 SessionSidebar UI Changes

**`SessionSidebar` composable** — replace the local `topLevelSessions` filter with `state.displayedSessions`:

```kotlin
// SessionSidebar.kt — CURRENT (line 118-132):
is SessionListState.Loaded -> {
    if (state.sessions.isEmpty()) {
        EmptyContent()
    } else {
        val topLevelSessions = state.sessions.filter { it.parentID == null }
        SessionList(
            sessions = topLevelSessions,
            selectedId = state.selectedId,
            onSessionSelected = onSessionSelected,
            onSessionArchived = onSessionArchived,
            streamingSessionIds = streamingSessionIds,
            pendingCreationSessionIds = pendingCreationSessionIds,
            listState = listState,
        )
    }
}

// SessionSidebar.kt — NEW:
is SessionListState.Loaded -> {
    if (state.sessions.isEmpty()) {
        EmptyContent()
    } else {
        SessionList(
            sessions = state.displayedSessions,       // sliced to displayLimit
            totalCount = state.topLevelSessions.size,  // total for "X of Y" text
            hasMore = state.hasMore,
            selectedId = state.selectedId,
            onSessionSelected = onSessionSelected,
            onSessionArchived = onSessionArchived,
            onLoadMore = onLoadMore,                   // NEW callback
            onClearAll = onClearAll,                   // NEW callback
            streamingSessionIds = streamingSessionIds,
            pendingCreationSessionIds = pendingCreationSessionIds,
            listState = listState,
        )
    }
}
```

**`SessionList` composable** — wraps LazyColumn + fixed footer in a Column:

```kotlin
@Composable
private fun SessionList(
    sessions: List<SessionItem>,         // state.displayedSessions (already sliced)
    totalCount: Int,                     // state.topLevelSessions.size
    hasMore: Boolean,                    // state.hasMore
    selectedId: String?,
    onSessionSelected: (String) -> Unit,
    onSessionArchived: (String) -> Unit,
    onLoadMore: () -> Unit,             // NEW
    onClearAll: () -> Unit,             // NEW
    streamingSessionIds: Set<String> = emptySet(),
    pendingCreationSessionIds: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
) {
    val fullTree = remember(sessions) { buildSessionTree(sessions) }
    // ... existing tree expansion logic (unchanged) ...

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            items(
                count = visibleItems.size,
                key = { visibleItems[it].session.id },
            ) { index ->
                // ... existing SessionRow rendering (unchanged) ...
            }
        }

        // Fixed footer at bottom of sidebar — always visible, no scrolling required
        if (totalCount > 0) {
            SessionListFooter(
                visibleCount = sessions.size,
                totalCount = totalCount,
                hasMore = hasMore,
                onLoadMore = onLoadMore,
                onClearAll = onClearAll,
            )
        }
    }
}
```

**`SessionListFooter` composable:**

> **Jewel API note:** Jewel's `OutlinedButton` uses `ButtonStyle` (via `JewelTheme.outlinedButtonStyle`), **not** Material's `ButtonDefaults.outlinedButtonColors()`. To change content color, use `LocalContentColor`. For error-colored text, use `retrieveColorOrUnspecified("Component.errorFocusColor")`. For dim/secondary text, use `retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.6f)`.

```kotlin
@Composable
private fun SessionListFooter(
    visibleCount: Int,
    totalCount: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // "X of Y sessions loaded" — or "All Y sessions loaded" when complete
        Text(
            text = if (hasMore) "$visibleCount of $totalCount sessions loaded"
                   else "All $totalCount sessions loaded",
            fontSize = 11.sp,
            color = retrieveColorOrUnspecified("Panel.foreground").copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // "Load more" button — always visible, disabled when no more to load
            OutlinedButton(
                onClick = onLoadMore,
                enabled = hasMore,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (hasMore) "Load more" else "All loaded")
            }

            // "Clear all" button — only when there are sessions to clear beyond the active one
            if (totalCount > 1) {
                CompositionLocalProvider(
                    LocalContentColor provides retrieveColorOrUnspecified("Component.errorFocusColor")
                ) {
                    OutlinedButton(
                        onClick = onClearAll,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear all")
                    }
                }
            }
        }
    }
}
```

**Footer placement rationale:** The footer is a fixed composable below the `LazyColumn` (not a scrollable `LazyColumn` item). This ensures the "Load more" and "Clear all" buttons are always visible at the bottom of the sidebar regardless of how many sessions are displayed — the user never has to scroll to find them. The `LazyColumn` takes `Modifier.weight(1f)` so it fills available space above the footer.

#### 4.4.4 Clear All — Confirmation Dialog & Progress Feedback

**Confirmation dialog (required — destructive action):**

Before executing `clearAllSessions()`, show a modal dialog:

```
┌─────────────────────────────────────────────┐
│  Clear All Sessions                          │
│                                              │
│  Delete N sessions? The active session will  │
│  be kept. This cannot be undone.             │
│                                              │
│          [Cancel]    [Delete N sessions]      │
└─────────────────────────────────────────────┘
```

Implementation: Use a Compose `Dialog` (not Swing `Messages.showOkCancelDialog()` — the entire UI is Compose, mixing Swing dialogs would be inconsistent). The "Delete N sessions" button uses `LocalContentColor provides retrieveColorOrUnspecified("Component.errorFocusColor")` styling, same as the "Clear all" button in the footer.

**Progress feedback during deletion:**

While `clearAllSessions()` runs, the "Clear all" button is disabled and shows a spinner. The footer text updates to "Deleting X of Y..." during the operation.

**Ownership pattern:** `clearAllSessions()` is a `SessionManager` method. The `ChatViewModel` wraps it with progress tracking:

```kotlin
// In ChatViewModel
private val _clearAllState = MutableStateFlow<ClearAllState>(ClearAllState.Idle)
val clearAllState: StateFlow<ClearAllState> = _clearAllState.asStateFlow()

sealed class ClearAllState {
    data object Idle : ClearAllState()
    data class InProgress(val deleted: Int, val total: Int) : ClearAllState()
    data class Done(val result: ClearAllResult) : ClearAllState()
}

// ChatViewModel method that wraps SessionManager.clearAllSessions()
fun clearAllSessions() {
    scope.launch {
        val loaded = sessionListState.value as? SessionListState.Loaded ?: return@launch
        val totalCount = loaded.topLevelSessions.count { it.id != service.sessionId }
        _clearAllState.value = ClearAllState.InProgress(0, totalCount)
        val result = service.clearAllSessions()  // delegates to SessionManager
        _clearAllState.value = ClearAllState.Done(result)
        // Reset to Idle after a short delay so the UI can show the result
        delay(2000)
        _clearAllState.value = ClearAllState.Idle
    }
}
```

The `SessionListFooter` observes `clearAllState` and disables buttons + shows progress when `InProgress`. When `Done`, it shows the result briefly before resetting.

**State lifecycle:** `ClearAllState` resets to `Idle` automatically after 2 seconds. It also resets on session switch (via `resetSessionState()` in the ViewModel). It does NOT reset on sidebar tab change — if the user switches to Context tab and back, the progress/result is still visible.

**Alternative considered:** Show a background task with progress bar in the IDE status bar. Rejected — the operation is scoped to the sidebar and completes in seconds, not minutes. Inline feedback is sufficient.

#### 4.4.5 Settings Panel Changes

Add a checkbox with warning tooltip to `OpenCodeSettingsPanel`:

```kotlin
// In OpenCodeSettingsPanel — add alongside existing fields (binaryPathField, portField, etc.)
val loadAllSessionsCheckbox: JCheckBox = JCheckBox("Load all sessions at startup").apply {
    toolTipText = "Warning: Loading all sessions at once may cause slow startup " +
        "with 100+ sessions. Default loads 10 most recent."
}

// In resetFormFromState():
loadAllSessionsCheckbox.isSelected = settings.loadAllSessions

// In applyStateToSettings():
settings.loadAllSessions = loadAllSessionsCheckbox.isSelected

// In isModified():
loadAllSessionsCheckbox.isSelected != settings.loadAllSessions
```

**`OpenCodeSettingsState.loadState()` wiring (required for persistence):**

`OpenCodeSettingsState` is a `PersistentStateComponent` with an explicit field-by-field `loadState()` method. The new `loadAllSessions` field must be added to `loadState()` or it will always revert to `false` on IDE restart:

```kotlin
// In OpenCodeSettingsState.loadState(), add:
loadAllSessions = state.loadAllSessions
```

When the user enables "Load all sessions", the next `loadSessions()` call will use `Int.MAX_VALUE` as the display limit. The change takes effect immediately (no restart required) — `SessionManager` reads the setting on each `loadSessions()` call.

**Toggle behavior at runtime:**

| Scenario | Behavior |
|----------|----------|
| Enable "Load all sessions" when user has loaded 30 via "Load more" | Next `loadSessions()` uses `Int.MAX_VALUE` — all sessions shown immediately. `_displayLimit` high-water mark is unchanged (30) but irrelevant since `loadAll` overrides it. |
| Disable "Load all sessions" when all were shown | Next `loadSessions()` uses `_displayLimit.value` (the high-water mark, which is still 30 from before). Sidebar shows 30 sessions, not 10. This preserves the user's "load more" progress. |
| Disable "Load all sessions" when user never clicked "Load more" | `_displayLimit.value` is still `DEFAULT_DISPLAY_LIMIT` (10). Sidebar shows 10 sessions. |

---

## 5. Assumptions & Dependencies

- The OpenCode server `GET /session` endpoint supports `limit`/`start` parameters but the plugin intentionally fetches all sessions (see §2.1 for rationale)
- `SessionItem` data class remains unchanged (no new fields needed)
- Existing archive (single-session delete) behavior is unchanged
- The `deleteSession()` API can be called in rapid succession without rate limiting
- `DELETE /session/:id` "permanently removes all associated data" (per OpenAPI docs) — this likely cascades to child sessions server-side. `clearAllSessions()` filters to top-level sessions (`parentID == null`) to avoid wasteful child deletion and misleading failure counts (see §4.4.2)
- `SessionManager.MAX_CACHED_SESSIONS = 10` limits the `SessionState` cache (not the display list). A user who loads 30 sessions in the sidebar can still click any of them — if the `SessionState` was evicted from cache, `switchSession()` will re-fetch from the server. This is existing behavior and is not affected by pagination.

---

## 6. Alternatives Considered

**Alternative: Server-side pagination**
- *What it is:* Use the existing `limit` and `start` query parameters on `GET /session`
- *Why plausible:* True pagination, only fetches what's needed. The server already supports this.
- *Why rejected:* (1) The plugin needs all sessions in memory for `_childSessionMap` — server-side pagination would require multiple round-trips to build the parent→child tree. (2) `start` is cursor-based, making "load more" append semantics harder. (3) Session counts are low enough that fetching all is fast. (4) Client-side pagination is simpler to implement and reason about.

**Alternative: Infinite scroll**
- *What it is:* Automatically load more sessions when the user scrolls to the bottom of the list
- *Why plausible:* Smoother UX, no explicit button click needed
- *Why rejected:* More complex to implement with `LazyColumn` tree expansion. Button-based "Load more" is explicit, simple, and sufficient. Can be upgraded later if needed.

**Alternative: `displayLimit` on `Loaded` only (no high-water mark)**
- *What it is:* Store `displayLimit` only in `SessionListState.Loaded`, reset to default on every `loadSessions()` call
- *Why plausible:* Simpler — no separate `_displayLimit` StateFlow
- *Why rejected:* `loadSessions()` is called after every streaming response, on session creation, and on archive. Resetting `displayLimit` each time would wipe the user's "load more" progress constantly (see §4.1). The high-water mark pattern is necessary for acceptable UX.

---

## 7. Cross-Cutting Concerns

### 7.1 Performance & Scalability

- **Memory:** All `SessionItem` objects remain in the `StateFlow` (source of truth), but only `displayLimit` are rendered. `LazyColumn` already handles virtual rendering, so the UI cost is constant regardless of total count. The `SessionItem` list itself is negligible — for 1000 sessions at ~200 bytes each, that's ~200KB.
- **Clear-all performance:** Deleting N sessions requires N sequential `DELETE` calls. For 100 sessions at ~100ms each, that's ~10 seconds. The confirmation dialog sets expectations, and the progress indicator ("Deleting X of Y...") provides feedback. The "Clear all" button is disabled during deletion to prevent double-invocation.
- **`loadSessions()` frequency:** Called after every streaming response (to pick up new child sessions). This is existing behavior and is not changed by pagination. The high-water mark ensures `displayLimit` survives these refreshes.

### 7.2 Observability

- Log `loadSessions()` with `displayLimit` value for debugging
- Log `clearAllSessions()` with count of sessions to delete
- Log each individual delete failure during clear-all (don't abort on first failure)
- Log `loadMoreSessions()` with old and new `displayLimit` values

### 7.3 Migration / First-Run Experience

Users who currently see all sessions will suddenly see only 10. The "10 of 50 sessions loaded" status text makes the change discoverable. The "Load more" button is fixed at the bottom of the sidebar (always visible, no scrolling required). When `hasMore` is false, the button changes to a disabled "All loaded" state and the status text reads "All 50 sessions loaded". No additional notification or migration step is needed — the fixed footer is self-explanatory.

**Scope:** These changes only affect the **Sessions** tab in the sidebar. The Context and Review tabs are unchanged.

### 7.4 Interaction with `MAX_CACHED_SESSIONS`

`SessionManager.MAX_CACHED_SESSIONS = 10` limits the `LinkedHashMap<String, SessionState>` cache (session content, messages, signals). This is independent of the display list (`SessionListState.Loaded.sessions`). A user who loads 30 sessions in the sidebar can click any of them — if the `SessionState` was evicted from the cache, `switchSession()` re-fetches from the server. This is existing behavior and is not affected by pagination.

---

## 8. Testing Strategy

### 8.1 Test Location

Tests go in `src/test/kotlin/com/opencode/acp/chat/processor/SessionManagerTest.kt` (unit tests for `loadMoreSessions()`, `clearAllSessions()`, `resetDisplayLimit()`, high-water mark behavior) and `src/test/kotlin/com/opencode/acp/chat/model/SessionListStateTest.kt` (computed property tests for `displayedSessions`, `hasMore`, `topLevelSessions`).

### 8.2 Mocking Strategy

- **`OpenCodeClient`:** Create a test double that implements the `deleteSession()` and `listSessions()` methods. For `clearAllSessions()` tests, the double returns configurable `true`/`false` per session ID. For `loadSessions()` tests, it returns a pre-built `List<OpenCodeSession>`.
- **`OpenCodeSettingsState`:** Use `ApplicationManager.getApplication().getService()` in test setup, or mock the `getInstance()` call to return a test instance with known `loadAllSessions` value.
- **Coroutines:** Use `kotlinx.coroutines.test.runTest` for suspend function tests. `SessionManager` takes a `CoroutineScope` — pass `TestScope()` in tests.
- **Compose UI:** `SessionListFooter` and `SessionSidebar` changes are thin wiring — test via integration/manual verification rather than Compose unit tests (the composable logic is minimal; the real complexity is in `SessionManager`).

### 8.3 Key Scenarios

1. **Initial load shows 10 sessions** — open sidebar, verify only 10 most recent are displayed
2. **Load more increments by 10** — click "Load more", verify 20 total shown, button still enabled if >20 exist
3. **Load more becomes disabled when all loaded** — load all sessions, verify "Load more" button changes to disabled "All loaded", status reads "All N sessions loaded"
4. **Status text updates** — "10 of 50 loaded" → "20 of 50 loaded" after load more, → "All 50 loaded" when complete
5. **Display limit survives loadSessions()** — load 30 sessions, trigger `loadSessions()` (e.g., send a message), verify 30 still shown (not reset to 10)
6. **Display limit resets on initialize** — call `initialize()`, verify `displayLimit` returns to 10
7. **Display limit resets on switchSession** — switch to a different session, verify `displayLimit` returns to 10
8. **Load all sessions setting** — enable setting, verify all sessions shown immediately, "Load more" button shows disabled "All loaded"
9. **Clear all sessions** — with 3 sessions (1 active + 2 others), confirm dialog, verify only active remains
10. **Clear all with delete failure** — `deleteSession()` returns `false` for one session, verify `ClearAllResult.Partial` with correct `deleted`/`failed` counts, others still deleted
11. **Clear all with server-side cascade** — parent and child in session list; `clearAllSessions()` deletes only the parent (top-level); server cascades child deletion; verify `ClearAllResult.Success(1)` — child is NOT in the deletion list because we filter to `parentID == null`
12. **Clear all confirmation dialog** — verify dialog appears before deletion, cancel prevents deletion
13. **Archive still works** — single session archive (× button) unaffected by pagination
14. **Archive active session resets display limit** — archive the currently active session → `switchSession(next)` is called → `displayLimit` resets to 10
15. **Session tree still works** — child sessions under a parent still expand correctly within the paginated view (children of parents in the display slice)
16. **Settings persistence** — "Load all sessions" checkbox survives IDE restart (verified via `loadState()` wiring)
17. **CreateAndSwitchSession preserves display limit** — with displayLimit=30, create new session via `createAndSwitchSession()`, verify `loadSessions()` returns the new session within the 30-item display range
18. **Footer always visible** — footer is fixed at bottom of sidebar (not inside LazyColumn), visible without scrolling regardless of list length

---

## 9. Open Questions

*(None — all questions resolved.)*

> **Resolved:** "Load more" placement is fixed at the bottom of the sidebar (outside the `LazyColumn`). The `SessionList` composable wraps `LazyColumn` + `SessionListFooter` in a `Column`, with the footer always visible. The "Load more" button is disabled (greyed out, label "All loaded") when no more sessions are available.

---

## 10. Document History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-09 | — | Initial draft |
| 2026-06-09 | — | Critical review fixes: high-water mark for displayLimit persistence (§4.1), confirmation dialog as requirement (§4.4.4), progress feedback (§4.4.4), fixed SessionItem field references, removed memory claim from problem statement, added loadSessions() call-site analysis, added migration section, fixed section numbering, added ClearAllResult return type, added MAX_CACHED_SESSIONS interaction, added test file paths, justified DEFAULT_DISPLAY_LIMIT=10 |
| 2026-06-09 | — | Second review fixes: fixed `clearAllSessions()` to check `deleteSession()` boolean return (never throws), moved SessionListFooter from LazyColumn item to fixed composable below LazyColumn, added `loadAllSessions` to `loadState()` wiring, added `createAndSwitchSession` optimistic update `displayLimit` note, added `resetDisplayLimit()` integration points into `switchSession()`, noted `archiveSession` active-session-archived exception, mentioned sidebar tabs scope, resolved Open Question #1 (sticky footer), removed `DEFAULT_DISPLAY_LIMIT` duplication, added 3 new test scenarios |
| 2026-06-09 | — | Third review (research-backed): corrected `GET /session` — server **does** support `limit`/`start` params; justified client-side pagination with 4 reasons (§2.1). Fixed `SessionManager.initialize()` → `OpenCodeService.initialize()` (§4.1, §4.4.2). Fixed Jewel API — `OutlinedButton` uses `ButtonStyle` not Material `ButtonDefaults`; replaced `Theme.colorScheme.error` with `retrieveColorOrUnspecified("Component.errorFocusColor")` + `LocalContentColor` (§4.4.3, §4.4.4). Filtered `clearAllSessions()` to top-level sessions only since server cascades child deletion (§4.4.2). Clarified `ClearAllState` ownership between SessionManager and ViewModel (§4.4.4). Added `resetDisplayLimit()` error path note for `switchSession()` (§4.1). Added `loadAllSessions` toggle runtime behavior table (§4.4.5). Added `GET /session/{sessionID}/children` endpoint to API table (§4.2). Committed to Compose dialog over Swing `Messages` (§4.4.4). Added mocking strategy to test section (§8.2). Updated test scenario #11 for top-level-only filtering. |
