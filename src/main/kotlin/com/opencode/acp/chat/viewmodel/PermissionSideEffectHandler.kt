package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles [SignalEffect.HandlePermissionReplied] and [SignalEffect.HandlePermissionTimedOut]
 * effects, extracted from [SignalSideEffectExecutor] for testability (council review Phase 2).
 *
 * These two handlers contain the most complex multi-branch cascade logic in the signal
 * pipeline: FIFO child-prompt dropping, timeout restart, failed-POST notification, and
 * NonCancellable reject POSTs. Extracting them into a dedicated class enables direct unit
 * testing without constructing the full 13-dependency [SignalSideEffectExecutor].
 *
 * Behavior is preserved EXACTLY from the original
 * [SignalSideEffectExecutor.handlePermissionReplied]/[handlePermissionTimedOut] methods
 * (ported from ChatViewModel.kt:358-431). Do NOT change the logic — only relocate it.
 */
class PermissionSideEffectHandler(
    private val service: OpenCodeServiceApi,
    private val project: Project,
    private val permissionViewModel: PermissionViewModel,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle a PermissionReplied SSE event. Ported from ChatViewModel.kt:358-391.
     *
     * Logic:
     * 1. If the replied permission matches the active-session prompt, clear it
     *    and cancel the active-session permission timeout.
     * 2. If the session is in failedPermissionPostSessions, notify the user
     *    that the permission was processed despite the local POST error, and
     *    remove the session from the tracking set.
     * 3. If child prompts still exist for the session:
     *    - On "reject" reply: cascade — remove all child prompts and cancel the
     *      child permission timeout.
     *    - On non-reject reply: drop only the FIRST (FIFO) prompt. If no prompts
     *      remain, cancel the timeout. If prompts remain, restart the timeout
     *      for the new first prompt.
     */
    fun handlePermissionReplied(permissionId: String, reply: String, sessionId: String) {
        // Confirm server processed our reply
        if (permissionViewModel.permissionPrompt.value?.permissionId == permissionId) {
            // Active-session permission: clear it
            permissionViewModel.setPermissionPrompt(null)
            service.permissionManager.cancelPermissionTimeout()
        }
        // Handle child prompts based on reply type.
        // Check failed POST notification regardless of whether prompts
        // were cleared by a concurrent timeout — the user must still see
        // that the server processed the failed POST (TDD §4.2.4).
        if (sessionId in permissionViewModel.failedPermissionPostSessions) {
            OpenCodeNotifications.notifyPermissionProcessedDespiteError(project, sessionId)
            permissionViewModel.failedPermissionPostSessions.remove(sessionId)
        }
        // Then handle child prompts if they still exist
        if (permissionViewModel.childPermissionPrompts.value.containsKey(sessionId)) {
            if (reply == "reject") {
                // Cascade: server rejects all pending permissions in the session
                permissionViewModel.removeChildPrompts(sessionId)
                permissionViewModel.cancelChildPermissionTimeout(sessionId)
            } else {
                // Non-reject reply: remove only the FIRST prompt (FIFO)
                val newFirstToolName = permissionViewModel.dropFirstChildPrompt(sessionId)
                if (newFirstToolName == null) {
                    // No more prompts — cancel the timeout
                    permissionViewModel.cancelChildPermissionTimeout(sessionId)
                } else {
                    // Remaining prompts — restart the timeout for the new first prompt
                    permissionViewModel.startChildPermissionTimeout(sessionId, newFirstToolName)
                }
            }
        }
    }

    /**
     * Handle a PermissionTimedOut SSE event. Ported from ChatViewModel.kt:392-431.
     *
     * Logic:
     * 1. Notify the user that the permission timed out.
     * 2. If the timeout is for the active-session permission (non-empty
     *    permissionId matching the current prompt), clear the active prompt.
     *    Child permission timeouts use permissionId="" — they must NOT clear
     *    the active session's permission prompt.
     * 3. If the session is a child session (non-empty sessionId):
     *    - For each remaining pending child prompt, POST REJECT_ONCE to the
     *      server (wrapped in NonCancellable on service.scope so the POSTs
     *      survive tool window disposal — the server's Deferred promises
     *      must be resolved).
     *    - Remove all child prompts for the session.
     *    - Cancel the child permission timeout.
     */
    fun handlePermissionTimedOut(permissionId: String, sessionId: String, toolName: String) {
        OpenCodeNotifications.notifyPermissionTimedOut(project, toolName)
        // Only clear active prompt if the timeout is for the active session's
        // permission (non-empty permissionId matching the current prompt).
        // Child permission timeouts use permissionId="" — they must NOT
        // clear the active session's permission prompt.
        if (permissionId.isNotEmpty() && permissionViewModel.permissionPrompt.value?.permissionId == permissionId) {
            permissionViewModel.setPermissionPrompt(null)
        }
        // Clear child prompts for the timed-out child session.
        // POST reject to the server for each remaining pending prompt before
        // clearing locally — the server is still waiting on Deferred promises
        // and would block indefinitely without a reply.
        if (sessionId.isNotEmpty()) {
            val pending = permissionViewModel.getChildPrompts(sessionId)
            if (pending.isNotEmpty()) {
                // Use service.scope (not ViewModel scope) so reject POSTs survive
                // tool window disposal — the server's Deferred promises must be
                // resolved even if the user closes the tool window.
                service.scope.launch {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        pending.forEach { p ->
                            try {
                                service.permissionManager.respondPermission(
                                    p.permissionId, p.toolCallId, sessionId,
                                    PermissionResponse.REJECT_ONCE,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "[ACP] Timeout reject failed for permission ${p.permissionId}" }
                            }
                        }
                    }
                }
            }
            permissionViewModel.removeChildPrompts(sessionId)
            permissionViewModel.cancelChildPermissionTimeout(sessionId)
        }
    }
}