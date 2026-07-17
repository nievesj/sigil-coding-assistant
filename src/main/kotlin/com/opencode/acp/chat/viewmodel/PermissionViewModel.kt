package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.SelectionResponse
import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns permission and selection prompt UI state plus their timeouts.
 *
 * Extracted from [ChatViewModel] per TDD §4.2.3. Holds:
 *  - the active-session [PermissionPrompt] StateFlow (blocking — input disabled)
 *  - the child-session [ChildPermissionPrompt] map (non-blocking, FIFO per child)
 *  - the [SelectionPrompt] StateFlow (question tool)
 *  - timeout jobs for child permission prompts (run on [OpenCodeService.scope]
 *    so they survive tool window recreation)
 *  - the failed-POST tracking set used by the [UiSignal.PermissionReplied] handler
 *
 * The active-session permission timeout is delegated to
 [OpenCodeService.permissionManager] (which owns the timeout coroutine and
 * callback). Child permission timeouts are launched here on `service.scope`
 * and emit [UiSignal.PermissionTimedOut] via `sessionManager.emitGlobalSignal`
 * so whichever ViewModel is active handles cleanup.
 *
 * @param scope The ViewModel scope — used for [respondSelection]'s coroutine.
 *   NOT used for child permission timeouts (those use [OpenCodeService.scope]
 *   to survive tool window recreation).
 */
class PermissionViewModel(
    private val scope: CoroutineScope,
    private val service: OpenCodeService,
    private val project: Project,
) {

    private val logger = KotlinLogging.logger {}

    private val _permissionPrompt = MutableStateFlow<PermissionPrompt?>(null)
    val permissionPrompt: StateFlow<PermissionPrompt?> = _permissionPrompt.asStateFlow()

    /** Child session permission prompts — non-blocking, keyed by child session ID.
     *  Supports multiple simultaneous pending permissions per child (FIFO list). */
    private val _childPermissionPrompts =
        MutableStateFlow<Map<String, List<ChildPermissionPrompt>>>(emptyMap())
    val childPermissionPrompts: StateFlow<Map<String, List<ChildPermissionPrompt>>> =
        _childPermissionPrompts.asStateFlow()

    private val _selectionPrompt = MutableStateFlow<SelectionPrompt?>(null)
    val selectionPrompt: StateFlow<SelectionPrompt?> = _selectionPrompt.asStateFlow()

    /** Timeout jobs for child permission prompts, keyed by child session ID. */
    private val childPermissionTimeoutJobs = ConcurrentHashMap<String, Job>()

    /** Session IDs where the last permission POST failed (rolled back to pending).
     *  Used by the PermissionReplied handler to detect that the server DID process
     *  the response despite a local network error, and surface a notification. */
    val failedPermissionPostSessions: ConcurrentHashMap.KeySetView<String, Boolean> =
        ConcurrentHashMap.newKeySet()

    // ── Active-session permission ──────────────────────────────────────────

    /** Set the active-session permission prompt (from [UiSignal.PermissionRequested]). */
    fun setPermissionPrompt(prompt: PermissionPrompt?) {
        _permissionPrompt.value = prompt
    }

    /** Start the active-session permission timeout. Reads the timeout from
     *  [OpenCodeSettingsState] at call time — if the user changes the setting
     *  while a prompt is pending, the current timeout is NOT restarted; the
     *  new value applies to the next prompt. */
    fun startPermissionTimeout() {
        val toolName = _permissionPrompt.value?.toolName ?: ""
        // Capture permissionId at START time, not at fire time.
        // If a new permission prompt arrives before this timeout fires, the
        // callback must NOT clear the new prompt — only the one it was started for.
        val capturedPermId = _permissionPrompt.value?.permissionId ?: ""
        service.permissionManager.startPermissionTimeout(
            timeoutSeconds = OpenCodeSettingsState.getInstance().state.permissionTimeoutSeconds,
            toolName = toolName,
        ) {
            // Only clear the prompt if it's still the one we were started for.
            // A newer prompt may have replaced this one before the timeout fired.
            if (_permissionPrompt.value?.permissionId == capturedPermId) {
                _permissionPrompt.value = null
            }
            OpenCodeNotifications.notifyPermissionTimedOut(project, toolName)
            logger.info { "[ACP] Permission timed out: permissionId=$capturedPermId, tool=$toolName" }
        }
    }

    /** Respond to the active-session permission prompt. On POST failure, keeps
     *  the prompt open for retry and tracks the session in
     *  [failedPermissionPostSessions]. */
    suspend fun respondPermission(
        response: PermissionResponse,
        agentName: String,
    ) {
        val prompt = _permissionPrompt.value ?: return
        try {
            service.respondPermission(
                prompt.permissionId, prompt.toolCallId, prompt.sessionId, response,
                toolName = prompt.toolName,
                patterns = prompt.patterns,
                agentName = agentName,
            )
            // POST succeeded — clear any prior failed-post tracking
            failedPermissionPostSessions.remove(prompt.sessionId)
            _permissionPrompt.value = null
            service.permissionManager.cancelPermissionTimeout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Permission response failed (network error, server down). Keep the
            // prompt open so the user can retry. Track the failure so the
            // PermissionReplied handler can surface a notification if the server
            // still processed it despite the network error (TDD §4.2.4).
            failedPermissionPostSessions.add(prompt.sessionId)
            logger.warn(e) { "[ACP] Permission response failed — keeping prompt open for retry" }
        }
    }

    // ── Child-session permission ───────────────────────────────────────────

    /** Add a child permission prompt (from [UiSignal.ChildPermissionRequested]). */
    fun addChildPermissionPrompt(prompt: ChildPermissionPrompt) {
        val childId = prompt.childSessionId
        _childPermissionPrompts.update { prompts ->
            val existing = prompts[childId] ?: emptyList()
            prompts + (childId to (existing + prompt))
        }
        startChildPermissionTimeout(childId, prompt.toolName)
    }

    /** Respond to the FIRST (FIFO) pending child permission prompt for [childSessionId].
     *  On REJECT_ONCE, cascades and clears ALL prompts for the child session
     *  (server rejects all pending permissions in the session). */
    suspend fun respondChildPermission(childSessionId: String, response: PermissionResponse) {
        val prompts = _childPermissionPrompts.value[childSessionId] ?: return
        val prompt = prompts.first()  // FIFO — respond to oldest first
        try {
            service.permissionManager.respondPermission(
                prompt.permissionId,
                prompt.toolCallId,
                childSessionId,  // reply goes to the CHILD session
                response,
                prompt.toolName,
                prompt.patterns,
                // Only pass the real agent name for config sync when the label was
                // verified from a Subtask SSE event. If the label is the fallback
                // "sub-agent" (Subtask event missed), pass empty string so
                // writeAlwaysAllowRule is skipped (toolName.isNotEmpty() guard
                // in PermissionManager + isValidConfigKey in McpConfigWriter).
                if (prompt.agentLabelVerified) prompt.subAgentLabel else "",
            )
            // POST succeeded — clear any prior failed-post tracking
            failedPermissionPostSessions.remove(childSessionId)
            if (response == PermissionResponse.REJECT_ONCE) {
                // CASCADE: clear ALL prompts for this child session
                // (server rejects all pending permissions in the session)
                _childPermissionPrompts.update { it - childSessionId }
                cancelChildPermissionTimeout(childSessionId)
                logger.info { "[ACP] Cascade rejection: clearing all prompts for childSessionId=$childSessionId" }
            } else {
                // Remove just this prompt; keep others if any
                _childPermissionPrompts.update { prompts ->
                    val remaining = (prompts[childSessionId] ?: emptyList()).drop(1)
                    if (remaining.isEmpty()) prompts - childSessionId
                    else prompts + (childSessionId to remaining)
                }
                if (!_childPermissionPrompts.value.containsKey(childSessionId)) {
                    // No more prompts — cancel the timeout
                    cancelChildPermissionTimeout(childSessionId)
                } else {
                    // Remaining prompts — restart the timeout for the new first prompt
                    val newFirst = _childPermissionPrompts.value[childSessionId]?.firstOrNull()
                    if (newFirst != null) {
                        startChildPermissionTimeout(childSessionId, newFirst.toolName)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // POST failed — track this session so the PermissionReplied handler
            // can surface a notification if the server still processed it.
            failedPermissionPostSessions.add(childSessionId)
            logger.warn(e) { "[ACP] Child permission response failed — keeping prompt open" }
        }
    }

    /** Start a child permission timeout. Uses [OpenCodeService.scope] (not the
     *  ViewModel scope) so the timeout fires even if the tool window is closed
     *  and reopened. Emits [UiSignal.PermissionTimedOut] via globalSignals so
     *  whichever ViewModel is active handles cleanup. */
    fun startChildPermissionTimeout(childSessionId: String, toolName: String) {
        val timeoutSeconds = ChatConstants.CHILD_PERMISSION_TIMEOUT_SECONDS
        childPermissionTimeoutJobs[childSessionId]?.cancel()
        childPermissionTimeoutJobs[childSessionId] = service.scope.launch {
            delay(timeoutSeconds * 1000L)
            // Clear the prompt and notify — emit via globalSignals so the
            // active ViewModel (which may be a different instance if the tool
            // window was recreated) handles cleanup.
            service.sessionManager.emitGlobalSignal(
                UiSignal.PermissionTimedOut(
                    permissionId = "",
                    sessionId = childSessionId,
                    toolName = toolName,
                )
            )
            childPermissionTimeoutJobs.remove(childSessionId)
            logger.info { "[ACP] Child permission timed out: childSessionId=$childSessionId, tool=$toolName" }
        }
    }

    fun cancelChildPermissionTimeout(childSessionId: String) {
        childPermissionTimeoutJobs.remove(childSessionId)?.cancel()
    }

    /** Remove all child prompts for [childSessionId] (e.g., on reject cascade
     *  from [UiSignal.PermissionReplied]). */
    fun removeChildPrompts(childSessionId: String) {
        _childPermissionPrompts.update { it - childSessionId }
    }

    /** Drop the FIRST (FIFO) prompt for [childSessionId] and restart the timeout
     *  for the next one if any remain. Used by the [UiSignal.PermissionReplied]
     *  handler for non-reject replies. Returns the new first prompt's toolName
     *  (so the caller can restart the timeout), or null if no prompts remain. */
    fun dropFirstChildPrompt(childSessionId: String): String? {
        var newFirstToolName: String? = null
        _childPermissionPrompts.update { prompts ->
            val remaining = (prompts[childSessionId] ?: emptyList()).drop(1)
            newFirstToolName = remaining.firstOrNull()?.toolName
            if (remaining.isEmpty()) prompts - childSessionId
            else prompts + (childSessionId to remaining)
        }
        return newFirstToolName
    }

    /** Get the current pending child prompts for [childSessionId] (read-only). */
    fun getChildPrompts(childSessionId: String): List<ChildPermissionPrompt> =
        _childPermissionPrompts.value[childSessionId] ?: emptyList()

    // ── Selection (question tool) ──────────────────────────────────────────

    /** Set the selection prompt (from [UiSignal.SelectionRequested]). */
    fun setSelectionPrompt(prompt: SelectionPrompt?) {
        _selectionPrompt.value = prompt
    }

    /** Respond to the selection prompt. Merges selected labels and custom input
     *  into a single inner list per the server's expected format. Clears the
     *  prompt on success or failure (SessionState already cleared its
     *  pendingSelection on failure). */
    fun respondSelection(response: SelectionResponse) {
        val prompt = _selectionPrompt.value ?: return
        scope.launch {
            try {
                val selectedLabels = response.selectedIndices.mapNotNull { idx ->
                    prompt.options.getOrNull(idx)?.label ?: run {
                        logger.warn { "[ACP] respondSelection: index $idx out of bounds (options size=${prompt.options.size}) — selection may be stale" }
                        null
                    }
                }
                // The server expects one inner array per question. Merge custom input
                // into the SAME inner list as selected labels (not a separate array),
                // producing [[label1, label2, customInput]] instead of
                // [[label1, label2], [customInput]].
                val answers = mutableListOf<List<String>>()
                val combined = selectedLabels.toMutableList()
                response.customInput?.let { custom ->
                    if (custom.isNotBlank()) combined.add(custom)
                }
                if (combined.isNotEmpty()) {
                    answers.add(combined)
                }
                if (answers.isEmpty()) {
                    service.rejectQuestion(prompt.promptId, prompt.sessionId)
                } else {
                    service.respondQuestion(prompt.promptId, answers, prompt.sessionId)
                }
                _selectionPrompt.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to respond to question ${prompt.promptId}" }
                // Clear the prompt so the UI doesn't show a stale prompt that can't
                // be retried — SessionState already cleared its pendingSelection.
                _selectionPrompt.value = null
            }
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    /** Cancel all child permission timeout jobs and clear prompt state.
     *  Called from [ChatViewModel.close]. The active-session permission timeout
     *  is cancelled separately via [OpenCodeService.permissionManager]. */
    fun close() {
        childPermissionTimeoutJobs.values.forEach { it.cancel() }
        childPermissionTimeoutJobs.clear()
        // Clear permission prompts to prevent stale StateFlow values lingering
        // in the old ViewModel after tool window recreation/disposal.
        _permissionPrompt.value = null
        _childPermissionPrompts.value = emptyMap()
    }
}