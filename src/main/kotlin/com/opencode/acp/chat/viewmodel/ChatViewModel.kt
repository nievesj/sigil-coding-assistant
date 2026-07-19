package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.follow.EditorFollowManager
import com.opencode.acp.mcp.ToolPermission
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewIndex
import com.opencode.acp.chat.service.GitService
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/**
 * Thin UI wrapper around [OpenCodeService].
 *
 * Owns only UI-specific state (control bar, permission prompts, etc.).
 * Delegates all connection, session, and message operations to the service.
 * Created per tool window — safe to dispose/recreate without losing state.
 *
 * ISP fix (TDD §4.2.5): Does NOT implement [java.io.Closeable] — the plugin
 * lifecycle is managed explicitly via [close] called from the tool window's
 * content disposer ([ChatToolWindowFactory]).
 *
 * NOTE (SRP): This class owns multiple responsibilities (control bar state, stream phase,
 * permission delegation, sidebar state, command history, compaction state, etc.).
 * Future refactoring should extract: (1) ControlBarViewModel for model/agent/thinking state,
 * (2) SidebarViewModel for sidebar/tab state, (3) CompactionViewModel for compaction state.
 * The class already delegates to PermissionViewModel, CommandHistory Manager, and MessageQueueManager.
 */
class ChatViewModel(
    val scope: CoroutineScope,
    private val service: OpenCodeServiceApi,
    private val project: Project
) {

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
    // Control bar state (agent/model/thinking effort) — delegated to ControlBarViewModel
    // (TDD §9 step 6, Phase 3). ChatViewModel exposes the same public API via delegation.
    private val controlBarViewModel = ControlBarViewModel(
        service = service,
        settings = OpenCodeSettingsState.getInstance(),
    )
    val controlState: StateFlow<ControlBarState> = controlBarViewModel.controlState

    private val _streamPhase = MutableStateFlow(StreamPhase.IDLE)
    val streamPhase: StateFlow<StreamPhase> = _streamPhase.asStateFlow()

    // --- Permission/Selection state (delegated to PermissionViewModel) ---
    private val permissionViewModel = PermissionViewModel(
        scope, service, project,
        braveModeProvider = { _braveModeEnabled.value },
    )
    val permissionPrompt: StateFlow<PermissionPrompt?> = permissionViewModel.permissionPrompt
    val childPermissionPrompts: StateFlow<Map<String, List<ChildPermissionPrompt>>> = permissionViewModel.childPermissionPrompts
    val selectionPrompt: StateFlow<SelectionPrompt?> = permissionViewModel.selectionPrompt

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

    // --- Command history (delegated to CommandHistoryManager) ---
    private val commandHistoryManager = CommandHistoryManager()
    val commandHistory: StateFlow<List<CommandHistoryEntry>> = commandHistoryManager.commandHistory

    private val _availableCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val availableCommands: StateFlow<List<SlashCommand>> = _availableCommands.asStateFlow()

    private val _clearAllState = MutableStateFlow<ClearAllState>(ClearAllState.Idle)
    val clearAllState: StateFlow<ClearAllState> = _clearAllState.asStateFlow()

    /** Follow Agent enabled state — synced with [EditorFollowManager]. */
    private val _followAgentEnabled = MutableStateFlow(
        EditorFollowManager.getInstance(project).isFollowEnabled()
    )
    val followAgentEnabled: StateFlow<Boolean> = _followAgentEnabled.asStateFlow()

    /** Brave Mode enabled state — synced with [OpenCodeFollowSettingsState].
     *  When ON, all permission prompts are auto-approved with ALLOW_ONCE
     *  without showing the UI dialog. The server still enforces explicit
     *  `deny` rules, so Brave Mode cannot override hard denials. */
    private val _braveModeEnabled = MutableStateFlow(
        OpenCodeFollowSettingsState.getInstance().braveModeEnabled
    ).also { logger.info { "[ACP] Brave Mode initial state from settings: ${it.value}" } }
    val braveModeEnabled: StateFlow<Boolean> = _braveModeEnabled.asStateFlow()

    /** Review comment changes — forwarded from [ReviewCommentManager]. */
    val commentChangeSignal: StateFlow<ReviewIndex> by lazy {
        ReviewCommentManager.getInstance(project).commentChanges
    }

    // --- Message queue (delegated to MessageQueueManager) ---
    private val messageQueueManager = MessageQueueManager(
        sendFunction = { msg -> sendMessage(msg.text, msg.files) }
    )
    val queuedMessages: StateFlow<List<QueuedMessage>> = messageQueueManager.queuedMessages

    // --- Signal routing (delegated to SignalRouter + SignalSideEffectExecutor) ---
    // Extracted per TDD §9 step 5 (Phase 3). The router maps UiSignal → SignalEffect
    // (pure, no state); the executor runs each effect with injected dependencies.
    // The StreamingCompleted ordered side effects are preserved exactly:
    //   SetStreamPhaseIdle → NotifyResponseComplete (conditional) →
    //   ComputeSessionContext → FetchTodos → LoadSessions → DrainQueue →
    //   RefreshReviewFiles.
    private val signalRouter = SignalRouter(scope)
    private val signalSideEffectExecutor = SignalSideEffectExecutor(
        service = service,
        project = project,
        permissionViewModel = permissionViewModel,
        messageQueueManager = messageQueueManager,
        refreshReviewFiles = { refreshReviewFiles() },
        computeSessionContext = { computeSessionContext() },
        fetchTodos = { fetchTodos() },
        computeSessionContextLocal = { computeSessionContextLocal() },
        setStreamPhaseIdle = { _streamPhase.value = StreamPhase.IDLE },
        // Gate on sessionId: only reset the ACTIVE session's stream phase if the SessionError
        // was for the active session. Without this gate, a SessionError on an inactive/background
        // session would incorrectly reset the active session's stream phase, potentially hiding
        // the stop button while the active session is still streaming.
        setStreamPhaseIdleForSession = { sessionId ->
            if (sessionId == service.activeSessionId.value) {
                _streamPhase.value = StreamPhase.IDLE
            }
        },
        emitFileChangeSignal = { _fileChangeSignal.tryEmit(Unit) },
        isActiveSessionChild = { isActiveSessionChild.value },
        isActiveMessage = { messageId -> service.messages.value.containsKey(messageId) },
        scope = scope,
    )

    private val _readyState = MutableStateFlow(ReadyState.NOT_STARTED)
    val readyState: StateFlow<ReadyState> = _readyState.asStateFlow()

    /** Manual compaction UI state — Idle / InProgress / Error.
     *  Delegated to [CompactionViewModel] (TDD §9 step 6, Phase 3). */
    private val compactionViewModel = CompactionViewModel(
        service = service,
        scope = scope,
        selectedModelProvider = { controlBarViewModel.controlState.value.selectedModel },
    )
    val compactionState: StateFlow<CompactionState> = compactionViewModel.compactionState

    /** Whether a background compaction checkpoint is ready for instant swap.
     *  Owned by [CompactionViewModel]; written by [computeSessionContext] via
     *  `compactionViewModel.setCheckpointReady()`. */
    val checkpointReady: StateFlow<Boolean> = compactionViewModel.checkpointReady

    private val initMutex = Mutex()
    private var initJob: Job? = null
    private var connectionObserverJob: Job? = null

    /** Cached GitService instance — reuses lineDeltaCache across calls. */
    private val gitService = GitService(project)

    // --- Computed input state (exhaustive state machine) ---

    /** Last logged inputState value — used to suppress duplicate log lines. */
    private val lastLoggedInputState = java.util.concurrent.atomic.AtomicReference<ChatInputState?>(null)

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
        // Use compareAndSet for a true dedup — prevents duplicate log lines from
        // concurrent combine() emissions racing on the @Volatile field.
        if (lastLoggedInputState.getAndSet(state) != state) {
            logger.info { "[ACP] inputState: conn=$conn ready=$ready perm=$perm sel=$sel phase=$phase → $state" }
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

        // Collect active session signals and update UI state.
        //
        // Signal routing is delegated to [signalRouter] + [signalSideEffectExecutor]
        // (TDD §9 step 5, Phase 3). The router maps UiSignal → SignalEffect (pure,
        // no state); the executor runs each effect with injected dependencies.
        //
        // The StreamingCompleted ordered side effects are preserved exactly:
        //   SetStreamPhaseIdle → NotifyResponseComplete (conditional) →
        //   ComputeSessionContext → FetchTodos → LoadSessions → DrainQueue →
        //   RefreshReviewFiles.
        //
        // StreamingStarted is handled inline here (simple StateFlow update, no
        // side effect) — the router only emits effects for side-effectful ops.
        // The router also collects service.signals but its StreamingStarted
        // branch is a no-op, so there's no conflict.
        scope.launch {
            service.signals.collect { signal ->
                if (signal is UiSignal.StreamingStarted) {
                    logger.info { "[ACP] signal: StreamingStarted → _streamPhase = STREAMING (current=${_streamPhase.value})" }
                    _streamPhase.value = StreamPhase.STREAMING
                }
            }
        }

        // Collect SessionCompacted from globalSignals to release the compaction
        // in-flight guard early (short-circuits the COMPACT_TIMEOUT_BACKOFF_MS
        // backoff when the server confirms the compaction). The router's
        // RefreshActiveSessionMessages + ComputeSessionContext effects still run
        // for message refresh; this collector only handles the guard release.
        scope.launch {
            service.globalSignals.collect { signal ->
                if (signal is UiSignal.SessionCompacted) {
                    compactionViewModel.onSessionCompacted()
                }
            }
        }

        // Start the SignalRouter → SignalSideEffectExecutor pipeline.
        // The router collects service.signals and service.globalSignals and
        // emits SignalEffect values; the executor collects the effects and
        // runs each with the injected dependencies.
        signalRouter.start(service.signals, service.globalSignals)
        signalSideEffectExecutor.start(signalRouter.effects)

        // Load persisted command history
        commandHistoryManager.loadFromSettings()

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
                        controlBarViewModel.loadAgentsAndProviders()
                        logger.info { "[ACP] ReadyState: LOADING_AGENTS → LOADING_PROVIDERS (agents=${controlBarViewModel.controlState.value.agents.size} loaded, defaultAgent=${controlBarViewModel.controlState.value.selectedAgent?.id})" }
                    } catch (e: Exception) {
                        logger.warn { "[ACP] Agent/provider loading failed: ${e.message}" }
                    }
                    // Always progress — agent loading is optional (chat works with defaults)

                    _readyState.value = ReadyState.LOADING_PROVIDERS
                    // Provider loading is performed inside controlBarViewModel.loadAgentsAndProviders()
                    // (it loads agents then providers in sequence). The LOADING_PROVIDERS state is
                    // kept for UI continuity — it briefly shows after loadAgentsAndProviders()
                    // returns before transitioning to LOADING_MCP.
                    logger.info { "[ACP] ReadyState: LOADING_PROVIDERS → LOADING_MCP (providers=${controlBarViewModel.controlState.value.models.size} loaded)" }

                    _readyState.value = ReadyState.LOADING_MCP
                    try {
                        val registry = service.toolRegistry
                        if (registry != null) {
                            val baseUrl = "http://${service.connectionManager.host}:${service.connectionManager.port}"
                            val mcpUrls = service.mcpManager?.getServerUrls() ?: emptyMap()
                            withTimeoutOrNull(60_000) {
                                registry.discoverAll(baseUrl, mcpUrls)
                                // Merge persisted permissions after discovery.
                                // loadPersistedPermissions() applies fail-closed
                                // (all tools → ASK) if the persisted JSON is corrupted.
                                val persisted = loadPersistedPermissions(registry)
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

    fun selectAgent(agent: OpenCodeAgentInfo) = controlBarViewModel.selectAgent(agent)

    fun selectModel(model: ProviderModel) = controlBarViewModel.selectModel(model)

    fun selectThinkingEffort(effort: ThinkingEffort) = controlBarViewModel.selectThinkingEffort(effort)

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
        messageQueueManager.clearQueue()

        // Sync prompt state from the new session's persistent StateFlows.
        // Cosmetic brief window: snapshots are read atomically here, but a
        // concurrent SSE event could update the source StateFlow between this
        // read and the assignment. The next SSE event will re-sync, so this
        // is not a correctness issue.
        val activeSession = service.sessionManager.getActiveSession()
        permissionViewModel.setPermissionPrompt(activeSession?.pendingPermission?.value)
        permissionViewModel.setSelectionPrompt(activeSession?.pendingSelection?.value)

        // If a permission prompt was restored, restart the timeout
        if (permissionViewModel.permissionPrompt.value != null) {
            permissionViewModel.startPermissionTimeout()
        }
    }

    suspend fun createAndSwitchSession(title: String? = null) {
        service.createAndSwitchSession(title)
        // New session — definitely not streaming yet
        _streamPhase.value = StreamPhase.IDLE
        messageQueueManager.clearQueue()
        val activeSession = service.sessionManager.getActiveSession()
        permissionViewModel.setPermissionPrompt(activeSession?.pendingPermission?.value)
        permissionViewModel.setSelectionPrompt(activeSession?.pendingSelection?.value)
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
        val state = controlBarViewModel.controlState.value
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
        commandHistoryManager.recordCommand(text, files)

        // Activate streaming indicators BEFORE the suspend call.
        // This ensures the glow, stop button, pulse, and spinner appear
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
        messageQueueManager.clearQueue()
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

    // --- Message Queue (queue mode) — delegated to MessageQueueManager ---

    /**
     * Add a message to the queue instead of sending it immediately.
     * Used when streaming is active (SENDING or STREAMING phase) and queue mode is enabled.
     * The message will be auto-sent when the current response completes.
     */
    fun queueMessage(text: String, files: List<AttachedFile> = emptyList()) =
        messageQueueManager.queueMessage(text, files)

    /**
     * Remove a queued message by ID (user cancelled it from the queue bar).
     */
    fun removeQueuedMessage(messageId: String) =
        messageQueueManager.removeQueuedMessage(messageId)

    /**
     * Edit a queued message's text (user clicked edit in the queue bar).
     */
    fun editQueuedMessage(messageId: String, newText: String) =
        messageQueueManager.editQueuedMessage(messageId, newText)

    /**
     * Clear all queued messages. Called on session switch, cancel, etc.
     */
    fun clearQueue() = messageQueueManager.clearQueue()

    // --- Permission/Selection — delegated to PermissionViewModel ---

    suspend fun respondPermission(response: PermissionResponse) {
        permissionViewModel.respondPermission(
            response,
            agentName = controlBarViewModel.controlState.value.selectedAgent?.id ?: "orchestrator",
        )
    }

    suspend fun respondChildPermission(childSessionId: String, response: PermissionResponse) =
        permissionViewModel.respondChildPermission(childSessionId, response)

    fun respondSelection(response: SelectionResponse) =
        permissionViewModel.respondSelection(response)

    // --- Paste signals ---

    fun requestImagePaste() {
        _pasteImageSignal.tryEmit(Unit)
    }

    fun requestTextPaste(text: String) {
        _pasteTextSignal.tryEmit(text)
    }

    // --- Context ---

    suspend fun computeSessionContext() {
        service.computeSessionContext(controlBarViewModel.controlState.value)
        compactionViewModel.setCheckpointReady(service.isCheckpointReady())
    }

    private suspend fun computeSessionContextLocal() {
        service.computeSessionContextLocal(controlBarViewModel.controlState.value)
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
    fun compactSession() = compactionViewModel.compactSession()

    /** Reset compaction state to Idle (e.g., after user dismisses an error). */
    fun resetCompactionState() = compactionViewModel.resetCompactionState()

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

    // --- Review commands — delegated to ReviewCommandHandler ---

    /** Cancellation flag for multi-model review loops. Set by [cancel] to
     *  stop the loop after the current model finishes. */
    @Volatile private var multiModelReviewCancelled = false

    private val reviewCommandHandler = ReviewCommandHandler(
        scope = scope,
        project = project,
        gitService = gitService,
        controlStateProvider = { controlBarViewModel.controlState.value },
        sendFunction = { text, files -> sendMessage(text, files) },
        sendWithModelFunction = { text, modelID, providerID, variant, model ->
            sendMessageWithModel(
                text = text,
                modelID = modelID,
                providerID = providerID,
                variant = variant,
                model = model,
            )
        },
        injectLocalMessage = { service.injectLocalMessage(it) },
        refreshReviewFiles = { refreshReviewFiles() },
        isCancelledProvider = { multiModelReviewCancelled },
        resetCancelled = { multiModelReviewCancelled = false },
    )

    /** Execute `/review-perform model...` — instructs the LLM to adversarially
     *  review the VCS-changed files and add review comments to `.review/` JSON files.
     *  See [ReviewCommandHandler.executeReviewPerformCommand] for model-selection
     *  semantics. */
    @Suppress("KDocUnresolvedReference")
    fun executeReviewPerformCommand(args: String = "") =
        reviewCommandHandler.executeReviewPerformCommand(args)

    /** Execute `/review-perform-gaming model...` — like
     *  executeReviewPerformCommand but injects the game-engine-specific
     *  adversarial checklist. Delegated to [ReviewCommandHandler]. */
    @Suppress("KDocUnresolvedReference")
    fun executeReviewPerformGamingCommand(args: String = "") =
        reviewCommandHandler.executeReviewPerformGamingCommand(args)

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow.
     *  Delegated to [ReviewCommandHandler]. */
    fun executeReviewResolveCommand() =
        reviewCommandHandler.executeReviewResolveCommand()

    /** Execute `/review-recheck model...` — re-runs the adversarial review with
     *  existing comments + replies as context. Delegated to [ReviewCommandHandler].
     *  See [ReviewCommandHandler.executeReviewRecheckCommand] for the reply
     *  preservation safety net. */
    @Suppress("KDocUnresolvedReference")
    fun executeReviewRecheckCommand(args: String = "") =
        reviewCommandHandler.executeReviewRecheckCommand(args)

    /** Trigger a direct disk re-read so the [ReviewCommentManager]
     *  picks up any new `.review/` JSON files written by the LLM agent.
     *
     *  `loadAll()` reads ALL `.review/` files from disk via
     *  `java.nio.file.Files.walk()`, bypassing VFS entirely. This is reliable
     *  even if VFS hasn't discovered a newly-created `.review/` subdirectory.
     *  Runs on ReviewCommentManager's internal scope (non-blocking).
     *
     *  NOTE: The previous secondary path (`VirtualFileManager.asyncRefresh(null)`)
     *  was removed because it triggers a VFS refresh on a platform background
     *  coroutine, which acquires a write intent via the suspend
     *  `runWriteActionWithCheckInWriteIntent` API. On IntelliJ 2026.2, the
     *  platform's `beforeWriteActionStart` callback fires on that background
     *  thread, where `TrafficLightRenderer.getErrorCounts` →
     *  `CodeInsightContextManagerImpl.getPreferredContext` tries to read editor
     *  context without wrapping in a read action — causing a
     *  `RuntimeExceptionWithAttachments: Read access is allowed from inside
     *  read-action only`. This is a platform bug, but `asyncRefresh` was the
     *  only background write-action trigger from our plugin, so removing it
     *  eliminates the crash. The AsyncFileListener still fires for VFS events
     *  from other sources (user edits, git operations, manual refresh). */
    private fun refreshReviewFiles(): kotlinx.coroutines.Job {
        val manager = com.opencode.acp.review.ReviewCommentManager.getInstance(project)
        return manager.scope.launch { manager.loadAll() }
    }

    /** Get messages StateFlow for a child session (used by ToolPill for task pills). */
    fun getSessionMessages(sessionId: String) = service.getSessionMessages(sessionId)

    fun getStreamingText(sessionId: String) = service.getStreamingText(sessionId)

    // --- Command history — delegated to CommandHistoryManager ---

    fun clearCommandHistory() = commandHistoryManager.clearCommandHistory()

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

    // --- Brave Mode toggle ---

    /** Toggle Brave Mode (auto-approve all permission prompts). When enabled,
     *  permission prompts are auto-approved with ALLOW_ONCE instead of showing
     *  the UI dialog. Explicit `deny` rules are still enforced server-side. */
    fun toggleBraveMode() {
        val newValue = !_braveModeEnabled.value
        OpenCodeFollowSettingsState.getInstance().braveModeEnabled = newValue
        _braveModeEnabled.value = newValue
        logger.info { "[ACP] Brave mode toggled: $newValue" }
    }

    // --- Permission persistence ---

    private fun loadPersistedPermissions(
        registry: com.opencode.acp.mcp.ToolRegistry? = service.toolRegistry
    ): Map<String, Pair<Boolean, ToolPermission>> {
        val settings = OpenCodeMcpSettingsState.getInstance()
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
            // FAIL-CLOSED: corrupted permissions JSON → set all discovered tools to ASK
            // (safest interactive default) instead of leaving them at ALLOW. See
            // OpenCodeService.discoverToolsInBackground for the full rationale.
            logger.error(e) { "[ACP] Corrupted tool permissions JSON — failing closed (all tools → ASK)" }
            val allDiscovered = registry?.tools?.values ?: emptyList()
            if (allDiscovered.isEmpty()) {
                emptyMap()
            } else {
                val failClosed = mutableMapOf<String, Pair<Boolean, ToolPermission>>()
                for (tool in allDiscovered) {
                    failClosed[tool.id] = Pair(true, ToolPermission.ASK)
                    failClosed[tool.name] = Pair(true, ToolPermission.ASK)
                }
                failClosed.toMap()
            }
        }
    }

    // --- Cleanup ---

    fun close() {
        connectionObserverJob?.cancel()
        initJob?.cancel()
        // Stop the SignalRouter → SignalSideEffectExecutor pipeline.
        signalSideEffectExecutor.stop()
        signalRouter.stop()
        service.permissionManager.cancelPermissionTimeout()
        // Cancel child permission timeout jobs and clear prompt state —
        // delegated to PermissionViewModel, which owns both.
        permissionViewModel.close()
    }

    // --- Helpers ---

    companion object {
        private const val MAX_STEER_WAIT_MS = 10_000L
    }
}
