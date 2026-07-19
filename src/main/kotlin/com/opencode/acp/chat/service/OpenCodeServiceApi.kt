package com.opencode.acp.chat.service

import com.opencode.acp.adapter.AgentInfo
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.ProviderResponse
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.ClearAllResult
import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.SessionListState
import com.opencode.acp.chat.model.TodoItem
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.ui.compose.SlashCommand
import com.opencode.acp.mcp.McpConnectionStatus
import com.opencode.acp.mcp.McpManager
import com.opencode.acp.mcp.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public API surface of [OpenCodeService], split into focused role interfaces to
 * satisfy the Interface Segregation Principle (ISP).
 *
 * Originally a single 40-member interface (TDD `docs/tdd/ui-testability-refactor.md`
 * §9 step 4.5, Phase 2a), it forced narrow consumers such as `CompactionViewModel`
 * (3 members), `ControlBarViewModel` (2), `PermissionViewModel` (5), and
 * `PermissionSideEffectHandler` (~4) to depend on the full 40-member surface.
 *
 * Following the council review (see `docs/tdd/ui-testability-refactor.md`), the
 * interface is now a **composite** of seven focused sub-interfaces grouped by
 * concern:
 *
 * - [OpenCodeConnectionApi] — connection lifecycle (initialize, stop, connection state)
 * - [OpenCodeSessionApi] — session list, switch, create, archive, clear
 * - [OpenCodeStreamingApi] — streaming session tracking (sidebar spinner)
 * - [OpenCodeMessageApi] — message sending and actions
 * - [OpenCodePermissionApi] — permission and question responses
 * - [OpenCodeContextApi] — context, todos, commands, agents, providers
 * - [OpenCodeMcpApi] — MCP integration
 *
 * Consumers that need the full service depend on [OpenCodeServiceApi] (the composite).
 * Consumers with narrower needs can depend on the specific sub-interface instead —
 * this is enabled for future use but this refactor does NOT change any consumer
 * declarations; they all continue to depend on [OpenCodeServiceApi].
 *
 * The concrete [OpenCodeService] implements this composite interface directly — its
 * existing public `override` members satisfy all sub-contracts without any logic
 * changes. Default argument values are declared on the interface methods; the
 * concrete class overrides have no defaults (Kotlin uses the interface defaults
 * when calling through the interface type).
 *
 * A [FakeOpenCodeService] can implement this interface (or any sub-interface) for
 * UI testing without having to construct the real service's side-effect-heavy
 * initializers (ProcessManager, SessionManager, SseConnectionManager, etc.).
 */

/**
 * Connection lifecycle: initialize, stop, connection state.
 */
interface OpenCodeConnectionApi {
    val connectionState: StateFlow<ConnectionState>
    val connectionErrorReason: StateFlow<ConnectionErrorReason?>
    val scope: CoroutineScope
    val connectionManager: ProcessManager
    suspend fun initialize(projectBasePath: String? = null): Boolean
    fun stopConnection()
}

/**
 * Session management: list, switch, create, archive, clear.
 */
interface OpenCodeSessionApi {
    val sessionManager: SessionManager
    val sessionListState: StateFlow<SessionListState>
    val childSessionMap: StateFlow<Map<String, List<SessionItem>>>
    val hiddenChildSessionIds: StateFlow<Set<String>>
    val knownChildSessionIds: StateFlow<Set<String>>
    val childToParent: StateFlow<Map<String, String>>
    val activeSessionId: StateFlow<String?>
    val sessionCachedFlow: SharedFlow<String>
    val sessionId: String?
    suspend fun loadSessions()
    fun loadMoreSessions()
    suspend fun switchSession(sessionId: String)
    suspend fun createAndSwitchSession(title: String? = null)
    suspend fun archiveSession(sessionId: String)
    suspend fun clearAllSessions(): ClearAllResult
    fun unhideChildSession(sessionId: String)
    fun getSessionMessages(sessionId: String): StateFlow<Map<String, ChatMessage>>?
    fun getStreamingText(sessionId: String): StateFlow<String>?
}

/**
 * Streaming session tracking (sidebar spinner).
 */
interface OpenCodeStreamingApi {
    val streamingSessionIds: StateFlow<Set<String>>
    val pendingCreationSessionIds: StateFlow<Set<String>>
    val messages: StateFlow<Map<String, ChatMessage>>
    val signals: SharedFlow<UiSignal>
    val globalSignals: SharedFlow<UiSignal>
    fun addStreamingSession(sessionId: String)
    fun removeStreamingSession(sessionId: String)
}

/**
 * Message sending and actions.
 */
interface OpenCodeMessageApi {
    suspend fun sendMessage(
        text: String,
        files: List<AttachedFile> = emptyList(),
        modelID: String? = null,
        providerID: String? = null,
        variant: String? = null,
        agent: String? = null,
        model: OpenCodeClient.MessageModel? = null
    ): SendMessageResult
    fun injectLocalMessage(text: String)
    suspend fun cancel()
    suspend fun steerCancel(): CompletableDeferred<Unit>
}

/**
 * Permission and question responses.
 */
interface OpenCodePermissionApi {
    val permissionManager: PermissionManager
    suspend fun respondPermission(
        permissionId: String,
        toolCallId: String,
        sessionId: String,
        response: PermissionResponse,
        toolName: String = "",
        patterns: List<String> = emptyList(),
        agentName: String = "orchestrator",
    )
    suspend fun respondQuestion(promptId: String, answers: List<List<String>>, sessionId: String)
    suspend fun rejectQuestion(promptId: String, sessionId: String)
}

/**
 * Context, todos, commands, agents, providers.
 */
interface OpenCodeContextApi {
    val todoItems: StateFlow<List<TodoItem>>
    val sessionContextState: StateFlow<SessionContextState>
    suspend fun computeSessionContext(controlState: ControlBarState? = null)
    suspend fun computeSessionContextLocal(controlState: ControlBarState? = null)
    suspend fun refreshActiveSessionMessages()
    fun isCheckpointReady(): Boolean
    suspend fun fetchTodos()
    suspend fun fetchAvailableCommands(): List<SlashCommand>
    suspend fun executeServerCommand(commandName: String, args: String = "")
    suspend fun listAgents(): List<AgentInfo>
    suspend fun listProviders(): ProviderResponse?
}

/**
 * MCP integration.
 */
interface OpenCodeMcpApi {
    val toolRegistry: ToolRegistry?
    val mcpManager: McpManager?
    val mcpServerStatuses: StateFlow<Map<String, McpConnectionStatus>>
    suspend fun disconnectAllMcp()
    suspend fun reinitializeMcp()
    fun reinitializeMcpFromSettings()
    fun resetMcpOnServerRestart()
    /** Parse persisted tool-permissions JSON into a map of toolName → (enabled, permission-action-string). */
    fun parsePersistedToolPermissions(perms: String): Map<String, Pair<Boolean, String>>
}

/**
 * Composite API — the full public surface of [OpenCodeService].
 *
 * Consumers that need the full service depend on this. Consumers with narrower needs
 * (e.g., `CompactionViewModel` only needs connection + session) can depend on the
 * specific sub-interface ([OpenCodeConnectionApi], [OpenCodeSessionApi]) instead.
 *
 * The concrete [OpenCodeService] implements this composite interface directly — its
 * existing public members satisfy all sub-contracts without any logic changes.
 */
interface OpenCodeServiceApi :
    OpenCodeConnectionApi,
    OpenCodeSessionApi,
    OpenCodeStreamingApi,
    OpenCodeMessageApi,
    OpenCodePermissionApi,
    OpenCodeContextApi,
    OpenCodeMcpApi