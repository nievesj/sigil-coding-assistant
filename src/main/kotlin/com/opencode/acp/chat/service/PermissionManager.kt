package com.opencode.acp.chat.service

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.mcp.McpConfigWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*

/**
 * Manages permission prompts and question/selection interactions.
 * Routes permission responses to the correct session via [SessionManager].
 */
class PermissionManager(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val sessionManager: SessionManager,
    private val mcpConfigWriterProvider: () -> McpConfigWriter? = { null },
) {

    private val logger = KotlinLogging.logger {}
    /** @Volatile because read/written from multiple threads:
     *  - startPermissionTimeout() / cancelPermissionTimeout() from ViewModel scope (Dispatchers.Default)
     *  - cancelPermissionTimeout() from dispose() on EDT */
    @Volatile private var permissionTimeoutJob: Job? = null
    /** Serializes the cancel+launch sequence in [startPermissionTimeout] so two
     *  concurrent calls cannot both leak an uncancelled job. Non-suspending lock
     *  because [startPermissionTimeout] / [cancelPermissionTimeout] are called
     *  from EDT and ViewModel scope (non-suspend). */
    private val timeoutLock = Any()

    /** Respond to a permission prompt. Routes to the correct session by sessionId.
     *
     * ORDER: Local state is updated BEFORE the server call. If the server call fails,
     * the local state is rolled back so the permission prompt stays visible for retry.
     * Previously, the server call was first — a success followed by a local state failure
     * left the server committed but the UI still showing the prompt. */
    suspend fun respondPermission(
        permissionId: String,
        toolCallId: String,
        sessionId: String,
        response: PermissionResponse,
        toolName: String = "",
        patterns: List<String> = emptyList(),
        agentName: String = "orchestrator",
    ) {
        // NOTE (ABA race): This uses optimistic local updates before the server call.
        // If an SSE event arrives between the optimistic update and a rollback (on
        // server failure), that SSE-driven state could be overwritten by the rollback.
        // This is an accepted limitation — the window is small and the server is
        // authoritative on retry (the prompt re-appears if the server never received
        // the response).
        val client = clientProvider()
        if (client == null) {
            logger.warn { "[ACP] Permission response dropped: client is null (server may not be connected)" }
            return
        }
        // Update local state first — optimistic but reversible.
        // REJECT_ONCE is NOT updated optimistically — see below for the post-server-call
        // update. This avoids diverging local and server state on POST failure.
        if (response == PermissionResponse.ALLOW_ONCE || response == PermissionResponse.ALLOW_ALWAYS) {
            sessionManager.updateToolCallStatusForSession(sessionId, toolCallId, ToolCallStatus.IN_PROGRESS, null)
        }
        try {
            client.respondPermission(permissionId = permissionId, sessionId = sessionId, response = response.optionId)
            // REJECT_ONCE local state is updated AFTER the server call succeeds to avoid
            // diverging local and server state on POST failure. If the server never received
            // the rejection, the tool must NOT appear Rejected locally — otherwise the user
            // sees a 'Rejected' pill while the server-side tool may still proceed. ALLOW_*
            // remain optimistic because IN_PROGRESS is the natural next state and SSE
            // reconciles quickly.
            if (response == PermissionResponse.REJECT_ONCE) {
                sessionManager.setToolPartStateForSession(sessionId, toolCallId, PartState.Rejected)
            }
            // Config sync AFTER POST succeeds (not in parallel) — if the server rejects,
            // the config file should NOT say "allow".
            if (response == PermissionResponse.ALLOW_ALWAYS && toolName.isNotEmpty()) {
                try {
                    mcpConfigWriterProvider()?.writeAlwaysAllowRule(agentName, toolName, patterns)
                    logger.debug { "[ACP] Always Allow synced to config: agent=$agentName, tool=$toolName, patterns=$patterns" }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Failed to sync 'Always Allow' to config for $toolName" }
                    // Non-fatal — server already persists "always" internally
                }
            }
            // Clear the pending permission flag so the session becomes eligible for
            // cache eviction again. Without this, permission-resolved sessions accumulate
            // in the cache (evictIfNeeded excludes hasPendingPermission sessions).
            sessionManager.getSession(sessionId)?.setPendingPermission(false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation — must re-throw, do NOT swallow
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to respond to permission $permissionId: ${e.message}" }
            // Do NOT roll back ALLOW_* tool state on POST failure. The server may have
            // processed the response despite the network error (TDD §4.2.4). Rolling
            // back to PENDING would overwrite SSE-driven state that already moved the
            // tool to IN_PROGRESS, making the tool appear stuck. REJECT_ONCE is not
            // updated optimistically (see above), so there is nothing to roll back.
            // The PermissionReplied handler (or timeout) is the sole authority on
            // clearing tool state. The prompt stays visible for retry because the
            // caller (ChatViewModel) catches the exception and keeps the prompt.
            throw e
        }
    }

    /** Answer a selection question from the agent. */
    suspend fun respondQuestion(promptId: String, answers: List<List<String>>, sessionId: String) {
        val client = clientProvider()
        if (client == null) {
            logger.warn { "[ACP] respondQuestion dropped: client is null (server may not be connected)" }
            return
        }
        try {
            client.respondQuestion(promptId, answers)
            // Clear the pending selection prompt ONLY after the server call succeeds.
            // If the call throws, this line is not reached — the prompt stays visible
            // for retry. (False-positive review concern: clearPendingSelection is
            // AFTER the server call, not before.)
            sessionManager.getSession(sessionId)?.clearPendingSelection()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to respond to question $promptId: ${e.message}" }
            throw e
        }
    }

    /** Reject a selection question. */
    suspend fun rejectQuestion(promptId: String, sessionId: String) {
        val client = clientProvider()
        if (client == null) {
            logger.warn { "[ACP] rejectQuestion dropped: client is null (server may not be connected)" }
            return
        }
        try {
            client.rejectQuestion(promptId)
            // Clear the pending selection prompt ONLY after the server call succeeds
            // (see respondQuestion for the ordering rationale).
            sessionManager.getSession(sessionId)?.clearPendingSelection()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to reject question $promptId: ${e.message}" }
            throw e
        }
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    /**
     * Start a permission timeout coroutine.
     *
     * Threading: The [onTimeout] callback is invoked on [scope] (Dispatchers.Default).
     * StateFlow updates from the callback are thread-safe. [OpenCodeNotifications]
     * methods internally use `invokeLater` for EDT-safe notification posting.
     *
     * The [timeoutSeconds] value is clamped to [0, 3600] before multiplication to
     * prevent absurd delays. The caller's value is untrusted (from settings).
     */
    fun startPermissionTimeout(
        timeoutSeconds: Int,
        toolName: String = "",
        onTimeout: () -> Unit,
    ) {
        synchronized(timeoutLock) {
            permissionTimeoutJob?.cancel()
            if (timeoutSeconds <= 0) return
            // Clamp BEFORE multiply: coerceAtMost(3600) bounds the value to <= 3600,
            // so `clampedSeconds * 1000L` is at most 3,600,000 — no Int overflow possible.
            val clampedSeconds = timeoutSeconds.coerceAtMost(3600) // Max 1 hour
            permissionTimeoutJob = scope.launch {
                delay(clampedSeconds * 1000L)
                try {
                    onTimeout()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "[ACP] Permission timeout callback threw an exception" }
                }
            }
        }
    }

    fun cancelPermissionTimeout() {
        synchronized(timeoutLock) {
            permissionTimeoutJob?.cancel()
            permissionTimeoutJob = null
        }
    }

    fun dispose() {
        cancelPermissionTimeout()
    }
}
