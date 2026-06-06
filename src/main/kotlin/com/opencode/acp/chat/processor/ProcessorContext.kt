package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill

/**
 * Mutable accumulation state for a single streaming turn.
 * Owned by MessageProcessorManager. NOT a data class — mutable fields must not be
 * shared via copy(). Use reset() to clear between turns.
 *
 * Thread safety: all mutation happens on Dispatchers.EDT via the internal
 * event processing coroutine. The SSE coroutine never touches ProcessorContext
 * directly — it sends events to eventChannel, which is consumed on EDT.
 */
class ProcessorContext {
    /** Accumulates raw text from TextChunk events before segmentation. */
    val textBuffer: StringBuilder = StringBuilder()
    /** Accumulates thinking/reasoning text. Plain text, NOT markdown — StreamHealer is NOT applied. */
    val thinkingBuffer: StringBuilder = StringBuilder()
    /** Whether thinking content has completed (Stop/Error received after thinking chunks).
     *  Used to transition Thinking part state from Streaming → Completed. */
    var activeThinkingCompleted: Boolean = false
    /** Tool call pills keyed by callId. Updated in-place for status changes. */
    val toolCallPills: LinkedHashMap<String, ToolCallPill> = linkedMapOf()
    /** Maps toolCallId → messageId for cross-message tool result/permission routing.
     *  Needed because ToolResult/Permission events reference toolCallId, not messageId. */
    val toolCallIndex: MutableMap<String, String> = mutableMapOf()
    /** File changes collected from tool calls for the current active message.
     *  Since ProcessorContext tracks only one active message at a time, this is
     *  a simple list, not a map keyed by message ID. reset() is called at each
     *  turn boundary, so accumulation across turns is not possible. */
    val pendingFileChanges: MutableList<ChatFileChange> = mutableListOf()
    /** Whether the first non-empty text chunk has been received.
     *  Only flipped when text.isNotBlank() — empty chunks are ignored. */
    var firstTextChunkReceived: Boolean = false
    /** Whether user echo text has been stripped from the response. */
    var userEchoStripped: Boolean = false
    /** Whether UiSignal.StreamingStarted has been emitted for this turn.
     *  Prevents double-emission when the first chunks arrive. */
    var streamingStartedEmitted: Boolean = false
    /** Whether UiSignal.StreamingCompleted has been emitted for this turn.
     *  Prevents double-emission when completeStreaming() is called after Stop. */
    var streamingCompletedEmitted: Boolean = false
    /** The ID of the currently-streaming assistant message. */
    var activeMessageId: String? = null
    /** Server's messageID for the current streaming turn (from sendMessageAsync response).
     *  Used to deterministically route V1 SSE events to the correct message via messageID match. */
    var activeServerMessageId: String? = null
    /** The last user message text, for echo stripping.
     *  Set by setLastUserText() before process() begins. */
    var lastUserText: String? = null
    /** Error message collected during this streaming turn, if any. */
    var errorMessage: String? = null
    /** Whether the message is currently streaming. */
    var isStreaming: Boolean = false
    /** Model ID for the current streaming turn. */
    var modelID: String? = null
    /** Provider ID for the current streaming turn. */
    var providerID: String? = null
    /** Active patch parts from the current streaming turn. */
    val activePatches: MutableList<SseEvent.Patch> = mutableListOf()
    /** Active agent name from the current streaming turn. */
    var activeAgentName: String? = null
    /** Active retry info from the current streaming turn. */
    var activeRetry: SseEvent.Retry? = null
    /** Active compaction info from the current streaming turn. */
    var activeCompaction: SseEvent.Compaction? = null
    /** Active step finish info from the current streaming turn. */
    var activeStepFinish: SseEvent.StepFinish? = null
    /** Lifecycle state of each tool call part, keyed by toolCallId.
     *  Tracks state transitions: Created → InProgress → Completed/Failed/Pending/Rejected. */
    val toolPartStates: MutableMap<String, PartState> = mutableMapOf()
    /** Assistant-generated files pending rendering (keyed by partId for correct update semantics, url as fallback). */
    val activeAssistantFiles: MutableMap<String, SseEvent.AssistantFile> = mutableMapOf()
    /** Assistant-generated images pending rendering (keyed by partId for correct update semantics, url as fallback). */
    val activeAssistantImages: MutableMap<String, SseEvent.AssistantImage> = mutableMapOf()

    /** Reset turn-specific state for a new streaming turn.
     *  Preserves [toolCallIndex] (needed for cross-message tool result/permission routing)
     *  and [lastUserText] (set separately via setLastUserText).
     *  Called at the start of createAssistantMessage(). */
    fun resetTurnState() {
        textBuffer.clear()
        thinkingBuffer.clear()
        toolCallPills.clear()
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
        activePatches.clear()
        activeAgentName = null
        activeRetry = null
        activeCompaction = null
        activeStepFinish = null
        toolPartStates.clear()
        activeAssistantFiles.clear()
        activeAssistantImages.clear()
        activeThinkingCompleted = false
    }

    /** Reset ALL state including toolCallIndex and lastUserText.
     *  Called on session switch (full reset). */
    fun reset() {
        resetTurnState()
        toolCallIndex.clear()
        lastUserText = null
    }
}
