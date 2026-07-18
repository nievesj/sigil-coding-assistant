package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.*
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.OpenCodeNotifications
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
    private val service: OpenCodeService,
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
    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

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
                        // IDE notification — only when:
                        //  1. The response ended naturally (not aborted/errored/timed out)
                        //  2. The active session is NOT a child/subtask session
                        //  3. The user is not looking at the IDE
                        // Check if the completing message belongs to the active session.
                        // If the user switched sessions, isActiveSessionChild may be stale.
                        // Verify the message is in the active session before notifying.
                        val isActiveMessage = service.messages.value.containsKey(signal.messageId)
                        if (signal.naturalCompletion && !isActiveSessionChild.value && isActiveMessage) {
                            OpenCodeNotifications.notifyResponseComplete(project)
                        }
                        // Refresh context and todos after response completes.
                        // Ordered sequentially: loadSessions must complete before
                        // drainQueue runs (drainQueue sends the next queued message,
                        // which depends on the session list being up to date).
                        scope.launch {
                            // Each operation is independently wrapped so a failure in one
                            // doesn't skip the others — especially drainQueue(), which would
                            // leave the user's queued message permanently stuck.
                            try { computeSessionContext() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { logger.warn(e) { "[ACP] computeSessionContext failed after StreamingCompleted" } }
                            try { fetchTodos() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { logger.warn(e) { "[ACP] fetchTodos failed after StreamingCompleted" } }
                            try { service.loadSessions() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { logger.warn(e) { "[ACP] loadSessions failed after StreamingCompleted" } }
                            try { messageQueueManager.drainQueue() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { logger.warn(e) { "[ACP] drainQueue failed after StreamingCompleted" } }
                        }
                        // Refresh review comments — the LLM may have written .review/ files
                        // during the response. StreamingCompleted fires once per response
                        // (after all tool calls finish), so this is the correct timing point.
                        // Not gated on fileChanges because .review/ files can be written by
                        // non-edit tools (bash, custom review tools, undetected kinds).
                        refreshReviewFiles()
                    }
                    is UiSignal.PermissionRequested -> {
                        permissionViewModel.setPermissionPrompt(signal.prompt)
                        OpenCodeNotifications.notifyPermissionNeeded(project)
                        // NOTE: timeoutSeconds is read at call time from settings. If the user
                        // changes the setting while a prompt is pending, the current timeout
                        // is NOT restarted — the new value applies to the next prompt.
                        permissionViewModel.startPermissionTimeout()
                    }
                    is UiSignal.SelectionRequested -> {
                        permissionViewModel.setSelectionPrompt(signal.prompt)
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
                        // Always remove the specific session from spinner set (not just active)
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
                        permissionViewModel.addChildPermissionPrompt(signal.prompt)
                        OpenCodeNotifications.notifyPermissionNeeded(project)
                        // NOTE: Do NOT change _streamPhase or inputState — child permissions are non-blocking
                    }
                    is UiSignal.PermissionReplied -> {
                        // Confirm server processed our reply
                        if (permissionViewModel.permissionPrompt.value?.permissionId == signal.permissionId) {
                            // Active-session permission: clear it
                            permissionViewModel.setPermissionPrompt(null)
                            service.permissionManager.cancelPermissionTimeout()
                        }
                        // Handle child prompts based on reply type
                        // Check failed POST notification regardless of whether prompts
                        // were cleared by a concurrent timeout — the user must still see
                        // that the server processed the failed POST (TDD §4.2.4).
                        if (signal.sessionId in permissionViewModel.failedPermissionPostSessions) {
                            OpenCodeNotifications.notifyPermissionProcessedDespiteError(project, signal.sessionId)
                            permissionViewModel.failedPermissionPostSessions.remove(signal.sessionId)
                        }
                        // Then handle child prompts if they still exist
                        if (permissionViewModel.childPermissionPrompts.value.containsKey(signal.sessionId)) {
                            if (signal.reply == "reject") {
                                // Cascade: server rejects all pending permissions in the session
                                permissionViewModel.removeChildPrompts(signal.sessionId)
                                permissionViewModel.cancelChildPermissionTimeout(signal.sessionId)
                            } else {
                                // Non-reject reply: remove only the FIRST prompt (FIFO)
                                val newFirstToolName = permissionViewModel.dropFirstChildPrompt(signal.sessionId)
                                if (newFirstToolName == null) {
                                    // No more prompts — cancel the timeout
                                    permissionViewModel.cancelChildPermissionTimeout(signal.sessionId)
                                } else {
                                    // Remaining prompts — restart the timeout for the new first prompt
                                    permissionViewModel.startChildPermissionTimeout(signal.sessionId, newFirstToolName)
                                }
                            }
                        }
                    }
                    is UiSignal.PermissionTimedOut -> {
                        OpenCodeNotifications.notifyPermissionTimedOut(project, signal.toolName)
                        // Only clear active prompt if the timeout is for the active session's
                        // permission (non-empty permissionId matching the current prompt).
                        // Child permission timeouts use permissionId="" — they must NOT
                        // clear the active session's permission prompt.
                        if (signal.permissionId.isNotEmpty() && permissionViewModel.permissionPrompt.value?.permissionId == signal.permissionId) {
                            permissionViewModel.setPermissionPrompt(null)
                        }
                        // Clear child prompts for the timed-out child session.
                        // POST reject to the server for each remaining pending prompt before
                        // clearing locally — the server is still waiting on Deferred promises
                        // and would block indefinitely without a reply.
                        if (signal.sessionId.isNotEmpty()) {
                            val pending = permissionViewModel.getChildPrompts(signal.sessionId)
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
                            permissionViewModel.removeChildPrompts(signal.sessionId)
                            permissionViewModel.cancelChildPermissionTimeout(signal.sessionId)
                        }
                    }
                    else -> { /* other global signals */ }
                }
            }
        }

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
            agentName = _controlState.value.selectedAgent?.id ?: "orchestrator",
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

    // --- Review commands — delegated to ReviewCommandHandler ---

    /** Cancellation flag for multi-model review loops. Set by [cancel] to
     *  stop the loop after the current model finishes. */
    @Volatile private var multiModelReviewCancelled = false

    private val reviewCommandHandler = ReviewCommandHandler(
        scope = scope,
        project = project,
        gitService = gitService,
        controlStateProvider = { _controlState.value },
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

    /** Execute `/review-perform [model...]` — instructs the LLM to adversarially
     *  review the VCS-changed files and add review comments to `.review/` JSON files.
     *  See [ReviewCommandHandler.executeReviewPerformCommand] for model-selection
     *  semantics. */
    fun executeReviewPerformCommand(args: String = "") =
        reviewCommandHandler.executeReviewPerformCommand(args)

    /** Execute `/review-perform-gaming [model...]` — like
     *  [executeReviewPerformCommand] but injects the game-engine-specific
     *  adversarial checklist. Delegated to [ReviewCommandHandler]. */
    fun executeReviewPerformGamingCommand(args: String = "") =
        reviewCommandHandler.executeReviewPerformGamingCommand(args)

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow.
     *  Delegated to [ReviewCommandHandler]. */
    fun executeReviewResolveCommand() =
        reviewCommandHandler.executeReviewResolveCommand()

    /** Execute `/review-recheck [model...]` — re-runs the adversarial review with
     *  existing comments + replies as context. Delegated to [ReviewCommandHandler].
     *  See [ReviewCommandHandler.executeReviewRecheckCommand] for the reply
     *  preservation safety net. */
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

    private fun loadPersistedPermissions(): Map<String, Pair<Boolean, ToolPermission>> {
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
            logger.warn(e) { "[ACP] Failed to parse persisted tool permissions — corrupted settings, clearing" }
            emptyMap()
        }
    }

    // --- Cleanup ---

    fun close() {
        connectionObserverJob?.cancel()
        initJob?.cancel()
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
