package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodeSession
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.chat.model.*
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.processor.BackgroundCompactor
import com.opencode.acp.chat.processor.BackgroundCompactorSettings
import com.opencode.acp.chat.processor.BreakdownComputer
import com.opencode.acp.chat.processor.ContextPressureMonitor
import com.opencode.acp.chat.processor.FileReadCache
import com.opencode.acp.chat.processor.ToolOutputTruncator
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/**
 * Owns all per-session state. Routes SSE events to the correct [SessionState].
 * Manages session lifecycle (create, switch, archive, list).
 * Replaces both the old MessageProcessorManager and the old SessionManager.
 *
 * Thread safety: Uses ReentrantLock (not Mutex) for sessions map access because
 * getActiveSession() is called from non-suspend functions. The lock is held briefly
 * (O(1) map operations) and won't cause coroutine starvation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionManager(
    private val scope: CoroutineScope,
    private val project: Project,
) {

    private val logger = KotlinLogging.logger {}

    companion object {
        /** Maximum number of sessions to keep in memory. LRU eviction. */
        const val MAX_CACHED_SESSIONS = 10
    }

    // ── Per-Session State ──

    /** All cached session states. Keyed by session ID. */
    private val sessions = LinkedHashMap<String, SessionState>()
    private val sessionsLock = ReentrantLock()

    /** Volatile snapshot of sessions map values, updated on every put/remove.
     *  Used by close() when the lock is held — allows cleanup without blocking EDT. */
    @Volatile
    private var sessionsSnapshot: List<SessionState> = emptyList()

    /** Mutex to serialize session switches. */
    private val switchMutex = Mutex()

    /** In-flight guard for full computeSessionContext (REST path). Prevents stacking
     *  concurrent full computations. Local-only refreshes use [localComputeInFlight] instead
     *  so they are not blocked by a slow REST call. */
    private val fullComputeInFlight = AtomicBoolean(false)

    /** In-flight guard for computeSessionContextLocal (no REST). Local refreshes are
     *  cheap and should not be starved by a full computation blocked on getSession(). */
    private val localComputeInFlight = AtomicBoolean(false)

    /** The currently active session ID. Null if no session is active. */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Messages for the active session. UI observes this.
     *  Uses flatMapLatest on _activeSessionId so that when the active session
     *  changes, the upstream automatically switches to the new session's messages.
     *  CRITICAL: Uses SharingStarted.Eagerly — the ViewModel reads .value synchronously.
     *
     *  Closed sessions (evicted from cache) are filtered out so the UI doesn't
     *  display stale data after eviction. The SessionState's messages StateFlow
     *  retains its last value after close(), but the data is no longer live.
     *
     *  STALE-DATA WINDOW (resolved): flatMapLatest checks `!state.isClosed` at
     *  switch time. If the session is evicted AFTER the snapshot is taken, the
     *  flow continues collecting from a closed SessionState. Per StateFlow
     *  semantics, a closed SessionState's messages flow retains its last value,
     *  so the UI shows stale data briefly until the next _activeSessionId
     *  emission switches away. This is acceptable — the window is bounded by
     *  the next session switch and the data is read-only (no mutations occur on
     *  a closed SessionState). Documented stale-data window; flatMapLatest
     *  checks isClosed at switch time. */
    val activeMessages: StateFlow<Map<String, ChatMessage>> = _activeSessionId
        .flatMapLatest { id ->
            val state = sessionsLock.withLock { sessions[id] }
            if (state != null && !state.isClosed) state.messages else flowOf(emptyMap())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Signals for the active session. ViewModel collects this.
     *  Same flatMapLatest pattern as activeMessages.
     *  CRITICAL: Uses SharingStarted.Eagerly for the same reason.
     *
     *  STALE-DATA WINDOW: Same as activeMessages — see its doc above. */
    val activeSignals: SharedFlow<UiSignal> = _activeSessionId
        .flatMapLatest { id ->
            sessionsLock.withLock { sessions[id] }?.takeIf { !it.isClosed }?.signals ?: emptyFlow()
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Global signals for cross-session events (SessionCreated, etc.). */
    private val _globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 16)
    val globalSignals: SharedFlow<UiSignal> = _globalSignals.asSharedFlow()

    // ── Session List State (from old SessionManager) ──

    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    /** High-water mark for display limit — preserves user's "load more" progress across loadSessions() calls. */
    private val _displayLimit = MutableStateFlow(SessionListState.Loaded.DEFAULT_DISPLAY_LIMIT)

    private val _childSessionMap = MutableStateFlow<Map<String, List<SessionItem>>>(emptyMap())
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = _childSessionMap.asStateFlow()

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    /** Progress for clear-all operation — updated during clearAllSessions().
     *  Emits [ClearAllState.InProgress] during the deletion loop and resets to
     *  [ClearAllState.Idle] when the loop completes. UI observers (e.g. the
     *  ViewModel) can collect this to show "Deleting N of M..." feedback. */
    private val _clearAllProgress = MutableStateFlow<ClearAllState>(ClearAllState.Idle)
    val clearAllProgress: StateFlow<ClearAllState> = _clearAllProgress.asStateFlow()

    // ── Dependencies (injected by OpenCodeService) ──

    internal var client: OpenCodeClient? = null
        set(value) {
            field = value
            // Recreate the background compactor when the client becomes available
            if (value != null && backgroundCompactor == null) {
                try {
                    backgroundCompactor = createBackgroundCompactor()
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Failed to create BackgroundCompactor" }
                }
            }
        }

    /**
     * IDE project root directory.  Passed as `?directory=` query param on
     * session-list and session-create requests so the server resolves the
     * correct project and returns only that project's sessions.
     * `null` = no filter (server returns all sessions).
     * Set by [OpenCodeService.initialize].
     */
    internal var projectBasePath: String? = null

    // ── Smart Context Manager components ──

    /** Tracks context pressure via rolling growth rate. Reset on session switch/compaction. */
    internal val pressureMonitor = ContextPressureMonitor()

    /** Pre-computes compaction summaries in the background for instant swap. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    internal var backgroundCompactor: BackgroundCompactor? = null
        private set

    /** Session-scoped cache for detecting duplicate file reads (opt-in). */
    internal val fileReadCache = FileReadCache()

    /** Whether tool output truncation is enabled (from settings, read per-call). */
    private fun truncateToolOutputEnabled(): Boolean =
        OpenCodeSettingsState.getInstance().truncateToolOutput

    /** Tool output char limit (from settings, read per-call). */
    private fun toolOutputCharLimit(): Int =
        OpenCodeSettingsState.getInstance().toolOutputCharLimit

    /** Whether background compaction is enabled (from settings). */
    private fun backgroundCompactionEnabled(): Boolean =
        OpenCodeSettingsState.getInstance().enableBackgroundCompaction

    /** Creates or recreates the BackgroundCompactor with current settings. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    private fun createBackgroundCompactor(): BackgroundCompactor {
        val settings = OpenCodeSettingsState.getInstance()
        return BackgroundCompactor(
            client = client ?: throw IllegalStateException("Client not initialized"),
            settings = BackgroundCompactorSettings(
                enabled = settings.enableBackgroundCompaction,
                checkpointThresholdPercent = settings.checkpointThresholdPercent,
                swapThresholdPercent = settings.swapThresholdPercent,
                maxCheckpointAgeMs = com.opencode.acp.chat.model.CompactionConstants.MAX_CHECKPOINT_AGE_MS,
            ),
            scope = scope,
        )
    }

    // ── Global signal merging ──

    private val _allSessionSignals = MutableSharedFlow<Pair<String, UiSignal>>(extraBufferCapacity = 64)
    val allSessionSignals: Flow<Pair<String, UiSignal>> = _allSessionSignals.asSharedFlow()

    /** Emits session IDs when a new SessionState is added to the cache.
     *  Used by ToolPill to detect when a child session becomes available.
     *  replay=1 ensures the last cached session ID is available to late subscribers. */
    private val _sessionCachedFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val sessionCachedFlow: SharedFlow<String> = _sessionCachedFlow.asSharedFlow()

    /** Session IDs that are currently streaming (generation in progress).
     *  Updated reactively from allSessionSignals — StreamingStarted adds, StreamingCompleted removes. */
    private val _streamingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val streamingSessionIds: StateFlow<Set<String>> = _streamingSessionIds.asStateFlow()

    /** Session IDs that are being created on the server (optimistic placeholder).
     *  Shows a spinner in the sidebar until loadSessions() returns the real data. */
    private val _pendingCreationSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingCreationSessionIds: StateFlow<Set<String>> = _pendingCreationSessionIds.asStateFlow()

    // ── Child Session Ephemeral State ──────────────────────────────────

    /** Completed child session IDs to hide from the sidebar.
     *  Populated by [markChildSessionComplete] on StreamingCompleted/SessionIdle.
     *  Pruned by [loadSessions] to remove IDs for sessions the server has deleted. */
    private val _hiddenChildSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenChildSessionIds: StateFlow<Set<String>> = _hiddenChildSessionIds.asStateFlow()

    /** All known child session IDs, populated independently of [_childSessionMap].
     *  Fixes the fast-completion race: a child created, streamed, and completed
     *  before loadSessions() refreshes _childSessionMap is still recognized via
     *  this set (populated on session creation and in loadSessions()). */
    private val _knownChildSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val knownChildSessionIds: StateFlow<Set<String>> = _knownChildSessionIds.asStateFlow()

    /** Reverse index: child session ID → parent session ID. O(1) child→parent lookup.
     *  Populated from [loadSessions] (snapshot). Real-time population from Subtask
     *  SSE events is handled inline in [processEvent] (agent label only — parent
     *  is unknown from the Subtask event payload). */
    private val _childToParent = MutableStateFlow<Map<String, String>>(emptyMap())
    val childToParent: StateFlow<Map<String, String>> = _childToParent.asStateFlow()

    /** Child session agent labels: child session ID → agent name (e.g., "fixer", "explorer").
     *  Populated inline in [processEvent] from Subtask SSE events. Used by
     *  ChildPermissionRelay for sub-agent labels. */
    private val _childAgentLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val childAgentLabels: StateFlow<Map<String, String>> = _childAgentLabels.asStateFlow()

    /** Child sessions with pending permissions — prevents cache eviction. */
    private val _childPendingPermissions = MutableStateFlow<Set<String>>(emptySet())
    val childPendingPermissions: StateFlow<Set<String>> = _childPendingPermissions.asStateFlow()

    /** Mark a completed child session for hiding from the sidebar.
     *  Skips the active/selected session — it stays visible until the user switches away. */
    fun markChildSessionComplete(sessionId: String) {
        if (sessionId == _activeSessionId.value) return
        _hiddenChildSessionIds.update { it + sessionId }
        logger.debug { "[ACP] Child session $sessionId completed — marking for sidebar removal" }
    }

    /** Un-hide a child session (e.g., when switching to it via ToolPill "open child"). */
    fun unhideChildSession(sessionId: String) {
        _hiddenChildSessionIds.update { it - sessionId }
    }

    /** Find the parent session ID for a given child session ID.
     *  O(1) lookup via _childToParent reverse index.
     *  Falls back to scanning _childSessionMap if reverse index is stale. */
    fun getParentSession(childSessionId: String): String? {
        return _childToParent.value[childSessionId]
            ?: _childSessionMap.value.entries.firstOrNull { (_, children) ->
                children.any { it.id == childSessionId }
            }?.key
    }

    /** Get the agent label for a child session (e.g., "fixer", "explorer"). */
    fun getChildAgentLabel(childSessionId: String): String? =
        _childAgentLabels.value[childSessionId]

    /** Mark a child session as having a pending permission. Prevents cache eviction. */
    fun markChildPendingPermission(childSessionId: String) {
        _childPendingPermissions.update { it + childSessionId }
    }

    /** Clear a child session's pending permission flag. */
    fun clearChildPendingPermission(childSessionId: String) {
        _childPendingPermissions.update { it - childSessionId }
    }

    internal fun emitSessionSignal(sessionId: String, signal: UiSignal) {
        _allSessionSignals.tryEmit(sessionId to signal)
    }

    /** Emit a global error signal to surface critical errors to the UI.
     *  Used by OpenCodeService's CoroutineExceptionHandler to prevent
     *  fail-silent behavior when background coroutines crash. */
    internal fun emitGlobalError(errorMessage: String) {
        // Use a synthetic session ID so the ViewModel's SessionError handler
        // can process it without matching a specific session.
        _globalSignals.tryEmit(UiSignal.SessionError("__internal__", errorMessage))
    }

    /** Emit a global signal (e.g., ChildPermissionRequested) to be collected by the ViewModel. */
    internal fun emitGlobalSignal(signal: UiSignal) {
        _globalSignals.tryEmit(signal)
    }

    /** Imperatively add a session ID to the streaming set.
     *  Used by ChatViewModel.sendMessage() to activate the sidebar shimmer
     *  immediately on send, before any SSE events arrive.
     *  Idempotent — duplicate adds are harmless (Set semantics). */
    fun addStreamingSession(sessionId: String) {
        _streamingSessionIds.update { it + sessionId }
    }

    /** Imperatively remove a session ID from the streaming set.
     *  Used by ChatViewModel on cancel/switch/error to deactivate the shimmer
     *  immediately, without waiting for StreamingCompleted.
     *  Idempotent — duplicate removes are harmless. */
    fun removeStreamingSession(sessionId: String) {
        _streamingSessionIds.update { it - sessionId }
    }

    init {
        // Track streaming session IDs from all session signals,
        // and auto-hide completed child sessions.
        scope.launch {
            allSessionSignals.collect { (sessionId, signal) ->
                try {
                    when (signal) {
                        is UiSignal.StreamingStarted -> {
                            _streamingSessionIds.update { it + sessionId }
                            // If this session isn't in the session list yet (e.g., a new
                            // subtask/child session that just started streaming), refresh
                            // the list so the sidebar can discover and auto-expand the parent.
                            val currentList = _sessionListState.value
                            if (currentList is SessionListState.Loaded) {
                                val isInList = currentList.sessions.any { it.id == sessionId }
                                if (!isInList) {
                                    scope.launch { loadSessions() }
                                }
                            }
                        }
                        is UiSignal.StreamingCompleted -> {
                            _streamingSessionIds.update { it - sessionId }
                            // Auto-hide completed child sessions.
                            // Use _knownChildSessionIds (populated on session creation and in loadSessions())
                            // rather than _childSessionMap, which is only refreshed in loadSessions(). This
                            // avoids a race where a fast-completing child finishes before loadSessions() runs
                            // and _childSessionMap does not yet contain it (isChild would return false).
                            if (sessionId in _knownChildSessionIds.value) {
                                markChildSessionComplete(sessionId)
                            }
                        }
                        is UiSignal.SessionIdle -> {
                            // Fallback trigger — SessionIdle also indicates completion
                            // (the new_message path doesn't always emit StreamingCompleted).
                            // Remove from streaming set (same as StreamingCompleted — SessionIdle
                            // is a completion signal and the new_message path may not emit StreamingCompleted).
                            _streamingSessionIds.update { it - sessionId }
                            // Set add is idempotent, so redundant calls are harmless.
                            if (sessionId in _knownChildSessionIds.value) {
                                markChildSessionComplete(sessionId)
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] SessionManager: error processing signal for session $sessionId" }
                }
            }
        }
    }

    // ── Session Lifecycle ──

    /**
     * Switch the active session. Does NOT destroy any session's state.
     * Serialized by switchMutex to prevent concurrent switches.
     */
    suspend fun switchSession(targetSessionId: String) {
        switchMutex.withLock {
            if (_activeSessionId.value == targetSessionId) return

            val previousSessionId = _activeSessionId.value

            try {
                ensureSessionCached(targetSessionId)
                _activeSessionId.value = targetSessionId
                resetDisplayLimit()
                updateSessionSelection(targetSessionId)
                logger.info { "[ACP] Switched to session $targetSessionId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to switch session $targetSessionId" }
                _activeSessionId.value = previousSessionId
                updateSessionSelection(previousSessionId)
                return
            }

            // Post-switch operations — failures here don't roll back the switch.
            // The session is active; context/todos will refresh on the next signal.
            try {
                resetSmartContextState()
                computeSessionContext()
                fetchTodos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] Failed to refresh context/todos after switching to $targetSessionId" }
            }
        }
    }

    /** Create a new session on the server and switch to it. */
    suspend fun createAndSwitchSession(title: String? = null) {
        val c = client ?: return
        val session = try {
            c.createSession(title, projectBasePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to create session" }
            return
        }

        // Optimistically add the new session to the list so it appears immediately
        // in the sidebar before loadSessions() completes the server round-trip.
        // Moved inside switchMutex to prevent concurrent createAndSwitchSession calls
        // from clobbering each other's optimistic list updates.
        val newItem = session.toSessionItem()

        switchMutex.withLock {
            _pendingCreationSessionIds.value = _pendingCreationSessionIds.value + session.id
            val current = _sessionListState.value
            if (current is SessionListState.Loaded) {
                _sessionListState.value = current.copy(
                    sessions = listOf(newItem) + current.sessions,
                    selectedId = session.id,
                    displayLimit = _displayLimit.value
                )
            }

            ensureSessionCached(session.id)
            _activeSessionId.value = session.id
            updateSessionSelection(session.id)
            resetSmartContextState()
            computeSessionContext()
            fetchTodos()
        }

        try {
            loadSessions()
        } finally {
            _pendingCreationSessionIds.value = _pendingCreationSessionIds.value - session.id
        }
        logger.info { "Created and switched to new session: ${session.id}" }
    }

    /** Load/reload the session list from the server.
     *  @param directory override the project directory filter.  Defaults to
     *    [projectBasePath].  Pass `null` to list all sessions (no filter).
     *    If the filtered list is empty or the request fails, falls back to
     *    unfiltered listing so the user always sees sessions. */
    suspend fun loadSessions(directory: String? = projectBasePath) {
        val c = client ?: run {
            logger.warn { "[ACP] SessionManager.loadSessions: client is null" }
            return
        }
        try {
            logger.info { "[ACP] SessionManager.loadSessions: fetching session list... (directory=$directory)" }
            var sessionList: List<OpenCodeSession>

            try {
                sessionList = c.listSessions(directory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Directory filter may not be supported or may have failed.
                // Fall back to unfiltered listing.
                if (directory != null) {
                    logger.warn(e) { "[ACP] SessionManager.loadSessions: directory filter failed, retrying without filter" }
                    sessionList = c.listSessions(null)
                } else {
                    throw e
                }
            }

            // Fallback: if directory filter returned empty, retry without filter
            // so the user always sees sessions (the filter path may not match
            // the server's project resolution).
            if (sessionList.isEmpty() && directory != null) {
                logger.warn { "[ACP] SessionManager.loadSessions: directory filter returned 0 sessions, retrying without filter (may show cross-project sessions)" }
                try {
                    val unfilteredList = c.listSessions(null)
                    // Filter to sessions whose directory matches the project base path
                    // to avoid leaking other projects' sessions on a shared server.
                    val currentProjectBase = projectBasePath
                    sessionList = if (currentProjectBase != null) {
                        val canonicalBase = try {
                            java.io.File(currentProjectBase).canonicalPath
                        } catch (_: Exception) { currentProjectBase }
                        val filtered = unfilteredList.filter { session ->
                            // Canonicalize the server-provided directory before
                            // prefix comparison to prevent path traversal bypass
                            // (e.g., "C:\Projects\MyApp\..\OtherProject" would
                            // pass a naive startsWith check but resolve to a
                            // different project).
                            val dir = session.directory
                            if (dir.isBlank()) {
                                false
                            } else {
                                val canonicalDir = try {
                                    java.io.File(dir).canonicalPath
                                } catch (_: Exception) { return@filter false }
                                canonicalDir == canonicalBase ||
                                    canonicalDir.startsWith(canonicalBase + java.io.File.separator)
                            }
                        }
                        if (filtered.isNotEmpty()) {
                            logger.info { "[ACP] SessionManager.loadSessions: filtered ${unfilteredList.size} → ${filtered.size} sessions matching project" }
                            filtered
                        } else {
                            // No matches after filtering — do NOT fall back to showing
                            // all sessions. On a shared server, the unfiltered list may
                            // contain sessions from other projects (data isolation leak).
                            // Keep the empty list and let the user see "No sessions" with
                            // the option to create a new session.
                            logger.warn { "[ACP] SessionManager.loadSessions: no sessions match project path ($currentProjectBase) — ${unfilteredList.size} unfiltered sessions exist but are not shown (cross-project leak prevented)" }
                            emptyList()
                        }
                    } else {
                        unfilteredList
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Already have empty list — keep it
                }
            }

            val currentId = _activeSessionId.value
            val items = sessionList
                .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                .map { it.toSessionItem() }
            val loadAll = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance().loadAllSessions
            val limit = if (loadAll) Int.MAX_VALUE else _displayLimit.value
            _sessionListState.value = SessionListState.Loaded(
                sessions = items,
                selectedId = currentId,
                displayLimit = limit
            )
            val children = items.filter { it.parentID != null }
                .groupBy { it.parentID!! }
            _childSessionMap.value = children

            // Populate _knownChildSessionIds independently of _childSessionMap
            // (fixes fast-completion race — see _knownChildSessionIds doc)
            _knownChildSessionIds.update { known ->
                known + items.filter { it.parentID != null }.map { it.id }.toSet()
            }

            // Populate _childToParent reverse index from session parentID fields
            _childToParent.update { existing ->
                val updated = existing.toMutableMap()
                for (item in items) {
                    item.parentID?.let { parentId ->
                        updated[item.id] = parentId
                    }
                }
                updated
            }

            // Prune _hiddenChildSessionIds: remove IDs for sessions the server has deleted,
            // preserve hidden state for sessions the server still knows about.
            val currentSessionIds = items.map { it.id }.toSet()
            _hiddenChildSessionIds.update { ids -> ids.filter { it in currentSessionIds }.toSet() }

            // Prune _knownChildSessionIds: remove IDs for sessions the server has deleted.
            // Without this, the set grows unbounded across the plugin's lifetime.
            _knownChildSessionIds.update { ids -> ids.filter { it in currentSessionIds }.toSet() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sessions" }
            _sessionListState.value = SessionListState.Error(e.message ?: "Failed to load sessions")
        }
    }

    /** Archive (delete) a session AND all of its descendants.
     *
     * The OpenCode server does NOT cascade-delete children when a parent is
     * deleted — without this recursive walk, child sessions would be orphaned
     * and reappear in the sidebar on the next [loadSessions] refresh.
     *
     * Deletion order: all descendants first (any order among siblings), then
     * the target session LAST. This prevents children from being orphaned
     * mid-loop (which would briefly make them top-level sessions).
     *
     * Per-id error handling: a failure on one session does NOT abort the rest.
     * Failures are counted and logged; the loop continues so a single server
     * error doesn't leave a partial tree behind. */
    suspend fun archiveSession(targetSessionId: String) {
        val c = client ?: return

        // Refresh the session list before collecting descendants. _childSessionMap
        // is only updated by loadSessions(), so children created via SSE auto-cache
        // since the last loadSessions() would be missed by collectDescendants().
        // This one extra HTTP call ensures the parent→child map is current.
        // Wrapped in try/catch so archive can proceed even if the session list
        // refresh fails — the descendants collection will be empty, but direct
        // deletion of the target still works.
        var sessionsLoaded = false
        try { loadSessions(); sessionsLoaded = true } catch (e: CancellationException) { throw e } catch (e: Exception) {
            logger.error(e) { "[ACP] archiveSession: loadSessions failed — child sessions may be orphaned if not already in _childSessionMap" }
        }
        if (!sessionsLoaded) {
            // Retry once — stale _childSessionMap can cause orphaned children
            try { loadSessions() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                logger.warn(e) { "[ACP] archiveSession: loadSessions retry also failed — proceeding with stale data" }
            }
        }

        val descendants = collectDescendants(targetSessionId)
        // Target is deleted LAST so children are never briefly orphaned as
        // top-level sessions between delete calls.
        val allIdsToDelete = descendants + targetSessionId
        logger.info { "[ACP] archiveSession: $targetSessionId + ${descendants.size} descendant(s)" }

        var failed = 0
        for (id in allIdsToDelete) {
            try {
                val success = c.deleteSession(id)
                if (!success) {
                    failed++
                    logger.warn { "Server returned false for deleteSession($id)" }
                } else {
                    // Evict from cache — close() outside lock (cancels coroutines, closes channels)
                    val evictedState = sessionsLock.withLock {
                        val removed = sessions.remove(id)
                        sessionsSnapshot = sessions.values.toList()
                        removed
                    }
                    evictedState?.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                logger.error(e) { "Failed to delete session $id during archiveSession($targetSessionId)" }
            }
        }

        // If the active session was the target or any of its descendants,
        // switch to another surviving session (or clear active if none remain).
        // Wrapped in try/catch because switchSession() can throw (ensureSessionCached
        // HTTP failure, computeSessionContext exception, etc.). loadSessions() must
        // always run to refresh the sidebar — it's in the finally block.
        try {
            if (_activeSessionId.value in allIdsToDelete) {
                val next = sessionsLock.withLock {
                    // Select the most recently used surviving session (by lastAccessTime)
                    // instead of arbitrary LinkedHashMap iteration order
                    sessions.entries
                        .filter { it.key !in allIdsToDelete }
                        .maxByOrNull { it.value.lastAccessTime }
                        ?.key
                }
                if (next != null && sessionsLock.withLock { sessions.containsKey(next) }) {
                    switchSession(next)
                } else {
                    _activeSessionId.value = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ACP] archiveSession: failed to switch active session after deleting $targetSessionId" }
            _activeSessionId.value = null
        } finally {
            loadSessions()
        }
        logger.info { "Archived session $targetSessionId (+${descendants.size} descendants, $failed failure(s))" }
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

    /** Delete all sessions except the active one. Deletes all sessions (including child sessions) — the server does NOT cascade-delete children. */
    suspend fun clearAllSessions(): ClearAllResult {
        val c = client ?: return ClearAllResult.Failed("Client not initialized")
        val currentId = _activeSessionId.value

        // Refresh the session list before collecting descendants. _childSessionMap
        // is only updated by loadSessions(), so children created via SSE auto-cache
        // since the last loadSessions() would be missed by collectDescendants().
        // This one extra HTTP call ensures the parent→child map is current.
        // Wrapped in try/catch so clear-all can proceed even if the session list
        // refresh fails — the descendants collection will be empty, but direct
        // deletion of top-level sessions still works.
        try { loadSessions() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            logger.error(e) { "[ACP] clearAllSessions: loadSessions failed — child sessions may be orphaned" }
        }

        // Re-read the loaded state AFTER loadSessions() so we use fresh data
        // (the state may have changed from the refresh above).
        val loaded = _sessionListState.value as? SessionListState.Loaded
            ?: return ClearAllResult.Failed("Sessions not loaded")

        val directDelete = loaded.sessions.filter { it.id != currentId }
        // Collect descendants for each session to prevent orphaning children
        // (the server does NOT cascade-delete children)
        val toDelete = mutableListOf<SessionItem>()
        // Build a map for O(1) lookup instead of O(n) find per descendant
        val sessionMap = loaded.sessions.associateBy { it.id }
        for (session in directDelete) {
            toDelete.add(session)
            val descendants = collectDescendants(session.id)
            for (descId in descendants) {
                sessionMap[descId]?.let { toDelete.add(it) }
            }
        }
        // Deduplicate in case a child appears both directly and as a descendant
        val seen = mutableSetOf<String>()
        val uniqueToDelete = toDelete.filter { seen.add(it.id) }
        if (uniqueToDelete.isEmpty()) return ClearAllResult.Success(0)

        var deleted = 0
        var failed = 0
        _clearAllProgress.value = ClearAllState.InProgress(0, uniqueToDelete.size)
        try {
            for (sessionItem in uniqueToDelete) {
                try {
                    val ok = c.deleteSession(sessionItem.id)
                    if (ok) {
                        // Remove from session cache
                        val evictedState = sessionsLock.withLock {
                            val removed = sessions.remove(sessionItem.id)
                            sessionsSnapshot = sessions.values.toList()
                            removed
                        }
                        evictedState?.close()
                        deleted++
                        _clearAllProgress.value = ClearAllState.InProgress(deleted, uniqueToDelete.size)
                    } else {
                        failed++
                        logger.warn { "DELETE /session/${sessionItem.id} returned false during clear-all" }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    logger.warn(e) { "Failed to delete session ${sessionItem.id} during clear-all" }
                }
            }
            loadSessions()
            return if (failed == 0) ClearAllResult.Success(deleted)
                   else ClearAllResult.Partial(deleted, failed)
        } finally {
            _clearAllProgress.value = ClearAllState.Idle
        }
    }

    // ── SSE Event Routing ──

    /** Process an SSE event. Routes to the correct SessionState by event.sessionId. */
    suspend fun processEvent(event: SseEvent) {
        val sessionId = event.sessionId

        when (event) {
            is SseEvent.SessionCreated -> {
                _globalSignals.tryEmit(UiSignal.SessionCreated(event.sessionId))
                return
            }
            is SseEvent.SessionIdle -> {
                if (event.sessionId == _activeSessionId.value) {
                    _globalSignals.tryEmit(UiSignal.SessionIdle(event.sessionId))
                }
                // Also emit to _allSessionSignals so the collector's SessionIdle
                // branch fires as a fallback — removes the session from
                // _streamingSessionIds even if StreamingCompleted was lost (e.g.
                // signalForwardJob cancelled, streamingCompletedEmitted guard blocked,
                // or ctx.isStreaming was already false when the backstop checked).
                _allSessionSignals.tryEmit(event.sessionId to UiSignal.SessionIdle(event.sessionId))
                // NOTE: The child-session backstop that previously finalized the
                // parent when a child went idle has been REMOVED. It caused premature
                // "Response Complete" notifications because a child session going idle
                // does NOT mean the parent's turn is done — the parent may continue
                // with more tool calls or text generation. The runningToolCount <= 1
                // guard was unreliable because the parent's ToolResult for the task
                // tool hasn't arrived yet when the child goes idle (it arrives after),
                // so the tool is still InProgress and the guard passes.
                //
                // The parent will finalize on its own via:
                //   - Its own Stop event (with non-tool-calls stopReason)
                //   - Its own MessageFinalized (with stopReason != null)
                //   - Its own SessionIdle (the active-session backstop at
                //     SessionState.kt:555-566, which now finalizes immediately
                //     via Layer 3)
                // These paths are reliable with Layer 1 (gated pendingStopJob cancel)
                // in place — the original cancel race that the child-backstop was
                // added to work around is now fixed at the root.
                //
                // Route to the child's own SessionState as a backstop — if the
                // child's streaming is still active when the server says it's idle,
                // finalize the CHILD (not the parent). Fall through to else branch.
            }
            is SseEvent.SessionError -> {
                logger.warn { "[ACP] Session error: session=${event.sessionId}, error=${event.errorMessage}" }
                _globalSignals.tryEmit(UiSignal.SessionError(event.sessionId, event.errorMessage))
                // Route to SessionState so it can finalize streaming and complete
                // responseDeferred. Without this, sendMessageInternal hangs at
                // deferred.await() holding sendMutex for 300s (the activity monitor
                // timeout), blocking all subsequent sends and freezing the UI.
                // This is the path that triggers on macOS when TCC denies the server
                // child process access to an attached file.
                sessionsLock.withLock { sessions[event.sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                return
            }
            is SseEvent.SessionCompacted -> {
                // Server performed auto-compaction — local message cache is stale.
                // Reset pressure monitor and clear background checkpoint (stale data).
                onSessionCompacted()
                // Emit signal so ViewModel can refresh messages and recompute context.
                if (event.sessionId == _activeSessionId.value) {
                    _globalSignals.tryEmit(UiSignal.SessionCompacted(event.sessionId))
                }
                return
            }
            is SseEvent.MessageRemoved -> {
                // Route to SessionState for cache removal, then refresh context
                sessionsLock.withLock { sessions[sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                if (sessionId == _activeSessionId.value) {
                    scope.launch { computeSessionContext() }
                }
                return
            }
            is SseEvent.Subtask -> {
                // The Subtask event's `sessionId` field is the CHILD's session ID.
                // The `processEvent` routing context (`sessionId` variable) is also
                // `event.sessionId` (the child's ID), NOT the parent's ID — the SSE
                // parser sets `SseEvent.Subtask.sessionId` to the child's session ID.
                // The parent's session ID is NOT available in the Subtask event payload.
                //
                // We CANNOT populate the _childToParent reverse index from Subtask
                // events because we don't know the parent's session ID. Mapping
                // childId as both child and parent would create a
                // self-referential mapping (childId → childId), which would cause
                // getParentSession to return the child's own ID as its parent.
                //
                // The reverse index is populated solely from loadSessions(), which
                // has `parentID` on each SessionItem. The agent label is still
                // captured from the Subtask event for display purposes.
                val childSessionId = event.sessionId
                val agent = event.agent
                // Only capture the agent label — don't populate the reverse index
                if (agent != null) {
                    _childAgentLabels.update { it + (childSessionId to agent) }
                }
                _knownChildSessionIds.update { it + childSessionId }
                logger.info { "[ACP] Subtask event: childSessionId=$childSessionId, agent=$agent — agent label captured, reverse index NOT populated (parent unknown from Subtask event)" }
                // Still route to SessionState for existing ToolPill rendering behavior
                sessionsLock.withLock { sessions[sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                return
            }
            is SseEvent.TodoUpdated -> {
                sessionsLock.withLock { sessions[sessionId] }?.let { state ->
                    state.processEvent(event)
                }
                if (sessionId == _activeSessionId.value) {
                    _todoItems.value = event.todos.map { todo -> TodoItem(content = todo.content, status = todo.status, priority = todo.priority) }
                }
                return
            }
            else -> {
                var state = sessionsLock.withLock { sessions[sessionId] }
                if (state == null) {
                    // Auto-cache unknown sessions when SSE events arrive (e.g. subtask streaming).
                    // Guards:
                    // 1. Only auto-cache if the cache has room to avoid evicting useful sessions.
                    // 2. Only auto-cache sessions that could belong to the current project:
                    //    skip if projectBasePath is set (project-scoped) and the session ID
                    //    doesn't match the active session or its known children. This prevents
                    //    loading other projects' messages into the local cache via untrusted SSE.
                    val activeId = _activeSessionId.value
                    // Only auto-cache sessions verifiably related to the active session (child or
                    // known child), regardless of projectBasePath. Auto-caching from untrusted SSE
                    // is a higher trust level than user-initiated REST listing — even when
                    // projectBasePath is null (cross-project mode), don't auto-load arbitrary
                    // session message histories from SSE.
                    val mayBelongToProject = sessionId == activeId ||
                        _childSessionMap.value[activeId]?.any { it.id == sessionId } == true ||
                        sessionId in _knownChildSessionIds.value
                    // Don't auto-cache if the cache is at capacity — prevent evicting useful
                    // sessions to make room for an untrusted SSE-driven auto-cache.
                    val cacheHasRoom = sessionsLock.withLock { sessions.size < MAX_CACHED_SESSIONS }
                    // RACE WINDOW (resolved): _knownChildSessionIds may be briefly stale
                    // (populated by loadSessions() and session creation, both async). The
                    // mayBelongToProject check verifies the session is the active ID, a known
                    // child of the active session, or in _knownChildSessionIds. If a child ID
                    // was evicted from _knownChildSessionIds between population and this check,
                    // a legitimate child event would be skipped — but the worst case is caching
                    // an extra session (bounded by MAX_CACHED_SESSIONS) or missing one cache
                    // entry (the next loadSessions() refreshes _knownChildSessionIds). This is
                    // acceptable. Documented race window; worst case is an extra cached session,
                    // bounded by MAX_CACHED_SESSIONS.
                    if (mayBelongToProject && cacheHasRoom) {
                        logger.info { "[ACP] Auto-caching session $sessionId from SSE event" }
                        ensureSessionCached(sessionId)
                        // Re-check: ensureSessionCached may have evicted a useful session.
                        // If the cache was at capacity and this session isn't the active or
                        // known child, skip caching to avoid evicting useful sessions.
                        state = sessionsLock.withLock { sessions[sessionId] }
                        if (state == null) {
                            logger.debug { "[ACP] Auto-cache for session $sessionId failed — cache at capacity after race" }
                        }
                    } else if (!cacheHasRoom) {
                        logger.debug { "[ACP] Skipping auto-cache for session $sessionId — cache at capacity" }
                    } else {
                        logger.debug { "[ACP] Skipping auto-cache for session $sessionId — not in project scope" }
                    }
                }
                if (state == null) {
                    logger.warn { "[ACP] Failed to cache session $sessionId, ignoring event" }
                    return
                }
                state.processEvent(event)
            }
        }
    }

    // ── Message Operations (delegate to active SessionState) ──

    fun createAssistantMessage(modelID: String?, providerID: String?, serverMessageId: String? = null): String {
        return getActiveSession()?.createAssistantMessage(modelID, providerID, serverMessageId)
            ?: throw IllegalStateException("No active session")
    }

    fun completeStreaming(messageId: String) {
        getActiveSession()?.completeStreaming(messageId)
    }

    fun abortStreaming(reason: String) {
        getActiveSession()?.abortStreaming(reason)
    }

    fun addMessage(message: ChatMessage) {
        getActiveSession()?.addMessage(message)
    }

    fun updateToolCallStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>?) {
        getActiveSession()?.updateToolCallStatus(toolCallId, status, output)
    }

    fun setToolPartState(toolCallId: String, state: PartState) {
        getActiveSession()?.setToolPartState(toolCallId, state)
    }

    fun updateServerMessageId(messageId: String, serverMessageId: String) {
        getActiveSession()?.updateServerMessageId(messageId, serverMessageId)
    }

    fun setLastUserText(text: String?) {
        getActiveSession()?.setLastUserText(text)
    }

    // ── Session-specific routing (for PermissionManager) ──

    suspend fun setToolPartStateForSession(sessionId: String, toolCallId: String, state: PartState) {
        sessionsLock.withLock { sessions[sessionId] }?.setToolPartState(toolCallId, state)
    }

    suspend fun updateToolCallStatusForSession(sessionId: String, toolCallId: String, status: ToolCallStatus, output: List<JsonObject>?) {
        sessionsLock.withLock { sessions[sessionId] }?.updateToolCallStatus(toolCallId, status, output)
    }

    suspend fun getSession(sessionId: String): SessionState? {
        return sessionsLock.withLock { sessions[sessionId] }
    }

    /** Get messages StateFlow for a cached session (returns null if not cached). */
    fun getSessionMessages(sessionId: String): StateFlow<Map<String, ChatMessage>>? {
        return sessionsLock.withLock { sessions[sessionId] }?.messages
    }

    /** Get real-time streaming text for a cached session (returns null if not cached). */
    fun getStreamingText(sessionId: String): StateFlow<String>? {
        return sessionsLock.withLock { sessions[sessionId] }?.streamingText
    }

    /** Check if a session is currently streaming. */
    fun isSessionStreaming(sessionId: String): Boolean {
        return sessionsLock.withLock { sessions[sessionId] }?.isStreaming ?: false
    }

    /** Get all cached sessions that have isStreaming == true.
     *  Used by OpenCodeService.recoverBackgroundSessions() after SSE reconnection. */
    internal fun getStreamingSessions(): List<SessionState> {
        return sessionsLock.withLock {
            sessions.values.filter { it.isStreaming }
        }
    }

    // ── Helpers ──

    /** Get the currently active SessionState. Returns null if no session is active.
     *  Uses ReentrantLock (not Mutex) because callers are non-suspend functions. */
    internal fun getActiveSession(): SessionState? {
        val id = _activeSessionId.value ?: return null
        return sessionsLock.withLock { sessions[id] }
    }

    /**
     * Non-blocking close — never blocks EDT.
     *
     * CONTRACT: The caller MUST also cancel the CoroutineScope after calling this.
     * When the lock is held, this method returns early — the in-flight coroutine
     * (holding the lock) will finish soon, and scope.cancel() ensures all
     * SessionState coroutines are cancelled. Without scope.cancel(), sessions
     * leaked by the early-return path would keep their coroutines and channels open.
     *
     *  Current call sites: only OpenCodeService.dispose(), which calls scope.cancel()
     *  immediately after. If adding new call sites, ensure scope.cancel() follows.
     *
     *  RECOMMENDED PATTERN: Callers should use a `disposeWithScope(sessionManager, scope)`
     *  helper that calls close() then scope.cancel() in sequence, to enforce the
     *  contract that scope.cancel() always follows close(). This prevents leaked
     *  SessionState coroutines/channels if a future call site forgets scope.cancel().
     *  Added KDoc note recommending disposeWithScope pattern; contract documented. */
    fun close() {
        val statesToClose: List<SessionState>
        if (sessionsLock.tryLock()) {
            try {
                statesToClose = sessions.values.toList()
                sessions.clear()
                sessionsSnapshot = emptyList()
            } finally {
                sessionsLock.unlock()
            }
        } else {
            // Lock held by a coroutine doing ensureSessionCached (blocking on HTTP).
            // DO NOT call sessionsLock.withLock here — it's a blocking call that would
            // freeze the EDT until the HTTP request completes. Use the volatile snapshot
            // (updated on every put/remove) to close sessions without acquiring the lock.
            // scope.cancel() (called by the caller after this) will cancel all SessionState
            // coroutines; close() here handles the critical cleanup (closed flag, responseDeferred,
            // event channel, prompts) that scope.cancel() alone does NOT perform.
            logger.warn { "[ACP] SessionManager.close: lock held — using volatile snapshot for cleanup" }
            val snapshot = sessionsSnapshot
            snapshot.forEach { it.close() }
            return
        }
        statesToClose.forEach { it.close() }
    }

    // ── Private: Cache Management ──

    /** Ensure a session's SessionState exists in cache. Fetch from server if not. */
    internal suspend fun ensureSessionCached(sessionId: String) {
        sessionsLock.withLock {
            if (sessions.containsKey(sessionId)) return
        }

        evictIfNeeded(excludeSessionId = sessionId)

        val c = client
        val messages = try {
            c?.listMessages(sessionId, limit = null) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch messages for $sessionId" }
            emptyList()
        }

        val state = SessionState(sessionId, scope, this, project)
        messages.forEach { state.addMessage(it.toChatMessage()) }

        // Adopt the last assistant message as the streaming context so subsequent
        // SSE events (TextChunk, ToolUse, etc.) are routed to it.
        // This is critical for child sessions: their SSE events arrive while the
        // parent's prompt loop is still running, but createAssistantMessage was
        // never called for the child's SessionState.
        val lastAssistant = messages.findLast { it.info.role == "assistant" }
        if (lastAssistant != null) {
            state.adoptStreamingContext(
                lastAssistant.info.id,
                lastAssistant.info.modelID,
                lastAssistant.info.providerID
            )
            logger.info { "[ACP] ensureSessionCached: adopted message ${lastAssistant.info.id} for session $sessionId" }
        }

        val evictedAfterInsert = mutableListOf<SessionState>()
        sessionsLock.withLock {
            val existing = sessions.putIfAbsent(sessionId, state)
            if (existing != null) {
                state.close()
            } else {
                // Re-check capacity under lock to prevent race with concurrent auto-cache:
                // the pre-fetch `cacheHasRoom` check in processEvent acquires the lock
                // separately, so the cache could fill between that check and this put.
                // Evict here (under the lock) to keep the cache bounded. We collect
                // evicted states and close them outside the lock to avoid blocking
                // other lock waiters on state.close() (which cancels coroutines).
                while (sessions.size > MAX_CACHED_SESSIONS) {
                    val lru = sessions.entries
                        .filter { it.key != _activeSessionId.value
                                && it.key != sessionId
                                && !it.value.isStreaming
                                && !it.value.hasPendingPermission
                                && it.key !in _childPendingPermissions.value }
                        .minByOrNull { it.value.lastAccessTime }
                        ?: break
                    evictedAfterInsert.add(lru.value)
                    sessions.remove(lru.key)
                }
                _sessionCachedFlow.tryEmit(sessionId)
                // Track child sessions for fast-completion detection.
                // parentID is not available here (SSE context), so check if
                // the session is already known as a child via _childSessionMap.
                if (_childSessionMap.value.values.flatten().any { it.id == sessionId }) {
                    _knownChildSessionIds.update { it + sessionId }
                }
            }
            sessionsSnapshot = sessions.values.toList()
        }
        // Close evicted states outside the lock (cancels coroutines, closes channels).
        evictedAfterInsert.forEach {
            it.close()
            logger.debug { "[ACP] Evicted session from cache (capacity re-check in ensureSessionCached)" }
        }
    }

    /**
     * Refresh the message cache for the active session by re-fetching from the server.
     * Used after auto-compaction (session.compacted) when the local cache is stale —
     * compacted messages are removed/summarized server-side, so local token
     * accumulation would produce inflated numbers without a refresh.
     */
    internal suspend fun refreshActiveSessionMessages() {
        val currentSessionId = _activeSessionId.value ?: return
        val c = client ?: return
        val state = sessionsLock.withLock { sessions[currentSessionId] } ?: return

        val messages = try {
            c.listMessages(currentSessionId, limit = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Failed to refresh messages for $currentSessionId after compaction" }
            return
        }

        state.replaceAllMessages(messages.map { it.toChatMessage() })
        logger.info { "[ACP] Refreshed messages for session $currentSessionId after compaction (${messages.size} messages)" }
    }

    /** Evict the least-recently-used non-active session if cache is full.
     *  Never evict streaming or permission-pending sessions.
     *  Removes sessions from the map under lock, then closes them outside the lock. */
    private suspend fun evictIfNeeded(excludeSessionId: String? = null) {
        val toEvict = mutableListOf<Pair<String, SessionState>>()
        sessionsLock.withLock {
            while (sessions.size > MAX_CACHED_SESSIONS) {
                val lru = sessions.entries
                    .filter { it.key != _activeSessionId.value
                            && it.key != excludeSessionId
                            && !it.value.isStreaming
                            && !it.value.hasPendingPermission
                            && it.key !in _childPendingPermissions.value }
                    .minByOrNull { it.value.lastAccessTime }
                    ?: break
                toEvict.add(lru.key to lru.value)
                sessions.remove(lru.key)
            }
            sessionsSnapshot = sessions.values.toList()
        }
        toEvict.forEach { (id, state) ->
            state.close()
            logger.debug { "[ACP] Evicted session $id from cache" }
        }
    }

    /** Recursively collect all descendant session IDs of [sessionId] by walking
     *  [_childSessionMap]. Returns descendants in pre-order (parent's children
     *  before their own children); the caller deletes them all before deleting
     *  [sessionId] itself, so order among descendants does not matter.
     *
     *  Cycle-safe via a visited set — defensive against server data bugs that
     *  could create cyclic parent/child relationships. */
    private fun collectDescendants(sessionId: String): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun walk(id: String) {
            if (id in visited) return
            visited.add(id)
            val children = _childSessionMap.value[id] ?: return
            for (child in children) {
                if (child.id !in visited) {
                    result.add(child.id)
                    walk(child.id)
                }
            }
        }
        walk(sessionId)
        return result
    }

    // ── Private: Context / Todos ──

    private fun updateSessionSelection(selectedId: String?) {
        val current = _sessionListState.value
        if (current is SessionListState.Loaded) {
            _sessionListState.value = current.copy(selectedId = selectedId)
        }
    }

    internal suspend fun computeSessionContext(controlState: ControlBarState? = null): SessionContextState {
        // NOTE: Do NOT acquire switchMutex here — switchSession() and
        // createAndSwitchSession() already hold it when they call this method,
        // and kotlinx.coroutines.sync.Mutex is non-reentrant (would deadlock).
        // The session-switch race window (reading _activeSessionId.value then
        // getActiveSession()) is negligible — both are thread-safe reads, and
        // a switch between them only means slightly stale messages for one frame.
        val currentSessionId = _activeSessionId.value ?: return SessionContextState.Loading
        val messages = getActiveSession()?.messages?.value ?: emptyMap()
        val c = client ?: return SessionContextState.Loading

        // In-flight guard: skip if another full computation is already running.
        // Uses a dedicated guard so local refreshes are not blocked.
        if (!fullComputeInFlight.compareAndSet(false, true)) {
            // Another full computation is in progress — return the current state as-is.
            // If it's Loading, the in-progress computation will replace it soon.
            return _sessionContextState.value
        }
        try {
            return computeSessionContextInternal(currentSessionId, messages, c, controlState, fetchSession = true)
        } finally {
            fullComputeInFlight.set(false)
        }
    }

    /**
     * Local-only context computation — no REST call. Used during streaming and on
     * intermediate [UiSignal.MessageUpdated] signals. Reads token/cost/model data
     * from the local message cache only; summary/time fields reuse the last loaded
     * values (they don't change mid-stream).
     *
     * Thread-safe via [localComputeInFlight] — local refreshes use a separate guard
     * from full (REST) refreshes so they are not starved during streaming.
     */
    internal suspend fun computeSessionContextLocal(controlState: ControlBarState? = null): SessionContextState {
        val currentSessionId = _activeSessionId.value ?: return SessionContextState.Loading
        val messages = getActiveSession()?.messages?.value ?: emptyMap()
        val c = client ?: return SessionContextState.Loading

        // Use a separate guard so local refreshes are NOT blocked by a full computation
        // blocked on REST getSession(). Local refreshes are cheap (no REST) and should
        // update the indicator promptly during streaming.
        if (!localComputeInFlight.compareAndSet(false, true)) {
            // Another local computation is in progress — return the current state as-is.
            return _sessionContextState.value
        }
        try {
            return computeSessionContextInternal(currentSessionId, messages, c, controlState, fetchSession = false)
        } finally {
            localComputeInFlight.set(false)
        }
    }

    /**
     * Shared computation logic for both full (REST) and local-only context computation.
     *
     * @param fetchSession if true, fetches session metadata (summary, time, model) via REST;
     *                     if false, reuses the last loaded summary/time values (they don't
     *                     change mid-stream) and falls back to controlState for model info.
     */
    private suspend fun computeSessionContextInternal(
        currentSessionId: String,
        messages: Map<String, ChatMessage>,
        c: OpenCodeClient,
        controlState: ControlBarState?,
        fetchSession: Boolean,
    ): SessionContextState {
        // Best-effort session fetch — only when fetchSession=true (full path).
        // Token/cost data comes from the local message cache (kept accurate by
        // MessageFinalized SSE events), NOT from session.tokens/session.cost
        // (the V1 API returns these as always-zero — see AGENTS.md § API Testing).
        val session = if (fetchSession) {
            try {
                c.getSession(currentSessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to fetch session for context" }
                null
            }
        } else {
            // Local-only path: reuse last loaded session metadata for summary/time
            // (they don't change mid-stream). Model falls back to controlState.
            null
        }

        // ── Token data ──
        // inputTokens and cacheReadTokens are CUMULATIVE (each message's value
        // represents the full prompt context sent for that LLM call, including all
        // prior messages). Use the LAST assistant message with non-zero tokens —
        // NOT a sum across all messages (that would double-count).
        // outputTokens, reasoningTokens, cacheWriteTokens, and cost are PER-MESSAGE
        // (incremental for that step). Sum these across all assistant messages.
        val assistantMessages = messages.values.filter { it.role == MessageRole.ASSISTANT }

        // Cumulative fields: last message with non-zero input tokens (not 0L fallback).
        // Falls back to the previous message's tokens when the last assistant is still
        // streaming (no MessageFinalized yet) — prevents the indicator from dropping to 0.
        val lastWithInput = assistantMessages.findLast { it.inputTokens > 0 }
        val inputTokens = lastWithInput?.inputTokens ?: 0L
        val cacheReadTokens = lastWithInput?.cacheReadTokens ?: 0L

        // Per-message fields: sum across all assistant messages
        val outputTokens = assistantMessages.sumOf { it.outputTokens }
        val reasoningTokens = assistantMessages.sumOf { it.reasoningTokens }
        val cacheWriteTokens = assistantMessages.sumOf { it.cacheWriteTokens }
        val totalTokens = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens
        val totalCost = assistantMessages.sumOf { it.cost }

        // ── Model info: from session metadata, fallback to controlState ──
        val modelId = session?.model?.id?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.modelID
        val providerId = session?.model?.providerID?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.providerID

        val (providerName, modelName) = resolveModelNames(
            controlState?.models ?: emptyList(), modelId, providerId
        )
        val contextLimit = resolveContextLimit(
            controlState?.allModels?.ifEmpty { controlState.models } ?: emptyList(),
            providerId, modelId
        )

        val usagePercent = if (contextLimit > 0L) {
            (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        // ── Smart Context Manager: record turn for pressure monitoring ──
        // Only record on the full (REST) path — local refreshes during streaming
        // would produce noisy intermediate data points.
        if (fetchSession && inputTokens > 0) {
            try {
                pressureMonitor.recordTurn(inputTokens, System.currentTimeMillis())
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] pressureMonitor.recordTurn failed" }
            }
        }

        // ── Smart Context Manager: background compaction checkpoint ──
        // DISABLED: The server's POST /session/{id}/summarize endpoint performs ACTUAL
        // compaction (removes/summarizes messages), not a preview. Calling it as a
        // "background checkpoint" compacts the session immediately — the user sees
        // their context disappear on load when usage > 60%. There is no server API
        // to pre-compute a summary without side effects, so the BackgroundCompactor's
        // auto-trigger is removed. Manual compaction (/compact command, "Compact Now"
        // button) remains fully functional. The BackgroundCompactor class is retained
        // as dead code in case a preview API is added in the future.
        //
        // if (fetchSession && backgroundCompactionEnabled() && backgroundCompactor != null) {
        //     backgroundCompactor?.maybeCheckpoint(...)
        // }

        // ── Message counts: from local cache ──
        val messageCount = messages.size
        val userMessageCount = messages.values.count { it.role == MessageRole.USER }
        val assistantMessageCount = assistantMessages.size

        val sessionTitle = (_sessionListState.value as? SessionListState.Loaded)
            ?.sessions?.find { it.id == currentSessionId }?.title ?: "Untitled"

        // ── Summary/time: from REST (full path) or reuse last loaded (local path) ──
        val lastLoaded = (_sessionContextState.value as? SessionContextState.Loaded)?.context
        val additions = session?.summary?.additions ?: lastLoaded?.additions ?: 0
        val deletions = session?.summary?.deletions ?: lastLoaded?.deletions ?: 0
        val filesModified = session?.summary?.files ?: lastLoaded?.filesModified ?: 0
        val sessionCreated = session?.time?.created ?: lastLoaded?.sessionCreated ?: 0L
        val lastUpdated = session?.time?.updated ?: lastLoaded?.lastUpdated ?: 0L

        // Read pruner heartbeat once — used for both breakdown adjustment and SessionContext fields
        val currentProjectBasePath = projectBasePath
        val prunerHeartbeat = if (currentProjectBasePath != null) {
            try { PrunerHeartbeatReader.readHeartbeat(currentProjectBasePath) } catch (_: Exception) { null }
        } else null

        val result = SessionContextState.Loaded(
            context = SessionContext(
                sessionId = currentSessionId,
                title = sessionTitle,
                providerID = providerId ?: "",
                modelID = modelId ?: "",
                providerName = providerName,
                modelName = modelName,
                contextLimit = contextLimit,
                totalTokens = totalTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                usagePercent = usagePercent,
                totalCost = totalCost,
                messageCount = messageCount,
                userMessageCount = userMessageCount,
                assistantMessageCount = assistantMessageCount,
                additions = additions,
                deletions = deletions,
                filesModified = filesModified,
                sessionCreated = sessionCreated,
                lastUpdated = lastUpdated,
                breakdown = computeBreakdownSafely(messages, contextLimit, totalTokens, prunerHeartbeat),
                pressure = computePressureSafely(totalTokens, contextLimit, inputTokens),
                prunerTokensSaved = prunerHeartbeat?.tokensSaved ?: 0,
                prunerOutputsPruned = prunerHeartbeat?.outputsPruned ?: 0,
                prunerInputsPruned = prunerHeartbeat?.inputsPruned ?: 0,
                prunerLastRunMs = prunerHeartbeat?.timestampMs ?: 0,
            )
        )
        logger.info { "[ACP] computeSessionContext(${if (fetchSession) "full" else "local"}): session=$currentSessionId totalTokens=$totalTokens cost=$totalCost usagePercent=${"%.1f".format(usagePercent)}% model=$modelId provider=$providerId" }
        // Staleness guard: don't publish if the active session changed during the
        // (possibly slow) computation. Prevents session A's context from appearing
        // under session B after a switch.
        if (currentSessionId != _activeSessionId.value) {
            logger.info { "[ACP] computeSessionContext: session changed during computation ($currentSessionId → ${_activeSessionId.value}) — discarding result" }
            return result
        }
        _sessionContextState.value = result
        return result
    }

    internal suspend fun fetchTodos() {
        val currentSessionId = _activeSessionId.value ?: return
        val c = client ?: return
        try {
            val todos = c.getTodos(currentSessionId)
            _todoItems.value = todos.map { TodoItem(content = it.content, status = it.status, priority = it.priority) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log for diagnostics — user sees no feedback (todo list just doesn't update)
            logger.warn(e) { "[ACP] Failed to fetch todos for session $currentSessionId" }
        }
    }

    // ── Private: Helpers ──

    private fun resolveModelNames(models: List<ProviderModel>, modelId: String?, providerId: String?): Pair<String, String> {
        if (modelId.isNullOrBlank() && providerId.isNullOrBlank()) return Pair("Unknown", "Unknown")
        if (models.isEmpty()) return Pair(providerId ?: "Unknown", modelId ?: "Unknown")

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null) {
            return splitDisplayName(exactMatch.displayName, exactMatch.providerID, exactMatch.modelID)
        }

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null) {
            return splitDisplayName(modelOnlyMatch.displayName, modelOnlyMatch.providerID, modelOnlyMatch.modelID)
        }

        return Pair(providerId ?: "Unknown", modelId ?: "Unknown")
    }

    /**
     * Split a displayName in "provider / model" format using the LAST occurrence
     * of " / " as the delimiter. This handles provider names that contain " / "
     * (e.g., "AI / ML Provider / gpt-4" → provider="AI / ML Provider", model="gpt-4").
     * Falls back to the provided providerID/modelID if the format doesn't match.
     */
    private fun splitDisplayName(displayName: String, providerID: String, modelID: String): Pair<String, String> {
        val separator = " / "
        val lastIdx = displayName.lastIndexOf(separator)
        if (lastIdx > 0) {
            val provider = displayName.substring(0, lastIdx)
            val model = displayName.substring(lastIdx + separator.length)
            if (provider.isNotBlank() && model.isNotBlank()) {
                return Pair(provider, model)
            }
        }
        return Pair(providerID, modelID)
    }

    private fun resolveContextLimit(models: List<ProviderModel>, providerId: String?, modelId: String?): Long {
        if (modelId.isNullOrBlank()) return 0L
        if (models.isEmpty()) return 0L

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null && exactMatch.contextWindow > 0) return exactMatch.contextWindow.toLong()

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null && modelOnlyMatch.contextWindow > 0) return modelOnlyMatch.contextWindow.toLong()

        return 0L
    }

    // ── Smart Context Manager helpers ──

    /** Compute breakdown safely — returns null on failure (non-fatal).
     *  If [projectDirectory] is provided, subtracts pruner-estimated tokens saved
     *  from the tool category so the UI reflects what the LLM actually sees.
     *  @param sessionTotalTokens the server-provided total token count, used to
     *  normalize the breakdown so its total matches the session's token count. */
    private fun computeBreakdownSafely(
        messages: Map<String, ChatMessage>,
        contextLimit: Long,
        sessionTotalTokens: Long = 0L,
        prunerHeartbeat: PrunerHeartbeat? = null,
    ): com.opencode.acp.chat.model.ContextBreakdown? {
        return try {
            if (messages.isEmpty()) return null
            val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit, sessionTotalTokens)
            val prunerSaved = prunerHeartbeat?.tokensSaved ?: 0L
            if (prunerSaved > 0) {
                // Subtract pruner-saved tokens from tool category (pruning targets tool outputs).
                // Clear the per-tool breakdown map because individual tool counts are unreliable
                // after pruner adjustment — the pruner doesn't record which tools were pruned,
                // so we can't proportionally adjust per-tool entries. The UI's tool breakdown
                // sub-view is hidden when the map is empty.
                val adjustedToolTokens = (breakdown.toolTokens - prunerSaved).coerceAtLeast(0L)
                val adjustedTotal = (breakdown.totalTokens - prunerSaved).coerceAtLeast(0L)
                return breakdown.copy(
                    toolTokens = adjustedToolTokens,
                    totalTokens = adjustedTotal,
                    freeTokens = (contextLimit - adjustedTotal).coerceAtLeast(0L),
                    toolBreakdown = emptyMap(),
                )
            }
            breakdown
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] BreakdownComputer.computeBreakdown failed" }
            null
        }
    }

    /** Compute pressure safely — returns null on failure or insufficient data (non-fatal). */
    private suspend fun computePressureSafely(
        totalTokens: Long,
        contextLimit: Long,
        inputTokens: Long,
    ): com.opencode.acp.chat.model.ContextPressure? {
        return try {
            pressureMonitor.computePressure(totalTokens, contextLimit)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] pressureMonitor.computePressure failed" }
            null
        }
    }

    /**
     * Reset smart-context state on session switch.
     * Called from [switchSession] and [createAndSwitchSession].
     */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    suspend fun resetSmartContextState() {
        BreakdownComputer.resetCalibration()
        pressureMonitor.reset()
        fileReadCache.clear()
        backgroundCompactor?.clearCheckpoint()
    }

    /**
     * Handle server-side compaction: reset pressure monitor and clear background checkpoint.
     * Called when the `session.compacted` SSE event fires.
     */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    suspend fun onSessionCompacted() {
        pressureMonitor.onCompaction()
        backgroundCompactor?.clearCheckpoint()
    }

    /**
     * Truncate tool output if truncation is enabled in settings.
     * Called by SessionState before storing tool results.
     */
    fun maybeTruncateToolOutput(toolName: String, output: List<JsonObject>): List<JsonObject> {
        if (!truncateToolOutputEnabled()) return output
        return ToolOutputTruncator.truncateIfNeeded(toolName, output, toolOutputCharLimit())
    }

    /** Whether the background compactor has a valid checkpoint for the active session. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    fun isCheckpointReady(): Boolean {
        val sessionId = _activeSessionId.value ?: return false
        val context = _sessionContextState.value
        if (context !is SessionContextState.Loaded) return false
        val providerId = context.context.providerID
        val modelId = context.context.modelID
        return backgroundCompactor?.hasValidCheckpoint(sessionId, providerId, modelId) == true
    }
}
