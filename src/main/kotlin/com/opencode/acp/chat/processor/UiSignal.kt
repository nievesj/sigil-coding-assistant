package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.TodoItem

/**
 * Signals emitted by [SessionState] for UI coordination.
 * The ViewModel observes these and updates its own StateFlows accordingly.
 */
sealed interface UiSignal {
    /** First text or thinking chunk received — streaming has started. */
    data class StreamingStarted(val messageId: String) : UiSignal

    /** Stop event processed — streaming is complete. */
    data class StreamingCompleted(val messageId: String, val fileChanges: List<ChatFileChange>) : UiSignal

    /** Permission prompt received from the server. */
    data class PermissionRequested(val prompt: PermissionPrompt) : UiSignal

    /** Selection/question prompt received from the server. */
    data class SelectionRequested(val prompt: SelectionPrompt) : UiSignal

    /** File changed by an edit tool — triggers review panel refresh. */
    data class FileChanged(val unit: Unit = Unit) : UiSignal

    /** Todo list updated via SSE. */
    data class TodoUpdated(val todos: List<TodoItem>) : UiSignal

    /** Child session created (e.g., subagent spawned). */
    data class SessionCreated(val sessionId: String) : UiSignal

    /** Error event processed. */
    data class Error(val messageId: String, val message: String) : UiSignal

    /** Session idle — server finished processing the current prompt cycle. */
    data class SessionIdle(val sessionId: String) : UiSignal

    /** Session error — server encountered an error for this session. */
    data class SessionError(val sessionId: String, val errorMessage: String?) : UiSignal

    /** Session compacted — server performed auto-compaction; local message cache may be stale. */
    data class SessionCompacted(val sessionId: String) : UiSignal
}
