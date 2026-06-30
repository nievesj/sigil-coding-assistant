package com.opencode.acp.chat.viewmodel

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.toChatMessage
import com.opencode.acp.chat.model.*
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
    val sessionCachedFlow: kotlinx.coroutines.flow.SharedFlow<String> = service.sessionCachedFlow

    // --- UI-specific state ---
    private val _controlState = MutableStateFlow(ControlBarState())
    val controlState: StateFlow<ControlBarState> = _controlState.asStateFlow()

    private val _streamPhase = MutableStateFlow(StreamPhase.IDLE)
    val streamPhase: StateFlow<StreamPhase> = _streamPhase.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt.asStateFlow()

    private val _selectionPrompt = MutableStateFlow<SelectionPrompt?>(null)
    val selectionPrompt: StateFlow<SelectionPrompt?> = _selectionPrompt.asStateFlow()

    private val _pasteImageSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pasteImageSignal: SharedFlow<Unit> = _pasteImageSignal.asSharedFlow()

    private val _pasteTextSignal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pasteTextSignal: SharedFlow<String> = _pasteTextSignal.asSharedFlow()

    private val _isSidebarVisible = MutableStateFlow(OpenCodeSettingsState.getInstance().sidebarVisible)
    val isSidebarVisible: StateFlow<Boolean> = _isSidebarVisible.asStateFlow()

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
     *  changes while preventing the read-modify-write race with drainQueue. */
    private val queueLock = java.util.concurrent.locks.ReentrantLock()

    /** Retry counts for queued messages — prevents infinite retry loops.
     *  Uses ConcurrentHashMap for thread-safety: clearQueue() may be called from
     *  EDT coroutines (session switch, cancel) while drainQueue() runs on the
     *  ViewModel scope. Both are serialized by [drainMutex] for correctness, but
     *  the ConcurrentHashMap provides a safety net against data races. */
    private val queueRetryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
    @Volatile private var lastLoggedInputState: ChatInputState? = null

    /**
     * Exhaustive input-area state derived from connection, streaming, and prompt StateFlows.
     * Composables switch on this instead of combining booleans.
     * Priority: Disabled > AwaitingPermission > AwaitingSelection > Streaming > Idle
     */
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
                        // Refresh context and todos after response completes
                        scope.launch { computeSessionContext() }
                        scope.launch { fetchTodos() }
                        // Refresh sessions to pick up new child sessions
                        scope.launch { service.loadSessions() }
                        // Auto-drain queue: send next queued message if any
                        scope.launch { drainQueue() }
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
                        // Belt-and-suspenders: SessionState.SseEvent.Error already
                        // emits StreamingCompleted (which transitions to IDLE via the
                        // signals collector above). This handles the edge case where
                        // StreamingCompleted was already emitted (streamingCompletedEmitted
                        // guard) and the phase is stuck.
                        //
                        // Only reset phase for errors on the ACTIVE session — errors on
                        // child/subagent sessions should not affect the input area state.
                        if (signal.sessionId == service.sessionId) {
                            _streamPhase.value = StreamPhase.IDLE
                        }
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
        if (!initMutex.tryLock()) {
            logger.warn { "[ACP] initialize() already in progress — user click acknowledged but deduplicated (current readyState=${_readyState.value})" }
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

    // --- Session actions ---

    suspend fun loadSessions() = service.loadSessions()

    suspend fun switchSession(sessionId: String) {
        // Remove old session from streaming set before switching
        service.sessionId?.let { service.removeStreamingSession(it) }
        service.switchSession(sessionId)
        // After switching, assume NOT streaming — SSE events will set it to true if needed.
        // Don't read activeSession?.isStreaming because adoptStreamingContext() unconditionally
        // marks the last assistant message as streaming, which is wrong for completed responses.
        _streamPhase.value = StreamPhase.IDLE
        clearQueue()

        // Sync prompt state from the new session's persistent StateFlows
        val activeSession = service.sessionManager.getActiveSession()
        _permissionPrompt.value = activeSession?.pendingPermission?.value
        _selectionPrompt.value = activeSession?.pendingSelection?.value

        // If a permission prompt was restored, restart the timeout
        if (_permissionPrompt.value != null) {
            startPermissionTimeout()
        }
    }

    suspend fun createAndSwitchSession(title: String? = null) {
        service.sessionId?.let { service.removeStreamingSession(it) }
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

    fun clearAllSessions() {
        scope.launch {
            val loaded = sessionListState.value as? SessionListState.Loaded ?: return@launch
            val totalCount = loaded.topLevelSessions.count { it.id != service.sessionId }
            // Skip the InProgress(0, N) state — clearAllSessions() performs all
            // deletions in a single suspend call, so intermediate progress is
            // never visible. Going directly to Done avoids the misleading
            // "Deleting 0 of N..." flash.
            val result = service.clearAllSessions()
            _clearAllState.value = ClearAllState.Done(result)
            delay(2000)
            _clearAllState.value = ClearAllState.Idle
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
        logger.info { "[ACP] drainQueue: sending '${next.text.take(50)}' (${_queuedMessages.value.size} remaining)" }

        val result = sendMessage(next.text, next.files)
        if (result is SendMessageResult.Error) {
            val retryCount = queueRetryCounts.getOrDefault(next.id, 0) + 1
            if (retryCount <= MAX_QUEUE_RETRIES) {
                queueRetryCounts[next.id] = retryCount
                delay(RETRY_DELAY_MS)
                queueLock.withLock {
                    val alreadyRequeued = _queuedMessages.value.any { it.id == next.id }
                    if (!alreadyRequeued) {
                        _queuedMessages.value = listOf(next) + _queuedMessages.value
                        logger.warn { "[ACP] drainQueue: re-queued failed message at front of queue (attempt $retryCount/$MAX_QUEUE_RETRIES) — ${result.message}" }
                    } else {
                        logger.debug { "[ACP] drainQueue: message ${next.id} already re-queued, skipping duplicate add (attempt $retryCount/$MAX_QUEUE_RETRIES)" }
                    }
                }
            } else {
                queueRetryCounts.remove(next.id)
                logger.error { "[ACP] drainQueue: dropping message after $MAX_QUEUE_RETRIES failed attempts — ${result.message}" }
            }
        } else {
            queueRetryCounts.remove(next.id)
        }
    }

    // --- Permission/Selection ---

    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        try {
            service.respondPermission(prompt.permissionId, prompt.toolCallId, prompt.sessionId, response)
            _permissionPrompt.value = null
            service.permissionManager.cancelPermissionTimeout()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Permission response failed (network error, server down). Keep the
            // prompt open so the user can retry. Don't dismiss it — dismissing
            // would leave the server waiting for a response it never received.
            logger.warn(e) { "[ACP] Permission response failed — keeping prompt open for retry" }
        }
    }

    private fun startPermissionTimeout() {
        service.permissionManager.startPermissionTimeout(
            timeoutSeconds = OpenCodeSettingsState.getInstance().state.permissionTimeoutSeconds
        ) {
            _permissionPrompt.value = null
        }
    }

    fun respondSelection(response: SelectionResponse) {
        val prompt = _selectionPrompt.value ?: return
        scope.launch {
            try {
                val selectedLabels = response.selectedIndices.mapNotNull { idx ->
                    prompt.options.getOrNull(idx)?.label
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _compactionState.value = CompactionState.Error(CompactionError.Timeout)
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

    /** Shared logic for both review-perform variants: resolve model args and
     *  send the prompt once per model (or once with the control-bar model if
     *  no args). Sequential — each [sendMessage] blocks until that model's
     *  response completes (via the service's sendMutex + responseDeferred). */
    private suspend fun executeMultiModelReview(args: String, prompt: String) {
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
            // If the model has variants and the current thinking effort isn't
            // null, use it. Otherwise pick the first variant if available, or
            // null (server default) if the model has no variants.
            val variant = when {
                model.variants.isEmpty() -> null
                currentVariant != null && currentVariant in model.variants -> currentVariant
                else -> model.variants.firstOrNull()
            }
            val header = "### Review by ${model.displayName}\n\n"
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
    }

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow. */
    fun executeReviewResolveCommand() {
        scope.launch {
            val index = ReviewCommentManager.getInstance(project).getIndex()
            service.sendMessage(text = ReviewSkill.buildResolvePrompt(index))
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
            executeMultiModelReview(args, prompt)
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

    private fun recordCommand(text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        val entry = CommandHistoryEntry(text = text, files = files)
        val maxSize = OpenCodeSettingsState.getInstance().commandHistorySize.coerceIn(1, 100)
        val current = _commandHistory.value.toMutableList()
        current.removeAll { existing ->
            existing.text == text &&
                existing.attachedFileNames == files.map { it.name } &&
                existing.attachedFilePaths == files.map { it.path } &&
                existing.attachedFileMimes == files.map { it.mime }
        }
        current.add(0, entry)
        val trimmed = if (current.size > maxSize) current.take(maxSize) else current
        _commandHistory.value = trimmed
        val settings = OpenCodeSettingsState.getInstance()
        settings.commandHistory = java.util.ArrayList(trimmed)
    }

    fun clearCommandHistory() {
        _commandHistory.value = emptyList()
        OpenCodeSettingsState.getInstance().commandHistory = java.util.ArrayList()
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
    }

    // --- Helpers ---

    companion object {
        private const val MAX_STEER_WAIT_MS = 10_000L
        private const val MAX_QUEUE_RETRIES = 3
        /** Delay before re-queuing a failed message to prevent rapid retry loops. */
        private const val RETRY_DELAY_MS = 2_000L
    }
}
