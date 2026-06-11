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
data class TextSegment(
    val startOffset: Int,
    var anchorKey: String?
)

/**
 * Mutable accumulation state for a single streaming turn.
 * Owned by [SessionState]. NOT a data class — mutable fields must not be
 * shared via copy(). Use reset() to clear between turns.
 *
 * Thread safety: all mutation happens on Dispatchers.EDT via the internal
 * event processing coroutine. The SSE coroutine never touches ProcessorContext
 * directly — it sends events to eventChannel, which is consumed on EDT.
 */
class ProcessorContext {
    // ── Text buffer ────────────────────────────────────────────────────────
    /** Accumulates raw text from TextChunk events before segmentation into text parts. */
    val textBuffer: StringBuilder = StringBuilder()
    /** Mirrors textBuffer content as a StateFlow for real-time observation by UI (e.g. task pill). */
    val streamingText = MutableStateFlow("")
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

    // ── Tool call state ───────────────────────────────────────────────────
    /** Tool call pills keyed by callId. Secondary index for updates — the primary
     *  data lives in the message parts map under the toolCallId key. */
    val toolCallPills: LinkedHashMap<String, ToolCallPill> = linkedMapOf()
    /** Maps toolCallId → messageId for cross-message tool result/permission routing. */
    val toolCallIndex: MutableMap<String, String> = mutableMapOf()
    /** Lifecycle state of each tool call part, keyed by toolCallId. */
    val toolPartStates: MutableMap<String, PartState> = mutableMapOf()

    // ── File changes ──────────────────────────────────────────────────────
    val pendingFileChanges: MutableList<ChatFileChange> = mutableListOf()

    // ── Turn lifecycle ────────────────────────────────────────────────────
    var firstTextChunkReceived: Boolean = false
    var userEchoStripped: Boolean = false
    var streamingStartedEmitted: Boolean = false
    var streamingCompletedEmitted: Boolean = false
    /** @Volatile because read by recoverBackgroundSessions() on Dispatchers.Default
     *  and written by event processing on Dispatchers.EDT. */
    @Volatile var activeMessageId: String? = null
    var activeServerMessageId: String? = null
    var lastUserText: String? = null
    var errorMessage: String? = null
    var isStreaming: Boolean = false
    var modelID: String? = null
    var providerID: String? = null
    /** Pending job for debounced Stop finalization. Cancelled if a new event arrives. */
    var pendingStopJob: Job? = null
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

    /** Reset turn-specific state for a new streaming turn. */
    fun resetTurnState() {
        textBuffer.clear()
        streamingText.value = ""
        textSegments.clear()
        thinkingBuffer.clear()
        activeThinkingKey = null
        activeThinkingCompleted = false
        thinkingPhaseIndex = 0
        toolCallPills.clear()
        toolPartStates.clear()
        pendingFileChanges.clear()
        firstTextChunkReceived = false
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