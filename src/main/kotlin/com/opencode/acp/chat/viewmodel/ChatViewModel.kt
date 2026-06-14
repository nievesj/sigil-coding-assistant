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
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
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

    /** Messages waiting to be sent when the current response completes (queue mode). */
    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    // --- Computed input state (exhaustive state machine) ---
    /**
     * Exhaustive input-area state derived from connection, streaming, and prompt StateFlows.
     * Composables switch on this instead of combining booleans.
     * Priority: Disabled > AwaitingPermission > AwaitingSelection > Streaming > Idle
     */
    val inputState: StateFlow<ChatInputState> = combine(
        connectionState,
        permissionPrompt,
        selectionPrompt,
        isStreaming,
    ) { conn, perm, sel, streaming ->
        when {
            conn != ConnectionState.CONNECTED -> ChatInputState.Disabled
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
                    is UiSignal.Error -> {
                        // Error already handled by processor (added as Error part)
                    }
                    is UiSignal.TodoUpdated -> {
                        // Todo updates come via SSE, processor handles them
                    }
                    is UiSignal.SessionCreated -> {
                        // Should not arrive on activeSignals (routed to globalSignals)
                    }
                    is UiSignal.SessionIdle -> {
                        // Should not arrive on activeSignals (routed to globalSignals)
                    }
                    is UiSignal.SessionError -> {
                        // Should not arrive on activeSignals (routed to globalSignals)
                    }
                    is UiSignal.SessionCompacted -> {
                        // Should not arrive on activeSignals (routed to globalSignals)
                    }
                    is UiSignal.FileChanged -> {
                        _fileChangeSignal.tryEmit(Unit)
                    }
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
    }

    // --- Initialization ---

    suspend fun initialize(projectBasePath: String?) {
        val success = service.initialize(projectBasePath)
        if (!success) {
            logger.warn { "Service initialization failed" }
            return
        }

        // Load agents for control bar
        try {
            val allAgents = service.listAgents()
            logger.info { "[ACP] ChatViewModel.initialize: got ${allAgents.size} agents from server" }
            val agents = allAgents.filter { it.mode != "subagent" && it.hidden != true }
            _controlState.value = _controlState.value.copy(
                agents = agents.map { info ->
                    OpenCodeAgentInfo(id = info.id, name = info.name, description = info.description)
                }
            )
            val defaultAgent = agents.firstOrNull { it.name == "orchestrator" }?.name
                ?: agents.firstOrNull()?.name
            if (defaultAgent != null) {
                val agentInfo = _controlState.value.agents.find { it.id == defaultAgent }
                if (agentInfo != null) {
                    _controlState.value = _controlState.value.copy(selectedAgent = agentInfo)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load agents (non-fatal)" }
        }

        // Load providers and models
        try {
            val providerResponse = service.listProviders()
            logger.info { "[ACP] ChatViewModel.initialize: providerResponse = ${providerResponse?.all?.size} providers" }
            if (providerResponse != null) {
                val connectedIds = providerResponse.connected.toSet()
                val models = providerResponse.all
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
                val allModels = providerResponse.all
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
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load providers (non-fatal)" }
        }

        // Fetch initial context and commands
        computeSessionContext()
        fetchTodos()
        fetchAvailableCommands()
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
        service.initialize(projectBasePath)
    }

    suspend fun connect(projectBasePath: String?) {
        if (connectionState.value == ConnectionState.CONNECTED || 
            connectionState.value == ConnectionState.CONNECTING) {
            return
        }
        service.initialize(projectBasePath)
    }

    fun stopConnection() {
        service.stopConnection()
    }

    // --- Cleanup ---

    override fun close() {
        service.permissionManager.cancelPermissionTimeout()
    }

    // --- Helpers ---

    companion object {
        private const val MAX_STEER_WAIT_MS = 10_000L

        /** Load persisted command history from settings. */
        private fun loadCommandHistory(): List<CommandHistoryEntry> {
            return ArrayList(OpenCodeSettingsState.getInstance().commandHistory)
        }
    }
}
