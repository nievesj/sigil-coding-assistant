package com.opencode.acp.chat.processor

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Describes a slice of [TextStreamingState.textBuffer] that should be segmented
 * and inserted after [anchorKey] in the parts map. Multiple segments allow text
 * to be interleaved with tool calls and thinking phases chronologically.
 *
 * @param startOffset Character offset in textBuffer where this segment starts.
 * @param anchorKey Key of the non-text part after which this segment's parts
 *                  should be inserted. null = insert at the beginning.
 */
class TextSegment(
    val startOffset: Int,
    val anchorKey: String?
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
 * Text streaming accumulation state for a single streaming turn.
 * Owned by [SessionState]. NOT a data class — mutable fields must not be
 * shared via copy(). Use [reset] to clear between turns.
 *
 * Thread safety: most mutation happens serially via the internal event
 * processing coroutine (Dispatchers.Default, serialized by Channel).
 * Fields read cross-coroutine are marked @Volatile for thread-safe visibility.
 */
class TextStreamingState {
    // ── Text buffer ────────────────────────────────────────────────────────
    /** Accumulates raw text from TextChunk events before segmentation into text parts. */
    val textBuffer: StringBuilder = StringBuilder()
    /** Mirrors textBuffer content as a StateFlow for real-time observation by UI (e.g. task pill). */
    val streamingText = MutableStateFlow("")

    // ── Typewriter reveal buffer ──────────────────────────────────────────
    /** The full accumulated text from SSE (same as textBuffer content).
     *  The reveal coroutine drains from here; only [revealedLen] characters
     *  are segmented and rendered. */
    val revealBuffer: StringBuffer = StringBuffer()
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
    val textSegments: MutableList<TextSegment> = CopyOnWriteArrayList()

    // ── Thinking phase state ──────────────────────────────────────────────
    /** Key for the currently active thinking phase in the parts map (e.g., "thinking_0").
     *  Null when no thinking phase has started yet. Each phase gets a unique key
     *  so completed phases are frozen and never overwritten. */
    /** @Volatile because written by the event processing coroutine and read by the
     *  thinking reveal loop coroutine (Dispatchers.Default) and by freezeThinking()
     *  from completeStreaming/abortStreaming (which can run on recoverBackgroundSessions). */
    @Volatile var activeThinkingKey: String? = null
    /** Accumulates text for the current streaming thinking phase.
     *  StringBuffer (not StringBuilder) for cross-coroutine visibility — read by
     *  freezeThinking() from completeStreaming/abortStreaming which can run on
     *  recoverBackgroundSessions (different coroutine from the event processing writer). */
    val thinkingBuffer: StringBuffer = StringBuffer()
    /** Whether the current thinking phase has completed (Stop/tool-calls received).
     *  When true, the current phase is frozen — any new ThinkingChunk/ThinkingReplace
     *  starts a new phase with a new key. */
    var activeThinkingCompleted: Boolean = false
    /** Monotonic counter for generating unique thinking keys. */
    var thinkingPhaseIndex: Int = 0

    // ── Thinking typewriter reveal buffer ──────────────────────────────────
    /** Reveal buffer for thinking — mirrors thinkingBuffer but only revealedLen chars are shown. */
    val thinkingRevealBuffer: StringBuffer = StringBuffer()
    /** How much of thinkingRevealBuffer has been revealed to the UI so far. */
    @Volatile var thinkingRevealedLen: Int = 0
    /** Job for the thinking reveal coroutine. Cancelled on freeze/reset. */
    @Volatile var thinkingRevealJob: Job? = null
    /** Whether the thinking source has stopped sending. When true, flush remaining buffer. */
    @Volatile var thinkingSourceComplete: Boolean = false

    // ── Turn lifecycle (text-streaming-owned subset) ───────────────────────
    var firstTextChunkReceived: Boolean = false
    /** PartId of the text part currently receiving streamed deltas. Null when no text part is active or when V2 events (which lack partId) are in use.
     *  @Volatile because written by [reset] on the caller's coroutine and
     *  read by the event processing coroutine (no happens-before between them). */
    @Volatile var activeTextPartId: String? = null
    var userEchoStripped: Boolean = false
    /** Length of revealBuffer at the time of the last successful resegmentTextPartsDirect call.
     *  Used to skip redundant re-segmentation when the revealed text hasn't changed.
     *  @Volatile because read by event processing coroutine and written by resegment
     *  (same coroutine, but volatile ensures visibility if the pattern changes). */
    @Volatile var lastSegmentedLen: Int = 0

    /** Reset text-streaming state for a new streaming turn. */
    fun reset() {
        textBuffer.clear()
        streamingText.value = ""
        revealBuffer.setLength(0)
        revealedLen = 0
        revealJob?.cancel()
        revealJob = null
        sourceComplete = false
        textSegments.clear()
        thinkingBuffer.setLength(0)
        thinkingRevealBuffer.setLength(0)
        thinkingRevealedLen = 0
        thinkingRevealJob?.cancel()
        thinkingRevealJob = null
        thinkingSourceComplete = false
        activeThinkingKey = null
        activeThinkingCompleted = false
        thinkingPhaseIndex = 0
        firstTextChunkReceived = false
        activeTextPartId = null
        userEchoStripped = false
        lastSegmentedLen = 0
    }
}