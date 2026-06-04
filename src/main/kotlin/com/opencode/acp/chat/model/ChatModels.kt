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
    val isStreaming: Boolean = false
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
