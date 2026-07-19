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
    /** Sets the stream phase to IDLE for the active session (StreamingCompleted). */
    data class SetStreamPhaseIdle(val messageId: String) : SignalEffect

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

    /** Handle a PermissionReplied SSE event (complex multi-branch logic). */
    data class HandlePermissionReplied(val permissionId: String, val reply: String, val sessionId: String) : SignalEffect

    /** Handle a PermissionTimedOut SSE event (reject pending + clear prompts). */
    data class HandlePermissionTimedOut(val permissionId: String, val sessionId: String, val toolName: String) : SignalEffect

    /** Emit the file-change signal (triggers VFS refresh subscribers). */
    object EmitFileChangeSignal : SignalEffect

    /** Local-only context recompute (no REST) — for intermediate MessageUpdated. */
    data class ComputeSessionContextLocal(val messageId: String) : SignalEffect
}