package com.opencode.acp.chat.processor

import com.opencode.acp.SseEvent
import com.opencode.acp.chat.model.TodoItem
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * SSE event dispatch pipeline, extracted from SessionState.processEventInternal().
 *
 * Pipeline phases:
 * 1. Pre-dispatch: pending turn identity, non-streaming events, isGenerationEvent check, auto-create, server ID routing
 * 2. Dispatch: route to SessionState handler methods
 *
 * See TDD §4.2.2 — SseEventPipeline. Uses SseEventContext which passes raw mutable
 * SessionState references to handlers (intentional trade-off — handlers are migrated
 * 1:1 from processEventInternal which already accesses this state directly).
 */
class SseEventPipeline(
    private val logger: io.github.oshai.kotlinlogging.KLogger = KotlinLogging.logger {},
) {
    /**
     * Process an SSE event through the pipeline.
     * @param event The parsed SSE event
     * @param ctx The session context (provides access to SessionState internals)
     */
    fun process(event: SseEvent, ctx: SseEventContext) {
        val s = ctx.sessionState
        // Apply a pending turn identity stored when createAssistantMessage's ResetTurn
        // trySend failed (channel full). This closes the window where activeMessageId
        // was null between the dropped ResetTurn and the first content-bearing SSE event,
        // which previously caused a duplicate auto-create. Resetting here (on the event
        // processing coroutine) is single-writer safe for ctx fields — but pendingTurnIdentity
        // itself is written by createAssistantMessage on an external coroutine, so the
        // read-clear must be atomic. We clear AFTER capturing and re-check after clear to
        // handle a concurrent write between capture and clear (the new value would be lost
        // by an unconditional clear). We do NOT drain the channel; stale events are skipped
       // by the server-ID routing check below (same as the ResetTurn handler).
       //
       // RACE WINDOW (not fully closed): The re-check below only catches a concurrent
       // write that happens AFTER the clear. If a concurrent createAssistantMessage
       // writes a NEW pendingTurnIdentity BETWEEN the read above and the clear below,
       // the clear overwrites the NEW value to null, and the re-check reads null. The
       // NEW value is silently LOST, and the OLD value (captured above) is applied.
       // Probability is very low (requires channel-full at 1024 capacity AND a concurrent
       // createAssistantMessage in the microsecond window). The auto-create fallback
       // (needsNewMessage check below) handles the lost identity by creating a new
       // message when the first content-bearing SSE event arrives, so the impact is a
       // potential duplicate assistant message, not data loss. A compare-and-swap loop
       // would close this fully but adds complexity for a very-low-probability race.
       var pending = s.pendingTurnIdentity
        if (pending != null) {
            s.pendingTurnIdentity = null
           // Re-check: a concurrent createAssistantMessage may have set a NEW
           // pendingTurnIdentity between our read above and the clear. If so,
           // apply the newer value (it carries the latest turn identity).
           // NOTE: This only covers writes AFTER the clear. Writes BETWEEN the read
           // and the clear are lost (see RACE WINDOW comment above).
           val newer = s.pendingTurnIdentity
            if (newer != null) {
                s.pendingTurnIdentity = null
                pending = newer
            }
            s.resetTurnState()
            s.turnLifecycleState.activeMessageId = pending.messageId
            if (pending.serverMessageId != null) s.turnLifecycleState.activeServerMessageId = pending.serverMessageId
            s.turnLifecycleState.modelID = pending.modelID
            s.turnLifecycleState.providerID = pending.providerID
            s.turnLifecycleState.isStreaming = true
            logger.info { "[ACP] processEventInternal: applied pendingTurnIdentity msg=${pending.messageId} serverMsg=${pending.serverMessageId}" }
        }

        // Events that don't require an active streaming message
        when (event) {
            // Internal control event — reset ctx for a new streaming turn.
            // Runs on the event processing coroutine, eliminating the cross-coroutine
            // race between external callers (under stateLock) and event processing.
            is SseEvent.ResetTurn -> {
                // Do NOT drain the channel — draining can discard new-turn SSE events that
                // arrived between ResetTurn send and this handler executing (the window includes
                // the network round-trip for sendMessageAsync). Stale events from the previous
                // turn are processed normally but skipped by the server-ID routing check below
                // (activeServerId != null && eventServerId != activeServerId → SKIP), because
                // ctx was reset and the stale events carry the old turn's messageId.
                s.resetTurnState()
                // Apply the new turn's identity atomically after clearing stale state.
                // This closes the window where activeMessageId was null between reset
                // and the first SSE content event (which caused duplicate auto-create).
                event.newTurnMessageId?.let { s.turnLifecycleState.activeMessageId = it }
                // Only set activeServerMessageId if the event carries one — don't
                // overwrite a value that updateServerMessageId may have already set
                // (e.g. if the HTTP response arrived and was processed before ResetTurn).
                event.newTurnServerMessageId?.let { s.turnLifecycleState.activeServerMessageId = it }
                s.turnLifecycleState.modelID = event.newTurnModelID
                s.turnLifecycleState.providerID = event.newTurnProviderID
                if (event.newTurnMessageId != null) {
                    s.turnLifecycleState.isStreaming = true
                }
                return
            }
            is SseEvent.TodoUpdated -> {
                val todos = event.todos.map { todo ->
                    TodoItem(content = todo.content, status = todo.status, priority = todo.priority)
                }
                s.emitSignal(UiSignal.TodoUpdated(todos))
                return
            }
            is SseEvent.QuestionAsked -> {
                s.handleQuestionAsked(event)
                return
            }
            is SseEvent.SessionCreated -> {
                s.emitSignal(UiSignal.SessionCreated(event.sessionId))
                return
            }
            is SseEvent.SessionIdle -> {
                // Backstop: if the server says it's idle but we're still streaming,
                // finalize. This handles the case where message.updated didn't have
                // a finish field or the messageId was missing.
                //
                // TOCTOU RACE (resolved, benign): There's a TOCTOU window between
                // reading turnLifecycleState.isStreaming here and finalizeStreaming acquiring
                // stateLock. A concurrent finalizeStreaming (from a Stop/
                // MessageFinalized event) could finalize first. The race is benign
                // because both paths check `turnLifecycleState.isStreaming` inside stateLock.withLock
                // (finalizeStreaming line ~1563, completeStreaming, etc.), and
                // emitStreamingCompleted has a `streamingCompletedEmitted` guard that
                // prevents double-emission. So at most one finalization succeeds; the
                // other is a no-op. Race is benign — both paths check turnLifecycleState.isStreaming
                // under stateLock; streamingCompletedEmitted prevents double-emission.
                if (s.turnLifecycleState.isStreaming) {
                    val msgId = s.turnLifecycleState.activeMessageId
                    if (msgId != null) {
                        logger.info { "[ACP] SessionIdle backstop: finalizing stuck streaming state" }
                        s.finalizeStreaming(msgId, "idle")
                    }
                }
                return
            }
            is SseEvent.MessageRemoved -> {
                // Remove the message from the local cache by server message ID
                val serverMsgId = event.messageId ?: return
                s.removeMessageByServerId(serverMsgId)
                return
            }
            is SseEvent.UserMessage -> { return }
            is SseEvent.Plan -> { return }
            is SseEvent.MessageComplete -> { return }
            is SseEvent.Ignored -> { return }
            is SseEvent.SessionError -> {
                s.handleSessionError(event)
                return
            }
            is SseEvent.SessionCompacted -> {
                // Handled by SessionManager (refreshes messages via REST + recomputes context).
                // No local state mutation needed in SessionState.
                return
            }
            else -> { /* handled below */ }
        }

        // Cancel pending debounced Stop ONLY for content-bearing events that indicate
        // continued generation. Metadata events (MessageFinalized without stopReason,
        // ToolResult, StepFinish, Snapshot, Compaction, Agent) must NOT cancel the
        // debounce — they can arrive after the final Stop and would otherwise prevent
        // finalization, leaving isStreaming=true forever (the "stuck generation" bug).
        //
        // NOTE: This list is intentionally DIFFERENT from isContentEvent (line 603-609).
        // isContentEvent includes ToolResult/StepFinish/Snapshot/Compaction for auto-create
        // logic. isGenerationEvent excludes them because they don't indicate the LLM is
        // still generating text. If you add a new event type, consider BOTH lists.
        val isGenerationEvent = event is SseEvent.TextChunk
            || event is SseEvent.TextReplace
            || event is SseEvent.ThinkingChunk
            || event is SseEvent.ThinkingReplace
            || event is SseEvent.ToolUse
            || event is SseEvent.Patch
            || event is SseEvent.AssistantFile
            || event is SseEvent.AssistantImage
            || event is SseEvent.Retry
            || event is SseEvent.Subtask
        if (isGenerationEvent) {
            s.turnLifecycleState.pendingStopJob?.cancel()
            s.turnLifecycleState.pendingStopJob = null
        }

        // Auto-create or rotate assistant message for child/subagent sessions.
        // Their SSE events arrive while the parent is streaming, but createAssistantMessage
        // was never called for the child's SessionState. Also handles the case where
        // adoptStreamingContext adopted a completed message but a new message is streaming.
        // Only auto-create for content-bearing events — lifecycle events like
        // MessageFinalized/Stop don't carry content and shouldn't create messages.
        val eventServerId = event.messageId
        val isContentEvent = event is SseEvent.TextChunk || event is SseEvent.TextReplace
            || event is SseEvent.ThinkingChunk || event is SseEvent.ThinkingReplace
            || event is SseEvent.ToolUse || event is SseEvent.ToolResult
            || event is SseEvent.Patch || event is SseEvent.Agent
            || event is SseEvent.StepFinish || event is SseEvent.Compaction
            || event is SseEvent.Snapshot || event is SseEvent.AssistantFile
            || event is SseEvent.AssistantImage
        val needsNewMessage = when {
            // When the turn is aborted, do NOT auto-create a new message — the
            // isAborted guard below will drop the stale event. Without this check,
            // auto-create would call resetTurnState() → reset() which clears
            // isAborted, letting the stale event through and creating a spurious
            // new assistant message.
            s.turnLifecycleState.isAborted -> false
            // ToolResult for a known tool call (in toolCallIndex) belongs to a previous
            // turn's message — don't auto-create a new message. The ToolResult handler
            // routes via toolCallIndex. This prevents a spurious third message when a
            // child task completes after the user steered/sent a follow-up.
            // Also skip if the toolCallId is NOT in the index (evicted) and there's no
            // active message — a stale ToolResult arriving after eviction + finalization
            // should NOT create a spurious new assistant message.
            event is SseEvent.ToolResult && s.toolCallState.toolCallIndex.containsKey(event.toolCallId) -> false
            event is SseEvent.ToolResult && s.turnLifecycleState.activeMessageId == null -> false
            s.turnLifecycleState.activeMessageId == null && isContentEvent -> true // No streaming context at all
            eventServerId != null && eventServerId != s.turnLifecycleState.activeServerMessageId -> {
                // Note: we compare eventServerId (server namespace) against
                // activeServerMessageId (server namespace) — NOT against
                // activeMessageId (local namespace). The activeMessageId check is
                // intentionally omitted because in the normal sendMessageInternal path,
                // activeMessageId is a random generateId() that never matches a server
                // ID. The auto-create path sets activeMessageId = serverMessageId, but
                // that case is covered by the first branch (activeMessageId == null).
                if (s.turnLifecycleState.activeServerMessageId == null) {
                    false
                } else {
                    // A new message is streaming — the event's server messageId differs from
                    // the currently active one. This happens for child/subagent sessions where
                    // adoptStreamingContext adopted a REST-loaded message but the server is now
                    // streaming a different message.
                    logger.info { "[ACP] New message detected for session ${s.sessionId}: eventServerId=$eventServerId vs activeMsgId=${s.turnLifecycleState.activeMessageId}/activeServerId=${s.turnLifecycleState.activeServerMessageId}" }
                    // Finalize the current streaming message before starting a new one
                    val currentId = s.turnLifecycleState.activeMessageId
                    if (currentId != null && s.turnLifecycleState.isStreaming) {
                        s.finalizeStreaming(currentId, "new_message")
                    }
                    true
                }
            }
            else -> false
        }
        if (needsNewMessage) {
            logger.info { "[ACP] Auto-creating assistant message for session ${s.sessionId} (triggered by ${event::class.simpleName}, serverMsgId=$eventServerId)" }
            s.createAssistantMessage(modelID = null, providerID = null, serverMessageId = eventServerId, fromEventProcessing = true)
        }

        val msgId = s.turnLifecycleState.activeMessageId
        if (msgId == null) {
            logger.info { "[ACP] processEvent DROP: ${event::class.simpleName} — activeMessageId is null even after auto-create" }
            return
        }

        // Server ID routing check
        val activeServerId = s.turnLifecycleState.activeServerMessageId
        val isCrossMessageEvent = event is SseEvent.ToolResult || event is SseEvent.Permission
        logger.info { "[ACP] processEvent ROUTE: ${event::class.simpleName} activeMsgId=$msgId activeServerId=$activeServerId eventServerId=$eventServerId isCross=$isCrossMessageEvent" }
        if (!isCrossMessageEvent && activeServerId != null && eventServerId != null && eventServerId != activeServerId) {
            logger.info { "[ACP] processEvent SKIP: event messageId=$eventServerId != active=$activeServerId" }
            return
        }

        // Abort guard: when the turn is aborted, drop all non-cross-message events.
        // This catches V1 tool events with eventServerId=null (which the routing check above
        // cannot filter because it requires eventServerId != null). Cross-message events
        // (ToolResult, Permission) are still allowed through so tool results for the aborted
        // turn can complete their pills.
        if (s.turnLifecycleState.isAborted && !isCrossMessageEvent) {
            logger.info { "[ACP] processEvent SKIP (aborted): ${event::class.simpleName} — turn is aborted, dropping stale event eventServerId=$eventServerId" }
            return
        }

        when (event) {
            // ── Thinking ──────────────────────────────────────────────────
            is SseEvent.ThinkingChunk -> s.handleThinkingChunk(event, msgId)
            is SseEvent.ThinkingReplace -> s.handleThinkingReplace(event, msgId)

            // ── Text ──────────────────────────────────────────────────────
            is SseEvent.TextChunk -> s.handleTextChunk(event, msgId)
            is SseEvent.TextReplace -> s.handleTextReplace(event, msgId)

            // ── Tool calls ───────────────────────────────────────────────
            is SseEvent.ToolUse -> s.handleToolUse(event, msgId)
            is SseEvent.ToolResult -> s.handleToolResult(event, msgId)
            is SseEvent.Permission -> s.handlePermission(event, msgId)
            is SseEvent.PermissionReplied -> s.handlePermissionReplied(event)

            // ── Stop ─────────────────────────────────────────────────────
            is SseEvent.Stop -> s.handleStop(event, msgId)

            // ── MessageFinalized ─────────────────────────────────────────
            is SseEvent.MessageFinalized -> s.handleMessageFinalized(event, msgId)

            // ── Error ─────────────────────────────────────────────────────
            is SseEvent.Error -> s.handleError(event, msgId)

            // ── Patch ─────────────────────────────────────────────────────
            is SseEvent.Patch -> s.handlePatch(event, msgId)

            // ── Agent ─────────────────────────────────────────────────────
            is SseEvent.Agent -> s.handleAgent(event, msgId)

            // ── Retry ─────────────────────────────────────────────────────
            is SseEvent.Retry -> s.handleRetry(event, msgId)

            // ── Compaction ────────────────────────────────────────────────
            is SseEvent.Compaction -> s.handleCompaction(event, msgId)

            // ── Snapshot ──────────────────────────────────────────────────
            is SseEvent.Snapshot -> { /* internal state marker */ }

            // ── StepFinish ───────────────────────────────────────────────
            is SseEvent.StepFinish -> s.handleStepFinish(event, msgId)

            // ── Subtask ──────────────────────────────────────────────────
            is SseEvent.Subtask -> { /* informational */ }

            // ── Assistant files/images ────────────────────────────────────
            is SseEvent.AssistantFile -> s.handleAssistantFile(event, msgId)
            is SseEvent.AssistantImage -> s.handleAssistantImage(event, msgId)

            // Unreachable — handled in first when block
            is SseEvent.ResetTurn,
            is SseEvent.Ignored,
            is SseEvent.MessageComplete,
            is SseEvent.MessageRemoved,
            is SseEvent.Plan,
            is SseEvent.QuestionAsked,
            is SseEvent.SessionCompacted,
            is SseEvent.SessionCreated,
            is SseEvent.SessionIdle,
            is SseEvent.SessionError,
            is SseEvent.TodoUpdated,
            is SseEvent.UserMessage -> { /* already returned above */ }
        }
    }
}

/**
 * Context passed to pipeline handlers, providing access to SessionState internals.
 *
 * DESIGN NOTE: Passes raw mutable SessionState references to all pipeline handlers.
 * This gives handlers unfettered access to _messages, _toolCalls, and other internal
 * state — which conflicts with the Phase 4 encapsulation goal. This is an intentional
 * trade-off: a read-only SessionStateView interface would add ceremony without benefit
 * for the initial extraction (handlers are migrated 1:1 from processEventInternal,
 * which already accesses this state directly).
 */
class SseEventContext(
    val sessionState: SessionState,
)