package com.opencode.acp.chat.model

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import java.util.LinkedHashMap

/** Attached file/image in a user message. */
data class AttachedFile(
    val name: String,
    val path: String,
    val mime: String,
    val dataUri: String
)

/**
 * A single entry in the input command history.
 *
 * Stores the message text plus its attachments (with full data URI) so that
 * recalling an entry re-populates the input area with the exact same content
 * the user previously sent — including clipboard images (which have no
 * on-disk path and must be restored from the stored dataUri).
 */
class CommandHistoryEntry {
    var text: String = ""
    var attachedFileNames: ArrayList<String> = ArrayList()
    var attachedFilePaths: ArrayList<String> = ArrayList()
    var attachedFileMimes: ArrayList<String> = ArrayList()
    var attachedFileDataUris: ArrayList<String> = ArrayList()

    /** No-arg constructor required for XStream deserialization. */
    constructor()

    constructor(text: String, files: List<AttachedFile>) {
        this.text = text
        files.forEach { f ->
            attachedFileNames.add(f.name)
            attachedFilePaths.add(f.path)
            attachedFileMimes.add(f.mime)
            attachedFileDataUris.add(f.dataUri)
        }
    }

    /** Reconstruct the original [AttachedFile] list. */
    fun toAttachedFiles(): List<AttachedFile> = buildList {
        val n = attachedFileNames.size
        for (i in 0 until n) {
            add(
                AttachedFile(
                    name = attachedFileNames[i],
                    path = attachedFilePaths[i],
                    mime = attachedFileMimes[i],
                    dataUri = attachedFileDataUris[i],
                )
            )
        }
    }
}

/** Display model for a single message in the chat list. */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val parts: Map<String, MessagePart>,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    /** Explicit message state — tracks lifecycle transitions. */
    val state: MessageState = MessageState.Created,
    // Attached images/files from user message
    val attachedFiles: List<AttachedFile> = emptyList(),
    // Model info from AssistantMessage — present only for assistant messages
    val modelID: String? = null,
    val providerID: String? = null,
    // Token info from AssistantMessage — present only for assistant messages
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cost: Double = 0.0,
    /**
     * Server's message ID — matches V1 `messageID` in SSE events.
     * Null for user messages (user messages have no server ID).
     * Used to deterministically route SSE events to the correct message
     * instead of relying on `ctx.activeMessageId`.
     * Stored explicitly rather than derived from [id] to avoid format assumptions.
     */
    val serverMessageId: String? = null,
)

enum class MessageRole { USER, ASSISTANT }

/** Display model for a tool call pill. */
data class ToolCallPill(
    val toolCallId: String,
    val toolName: String,
    val title: String,
    val kind: ToolKind,
    val status: ToolCallStatus,
    val input: kotlinx.serialization.json.JsonObject? = null,
    val output: List<kotlinx.serialization.json.JsonObject>? = null,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
)

/** A file modified by a tool call, displayed in the assistant message. */
data class ChatFileChange(
    val filePath: String,
    val fileName: String,
    val additions: Int = 0,
    val deletions: Int = 0
)

/** Display model for a permission prompt inline in chat. */
data class PermissionPrompt(
    val sessionId: String,
    val permissionId: String,
    val toolCallId: String,
    val toolName: String,
    val description: String?,
    val patterns: List<String> = emptyList()
)

/** A single option in a selection prompt. */
data class SelectionOption(
    val title: String,
    val description: String,
    /** Server-side label used in the answer payload. Maps to `label` in the wire format. */
    val label: String = title
)

/** Display model for a multi-select prompt inline in chat. */
data class SelectionPrompt(
    val sessionId: String,
    val promptId: String,
    val question: String,
    val subtitle: String? = null,
    val options: List<SelectionOption>,
    val allowCustomInput: Boolean = true,
    val multiSelect: Boolean = true
)

/** Response from a selection prompt. */
data class SelectionResponse(
    val selectedIndices: Set<Int>,
    val customInput: String? = null
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
    ALLOW_ONCE("once"),
    REJECT_ONCE("reject"),
    ALLOW_ALWAYS("always")
}

/** Display model for a session in the sidebar list. */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,      // epoch millis from OpenCodeSession.time.updated
    val cost: Double,          // USD from OpenCodeSession.cost
    val inputTokens: Long,     // from OpenCodeSession.tokens.input
    val outputTokens: Long,    // from OpenCodeSession.tokens.output
    val parentID: String? = null  // non-null for subtask sessions
)

/** Sealed state for the session list sidebar — distinguishes loading/error/loaded. */
sealed interface SessionListState {
    data object Loading : SessionListState
    data class Loaded(
        val sessions: List<SessionItem>,
        val selectedId: String?,
        /** How many top-level sessions to display in the sidebar. Defaults to DEFAULT_DISPLAY_LIMIT. */
        val displayLimit: Int = DEFAULT_DISPLAY_LIMIT,
    ) : SessionListState {
        /** Top-level sessions (no parentID), sorted by updatedAt desc (same order as sessions). */
        val topLevelSessions: List<SessionItem>
            get() = sessions.filter { it.parentID == null }

        /** Sessions currently visible to the UI (sliced to displayLimit). */
        val displayedSessions: List<SessionItem>
            get() = topLevelSessions.take(displayLimit)

        /** Whether more sessions can be loaded. */
        val hasMore: Boolean
            get() = displayedSessions.size < topLevelSessions.size

        companion object {
            const val DEFAULT_DISPLAY_LIMIT = 10
        }
    }
    data class Error(val message: String) : SessionListState
}

/** Result of clearAllSessions(). */
sealed class ClearAllResult {
    data class Success(val count: Int) : ClearAllResult()
    data class Partial(val deleted: Int, val failed: Int) : ClearAllResult()
    data class Failed(val message: String) : ClearAllResult()
}

/** UI state for the "Clear all sessions" operation. */
sealed class ClearAllState {
    data object Idle : ClearAllState()
    data class InProgress(val deleted: Int, val total: Int) : ClearAllState()
    data class Done(val result: ClearAllResult) : ClearAllState()
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

/** A single todo item from the OpenCode todowrite tool. */
data class TodoItem(
    val content: String,
    val status: String,    // "pending", "in_progress", "completed"
    val priority: String   // "high", "medium", "low"
)

/**
 * Returns the full markdown representation of a ChatMessage, suitable for
 * clipboard copy, context display, or debugging.
 * Concatenates all [MessagePart] variants into a single markdown string:
 * - Text parts are included as-is
 * - Code parts are wrapped in ``` fences
 * - Table parts use their raw markdown representation
 * - Thinking parts are prefixed with `> ` (blockquote style)
 * - Error parts are prefixed with "Error: "
 * - ToolCall, FileChange, and Subagent parts are skipped (not markdown-renderable)
 */
val ChatMessage.fullMarkdownContent: String
    get() = buildString {
        parts.values.forEach { part ->
            when (part) {
                is MessagePart.Text -> append(part.content).append('\n')
                is MessagePart.Code -> append("```${part.language}\n${part.content}\n```\n")
                is MessagePart.Table -> append(part.rawMarkdown).append('\n')
                is MessagePart.Thinking -> {
                    part.content.lines().forEach { line ->
                        append("> ").append(line).append('\n')
                    }
                }
                is MessagePart.Error -> append("Error: ${part.message}\n")
                is MessagePart.ToolCall -> { /* skip — binary/structured, not markdown */ }
                is MessagePart.FileChange -> { /* skip — not markdown content */ }
                is MessagePart.Patch -> append("Patch: ${part.hash} — ${part.files.size} file(s)\n")
                is MessagePart.Agent -> append("Agent: ${part.name}\n")
                is MessagePart.Retry -> append("Retry: ${part.attempt}/${part.maxAttempts}${part.error?.let { " — $it" } ?: ""}\n")
                is MessagePart.Compaction -> append("Context compacted${part.summary?.let { ": $it" } ?: ""}\n")
                is MessagePart.AssistantFile -> append("📎 ${part.filename ?: part.url}\n")
                is MessagePart.Image -> append("🖼️ ${part.filename ?: part.url}\n")
                is MessagePart.StepFinish -> { /* skip — informational */ }
            }
        }
    }

/** Sidebar tab identifiers. */
enum class SidebarTab { SESSIONS, CONTEXT, REVIEW }

/** Visual indicator state for a session row in the sidebar. */
enum class SessionIndicator {
    NONE,
    CREATING,
    STREAMING,
}

/**
 * A message waiting in the queue to be sent when the current response completes.
 *
 * Queue mode replaces the old "steer" behavior (which aborted the running response).
 * Instead, the user's message is held locally and auto-sent when the server goes idle.
 * This preserves all running tools and subtasks.
 */
data class QueuedMessage(
    val id: String,
    val text: String,
    val files: List<AttachedFile> = emptyList(),
    val queuedAt: Long = System.currentTimeMillis()
)
