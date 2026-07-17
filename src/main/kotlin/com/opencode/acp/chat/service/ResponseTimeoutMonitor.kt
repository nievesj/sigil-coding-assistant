package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.processor.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Activity-aware response timeout monitor.
 *
 * Launched per sendMessage() call. Periodically checks:
 * 1. If tools are running (InProgress/Pending): skip activity timeout, but enforce
 *    a hard tool-stuck ceiling. The ceiling is SUSPENDED while any child/subagent
 *    session is actively generating (via [childActivityProvider]) — this prevents
 *    false-positive aborts during long-running subagents. The ceiling still fires
 *    for genuinely stuck tools (no child activity = lost ToolResult, crashed child,
 *    SSE gap).
 * 2. If no tools running: check elapsed time since last SSE activity across the
 *    active session tree (parent + children, via [effectiveLastActivityMsProvider]).
 *    If > responseTimeoutSeconds, abort streaming.
 *
 * SUBAGENT AWARENESS: During subagent execution, SSE events flow to the CHILD
 * session, not the parent. The parent's own lastActivityTimeMs goes stale. The
 * [effectiveLastActivityMsProvider] returns the max activity across the whole
 * active tree, so child generation keeps the parent's timeouts alive. The
 * [childActivityProvider] returns true if any child is actively generating,
 * suspending the tool-stuck ceiling.
 *
 * The timeout is re-read each iteration from the injected providers — changes take
 * effect mid-response. Both providers' return values are clamped internally:
 * - `responseTimeoutSeconds` clamped to [10, 3600]
 * - `toolStuckTimeoutSeconds` clamped to [60, 3600]
 *
 * Session eviction safety: the monitor re-fetches `sessionManager.getActiveSession()`
 * on EACH iteration (NOT a captured reference). If the session was evicted from the
 * cache (e.g., LRU eviction during a long send), `getActiveSession()` returns null
 * and the monitor skips that iteration.
 *
 * @param scope Coroutine scope for the monitor job
 * @param sessionManager Provides session lookup (re-fetched each iteration for eviction safety)
 * @param responseTimeoutSecondsProvider Returns the configured response timeout (seconds, raw — clamped internally)
 * @param toolStuckTimeoutSecondsProvider Returns the configured tool-stuck ceiling (seconds, raw — clamped internally)
 */
internal class ResponseTimeoutMonitor(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val responseTimeoutSecondsProvider: () -> Int,
    private val toolStuckTimeoutSecondsProvider: () -> Int,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Start monitoring for timeout/stuck-tool conditions on the currently active session.
     *
     * The monitor re-fetches `sessionManager.getActiveSession()` on each iteration to
     * handle session eviction gracefully. If the active session is null (evicted or
     * switched), the monitor skips that iteration — no false-positive timeout.
     *
     * @param onTimeout Called with an error message when the response times out (no SSE activity).
     * @param onToolStuck Called with an error message when a tool is stuck beyond the ceiling.
     * @return The monitor Job — caller must cancel it when the response completes.
     */
    fun startMonitoring(
        onTimeout: (String) -> Unit,
        onToolStuck: (String) -> Unit,
        /**
         * Returns the effective last-activity timestamp across the active session tree
         * (parent + children). Defaults to the active session's own lastActivityTimeMs
         * (backward-compatible with pre-subagent-awareness behavior).
         */
        effectiveLastActivityMsProvider: () -> Long = { sessionManager.getActiveSession()?.lastActivityTimeMs ?: System.currentTimeMillis() },
        /**
         * Returns true if any child/subagent session of the active session is actively
         * generating. When true, the tool-stuck ceiling is suspended (the tool is waiting
         * on a working subagent, not stuck). Defaults to false (backward-compatible —
         * ceiling always enforced if no provider is given).
         */
        childActivityProvider: () -> Boolean = { false },
    ): Job = scope.launch {
        while (isActive) {
            delay(ACTIVITY_CHECK_INTERVAL_MS)
            val responseTimeoutMs = responseTimeoutSecondsProvider()
                .coerceIn(RESPONSE_TIMEOUT_MIN_SECONDS, RESPONSE_TIMEOUT_MAX_SECONDS) * 1000L
            // NOTE: Timeout is re-read each iteration — changes take effect mid-response.
            // This is intentional but may cause unexpected aborts if the user lowers the timeout.
            // Re-fetch the active session on each iteration instead of using the
            // captured reference. The session could be evicted from the cache
            // during the long-running send (e.g., user switches sessions and the
            // old session is LRU-evicted). Reading from an evicted SessionState
            // would give stale toolPartStates and lastActivityTimeMs values.
            val monitorSession = sessionManager.getActiveSession()
            // If the session was evicted or switched, fall back to current time
            // so we don't false-positive timeout on stale data.
            if (monitorSession == null) {
                logger.debug { "[ACP] sendMessage: active session no longer cached, skipping activity check" }
                continue
            }
            // If any tool is actively running, the server is busy — don't timeout.
            // Tool events (ToolUse→ToolResult) can take arbitrarily long (subtasks,
            // file writes, network calls). During this time the parent session gets
            // no SSE events, so lastActivityTimeMs goes stale even though work is
            // happening server-side.
            // PartState.Pending (waiting for user permission) also counts as active —
            // the server IS working, just blocked on user input. Without this, the
            // monitor would false-positive timeout while the user reads the permission prompt.
            // Acquire stateLock for a consistent snapshot of tool states and pills.
            // Without the lock, the event processing coroutine could mutate these maps
            // mid-snapshot, producing an inconsistent view (e.g., a tool appears
            // InProgress in partStates but Completed in pills). The lock is ReentrantLock
            // and the read is O(n) — brief enough to not cause contention.
            val (partStatesSnapshot, pillsSnapshot, statesSnapshot) = monitorSession.snapshotToolState()
            val hasRunningTools = partStatesSnapshot.any {
                it is PartState.InProgress || it is PartState.Pending
            }
            if (hasRunningTools) {
                // Tools are running — skip the normal activity timeout, BUT enforce
                // a hard ceiling based on tool START TIME. If a tool's ToolResult is
                // lost (child crash, SSE reconnect gap), the tool stays InProgress
                // forever. This ceiling ensures recovery.
                //
                // SUBAGENT AWARENESS: The ceiling is SUSPENDED while any child session
                // is actively generating (childActivityProvider returns true). This
                // prevents false-positive aborts during long-running subagents — the
                // tool is InProgress because it's waiting on a working subagent, not
                // because it's stuck. The ceiling still fires when no child is active
                // (genuinely stuck tool, crashed child, lost ToolResult).
                val toolStuckTimeoutSec = toolStuckTimeoutSecondsProvider()
                    .coerceIn(TOOL_STUCK_TIMEOUT_MIN_SECONDS, TOOL_STUCK_TIMEOUT_MAX_SECONDS)
                val toolStuckTimeoutMs = toolStuckTimeoutSec * 1000L
                val oldestToolStartMs = pillsSnapshot
                    .filter {
                        val state = statesSnapshot[it.key]
                        state is PartState.InProgress || state is PartState.Pending
                    }
                    .mapNotNull { it.value.startTimeMs }
                    .minOrNull()
                if (oldestToolStartMs != null) {
                    val toolElapsed = System.currentTimeMillis() - oldestToolStartMs
                    if (toolElapsed > toolStuckTimeoutMs) {
                        // Check if a child/subagent is actively generating — if so,
                        // the "stuck" tool is actually waiting on a working subagent.
                        // Suspend the ceiling; the safety net still fires when the
                        // child goes silent (crash, SSE gap, lost ToolResult).
                        if (childActivityProvider()) {
                            logger.debug { "[ACP] sendMessage: tool running ${toolElapsed}ms (> ${toolStuckTimeoutSec}s) but child actively generating — suspending tool-stuck ceiling" }
                            continue
                        }
                        logger.error { "[ACP] sendMessage: tool stuck for ${toolElapsed}ms (> ${toolStuckTimeoutSec}s) and no child activity — aborting" }
                        onToolStuck("Tool stuck for >${toolStuckTimeoutSec}s.")
                        break
                    }
                }
                logger.debug { "[ACP] sendMessage: tools still running, skipping activity check" }
                continue
            }
            // Use the effective last-activity across the active session tree (parent +
            // children). During subagent generation, the parent's own lastActivityTimeMs
            // goes stale (child events route to the child's SessionState), but the
            // effective value includes child activity, so the activity timeout resets.
            val lastActivity = effectiveLastActivityMsProvider()
            // RESOLVED: lastActivityTimeMs is @Volatile in TurnLifecycleState,
            // ensuring visibility across coroutines. It's written on the SSE event
            // processing coroutine and read here on the activity monitor coroutine.
            // @Volatile provides the necessary happens-before guarantee for a single
            // Long field — no additional synchronization needed.
            val elapsed = System.currentTimeMillis() - lastActivity
            if (elapsed > responseTimeoutMs) {
                val timeoutSec = responseTimeoutSecondsProvider()
                    .coerceIn(RESPONSE_TIMEOUT_MIN_SECONDS, RESPONSE_TIMEOUT_MAX_SECONDS)
                logger.error { "[ACP] sendMessage: no SSE activity for ${elapsed}ms (> ${timeoutSec}s) — aborting" }
                onTimeout("No activity for ${timeoutSec}s.")
                // abortStreaming() emits StreamingCompleted which completes
                // responseDeferred via the global signal collector. Don't call
                // deferred.complete() again — it would throw IllegalStateException.
                break
            }
        }
    }

    companion object {
        const val ACTIVITY_CHECK_INTERVAL_MS = 5_000L

        // Clamping bounds — match current inline behavior (OpenCodeService.kt:970, 1010)
        const val RESPONSE_TIMEOUT_MIN_SECONDS = 10
        const val RESPONSE_TIMEOUT_MAX_SECONDS = 3600
        const val TOOL_STUCK_TIMEOUT_MIN_SECONDS = 60
        const val TOOL_STUCK_TIMEOUT_MAX_SECONDS = 3600
    }
}