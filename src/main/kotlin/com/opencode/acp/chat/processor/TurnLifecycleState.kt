package com.opencode.acp.chat.processor

import kotlinx.coroutines.Job

/**
 * Turn lifecycle state for a single streaming turn.
 * Owned by [SessionState]. NOT a data class — mutable fields must not be
 * shared via copy(). Use [reset] to clear between turns.
 *
 * Thread safety: most mutation happens serially via the internal event
 * processing coroutine (Dispatchers.Default, serialized by Channel).
 * Fields read cross-coroutine are marked @Volatile for thread-safe visibility.
 */
class TurnLifecycleState {
    /** @Volatile because read by recoverBackgroundSessions() on Dispatchers.Default
     *  and written by event processing on Dispatchers.EDT. */
    @Volatile var activeMessageId: String? = null
    @Volatile var activeServerMessageId: String? = null
    @Volatile var lastUserText: String? = null
    @Volatile var errorMessage: String? = null
    /** @Volatile because read by the activity monitor coroutine in OpenCodeService
     *  and by recoverBackgroundSessions() — both run on Dispatchers.Default but
     *  are separate coroutine instances from the event processing coroutine. */
    @Volatile var isStreaming: Boolean = false
    @Volatile var modelID: String? = null
    @Volatile var providerID: String? = null
    /** Pending job for debounced Stop finalization. Cancelled if a new event arrives.
     *  @Volatile because written by event processing coroutine and read/cancelled
     *  from reset() on the caller's coroutine. */
    @Volatile var pendingStopJob: Job? = null
    /** Timestamp of the last SSE event received during streaming.
     *  Used by the activity-aware response timeout in OpenCodeService to avoid
     *  false timeouts during long-running generations (subtasks, tool chains).
     *  Updated by SessionState.processEvent(); read by the activity monitor coroutine. */
    @Volatile var lastActivityTimeMs: Long = System.currentTimeMillis()
    @Volatile var streamingStartedEmitted: Boolean = false
    /** @Volatile because written by emitStreamingCompleted from the event processing
     *  coroutine and read by emitStreamingCompleted from completeStreaming/abortStreaming
     *  which can run on recoverBackgroundSessions or the activity monitor coroutine. */
    @Volatile var streamingCompletedEmitted: Boolean = false
    /** Set to true in abort/error paths to mark the turn as aborted. Checked by
     *  SseEventPipeline to drop stale events that arrive after abort but before the
     *  next ResetTurn clears it. Cleared by reset() on the next turn.
     *  @Volatile because written by abort/error paths (which can run on the activity
     *  monitor coroutine or recoverBackgroundSessions) and read by the event processing
     *  coroutine in SseEventPipeline.process(). */
    @Volatile var isAborted: Boolean = false

   /** Reset turn-lifecycle state for a new streaming turn. */
   fun reset() {
       activeMessageId = null
       activeServerMessageId = null
       // NOTE: lastUserText is intentionally NOT cleared here. It is overwritten
       // on each send by setLastUserText(). Clearing it would break echo stripping
       // when the async ResetTurn (enqueued by createAssistantMessage) is processed
       // AFTER setLastUserText runs on the sender's coroutine — the race window
       // where reset() would null out lastUserText before the first TextReplace
       // arrives. See SessionStateTest: `lastUserText survives resetTurnState so
       // echo stripping works after async ResetTurn race`.
       // DO NOT add `lastUserText = null` here without updating that test.
       errorMessage = null
       isStreaming = false
       modelID = null
       providerID = null
        pendingStopJob?.cancel()
        pendingStopJob = null
        lastActivityTimeMs = System.currentTimeMillis()
        streamingStartedEmitted = false
        streamingCompletedEmitted = false
        isAborted = false
    }
}