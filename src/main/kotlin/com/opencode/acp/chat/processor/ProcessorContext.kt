package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Describes a slice of [textBuffer] that should be segmented and inserted
 * after [anchorKey] in the parts map. Multiple segments allow text to be
 * interleaved with tool calls and thinking phases chronologically.
 *
 * @param startOffset Character offset in textBuffer where this segment starts.
 * @param anchorKey Key of the non-text part after which this segment's parts
 *                  should be inserted. null = insert at the beginning.
 */
class TextSegment(
    val startOffset: Int,
    @Volatile var anchorKey: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextSegment) return false
        return startOffset == other.startOffset && anchorKey == other.anchorKey
    }

    override fun hashCode(): Int {
        var result = startOffset
        result = 31 * result + (anchorKey?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "TextSegment(startOffset=$startOffset, anchorKey=$anchorKey)"
}

/**
 * Mutable accumulation state for a single streaming turn.
 * Owned by [SessionState]. NOT a data class — mutable fields must not be
 * shared via copy(). Use reset() to clear between turns.
 *
 * Thread safety: most mutation happens serially via the internal event
 * processing coroutine (Dispatchers.Default, serialized by Channel).
 * The SSE coroutine never touches ProcessorContext directly — it sends
 * events to eventChannel, which is consumed on a single coroutine.
 *
 * Exception: [resetTurnState] is called by [SessionState.createAssistantMessage]
 * on the caller's coroutine (under stateLock), NOT on the event processing
 * coroutine. Fields read cross-coroutine ([isStreaming], [activeTextPartId],
 * [lastActivityTimeMs]) are marked @Volatile for thread-safe visibility.
 */
class ProcessorContext {
    // ── Text buffer ────────────────────────────────────────────────────────
    /** Accumulates raw text from TextChunk events before segmentation into text parts. */
    val textBuffer: StringBuilder = StringBuilder()
    /** Mirrors textBuffer content as a StateFlow for real-time observation by UI (e.g. task pill). */
    val streamingText = MutableStateFlow("")

    // ── Typewriter reveal buffer ──────────────────────────────────────────
    /** The full accumulated text from SSE (same as textBuffer content).
     *  The reveal coroutine drains from here; only [revealedLen] characters
     *  are segmented and rendered. */
    val revealBuffer: StringBuilder = StringBuilder()
    /** How much of revealBuffer has been revealed to the UI so far (character count). */
    @Volatile var revealedLen: Int = 0
    /** Job for the reveal coroutine. Cancelled on reset/finalize. */
    @Volatile var revealJob: Job? = null
    /** Whether the source (SSE) has stopped sending text. When true, the reveal
     *  loop flushes remaining buffer immediately instead of continuing slow reveal. */
    @Volatile var sourceComplete: Boolean = false

    /** Tracks text segments split at tool call / thinking boundaries.
     *  Each segment records where in [textBuffer] it starts and which non-text
     *  part key it should be inserted after. This enables chronological
     *  interleaving: text before a tool call stays before it, text after stays after. */
    val textSegments: MutableList<TextSegment> = mutableListOf()

    // ── Thinking phase state ──────────────────────────────────────────────
    /** Key for the currently active thinking phase in the parts map (e.g., "thinking_0").
     *  Null when no thinking phase has started yet. Each phase gets a unique key
     *  so completed phases are frozen and never overwritten. */
    var activeThinkingKey: String? = null
    /** Accumulates text for the current streaming thinking phase. */
    val thinkingBuffer: StringBuilder = StringBuilder()
    /** Whether the current thinking phase has completed (Stop/tool-calls received).
     *  When true, the current phase is frozen — any new ThinkingChunk/ThinkingReplace
     *  starts a new phase with a new key. */
    var activeThinkingCompleted: Boolean = false
    /** Monotonic counter for generating unique thinking keys. */
    var thinkingPhaseIndex: Int = 0

    // ── Thinking typewriter reveal buffer ──────────────────────────────────
    /** Reveal buffer for thinking — mirrors thinkingBuffer but only revealedLen chars are shown. */
    val thinkingRevealBuffer: StringBuilder = StringBuilder()
    /** How much of thinkingRevealBuffer has been revealed to the UI so far. */
    @Volatile var thinkingRevealedLen: Int = 0
    /** Job for the thinking reveal coroutine. Cancelled on freeze/reset. */
    @Volatile var thinkingRevealJob: Job? = null
    /** Whether the thinking source has stopped sending. When true, flush remaining buffer. */
    @Volatile var thinkingSourceComplete: Boolean = false

    // ── Tool call state ───────────────────────────────────────────────────
    /** Tool call pills keyed by callId. Secondary index for updates — the primary
     *  data lives in the message parts map under the toolCallId key.
     *  ConcurrentHashMap for thread safety: written by event processing coroutine,
     *  read by UI/caller coroutines. */
    val toolCallPills: java.util.concurrent.ConcurrentHashMap<String, ToolCallPill> = java.util.concurrent.ConcurrentHashMap()
    /** Maps toolCallId → messageId for cross-message tool result/permission routing.
     *  ConcurrentHashMap for thread safety (same reason as toolCallPills). */
    val toolCallIndex: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    /** Lifecycle state of each tool call part, keyed by toolCallId.
     *  ConcurrentHashMap for thread safety (same reason as toolCallPills). */
    val toolPartStates: java.util.concurrent.ConcurrentHashMap<String, PartState> = java.util.concurrent.ConcurrentHashMap()

    // ── File changes ──────────────────────────────────────────────────────
    /** File changes detected from tool inputs during the current streaming turn.
     *  SINGLE-COROUTINE OWNERSHIP: All access (write, read, clear) runs on the
     *  event processing coroutine. Do NOT access from other coroutines without
     *  synchronization. */
    val pendingFileChanges: MutableList<ChatFileChange> = mutableListOf()

    // ── Turn lifecycle ────────────────────────────────────────────────────
    var firstTextChunkReceived: Boolean = false
    /** PartId of the text part currently receiving streamed deltas. Null when no text part is active or when V2 events (which lack partId) are in use.
     *  @Volatile because written by [resetTurnState] on the caller's coroutine and
     *  read by the event processing coroutine (no happens-before between them). */
    @Volatile var activeTextPartId: String? = null
    var userEchoStripped: Boolean = false
    var streamingStartedEmitted: Boolean = false
    var streamingCompletedEmitted: Boolean = false
    /** @Volatile because read by recoverBackgroundSessions() on Dispatchers.Default
     *  and written by event processing on Dispatchers.EDT. */
    @Volatile var activeMessageId: String? = null
    @Volatile var activeServerMessageId: String? = null
    var lastUserText: String? = null
    var errorMessage: String? = null
    /** @Volatile because read by the activity monitor coroutine in OpenCodeService
     *  and by recoverBackgroundSessions() — both run on Dispatchers.Default but
     *  are separate coroutine instances from the event processing coroutine. */
    @Volatile var isStreaming: Boolean = false
    @Volatile var modelID: String? = null
    @Volatile var providerID: String? = null
    /** Pending job for debounced Stop finalization. Cancelled if a new event arrives.
     *  @Volatile because written by event processing coroutine and read/cancelled
     *  from resetTurnState() on the caller's coroutine. */
    @Volatile var pendingStopJob: Job? = null
    /** Timestamp of the last SSE event received during streaming.
     *  Used by the activity-aware response timeout in OpenCodeService to avoid
     *  false timeouts during long-running generations (subtasks, tool chains).
     *  Updated by SessionState.processEvent(); read by the activity monitor coroutine. */
    @Volatile var lastActivityTimeMs: Long = System.currentTimeMillis()

    // ── Per-event state (added directly to parts map via updateMessage) ────
    val activePatches: MutableList<SseEvent.Patch> = mutableListOf()
    var activeAgentName: String? = null
    var activeRetry: SseEvent.Retry? = null
    var activeCompaction: SseEvent.Compaction? = null
    var activeStepFinish: SseEvent.StepFinish? = null
    val activeAssistantFiles: MutableMap<String, SseEvent.AssistantFile> = mutableMapOf()
    val activeAssistantImages: MutableMap<String, SseEvent.AssistantImage> = mutableMapOf()

    /** Reset turn-specific state for a new streaming turn.
     *  NOTE: Does NOT clear toolCallIndex — it spans turns so late tool results
     *  from previous turns can still be routed. Stale entries for evicted messages
     *  are harmlessly routed to ctx.activeMessageId via the fallback. */
    fun resetTurnState() {
        textBuffer.clear()
        streamingText.value = ""
        revealBuffer.clear()
        revealedLen = 0
        revealJob?.cancel()
        revealJob = null
        sourceComplete = false
        textSegments.clear()
        thinkingBuffer.clear()
        thinkingRevealBuffer.clear()
        thinkingRevealedLen = 0
        thinkingRevealJob?.cancel()
        thinkingRevealJob = null
        thinkingSourceComplete = false
        activeThinkingKey = null
        activeThinkingCompleted = false
        thinkingPhaseIndex = 0
        toolCallPills.clear()
        toolPartStates.clear()
        pendingFileChanges.clear()
        firstTextChunkReceived = false
        activeTextPartId = null
        userEchoStripped = false
        streamingStartedEmitted = false
        streamingCompletedEmitted = false
        activeMessageId = null
        activeServerMessageId = null
        errorMessage = null
        isStreaming = false
        modelID = null
        providerID = null
        pendingStopJob?.cancel()
        pendingStopJob = null
        lastActivityTimeMs = System.currentTimeMillis()
        activePatches.clear()
        activeAgentName = null
        activeRetry = null
        activeCompaction = null
        activeStepFinish = null
        activeAssistantFiles.clear()
        activeAssistantImages.clear()
    }

    /** Reset ALL state including toolCallIndex and lastUserText. */
    fun reset() {
        resetTurnState()
        toolCallIndex.clear()
        lastUserText = null
    }
}