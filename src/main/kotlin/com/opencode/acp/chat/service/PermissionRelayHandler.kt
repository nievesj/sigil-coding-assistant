package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Handles child-session permission requests collected from the global
 * [SessionManager.allSessionSignals] flow.
 *
 * Extracted from [OpenCodeService.startGlobalSignalCollection] to make the
 * Brave Mode relay-point auto-approve and orphan-retry logic unit-testable
 * without instantiating the full [OpenCodeService] (which has many heavy
 * dependencies: ProcessManager, McpManager, AttachmentValidator, etc.).
 *
 * Two modes:
 *
 * 1. **Brave Mode ON** — auto-approves child permissions at the relay point
 *    by calling [PermissionManager.respondPermission] with [PermissionResponse.ALLOW_ONCE],
 *    bypassing the relay entirely. If the auto-approve POST fails, falls back to
 *    the relay path so the prompt surfaces in the UI.
 *
 * 2. **Brave Mode OFF** — relays the prompt to the parent session via
 *    [ChildPermissionRelay.relayChildPermission]. If the parent mapping is not
 *    yet populated (orphan), triggers [SessionManager.loadSessions] and retries
 *    once. If the retry also fails, surfaces a synthetic
 *    [UiSignal.ChildPermissionRequested] to the UI as a fallback so the user
 *    can still approve the prompt (instead of silently dropping it and hanging
 *    the subtask).
 *
 * Active-session permission requests are NOT handled here — they are routed
 * via [SessionManager.activeSignals] to the [PermissionViewModel]. This handler
 * skips them (see [handlePermissionRequested]).
 *
 * @param scope Coroutine scope for the orphan-retry background launch.
 * @param sessionManager Provides parent mapping, session list, and global signal emission.
 * @param permissionManager Used for Brave Mode auto-approve POST.
 * @param childPermissionRelay Maps child → parent and builds [ChildPermissionPrompt].
 * @param braveModeProvider Returns the current Brave Mode setting. Defaults to reading
 *   [com.opencode.acp.config.settings.OpenCodeFollowSettingsState.getInstance].
 *   Tests inject a lambda to avoid the IntelliJ static call.
 */
class PermissionRelayHandler(
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val permissionManager: PermissionManager,
    private val childPermissionRelay: ChildPermissionRelay,
    private val braveModeProvider: () -> Boolean = {
        com.opencode.acp.config.settings.OpenCodeFollowSettingsState.getInstance().braveModeEnabled
    },
) {

    /** Tracks in-flight relay attempts per sessionId to prevent double-emission
     *  when Brave Mode POST fails and falls back to relay concurrently. */
    private val inFlightRelays = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Handle a [UiSignal.PermissionRequested] signal from the global
     * [SessionManager.allSessionSignals] flow.
     *
     * @param sessionId The session that emitted the permission request.
     * @param signal The permission-requested signal.
     * @return `true` if the signal was handled (active-session skip, Brave Mode
     *   auto-approve, or relay); `false` if it should be handled by the caller.
     */
    fun handlePermissionRequested(sessionId: String, signal: UiSignal.PermissionRequested): Boolean {
        // If sessionId == activeSessionId, it's already handled via activeSignals — skip.
        if (sessionId == sessionManager.activeSessionId.value) return false

        // Brave Mode: auto-approve child permissions at the relay point.
        // This bypasses the relay entirely — no parent mapping needed, no orphan drop.
        // The server still enforces explicit deny rules before sending the SSE event,
        // so Brave Mode cannot override hard denials.
        if (braveModeProvider()) {
            logger.warn { "[ACP] Brave Mode: auto-approving child permission ${signal.prompt.permissionId} at relay point (tool=${signal.prompt.toolName}, session=$sessionId)" }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(NonCancellable) {
                        // Timeout the POST so a hung network doesn't leak this coroutine forever.
                        // NonCancellable ensures the server's Deferred promise gets a response even
                        // if the scope is cancelled, but the timeout prevents indefinite blocking.
                        kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                            permissionManager.respondPermission(
                                permissionId = signal.prompt.permissionId,
                                toolCallId = signal.prompt.toolCallId,
                                sessionId = sessionId,
                                response = PermissionResponse.ALLOW_ONCE,
                                toolName = signal.prompt.toolName,
                                patterns = signal.prompt.patterns,
                            )
                        } ?: run {
                            logger.warn { "[ACP] Brave Mode relay-point auto-approve timed out after 30s for ${signal.prompt.permissionId} — falling back to relay" }
                            throw RuntimeException("Auto-approve POST timed out after 30s")
                        }
                    }
                } catch (e: CancellationException) {
                    logger.info { "[ACP] Brave Mode relay-point auto-approve cancelled for ${signal.prompt.permissionId}" }
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Brave Mode relay-point auto-approve failed for ${signal.prompt.permissionId} — falling back to relay" }
                    // Fall back to the relay path so the prompt surfaces in the UI
                    relayChildPermissionWithOrphanRetry(sessionId, signal.prompt)
                }
            }
            return true
        }

        // Non-Brave-Mode path: relay to parent session
        relayChildPermissionWithOrphanRetry(sessionId, signal.prompt)
        return true
    }

    /**
     * Relay a child session's permission prompt to its parent session UI.
     *
     * If the parent mapping is not yet populated (orphan — happens when a subtask
     * requests permission before `loadSessions()` has refreshed the child→parent
     * reverse index), triggers `loadSessions()` and retries once.
     *
     * If the retry STILL fails, surfaces the prompt to the UI as a fallback via
     * [UiSignal.ChildPermissionRequested] with a synthetic [ChildPermissionPrompt]
     * (using the child's own sessionId as `childSessionId` and a fallback
     * "sub-agent" label). This ensures the user ALWAYS sees the prompt and can
     * approve it, even if the parent mapping can't be resolved — instead of the
     * previous behavior of silently dropping the prompt and hanging the subtask.
     */
    internal fun relayChildPermissionWithOrphanRetry(
        sessionId: String,
        prompt: PermissionPrompt,
    ) {
        // Prevent double-emission: if a relay is already in-flight for this session
        // (e.g., Brave Mode POST failed and fell back to relay), skip this call.
        if (!inFlightRelays.add(sessionId)) {
            logger.info { "[ACP] relayChildPermissionWithOrphanRetry: relay already in-flight for $sessionId — skipping" }
            return
        }
        try {
            val relayed = childPermissionRelay.relayChildPermission(sessionId, prompt)
            if (relayed != null) {
                sessionManager.emitGlobalSignal(relayed)
                sessionManager.markChildPendingPermission(sessionId)
                return
            }
            // Orphan: the child's parent mapping is not yet populated.
            // This happens when a subtask requests permission before
            // loadSessions() has refreshed the child→parent reverse index.
            // The Subtask SSE event carries the PARENT's sessionId (the
            // session owning the message with the subtask part), NOT the
            // child's — so the reverse index can't be populated from it.
            // Trigger loadSessions() to fetch the child's SessionItem
            // (which has parentID) and populate the reverse index, then
            // retry the relay.
            logger.info { "[ACP] Orphan permission for session $sessionId — triggering loadSessions() to populate parent mapping" }
            scope.launch {
                var loadSucceeded = false
                try {
                    sessionManager.loadSessions()
                    loadSucceeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] loadSessions() failed during orphan permission retry for $sessionId" }
                }
                val retryRelayed = childPermissionRelay.relayChildPermission(sessionId, prompt)
                if (retryRelayed != null) {
                    sessionManager.emitGlobalSignal(retryRelayed)
                    sessionManager.markChildPendingPermission(sessionId)
                } else {
                    // Fallback: still no parent after loadSessions(). Surface the prompt
                    // to the UI as a ChildPermissionRequested with a synthetic prompt so
                    // the user can approve it, instead of silently dropping it (which
                    // would hang the subtask with no way to approve).
                    val label = if (loadSucceeded) "sub-agent" else "sub-agent (load failed)"
                    logger.warn { "[ACP] Orphan permission for $sessionId — still no parent after loadSessions() (loadSucceeded=$loadSucceeded), surfacing to UI as fallback" }
                    val fallbackPrompt = ChildPermissionPrompt(
                        childSessionId = sessionId,
                        permissionId = prompt.permissionId,
                        toolCallId = prompt.toolCallId,
                        toolName = prompt.toolName,
                        description = prompt.description,
                        patterns = prompt.patterns,
                        subAgentLabel = label,
                        agentLabelVerified = false,
                    )
                    sessionManager.emitGlobalSignal(UiSignal.ChildPermissionRequested(fallbackPrompt))
                    sessionManager.markChildPendingPermission(sessionId)
                }
            }
        } finally {
            inFlightRelays.remove(sessionId)
        }
    }
}