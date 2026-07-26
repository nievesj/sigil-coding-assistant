package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.ChildPermissionPrompt
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

    /** Stop event processed — streaming is complete.
     *  @param naturalCompletion true when the response ended naturally (Stop/idle/debounced).
     *         false when forcibly ended or interrupted (abort, error, timeout). */
    data class StreamingCompleted(
        val messageId: String,
        val fileChanges: List<ChatFileChange>,
        val naturalCompletion: Boolean = true,
    ) : UiSignal

    /** Intermediate message update (token/cost data applied) — triggers local-only context refresh. */
    data class MessageUpdated(val messageId: String) : UiSignal

    /** Permission prompt received from the server. */
    data class PermissionRequested(val prompt: PermissionPrompt) : UiSignal

    /** Child session (sub-agent) permission request relayed to the parent session. Non-blocking. */
    data class ChildPermissionRequested(val prompt: ChildPermissionPrompt) : UiSignal

    /** Server confirmed a permission response was processed (permission.replied SSE event). */
    data class PermissionReplied(val permissionId: String, val reply: String, val sessionId: String) : UiSignal

    /** Permission prompt timed out — surface visual feedback instead of silent dismissal. */
    data class PermissionTimedOut(val permissionId: String, val sessionId: String, val toolName: String) : UiSignal

    /** Selection/question prompt received from the server. */
    data class SelectionRequested(val prompt: SelectionPrompt) : UiSignal

    /** File changed by an edit tool — triggers review panel refresh.
     *  The `val unit: Unit = Unit` is a workaround for a data class with no fields
     *  (forces a constructor parameter); prefer `data object` on Kotlin 1.9+. */
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

    /** Session deleted — server removed the session; cache and sidebar must be pruned. */
    data class SessionDeleted(val sessionId: String) : UiSignal
}
