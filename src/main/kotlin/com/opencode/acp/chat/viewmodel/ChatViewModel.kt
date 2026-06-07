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
import com.opencode.acp.chat.model.ConnectionState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
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
    private val scope: CoroutineScope,
    private val service: OpenCodeService
) : Closeable {

    private val logger = KotlinLogging.logger {}

    // --- Forwarded from service (read-only for UI) ---
    val messages: StateFlow<Map<String, ChatMessage>> = service.messages
    val connectionState: StateFlow<ConnectionState> = service.connectionState
    val sessionListState: StateFlow<SessionListState> = service.sessionListState
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>> = service.childSessionMap
    val todoItems: StateFlow<List<TodoItem>> = service.todoItems
    val sessionContextState: StateFlow<SessionContextState> = service.sessionContextState

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

        // Collect processor signals and update UI state
        scope.launch {
            service.signals.collect { signal ->
                when (signal) {
                    is UiSignal.StreamingStarted -> {
                        _isStreaming.value = true
                    }
                    is UiSignal.StreamingCompleted -> {
                        _isStreaming.value = false
                        // Refresh context and todos after response completes
                        scope.launch { computeSessionContext() }
                        scope.launch { fetchTodos() }
                        // Refresh sessions to pick up new child sessions
                        scope.launch { service.loadSessions() }
                    }
                    is UiSignal.PermissionRequested -> {
                        _permissionPrompt.value = signal.prompt
                        startPermissionTimeout()
                    }
                    is UiSignal.SelectionRequested -> {
                        _selectionPrompt.value = signal.prompt
                    }
                    is UiSignal.Error -> {
                        // Error already handled by processor (added as Error part)
                    }
                    is UiSignal.TodoUpdated -> {
                        // Todo updates come via SSE, processor handles them
                    }
                    is UiSignal.SessionCreated -> {
                        // New session detected — refresh session list
                        scope.launch { service.loadSessions() }
                    }
                    is UiSignal.FileChanged -> {
                        _fileChangeSignal.tryEmit(Unit)
                    }
                }
            }
        }

        // Load persisted command history
        val settings = OpenCodeSettingsState.getInstance()
        _commandHistory.value = ArrayList(settings.commandHistory)
    }

    // --- Initialization ---

    suspend fun initialize(projectBasePath: String) {
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

    suspend fun switchSession(sessionId: String) = service.switchSession(sessionId)

    suspend fun createAndSwitchSession(title: String? = null) = service.createAndSwitchSession(title)

    suspend fun archiveSession(sessionId: String) = service.archiveSession(sessionId)

    // --- Message sending ---

    suspend fun sendMessage(text: String, files: List<AttachedFile> = emptyList()) {
        recordCommand(text, files)

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
    }

    // --- Permission/Selection ---

    suspend fun respondPermission(response: PermissionResponse) {
        val prompt = _permissionPrompt.value ?: return
        service.respondPermission(prompt.permissionId, prompt.toolCallId, response)
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
                    service.rejectQuestion(prompt.promptId)
                } else {
                    service.respondQuestion(prompt.promptId, answers)
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

    // --- Command history ---

    private fun recordCommand(text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        val entry = CommandHistoryEntry(text = text, files = files)
        val maxSize = OpenCodeSettingsState.getInstance().commandHistorySize.coerceIn(1, 100)
        val current = _commandHistory.value.toMutableList()
        current.removeAll { existing ->
            existing.text == text &&
                existing.attachedFilePaths.size == files.size &&
                existing.attachedFilePaths.zip(files.map { it.path }).all { (a, b) -> a == b } &&
                existing.attachedFileDataUris.size == files.size &&
                existing.attachedFileDataUris.zip(files.map { it.dataUri }).all { (a, b) -> a == b }
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

    suspend fun retryConnection(projectBasePath: String) {
        service.retryConnection()
    }

    suspend fun connect(projectBasePath: String) {
        if (connectionState.value == ConnectionState.CONNECTED || 
            connectionState.value == ConnectionState.CONNECTING) {
            return
        }
        service.initialize(projectBasePath)
    }

    fun stopConnection() {
        service.connectionManager.disconnect()
    }

    // --- Cleanup ---

    override fun close() {
        service.permissionManager.cancelPermissionTimeout()
    }

    // --- Helpers ---

    companion object {
        /** Load persisted command history from settings. */
        private fun loadCommandHistory(): List<CommandHistoryEntry> {
            return ArrayList(OpenCodeSettingsState.getInstance().commandHistory)
        }
    }
}
