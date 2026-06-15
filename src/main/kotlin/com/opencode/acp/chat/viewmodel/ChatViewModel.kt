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
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

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

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

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

    /** Messages waiting to be sent when the current response completes (queue mode). */
    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    private val _readyState = MutableStateFlow(ReadyState.NOT_STARTED)
    val readyState: StateFlow<ReadyState> = _readyState.asStateFlow()

    private val initMutex = Mutex()
    private var initJob: Job? = null
    private var connectionObserverJob: Job? = null

    // --- Computed input state (exhaustive state machine) ---
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
        isStreaming,
    ) { conn, ready, perm, sel, streaming ->
        when {
            conn != ConnectionState.CONNECTED -> ChatInputState.Disabled
            ready != ReadyState.READY -> ChatInputState.Disabled
            perm != null -> ChatInputState.AwaitingPermission(perm)
            sel != null -> ChatInputState.AwaitingSelection(sel)
            streaming -> ChatInputState.Streaming
            else -> ChatInputState.Idle
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChatInputState.Disabled)

    private val _fileChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val fileChangeSignal: SharedFlow<Unit> = _fileChangeSignal.asSharedFlow()

    // --- Init ---

    init {
        // Log message count changes for diagnostics
        scope.launch {
            service.messages.collect { msgMap ->
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
                        _isStreaming.value = true
                    }
                    is UiSignal.StreamingCompleted -> {
                        _isStreaming.value = false
                        // IDE notification — only when user is not looking at the IDE
                        OpenCodeNotifications.notifyResponseComplete(project)
                        // Refresh context and todos after response completes
                        scope.launch { computeSessionContext() }
                        scope.launch { fetchTodos() }
                        // Refresh sessions to pick up new child sessions
                        scope.launch { service.loadSessions() }
                        // Auto-drain queue: send next queued message if any
                        drainQueue()
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
                        if (_readyState.value == ReadyState.NOT_STARTED && initJob?.isActive != true) {
                            logger.info { "[ACP] Auto-reconnect detected — re-running initialize()" }
                            initialize(projectBasePath = null)
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
        // Guard against concurrent initialization
        if (!initMutex.tryLock()) {
            logger.warn { "[ACP] initialize() already in progress — skipping" }
            return
        }

        try {
            initJob = scope.launch {
                _readyState.value = ReadyState.NOT_STARTED

                _readyState.value = ReadyState.INITIALIZING_SERVICE
                val success = service.initialize(projectBasePath)
                if (!success) {
                    _readyState.value = ReadyState.NOT_STARTED
                    return@launch
                }

                _readyState.value = ReadyState.LOADING_AGENTS
                try {
                    val agents = withTimeoutOrNull(30_000) { service.listAgents() }
                    if (agents == null) {
                        logger.warn { "[ACP] Agent loading timed out after 30s" }
                    } else {
                        val filtered = agents.filter { it.mode != "subagent" && it.hidden != true }
                        _controlState.value = _controlState.value.copy(
                            agents = filtered.map { info ->
                                OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                            }
                        )
                        val defaultAgent = filtered.firstOrNull { it.name == "orchestrator" }?.name
                            ?: filtered.firstOrNull()?.name
                        if (defaultAgent != null) {
                            val agentInfo = _controlState.value.agents.find { it.id == defaultAgent }
                            if (agentInfo != null) {
                                _controlState.value = _controlState.value.copy(selectedAgent = agentInfo)
                            }
                        }
                        logger.info { "[ACP] ReadyState: LOADING_AGENTS → LOADING_PROVIDERS (agents=${filtered.size} loaded)" }
                    }
                } catch (e: Exception) {
                    logger.warn { "[ACP] Agent loading failed: ${e.message}" }
                }

                _readyState.value = ReadyState.LOADING_PROVIDERS
                try {
                    val providers = withTimeoutOrNull(30_000) { service.listProviders() }
                    if (providers == null) {
                        logger.warn { "[ACP] Provider loading timed out after 30s" }
                    } else {
                        val connectedIds = providers.connected.toSet()
                        val models = providers.all
                            .filter { it.id in connectedIds }
                            .flatMap { provider ->
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
                        val allModels = providers.all
                            .flatMap { provider ->
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
                                registry.loadPermissions(persisted)
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
    }

    fun selectModel(model: ProviderModel) {
        _controlState.value = _controlState.value.copy(selectedModel = model)
        val settings = OpenCodeSettingsState.getInstance()
        settings.lastSelectedModelKey = OpenCodeSettingsState.modelKey(model.providerID, model.modelID)
    }

    fun selectThinkingEffort(effort: ThinkingEffort) {
        _controlState.value = _controlState.value.copy(thinkingEffort = effort)
    }

    fun toggleSidebar() {
        val newValue = !_isSidebarVisible.value
        _isSidebarVisible.value = newValue
        OpenCodeSettingsState.getInstance().sidebarVisible = newValue
    }

    // --- Session actions ---

    suspend fun loadSessions() = service.loadSessions()

    suspend fun switchSession(sessionId: String) {
        service.switchSession(sessionId)
        // After switching, assume NOT streaming — SSE events will set it to true if needed.
        // Don't read activeSession?.isStreaming because adoptStreamingContext() unconditionally
        // marks the last assistant message as streaming, which is wrong for completed responses.
        _isStreaming.value = false
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
        service.createAndSwitchSession(title)
        // New session — definitely not streaming yet
        _isStreaming.value = false
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
            _clearAllState.value = ClearAllState.InProgress(0, totalCount)
            val result = service.clearAllSessions()
            _clearAllState.value = ClearAllState.Done(result)
            delay(2000)
            _clearAllState.value = ClearAllState.Idle
        }
    }

    // --- Message sending ---

    suspend fun sendMessage(text: String, files: List<AttachedFile> = emptyList()) {
        recordCommand(text, files)

        // Show streaming indicator immediately — don't wait for the first SSE event
        _isStreaming.value = true

        val state = _controlState.value
        val result = service.sendMessage(
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

        when (result) {
            is SendMessageResult.Success -> { /* handled by service */ }
            is SendMessageResult.Error -> {
                _isStreaming.value = false
            }
        }
    }

    suspend fun cancel() {
        service.cancel()
        _isStreaming.value = false
        clearQueue()
    }

    /**
     * Send a message while streaming is in progress (steering/nudging).
     * Auto-aborts the current response, waits for the send mutex to be released,
     * then sends as a fresh message via [sendMessage].
     *
     * Does NOT call recordCommand() — that's handled by sendMessage().
     * Does NOT set _isStreaming — that's also handled by sendMessage().
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
            _isStreaming.value = false
            return
        }

        // Session guard: if the user switched sessions during the steer,
        // don't send the message to the wrong session.
        val currentSessionId = service.sessionId
        if (currentSessionId == null) {
            logger.warn { "[ACP] steerMessage: session lost during steer" }
            _isStreaming.value = false
            return
        }

        // Now the mutex is free — send through the normal path.
        // sendMessage() handles: recordCommand, _isStreaming = true, error handling.
        sendMessage(text, files)
    }

    // --- Message Queue (queue mode) ---

    /**
     * Add a message to the queue instead of sending it immediately.
     * Used when [isStreaming] is true and queue mode is enabled.
     * The message will be auto-sent when the current response completes.
     */
    fun queueMessage(text: String, files: List<AttachedFile> = emptyList()) {
        val msg = QueuedMessage(
            id = generateId(),
            text = text,
            files = files
        )
        _queuedMessages.value = _queuedMessages.value + msg
        logger.info { "[ACP] queueMessage: queued '${text.take(50)}' (${_queuedMessages.value.size} in queue)" }
    }

    /**
     * Remove a queued message by ID (user cancelled it from the queue bar).
     */
    fun removeQueuedMessage(messageId: String) {
        _queuedMessages.value = _queuedMessages.value.filter { it.id != messageId }
        logger.info { "[ACP] removeQueuedMessage: $messageId (${_queuedMessages.value.size} remaining)" }
    }

    /**
     * Edit a queued message's text (user clicked edit in the queue bar).
     */
    fun editQueuedMessage(messageId: String, newText: String) {
        _queuedMessages.value = _queuedMessages.value.map {
            if (it.id == messageId) it.copy(text = newText) else it
        }
    }

    /**
     * Clear all queued messages. Called on session switch, cancel, etc.
     */
    fun clearQueue() {
        val count = _queuedMessages.value.size
        if (count > 0) {
            _queuedMessages.value = emptyList()
            logger.info { "[ACP] clearQueue: cleared $count queued messages" }
        }
    }

    /**
     * Drain the queue — send the next queued message if any.
     * Called automatically when StreamingCompleted fires and queue is non-empty.
     */
    private fun drainQueue() {
        val queue = _queuedMessages.value
        if (queue.isEmpty()) return

        val next = queue.first()
        _queuedMessages.value = queue.drop(1)
        logger.info { "[ACP] drainQueue: sending '${next.text.take(50)}' (${_queuedMessages.value.size} remaining)" }

        scope.launch {
            sendMessage(next.text, next.files)
        }
    }

    // --- Permission/Selection ---

    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        service.respondPermission(prompt.permissionId, prompt.toolCallId, prompt.sessionId, response)
        _permissionPrompt.value = null
        service.permissionManager.cancelPermissionTimeout()
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
                val answers = mutableListOf<List<String>>()
                if (selectedLabels.isNotEmpty()) {
                    answers.add(selectedLabels)
                }
                response.customInput?.let { answers.add(listOf(it)) }
                if (answers.isEmpty()) {
                    service.rejectQuestion(prompt.promptId, prompt.sessionId)
                } else {
                    service.respondQuestion(prompt.promptId, answers, prompt.sessionId)
                }
                _selectionPrompt.value = null
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
    }

    fun retryContextFetch() {
        scope.launch { computeSessionContext() }
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
                existing.attachedFilePaths == files.map { it.path }
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
        service.connectionManager.resetForRetry()
        resetReadyState()
        initialize(projectBasePath)
    }

    suspend fun connect(projectBasePath: String?) {
        if (connectionState.value == ConnectionState.CONNECTED || 
            connectionState.value == ConnectionState.CONNECTING) {
            return
        }
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

    private fun loadPersistedPermissions(): Map<String, ToolPermission> {
        val settings = OpenCodeSettingsState.getInstance()
        val permsJson = settings.toolPermissions
        if (permsJson.isBlank()) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(permsJson).jsonObject
            obj.entries.associate { (toolName, element) ->
                val toolObj = element.jsonObject
                val permStr = toolObj["permission"]?.jsonPrimitive?.contentOrNull ?: "allow"
                toolName to ToolPermission.fromActionString(permStr)
            }
        } catch (e: Exception) {
            logger.warn { "[ACP] Failed to parse persisted tool permissions: ${e.message}" }
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
    }
}
