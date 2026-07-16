package com.opencode.acp.chat.service

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.processor.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the global SSE subscription lifecycle.
 *
 * - Subscribes to [OpenCodeClient.subscribeGlobalEvents] and routes events to [SessionManager.processEvent]
 * - Automatic reconnection with exponential backoff + jitter on stream end
 * - Periodic health-check probes when the connection is silent (Java HTTP has no socket idle timeout)
 * - Circuit breaker: transitions to ERROR after [MAX_RECONNECT_ATTEMPTS] failed attempts
 * - Debug event-summary logging (the `when` block from handleSseEvent moves here — no onEvent callback)
 *
 * @param scope Coroutine scope for launching SSE/health-check/reconnect jobs
 * @param clientProvider Returns the current OpenCodeClient (null if not connected)
 * @param sessionManager Routes SSE events to SessionManager.processEvent
 * @param onConnectionError Called when the circuit breaker trips (transitions ProcessManager to ERROR)
 * @param onReconnectSuccess Called after reconnection succeeds — OpenCodeService uses this to invoke
 *   sessionManager.recoverBackgroundSessions(client), sessionManager.fetchTodos(), and
 *   sessionManager.computeSessionContext(). This keeps SseConnectionManager focused on transport
 *   and avoids coupling it to SessionState recovery internals.
 */
internal class SseConnectionManager(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val sessionManager: SessionManager,
    private val onConnectionError: () -> Unit,
    private val onReconnectSuccess: suspend () -> Unit,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile private var sseJob: Job? = null
    @Volatile private var sseReconnectJob: Job? = null
    @Volatile private var sseReconnectAttempt: Int = 0
    /** Timestamp (epoch ms) of the last SSE event received. Used for health-check timing. */
    private val sseLastEventTimeMs = AtomicLong(0L)
    /** Job that periodically health-checks the server when SSE is silent. */
    @Volatile private var sseHealthCheckJob: Job? = null

    /** Whether the SSE subscription is currently active. Returns false when sseJob is null
     *  (after stop()) or during the brief window between job launch and coroutine start.
     *  Used only in tests — production code should not gate decisions on this property. */
    val isActive: Boolean get() = sseJob?.isActive == true

    /** Start the SSE subscription + health check. Idempotent — cancels previous jobs first. */
    fun start() {
        sseJob?.cancel()
        sseReconnectJob?.cancel()
        sseHealthCheckJob?.cancel()
        val client = clientProvider() ?: return
        val connectTime = System.currentTimeMillis()
        sseLastEventTimeMs.set(connectTime)
        logger.info { "[ACP] SSE connected at $connectTime" }

        sseJob = launchSseJob(client, connectTime)
        sseHealthCheckJob = launchHealthCheck(client, connectTime)
    }

    /** Stop all SSE activity: subscription, reconnection loop, health checks.
     *  Must be non-blocking (EDT-safe): cancels jobs only, never calls join().
     *  Must be idempotent: safe to call from both stopConnection() and dispose(). */
    fun stop() {
        sseReconnectJob?.cancel()
        sseReconnectJob = null
        sseHealthCheckJob?.cancel()
        sseHealthCheckJob = null
        sseJob?.cancel()
        sseJob = null
    }

    /** Manually trigger reconnection (e.g., after health check detects a dead connection). */
    fun triggerReconnect() {
        sseReconnectJob?.cancel()
        sseReconnectAttempt = 0

        sseReconnectJob = scope.launch {
            while (isActive) {
                val base = ChatConstants.RECONNECT_DELAY_MS
                val max = ChatConstants.RECONNECT_MAX_DELAY_MS
                val exponential = minOf(base * (1L shl sseReconnectAttempt.coerceIn(0, MAX_BACKOFF_SHIFT)), max)
                val jitter = (exponential * kotlin.random.Random.nextDouble(0.0, 0.2)).toLong()
                val delayMs = (exponential + jitter).coerceAtMost(max)

                // Circuit breaker: after MAX_RECONNECT_ATTEMPTS, transition to ERROR state
                // to stop infinite reconnection loops on permanently-down servers.
                if (sseReconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                    logger.error { "[ACP] SSE reconnect: giving up after $MAX_RECONNECT_ATTEMPTS attempts — server appears permanently down" }
                    onConnectionError()
                    return@launch
                }

                if (sseReconnectAttempt > 0) {
                    logger.info { "[ACP] SSE reconnect attempt ${sseReconnectAttempt + 1}, waiting ${delayMs}ms" }
                    delay(delayMs)
                }

                val client = clientProvider()
                if (client == null) {
                    logger.warn { "[ACP] SSE reconnect: client is null, giving up" }
                    return@launch
                }

                try {
                    if (!client.healthCheck()) {
                        sseReconnectAttempt++
                        continue
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    sseReconnectAttempt++
                    continue
                }

                // Server healthy — re-subscribe global SSE
                logger.info { "[ACP] SSE reconnect: server healthy, re-subscribing" }
                // Capture the old jobs BEFORE reassigning sseJob/sseHealthCheckJob below.
                // The previous code called `sseJob?.join()` after `sseJob = launchSseJob(...)`,
                // which joined the NEW job instead of the old one — defeating the purpose
                // of waiting for the old job to finish before launching new ones.
                val oldSseJob = sseJob
                val oldHealthCheckJob = sseHealthCheckJob
                oldSseJob?.cancel()
                oldHealthCheckJob?.cancel()
                // Wait for old jobs to finish before launching new ones to prevent
                // stale StreamingCompleted signals from completing the new turn's deferred.
                // Use withTimeoutOrNull to avoid hanging if the old job is stuck on
                // a half-open TCP connection (no socket timeout per AGENTS.md).
                // If the join times out, the old job becomes a zombie — but it's harmless:
                // oldSseJob?.cancel() was already called above, and the scope will reclaim it
                // on dispose. The zombie can't process new events (its TCP stream is dead).
                withTimeoutOrNull(5000) { oldSseJob?.join() }
                withTimeoutOrNull(5000) { oldHealthCheckJob?.join() }
                val reconnectTime = System.currentTimeMillis()
                sseLastEventTimeMs.set(reconnectTime)
                logger.info { "[ACP] SSE reconnected at $reconnectTime" }

                sseJob = launchSseJob(client, reconnectTime)
                sseHealthCheckJob = launchHealthCheck(client, reconnectTime)

                // Signal success — OpenCodeService handles session recovery, todo refresh,
                // and context recomputation via the onReconnectSuccess callback.
                try {
                    onReconnectSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] SSE reconnect: onReconnectSuccess callback failed" }
                }

                sseReconnectAttempt = 0
                logger.info { "[ACP] SSE reconnected successfully" }
                return@launch
            }
        }
    }

    /** Launch the SSE event collection job. Shared between initial subscription
     *  and reconnection to prevent divergence. Handles stream end by triggering
     *  reconnection — works correctly for both user-initiated cancellation and
     *  unexpected errors. */
    private fun launchSseJob(client: OpenCodeClient, connectTime: Long): Job = scope.launch {
        try {
            client.subscribeGlobalEvents().collect { event ->
                sseLastEventTimeMs.set(System.currentTimeMillis())
                handleSseEvent(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] SSE collection error: ${e.message}" }
        }
        // Stream ended — trigger reconnection if this wasn't a user-initiated stop.
        // CancellationException from stop() is caught above; after it,
        // scope is cancelled so isActive=false and we skip reconnection.
        if (isActive) {
            val uptimeSec = (System.currentTimeMillis() - connectTime) / 1000
            logger.info { "[ACP] SSE stream ended after ${uptimeSec}s — triggering reconnection" }
            triggerReconnect()
        }
    }

    /** Launch a health-check probe coroutine. When the SSE connection has been
     *  silent for [ChatConstants.SSE_HEALTH_CHECK_INTERVAL_MS], sends a lightweight
     *  GET /global/health to verify the server and connection are alive. If the
     *  health check fails, cancels [sseJob] which triggers reconnection via
     *  [launchSseJob]'s post-catch logic.
     *
     *  This replaces the old idle-detection approach that killed healthy
     *  connections during normal user thinking time. */
    private fun launchHealthCheck(client: OpenCodeClient, connectTime: Long): Job = scope.launch {
        val checkInterval = ChatConstants.SSE_HEALTH_CHECK_INTERVAL_MS
        val checkTimeout = ChatConstants.SSE_HEALTH_CHECK_TIMEOUT_MS
        while (isActive) {
            delay(checkInterval)
            val lastEvent = sseLastEventTimeMs.get()
            val silenceMs = System.currentTimeMillis() - lastEvent
            if (silenceMs < checkInterval) continue  // Recent activity — no probe needed

            try {
                val healthy = withTimeout(checkTimeout) { client.healthCheck() }
                if (healthy) {
                    // Server is alive and responding — reset the timer so the SSE
                    // connection stays open. Silence doesn't mean death.
                    sseLastEventTimeMs.set(System.currentTimeMillis())
                } else {
                    logger.warn { "[ACP] SSE health check failed (server unhealthy) after ${silenceMs}ms silence — triggering reconnection" }
                    sseJob?.cancel()
                    // Trigger reconnection explicitly — launchSseJob's post-catch
                    // logic checks isActive which is false after cancellation, so
                    // it won't call triggerReconnect() on its own.
                    triggerReconnect()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logger.warn { "[ACP] SSE health check failed (request error) after ${silenceMs}ms silence — triggering reconnection" }
                sseJob?.cancel()
                // Trigger reconnection explicitly — same reason as above.
                triggerReconnect()
                break
            }
        }
    }

    private suspend fun handleSseEvent(event: SseEvent) {
        val summary = when (event) {
            is SseEvent.TextChunk -> "TextChunk(sid=${event.sessionId}, mid=${event.messageId}, text.len=${event.text.length})"
            is SseEvent.ThinkingChunk -> "ThinkingChunk(sid=${event.sessionId}, mid=${event.messageId}, text.len=${event.text.length})"
            is SseEvent.Stop -> "Stop(sid=${event.sessionId}, mid=${event.messageId}, reason=${event.stopReason})"
            is SseEvent.Error -> "Error(sid=${event.sessionId}, mid=${event.messageId}, msg.len=${event.message.length})"
            is SseEvent.ToolUse -> "ToolUse(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.ToolResult -> "ToolResult(sid=${event.sessionId}, mid=${event.messageId}, call=${event.toolCallId})"
            is SseEvent.Permission -> "Permission(sid=${event.sessionId}, mid=${event.messageId}, tool=${event.toolCallId})"
            is SseEvent.Ignored -> "Ignored(type=${event.eventType}, reason=${event.reason})"
            else -> "${event::class.simpleName}(sid=${event.sessionId}, mid=${event.messageId})"
        }
        logger.debug { "[ACP] handleSseEvent: $summary" }
        sessionManager.processEvent(event)
    }

    companion object {
        const val MAX_BACKOFF_SHIFT = 10
        const val MAX_RECONNECT_ATTEMPTS = 50
    }
}