package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.CompactionError
import com.opencode.acp.chat.model.CompactionState
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.service.OpenCodeServiceApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns `_compactionState`, `_checkpointReady`, the `compactionInProgress` guard,
 * and `compactSession()`. Reads the selected model via [selectedModelProvider]
 * for providerID/modelID.
 *
 * Extracted per TDD `docs/tdd/ui-testability-refactor.md` §9 step 6 (Phase 3).
 *
 * The `_checkpointReady` StateFlow is owned here for cohesion with compaction
 * state, but it is *written* by [ChatViewModel.computeSessionContext] (via
 * `setCheckpointReady()`). This class only owns the storage; the writer is
 * ChatViewModel because checkpoint readiness depends on the context computation
 * pipeline, which lives in ChatViewModel.
 */
class CompactionViewModel(
    private val service: OpenCodeServiceApi,
    private val scope: CoroutineScope,
    private val selectedModelProvider: () -> ProviderModel?,
) {
    private val logger = KotlinLogging.logger {}

    private val _compactionState = MutableStateFlow<CompactionState>(CompactionState.Idle)
    val compactionState: StateFlow<CompactionState> = _compactionState.asStateFlow()

    private val _checkpointReady = MutableStateFlow(false)
    val checkpointReady: StateFlow<Boolean> = _checkpointReady.asStateFlow()

    /** In-flight guard for compaction — prevents concurrent compaction requests
     *  that could corrupt server-side session state on timeout+retry. */
    private val compactionInProgress = AtomicBoolean(false)

    /** Update the checkpoint-ready state. Called by [ChatViewModel.computeSessionContext]
     *  after `service.isCheckpointReady()` is queried. */
    fun setCheckpointReady(value: Boolean) {
        _checkpointReady.value = value
    }

    /**
     * Trigger manual compaction for the active session.
     * Calls POST /session/{id}/summarize with auto=false.
     *
     * NOTE: The OpenCode server does NOT support a `guidance` field — the request
     * body is only `{ providerID, modelID, auto }`. The TDD originally specified
     * guidance features, but they were dropped after research confirmed the server
     * ignores unknown fields.
     */
    fun compactSession() {
        val sessionId = service.sessionId
        if (sessionId == null) {
            _compactionState.value = CompactionState.Error(CompactionError.NoActiveSession)
            return
        }
        val client = service.connectionManager.client
        if (client == null) {
            _compactionState.value = CompactionState.Error(CompactionError.NotConnected)
            return
        }

        // In-flight guard: prevent concurrent compaction requests. If the server
        // is already processing a compaction (e.g., from a timed-out retry), sending
        // a second request could corrupt session state.
        if (!compactionInProgress.compareAndSet(false, true)) {
            logger.warn { "[ACP] Manual compaction rejected — already in progress" }
            return
        }

        logger.info { "[ACP] Manual compaction triggered for session $sessionId" }
        _compactionState.value = CompactionState.InProgress

        // The guard is reset in the launched coroutine's finally block below,
        // EXCEPT on timeout — see the TimeoutCancellationException branch.
        scope.launch {
            var timedOut = false
            try {
                val model = selectedModelProvider()
                val providerID = model?.providerID ?: ""
                val modelID = model?.modelID ?: ""
                if (providerID.isBlank() || modelID.isBlank()) {
                    _compactionState.value = CompactionState.Error(
                        CompactionError.ServerError("No model selected")
                    )
                    return@launch
                }
                val success = withTimeout(CompactionConstants.COMPACT_TIMEOUT_MS) {
                    client.compactSession(sessionId, providerID, modelID, auto = false)
                }
                if (success) {
                    _compactionState.value = CompactionState.Idle
                    // SSE session.compacted event handles message refresh + context update
                    logger.info { "[ACP] Manual compaction succeeded for session $sessionId" }
                } else {
                    _compactionState.value = CompactionState.Error(
                        CompactionError.ServerError("Compaction failed")
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // Compaction timed out client-side, but the OpenCode server may still
                // be processing the /summarize request. The server's /summarize endpoint
                // performs ACTUAL compaction (removes/summarizes messages), so sending a
                // SECOND compaction while the first is still running server-side could
                // corrupt session state.
                //
                // Fix (option a): do NOT reset compactionInProgress here. Set a TimedOut
                // state telling the user to wait for server confirmation, and launch a
                // backoff coroutine that auto-resets the guard after
                // COMPACT_TIMEOUT_BACKOFF_MS (30s) if no session.compacted SSE arrived.
                // The guard is also released early by onSessionCompacted() when the SSE
                // arrives before the backoff elapses.
                timedOut = true
                _compactionState.value = CompactionState.TimedOut
                logger.warn { "[ACP] Manual compaction timed out for session $sessionId — server may still be processing; guard held for ${CompactionConstants.COMPACT_TIMEOUT_BACKOFF_MS}ms or until session.compacted SSE" }
                scope.launch {
                    delay(CompactionConstants.COMPACT_TIMEOUT_BACKOFF_MS)
                    if (compactionInProgress.compareAndSet(true, false)) {
                        logger.info { "[ACP] Compaction backoff elapsed for session $sessionId — guard released (no session.compacted SSE received)" }
                        if (_compactionState.value is CompactionState.TimedOut) {
                            _compactionState.value = CompactionState.Idle
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectException) {
                _compactionState.value = CompactionState.Error(CompactionError.NotConnected)
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                // Detect timeout-like exceptions from Ktor
                if (msg.contains("timeout", ignoreCase = true) || e is SocketTimeoutException) {
                    _compactionState.value = CompactionState.Error(CompactionError.Timeout)
                } else {
                    _compactionState.value = CompactionState.Error(CompactionError.ServerError(msg))
                }
            } finally {
                // Only reset the guard on non-timeout paths. On timeout, the guard is
                // held until the backoff coroutine (or onSessionCompacted) releases it.
                // NOTE: compactionInProgress.set(false) is a non-suspending atomic
                // operation, so it executes even if the scope is cancelled mid-finally
                // (e.g., tool window disposal during compaction). This guarantees the
                // guard is always reset on non-timeout paths, preventing a stuck guard.
                if (!timedOut) {
                    compactionInProgress.set(false)
                }
            }
        }
    }

    /**
     * Called when a `session.compacted` SSE event arrives for the active session
     * (wired from ChatViewModel's SessionCompacted handling). Releases the
     * in-flight compaction guard early if a timed-out compaction is still holding
     * it, and clears the TimedOut state. This is the "server confirmation" path
     * that short-circuits the [COMPACT_TIMEOUT_BACKOFF_MS] backoff.
     */
    fun onSessionCompacted() {
        if (compactionInProgress.compareAndSet(true, false)) {
            logger.info { "[ACP] session.compacted SSE received — releasing compaction guard early" }
        }
        if (_compactionState.value is CompactionState.TimedOut) {
            _compactionState.value = CompactionState.Idle
        }
    }

    /** Reset compaction state to Idle (e.g., after user dismisses an error). */
    fun resetCompactionState() {
        _compactionState.value = CompactionState.Idle
    }
}