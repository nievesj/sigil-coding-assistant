package com.opencode.acp.chat.model

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind

/** Display model for a single message in the chat list. */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val renderedHtml: String? = null,
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
    val description: String?,
    val options: List<PermissionOption>,
    val expiresAt: Long? = null
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

/** Provider info from GET /provider. */
data class ProviderInfo(
    val id: String,
    val name: String,
    val models: Map<String, ModelInfo>
)

/** Model info nested inside a provider. */
data class ModelInfo(
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val contextLimit: Int = 0,
    val outputLimit: Int = 0
)

/** Flattened model selection for the control bar. */
data class ProviderModel(
    val providerID: String,
    val modelID: String,
    val displayName: String,
    val reasoning: Boolean = false
)

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
