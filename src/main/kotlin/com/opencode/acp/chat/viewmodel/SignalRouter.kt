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

    /** Guard flag — when true, routeSignal/routeGlobalSignal drop emissions to prevent
     *  effects being processed after the router has been stopped. Set by stop(), cleared
     *  by start(). This is defense-in-depth alongside the caller ordering requirement
     *  (stop executor before router). */
    @Volatile
    private var stopped = false

    /** Recently-seen (permissionId, sessionId, toolName) tuples for PermissionTimedOut
     *  deduplication. Prevents double-fire when the same signal arrives on both
     *  activeSignals and globalSignals. Entries expire after a short TTL to bound
     *  memory. Access is synchronized because the two collectors run concurrently. */
    private val recentPermissionTimeouts = java.util.LinkedHashMap<String, Long>()
    private val permissionTimeoutDedupTtlMs = 5_000L

    /** Returns true if this PermissionTimedOut signal was already seen recently
     *  (within the dedup TTL). Thread-safe via synchronized block. */
    private fun isDuplicatePermissionTimeout(permissionId: String, sessionId: String, toolName: String): Boolean {
        val key = "$permissionId|$sessionId|$toolName"
        val now = System.currentTimeMillis()
        synchronized(recentPermissionTimeouts) {
            // Prune expired entries
            val iterator = recentPermissionTimeouts.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > permissionTimeoutDedupTtlMs) {
                    iterator.remove()
                } else {
                    break // LinkedHashMap preserves insertion order; entries after this are newer
                }
            }
            if (recentPermissionTimeouts.containsKey(key)) {
                return true
            }
            recentPermissionTimeouts[key] = now
            return false
        }
    }

    /**
     * Start collecting [UiSignal] events from [signals] (active-session) and
     * [globalSignals] (global), emitting [SignalEffect] values to [effects].
     *
     * Safe to call multiple times: any existing collect jobs are cancelled and
     * joined before launching new ones. The join() ensures in-flight routeSignal
     * calls complete before new collectors start, preventing duplicate effects
     * from overlapping old and new collectors processing the same signal.
     *
     * NOTE: This function is non-suspending. The cancel+join+relaunch sequence
     * runs inside a single [scope.launch] coroutine so the join() (a suspend
     * call) is in a coroutine context. The new collect jobs are launched from
     * within that same coroutine, after the join completes, preserving
     * ordering: old collectors finish → new collectors start.
     */
    fun start(signals: Flow<UiSignal>, globalSignals: Flow<UiSignal>) {
        // Set stopped = false SYNCHRONOUSLY so signals arriving before the
        // scope.launch block runs are NOT dropped by the routeSignal guard.
        // If this were set inside the launch coroutine, there would be a window
        // where `stopped` is still true (initial value or from a prior stop())
        // and a StreamingCompleted signal arriving in that window would be
        // silently dropped — leaving _streamPhase stuck in STREAMING forever.
        stopped = false
        // Cancel any existing jobs before launching new ones. Without this guard,
        // a double-start would orphan the first pair of collect jobs (still running,
        // producing duplicate effects from the old Flow references).
        // join() ensures in-flight routeSignal/routeGlobalSignal calls finish
        // before the new collectors begin, so a signal in flight during restart
        // is not processed twice (once by the old collector, once by the new).
        // The cancel+join+relaunch runs in a single coroutine so the suspend
        // join() is in a coroutine context and the relaunch happens after join.
        scope.launch {
            activeJob?.cancel()
            globalJob?.cancel()
            activeJob?.join()
            globalJob?.join()
            activeJob = scope.launch {
                signals.collect { signal -> routeSignal(signal) }
            }
            globalJob = scope.launch {
                globalSignals.collect { signal -> routeGlobalSignal(signal) }
            }
        }
    }

    /** Stop collecting signals. Cancels both collect jobs.
     *
     * Best-effort: cancels the collect jobs but does NOT join them. An in-flight
     * [routeSignal] call may still emit remaining effects to the buffered SharedFlow
     * (extraBufferCapacity=256) before cancellation takes effect. These buffered
     * effects are silently discarded if the [SignalSideEffectExecutor] has already
     * stopped collecting.
     *
     * CALLER ORDERING REQUIREMENT: callers MUST stop the [SignalSideEffectExecutor]
     * BEFORE calling stop() on the router. This ensures the executor's collect
     * coroutine is cancelled first, so any effects emitted by an in-flight
     * [routeSignal] call (after this stop()) are not processed. [ChatViewModel.close]
     * enforces this ordering — it stops the executor before the router. Reversing
     * the order risks processing effects (e.g., [SignalEffect.DrainQueue]) after
     * the ViewModel is partially torn down.
     *
     * DEFENSE-IN-DEPTH: Sets `stopped = true` so routeSignal/routeGlobalSignal drop
     * emissions after stop() returns, even if an in-flight call is still executing.
     * This reduces (but does not eliminate) the window — a call already past the
     * guard check when stop() sets the flag will still emit. The caller ordering
     * requirement remains the primary mitigation.
     */
    fun stop() {
        stopped = true
        activeJob?.cancel()
        activeJob = null
        globalJob?.cancel()
        globalJob = null
        synchronized(recentPermissionTimeouts) { recentPermissionTimeouts.clear() }
    }

    /** Route an active-session [UiSignal] to one or more [SignalEffect]s. */
    private suspend fun routeSignal(signal: UiSignal) {
        if (stopped) return
        when (signal) {
            is UiSignal.StreamingStarted -> {
                // _streamPhase.value = StreamPhase.STREAMING — handled directly by
                // ChatViewModel (simple StateFlow update, no side effect needed).
                // The router only emits effects for side-effectful operations.
            }
            is UiSignal.StreamingCompleted -> {
                // CRITICAL: ordered side effects — preserve exact order.
                // Each is emitted separately so the executor can independently
                // try/catch each. naturalCompletion is checked HERE (router) —
                // NotifyResponseComplete is only emitted when naturalCompletion=true.
                // isActiveMessage and !isActiveSessionChild are checked in the executor
                // (SignalSideEffectExecutor.kt), which has access to service state.
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
            is UiSignal.Error -> {
                // Error signal (abort/timeout): emit SetStreamPhaseIdleGated as a
                // backstop. abortStreamingWithFallback emits StreamingCompleted
                // BEFORE this signal, but StreamingCompleted is guarded by
                // streamingCompletedEmitted — if a prior new_message/stop
                // finalization already set that flag true in this turn chain,
                // the StreamingCompleted signal is suppressed and _streamPhase
                // never resets to IDLE. Emitting SetStreamPhaseIdleGated here ensures
                // the phase resets regardless. SetStreamPhaseIdleGated is idempotent
                // (setting IDLE when already IDLE is a no-op), so double-emission
                // is safe.
                //
                // The executor gates this on isActiveMessage(messageId) so an
                // Error for a message NOT in the active session does NOT reset
                // the active session's stream phase (prevents a background error
                // from killing a legitimately streaming active session's UI).
                _effects.emit(SignalEffect.SetStreamPhaseIdleGated(signal.messageId))
            }
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
                // If it arrives on activeSignals, it indicates a routing bug. Delegate
                // to the global handler (routeGlobalSignal) instead of emitting the
                // effect directly. This ensures a single emission path: if the same
                // signal also arrives on globalSignals, both calls funnel through
                // routeGlobalSignal, which is the canonical emitter. The dedup
                // check in routeGlobalSignal (isDuplicatePermissionTimeout) ensures
                // that even if the same signal arrives on both streams, only the
                // first emission produces an effect.
                // Silently dropping a security-relevant signal would leave the
                // permission prompt on screen indefinitely with no timeout, so we
                // forward rather than drop.
                logger.warn { "[ACP] PermissionTimedOut arrived on activeSignals (expected globalSignals) — possible routing bug; delegating to global handler" }
                routeGlobalSignal(signal)
            }
        }
    }

    /** Route a global [UiSignal] to one or more [SignalEffect]s. */
    private suspend fun routeGlobalSignal(signal: UiSignal) {
        if (stopped) return
        when (signal) {
            is UiSignal.SessionCreated -> {
                _effects.emit(SignalEffect.LoadSessions(false))
            }
            is UiSignal.SessionIdle -> {
                _effects.emit(SignalEffect.ComputeSessionContext(null))
            }
            is UiSignal.SessionError -> {
                // Log the error message (preserves errorMessage that was previously
                // discarded). The executor logs it at WARN level for idea.log visibility.
                _effects.emit(SignalEffect.LogSessionError(signal.sessionId, signal.errorMessage))
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
                // Dedup: if the same PermissionTimedOut was already handled (e.g.,
                // it arrived on activeSignals first and was delegated here, then
                // also arrived on globalSignals), skip the second emission. This
                // prevents double-reject and double-clear-prompt.
                if (!isDuplicatePermissionTimeout(signal.permissionId, signal.sessionId, signal.toolName)) {
                    _effects.emit(SignalEffect.HandlePermissionTimedOut(signal.permissionId, signal.sessionId, signal.toolName))
                } else {
                    logger.warn { "[ACP] PermissionTimedOut dedup: skipping duplicate (permissionId=${signal.permissionId}, sessionId=${signal.sessionId})" }
                }
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