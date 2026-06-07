package com.opencode.acp.chat.service

import com.agentclientprotocol.model.ToolCallStatus
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.MessageProcessorManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*

/**
 * Manages permission prompts and question/selection interactions.
 */
class PermissionManager(
    private val scope: CoroutineScope,
    private val clientProvider: () -> OpenCodeClient?,
    private val processor: MessageProcessorManager,
) {

    private val logger = KotlinLogging.logger {}
    private var permissionTimeoutJob: Job? = null

    /** Respond to a permission prompt. */
    suspend fun respondPermission(permissionId: String, toolCallId: String, response: PermissionResponse) {
        val client = clientProvider() ?: return
        try {
            client.respondPermission(permissionId = permissionId, response = response.optionId)
            when (response) {
                PermissionResponse.REJECT_ONCE ->
                    processor.setToolPartState(toolCallId, PartState.Rejected)
                PermissionResponse.ALLOW_ONCE,
                PermissionResponse.ALLOW_ALWAYS ->
                    processor.updateToolCallStatus(toolCallId, ToolCallStatus.IN_PROGRESS)
            }
        } catch (_: Exception) {
            // Network error — keep prompt open for retry
        }
    }

    /** Answer a selection question from the agent. */
    suspend fun respondQuestion(promptId: String, answers: List<List<String>>) {
        val client = clientProvider() ?: return
        client.respondQuestion(promptId, answers)
    }

    /** Reject a selection question. */
    suspend fun rejectQuestion(promptId: String) {
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
