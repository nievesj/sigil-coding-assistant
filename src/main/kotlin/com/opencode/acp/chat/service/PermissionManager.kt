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
    private var permissionTimeoutJob: Job? = null

    /** Respond to a permission prompt. Routes to the correct session by sessionId. */
    suspend fun respondPermission(permissionId: String, toolCallId: String, sessionId: String, response: PermissionResponse) {
        val client = clientProvider() ?: return
        try {
            client.respondPermission(permissionId = permissionId, response = response.optionId)
            when (response) {
                PermissionResponse.REJECT_ONCE ->
                    sessionManager.setToolPartStateForSession(sessionId, toolCallId, PartState.Rejected)
                PermissionResponse.ALLOW_ONCE,
                PermissionResponse.ALLOW_ALWAYS ->
                    sessionManager.updateToolCallStatusForSession(sessionId, toolCallId, ToolCallStatus.IN_PROGRESS, null)
            }
            // Clear the pending permission flag so the session becomes eligible for
            // cache eviction again. Without this, permission-resolved sessions accumulate
            // in the cache (evictIfNeeded excludes hasPendingPermission sessions).
            sessionManager.getSession(sessionId)?.setPendingPermission(false)
        } catch (_: Exception) {
            // Network error — keep prompt open for retry
        }
    }

    /** Answer a selection question from the agent. */
    suspend fun respondQuestion(promptId: String, answers: List<List<String>>, sessionId: String) {
        val client = clientProvider() ?: return
        client.respondQuestion(promptId, answers)
    }

    /** Reject a selection question. */
    suspend fun rejectQuestion(promptId: String, sessionId: String) {
        val client = clientProvider() ?: return
        client.rejectQuestion(promptId)
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    fun startPermissionTimeout(timeoutSeconds: Int, onTimeout: () -> Unit) {
        permissionTimeoutJob?.cancel()
        if (timeoutSeconds <= 0) return
        permissionTimeoutJob = scope.launch {
            delay(timeoutSeconds * 1000L)
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
