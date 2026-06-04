package com.opencode.acp.chat.model

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind

/** Display model for a single message in the chat list. */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val toolCalls: List<ToolCallPill> = emptyList(),
    val thinkingContent: String = "",
    val isStreaming: Boolean = false,
    // Model info from AssistantMessage — present only for assistant messages
    val modelID: String? = null,
    val providerID: String? = null,
    // Token info from AssistantMessage — present only for assistant messages
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cost: Double = 0.0
)

enum class MessageRole { USER, ASSISTANT }

/** Display model for a tool call pill. */
data class ToolCallPill(
    val toolCallId: String,
    val toolName: String,
    val title: String,
    val kind: ToolKind,
    val status: ToolCallStatus
)

/** Display model for a permission prompt inline in chat. */
data class PermissionPrompt(
    val permissionId: String,
    val toolCallId: String,
    val toolName: String,
    val description: String?
)

/** Bottom bar state. */
data class ControlBarState(
    val agents: List<OpenCodeAgentInfo> = emptyList(),
    val selectedAgent: OpenCodeAgentInfo? = null,
    val models: List<ProviderModel> = emptyList(),
    /** All models from all providers (including disconnected) — used for context limit lookup. */
    val allModels: List<ProviderModel> = emptyList(),
    val selectedModel: ProviderModel? = null,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.DEFAULT
)

/** Agent info from OpenCode REST API. */
data class OpenCodeAgentInfo(
    val id: String,
    val name: String,
    val description: String? = null
)

/** Flattened model selection for the control bar. */
data class ProviderModel(
    val providerID: String,
    val modelID: String,
    val displayName: String,
    val reasoning: Boolean = false,
    val contextWindow: Int = 0,
    val providerIconId: String = "",
    val variants: List<String> = emptyList()
)

/** Sealed type for heterogeneous combo box model items (headers + models with stars). */
sealed interface DropdownItem {
    /** Provider section header — not interactive. */
    data class ProviderHeader(val name: String) : DropdownItem

    /** A single model entry with favorite toggle. */
    data class ModelItem(
        val model: ProviderModel,
        val providerName: String,
        val modelName: String,
        val isFavorite: Boolean,
        val contextWindowLabel: String = ""
    ) : DropdownItem {
        /** Equality by ProviderModel so JComboBox.setSelectedItem() can match across lists. */
        override fun equals(other: Any?): Boolean =
            other is ModelItem && model == other.model

        override fun hashCode(): Int = model.hashCode()
    }
}

enum class ThinkingEffort(val label: String, val variant: String?) {
    NONE("None", "none"),
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high"),
    DEFAULT("Default", null)
}

/** Connection state for the chat panel. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/** Permission response options (strongly typed). */
enum class PermissionResponse(val optionId: String) {
    ALLOW_ONCE("allow-once"),
    REJECT_ONCE("reject-once"),
    ALLOW_ALWAYS("allow-always")
}

/** Display model for a session in the sidebar list. */
data class SessionItem(
    val id: String,
    val title: String,
    val createdAt: Long,      // epoch millis from OpenCodeSession.time.created
    val cost: Double,          // USD from OpenCodeSession.cost
    val inputTokens: Long,     // from OpenCodeSession.tokens.input
    val outputTokens: Long,    // from OpenCodeSession.tokens.output
    val parentID: String? = null  // non-null for subtask sessions
)

/** Sealed state for the session list sidebar — distinguishes loading/error/loaded. */
sealed interface SessionListState {
    data object Loading : SessionListState
    data class Loaded(val sessions: List<SessionItem>, val selectedId: String?) : SessionListState
    data class Error(val message: String) : SessionListState
}

/** Sealed state for session context — distinguishes loading, loaded, and error. */
sealed interface SessionContextState {
    data object Loading : SessionContextState
    data class Loaded(val context: SessionContext) : SessionContextState
    data class Error(val message: String, val retryable: Boolean = true) : SessionContextState
}

/** Context information for the active session, derived from GET /session/:id. */
data class SessionContext(
    val sessionId: String,
    val title: String,
    val providerID: String,
    val modelID: String,
    val providerName: String,
    val modelName: String,
    val contextLimit: Long,          // 0 = unknown → "N/A"
    val totalTokens: Long,           // input + output + reasoning + cacheRead + cacheWrite
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val usagePercent: Float,         // totalTokens / contextLimit * 100 (can exceed 100f)
    val totalCost: Double,
    val messageCount: Int,           // total number of messages in the session
    val userMessageCount: Int,       // number of user messages
    val assistantMessageCount: Int,  // number of assistant messages
    val additions: Int,              // lines added (from session summary)
    val deletions: Int,              // lines deleted (from session summary)
    val filesModified: Int,          // files modified (from session summary)
    val sessionCreated: Long,        // epoch millis
    val lastUpdated: Long           // epoch millis
)

/** Sidebar tab identifiers. */
enum class SidebarTab { SESSIONS, CONTEXT }
