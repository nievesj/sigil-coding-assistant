package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.SelectionPrompt

/**
 * One variant per side-effectful operation emitted by [SignalRouter].
 *
 * [SignalSideEffectExecutor] collects these and runs each with injected
 * dependencies. Each [StreamingCompleted] side effect is independently
 * try/caught so one failure doesn't skip the others.
 *
 * The ordered side effects of [StreamingCompleted] are:
 * [SetStreamPhaseIdle] → [NotifyResponseComplete] (conditional) →
 * [ComputeSessionContext] → [FetchTodos] → [LoadSessions] → [DrainQueue] →
 * [RefreshReviewFiles].
 *
 * The 5th side effect, [RefreshReviewFiles], was outside the try/catch chain
 * in the original ChatViewModel code (ChatViewModel.kt:271). It is now emitted
 * as [RefreshReviewFiles] and the [SignalSideEffectExecutor] wraps it in its
 * own try/catch so a failure is logged for visibility without propagating
 * (it is the last effect in the StreamingCompleted chain, but the wrapping
 * is still important for log visibility).
 */
sealed interface SignalEffect {
    /** Sets the stream phase to IDLE for the active session (StreamingCompleted).
     *  UNGATED — always applied. The messageId is the message that just finished
     *  streaming, so the phase must reset to IDLE regardless of whether the user
     *  switched sessions between signal emission and effect execution. */
    data class SetStreamPhaseIdle(val messageId: String) : SignalEffect

    /** Sets the stream phase to IDLE, gated on isActiveMessage (Error backstop).
     *  Unlike [SetStreamPhaseIdle] (used by StreamingCompleted), this is only applied
     *  if the messageId is in the active session's messages — prevents a background
     *  Error from forcing IDLE while the active session is legitimately streaming
     *  for a different message. */
    data class SetStreamPhaseIdleGated(val messageId: String) : SignalEffect

    /** Sets the stream phase to IDLE for a specific session (SessionError). */
    data class SetStreamPhaseIdleForSession(val sessionId: String) : SignalEffect

    /** Notify the user that the LLM response is complete (gated by naturalCompletion + active message + not child). */
    data class NotifyResponseComplete(val messageId: String) : SignalEffect

    /** Notify the user that a permission prompt needs their attention. */
    object NotifyPermissionNeeded : SignalEffect

    /** Notify the user that the LLM is asking a question (SelectionRequested). */
    object NotifyQuestionAsked : SignalEffect

    /** Recompute the session context (REST fetch + local cache accumulation). */
    data class ComputeSessionContext(val sessionId: String?) : SignalEffect

    /** Fetch the todo list for the active session. */
    data class FetchTodos(val sessionId: String?) : SignalEffect

    /** Reload the session list from the server. */
    data class LoadSessions(val force: Boolean) : SignalEffect

    /** Drain the message queue (send the next queued message if any). */
    object DrainQueue : SignalEffect

    /** Refresh review files from disk (re-read .review/ JSON files). */
    object RefreshReviewFiles : SignalEffect

    /** Refresh the active session's messages from the server (after compaction). */
    data class RefreshActiveSessionMessages(val sessionId: String) : SignalEffect

    /** Handle a session deletion — clear messages if it was the active session, reload session list. */
    data class HandleSessionDeleted(val sessionId: String) : SignalEffect

    /** Remove a session from the streaming-session spinner set. */
    data class RemoveStreamingSession(val sessionId: String) : SignalEffect

    /** Start the active-session permission timeout. */
    data class StartPermissionTimeout(val prompt: PermissionPrompt) : SignalEffect

    /** Set the active-session permission prompt. */
    data class SetPermissionPrompt(val prompt: PermissionPrompt?) : SignalEffect

    /** Set the selection (question) prompt. */
    data class SetSelectionPrompt(val prompt: SelectionPrompt?) : SignalEffect

    /** Add a child-session permission prompt. */
    data class AddChildPermissionPrompt(val prompt: ChildPermissionPrompt) : SignalEffect

    /** Handle a PermissionReplied SSE event (complex multi-branch logic).
     *
     *  Validates the trust boundary from SSE parsing: permissionId and sessionId
     *  must be non-blank. A blank value would indicate a malformed SSE event or
     *  a parsing bug, and would cause downstream permission enforcement to
     *  operate on the wrong (or no) permission — a security risk. */
    data class HandlePermissionReplied(val permissionId: String, val reply: String, val sessionId: String) : SignalEffect {
        init {
            require(permissionId.isNotBlank()) { "permissionId must not be blank" }
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        }
    }

    /** Handle a PermissionTimedOut SSE event (reject pending + clear prompts).
     *
     *  Validates the trust boundary from SSE parsing: permissionId and sessionId
     *  must be non-blank. A blank value would indicate a malformed SSE event or
     *  a parsing bug, and would cause downstream permission timeout enforcement
     *  to operate on the wrong (or no) permission — a security risk. */
    data class HandlePermissionTimedOut(val permissionId: String, val sessionId: String, val toolName: String) : SignalEffect {
        init {
            require(permissionId.isNotBlank()) { "permissionId must not be blank" }
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(toolName.isNotBlank()) { "toolName must not be blank" }
        }
    }

    /** Emit the file-change signal (triggers VFS refresh subscribers). */
    object EmitFileChangeSignal : SignalEffect

    /** Local-only context recompute (no REST) — for intermediate MessageUpdated. */
    data class ComputeSessionContextLocal(val messageId: String) : SignalEffect

    /** Log a session error message (SessionError global signal). Preserves the
     *  errorMessage field that was previously discarded by the router. The executor
     *  logs it at WARN level for visibility in idea.log. */
    data class LogSessionError(val sessionId: String, val errorMessage: String?) : SignalEffect
}