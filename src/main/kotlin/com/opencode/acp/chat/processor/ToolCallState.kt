package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tool call and per-event state for a single streaming turn.
 * Owned by [SessionState]. NOT a data class — mutable fields must not be
 * shared via copy(). Use [reset] to clear between turns.
 *
 * Thread safety: most mutation happens serially via the internal event
 * processing coroutine (Dispatchers.Default, serialized by Channel).
 * ConcurrentHashMap / CopyOnWriteArrayList are used for defensive safety
 * (matches the pattern used previously in ProcessorContext).
 */
class ToolCallState {
    // ── Tool call state ───────────────────────────────────────────────────
    /** Tool call pills keyed by callId. Secondary index for updates — the primary
     *  data lives in the message parts map under the toolCallId key.
     *  ConcurrentHashMap for thread safety: written by event processing coroutine,
     *  read by UI/caller coroutines. */
    val toolCallPills: ConcurrentHashMap<String, ToolCallPill> = ConcurrentHashMap()
    /** Maps toolCallId → messageId for cross-message tool result/permission routing.
     *  ConcurrentHashMap for thread safety (same reason as toolCallPills). */
    val toolCallIndex: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    /** Lifecycle state of each tool call part, keyed by toolCallId.
     *  ConcurrentHashMap for thread safety (same reason as toolCallPills). */
    val toolPartStates: ConcurrentHashMap<String, PartState> = ConcurrentHashMap()

    // ── File changes ──────────────────────────────────────────────────────
    /** File changes detected from tool inputs during the current streaming turn.
     *  CopyOnWriteArrayList for thread safety: written by the event processing
     *  coroutine (FollowAgentDispatcher.dispatchToolUse) and read cross-coroutine
     *  by emitStreamingCompleted (via completeStreaming/abortStreaming on
     *  recoverBackgroundSessions/activity-monitor coroutines). */
    val pendingFileChanges: CopyOnWriteArrayList<ChatFileChange> = CopyOnWriteArrayList()

    // ── Per-event state (added directly to parts map via updateMessage) ────
    // Use thread-safe collections for defensive safety. Primary ownership is the
    // event processing coroutine (SINGLE-COROUTINE OWNERSHIP), but these collections
    // may be read from other coroutines in the future. CopyOnWriteArrayList and
    // ConcurrentHashMap provide safe concurrent reads without external synchronization,
    // matching the pattern used for toolCallPills/toolCallIndex/toolPartStates.
    val activePatches: CopyOnWriteArrayList<SseEvent.Patch> = CopyOnWriteArrayList()
    val activeAgents = CopyOnWriteArrayList<SseEvent.Agent>()
    val activeCompactions = CopyOnWriteArrayList<SseEvent.Compaction>()
    val activeStepFinishes = CopyOnWriteArrayList<SseEvent.StepFinish>()
    var activeAgentName: String? = null
    var activeRetry: SseEvent.Retry? = null
    var activeCompaction: SseEvent.Compaction? = null
    var activeStepFinish: SseEvent.StepFinish? = null
    val activeAssistantFiles: ConcurrentHashMap<String, SseEvent.AssistantFile> = ConcurrentHashMap()
    val activeAssistantImages: ConcurrentHashMap<String, SseEvent.AssistantImage> = ConcurrentHashMap()

    /** Reset tool-call state for a new streaming turn.
     *  NOTE: Does NOT clear [toolCallIndex] or [toolCallPills] — they span turns so
     *  late tool results from previous turns can still be routed and update the
     *  existing pill's status. Stale entries for evicted messages are cleared by
     *  addMessage eviction / removeMessageByServerId / replaceAllMessages (which
     *  clear all three together). [toolPartStates] IS cleared here because it is a
     *  lifecycle-state cache (InProgress/Pending) that must not persist across
     *  turns — stale entries would make the activity monitor skip the activity
     *  timeout and trigger false stuck-tool aborts for tools from previous turns. */
    fun reset() {
        toolPartStates.clear()
        pendingFileChanges.clear()
        activePatches.clear()
        activeAgents.clear()
        activeCompactions.clear()
        activeStepFinishes.clear()
        activeAgentName = null
        activeRetry = null
        activeCompaction = null
        activeStepFinish = null
        activeAssistantFiles.clear()
        activeAssistantImages.clear()
    }
}