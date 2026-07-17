package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodeSession
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.adapter.toSessionItem
import com.opencode.acp.chat.model.*
import com.opencode.acp.config.settings.OpenCodeContextSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.processor.BackgroundCompactor
import com.opencode.acp.chat.processor.BackgroundCompactorSettings
import com.opencode.acp.chat.processor.FileReadCache
import com.opencode.acp.chat.processor.ToolOutputTruncator
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
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
) : SessionStateContext {

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

    /** The currently active session ID. Null if no session is active. */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Extracted context computation: token accumulation, model resolution, pressure monitoring. */
    internal val contextComputer = ContextComputer(
        activeSessionIdProvider = { _activeSessionId.value },
        messagesProvider = { getActiveSession()?.messages?.value ?: emptyMap() },
        clientProvider = { client },
        projectBasePathProvider = { projectBasePath },
        sessionListStateProvider = { _sessionListState.value },
    )

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

    val sessionContextState: StateFlow<SessionContextState> = contextComputer.sessionContextState

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

    /** Pre-computes compaction summaries in the background for instant swap. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    internal var backgroundCompactor: BackgroundCompactor? = null
        private set

    /** Session-scoped cache for detecting duplicate file reads (opt-in). */
    internal val fileReadCache = FileReadCache()

    /** Whether tool output truncation is enabled (from settings, read per-call). */
    private fun truncateToolOutputEnabled(): Boolean =
        OpenCodeContextSettingsState.getInstance().truncateToolOutput

    /** Tool output char limit (from settings, read per-call). */
    private fun toolOutputCharLimit(): Int =
        OpenCodeContextSettingsState.getInstance().toolOutputCharLimit

    /** Whether background compaction is enabled (from settings). */
    private fun backgroundCompactionEnabled(): Boolean =
        OpenCodeContextSettingsState.getInstance().enableBackgroundCompaction

    /** Creates or recreates the BackgroundCompactor with current settings. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    private fun createBackgroundCompactor(): BackgroundCompactor {
        val settings = OpenCodeContextSettingsState.getInstance()
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

    // ── Child Session Ephemeral State (delegated to ChildSessionTracker) ──

    /** Extracted child session tracking: hidden/known/pending sets, reverse index, agent labels. */
    internal val childSessionTracker = ChildSessionTracker(
        activeSessionIdProvider = { _activeSessionId.value },
    )

    val hiddenChildSessionIds: StateFlow<Set<String>> = childSessionTracker.hiddenChildSessionIds
    val knownChildSessionIds: StateFlow<Set<String>> = childSessionTracker.knownChildSessionIds
    val childToParent: StateFlow<Map<String, String>> = childSessionTracker.childToParent
    val childAgentLabels: StateFlow<Map<String, String>> = childSessionTracker.childAgentLabels
    val childPendingPermissions: StateFlow<Set<String>> = childSessionTracker.childPendingPermissions

    /** Mark a completed child session for hiding from the sidebar.
     *  Skips the active/selected session — it stays visible until the user switches away. */
    fun markChildSessionComplete(sessionId: String) =
        childSessionTracker.markChildSessionComplete(sessionId)

    /** Un-hide a child session (e.g., when switching to it via ToolPill "open child"). */
    fun unhideChildSession(sessionId: String) =
        childSessionTracker.unhideChildSession(sessionId)

    /** Find the parent session ID for a given child session ID.
     *  O(1) lookup via childToParent reverse index.
     *  Falls back to scanning childSessionMap if reverse index is stale. */
    fun getParentSession(childSessionId: String): String? =
        childSessionTracker.getParentSession(childSessionId, _childSessionMap.value)

    /** Get the agent label for a child session (e.g., "fixer", "explorer"). */
    fun getChildAgentLabel(childSessionId: String): String? =
        childSessionTracker.getChildAgentLabel(childSessionId)

    /** Mark a child session as having a pending permission. Prevents cache eviction. */
    fun markChildPendingPermission(childSessionId: String) =
        childSessionTracker.markChildPendingPermission(childSessionId)

    /** Clear a child session's pending permission flag. */
    fun clearChildPendingPermission(childSessionId: String) =
        childSessionTracker.clearChildPendingPermission(childSessionId)

    override fun emitSessionSignal(sessionId: String, signal: UiSignal) {
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
                            // Use knownChildSessionIds (populated on session creation and in loadSessions())
                            // rather than _childSessionMap, which is only refreshed in loadSessions(). This
                            // avoids a race where a fast-completing child finishes before loadSessions() runs
                            // and _childSessionMap does not yet contain it (isChild would return false).
                            if (childSessionTracker.isKnownChild(sessionId)) {
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
                            if (childSessionTracker.isKnownChild(sessionId)) {
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
                        } catch (e: Exception) {
                            logger.warn(e) { "[ACP] SessionManager.loadSessions: failed to canonicalize project base '$currentProjectBase' — will return empty list to prevent cross-project leak" }
                            null
                        }
                        if (canonicalBase == null) {
                            // Canonicalization failed — return empty list for security.
                            // On a shared server, the unfiltered list may contain sessions
                            // from other projects. Without canonicalization, we cannot
                            // verify the project boundary, so we must NOT show any sessions
                            // to prevent cross-project data leakage. The user can still
                            // create a new session.
                            logger.warn { "[ACP] SessionManager.loadSessions: canonicalization failed for project base '$currentProjectBase' — returning empty list to prevent cross-project leak" }
                            emptyList()
                        } else {
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
                        }
                    } else {
                        unfilteredList
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] SessionManager.loadSessions: unfiltered retry also failed — keeping empty list" }
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

            // Populate knownChildSessionIds independently of _childSessionMap
            // (fixes fast-completion race — see knownChildSessionIds doc)
            childSessionTracker.addKnownChildrenFromItems(items)

            // Populate childToParent reverse index from session parentID fields
            childSessionTracker.updateChildToParent(items)

            // Prune hiddenChildSessionIds and knownChildSessionIds: remove IDs for
            // sessions the server has deleted, preserve state for sessions the server
            // still knows about. Without this, the sets grow unbounded across the
            // plugin's lifetime.
            val currentSessionIds = items.map { it.id }.toSet()
            childSessionTracker.pruneDeleted(currentSessionIds)
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
                logger.error { "[ACP] archiveSession: both loadSessions() attempts failed — child sessions may be orphaned. Proceeding with direct deletion of $targetSessionId only." }
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
            logger.error { "[ACP] clearAllSessions: loadSessions() failed — child sessions may be orphaned. Proceeding with direct deletion of top-level sessions only." }
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
                // The Subtask event is parsed from a "message.part.updated" SSE event
                // where the part type is "subtask". The `sessionId` here is the session
                // that OWNS the message containing the subtask part — i.e., the PARENT
                // session (the parent's assistant message includes the subtask part).
                // The child's session ID is NOT present in the Subtask event payload
                // (OpenCodePart.Subtask has only prompt, description, agent, model).
                //
                // We CANNOT populate the childToParent reverse index from Subtask
                // events because we don't know the child's session ID from this event.
                // The reverse index is populated solely from loadSessions(), which
                // has `parentID` on each SessionItem. The agent label is still
                // captured from the Subtask event for display purposes.
                //
                // When a child session needs permission before loadSessions() has
                // populated the reverse index, the orphan-permission retry in
                // OpenCodeService.startGlobalSignalCollection() triggers a
                // loadSessions() to fetch the child's parentID and retry the relay.
                val childSessionId = event.sessionId
                val agent = event.agent
                // Only capture the agent label — don't populate the reverse index
                if (agent != null) {
                    childSessionTracker.setChildAgentLabel(childSessionId, agent)
                }
                childSessionTracker.addKnownChild(childSessionId)
                logger.info { "[ACP] Subtask event: parentSessionId=$childSessionId, agent=$agent — agent label captured, reverse index NOT populated (child session ID unknown from Subtask event)" }
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
                    // 2. Only auto-cache sessions verifiably related to the active session
                    //    (the active session itself, a known child of the active session, or a
                    //    known child in childSessionTracker). This check is UNCONDITIONAL — it
                    //    always applies regardless of whether projectBasePath is set. Even in
                    //    cross-project mode (projectBasePath == null), we never auto-load
                    //    arbitrary session message histories from untrusted SSE. Auto-caching
                    //    from SSE is a higher trust level than user-initiated REST listing.
                    val activeId = _activeSessionId.value
                    val mayBelongToProject = sessionId == activeId ||
                        _childSessionMap.value[activeId]?.any { it.id == sessionId } == true ||
                        childSessionTracker.isKnownChild(sessionId)
                    // Don't auto-cache if the cache is at capacity — prevent evicting useful
                    // sessions to make room for an untrusted SSE-driven auto-cache.
                    val cacheHasRoom = sessionsLock.withLock { sessions.size < MAX_CACHED_SESSIONS }
                    // RACE WINDOW (resolved): knownChildSessionIds may be briefly stale
                    // (populated by loadSessions() and session creation, both async). The
                    // mayBelongToProject check verifies the session is the active ID, a known
                    // child of the active session, or in knownChildSessionIds. If a child ID
                    // was evicted from knownChildSessionIds between population and this check,
                    // a legitimate child event would be skipped — but the worst case is caching
                    // an extra session (bounded by MAX_CACHED_SESSIONS) or missing one cache
                    // entry (the next loadSessions() refreshes knownChildSessionIds). This is
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

    /**
     * Abort streaming with a fallback message ID for the case where [createAssistantMessage]
     * sent a ResetTurn control event that hasn't been processed yet (activeMessageId is still
     * null). The fallback ID is the assistant message ID returned by [createAssistantMessage].
     * Without this, a fast-failing send (e.g., server rejects attachment) would leave the
     * assistant message stuck in isStreaming=true forever (a "ghost message").
     */
    fun abortStreamingWithFallback(reason: String, fallbackMessageId: String) {
        getActiveSession()?.abortStreamingWithFallback(reason, fallbackMessageId)
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
     *  Used by [recoverBackgroundSessions] after SSE reconnection. */
    internal fun getStreamingSessions(): List<SessionState> {
        return sessionsLock.withLock {
            sessions.values.filter { it.isStreaming }
        }
    }

    /** Extracted background session recovery after SSE reconnection. */
    internal val sessionRecoveryManager = SessionRecoveryManager(
        streamingSessionsProvider = { getStreamingSessions() },
    )

    /**
     * Check all cached sessions that were streaming when SSE dropped.
     * Re-fetches recent messages for each streaming session. If the server's
     * last message is an assistant message (indicating generation completed),
     * finalize it locally. Prevents responseDeferred from hanging until the
     * configurable response timeout.
     *
     * Delegated to [SessionRecoveryManager.recoverBackgroundSessions]. See its
     * KDoc for the SAFETY and ASSUMPTION notes (in-progress tool call detection
     * prevents incorrectly finalizing a session that's mid-tool-execution).
     *
     * @param client The OpenCodeClient to use for REST calls (passed by caller
     *   from ProcessManager.client — SessionManager does NOT own a client reference).
     */
    internal suspend fun recoverBackgroundSessions(client: OpenCodeClient?) {
        sessionRecoveryManager.recoverBackgroundSessions(client)
    }

    // ── Helpers ──

    /** Get the currently active SessionState. Returns null if no session is active.
     *  Uses ReentrantLock (not Mutex) because callers are non-suspend functions. */
    internal fun getActiveSession(): SessionState? {
        val id = _activeSessionId.value ?: return null
        return sessionsLock.withLock { sessions[id] }
    }

    /**
     * Latest SSE activity timestamp across the active session AND all known
     * cached descendants. Lets the activity monitor treat child/subagent
     * generation as activity on the parent turn, so timeouts reset when
     * subagents are actively generating.
     *
     * Returns the max of (parent's lastActivityTimeMs, max over all cached
     * children's lastActivityTimeMs). Children are identified via both
     * [_childSessionMap] (from loadSessions) and [childSessionTracker.knownChildSessionIds]
     * (from Subtask SSE events, covers the pre-loadSessions window).
     *
     * Returns current time if no active session (defensive — avoids false-positive).
     *
     * Thread safety: reads `lastActivityTimeMs` which is `@Volatile` in
     * [TurnLifecycleState]. The `sessionsLock` is held only for the O(children)
     * map lookups — brief, no contention.
     */
    internal fun getEffectiveLastActivityMs(): Long {
        val activeId = _activeSessionId.value ?: return System.currentTimeMillis()
        val parentTs = sessionsLock.withLock { sessions[activeId] }
            ?.lastActivityTimeMs ?: return System.currentTimeMillis()
        val childIds = (_childSessionMap.value[activeId]?.map { it.id } ?: emptyList())
            .plus(childSessionTracker.knownChildSessionIds.value)
            .distinct()
        if (childIds.isEmpty()) return parentTs
        var maxTs = parentTs
        sessionsLock.withLock {
            for (cid in childIds) {
                sessions[cid]?.let { if (!it.isClosed) maxTs = maxOf(maxTs, it.lastActivityTimeMs) }
            }
        }
        return maxTs
    }

    /**
     * Whether any known child session of the active session is actively generating.
     *
     * A child is "actively generating" if EITHER:
     * - It is in [_streamingSessionIds] (StreamingStarted fired, StreamingCompleted/SessionIdle
     *   not yet fired), OR
     * - It has received an SSE event in the last [idleThresholdMs] (default 60s,
     *   matching SSE_HEALTH_CHECK_INTERVAL_MS).
     *
     * Used by [ResponseTimeoutMonitor] to suspend the tool-stuck ceiling while
     * subagents are working. The ceiling is PRESERVED as a safety net — it still
     * fires when no child is active (genuinely stuck tool, crashed child, lost
     * ToolResult after SSE gap).
     *
     * The `_streamingSessionIds` check survives LRU eviction of the child's
     * SessionState (the set is maintained independently of the cache), so a
     * child that is evicted but still streaming on the server still counts as
     * active.
     *
     * Returns false if no active session or no known children are active.
     */
    internal fun isAnyChildActivelyGenerating(idleThresholdMs: Long = 60_000L): Boolean {
        val activeId = _activeSessionId.value ?: return false
        val now = System.currentTimeMillis()
        val candidateIds = (_childSessionMap.value[activeId]?.map { it.id } ?: emptyList())
            .plus(childSessionTracker.knownChildSessionIds.value)
            .distinct()
        val streamingIds = _streamingSessionIds.value
        sessionsLock.withLock {
            for (cid in candidateIds) {
                // A child in the streaming set is active regardless of cache state
                // (survives LRU eviction — the set is maintained from allSessionSignals).
                if (cid in streamingIds) return true
                val state = sessions[cid] ?: continue
                if (state.isClosed) continue
                if (now - state.lastActivityTimeMs < idleThresholdMs) return true
            }
        }
        return false
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

        val state = SessionState(sessionId, scope, this) { toolCallState, turnLifecycleState ->
            FollowAgentDispatcher(project, toolCallState, turnLifecycleState, scope, this, logger)
        }
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
                                && !childSessionTracker.hasPendingPermission(it.key) }
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
                    childSessionTracker.addKnownChild(sessionId)
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
                            && !childSessionTracker.hasPendingPermission(it.key) }
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

    internal suspend fun computeSessionContext(controlState: ControlBarState? = null): SessionContextState =
        contextComputer.compute(controlState)

    /**
     * Local-only context computation — no REST call. Used during streaming and on
     * intermediate [UiSignal.MessageUpdated] signals. Reads token/cost/model data
     * from the local message cache only; summary/time fields reuse the last loaded
     * values (they don't change mid-stream).
     *
     * Delegated to [ContextComputer.computeLocal] — thread-safe via a separate
     * in-flight guard so local refreshes are not starved by a full computation
     * blocked on REST getSession().
     */
    internal suspend fun computeSessionContextLocal(controlState: ControlBarState? = null): SessionContextState =
        contextComputer.computeLocal(controlState)

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

    /**
     * Reset smart-context state on session switch.
     * Called from [switchSession] and [createAndSwitchSession].
     */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    suspend fun resetSmartContextState() {
        contextComputer.resetBreakdownCalibration()
        contextComputer.resetPressureMonitor()
        fileReadCache.clear()
        backgroundCompactor?.clearCheckpoint()
    }

    /**
     * Handle server-side compaction: reset pressure monitor and clear background checkpoint.
     * Called when the `session.compacted` SSE event fires.
     */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    suspend fun onSessionCompacted() {
        contextComputer.onCompaction()
        backgroundCompactor?.clearCheckpoint()
    }

    /**
     * Truncate tool output if truncation is enabled in settings.
     * Called by SessionState before storing tool results.
     */
    override fun maybeTruncateToolOutput(toolName: String, output: List<JsonObject>): List<JsonObject> {
        if (!truncateToolOutputEnabled()) return output
        return ToolOutputTruncator.truncateIfNeeded(toolName, output, toolOutputCharLimit())
    }

    /** Whether the background compactor has a valid checkpoint for the active session. */
    @Suppress("DEPRECATION") // BackgroundCompactor is deprecated (auto-trigger disabled); retained as dead code
    fun isCheckpointReady(): Boolean {
        val sessionId = _activeSessionId.value ?: return false
        val context = contextComputer.sessionContextState.value
        if (context !is SessionContextState.Loaded) return false
        val providerId = context.context.providerID
        val modelId = context.context.modelID
        return backgroundCompactor?.hasValidCheckpoint(sessionId, providerId, modelId) == true
    }
}
