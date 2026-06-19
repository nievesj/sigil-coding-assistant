package com.opencode.acp.chat.service

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.SessionManager
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
) {

    private val logger = KotlinLogging.logger {}
    /** @Volatile because read/written from multiple threads:
     *  - startPermissionTimeout() / cancelPermissionTimeout() from ViewModel scope (Dispatchers.Default)
     *  - cancelPermissionTimeout() from dispose() on EDT */
    @Volatile private var permissionTimeoutJob: Job? = null

    /** Respond to a permission prompt. Routes to the correct session by sessionId.
     *
     * ORDER: Local state is updated BEFORE the server call. If the server call fails,
     * the local state is rolled back so the permission prompt stays visible for retry.
     * Previously, the server call was first — a success followed by a local state failure
     * left the server committed but the UI still showing the prompt. */
    suspend fun respondPermission(permissionId: String, toolCallId: String, sessionId: String, response: PermissionResponse) {
        val client = clientProvider()
        if (client == null) {
            logger.warn { "[ACP] Permission response dropped: client is null (server may not be connected)" }
            return
        }
        // Update local state first — optimistic but reversible
        when (response) {
            PermissionResponse.REJECT_ONCE ->
                sessionManager.setToolPartStateForSession(sessionId, toolCallId, PartState.Rejected)
            PermissionResponse.ALLOW_ONCE,
            PermissionResponse.ALLOW_ALWAYS ->
                sessionManager.updateToolCallStatusForSession(sessionId, toolCallId, ToolCallStatus.IN_PROGRESS, null)
        }
        try {
            client.respondPermission(permissionId = permissionId, response = response.optionId)
            // Clear the pending permission flag so the session becomes eligible for
            // cache eviction again. Without this, permission-resolved sessions accumulate
            // in the cache (evictIfNeeded excludes hasPendingPermission sessions).
            sessionManager.getSession(sessionId)?.setPendingPermission(false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation — must re-throw, do NOT swallow
            throw e
        } catch (e: Exception) {
            // Server call failed — roll back local state so the prompt stays visible
            // for retry. Without rollback, the server never received the response
            // but the UI cleared the prompt, leaving the server waiting.
            logger.warn(e) { "[ACP] Failed to respond to permission $permissionId: ${e.message}" }
            when (response) {
                PermissionResponse.REJECT_ONCE ->
                    sessionManager.setToolPartStateForSession(sessionId, toolCallId, PartState.InProgress)
                PermissionResponse.ALLOW_ONCE,
                PermissionResponse.ALLOW_ALWAYS ->
                    sessionManager.updateToolCallStatusForSession(sessionId, toolCallId, ToolCallStatus.PENDING, null)
            }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to reject question $promptId: ${e.message}" }
            throw e
        }
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    fun startPermissionTimeout(timeoutSeconds: Int, onTimeout: () -> Unit) {
        permissionTimeoutJob?.cancel()
        if (timeoutSeconds <= 0) return
        val clampedSeconds = timeoutSeconds.coerceAtMost(3600) // Max 1 hour
        permissionTimeoutJob = scope.launch {
            delay(clampedSeconds * 1000L)
            onTimeout()
        }
    }

    fun cancelPermissionTimeout() {
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
    }

    fun dispose() {
        cancelPermissionTimeout()
    }
}
