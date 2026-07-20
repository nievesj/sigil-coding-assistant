package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.util.generateId
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the HIGH-LEVEL streaming message lifecycle: create, adopt, complete,
 * abort, server-ID sync, and last-user-text tracking.
 *
 * This is the orchestration layer ABOVE [MessageMapManager]. Where
 * [MessageMapManager] handles LOW-LEVEL CRUD on the message `LinkedHashMap`
 * (add/remove/replace/update + FIFO eviction + tool-index cleanup),
 * [MessageLifecycleManager] handles turn-level lifecycle concerns:
 * - Creating a new assistant message and establishing the streaming context
 *   (ResetTurn control event, pendingTurnIdentity fallback)
 * - Adopting a REST-loaded streaming message as the active turn
 * - Completing (finalizing) the active streaming message
 * - Aborting streaming with an error reason
 * - Syncing the server message ID onto the active turn
 * - Tracking the last user text for echo-stripping
 *
 * Thread safety: acquires [stateLock] for all public operations.
 * [ReentrantLock] allows nested acquisition from the same thread (e.g.,
 * [completeStreaming] calls [MessageMapManager.update] which also acquires
 * the lock).
 *
 * @param sessionId The session ID — used in ResetTurn events and UiSignal.Error.
 * @param stateLock Shared reentrant lock — same instance as SessionState's.
 * @param turnLifecycleState Turn lifecycle state — read/written for activeMessageId,
 *        activeServerMessageId, modelID, providerID, isStreaming, lastUserText, errorMessage.
 * @param messageMap Message cache manager — used for add and update operations.
 * @param textStreaming Text streaming manager — called for cancelResegmentJob,
 *        freezeThinking, flushReveal, resegmentFinal during complete/abort.
 * @param streamingLifecycle Streaming lifecycle manager — called for
 *        emitStreamingCompleted during complete/abort.
 * @param signals Signal flow — for emitting UiSignal.Error on abort.
 * @param messages The message cache StateFlow — read for debug logging in
 *        completeStreaming (key-change detection).
 * @param eventChannel SSE event channel — used by createAssistantMessage and
 *        adoptStreamingContext to send ResetTurn control events.
 * @param resetTurnState Callback to SessionState.resetTurnState() — resets
 *        textStreamingState, toolCallState, and turnLifecycleState for a new turn.
 *        Called ONLY from the event processing coroutine path (fromEventProcessing=true).
 * @param firstTextSegmentedSet Callback to set SessionState.firstTextSegmented —
 *        reset to false on create/adopt.
 * @param lastAccessTimeSet Callback to set SessionState.lastAccessTime —
 *        updated on create/adopt for LRU tracking.
 * @param logger Shared logger instance.
 */
internal class MessageLifecycleManager(
    private val sessionId: String,
    private val stateLock: ReentrantLock,
    private val turnLifecycleState: TurnLifecycleState,
    private val messageMap: MessageMapManager,
    private val textStreaming: TextStreamingManager,
    private val streamingLifecycle: StreamingLifecycleManager,
    private val signals: MutableSharedFlow<UiSignal>,
    private val messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>,
    private val eventChannel: Channel<SseEvent>,
    private val resetTurnState: () -> Unit,
    private val firstTextSegmentedSet: (Boolean) -> Unit,
    private val lastAccessTimeSet: (Long) -> Unit,
    private val logger: KLogger,
) {
    /**
     * Pending turn identity stored when createAssistantMessage's ResetTurn trySend
     * fails (event channel full). The event processing coroutine checks this at the
     * start of processEventInternal and applies it if set, closing the window where
     * activeMessageId would be null and a duplicate auto-create could fire.
     * @Volatile because written by createAssistantMessage (caller's coroutine) and
     * read/cleared by the event processing coroutine.
     */
    @Volatile
    var pendingTurnIdentity: PendingTurnIdentity? = null

    /**
     * Carries the new turn's identity when the ResetTurn control event is dropped
     * due to a full event channel. Applied at the start of processEventInternal.
     */
    internal data class PendingTurnIdentity(
        val messageId: String,
        val serverMessageId: String?,
        val modelID: String?,
        val providerID: String?,
    )

    /**
     * Create a new assistant message and establish it as the active streaming turn.
     *
     * Called from two paths:
     * 1. OpenCodeService.sendMessageInternal() (caller's coroutine, holds sendMutex)
     * 2. processEventInternal() auto-create (event processing coroutine, activeMessageId == null)
     *
     * Thread-safety: ctx field mutations must run on the event processing
     * coroutine (single-writer). External callers send a ResetTurn control
     * event through eventChannel; the auto-create path (fromEventProcessing=true)
     * resets directly since it's already on the event processing coroutine.
     *
     * @param modelID The model ID for this turn (may be null).
     * @param providerID The provider ID for this turn (may be null).
     * @param serverMessageId The server-assigned message ID (if known from REST).
     *        When null, a local ID is generated via [generateId].
     * @param fromEventProcessing When true, [resetTurnState] is called directly
     *        and ctx fields are set immediately (caller is the event processing
     *        coroutine). When false, a [SseEvent.ResetTurn] control event is sent
     *        through [eventChannel] so the reset runs on the event processing
     *        coroutine — eliminating the cross-coroutine race on ctx fields.
     * @return The local message ID of the created assistant message.
     */
    fun createAssistantMessage(
        modelID: String?,
        providerID: String?,
        serverMessageId: String? = null,
        fromEventProcessing: Boolean = false
    ): String = stateLock.withLock {
        val id = serverMessageId ?: generateId()

        if (fromEventProcessing) {
            // Already on the event processing coroutine — reset and set ctx directly.
            resetTurnState()
            turnLifecycleState.activeMessageId = id
            turnLifecycleState.activeServerMessageId = serverMessageId
            turnLifecycleState.modelID = modelID
            turnLifecycleState.providerID = providerID
            turnLifecycleState.isStreaming = true
        } else {
            // Send ResetTurn carrying the new turn's identity. The event-processing
            // coroutine will drain stale events, resetTurnState(), then apply these
            // fields atomically — eliminating the window where activeMessageId is null
            // (which previously caused a duplicate auto-create when the first SSE
            // content event arrived).
            //
            // Stale-event draining is done in the ResetTurn handler (consumer side)
            // rather than here (producer side) to close the window where an SSE event
            // could be enqueued between the drain completing and ResetTurn being sent.
            // The single-reader event coroutine owns both the drain and the reset.
            //
            // We do NOT set ctx fields here; the event coroutine owns them.
            val resetEvent = SseEvent.ResetTurn(
                sessionId = sessionId,
                newTurnMessageId = id,
                newTurnServerMessageId = serverMessageId,
                newTurnModelID = modelID,
                newTurnProviderID = providerID,
            )
            val sendResult = eventChannel.trySend(resetEvent)
            if (sendResult.isFailure) {
                // Channel is full (1024 capacity) or closed. Do NOT drain the channel
                // and do NOT write ctx fields directly — that would race with the event
                // processing coroutine, which mutates ctx fields WITHOUT acquiring
                // stateLock (it relies on single-writer serialization via the channel).
                //
                // Instead, drop the ResetTurn. The event processing coroutine's
                // auto-create logic (the `needsNewMessage` check in processEventInternal)
                // will create the assistant message when the first content-bearing SSE
                // event arrives, using the serverMessageId from that event. This is the
                // same path used for child/subagent sessions and is well-tested.
                //
                // RACE WINDOW: The message is added to the map below with isStreaming=true, but
                // activeMessageId is NOT set (the ResetTurn event was dropped). If the first SSE
                // content event arrives before the event processing coroutine checks pendingTurnIdentity,
                // the auto-create logic will create ANOTHER assistant message. The pendingTurnIdentity
                // check at the start of processEventInternal closes this window for events that arrive
                // after the check. The window between messageMap.add() and the pendingTurnIdentity check
                // is bounded by the event processing coroutine's next iteration (microseconds).
                //
                // PENDING-TURN FALLBACK: Store the turn identity so the event processing
                // coroutine can apply it at the start of processEventInternal. This closes
                // the window where activeMessageId is null and a duplicate auto-create
                // could fire when the first content-bearing SSE event arrives.
                pendingTurnIdentity = PendingTurnIdentity(id, serverMessageId, modelID, providerID)
                logger.warn { "[ACP] createAssistantMessage: eventChannel FULL — ResetTurn dropped, pendingTurnIdentity stored + auto-create will handle it. id=$id" }
            }
        }

        textStreaming.cancelResegmentJob()

        firstTextSegmentedSet(false)
        lastAccessTimeSet(System.currentTimeMillis())
        logger.info { "[ACP] createAssistantMessage: id=$id, serverMessageId=$serverMessageId, fromEventProcessing=$fromEventProcessing" }

        val message = ChatMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = linkedMapOf(),
            isStreaming = true,
            state = MessageState.Created,
            timestamp = System.currentTimeMillis(),
            modelID = modelID,
            providerID = providerID,
            serverMessageId = serverMessageId,
        )
        messageMap.add(message)
        id
    }

    /**
     * Adopt a streaming assistant message that was loaded from REST (e.g., child session
     * cached via ensureSessionCached while still streaming). Sets turnLifecycleState.activeMessageId so
     * subsequent SSE events (TextChunk, ToolUse, etc.) are routed to this message.
     * Does NOT create a new message — the message already exists in the map from REST fetch.
     */
    fun adoptStreamingContext(messageId: String, modelID: String?, providerID: String?) = stateLock.withLock {
        // Send through the event channel to maintain single-writer invariant.
        // The event processing coroutine owns ctx field mutations — writing
        // directly here would race with it (the reader doesn't hold stateLock).
        val resetEvent = SseEvent.ResetTurn(
            sessionId = sessionId,
            newTurnMessageId = messageId,
            newTurnServerMessageId = null,
            newTurnModelID = modelID,
            newTurnProviderID = providerID,
        )
        val sendResult = eventChannel.trySend(resetEvent)
        if (sendResult.isFailure) {
            // Channel full — store as pending turn identity (same fallback as createAssistantMessage)
            pendingTurnIdentity = PendingTurnIdentity(messageId, null, modelID, providerID)
            logger.warn { "[ACP] adoptStreamingContext: eventChannel FULL — ResetTurn dropped, pendingTurnIdentity stored" }
        }
        firstTextSegmentedSet(false)
        textStreaming.cancelResegmentJob()
        lastAccessTimeSet(System.currentTimeMillis())
        logger.info { "[ACP] adoptStreamingContext: id=$messageId, modelID=$modelID, providerID=$providerID (sent via ResetTurn)" }
    }

    /**
     * Complete (finalize) the active streaming message.
     *
     * BY DESIGN: only finalizes the ACTIVE streaming message. Old messages from
     * previous turns are already finalized via finalizeStreaming (triggered by
     * Stop/MessageFinalized/SessionIdle events). If messageId doesn't match
     * turnLifecycleState.activeMessageId, this is a stale call for an already-
     * finalized message — skip it.
     */
    fun completeStreaming(messageId: String) = stateLock.withLock {
        if (messageId != turnLifecycleState.activeMessageId) {
            logger.warn { "[ACP] completeStreaming: SKIP messageId=$messageId != activeMessageId=${turnLifecycleState.activeMessageId}" }
            return@withLock
        }
        val textKeysBefore = if (logger.isDebugEnabled()) {
            messages.value[messageId]?.parts?.keys?.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }?.toSet()
        } else null
        if (logger.isDebugEnabled()) {
            logger.debug { "[ACP] completeStreaming: START msg=$messageId partsBefore=${messages.value[messageId]?.parts?.size} textKeys=$textKeysBefore" }
        }
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        textStreaming.resegmentFinal(messageId)
        turnLifecycleState.isStreaming = false
        messageMap.update(messageId) { it.copy(isStreaming = false, state = MessageState.Completed) }
        if (logger.isDebugEnabled()) {
            val textKeysAfter = messages.value[messageId]?.parts?.keys?.filter { it.startsWith("text_") || it.startsWith("code_") || it.startsWith("table_") || it.startsWith("task_") }?.toSet()
            logger.debug { "[ACP] completeStreaming: END msg=$messageId partsAfter=${messages.value[messageId]?.parts?.size} textKeys=$textKeysAfter" }
            if (textKeysBefore != null && textKeysAfter != null && textKeysBefore != textKeysAfter) {
                logger.warn { "[ACP] completeStreaming KEYS CHANGED: msg=$messageId before=$textKeysBefore after=$textKeysAfter removed=${textKeysBefore - textKeysAfter} added=${textKeysAfter - textKeysBefore}" }
            }
        }
        streamingLifecycle.emitStreamingCompleted(messageId)
    }

    /**
     * Abort the active streaming message with an error reason.
     * Freezes thinking/reveal, marks the message as Aborted, emits UiSignal.Error
     * and StreamingCompleted.
     */
    fun abortStreaming(reason: String) = stateLock.withLock {
        val msgId = turnLifecycleState.activeMessageId ?: return@withLock
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        turnLifecycleState.errorMessage = reason
        turnLifecycleState.isStreaming = false
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(reason)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Aborted)
        }
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion = false)
        signals.tryEmit(UiSignal.Error(msgId, reason))
        abortTurnAndReset(msgId, reason)
    }

    /**
     * Abort streaming with a fallback message ID for the case where
     * [turnLifecycleState.activeMessageId] is null because the ResetTurn control event
     * from [createAssistantMessage] hasn't been processed yet.
     *
     * This happens when sendMessageAsync throws quickly (e.g., server rejects an
     * attachment) before the event processing coroutine handles the ResetTurn.
     * Without this fallback, the assistant message created by createAssistantMessage
     * would be stuck in isStreaming=true, state=Created, parts=empty forever —
     * a "ghost message" that's invisible (filtered by MessageList) but never finalized.
     *
     * The fallback finalizes the message directly by its ID, bypassing the
     * activeMessageId lookup. It also sets turnLifecycleState fields so the next
     * ResetTurn (from the next send) doesn't find stale isStreaming=true state.
     */
    fun abortStreamingWithFallback(reason: String, fallbackMessageId: String) = stateLock.withLock {
        // If activeMessageId is already set (ResetTurn was processed), use the normal path.
        val msgId = turnLifecycleState.activeMessageId ?: fallbackMessageId
        textStreaming.freezeThinking()
        textStreaming.flushReveal()
        turnLifecycleState.errorMessage = reason
        turnLifecycleState.isStreaming = false
        messageMap.update(msgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            parts["error"] = MessagePart.Error(reason)
            msg.copy(parts = parts, isStreaming = false, state = MessageState.Aborted)
        }
        streamingLifecycle.emitStreamingCompleted(msgId, naturalCompletion = false)
        signals.tryEmit(UiSignal.Error(msgId, reason))
        abortTurnAndReset(msgId, reason)
    }

    /**
     * Targeted clear of turn identity after an abort/error. Called by [abortStreaming]
     * and [abortStreamingWithFallback] AFTER [emitStreamingCompleted] and [UiSignal.Error]
     * emission (ordering matters — [emitStreamingCompleted] reads [toolCallState.pendingFileChanges]).
     *
     * Performs a TARGETED clear — NOT a full [resetTurnState]:
     * - Clears [TurnLifecycleState.activeMessageId], [TurnLifecycleState.activeServerMessageId],
     *   [TurnLifecycleState.isStreaming] (already set by callers, set again for clarity).
     * - Sets [TurnLifecycleState.isAborted] = true so [SseEventPipeline] drops stale events
     *   that arrive after abort but before the next [SseEvent.ResetTurn] clears it.
     * - Cancels [TurnLifecycleState.pendingStopJob].
     *
     * Does NOT clear: [errorMessage] (UI may read it), [modelID]/[providerID] (next turn
     * overwrites), [streamingStartedEmitted]/[streamingCompletedEmitted] (idempotency guards
     * — clearing them risks double-emission). Does NOT clear [toolCallState] or
     * [textStreamingState] — those are owned by their own managers and the [isAborted]
     * flag in [SseEventPipeline] prevents new events from mutating them. They'll be
     * cleared by [resetTurnState] on the next [createAssistantMessage].
     *
     * Caller MUST already hold [stateLock].
     */
    private fun abortTurnAndReset(msgId: String, reason: String) {
        turnLifecycleState.activeMessageId = null
        turnLifecycleState.activeServerMessageId = null
        turnLifecycleState.isStreaming = false
        turnLifecycleState.isAborted = true
        turnLifecycleState.pendingStopJob?.cancel()
        turnLifecycleState.pendingStopJob = null
        logger.info { "[ACP] abortTurnAndReset: msg=$msgId reason=$reason — cleared activeMessageId/activeServerMessageId, set isAborted=true" }
    }

    /**
     * Update the server message ID on a message and, if the turn context still
     * refers to this message, on turnLifecycleState.activeServerMessageId.
     *
     * Always updates the message's serverMessageId field — this is a message-level
     * operation that doesn't depend on ctx (which may be set asynchronously by
     * ResetTurn on the event-processing coroutine).
     *
     * Only updates turnLifecycleState.activeServerMessageId if ctx still refers to
     * this message. If ResetTurn hasn't been processed yet, turnLifecycleState.activeMessageId
     * may be null or point to a previous turn — in that case, the ResetTurn event
     * carries newTurnServerMessageId=null, and the first SSE event will set it.
     */
    fun updateServerMessageId(messageId: String, serverMessageId: String) = stateLock.withLock {
        messageMap.update(messageId) { it.copy(serverMessageId = serverMessageId) }

        if (messageId == turnLifecycleState.activeMessageId) {
            if (turnLifecycleState.activeServerMessageId == null) {
                turnLifecycleState.activeServerMessageId = serverMessageId
                logger.info { "[ACP] updateServerMessageId: msg=$messageId → serverId=$serverMessageId" }
            } else if (turnLifecycleState.activeServerMessageId != serverMessageId) {
                logger.warn { "[ACP] updateServerMessageId: server ID mismatch — HTTP=$serverMessageId, SSE=${turnLifecycleState.activeServerMessageId}. Keeping SSE value." }
            }
        } else {
            // ctx not yet synced — but still set activeServerMessageId if null so the
            // routing check can filter stale events. This closes the window between
            // createAssistantMessage (sends ResetTurn) and ResetTurn being processed.
            // If activeServerMessageId is non-null AND differs from the new serverMessageId,
            // it is a stale value from a previous turn (ResetTurn hasn't cleared it yet).
            // Overwrite it with the new turn's serverId — otherwise the server-ID routing
            // check in SseEventPipeline would skip events for the new turn (comparing the
           // new event's serverId against the stale activeServerMessageId).
           //
           // ASSUMPTION: This overwrite assumes single-send-in-flight for the parent
           // session (sendMutex serializes sends). A child-session auto-create racing
           // with a parent send could theoretically cause two updateServerMessageId
           // calls to race in this branch, with the second overwriting the first's
           // activeServerMessageId. Impact is low: the auto-create path sets
           // activeMessageId directly (fromEventProcessing=true), so the
           // `messageId == activeMessageId` branch above is taken instead of this
           // one. This branch only fires during the narrow window between
           // createAssistantMessage (sends ResetTurn) and ResetTurn being processed,
           // and only one send should be in flight during that window.
           if (turnLifecycleState.activeServerMessageId == null) {
                turnLifecycleState.activeServerMessageId = serverMessageId
                logger.info { "[ACP] updateServerMessageId: ctx not yet synced but setting activeServerMessageId=$serverMessageId (msg=$messageId, activeMessageId=${turnLifecycleState.activeMessageId})" }
            } else if (turnLifecycleState.activeServerMessageId != serverMessageId) {
                logger.warn { "[ACP] updateServerMessageId: overwriting stale activeServerMessageId=${turnLifecycleState.activeServerMessageId} with $serverMessageId (msg=$messageId, activeMessageId=${turnLifecycleState.activeMessageId}) — ResetTurn hasn't cleared the previous turn's serverId yet" }
                turnLifecycleState.activeServerMessageId = serverMessageId
            } else {
                logger.info { "[ACP] updateServerMessageId: ctx not yet synced (msg=$messageId, activeMessageId=${turnLifecycleState.activeMessageId}) — message field updated, ctx will be set by ResetTurn or first SSE event" }
            }
        }
    }

    /**
     * Set the last user text for echo-stripping during text streaming.
     * The text streaming handlers use this to strip the server's echo of the
     * user's input from the first TextChunk/TextReplace.
     */
    fun setLastUserText(text: String?) = stateLock.withLock {
        turnLifecycleState.lastUserText = text
    }
}