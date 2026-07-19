package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageState
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages streaming finalization: [finalizeStreaming] (4 stop-reason branches),
 * [emitStreamingStartedIfNeeded], and [emitStreamingCompleted].
 *
 * Key design: [responseDeferred] stays on SessionState (it's written by OpenCodeService).
 * This manager receives a [completeResponseDeferred] callback for the `new_message` branch,
 * which completes the deferred WITHOUT emitting `StreamingCompleted` (to prevent the new
 * message's phase from flickering to IDLE). This avoids a circular dependency � the manager
 * doesn't hold a SessionState reference.
 *
 * @param ctx Shared processor context.
 * @param stateLock Shared reentrant lock.
 * @param scope Coroutine scope � for launching the debounced finalization job.
 * @param messageMap Message cache manager � used to update message parts during finalization.
 * @param textStreaming Text streaming manager � called for freeze/flush/resegment.
 * @param signals Signal flow � for emitting StreamingStarted/StreamingCompleted.
 * @param completeResponseDeferred Callback to SessionState: completes responseDeferred
 *        without emitting StreamingCompleted (used by the new_message branch).
 * @param logger Shared logger instance.
 */
internal class StreamingLifecycleManager(
    private val turnLifecycleState: TurnLifecycleState,
    private val toolCallState: ToolCallState,
    private val stateLock: ReentrantLock,
    private val scope: CoroutineScope,
    private val messageMap: MessageMapManager,
    private val textStreaming: TextStreamingManager,
    private val signals: MutableSharedFlow<UiSignal>,
    private val completeResponseDeferred: () -> Unit,
    private val logger: KLogger,
) {
    /**
     * Shared streaming finalization logic � called by both SseEvent.Stop and
     * SseEvent.MessageFinalized handlers. Handles freeze, resegment, tool-calls
     * filter, and debounced completion.
     *
     * DEBOUNCE SCOPE: The 300ms debounce applies ONLY to non-"tool-calls",
     * non-"idle" stop reasons. "tool-calls" is an intermediate stop (message
     * continues streaming for the next tool cycle). "idle" is a terminal server
     * signal that finalizes immediately (no debounce � debouncing would create a
     * race window where a late metadata event could cancel the finalization).
     *
     * DEBOUNCE RACE NOTE: The 300ms debounce is intentional. If a new event arrives
     * before the debounce completes, processEventInternal() cancels pendingStopJob,
     * preventing premature finalization. If the debounce completes and acquires
     * stateLock before a new event can cancel it, finalization proceeds � the
     * subsequent event processes against the finalized message, which is correct
     * (the Stop/MessageFinalized event was the last content-bearing event). Under
     * heavy load (e.g., resegmentDirect holding stateLock during markdown parsing),
     * the debounce job's lock acquisition is delayed, but this only extends the
     * debounce window � it does not cause incorrect behavior.
     */
    fun finalizeStreaming(msgId: String, stopReason: String) {
        if (!turnLifecycleState.isStreaming) {
            logger.info { "[ACP] finalizeStreaming SKIP: not streaming (reason=$stopReason)" }
            return
        }

        // NOTE: This function must ONLY be called from the event processing coroutine
        // (the single writer for ctx fields). The initial isStreaming check and the
        // freezeThinking/flushReveal calls run WITHOUT stateLock because they mutate
        // ctx fields that are owned by this coroutine. The lock is acquired later
        // inside resegmentDirect and in the debounced/idle/new_message branches.
        // Adding a concurrent writer would break this invariant.
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        logger.debug { "[ACP] finalizeStreaming: BEFORE resegment msg=$msgId reason=$stopReason isStreaming=${turnLifecycleState.isStreaming}" }
        textStreaming.resegmentDirect(msgId)
        logger.debug { "[ACP] finalizeStreaming: AFTER resegment msg=$msgId reason=$stopReason" }

        if (stopReason == "tool-calls") {
            // Intermediate stop � tool calls starting; keep message streaming, don't mark completed
            logger.info { "[ACP] finalizeStreaming (tool-calls): intermediate � continuing stream, token data applied" }
        } else if (stopReason == "idle") {
            // Server explicitly declared session idle � finalize immediately without
            // the 300ms debounce. The debounce exists to absorb late content events
            // after a normal Stop, but SessionIdle is a terminal server signal.
            // Debouncing here only creates a race window where a late metadata event
            // (e.g., MessageFinalized with stopReason=null) can cancel the finalization
            // and leave isStreaming=true forever.
            logger.info { "[ACP] finalizeStreaming (idle): immediate finalization (no debounce)" }
            stateLock.withLock {
                // Guard against the new-message race: if a new turn started (activeMessageId changed),
                // don't finalize the old message — it would kill the new turn's isStreaming flag.
                if (turnLifecycleState.activeMessageId != null && turnLifecycleState.activeMessageId != msgId) {
                    logger.info { "[ACP] finalizeStreaming (idle): SKIP — activeMessageId changed from $msgId to ${turnLifecycleState.activeMessageId}" }
                    return@withLock
                }
                if (!turnLifecycleState.isStreaming) return@withLock
                turnLifecycleState.isStreaming = false
                messageMap.update(msgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                emitStreamingCompleted(msgId)
            }
        } else if (stopReason == "new_message") {
            // A new message is starting � finalize the old one immediately.
            // No debounce: the new message's content events are already arriving,
            // so there's nothing to wait for. The 300ms debounce would delay the
            // isStreaming=true?false transition on the old message, causing a mass
            // LazyColumn dispose+recreate when it finally fires (the flicker bug).
            //
            // Do NOT emit StreamingCompleted here � createAssistantMessage already
            // reset streamingCompletedEmitted via resetTurnState(), so the new
            // message's completion will emit it. Emitting here would prematurely
            // set _streamPhase=IDLE while the new message is actively streaming.
            logger.info { "[ACP] finalizeStreaming (new_message): immediate finalization (no debounce, no StreamingCompleted)" }
            stateLock.withLock {
                // Guard against the new-message race: if a ResetTurn arrived and
                // activeMessageId changed to a different message, the captured
                // msgId refers to an old message — mutating it would update the
                // WRONG message. The null check is important: if activeMessageId
                // is null (ResetTurn hasn't been processed yet), proceed with the
                // fallback finalization.
                if (turnLifecycleState.activeMessageId != null && turnLifecycleState.activeMessageId != msgId) {
                    logger.info { "[ACP] finalizeStreaming (new_message): SKIP — activeMessageId changed from $msgId to ${turnLifecycleState.activeMessageId}" }
                    return@withLock
                }
                turnLifecycleState.isStreaming = false
                messageMap.update(msgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                // Complete the old turn's responseDeferred so sendMessageInternal unblocks.
                // Don't emit StreamingCompleted — the new message's completion will handle
                // that. The ViewModel's _streamPhase stays STREAMING (set by the new
                // message's StreamingStarted), avoiding a brief IDLE flicker.
                //
                // NOTE: responseDeferred is replaced (not reused) between turns — see
                // OpenCodeService.sendMessageInternal which creates a fresh
                // CompletableDeferred<Unit>() and assigns it to activeSession.responseDeferred
                // on each send. Completing the old deferred here does NOT affect the new
                // turn's deferred (it's a different instance).
                completeResponseDeferred()
            }
        } else {
            val capturedMsgId = msgId
            turnLifecycleState.pendingStopJob?.cancel()
            turnLifecycleState.pendingStopJob = scope.launch {
                delay(300)
                stateLock.withLock {
                    // Guard against the new-message race: if a ResetTurn arrived during
                    // the 300ms debounce window, activeMessageId changed and isStreaming
                    // is now true for the NEW message. Without this check, the debounce
                    // would set isStreaming=false (killing the new message's indicator)
                    // and emit StreamingCompleted for the old message while the new one
                    // is actively streaming.
                    if (capturedMsgId != turnLifecycleState.activeMessageId) {
                        logger.info { "[ACP] finalizeStreaming (debounced): SKIP — activeMessageId changed from $capturedMsgId to ${turnLifecycleState.activeMessageId} during debounce" }
                        return@withLock
                    }
                    if (!turnLifecycleState.isStreaming) return@withLock
                    turnLifecycleState.isStreaming = false
                    messageMap.update(capturedMsgId) { it.copy(isStreaming = false, state = MessageState.Completed) }
                    emitStreamingCompleted(capturedMsgId)
                }
            }
            logger.info { "[ACP] finalizeStreaming (reason=$stopReason): debounced finalization (300ms)" }
        }
    }

    /** Emit StreamingStarted signal once per turn (idempotent via turnLifecycleState.streamingStartedEmitted). */
    fun emitStreamingStartedIfNeeded(msgId: String) {
        if (!turnLifecycleState.streamingStartedEmitted) {
            turnLifecycleState.streamingStartedEmitted = true
            messageMap.update(msgId) { it.copy(state = MessageState.Streaming) }
            signals.tryEmit(UiSignal.StreamingStarted(msgId))
        }
    }

    /** Emit StreamingCompleted signal once per turn (idempotent via turnLifecycleState.streamingCompletedEmitted).
     *  @param naturalCompletion true for natural end (Stop/idle/debounce), false for abort/error/timeout. */
    fun emitStreamingCompleted(msgId: String, naturalCompletion: Boolean = true) {
        if (turnLifecycleState.streamingCompletedEmitted) return
        turnLifecycleState.streamingCompletedEmitted = true
        signals.tryEmit(UiSignal.StreamingCompleted(msgId, toolCallState.pendingFileChanges.toList(), naturalCompletion))
    }
}