package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Maps a child session's permission request to its parent session and emits
 * a [UiSignal.ChildPermissionRequested] signal for the parent's ViewModel.
 *
 * Uses [SessionManager.getParentSession] (reverse index) for O(1) lookup.
 * Falls back to scanning [_childSessionMap] if the reverse index is stale.
 *
 * Sub-agent label derivation priority:
 * 1. [SessionManager.getChildAgentLabel] (from Subtask SSE event agent field)
 * 2. "sub-agent" (fallback)
 */
class ChildPermissionRelay(
    private val sessionManager: SessionManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Map a child session's permission request to its parent session.
     * Returns a [UiSignal.ChildPermissionRequested] if the parent is found,
     * or null if the child has no known parent (orphan session).
     *
     * @param childSessionId The child session that needs permission
     * @param prompt The permission prompt from the child session
     */
    fun relayChildPermission(
        childSessionId: String,
        prompt: PermissionPrompt,
    ): UiSignal.ChildPermissionRequested? {
        val parentSessionId = sessionManager.getParentSession(childSessionId)
        if (parentSessionId == null) {
            logger.warn { "[ACP] ChildPermissionRelay: no parent found for child $childSessionId — orphan" }
            return null
        }
        // Don't relay if the child IS the active session (it's already handled via activeSignals)
        if (childSessionId == sessionManager.activeSessionId.value) {
            return null
        }

        val agentLabel = sessionManager.getChildAgentLabel(childSessionId)?.takeIf { it.isNotBlank() }
        val subAgentLabel = agentLabel ?: "sub-agent"
        val agentLabelVerified = agentLabel != null

        val childPrompt = ChildPermissionPrompt(
            childSessionId = childSessionId,
            permissionId = prompt.permissionId,
            toolCallId = prompt.toolCallId,
            toolName = prompt.toolName,
            description = prompt.description,
            patterns = prompt.patterns,
            subAgentLabel = subAgentLabel,
            agentLabelVerified = agentLabelVerified,
        )

        // Re-check activeSessionId after building the prompt to close the TOCTOU
        // window: the user may have switched to this child session between the
        // first check (above) and here. If the child is now active, the permission
        // is already handled via activeSignals — don't relay a duplicate.
        if (childSessionId == sessionManager.activeSessionId.value) {
            logger.debug { "[ACP] ChildPermissionRelay: child $childSessionId became active during relay — suppressing duplicate" }
            return null
        }

        logger.info { "[ACP] Child permission relayed: $childSessionId → $parentSessionId (tool: ${prompt.toolName}, agent: $subAgentLabel)" }
        return UiSignal.ChildPermissionRequested(childPrompt)
    }
}