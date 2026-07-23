package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.processor.UiSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Pure signal-to-effect mapping. Receives [UiSignal] events and emits
 * [SignalEffect] values. Owns NO state and holds NO mutable manager references.
 *
 * This preserves the ordered side effects of [UiSignal.StreamingCompleted]:
 * [SignalEffect.SetStreamPhaseIdle] → [SignalEffect.NotifyResponseComplete]
 * (conditional) → [SignalEffect.ComputeSessionContext] →
 * [SignalEffect.FetchTodos] → [SignalEffect.LoadSessions] →
 * [SignalEffect.DrainQueue] → [SignalEffect.RefreshReviewFiles].
 *
 * The 5th side effect, [SignalEffect.RefreshReviewFiles], is emitted and the
 * [SignalSideEffectExecutor] wraps it in its own try/catch so a failure
 * doesn't break subsequent emissions.
 *
 * MUST NOT touch: StreamingCompleted ordered side effects order.
 *
 * @param scope The ViewModel scope used to launch the collect coroutines.
 *   The router itself owns no state — the scope is only used to start
 *   collection jobs.
 */
class SignalRouter(
    private val scope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger {}

    private val _effects = MutableSharedFlow<SignalEffect>(extraBufferCapacity = 256)
    val effects: SharedFlow<SignalEffect> = _effects.asSharedFlow()

    /** Job tracking for stop(). */
    private var activeJob: kotlinx.coroutines.Job? = null
    private var globalJob: kotlinx.coroutines.Job? = null

    /**
     * Start collecting [UiSignal] events from [signals] (active-session) and
     * [globalSignals] (global), emitting [SignalEffect] values to [effects].
     */
    fun start(signals: Flow<UiSignal>, globalSignals: Flow<UiSignal>) {
        activeJob = scope.launch {
            signals.collect { signal -> routeSignal(signal) }
        }
        globalJob = scope.launch {
            globalSignals.collect { signal -> routeGlobalSignal(signal) }
        }
    }

    /** Stop collecting signals. Cancels both collect jobs. */
    fun stop() {
        activeJob?.cancel()
        activeJob = null
        globalJob?.cancel()
        globalJob = null
    }

    /** Route an active-session [UiSignal] to one or more [SignalEffect]s. */
    private suspend fun routeSignal(signal: UiSignal) {
        when (signal) {
            is UiSignal.StreamingStarted -> {
                // _streamPhase.value = StreamPhase.STREAMING — handled directly by
                // ChatViewModel (simple StateFlow update, no side effect needed).
                // The router only emits effects for side-effectful operations.
            }
            is UiSignal.StreamingCompleted -> {
                // CRITICAL: ordered side effects — preserve exact order.
                // Each is emitted separately so the executor can independently
                // try/catch each. The router does NOT check naturalCompletion,
                // isActiveMessage, or isActiveSessionChild here — those gates
                // are applied in the executor (which has access to service state).
                _effects.emit(SignalEffect.SetStreamPhaseIdle(signal.messageId))
                // NotifyResponseComplete is emitted unconditionally; the executor
                // checks naturalCompletion (carried via the effect's messageId
                // lookup) + isActiveMessage + !isActiveSessionChild before firing.
                // To preserve the original gate (naturalCompletion), we pass it
                // by emitting NotifyResponseComplete only when naturalCompletion=true.
                if (signal.naturalCompletion) {
                    _effects.emit(SignalEffect.NotifyResponseComplete(signal.messageId))
                }
                _effects.emit(SignalEffect.ComputeSessionContext(null))
                _effects.emit(SignalEffect.FetchTodos(null))
                _effects.emit(SignalEffect.LoadSessions(false))
                _effects.emit(SignalEffect.DrainQueue)
                _effects.emit(SignalEffect.RefreshReviewFiles)
            }
            is UiSignal.PermissionRequested -> {
                _effects.emit(SignalEffect.SetPermissionPrompt(signal.prompt))
                _effects.emit(SignalEffect.NotifyPermissionNeeded)
                _effects.emit(SignalEffect.StartPermissionTimeout(signal.prompt))
            }
            is UiSignal.SelectionRequested -> {
                _effects.emit(SignalEffect.SetSelectionPrompt(signal.prompt))
                _effects.emit(SignalEffect.NotifyQuestionAsked)
            }
            is UiSignal.Error -> Unit
            is UiSignal.TodoUpdated -> Unit
            is UiSignal.FileChanged -> {
                _effects.emit(SignalEffect.EmitFileChangeSignal)
                _effects.emit(SignalEffect.RefreshReviewFiles)
            }
            is UiSignal.MessageUpdated -> {
                _effects.emit(SignalEffect.ComputeSessionContextLocal(signal.messageId))
            }
            // Global-only signals — should not arrive on activeSignals,
            // but must be present for exhaustive when.
            is UiSignal.SessionCreated -> Unit
            is UiSignal.SessionIdle -> Unit
            is UiSignal.SessionError -> Unit
            is UiSignal.SessionCompacted -> Unit
            is UiSignal.SessionDeleted -> Unit
            is UiSignal.ChildPermissionRequested -> Unit
            is UiSignal.PermissionReplied -> Unit
            is UiSignal.PermissionTimedOut -> {
                // PermissionTimedOut should arrive on globalSignals (active-session
                // timeouts fire via the service scope and emit through globalSignals).
                // If it arrives on activeSignals, it indicates a routing bug — log a
                // warning so misrouted signals are visible during debugging.
                logger.warn { "[ACP] PermissionTimedOut arrived on activeSignals (expected globalSignals) — possible routing bug; signal dropped" }
            }
        }
    }

    /** Route a global [UiSignal] to one or more [SignalEffect]s. */
    private suspend fun routeGlobalSignal(signal: UiSignal) {
        when (signal) {
            is UiSignal.SessionCreated -> {
                _effects.emit(SignalEffect.LoadSessions(false))
            }
            is UiSignal.SessionIdle -> {
                _effects.emit(SignalEffect.ComputeSessionContext(null))
            }
            is UiSignal.SessionError -> {
                _effects.emit(SignalEffect.SetStreamPhaseIdleForSession(signal.sessionId))
                _effects.emit(SignalEffect.RemoveStreamingSession(signal.sessionId))
            }
            is UiSignal.SessionCompacted -> {
                _effects.emit(SignalEffect.RefreshActiveSessionMessages(signal.sessionId))
                _effects.emit(SignalEffect.ComputeSessionContext(null))
            }
            is UiSignal.SessionDeleted -> {
                _effects.emit(SignalEffect.HandleSessionDeleted(signal.sessionId))
            }
            is UiSignal.ChildPermissionRequested -> {
                _effects.emit(SignalEffect.AddChildPermissionPrompt(signal.prompt))
                _effects.emit(SignalEffect.NotifyPermissionNeeded)
            }
            is UiSignal.PermissionReplied -> {
                _effects.emit(SignalEffect.HandlePermissionReplied(signal.permissionId, signal.reply, signal.sessionId))
            }
            is UiSignal.PermissionTimedOut -> {
                _effects.emit(SignalEffect.HandlePermissionTimedOut(signal.permissionId, signal.sessionId, signal.toolName))
            }
            // Active-session signals — should not arrive on globalSignals,
            // but must be present for exhaustive when.
            is UiSignal.StreamingStarted -> Unit
            is UiSignal.StreamingCompleted -> Unit
            is UiSignal.MessageUpdated -> Unit
            is UiSignal.PermissionRequested -> Unit
            is UiSignal.SelectionRequested -> Unit
            is UiSignal.Error -> Unit
            is UiSignal.TodoUpdated -> Unit
            is UiSignal.FileChanged -> Unit
        }
    }
}