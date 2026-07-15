package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.ui.compose.MarkdownSegment
import com.opencode.acp.chat.ui.compose.MarkdownSegmenter
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages typewriter reveal animation and markdown segmentation.
 *
 * Thread safety: reveal coroutines are launched on [scope] (Dispatchers.Default).
 * The state fields are accessed from both the event processing coroutine and the
 * reveal coroutines � the @Volatile annotations on [TextStreamingState] /
 * [TurnLifecycleState] fields ensure visibility. [stateLock] is acquired by
 * [resegmentDirect] and is reentrant-safe (can be called from within other
 * stateLock.withLock blocks).
 *
 * @param textStreamingState Text streaming state � reveal buffers, reveal state,
 *        text segments, thinking buffers.
 * @param turnLifecycleState Turn lifecycle state � read for [TurnLifecycleState.isStreaming]
 *        (used as default for [resegmentDirect]'s overrideIsStreaming) and
 *        [TurnLifecycleState.activeMessageId] (used by [freezeThinking]).
 * @param stateLock Shared reentrant lock � same instance as SessionState's.
 * @param scope Coroutine scope � for launching reveal coroutines.
 * @param messageMap Message cache manager � used to update message parts during reveal.
 * @param firstTextSegmentedGet Callback to read the firstTextSegmented flag on SessionState.
 *        Read by [scheduleResegment].
 * @param firstTextSegmentedSet Callback to set the firstTextSegmented flag on SessionState.
 * @param logger Shared logger instance.
 */
internal class TextStreamingManager(
    private val textStreamingState: TextStreamingState,
    private val turnLifecycleState: TurnLifecycleState,
    private val stateLock: ReentrantLock,
    private val scope: CoroutineScope,
    private val messageMap: MessageMapManager,
    private val firstTextSegmentedGet: () -> Boolean,
    private val firstTextSegmentedSet: (Boolean) -> Unit,
    private val logger: KLogger,
) {
    /** Debounced text re-segmentation job. Moves from SessionState. */
    private var resegmentJob: Job? = null

    /**
     * Start (or no-op if already running) the typewriter reveal coroutine for thinking.
     * Mirrors [startRevealLoop] but for thinking content � updates the thinking
     * MessagePart directly via [messageMap.update] with only the revealed portion.
     */
    fun startThinkingRevealLoop(msgId: String) {
        if (textStreamingState.thinkingRevealJob?.isActive == true) return
        textStreamingState.thinkingSourceComplete = false
        textStreamingState.thinkingRevealJob = scope.launch(Dispatchers.Default) {
            var charBudget = 0.0
            var lastTickMs = System.currentTimeMillis()
            try {
                while (true) {
                    val target = textStreamingState.thinkingRevealBuffer.length
                    val backlog = target - textStreamingState.thinkingRevealedLen
                    if (backlog <= 0) {
                        if (textStreamingState.thinkingSourceComplete) break
                        delay(50) // idle poll
                        lastTickMs = System.currentTimeMillis()
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val dtMs = (now - lastTickMs).coerceAtLeast(1)
                    lastTickMs = now
                    // Adaptive rate: 42 cps at rest, 125 cps normal, 500 cps catching up
                    val rate = when {
                        textStreamingState.thinkingSourceComplete -> backlog.toDouble() // flush all
                        backlog > 80 -> 500.0
                        backlog > 30 -> 125.0
                        else -> 42.0
                    }
                    charBudget += rate * (dtMs / 1000.0)
                    val wholeChars = charBudget.toInt()
                    if (wholeChars >= 1) {
                        val reveal = minOf(wholeChars, backlog)
                        textStreamingState.thinkingRevealedLen = minOf(textStreamingState.thinkingRevealedLen + reveal, target)
                        charBudget -= reveal
                        // Update the thinking MessagePart with the revealed content.
                        val key = textStreamingState.activeThinkingKey
                        if (key != null) {
                            val revealedContent = textStreamingState.thinkingRevealBuffer.substring(0, textStreamingState.thinkingRevealedLen)
                            messageMap.update(msgId) { msg ->
                                val parts = LinkedHashMap(msg.parts)
                                parts[key] = MessagePart.Thinking(
                                    content = revealedContent,
                                    state = PartState.Streaming
                                )
                                msg.copy(parts = parts, isStreaming = turnLifecycleState.isStreaming)
                            }
                        }
                    }
                    val delayMs = when {
                        textStreamingState.thinkingSourceComplete -> 0L
                        backlog > 80 -> 8L
                        backlog > 30 -> 16L
                        else -> 24L
                    }
                    if (delayMs > 0) delay(delayMs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] startThinkingRevealLoop: unexpected exception � flushing thinking reveal buffer" }
                flushThinkingReveal()
                // Re-sync the UI: the exception may have left the thinking part's
                // content out of sync with the reveal buffer. resegmentDirect
                // rebuilds text-derived parts from the current reveal buffer state.
                resegmentDirect(msgId)
            }
        }
    }

    /**
     * Flush the thinking reveal buffer � reveal all remaining thinking text immediately
     * and stop the reveal loop. Called by freezeThinking.
     */
    fun flushThinkingReveal() {
        // Cancel first, then set — same rationale as flushReveal (cooperative cancel).
        textStreamingState.thinkingRevealJob?.cancel()
        textStreamingState.thinkingRevealJob = null
        textStreamingState.thinkingSourceComplete = true
        textStreamingState.thinkingRevealedLen = textStreamingState.thinkingRevealBuffer.length
    }

    /**
     * Freeze the active thinking phase into the parts map.
     * Called when thinking ends (text starts, tool call starts, or finalization).
     */
    fun freezeThinking() {
        val key = textStreamingState.activeThinkingKey ?: return
        if (textStreamingState.thinkingBuffer.isEmpty()) return
        flushThinkingReveal()
        val content = textStreamingState.thinkingRevealBuffer.substring(0, textStreamingState.thinkingRevealedLen)
        val msgId = turnLifecycleState.activeMessageId ?: return
        logger.debug { "[ACP] freezeCurrentThinking: msg=$msgId key=$key contentLen=${content.length}" }
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts[key] = MessagePart.Thinking(content = content, state = PartState.Completed)
            msg.copy(parts = parts)
        }
        textStreamingState.thinkingBuffer.setLength(0)
        textStreamingState.thinkingRevealBuffer.setLength(0)
        textStreamingState.thinkingRevealedLen = 0
        textStreamingState.activeThinkingKey = null
        textStreamingState.activeThinkingCompleted = false
    }

    /**
     * Start (or no-op if already running) the typewriter reveal coroutine.
     * This coroutine drains [textStreamingState.revealBuffer] at an adaptive rate, calling
     * [resegmentDirect] each time new characters are revealed. The effect is
     * that text appears gradually instead of in irregular SSE-driven bursts.
     */
    fun startRevealLoop(msgId: String) {
        if (textStreamingState.revealJob?.isActive == true) return
        textStreamingState.sourceComplete = false
        textStreamingState.revealJob = scope.launch(Dispatchers.Default) {
            var charBudget = 0.0
            var lastTickMs = System.currentTimeMillis()
            try {
                while (true) {
                    val target = textStreamingState.revealBuffer.length
                    val backlog = target - textStreamingState.revealedLen
                    if (backlog <= 0) {
                        if (textStreamingState.sourceComplete) break
                        delay(50) // idle poll
                        lastTickMs = System.currentTimeMillis()
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val dtMs = (now - lastTickMs).coerceAtLeast(1)
                    lastTickMs = now
                    // Adaptive rate: 42 cps at rest, 125 cps normal, 500 cps catching up
                    val rate = when {
                        textStreamingState.sourceComplete -> backlog.toDouble() // flush all
                        backlog > 80 -> 500.0
                        backlog > 30 -> 125.0
                        else -> 42.0
                    }
                    charBudget += rate * (dtMs / 1000.0)
                    val wholeChars = charBudget.toInt()
                    if (wholeChars >= 1) {
                        val reveal = minOf(wholeChars, backlog)
                        textStreamingState.revealedLen = minOf(textStreamingState.revealedLen + reveal, target)
                        charBudget -= reveal
                        // Call resegment DIRECTLY � no debounce. The reveal loop's delay
                        // is the pacing mechanism. scheduleResegment's 50ms debounce
                        // cancels every tick because the reveal loop ticks faster than 50ms.
                        resegmentDirect(msgId)
                    }
                    val delayMs = when {
                        textStreamingState.sourceComplete -> 0L
                        backlog > 80 -> 8L
                        backlog > 30 -> 16L
                        else -> 24L
                    }
                    if (delayMs > 0) delay(delayMs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] startRevealLoop: unexpected exception � flushing reveal buffer" }
                flushReveal()
                // Re-sync the UI: the exception may have left text-derived parts out
                // of sync with the reveal buffer. resegmentDirect rebuilds
                // them from the current reveal buffer state (now fully revealed).
                resegmentDirect(msgId)
            }
        }
    }

    /**
     * Flush the reveal buffer � reveal all remaining text immediately and
     * stop the reveal loop. Called by finalizeStreaming, completeStreaming,
     * abortStreaming, and TextReplace handler.
     */
    fun flushReveal() {
        logger.debug { "[ACP] flushRevealBuffer: revealedLen=${textStreamingState.revealedLen}?${textStreamingState.revealBuffer.length} bufferLen=${textStreamingState.revealBuffer.length}" }
        // Cancel the reveal job FIRST, then set revealedLen. cancel() is cooperative —
        // the reveal loop may complete its current iteration before stopping. If we set
        // revealedLen first, that final iteration could overwrite it with a smaller value
        // (using the pre-flush revealedLen it read at the top of the loop). Cancelling
        // first ensures the loop is no longer running when we write the flushed value.
        textStreamingState.revealJob?.cancel()
        textStreamingState.revealJob = null
        textStreamingState.sourceComplete = true
        textStreamingState.revealedLen = textStreamingState.revealBuffer.length
    }

    /**
     * Schedule debounced (50ms) re-segmentation.
     * The first call is non-debounced for responsiveness.
     */
    fun scheduleResegment(msgId: String) {
        if (!firstTextSegmentedGet()) {
            firstTextSegmentedSet(true)
            resegmentDirect(msgId)
            return
        }
        resegmentJob?.cancel()
        // Use Dispatchers.Default (NOT EDT) � markdown parsing is CPU-intensive.
        // CancellationException propagates naturally when a newer resegment cancels
        // this job or when the scope is cancelled � no catch needed.
        resegmentJob = scope.launch(Dispatchers.Default) {
            delay(DEBOUNCE_MS)
            resegmentDirect(msgId)
        }
    }

    /**
     * Full markdown re-segmentation of revealed text into parts.
     *
     * VERBATIM MOVE of resegmentTextPartsDirect � with updateMessage ? messageMap.update
     * as the ONLY substitution. The method is 181 lines and includes:
     * 1. Snapshot textStreamingState.textSegments (CopyOnWriteArrayList) to avoid concurrent modification
     * 2. Read textStreamingState.revealBuffer up to textStreamingState.revealedLen
     * 3. Apply StreamHealer.heal() if streaming (segmentHealed), else segment()
     *    � overrideIsStreaming parameter controls this; streaming uses segmentHealed,
     *      final also uses segmentHealed (for consistency, per AGENTS.md fix)
     * 4. Parse tables via MarkdownSegmenter.parseTable()
     * 5. Diff new segments against existing text/code/table parts
     *    � uses lastNonTextEntry anchor-key trick for segment insertion order
     * 6. Rebuild parts map: keep non-text parts, replace text parts with new segments
     * 7. messageMap.update(msgId) { msg -> msg.copy(parts = newParts) }
     * 8. Update textStreamingState.lastSegmentedLen
     * DO NOT attempt to simplify this method during extraction � copy it verbatim.
     */
    fun resegmentDirect(msgId: String, overrideIsStreaming: Boolean = turnLifecycleState.isStreaming) {
        stateLock.withLock {
            // SHORT-CIRCUIT: Skip re-segmentation if revealed text hasn't changed since
            // the last call. This eliminates redundant O(n) markdown parsing on every
            // reveal loop tick (8-24ms) when no new text has been revealed. The reveal
            // loop calls this on every tick, but charBudget may not produce new chars
            // if the backlog is small or the rate is low. Without this guard, a 50k-token
            // response would trigger O(n) segmentation on every 8ms tick regardless of
            // whether new text was revealed.
            //
            // NOTE: This check-then-set pattern is NOT atomic on its own — concurrent
            // callers could both pass the guard. Correctness relies on stateLock
            // (acquired above) serializing all resegmentDirect calls. The @Volatile
            // annotations on revealedLen/lastSegmentedLen are for cross-coroutine
            // visibility of the VALUES, not for lock-free concurrency of this guard.
            if (textStreamingState.revealedLen == textStreamingState.lastSegmentedLen && textStreamingState.revealedLen > 0) {
                return@withLock
            }
            textStreamingState.lastSegmentedLen = textStreamingState.revealedLen

            if (textStreamingState.revealedLen == 0) {
                messageMap.update(msgId) { msg ->
                    val parts = LinkedHashMap(msg.parts)
                    parts.keys.removeIf { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }
                    msg.copy(parts = parts)
                }
                return@withLock
            }

            // Ensure at least one segment exists
            if (textStreamingState.textSegments.isEmpty()) {
                textStreamingState.textSegments.add(TextSegment(0, null))
            }

            // Set first segment's anchor if not yet set � the last non-text part
            // that existed when text first arrived.
            if (textStreamingState.textSegments[0].anchorKey == null) {
                messageMap.update(msgId) { msg ->
                    val lastNonTextEntry = msg.parts.entries.lastOrNull { entry ->
                        !entry.key.startsWith("text_") && !entry.key.startsWith("code_") &&
                            !entry.key.startsWith("table_") && !entry.key.startsWith("task_")
                    }
                    val anchorKey = lastNonTextEntry?.key
                    if (anchorKey != null) {
                        // Replace the element instead of mutating it in place �
                        // anchorKey is now a val, so we create a new TextSegment.
                        textStreamingState.textSegments[0] = TextSegment(textStreamingState.textSegments[0].startOffset, anchorKey)
                    }
                    msg
                }
            }

            // Snapshot after anchor setup � iteration below uses this snapshot
            // to avoid races with the event processing coroutine that may mutate textStreamingState.textSegments.
            // NOTE: This snapshot is taken AFTER the anchor-setup updateMessage call above,
            // so it reflects the anchor key replacement. No fix needed �
            // segmentsSnapshot is taken after anchor setup; no fix needed. (False positive.)
            val segmentsSnapshot = textStreamingState.textSegments.toList()

            val raw = textStreamingState.revealBuffer.substring(0, textStreamingState.revealedLen)

            // Segment each text segment independently and collect parts per segment.
            // Each segment covers a slice of textBuffer: [startOffset, nextSegment.startOffset).
            val partsBySegment = mutableListOf<List<Pair<String, MessagePart>>>()
            for (segIdx in segmentsSnapshot.indices) {
                val start = minOf(segmentsSnapshot[segIdx].startOffset, textStreamingState.revealedLen)
                val end = if (segIdx + 1 < segmentsSnapshot.size) minOf(segmentsSnapshot[segIdx + 1].startOffset, textStreamingState.revealedLen) else textStreamingState.revealedLen
                if (start >= end) {
                    partsBySegment.add(emptyList())
                    continue
                }
                val segmentText = raw.substring(start, end)
                val segments = if (overrideIsStreaming) {
                    MarkdownSegmenter.segmentHealed(segmentText)
                } else {
                    MarkdownSegmenter.segment(segmentText)
                }
                val segParts = mutableListOf<Pair<String, MessagePart>>()
                segments.forEachIndexed { i, segment ->
                    when (segment.type) {
                        MarkdownSegment.Type.TEXT -> {
                            if (segment.content.isNotBlank()) segParts.add("text_${segIdx}_$i" to MessagePart.Text(segment.content))
                        }
                        MarkdownSegment.Type.CODE -> {
                            if (segment.content.isNotBlank()) segParts.add("code_${segIdx}_$i" to MessagePart.Code(segment.language ?: "", segment.content))
                        }
                        MarkdownSegment.Type.TABLE -> {
                            val parsed = MarkdownSegmenter.parseTable(segment.content.lines())
                            if (parsed != null) {
                                segParts.add("table_${segIdx}_$i" to MessagePart.Table(
                                    rawMarkdown = segment.content,
                                    headers = parsed.header,
                                    rows = parsed.rows,
                                    alignments = parsed.alignments
                                ))
                            } else {
                                segParts.add("text_${segIdx}_$i" to MessagePart.Text(segment.content))
                            }
                        }
                        MarkdownSegment.Type.TASK -> {
                            val state = segment.taskAttrs?.get("state") ?: "completed"
                            val status = when (state) {
                                "completed" -> ToolCallStatus.COMPLETED
                                "failed" -> ToolCallStatus.FAILED
                                else -> ToolCallStatus.IN_PROGRESS
                            }
                            val agentId = segment.taskAttrs?.get("id") ?: ""
                            val output = listOf(kotlinx.serialization.json.JsonObject(
                                mapOf("text" to kotlinx.serialization.json.JsonPrimitive(segment.content))
                            ))
                            val pill = ToolCallPill(
                                toolCallId = "task_$agentId",
                                toolName = "task",
                                title = "task",
                                kind = com.agentclientprotocol.model.ToolKind.OTHER,
                                status = status,
                                output = output,
                            )
                            segParts.add("task_${segIdx}_$i" to MessagePart.ToolCall(
                                pill = pill,
                                state = if (status == ToolCallStatus.COMPLETED) PartState.Completed else PartState.InProgress
                            ))
                        }
                    }
                }
                partsBySegment.add(segParts)
            }

            messageMap.update(msgId) { msg ->
                val oldParts = LinkedHashMap(msg.parts)

                // Remove all text-derived parts, preserve non-text parts in order
                val preserved = mutableListOf<Pair<String, MessagePart>>()
                oldParts.entries.forEach { entry ->
                    val isTextDerived = entry.key.startsWith("text_") || entry.key.startsWith("code_") ||
                        entry.key.startsWith("table_") || entry.key.startsWith("task_")
                    if (!isTextDerived) {
                        preserved.add(entry.key to entry.value)
                    }
                }

            val totalNewSegParts = partsBySegment.sumOf { it.size }
            val segFunc = if (overrideIsStreaming) "segmentHealed" else "segment"
            logger.debug { "[ACP] resegment: msg=$msgId oldParts=${oldParts.size} preserved=${preserved.size} newSegParts=$totalNewSegParts revealedLen=${textStreamingState.revealedLen} segments=${segmentsSnapshot.size} func=$segFunc" }

                // Rebuild map: insert each segment's parts after its anchor
                val newMap = linkedMapOf<String, MessagePart>()
                var nextSegToInsert = 0

                // Insert segments with null anchor at the beginning
                while (nextSegToInsert < segmentsSnapshot.size && segmentsSnapshot[nextSegToInsert].anchorKey == null) {
                    partsBySegment[nextSegToInsert].forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                    nextSegToInsert++
                }

                // Iterate preserved parts, inserting segment parts after their anchors
                preserved.forEach { (key, part) ->
                    newMap[key] = part
                    while (nextSegToInsert < segmentsSnapshot.size && segmentsSnapshot[nextSegToInsert].anchorKey == key) {
                        partsBySegment[nextSegToInsert].forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                        nextSegToInsert++
                    }
                }

                // Insert remaining segments (anchors were removed, e.g., compaction)
                while (nextSegToInsert < segmentsSnapshot.size) {
                    partsBySegment[nextSegToInsert].forEach { (tKey, tPart) -> newMap[tKey] = tPart }
                    nextSegToInsert++
                }

                if (newMap.isEmpty() && oldParts.isNotEmpty()) {
                    logger.warn { "[ACP] resegment: EMPTY RESULT! msg=$msgId oldParts=${oldParts.size} preserved=${preserved.size} newSegParts=$totalNewSegParts revealedLen=${textStreamingState.revealedLen} textBufferLen=${textStreamingState.textBuffer.length}" }
                }

                // Detect key changes � if text-derived keys changed, Compose disposes+recreates
                val oldTextKeys = oldParts.keys.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }.toSet()
                val newTextKeys = newMap.keys.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }.toSet()
                val keysRemoved = oldTextKeys - newTextKeys
                val keysAdded = newTextKeys - oldTextKeys
                if (keysRemoved.isNotEmpty() || keysAdded.isNotEmpty()) {
                    logger.warn { "[ACP] resegment KEYS CHANGED: msg=$msgId func=$segFunc removed=$keysRemoved added=$keysAdded" }
                }

                msg.copy(parts = newMap)
            }
        }
    }

    /**
     * Final re-segmentation (always uses segmentHealed).
     * Use segmentHealed (same as streaming) to avoid changing part keys on
     * finalization. Switching from segmentHealed?segment can produce different
     * segment counts, changing keys like text_0_0?text_0_0+text_0_1, which
     * causes Compose to dispose+recreate every visible part � the "jump" flicker.
     * For complete content, segmentHealed and segment produce identical results;
     * for any edge case, consistency is more important than the non-healed path.
     */
    fun resegmentFinal(msgId: String) = stateLock.withLock {
        resegmentDirect(msgId, overrideIsStreaming = true)
    }

    /**
     * Cancel the resegment job (called on createAssistantMessage / close).
     * Returns the cancelled job (if any) for callers that need to wait.
     */
    fun cancelResegmentJob(): Job? {
        resegmentJob?.cancel()
        val job = resegmentJob
        resegmentJob = null
        return job
    }

    /**
     * Cancel BOTH the text reveal job and the thinking reveal job.
     * Called by [SessionState.close] to ensure no reveal coroutine outlives the session.
     * This fixes the pre-existing bug where only [textStreamingState.revealJob] was cancelled
     * in close(), leaking [textStreamingState.thinkingRevealJob].
     */
    fun cancelRevealJobs() {
        textStreamingState.revealJob?.cancel()
        textStreamingState.revealJob = null
        textStreamingState.thinkingRevealJob?.cancel()
        textStreamingState.thinkingRevealJob = null
    }

    companion object {
        private const val DEBOUNCE_MS = 50L
    }
}