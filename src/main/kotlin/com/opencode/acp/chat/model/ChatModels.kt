package com.opencode.acp.chat.model

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.LinkedHashMap

/** Logger for [CommandHistoryEntry] parallel-list mismatch diagnostics. */
private val commandHistoryLogger = KotlinLogging.logger {}

/** Attached file/image in a user message. */
data class AttachedFile(
    val name: String,
    val path: String,
    val mime: String
)

/**
 * A single entry in the input command history.
 *
 * Stores the message text plus its attachments so that recalling an entry
 * re-populates the input area with the same content the user previously sent.
 *
 * Rev 2+: `attachedFileDataUris` is no longer populated — `AttachedFile.dataUri`
 * was removed. The field is retained (XStream ignores `@Deprecated`) for
 * backward compatibility with pre-rev2 serialized entries.
 */
class CommandHistoryEntry {
    var text: String = ""
    var attachedFileNames: ArrayList<String> = ArrayList()
    var attachedFilePaths: ArrayList<String> = ArrayList()
    var attachedFileMimes: ArrayList<String> = ArrayList()

    /**
     * No longer populated as of rev 2 (AttachedFile.dataUri was removed).
     * Retained for XStream backward compat with pre-rev2 entries.
     * Deserialization reads it but does nothing with it; serialization writes an empty list.
     */
    @Deprecated("Removed in rev 2; retained for XStream backward compat")
    var attachedFileDataUris: ArrayList<String> = ArrayList()

    /** No-arg constructor required for XStream deserialization. */
    constructor()

    constructor(text: String, files: List<AttachedFile>) {
        this.text = text
        files.forEach { f ->
            attachedFileNames.add(f.name)
            attachedFilePaths.add(f.path)
            attachedFileMimes.add(f.mime)
        }
    }

    /** Reconstruct the original [AttachedFile] list. */
    fun toAttachedFiles(): List<AttachedFile> = buildList {
        val n = minOf(attachedFileNames.size, attachedFilePaths.size, attachedFileMimes.size)
        if (attachedFileNames.size != n || attachedFilePaths.size != n || attachedFileMimes.size != n) {
            commandHistoryLogger.warn {
                "[ACP] CommandHistoryEntry.toAttachedFiles() parallel list mismatch: " +
                    "names=${attachedFileNames.size}, paths=${attachedFilePaths.size}, " +
                    "mimes=${attachedFileMimes.size} — truncating to $n entries. " +
                    "Extra entries in the longer list(s) are dropped."
            }
        }
        @Suppress("DEPRECATION")
        if (attachedFileDataUris.isNotEmpty()) {
            commandHistoryLogger.warn {
                "[ACP] CommandHistoryEntry.toAttachedFiles(): entry has ${attachedFileDataUris.size} " +
                    "legacy attachedFileDataUris (pre-rev2 format) — these are ignored. " +
                    "Text: '${text.take(40)}'"
            }
        }
        for (i in 0 until n) {
            add(
                AttachedFile(
                    name = attachedFileNames[i],
                    path = attachedFilePaths[i],
                    mime = attachedFileMimes[i],
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
    val startTimeMs: Long? = null,
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

/** A child session (sub-agent) permission prompt relayed to the parent session UI.
 *  Non-blocking — renders as a banner, not in ChatInputState. */
data class ChildPermissionPrompt(
    val childSessionId: String,
    val permissionId: String,
    val toolCallId: String,
    val toolName: String,
    val description: String?,
    val patterns: List<String>,
    val subAgentLabel: String,
    /** Whether [subAgentLabel] was derived from a Subtask SSE event (true) or
     *  is the fallback "sub-agent" string (false). When false, the label should
     *  NOT be used for config sync (writeAlwaysAllowRule) because "sub-agent"
     *  is not a real agent name — it would create a config entry for a
     *  non-existent agent. */
    val agentLabelVerified: Boolean,
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

/**
 * Distinguishes *why* a connection reached [ConnectionState.ERROR] or failed,
 * so the UI can show targeted guidance instead of a generic "Connection failed".
 */
sealed interface ConnectionErrorReason {
    /** No OpenCode binary path is configured in Settings. */
    data object NoBinaryConfigured : ConnectionErrorReason
    /** The configured binary failed to launch (file not found, permissions, etc.). */
    data class BinaryLaunchFailed(val detail: String?) : ConnectionErrorReason
    /** The launched process exited before the server became healthy. */
    data class ProcessExited(val exitCode: Int, val outputTail: String?) : ConnectionErrorReason
    /** The server did not pass the health check within the startup timeout. */
    data object HealthCheckTimeout : ConnectionErrorReason
    /** Connection lost after successful init (reconnection failure). */
    data class ReconnectionFailed(val detail: String?) : ConnectionErrorReason
    /**
     * Any other connection failure not covered above.
     *
     * Currently unused (no call site constructs this), but retained for future
     * error categories. UI `when` arms handle it explicitly (not via `else`) so
     * the compiler enforces exhaustive matching.
     */
    data class Other(val detail: String?) : ConnectionErrorReason
}

/**
 * Readiness state for the UI transition to chat.
 * Owned by ChatViewModel — gates the splash → chat transition
 * alongside ConnectionState.CONNECTED.
 */
enum class ReadyState {
    NOT_STARTED,           // initialization hasn't started
    INITIALIZING_SERVICE,  // service.initialize() running (includes SSE start, MCP registration)
    LOADING_AGENTS,        // fetching agent list
    LOADING_PROVIDERS,     // fetching provider/model list
    LOADING_MCP,           // discovering MCP tools via ToolRegistry
    READY                  // everything loaded, UI can show
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
    val lastUpdated: Long,           // epoch millis
    /** 5-category token breakdown. Null until BreakdownComputer runs. */
    val breakdown: ContextBreakdown? = null,
    /** Context pressure signals (growth rate, turns until compact). Null until enough turns. */
    val pressure: ContextPressure? = null,
    /** Sigil context pruner stats from the heartbeat file. 0 when pruner is disabled or hasn't run. */
    val prunerTokensSaved: Long = 0,
    val prunerOutputsPruned: Long = 0,
    val prunerInputsPruned: Long = 0,
    val prunerLastRunMs: Long = 0,
)

/**
 * Token breakdown by category for the active session.
 * Computed from the local message cache — the server does NOT provide per-category breakdown.
 *
 * Classification (estimates — the server does not expose per-part token counts):
 * - systemPromptTokens: Estimated from the first assistant message's inputTokens minus
 *   all prior user message tokens. Approximates system prompt + tool definitions + format
 *   tokens + attached files. Labeled "System + Tool Definitions" in the UI.
 * - userTokens: Sum of all user message text parts (estimated via char count / calibratedCharsPerToken).
 * - assistantTokens: Sum of all assistant message text parts (estimated via char count / calibratedCharsPerToken).
 * - toolTokens: Sum of all tool call + tool result parts (estimated from JSON byte size / calibratedCharsPerToken).
 * - otherTokens: reasoningTokens + cacheReadTokens + cacheWriteTokens + unclassified.
 */
data class ContextBreakdown(
    val systemPromptTokens: Long,
    val userTokens: Long,
    val assistantTokens: Long,
    val toolTokens: Long,
    val otherTokens: Long,
    val freeTokens: Long,         // contextLimit - total (can be negative when over-full)
    val totalTokens: Long,        // sum of all categories
    val toolBreakdown: Map<String, ToolCategoryBreakdown>,  // per-tool-name aggregation
    val otherBreakdown: Map<String, OtherCategoryBreakdown> = emptyMap(),  // reasoning, cache read/write
) {
    /**
     * Percentages for the proportional bar.
     * When freeTokens < 0 (context over-full), percentages sum to >100%.
     * The UI caps the bar at 100% and shows an overflow indicator.
     *
     * WARNING: These percentages are ESTIMATES derived from estimated category
     * token counts (the server does not expose per-part token counts). They are
     * internally consistent (sum to 100% when not over-full) but should NOT be
     * treated as precise measurements. Use for proportional display only, not
     * for billing or capacity planning.
     */
    val systemPromptPercent: Float get() = if (totalTokens > 0) systemPromptTokens.toFloat() / totalTokens * 100 else 0f
    val userPercent: Float get() = if (totalTokens > 0) userTokens.toFloat() / totalTokens * 100 else 0f
    val assistantPercent: Float get() = if (totalTokens > 0) assistantTokens.toFloat() / totalTokens * 100 else 0f
    val toolPercent: Float get() = if (totalTokens > 0) toolTokens.toFloat() / totalTokens * 100 else 0f
    val otherPercent: Float get() = if (totalTokens > 0) otherTokens.toFloat() / totalTokens * 100 else 0f
}

/**
 * Per-tool-name token aggregation for the tool breakdown sub-view.
 */
data class ToolCategoryBreakdown(
    val toolName: String,
    val callCount: Int,
    val estimatedTokens: Long,     // sum of input + output JSON byte sizes / 4
    val lastCallAt: Long,           // epoch millis
)

/**
 * Per-category breakdown within the "Other" category (reasoning, cache read, cache write).
 */
data class OtherCategoryBreakdown(
    val categoryName: String,
    val estimatedTokens: Long,
)

/**
 * Context pressure signals computed from rolling growth rate.
 */
data class ContextPressure(
    val currentTokens: Long,
    val contextLimit: Long,
    val usagePercent: Float,
    val growthPerTurn: Double,         // average tokens added per assistant turn (rolling window)
    val turnsUntilCompact: Int?,       // estimated turns before auto-compact fires (null = unknown)
    val burnRatePerMinute: Double,     // tokens per minute (wall-clock growth rate)
    val pressureLevel: PressureLevel,
)

enum class PressureLevel {
    COMFORTABLE,   // < 50%
    ELEVATED,      // 50-70%
    HIGH,          // 70-85%
    CRITICAL       // 85%+
}

/** UI state for manual compaction. */
sealed interface CompactionState {
    data object Idle : CompactionState
    data object InProgress : CompactionState
    data class Error(val error: CompactionError) : CompactionState
}

/** Typed errors for manual compaction failures. */
sealed interface CompactionError {
    data object NoActiveSession : CompactionError
    data object NotConnected : CompactionError
    data class ServerError(val message: String) : CompactionError
    data object Timeout : CompactionError
}

/** A single todo item from the OpenCode todowrite tool. */
data class TodoItem(
    val content: String,
    val status: String,    // "pending", "in_progress", "completed"
    val priority: String   // "high", "medium", "low"
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"
    }
}

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
 *
 * **WARNING:** This output is NOT escaped and is intended for display/clipboard/debug only.
 * Do NOT pass `rawMarkdownForClipboard` back into an LLM context — use `escapedForPrompt` instead.
 * Untrusted message content can contain markdown fences or directive-like syntax that
 * acts as prompt injection. The content is intentionally NOT escaped because this is a
 * raw representation of the message parts for clipboard/debug purposes, not a sanitized
 * prompt input.
 */
val ChatMessage.rawMarkdownForClipboard: String
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

/**
 * Returns a content-neutralized markdown representation of a ChatMessage, safe
 * for inclusion in LLM prompts. Wraps each part in fenced delimiters and escapes
 * directive-like syntax (markdown headings, code fences, HTML tags) so that
 * untrusted message content cannot act as prompt injection.
 *
 * Use this instead of [rawMarkdownForClipboard] when feeding message content
 * back into an LLM context (e.g., for summarization, context inclusion, or
 * multi-shot review prompts).
 */
val ChatMessage.escapedForPrompt: String
    get() {
        val raw = rawMarkdownForClipboard
        // Escape code fences so untrusted content can't break out of a fenced block
        // and inject directives. Replace triple-backtick sequences with a safe alias.
        val fenceEscaped = raw.replace("```", "´´´")
        // Escape markdown heading markers at line start so untrusted content can't
        // inject headings like "### System: ignore previous instructions".
        val headingEscaped = fenceEscaped.replace(Regex("(?m)^(#{1,6}\\s)"), "\\$1")
        // Escape HTML-like tags so untrusted content can't inject HTML directives
        val htmlEscaped = headingEscaped.replace("<", "&lt;").replace(">", "&gt;")
        return htmlEscaped
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
