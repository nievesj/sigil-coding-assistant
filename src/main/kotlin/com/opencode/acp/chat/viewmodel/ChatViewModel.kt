package com.opencode.acp.chat.viewmodel

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.chat.util.generateId
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.follow.EditorFollowManager
import com.opencode.acp.mcp.ToolPermission
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewIndex
import com.opencode.acp.review.ReviewSkill
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.util.ModelArgResolver
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import kotlin.concurrent.withLock

/**
 * Thin UI wrapper around [OpenCodeService].
 *
 * Owns only UI-specific state (control bar, permission prompts, etc.).
 * Delegates all connection, session, and message operations to the service.
 * Created per tool window — safe to dispose/recreate without losing state.
 */
class ChatViewModel(
    val scope: CoroutineScope,
    private val service: OpenCodeService,
    private val project: Project
) : Closeable {

    private val logger = KotlinLogging.logger {}

    // --- Forwarded from service (read-only for UI) ---
    val messages: StateFlow<Map<String, ChatMessage>> = service.messages
    val connectionState: StateFlow<ConnectionState> = service.connectionState
    val connectionErrorReason: StateFlow<ConnectionErrorReason?> = service.connectionErrorReason
    val sessionListState: StateFlow<SessionListState> = service.sessionListState
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = service.childSessionMap
    val todoItems: StateFlow<List<TodoItem>> = service.todoItems
    val sessionContextState: StateFlow<SessionContextState> = service.sessionContextState
    val streamingSessionIds: StateFlow<Set<String>> = service.streamingSessionIds
    val pendingCreationSessionIds: StateFlow<Set<String>> = service.pendingCreationSessionIds
    val hiddenChildSessionIds: StateFlow<Set<String>> = service.hiddenChildSessionIds
    val knownChildSessionIds: StateFlow<Set<String>> = service.knownChildSessionIds
    val childToParent: StateFlow<Map<String, String>> = service.childToParent
    val sessionCachedFlow: kotlinx.coroutines.flow.SharedFlow<String> = service.sessionCachedFlow

    // --- UI-specific state ---
    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

    private val _streamPhase = MutableStateFlow(StreamPhase.IDLE)
    val streamPhase: StateFlow<StreamPhase> = _streamPhase.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt.asStateFlow()

    /** Child session permission prompts — non-blocking, keyed by child session ID.
     *  Supports multiple simultaneous pending permissions per child (FIFO list). */
    private val _childPermissionPrompts = MutableStateFlow<Map<String, List<ChildPermissionPrompt>>>(emptyMap())
    val childPermissionPrompts: StateFlow<Map<String, List<ChildPermissionPrompt>>> = _childPermissionPrompts.asStateFlow()

    private val _selectionPrompt = MutableStateFlow<SelectionPrompt?>(null)
    val selectionPrompt: StateFlow<SelectionPrompt?> = _selectionPrompt.asStateFlow()

    private val _pasteImageSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pasteImageSignal: SharedFlow<Unit> = _pasteImageSignal.asSharedFlow()

    private val _pasteTextSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pasteTextSignal: SharedFlow<String> = _pasteTextSignal.asSharedFlow()

    private val _isSidebarVisible = MutableStateFlow(OpenCodeSettingsState.getInstance().sidebarVisible)
    val isSidebarVisible: StateFlow<Boolean> = _isSidebarVisible.asStateFlow()

    /** Sidebar tab selection — survives tool window disposal/recreation within
     *  the same IDE session. Resets to SESSIONS on IDE restart (not persisted to settings). */
    private val _selectedSidebarTab = MutableStateFlow(SidebarTab.SESSIONS)
    val selectedSidebarTab: StateFlow<SidebarTab> = _selectedSidebarTab.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<CommandHistoryEntry>>(emptyList())
    val commandHistory: StateFlow<List<CommandHistoryEntry>> = _commandHistory.asStateFlow()

    private val _availableCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val availableCommands: StateFlow<List<SlashCommand>> = _availableCommands.asStateFlow()

    private val _clearAllState = MutableStateFlow<ClearAllState>(ClearAllState.Idle)
    val clearAllState: StateFlow<ClearAllState> = _clearAllState.asStateFlow()

    /** Follow Agent enabled state — synced with [EditorFollowManager]. */
    private val _followAgentEnabled = MutableStateFlow(
        EditorFollowManager.getInstance(project).isFollowEnabled()
    )
    val followAgentEnabled: StateFlow<Boolean> = _followAgentEnabled.asStateFlow()

    /** Review comment changes — forwarded from [ReviewCommentManager]. */
    val commentChangeSignal: StateFlow<ReviewIndex> by lazy {
        ReviewCommentManager.getInstance(project).commentChanges
    }

    /** Messages waiting to be sent when the current response completes (queue mode). */
    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    /** Serializes drainQueue to prevent concurrent queue-drain races. */
    private val drainMutex = Mutex()

    /** Lock for non-suspend queue mutations (queueMessage/removeQueuedMessage/
     *  editQueuedMessage/clearQueue). These are called from EDT callbacks and cannot
     *  use the suspend `drainMutex.withLock`. A plain ReentrantLock avoids signature
     *  changes while preventing the read-modify-write race with drainQueue.
     *
     *  LOCK ORDERING CONSTRAINT: Always acquire `queueLock` BEFORE `drainMutex` if
     *  both are needed. Currently, `drainQueue` acquires `drainMutex` first, then
     *  `queueLock` inside — this is safe because no EDT caller acquires `drainMutex`.
     *  DO NOT add `drainMutex` acquisition inside a `queueLock`-held block without
     *  reversing the order, or a deadlock will occur. */
    private val queueLock = java.util.concurrent.locks.ReentrantLock()

    /** Retry counts for queued messages — prevents infinite retry loops.
     *  Uses ConcurrentHashMap for thread-safety: clearQueue() may be called from
     *  EDT coroutines (session switch, cancel) while drainQueue() runs on the
     *  ViewModel scope. Both are serialized by [drainMutex] for correctness, but
     *  the ConcurrentHashMap provides a safety net against data races. */
    private val queueRetryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Next-allowed-retry timestamp (ms) per queued message id. Enforces RETRY_DELAY_MS
     *  before the next send attempt, not after the re-queue, so consecutive failures
     *  cannot bypass the retry delay. */
    private val nextRetryTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _readyState = MutableStateFlow(ReadyState.NOT_STARTED)
    val readyState: StateFlow<ReadyState> = _readyState.asStateFlow()

    /** Manual compaction UI state — Idle / InProgress / Error. */
    private val _compactionState = MutableStateFlow<CompactionState>(CompactionState.Idle)
    val compactionState: StateFlow<CompactionState> = _compactionState.asStateFlow()

    /** In-flight guard for compaction — prevents concurrent compaction requests
     *  that could corrupt server-side session state on timeout+retry. */
    private val compactionInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether a background compaction checkpoint is ready for instant swap. */
    private val _checkpointReady = MutableStateFlow(false)
    val checkpointReady: StateFlow<Boolean> = _checkpointReady.asStateFlow()

    private val initMutex = Mutex()
    private var initJob: Job? = null
    private var connectionObserverJob: Job? = null

    /** Timeout jobs for child permission prompts, keyed by child session ID. */
    private val childPermissionTimeoutJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Session IDs where the last permission POST failed (rolled back to pending).
     *  Used by the PermissionReplied handler to detect that the server DID process
     *  the response despite a local network error, and surface a notification. */
    private val failedPermissionPostSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Cached GitService instance — reuses lineDeltaCache across calls. */
    private val gitService = GitService(project)

    // --- Computed input state (exhaustive state machine) ---

    /** Last logged inputState value — used to suppress duplicate log lines. */
    @Volatile private var lastLoggedInputState: ChatInputState? = null

    /**
     * Exhaustive input-area state derived from connection, streaming, and prompt StateFlows.
     * Composables switch on this instead of combining booleans.
     * Priority: Disabled > AwaitingPermission > AwaitingSelection > Streaming > Idle
     */
    // combine emits on any flow change; setting permissionPrompt/selectionPrompt triggers
    // re-derivation immediately. No race — the prompt StateFlows are part of the combine.
    val inputState: StateFlow<ChatInputState> = combine(
        connectionState,
        readyState,
        permissionPrompt,
        selectionPrompt,
        streamPhase,
    ) { conn, ready, perm, sel, phase ->
        val state = when {
            conn != ConnectionState.CONNECTED -> ChatInputState.Disabled
            ready != ReadyState.READY -> ChatInputState.Disabled
            perm != null -> ChatInputState.AwaitingPermission(perm)
            sel != null -> ChatInputState.AwaitingSelection(sel)
            phase == StreamPhase.SENDING -> ChatInputState.Sending
            phase == StreamPhase.STREAMING -> ChatInputState.Streaming
            else -> ChatInputState.Idle
        }
        // Only log when the derived state actually changes — avoids excessive
        // logging on every combine emission (e.g., during streaming when phase
        // toggles but the derived state stays the same).
        if (state != lastLoggedInputState) {
            logger.info { "[ACP] inputState: conn=$conn ready=$ready perm=$perm sel=$sel phase=$phase → $state" }
            lastLoggedInputState = state
        }
        state
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChatInputState.Disabled)

    /** True when the active session is a sub-task/child session (parentID != null).
     *  Used to disable the input area — sub sessions are agent-only. */
    val isActiveSessionChild: StateFlow<Boolean> = combine(
        service.activeSessionId,
        knownChildSessionIds,
    ) { activeId, knownChildren ->
        activeId != null && activeId in knownChildren
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /** Parent session ID of the active session, or null if the active session is not a child.
     *  Used for the "Back to parent" button in the sub-session banner. */
    val activeSessionParentId: StateFlow<String?> = combine(
        service.activeSessionId,
        childToParent,
    ) { activeId, childToParentMap ->
        activeId?.let { childToParentMap[it] }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val _fileChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fileChangeSignal: SharedFlow<Unit> = _fileChangeSignal.asSharedFlow()

    // --- Init ---

    init {
        // Log message count changes for diagnostics
        scope.launch {
            service.messages.sample(1000).collect { msgMap ->
                if (msgMap.isNotEmpty()) {
                    val userCount = msgMap.values.count { it.role == MessageRole.USER }
                    val assistantCount = msgMap.values.count { it.role == MessageRole.ASSISTANT }
                    logger.info { "[ACP] VM messages snapshot: ${msgMap.size} total (${userCount} user, $assistantCount assistant), ids=${msgMap.keys.toList().takeLast(3).joinToString(",")}" }
                } else {
                    logger.info { "[ACP] VM messages snapshot: EMPTY map" }
                }
            }
        }

        // Collect active session signals and update UI state
        scope.launch {
            service.signals.collect { signal ->
                when (signal) {
                    is UiSignal.StreamingStarted -> {
                        logger.info { "[ACP] signal: StreamingStarted → _streamPhase = STREAMING (current=${_streamPhase.value})" }
                        _streamPhase.value = StreamPhase.STREAMING
                    }
                    is UiSignal.StreamingCompleted -> {
                        logger.info { "[ACP] signal: StreamingCompleted → _streamPhase = IDLE (current=${_streamPhase.value})" }
                        _streamPhase.value = StreamPhase.IDLE
                        // IDE notification — only when user is not looking at the IDE
                        OpenCodeNotifications.notifyResponseComplete(project)
                        // Refresh context and todos after response completes.
                        // Ordered sequentially: loadSessions must complete before
                        // drainQueue runs (drainQueue sends the next queued message,
                        // which depends on the session list being up to date).
                        scope.launch {
                            computeSessionContext()
                            fetchTodos()
                            service.loadSessions()
                            drainQueue()
                        }
                        // Refresh review comments — the LLM may have written .review/ files
                        // during the response. StreamingCompleted fires once per response
                        // (after all tool calls finish), so this is the correct timing point.
                        // Not gated on fileChanges because .review/ files can be written by
                        // non-edit tools (bash, custom review tools, undetected kinds).
                        refreshReviewFiles()
                    }
                    is UiSignal.PermissionRequested -> {
                        _permissionPrompt.value = signal.prompt
                        OpenCodeNotifications.notifyPermissionNeeded(project)
                        // NOTE: timeoutSeconds is read at call time from settings. If the user
                        // changes the setting while a prompt is pending, the current timeout
                        // is NOT restarted — the new value applies to the next prompt.
                        startPermissionTimeout()
                    }
                    is UiSignal.SelectionRequested -> {
                        _selectionPrompt.value = signal.prompt
                        OpenCodeNotifications.notifyQuestionAsked(project)
                    }
                    // Informational signals — processor handles state updates directly
                    is UiSignal.Error -> Unit
                    is UiSignal.TodoUpdated -> Unit
                    is UiSignal.FileChanged -> {
                        _fileChangeSignal.tryEmit(Unit)
                        // Trigger a VFS refresh so the ReviewCommentManager's
                        // AsyncFileListener picks up any new .review/ JSON files
                        // written by the LLM agent. Without this, gutter icons
                        // and highlights don't appear until the user manually
                        // refreshes (Ctrl+Alt+Y) or alt-tabs away and back.
                        refreshReviewFiles()
                    }
                    is UiSignal.MessageUpdated -> {
                        // Intermediate token/cost update — local-only refresh (no REST).
                        // Keeps the context indicator live during streaming without
                        // spamming the server. Full REST refresh on StreamingCompleted.
                        scope.launch { computeSessionContextLocal() }
                    }
                    // Global-only signals — should not arrive on activeSignals,
                    // but must be present for exhaustive when.
                    is UiSignal.SessionCreated -> Unit
                    is UiSignal.SessionIdle -> Unit
                    is UiSignal.SessionError -> Unit
                    is UiSignal.SessionCompacted -> Unit
                    is UiSignal.ChildPermissionRequested -> Unit
                    is UiSignal.PermissionReplied -> Unit
                    is UiSignal.PermissionTimedOut -> Unit
                }
            }
        }

        // Collect global signals (SessionCreated, etc.)
        scope.launch {
            service.globalSignals.collect { signal ->
                when (signal) {
                    is UiSignal.SessionCreated -> {
                        scope.launch { service.loadSessions() }
                    }
                    is UiSignal.SessionIdle -> {
                        // Server-authoritative idle signal — refresh context immediately
                        // (eliminates the 300ms debounce dependency from StreamingCompleted)
                        scope.launch { computeSessionContext() }
                    }
                    is UiSignal.SessionError -> {
                        // SessionState's SseEvent.SessionError handler now finalizes
                        // streaming and emits StreamingCompleted (which completes
                        // responseDeferred and transitions to IDLE via the signals
                        // collector above). This handler is a belt-and-suspenders reset
                        // for the edge case where StreamingCompleted was already emitted
                        // (streamingCompletedEmitted guard) and the phase is stuck.
                        //
                        // Reset phase for any session error — the signal carries the
                        // sessionId that errored. Using service.sessionId (mutable) here
                        // could miss the reset if the user switched sessions between
                        // signal emission and this handler. Resetting is idempotent.
                        _streamPhase.value = StreamPhase.IDLE
                        // Always remove the specific session from shimmer (not just active)
                        service.removeStreamingSession(signal.sessionId)
                        logger.warn { "[ACP] ViewModel received session error: session=${signal.sessionId}, error=${signal.errorMessage}" }
                    }
                    is UiSignal.SessionCompacted -> {
                        // Server performed auto-compaction — local message cache is stale.
                        // Refresh messages from server, then recompute context.
                        scope.launch {
                            service.refreshActiveSessionMessages()
                            computeSessionContext()
                        }
                    }
                    is UiSignal.ChildPermissionRequested -> {
                        val childId = signal.prompt.childSessionId
                        _childPermissionPrompts.update { prompts ->
                            val existing = prompts[childId] ?: emptyList()
                            prompts + (childId to (existing + signal.prompt))
                        }
                        OpenCodeNotifications.notifyPermissionNeeded(project)
                        startChildPermissionTimeout(childId, signal.prompt.toolName)
                        // NOTE: Do NOT change _streamPhase or inputState — child permissions are non-blocking
                    }
                    is UiSignal.PermissionReplied -> {
                        // Confirm server processed our reply
                        if (_permissionPrompt.value?.permissionId == signal.permissionId) {
                            // Active-session permission: clear it
                            _permissionPrompt.value = null
                            service.permissionManager.cancelPermissionTimeout()
                        }
                        // Handle child prompts based on reply type
                        if (_childPermissionPrompts.value.containsKey(signal.sessionId)) {
                            // If the last POST for this session failed (rolled back to pending),
                            // the server still processed it. Surface a notification instead of
                            // silently clearing (TDD §4.2.4 error handling).
                            if (signal.sessionId in failedPermissionPostSessions) {
                                OpenCodeNotifications.notifyPermissionProcessedDespiteError(project, signal.sessionId)
                                // Remove AFTER the reject check below — the reject cascade
                                // needs to know about the failed POST for notification.
                            }
                            if (signal.reply == "reject") {
                                // Cascade: server rejects all pending permissions in the session
                                _childPermissionPrompts.update { it - signal.sessionId }
                                cancelChildPermissionTimeout(signal.sessionId)
                            } else {
                                // Non-reject reply: remove only the FIRST prompt (FIFO)
                                _childPermissionPrompts.update { prompts ->
                                    val remaining = (prompts[signal.sessionId] ?: emptyList()).drop(1)
                                    if (remaining.isEmpty()) prompts - signal.sessionId
                                    else prompts + (signal.sessionId to remaining)
                                }
                                if (!_childPermissionPrompts.value.containsKey(signal.sessionId)) {
                                    // No more prompts — cancel the timeout
                                    cancelChildPermissionTimeout(signal.sessionId)
                                } else {
                                    // Remaining prompts — restart the timeout for the new first prompt
                                    val newFirst = _childPermissionPrompts.value[signal.sessionId]?.firstOrNull()
                                    if (newFirst != null) {
                                        startChildPermissionTimeout(signal.sessionId, newFirst.toolName)
                                    }
                                }
                            }
                            // Now safe to remove — notification has been shown and reject cascade is done
                            failedPermissionPostSessions.remove(signal.sessionId)
                        }
                    }
                    is UiSignal.PermissionTimedOut -> {
                        OpenCodeNotifications.notifyPermissionTimedOut(project, signal.toolName)
                        // Only clear active prompt if the timeout is for the active session's
                        // permission (non-empty permissionId matching the current prompt).
                        // Child permission timeouts use permissionId="" — they must NOT
                        // clear the active session's permission prompt.
                        if (signal.permissionId.isNotEmpty() && _permissionPrompt.value?.permissionId == signal.permissionId) {
                            _permissionPrompt.value = null
                        }
                        // Clear child prompts for the timed-out child session.
                        // POST reject to the server for each remaining pending prompt before
                        // clearing locally — the server is still waiting on Deferred promises
                        // and would block indefinitely without a reply.
                        if (signal.sessionId.isNotEmpty()) {
                            val pending = _childPermissionPrompts.value[signal.sessionId] ?: emptyList()
                            if (pending.isNotEmpty()) {
                                // Use service.scope (not ViewModel scope) so reject POSTs survive
                                // tool window disposal — the server's Deferred promises must be
                                // resolved even if the user closes the tool window.
                                service.scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        pending.forEach { p ->
                                            try {
                                                service.permissionManager.respondPermission(
                                                    p.permissionId, p.toolCallId, signal.sessionId,
                                                    PermissionResponse.REJECT_ONCE,
                                                )
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                logger.warn(e) { "[ACP] Timeout reject failed for permission ${p.permissionId}" }
                                            }
                                        }
                                    }
                                }
                            }
                            _childPermissionPrompts.update { it - signal.sessionId }
                            cancelChildPermissionTimeout(signal.sessionId)
                        }
                    }
                    else -> { /* other global signals */ }
                }
            }
        }

        // Load persisted command history
        val settings = OpenCodeSettingsState.getInstance()
        _commandHistory.value = ArrayList(settings.commandHistory)

        // Observe connectionState and reset readyState on disconnect/reconnect/error.
        // On auto-reconnect (CONNECTED after NOT_STARTED), re-run initialize().
        connectionObserverJob = scope.launch {
            connectionState.collect { state ->
                when (state) {
                    ConnectionState.DISCONNECTED,
                    ConnectionState.RECONNECTING,
                    ConnectionState.ERROR -> {
                        if (_readyState.value != ReadyState.NOT_STARTED) {
                            logger.info { "[ACP] ReadyState: ${_readyState.value} → NOT_STARTED (connectionState=$state)" }
                            _readyState.value = ReadyState.NOT_STARTED
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        // Auto-reconnect: if readyState is NOT_STARTED after a reconnect,
                        // re-run initialization to reload agents/providers/MCP tools.
                        // Cancel any in-progress init to prevent the old job from racing
                        // with the new one (the old job may be about to set READY on a
                        // stale connection).
                        if (_readyState.value == ReadyState.NOT_STARTED) {
                            initJob?.cancel()
                            logger.info { "[ACP] Auto-reconnect detected — re-running initialize()" }
                            try {
                                initialize(projectBasePath = null)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Auto-reconnect initialize() failed" }
                            }
                        }
                    }
                    else -> { /* CONNECTING — no action */ }
                }
            }
        }
    }

    // --- Initialization ---

    fun resetReadyState() {
        _readyState.value = ReadyState.NOT_STARTED
    }

    suspend fun initialize(projectBasePath: String?) {
        // Guard against concurrent initialization. Intentionally uses tryLock()
        // instead of lock() because: (1) rapid reconnection events (network flapping)
        // should not queue up N initialization attempts, (2) the connectionObserverJob
        // will re-trigger on the next CONNECTED event if the first attempt fails,
        // and (3) initialization is idempotent — running it twice wastes resources
        // but doesn't cause correctness issues.
        //
        // Note: retryConnection calls initJob?.cancel() then initJob?.join() before
        // calling initialize(). The join() ensures the old job's finally block
        // (initMutex.unlock()) has run, so the mutex is released before the new
        // initialize() call acquires it.
        if (!initMutex.tryLock()) {
            logger.warn { "[ACP] initialize() already in progress — user click acknowledged but deduplicated (current readyState=${_readyState.value})" }
            // NOTE: The user gets no UI feedback that their click was deduplicated.
            // The Connect button should be disabled while init is in progress.
            // This is a known UX limitation — the connectionObserverJob will re-trigger
            // on the next CONNECTED event if the first attempt fails.
            return
        }

        try {
            initJob = scope.launch {
                _readyState.value = ReadyState.NOT_STARTED

                try {
                    _readyState.value = ReadyState.INITIALIZING_SERVICE
                    val success = try {
                        service.initialize(projectBasePath)
                    } catch (e: Exception) {
                        logger.error(e) { "[ACP] Service initialization failed" }
                        false
                    }
                    if (!success) {
                        _readyState.value = ReadyState.NOT_STARTED
                        return@launch
                    }

                    _readyState.value = ReadyState.LOADING_AGENTS
                    try {
                        val agents = withTimeoutOrNull(30_000) { service.listAgents() }
                        if (agents == null) {
                            logger.warn { "[ACP] Agent loading timed out after 30s — continuing with defaults" }
                        } else {
                            val filtered = agents.filter { it.mode != "subagent" && it.hidden != true }
                            _controlState.value = _controlState.value.copy(
                                agents = filtered.map { info ->
                                    OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                                }
                            )
                            val defaultAgentInfo = filtered.firstOrNull { it.name == "orchestrator" }
                                ?: filtered.firstOrNull()
                            if (defaultAgentInfo != null) {
                                val agentInfo = _controlState.value.agents.find { it.id == defaultAgentInfo.id }
                                if (agentInfo != null) {
                                    _controlState.value = _controlState.value.copy(selectedAgent = agentInfo)
                                }
                            }
                            logger.info { "[ACP] ReadyState: LOADING_AGENTS → LOADING_PROVIDERS (agents=${filtered.size} loaded, defaultAgent=${defaultAgentInfo?.id})" }
                        }
                    } catch (e: Exception) {
                        logger.warn { "[ACP] Agent loading failed: ${e.message}" }
                    }
                    // Always progress — agent loading is optional (chat works with defaults)

                    _readyState.value = ReadyState.LOADING_PROVIDERS
                    try {
                        val providers = withTimeoutOrNull(30_000) { service.listProviders() }
                        if (providers == null) {
                            logger.warn { "[ACP] Provider loading timed out after 30s" }
                        } else {
                            val connectedIds = providers.connected.toSet()
                            fun buildProviderModels(providerList: List<com.opencode.acp.adapter.ProviderData>): List<ProviderModel> {
                                return providerList.flatMap { provider ->
                                    provider.models.map { (_, modelData) ->
                                        ProviderModel(
                                            providerID = provider.id,
                                            modelID = modelData.id,
                                            displayName = "${provider.name} / ${modelData.name}",
                                            reasoning = modelData.reasoning,
                                            contextWindow = modelData.limit?.context ?: 0,
                                            providerIconId = provider.id,
                                            variants = modelData.variants?.keys?.toList() ?: emptyList()
                                        )
                                    }
                                }
                            }
                            val models = buildProviderModels(providers.all.filter { it.id in connectedIds })
                            val allModels = buildProviderModels(providers.all)
                            val savedKey = OpenCodeSettingsState.getInstance().lastSelectedModelKey
                            val restoredModel = if (savedKey.isNotEmpty()) {
                                models.find {
                                    OpenCodeSettingsState.modelKey(it.providerID, it.modelID) == savedKey
                                }
                            } else null
                            _controlState.value = _controlState.value.copy(
                                models = models,
                                allModels = allModels,
                                selectedModel = restoredModel ?: models.firstOrNull()
                            )
                            // Restore persisted agent selection
                            val savedAgentId = OpenCodeSettingsState.getInstance().lastSelectedAgent
                            if (savedAgentId.isNotEmpty()) {
                                val restoredAgent = _controlState.value.agents.find { it.id == savedAgentId }
                                if (restoredAgent != null) {
                                    _controlState.value = _controlState.value.copy(selectedAgent = restoredAgent)
                                }
                            }
                            // Restore persisted thinking effort
                            val savedEffortName = OpenCodeSettingsState.getInstance().lastSelectedThinkingEffort
                            if (savedEffortName.isNotEmpty()) {
                                val restoredEffort = ThinkingEffort.entries.firstOrNull { it.name == savedEffortName }
                                if (restoredEffort != null) {
                                    _controlState.value = _controlState.value.copy(thinkingEffort = restoredEffort)
                                }
                            }
                            logger.info { "[ACP] ReadyState: LOADING_PROVIDERS → LOADING_MCP (providers=${providers.all.size} loaded)" }
                        }
                    } catch (e: Exception) {
                        logger.warn { "[ACP] Provider loading failed: ${e.message}" }
                    }

                    _readyState.value = ReadyState.LOADING_MCP
                    try {
                        val registry = service.toolRegistry
                        if (registry != null) {
                            val baseUrl = "http://127.0.0.1:${service.connectionManager.port}"
                            val mcpUrls = service.mcpManager?.getServerUrls() ?: emptyMap()
                            withTimeoutOrNull(60_000) {
                                registry.discoverAll(baseUrl, mcpUrls)
                                // Merge persisted permissions after discovery
                                val persisted = loadPersistedPermissions()
                                if (persisted.isNotEmpty()) {
                                    registry.loadEnabledAndPermissions(persisted)
                                }
                                // Update McpManager tool counts from discovered tools
                                service.mcpManager?.updateToolCounts(registry)
                            } ?: logger.warn { "[ACP] MCP tool discovery timed out after 60s" }
                        }
                    } catch (e: Exception) {
                        logger.warn { "[ACP] MCP tool discovery failed: ${e.message}" }
                    }

                    computeSessionContext()
                    fetchTodos()
                    fetchAvailableCommands()

                    logger.info { "[ACP] ReadyState: LOADING_MCP → READY" }
                    _readyState.value = ReadyState.READY
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safety net for agent/provider/MCP loading steps that throw outside
                    // their inner try/catch blocks. Prevents unhandled exceptions from
                    // crashing callers (retryConnection, connectionObserverJob).
                    logger.error(e) { "[ACP] initialize() failed" }
                    _readyState.value = ReadyState.NOT_STARTED
                    return@launch
                }
            }
            initJob?.join()
        } finally {
            initMutex.unlock()
        }
    }

    fun cancelInitialization() {
        initJob?.cancel()
        initJob = null
        _readyState.value = ReadyState.NOT_STARTED
        service.stopConnection()
    }

    // --- Control bar actions ---

    fun selectAgent(agent: OpenCodeAgentInfo) {
        _controlState.value = _controlState.value.copy(selectedAgent = agent)
        OpenCodeSettingsState.getInstance().lastSelectedAgent = agent.id
    }

    fun selectModel(model: ProviderModel) {
        _controlState.value = _controlState.value.copy(selectedModel = model)
        val settings = OpenCodeSettingsState.getInstance()
        settings.lastSelectedModelKey = OpenCodeSettingsState.modelKey(model.providerID, model.modelID)
    }

    fun selectThinkingEffort(effort: ThinkingEffort) {
        _controlState.value = _controlState.value.copy(thinkingEffort = effort)
        OpenCodeSettingsState.getInstance().lastSelectedThinkingEffort = effort.name
    }

    fun toggleSidebar() {
        val newValue = !_isSidebarVisible.value
        _isSidebarVisible.value = newValue
        OpenCodeSettingsState.getInstance().sidebarVisible = newValue
    }

    fun selectSidebarTab(tab: SidebarTab) {
        _selectedSidebarTab.value = tab
    }

    // --- Session actions ---

    suspend fun loadSessions() = service.loadSessions()

    suspend fun switchSession(sessionId: String) {
        service.switchSession(sessionId)
        // Un-hide the target session if it was a hidden child (ToolPill "open child" path).
        service.unhideChildSession(sessionId)
        // Derive _streamPhase from the new session's actual streaming state.
        val isCreating = service.pendingCreationSessionIds.value.contains(sessionId)
        val isActiveStreaming = service.streamingSessionIds.value.contains(sessionId)
        _streamPhase.value = when {
            isActiveStreaming -> StreamPhase.STREAMING
            // Use IDLE for pending-creation sessions — the session is being created
            // on the server, not sending a message. Using SENDING would show a
            // misleading stop button. The phase will transition to STREAMING when
            // the first message is sent and StreamingStarted fires.
            isCreating -> StreamPhase.IDLE
            else -> StreamPhase.IDLE
        }
        clearQueue()

        // Sync prompt state from the new session's persistent StateFlows.
        // Cosmetic brief window: snapshots are read atomically here, but a
        // concurrent SSE event could update the source StateFlow between this
        // read and the assignment. The next SSE event will re-sync, so this
        // is not a correctness issue.
        val activeSession = service.sessionManager.getActiveSession()
        _permissionPrompt.value = activeSession?.pendingPermission?.value
        _selectionPrompt.value = activeSession?.pendingSelection?.value

        // If a permission prompt was restored, restart the timeout
        if (_permissionPrompt.value != null) {
            startPermissionTimeout()
        }
    }

    suspend fun createAndSwitchSession(title: String? = null) {
        service.createAndSwitchSession(title)
        // New session — definitely not streaming yet
        _streamPhase.value = StreamPhase.IDLE
        clearQueue()
        val activeSession = service.sessionManager.getActiveSession()
        _permissionPrompt.value = activeSession?.pendingPermission?.value
        _selectionPrompt.value = activeSession?.pendingSelection?.value
    }

    suspend fun archiveSession(sessionId: String) = service.archiveSession(sessionId)

    fun loadMoreSessions() = service.loadMoreSessions()

    /** Tracks the current clear-all operation so a new call cancels the old one.
     *  Prevents the race where the old call's 2s delay overwrites the new call's state. */
    private var clearAllJob: Job? = null

    fun clearAllSessions() {
        // Done state is shown for 2s; interruption by a new clear-all is expected
        // behavior (the old job is cancelled and the new one starts). The Done
        // state IS visible for 2s unless interrupted by another clear-all call.
        clearAllJob?.cancel()
        clearAllJob = scope.launch {
            val loaded = sessionListState.value as? SessionListState.Loaded ?: return@launch
            // Forward SessionManager's live progress (ClearAllState.InProgress(deleted, total))
            // to the UI's clearAllState so the footer shows "Deleting N of M..." during the
            // sequential deletion loop. The collector runs concurrently with the suspend
            // clearAllSessions() call below and is cancelled in finally when the operation
            // completes. For small session counts the loop is fast enough that only the
            // final Done state is visible; for large counts (50+) this gives real feedback.
            val progressJob = scope.launch {
                service.sessionManager.clearAllProgress.collect { progress ->
                    if (progress is ClearAllState.InProgress) {
                        _clearAllState.value = progress
                    }
                }
            }
            try {
                val result = service.clearAllSessions()
                _clearAllState.value = ClearAllState.Done(result)
                delay(2000)
                // Only reset to Idle if still Done — a concurrent clearAllSessions() call
                // may have set InProgress during the delay.
                if (_clearAllState.value is ClearAllState.Done) {
                    _clearAllState.value = ClearAllState.Idle
                }
            } finally {
                progressJob.cancel()
                // Reset state if coroutine was cancelled during delay (e.g., tool window
                // disposal) — prevents _clearAllState from staying at Done/InProgress forever.
                val current = _clearAllState.value
                if (current is ClearAllState.Done || current is ClearAllState.InProgress) {
                    _clearAllState.value = ClearAllState.Idle
                }
            }
        }
    }

    // --- Message sending ---

    suspend fun sendMessage(text: String, files: List<AttachedFile> = emptyList()): SendMessageResult {
        val state = _controlState.value
        return sendMessageWithModel(
            text = text,
            files = files,
            modelID = state.selectedModel?.modelID,
            providerID = state.selectedModel?.providerID,
            variant = state.thinkingEffort.variant,
            agent = state.selectedAgent?.id,
            model = state.selectedModel?.let {
                OpenCodeClient.MessageModel(providerID = it.providerID, modelID = it.modelID)
            }
        )
    }

    /**
     * Send a message with explicit model/agent parameters. Used by
     * [executeMultiModelReview] to send review prompts with specific models
     * (different from the control-bar selected model) while still routing
     * through the ViewModel so [_streamPhase] and streamingSessionIds stay
     * consistent with the UI.
     */
    suspend fun sendMessageWithModel(
        text: String,
        files: List<AttachedFile> = emptyList(),
        modelID: String? = null,
        providerID: String? = null,
        variant: String? = null,
        agent: String? = null,
        model: OpenCodeClient.MessageModel? = null
    ): SendMessageResult {
        recordCommand(text, files)

        // Activate streaming indicators BEFORE the suspend call.
        // This ensures the glow, stop button, pulse, and shimmer appear
        // immediately when the user clicks Send, not after the first token.
        logger.info { "[ACP] sendMessage: setting _streamPhase = SENDING (current=${_streamPhase.value})" }
        _streamPhase.value = StreamPhase.SENDING
        val sessionId = service.sessionId
        if (sessionId != null) {
            service.addStreamingSession(sessionId)
        }

        return try {
            val result = service.sendMessage(
                text = text,
                files = files,
                modelID = modelID,
                providerID = providerID,
                variant = variant,
                agent = agent,
                model = model
            )

            when (result) {
                is SendMessageResult.Success -> {
                    // Phase is now STREAMING (set by StreamingStarted signal during
                    // the suspend). If no signal arrived (e.g., server returned
                    // immediately), StreamingCompleted already set it to IDLE.
                    // No action needed here — the signal collector owns the phase.
                }
                is SendMessageResult.Error -> {
                    _streamPhase.value = StreamPhase.IDLE
                    sessionId?.let { service.removeStreamingSession(it) }
                }
            }
            result
        } catch (e: Exception) {
            _streamPhase.value = StreamPhase.IDLE
            sessionId?.let { service.removeStreamingSession(it) }
            throw e
        }
    }

    suspend fun cancel() {
        // Capture session ID before the suspend call — service.sessionId could
        // change if a session switch happens during the cancel.
        val sessionId = service.sessionId
        // Set the multi-model review cancellation flag so the review loop
        // stops after the current model finishes.
        multiModelReviewCancelled = true
        service.cancel()
        _streamPhase.value = StreamPhase.IDLE
        sessionId?.let { service.removeStreamingSession(it) }
        clearQueue()
    }

    /**
     * Send a message while streaming is in progress (steering/nudging).
     * Auto-aborts the current response, waits for the send mutex to be released,
     * then sends as a fresh message via [sendMessage].
     *
     * Does NOT call recordCommand() — that's handled by sendMessage().
     * Does NOT set _streamPhase to IDLE before sendMessage() — sendMessage()
     * sets SENDING which overwrites STREAMING directly, avoiding a visible
     * IDLE flicker. The StreamingCompleted from the old turn (arriving async
     * via SSE) may briefly set IDLE, but sendMessage() has already set SENDING.
     */
    suspend fun steerMessage(text: String, files: List<AttachedFile> = emptyList()) {
        // Abort in-progress response and get a signal for when the mutex is released
        val readyDeferred = service.steerCancel()

        // Await the signal — deterministic, not a fixed delay.
        // Resumes as soon as the old sendMessage() releases the sendMutex.
        withTimeoutOrNull(MAX_STEER_WAIT_MS) {
            readyDeferred.await()
        } ?: run {
            // Safety net: mutex not released after MAX_STEER_WAIT_MS
            logger.error { "[ACP] steerMessage: mutex not released after ${MAX_STEER_WAIT_MS}ms — giving up" }
            service.cancel()
            _streamPhase.value = StreamPhase.IDLE
            service.sessionId?.let { service.removeStreamingSession(it) }
            logger.warn { "[ACP] steerMessage: timed out after ${MAX_STEER_WAIT_MS}ms" }
            service.injectLocalMessage("⚠️ Could not send steering message — timed out. Please try again.")
            return
        }

        // Session guard: if the user switched sessions during the steer,
        // don't send the message to the wrong session.
        val currentSessionId = service.sessionId
        if (currentSessionId == null) {
            logger.warn { "[ACP] steerMessage: session lost during steer" }
            _streamPhase.value = StreamPhase.IDLE
            // No removeStreamingSession needed — currentSessionId is null,
            // so there's no streaming session to clean up.
            service.injectLocalMessage("⚠️ Could not send message — session was lost. Please try again.")
            return
        }

        // Re-check session ID right before sending: between readyDeferred.await()
        // and this point, the user could have switched sessions. If the session
        // changed, don't send the steer message to the wrong session.
        if (service.sessionId != currentSessionId) {
            logger.warn { "[ACP] steerMessage: session changed during steer (was=$currentSessionId, now=${service.sessionId})" }
            _streamPhase.value = StreamPhase.IDLE
            currentSessionId.let { service.removeStreamingSession(it) }
            service.injectLocalMessage("⚠️ Could not send message — session changed during steer. Please try again.")
            return
        }

        // Now the mutex is free — send through the normal path.
        // sendMessage() handles: recordCommand, _streamPhase = SENDING, error handling.
        // It overwrites STREAMING with SENDING — no IDLE flicker.
        sendMessage(text, files)
    }

    // --- Message Queue (queue mode) ---

    /**
     * Add a message to the queue instead of sending it immediately.
     * Used when streaming is active (SENDING or STREAMING phase) and queue mode is enabled.
     * The message will be auto-sent when the current response completes.
     */
    fun queueMessage(text: String, files: List<AttachedFile> = emptyList()) {
        queueLock.withLock {
            val msg = QueuedMessage(
                id = generateId(),
                text = text,
                files = files
            )
            _queuedMessages.value = _queuedMessages.value + msg
            logger.info { "[ACP] queueMessage: queued '${text.take(50)}' (${_queuedMessages.value.size} in queue)" }
        }
    }

    /**
     * Remove a queued message by ID (user cancelled it from the queue bar).
     */
    fun removeQueuedMessage(messageId: String) {
        queueLock.withLock {
            _queuedMessages.value = _queuedMessages.value.filter { it.id != messageId }
            logger.info { "[ACP] removeQueuedMessage: $messageId (${_queuedMessages.value.size} remaining)" }
        }
    }

    /**
     * Edit a queued message's text (user clicked edit in the queue bar).
     */
    fun editQueuedMessage(messageId: String, newText: String) {
        queueLock.withLock {
            _queuedMessages.value = _queuedMessages.value.map {
                if (it.id == messageId) it.copy(text = newText) else it
            }
        }
    }

    /**
     * Clear all queued messages. Called on session switch, cancel, etc.
     */
    fun clearQueue() {
        queueLock.withLock {
            val count = _queuedMessages.value.size
            if (count > 0) {
                _queuedMessages.value = emptyList()
                queueRetryCounts.clear()
                nextRetryTime.clear()
                logger.info { "[ACP] clearQueue: cleared $count queued messages" }
            }
        }
    }

    /**
     * Drain the queue — send the next queued message if any.
     * Called automatically when StreamingCompleted fires and queue is non-empty.
     * Serialized by [drainMutex] to prevent concurrent queue-drain races.
     *
     * RETRY LIMIT: Failed messages are re-queued at most [MAX_QUEUE_RETRIES]
     * times before being dropped. This prevents infinite retry loops when the
     * server is unavailable.
     */
    private suspend fun drainQueue() = drainMutex.withLock {
        val next: QueuedMessage?
        queueLock.withLock {
            val queue = _queuedMessages.value
            if (queue.isEmpty()) {
                next = null
                return@withLock
            }
            next = queue.first()
            _queuedMessages.value = queue.drop(1)
        }
        if (next == null) return@withLock
        // Enforce retry delay BEFORE sending, not after re-queue. Without this,
        // a re-queued message would be re-sent immediately on the next drainQueue
        // call (triggered by the next StreamingCompleted), bypassing RETRY_DELAY_MS.
        val retryAt = nextRetryTime[next.id]
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            // Not yet time to retry — re-queue at end and return without sending.
            queueLock.withLock {
                _queuedMessages.value = _queuedMessages.value + next
            }
            return@withLock
        }
        logger.info { "[ACP] drainQueue: sending '${next.text.take(50)}' (${_queuedMessages.value.size} remaining)" }

        val result = try {
            sendMessage(next.text, next.files)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] drainQueue: sendMessage threw exception" }
            SendMessageResult.Error(e.message ?: "Send failed")
        }
        if (result is SendMessageResult.Error) {
            queueLock.withLock {
                // Check retry count inside queueLock to prevent race with clearQueue
                // (which clears queueRetryCounts under queueLock as well)
                val retryCount = queueRetryCounts.getOrDefault(next.id, 0) + 1
                if (retryCount <= MAX_QUEUE_RETRIES) {
                    queueRetryCounts[next.id] = retryCount
                    // Record next-allowed-retry timestamp so the delay is enforced
                    // before the next send attempt (checked at the top of drainQueue).
                    nextRetryTime[next.id] = System.currentTimeMillis() + RETRY_DELAY_MS
                    val alreadyRequeued = _queuedMessages.value.any { it.id == next.id }
                    if (!alreadyRequeued) {
                        // Re-queue at the END of the queue (not the front) to avoid
                        // starving later messages. The retry delay already gives
                        // the server time to recover, so the message doesn't need
                        // priority over others.
                        _queuedMessages.value = _queuedMessages.value + next
                        logger.warn { "[ACP] drainQueue: re-queued failed message at end of queue (attempt $retryCount/$MAX_QUEUE_RETRIES) — ${result.message}" }
                    } else {
                        logger.debug { "[ACP] drainQueue: message ${next.id} already re-queued, skipping duplicate add (attempt $retryCount/$MAX_QUEUE_RETRIES)" }
                    }
                } else {
                    queueRetryCounts.remove(next.id)
                    nextRetryTime.remove(next.id)
                    logger.error { "[ACP] drainQueue: dropping message after $MAX_QUEUE_RETRIES failed attempts — ${result.message}" }
                }
            }
        } else {
            queueRetryCounts.remove(next.id)
            nextRetryTime.remove(next.id)
        }
    }

    // --- Permission/Selection ---

    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        try {
            service.respondPermission(
                prompt.permissionId, prompt.toolCallId, prompt.sessionId, response,
                toolName = prompt.toolName,
                patterns = prompt.patterns,
                agentName = _controlState.value.selectedAgent?.id ?: "orchestrator",
            )
            // POST succeeded — clear any prior failed-post tracking
            failedPermissionPostSessions.remove(prompt.sessionId)
            _permissionPrompt.value = null
            service.permissionManager.cancelPermissionTimeout()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Permission response failed (network error, server down). Keep the
            // prompt open so the user can retry. Track the failure so the
            // PermissionReplied handler can surface a notification if the server
            // still processed it despite the network error (TDD §4.2.4).
            failedPermissionPostSessions.add(prompt.sessionId)
            logger.warn(e) { "[ACP] Permission response failed — keeping prompt open for retry" }
        }
    }

    suspend fun respondChildPermission(childSessionId: String, response: PermissionResponse) {
        val prompts = _childPermissionPrompts.value[childSessionId] ?: return
        val prompt = prompts.first()  // FIFO — respond to oldest first
        try {
            service.permissionManager.respondPermission(
                prompt.permissionId,
                prompt.toolCallId,
                childSessionId,  // reply goes to the CHILD session
                response,
                prompt.toolName,
                prompt.patterns,
                // Only pass the real agent name for config sync when the label was
                // verified from a Subtask SSE event. If the label is the fallback
                // "sub-agent" (Subtask event missed), pass empty string so
                // writeAlwaysAllowRule is skipped (toolName.isNotEmpty() guard
                // in PermissionManager + isValidConfigKey in McpConfigWriter).
                if (prompt.agentLabelVerified) prompt.subAgentLabel else "",
            )
            // POST succeeded — clear any prior failed-post tracking
            failedPermissionPostSessions.remove(childSessionId)
            if (response == PermissionResponse.REJECT_ONCE) {
                // CASCADE: clear ALL prompts for this child session
                // (server rejects all pending permissions in the session)
                _childPermissionPrompts.update { it - childSessionId }
                cancelChildPermissionTimeout(childSessionId)
                logger.info { "[ACP] Cascade rejection: clearing all prompts for childSessionId=$childSessionId" }
            } else {
                // Remove just this prompt; keep others if any
                // Verified: success path correctly restarts timeout for the next prompt
                // if more prompts remain (line ~1093). failedPermissionPostSessions
                // is cleared before prompt-clearing — safe per StateFlow update semantics.
                _childPermissionPrompts.update { prompts ->
                    val remaining = (prompts[childSessionId] ?: emptyList()).drop(1)
                    if (remaining.isEmpty()) prompts - childSessionId
                    else prompts + (childSessionId to remaining)
                }
                if (!_childPermissionPrompts.value.containsKey(childSessionId)) {
                    // No more prompts — cancel the timeout
                    cancelChildPermissionTimeout(childSessionId)
                } else {
                    // Remaining prompts — restart the timeout for the new first prompt
                    val newFirst = _childPermissionPrompts.value[childSessionId]?.firstOrNull()
                    if (newFirst != null) {
                        startChildPermissionTimeout(childSessionId, newFirst.toolName)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // POST failed — track this session so the PermissionReplied handler
            // can surface a notification if the server still processed it.
            failedPermissionPostSessions.add(childSessionId)
            logger.warn(e) { "[ACP] Child permission response failed — keeping prompt open" }
        }
    }

    private fun startPermissionTimeout() {
        val toolName = _permissionPrompt.value?.toolName ?: ""
        service.permissionManager.startPermissionTimeout(
            timeoutSeconds = OpenCodeSettingsState.getInstance().state.permissionTimeoutSeconds,
            toolName = toolName,
        ) {
            // Emit a PermissionTimedOut signal instead of silently clearing
            val permId = _permissionPrompt.value?.permissionId ?: ""
            val sid = _permissionPrompt.value?.sessionId ?: ""
            _permissionPrompt.value = null
            OpenCodeNotifications.notifyPermissionTimedOut(project, toolName)
            logger.info { "[ACP] Permission timed out: permissionId=$permId, tool=$toolName" }
        }
    }

    private fun startChildPermissionTimeout(childSessionId: String, toolName: String) {
        val timeoutSeconds = ChatConstants.CHILD_PERMISSION_TIMEOUT_SECONDS
        childPermissionTimeoutJobs[childSessionId]?.cancel()
        // Use the service scope (survives tool window recreation) so the timeout
        // fires even if the tool window is closed and reopened. The timeout job
        // emits a PermissionTimedOut signal via the service's globalSignals so
        // whichever ViewModel is active will handle the cleanup.
        childPermissionTimeoutJobs[childSessionId] = service.scope.launch {
            delay(timeoutSeconds * 1000L)
            // Clear the prompt and notify — emit via globalSignals so the
            // active ViewModel (which may be a different instance if the tool
            // window was recreated) handles cleanup.
            service.sessionManager.emitGlobalSignal(
                UiSignal.PermissionTimedOut(
                    permissionId = "",
                    sessionId = childSessionId,
                    toolName = toolName,
                )
            )
            childPermissionTimeoutJobs.remove(childSessionId)
            logger.info { "[ACP] Child permission timed out: childSessionId=$childSessionId, tool=$toolName" }
        }
    }

    private fun cancelChildPermissionTimeout(childSessionId: String) {
        childPermissionTimeoutJobs.remove(childSessionId)?.cancel()
    }

    fun respondSelection(response: SelectionResponse) {
        val prompt = _selectionPrompt.value ?: return
        scope.launch {
            try {
                val selectedLabels = response.selectedIndices.mapNotNull { idx ->
                    prompt.options.getOrNull(idx)?.label ?: run {
                        logger.warn { "[ACP] respondSelection: index $idx out of bounds (options size=${prompt.options.size}) — selection may be stale" }
                        null
                    }
                }
                // The server expects one inner array per question. Merge custom input
                // into the SAME inner list as selected labels (not a separate array),
                // producing [[label1, label2, customInput]] instead of
                // [[label1, label2], [customInput]].
                val answers = mutableListOf<List<String>>()
                val combined = selectedLabels.toMutableList()
                response.customInput?.let { custom ->
                    if (custom.isNotBlank()) combined.add(custom)
                }
                if (combined.isNotEmpty()) {
                    answers.add(combined)
                }
                if (answers.isEmpty()) {
                    service.rejectQuestion(prompt.promptId, prompt.sessionId)
                } else {
                    service.respondQuestion(prompt.promptId, answers, prompt.sessionId)
                }
                _selectionPrompt.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to respond to question ${prompt.promptId}" }
                // Clear the prompt so the UI doesn't show a stale prompt that can't
                // be retried — SessionState already cleared its pendingSelection.
                _selectionPrompt.value = null
            }
        }
    }

    // --- Paste signals ---

    fun requestImagePaste() {
        _pasteImageSignal.tryEmit(Unit)
    }

    fun requestTextPaste(text: String) {
        _pasteTextSignal.tryEmit(text)
    }

    // --- Context ---

    suspend fun computeSessionContext() {
        service.computeSessionContext(_controlState.value)
        _checkpointReady.value = service.isCheckpointReady()
    }

    private suspend fun computeSessionContextLocal() {
        service.computeSessionContextLocal(_controlState.value)
    }

    fun retryContextFetch() {
        scope.launch { computeSessionContext() }
    }

    /**
     * Trigger manual compaction for the active session.
     * Calls POST /session/{id}/summarize with auto=false.
     *
     * NOTE: The OpenCode server does NOT support a `guidance` field — the request
     * body is only `{ providerID, modelID, auto }`. The TDD originally specified
     * guidance features, but they were dropped after research confirmed the server
     * ignores unknown fields.
     */
    fun compactSession() {
        val sessionId = service.sessionId
        if (sessionId == null) {
            _compactionState.value = CompactionState.Error(CompactionError.NoActiveSession)
            return
        }
        val client = service.connectionManager.client
        if (client == null) {
            _compactionState.value = CompactionState.Error(CompactionError.NotConnected)
            return
        }

        // In-flight guard: prevent concurrent compaction requests. If the server
        // is already processing a compaction (e.g., from a timed-out retry), sending
        // a second request could corrupt session state.
        if (!compactionInProgress.compareAndSet(false, true)) {
            logger.warn { "[ACP] Manual compaction rejected — already in progress" }
            return
        }

        logger.info { "[ACP] Manual compaction triggered for session $sessionId" }
        _compactionState.value = CompactionState.InProgress

        // The guard is reset in the launched coroutine's finally block below.
        scope.launch {
            try {
                val state = _controlState.value
                val providerID = state.selectedModel?.providerID ?: ""
                val modelID = state.selectedModel?.modelID ?: ""
                if (providerID.isBlank() || modelID.isBlank()) {
                    _compactionState.value = CompactionState.Error(
                        CompactionError.ServerError("No model selected")
                    )
                    return@launch
                }
                val success = kotlinx.coroutines.withTimeout(
                    com.opencode.acp.chat.model.CompactionConstants.COMPACT_TIMEOUT_MS
                ) {
                    client.compactSession(sessionId, providerID, modelID, auto = false)
                }
                if (success) {
                    _compactionState.value = CompactionState.Idle
                    // SSE session.compacted event handles message refresh + context update
                    logger.info { "[ACP] Manual compaction succeeded for session $sessionId" }
                } else {
                    _compactionState.value = CompactionState.Error(
                        CompactionError.ServerError("Compaction failed")
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _compactionState.value = CompactionState.Error(CompactionError.Timeout)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.ConnectException) {
                _compactionState.value = CompactionState.Error(CompactionError.NotConnected)
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                // Detect timeout-like exceptions from Ktor
                if (msg.contains("timeout", ignoreCase = true) || e is java.net.SocketTimeoutException) {
                    _compactionState.value = CompactionState.Error(CompactionError.Timeout)
                } else {
                    _compactionState.value = CompactionState.Error(CompactionError.ServerError(msg))
                }
            } finally {
                compactionInProgress.set(false)
            }
        }
    }

    /** Reset compaction state to Idle (e.g., after user dismisses an error). */
    fun resetCompactionState() {
        _compactionState.value = CompactionState.Idle
    }

    // --- Todos ---

    private suspend fun fetchTodos() = service.fetchTodos()

    // --- Commands ---

    fun fetchAvailableCommands() {
        scope.launch {
            _availableCommands.value = service.fetchAvailableCommands()
        }
    }

    fun executeServerCommand(commandName: String, args: String = "") {
        scope.launch {
            service.executeServerCommand(commandName, args)
        }
    }

    /** Execute `/review-perform [model...]` — instructs the LLM to adversarially
     *  review the VCS-changed files and add review comments to `.review/` JSON files.
     *
     *  ## Model selection
     *
     *  - **No args** (`/review-perform`): uses the currently-selected control-bar
     *    model. Backward-compatible with the original behavior.
     *  - **One or more model args** (`/review-perform glm5.2 claude-sonnet`):
     *    each arg is fuzzy-matched against the server-fetched model list
     *    ([ModelArgResolver]) and the review prompt is sent once per matched
     *    model, **sequentially** (each response completes before the next starts).
     *    Each response is prefixed with a `### Review by <model>` header so the
     *    user can compare findings across models in the same chat thread.
     *  - **`*` wildcard** (`/review-perform *`): runs the review on all available
     *    models. Use with caution — can be slow and costly.
     *  - **Unresolved args**: if any arg doesn't match a model, an error message
     *    is shown in the chat and only the resolved models run (or the control-bar
     *    model if none resolved). */
    fun executeReviewPerformCommand(args: String = "") {
        scope.launch {
            // GitService.getChangedFiles must run inside a read action.
            // Uses Dispatchers.IO because runReadActionBlocking may spin-wait
            // for a write action to complete — IO threads handle blocking.
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildPerformPrompt(changedPaths)
            executeMultiModelReview(args, prompt)
        }
    }

    /** Execute `/review-perform-gaming [model...]` — like
     *  [executeReviewPerformCommand] but injects the game-engine-specific
     *  adversarial checklist (Unreal C++ GC/threading/lifecycle, Unity C#
     *  allocations/coroutines/leaks, frame budgets, Blueprint interop,
     *  replication). Model arg handling is identical to
     *  [executeReviewPerformCommand]. */
    fun executeReviewPerformGamingCommand(args: String = "") {
        scope.launch {
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildPerformGamingPrompt(changedPaths)
            executeMultiModelReview(args, prompt)
        }
    }

    /** Cancellation flag for multi-model review loops. Set by [cancel] to
     *  stop the loop after the current model finishes. */
    @Volatile private var multiModelReviewCancelled = false

    /** Shared logic for both review-perform variants: resolve model args and
     *  send the prompt once per model (or once with the control-bar model if
     *  no args). Sequential — each [sendMessage] blocks until that model's
     *  response completes (via the service's sendMutex + responseDeferred).
     *
     *  The user can cancel the loop mid-way by clicking the Cancel button,
     *  which sets [multiModelReviewCancelled]. The loop checks this flag
     *  between iterations and stops if set. */
    private suspend fun executeMultiModelReview(args: String, prompt: String) {
        multiModelReviewCancelled = false
        if (args.isBlank()) {
            // No model args — use the currently-selected control-bar model.
            // Route through the ViewModel's sendMessage() so _streamPhase and
            // streamingSessionIds stay consistent with the UI.
            sendMessage(text = prompt)
            return
        }

        // Use connected-providers models only (controlState.models), NOT
        // allModels — allModels includes disconnected providers whose models
        // would 500 when sent to the server.
        val models = _controlState.value.models
        val resolution = ModelArgResolver.resolveAll(args, models)

        // Surface unresolved args as a chat message so the user sees the typo.
        if (resolution.unresolved.isNotEmpty()) {
            val unresolvedStr = resolution.unresolved.joinToString(", ") { "`$it`" }
            val availableHints = models.take(5).joinToString(", ") {
                "`${it.providerID}/${it.modelID}`"
            }
            val errorMsg = "[User Notification] ⚠️ Could not resolve model(s): $unresolvedStr. " +
                "Available models include: $availableHints" +
                if (models.size > 5) ", …" else "."
            service.injectLocalMessage(errorMsg)
        }

        if (resolution.models.isEmpty()) {
            // Nothing resolved — don't run a review with the wrong model silently.
            return
        }

        // Send one review per model, sequentially.
        // For reasoning models that have variants, pick the first variant
        // (or the control-bar's current thinking effort if the model supports it).
        val currentVariant = _controlState.value.thinkingEffort.variant
        for (model in resolution.models) {
            currentCoroutineContext().ensureActive()
            // Check if the user cancelled the review loop (via Cancel button).
            if (multiModelReviewCancelled) {
                service.injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                break
            }
            // If the model has variants and the current thinking effort isn't
            // null, use it. Otherwise pick the first variant if available, or
            // null (server default) if the model has no variants.
            val variant = when {
                model.variants.isEmpty() -> null
                currentVariant != null && currentVariant in model.variants -> currentVariant
                else -> model.variants.firstOrNull()
            }
            val header = "### Review by ${model.displayName}\n\n"
            // Re-check cancellation flag immediately before send to close TOCTOU window
            // (cancel() may have set the flag between the loop-top check and this point).
            if (multiModelReviewCancelled) {
                service.injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                break
            }
            // Route through the ViewModel's sendMessageWithModel() so _streamPhase and
            // streamingSessionIds stay consistent with the UI (stop button, shimmer).
            val result = sendMessageWithModel(
                text = header + prompt,
                modelID = model.modelID,
                providerID = model.providerID,
                variant = variant,
                model = OpenCodeClient.MessageModel(providerID = model.providerID, modelID = model.modelID)
            )
            // If a review fails (timeout, error), stop the loop — no point
            // continuing with the remaining models if the session is in a
            // bad state.
            if (result is SendMessageResult.Error) {
                service.injectLocalMessage(
                    "⚠️ Review with ${model.displayName} failed: ${result.message}. " +
                        "Remaining models skipped."
                )
                break
            }
        }
        // Reset the cancellation flag after the loop completes (or breaks on
        // error/cancel) so the next review invocation starts with a clean state.
        multiModelReviewCancelled = false
    }

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow. */
    fun executeReviewResolveCommand() {
        scope.launch {
            val index = ReviewCommentManager.getInstance(project).getIndex()
            // Route through the ViewModel's sendMessage() so _streamPhase,
            // streamingSessionIds, and recordCommand() stay consistent with the UI.
            sendMessage(text = ReviewSkill.buildResolvePrompt(index))
        }
    }

    /** Execute `/review-recheck [model...]` — re-runs the adversarial review with
     *  existing comments + replies as context. The LLM verifies replies against
     *  the actual code, re-raises unresolved issues, marks resolved comments, and
     *  adds new comments. Model arg handling is identical to
     *  [executeReviewPerformCommand] (via [executeMultiModelReview]).
     *
     *  ## Reply preservation safety net
     *
     *  After the LLM finishes and [refreshReviewFiles] re-reads the `.review/` files,
     *  the plugin verifies no pre-existing replies were dropped by the LLM's file
     *  rewrite and re-merges any that were via [ReviewCommentManager.restoreMissingReplies].
     *  This is a structural guarantee independent of prompt compliance — see TDD §4. */
    fun executeReviewRecheckCommand(args: String = "") {
        scope.launch {
            val manager = ReviewCommentManager.getInstance(project)
            val preRecheckIndex = manager.getIndex()
            val replySnapshot = manager.snapshotReplyIds(preRecheckIndex)
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildRecheckPrompt(preRecheckIndex, changedPaths)
            try {
                executeMultiModelReview(args, prompt)
            } finally {
                // After the LLM writes updated .review/ files, refresh the index
                // and WAIT for loadAll() to finish before checking for dropped replies.
                // The restore reads stateHolder.value which must reflect the post-LLM state.
                refreshReviewFiles().join()
                // Structural safety net: re-merge any replies the LLM dropped.
                val restored = manager.restoreMissingReplies(replySnapshot, preRecheckIndex)
                if (restored > 0) {
                    logger.warn { "[ACP] /review-recheck restored $restored dropped reply(ies)" }
                    refreshReviewFiles()
                }
            }
        }
    }

    /** Trigger a VFS refresh AND a direct disk re-read so the [ReviewCommentManager]
     *  picks up any new `.review/` JSON files written by the LLM agent.
     *
     *  Two paths:
     *  1. `asyncRefresh(null)` — triggers VFS discovery → AsyncFileListener re-reads
     *     changed files incrementally. Fire-and-forget; may miss newly-created
     *     `.review/` subdirectories.
     *  2. `loadAll()` — reads ALL `.review/` files from disk via
     *     `java.nio.file.Files.walk()`, bypassing VFS. Reliable but O(all-files).
     *     Runs on ReviewCommentManager's internal scope (non-blocking).
     *
     *  Without this, gutter icons and highlights don't appear after `/review-perform`
     *  until the user manually refreshes (Ctrl+Alt+Y) or restarts the IDE. */
    private fun refreshReviewFiles(): kotlinx.coroutines.Job {
        // 1. Direct disk re-read — reads ALL .review/ files via java.nio.file.Files.walk(),
        // bypassing VFS entirely. This is the reliable primary path: even if VFS hasn't
        // discovered a newly-created .review/ subdirectory, loadAll() still picks it up.
        // Runs on ReviewCommentManager's internal scope (Dispatchers.Default) — non-blocking.
        val manager = com.opencode.acp.review.ReviewCommentManager.getInstance(project)
        val job = manager.scope.launch { manager.loadAll() }
        // 2. Trigger VFS refresh so the ReviewCommentFileWatcher's AsyncFileListener
        // can do incremental re-reads for any later changes (secondary path).
        com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null)
        return job
    }

    /** Get messages StateFlow for a child session (used by ToolPill for task pills). */
    fun getSessionMessages(sessionId: String) = service.getSessionMessages(sessionId)

    fun getStreamingText(sessionId: String) = service.getStreamingText(sessionId)

    // --- Command history ---

    /** Lock for command history mutations — prevents EDT blocking from synchronized(settings). */
    private val commandHistoryLock = Any()

    private fun recordCommand(text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        val entry = CommandHistoryEntry(text = text, files = files)
        val settings = OpenCodeSettingsState.getInstance()
        val maxSize = settings.commandHistorySize.coerceIn(1, 100)
        // Synchronize on a dedicated lock to prevent lost-update race with clearCommandHistory
        // (which also writes settings.commandHistory from EDT). Using `settings` as the
        // monitor could block the EDT if a background coroutine holds it; the dedicated
        // lock avoids coupling EDT blocking to settings object monitor contention.
        synchronized(commandHistoryLock) {
            val current = _commandHistory.value.toMutableList()
            // Compute the new file lists once outside removeAll to avoid creating
            // 3 intermediate lists per existing-entry comparison (O(history×files)
            // → O(history+files) allocations).
            val names = files.map { it.name }
            val paths = files.map { it.path }
            val mimes = files.map { it.mime }
            current.removeAll { existing ->
                existing.text == text &&
                    existing.attachedFileNames == names &&
                    existing.attachedFilePaths == paths &&
                    existing.attachedFileMimes == mimes
            }
            current.add(0, entry)
            val trimmed = if (current.size > maxSize) current.take(maxSize) else current
            _commandHistory.value = trimmed
            settings.commandHistory = java.util.ArrayList(trimmed)
        }
    }

    fun clearCommandHistory() {
        val settings = OpenCodeSettingsState.getInstance()
        synchronized(commandHistoryLock) {
            _commandHistory.value = emptyList()
            settings.commandHistory = java.util.ArrayList()
        }
    }

    // --- Retry ---

    suspend fun retryConnection(projectBasePath: String?) {
        // Cancel any in-flight initialization before retrying. Without this,
        // a stuck or slow init job holds initMutex, and initialize() silently
        // no-ops on tryLock() — making the Retry button appear dead.
        initJob?.cancel()
        initJob?.join()
        service.connectionManager.resetForRetry()
        resetReadyState()
        initialize(projectBasePath)
    }

    suspend fun connect(projectBasePath: String?) {
        if (connectionState.value == ConnectionState.CONNECTED || 
            connectionState.value == ConnectionState.CONNECTING) {
            return
        }
        // Cancel any in-flight initialization before connecting. Same reason
        // as retryConnection: a stale init job would block the new attempt.
        initJob?.cancel()
        initJob?.join()
        initialize(projectBasePath)
    }

    fun stopConnection() {
        service.stopConnection()
    }

    // --- Follow Agent toggle ---

    fun toggleFollowAgent() {
        val newValue = !_followAgentEnabled.value
        EditorFollowManager.getInstance(project).setFollowEnabled(newValue)
        _followAgentEnabled.value = newValue
        logger.info { "[ACP] Follow agent toggled: $newValue" }
    }

    // --- Permission persistence ---

    private fun loadPersistedPermissions(): Map<String, Pair<Boolean, ToolPermission>> {
        val settings = OpenCodeSettingsState.getInstance()
        val permsJson = settings.toolPermissions
        if (permsJson.isBlank()) return emptyMap()
        // Delegate to the service's parser to avoid duplicating JSON parsing logic.
        // The service returns Map<String, Pair<Boolean, String>> (string permission);
        // map the string permission to ToolPermission here.
        return try {
            service.parsePersistedToolPermissions(permsJson).mapValues { (_, pair) ->
                Pair(pair.first, ToolPermission.fromActionString(pair.second))
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to parse persisted tool permissions — corrupted settings, clearing" }
            emptyMap()
        }
    }

    // --- Cleanup ---

    override fun close() {
        connectionObserverJob?.cancel()
        initJob?.cancel()
        service.permissionManager.cancelPermissionTimeout()
        // Cancel child permission timeout jobs — they run on service.scope which
        // survives tool window recreation, but the prompts they guard are ViewModel-
        // scoped and lost on disposal. Without cancellation, the jobs fire after
        // disposal and emit PermissionTimedOut to globalSignals, but no ViewModel is
        // collecting (replay=0 SharedFlow drops the signal).
        childPermissionTimeoutJobs.values.forEach { it.cancel() }
        childPermissionTimeoutJobs.clear()
        // Clear child permission prompts to prevent stale StateFlow values lingering
        // in the old ViewModel after tool window recreation/disposal.
        _childPermissionPrompts.value = emptyMap()
    }

    // --- Helpers ---

    companion object {
        private const val MAX_STEER_WAIT_MS = 10_000L
        private const val MAX_QUEUE_RETRIES = 3
        /** Delay before re-queuing a failed message to prevent rapid retry loops. */
        private const val RETRY_DELAY_MS = 2_000L
    }
}
