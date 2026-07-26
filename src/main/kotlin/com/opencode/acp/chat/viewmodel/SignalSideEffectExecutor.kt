package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Collects [SignalEffect] from [SignalRouter] and runs each with the
 * injected dependencies. Each [SignalEffect] side effect is independently
 * try/caught so one failure doesn't skip the others.
 *
 * Preserves the independent try/catch wrapping for StreamingCompleted side
 * effects: `computeSessionContext() → fetchTodos() → loadSessions() →
 * drainQueue() → refreshReviewFiles() → notifyResponseComplete`.
 *
 * The 5th side effect, [SignalEffect.RefreshReviewFiles], was outside the
 * try/catch chain in the original ChatViewModel code. It is now wrapped in
 * its own try/catch here so a failure doesn't break subsequent emissions
 * (though it's the last in the chain, the wrapping is still important for
 * log visibility).
 *
 * @param service The OpenCode service API — used for loadSessions,
 *   refreshActiveSessionMessages, removeStreamingSession, permissionManager,
 *   scope (for non-cancellable POSTs).
 * @param project The IntelliJ project — used for [OpenCodeNotifications].
 * @param permissionViewModel The permission view model — owns prompt state
 *   and timeouts.
 * @param messageQueueManager The message queue manager — owns drainQueue.
 * @param refreshReviewFiles Callback to re-read .review/ JSON files from disk.
 *   Returns a [kotlinx.coroutines.Job] (or [Any]) — the return value is
 *   ignored by the executor.
 * @param computeSessionContext Callback to recompute session context (REST).
 * @param fetchTodos Callback to fetch the todo list.
 * @param computeSessionContextLocal Callback for local-only context recompute.
 * @param setStreamPhaseIdle Callback to set _streamPhase = IDLE (active session).
 * @param setStreamPhaseIdleForSession Callback to set _streamPhase = IDLE
 *   (specific session — used by SessionError).
 * @param emitFileChangeSignal Callback to emit the file-change signal.
 * @param isActiveSessionChild Provider for "is the active session a child
 *   session?" — gates [SignalEffect.NotifyResponseComplete].
 * @param isActiveMessage Provider for "is this message in the active
 *   session?" — gates [SignalEffect.NotifyResponseComplete].
 * @param scope The ViewModel scope — used to launch the collect coroutine
 *   and the StreamingCompleted side-effect coroutine.
 */
class SignalSideEffectExecutor(
    private val service: OpenCodeServiceApi,
    private val project: Project,
    private val permissionViewModel: PermissionViewModel,
    private val messageQueueManager: MessageQueueManager,
    private val refreshReviewFiles: () -> Any,
    private val computeSessionContext: suspend () -> Unit,
    private val fetchTodos: suspend () -> Unit,
    private val computeSessionContextLocal: suspend () -> Unit,
    private val setStreamPhaseIdle: () -> Unit,
    private val setStreamPhaseIdleForSession: (String) -> Unit,
    private val emitFileChangeSignal: () -> Any,
    private val isActiveSessionChild: () -> Boolean,
    private val isActiveMessage: (String) -> Boolean,
    private val scope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger {}

    private val permissionHandler = PermissionSideEffectHandler(service, project, permissionViewModel)

    private var collectJob: kotlinx.coroutines.Job? = null

    /**
     * Timeout for REST-calling side effects. Prevents a half-open TCP connection
     * (server unresponsive but TCP alive) from freezing the entire signal pipeline
     * indefinitely. Effects are processed sequentially, so one hung REST call would
     * block ALL subsequent effect processing — blocking signal routing, which
     * blocks SSE event handling. [withTimeoutOrNull] returns null on timeout,
     * which is logged as a warning; the collect coroutine continues.
     */
    private val effectTimeoutMs: Long = 30_000L

    /**
     * Start collecting [SignalEffect] from [effects] and executing each.
     *
     * Safe to call multiple times: any existing collect job is cancelled and joined
     * before launching a new one. The join() ensures in-flight [executeEffect] calls
     * complete before the new collector starts, preventing duplicate effect
     * processing from overlapping old and new collectors.
     *
     * Backpressure: effects are processed sequentially. If a side effect blocks
     * (e.g., loadSessions() on a slow HTTP response), subsequent effects queue in
     * the SharedFlow buffer (capacity 256). If the buffer fills, the router's
     * _effects.emit() suspends, back-pressuring the SSE event pipeline. This is
     * intentional — ordered processing is required for the StreamingCompleted
     * side-effect chain. REST-calling effects are wrapped in [withTimeoutOrNull]
     * (see [effectTimeoutMs]) so a half-open TCP connection cannot block the
     * pipeline indefinitely.
     */
    fun start(effects: Flow<SignalEffect>) {
        // Cancel+join any existing collect job before launching a new one.
        // The join() ensures in-flight executeEffect calls complete before the
        // new collector starts, preventing duplicate effect processing from
        // overlapping old and new collectors. Runs in a scope.launch so the
        // suspend join() is in a coroutine context and the relaunch happens
        // after join completes.
        scope.launch {
            collectJob?.cancel()
            collectJob?.join()
            collectJob = scope.launch {
                effects.collect { effect -> executeEffect(effect) }
            }
        }
    }

    /** Stop collecting effects. Cancels the collect job. */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * Run a side-effect block with standard error handling: re-throw CancellationException,
     * log other exceptions as warnings. This ensures one failing side effect doesn't skip
     * subsequent ones, and that coroutine cancellation propagates correctly.
     *
     * @param name Human-readable effect name for log messages. Include the triggering
     *   context where helpful (e.g., "loadSessions [HandleSessionDeleted]") to distinguish
     *   effects triggered by different signals.
     * @param securityRelevant When true, the effect is security-relevant (e.g., permission
     *   enforcement) and failures are logged at ERROR level instead of WARN. ERROR-level
     *   logs are visible in idea.log under the default INFO log level, ensuring security
     *   effect failures are not silently buried. A dedicated UI notification (balloon) for
     *   security effect failures is NOT yet implemented — TODO: add a visible notification
     *   via NotificationGroupManager so the user is alerted that permission enforcement may
     *   be incomplete. For now, ERROR-level logging is the immediate improvement.
     */
    private suspend fun runEffect(name: String, securityRelevant: Boolean = false, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (securityRelevant) {
                logger.error(e) { "[ACP] SECURITY side-effect '$name' failed — permission enforcement may be incomplete" }
                // Surface a user-visible notification so the user knows permission enforcement
                // failed. Without this, the user believes a tool was denied/allowed/timed-out
                // but the server never received the decision — the permission may remain
                // pending indefinitely. Uses invokeLater because this may run on a coroutine
                // background thread and Notification.notify() requires the EDT.
                notifySecurityEffectFailure(name)
            } else {
                logger.warn(e) { "[ACP] side-effect '$name' failed" }
            }
        }
    }

    /** Show a balloon notification for a security-relevant effect failure. */
    private fun notifySecurityEffectFailure(name: String) {
        try {
            ApplicationManager.getApplication().invokeLater {
                Notification(
                    "Sigil",
                    "Sigil",
                    "Permission enforcement failed for: $name. The server may not have received the decision. Check idea.log for details.",
                    NotificationType.WARNING,
                ).notify(project)
            }
        } catch (e: Exception) {
            // Notification system may not be available (e.g., during disposal). Log and continue.
            logger.warn(e) { "[ACP] Failed to show security effect failure notification for '$name'" }
        }
    }

    /** Execute a single [SignalEffect]. Dispatches to the appropriate dependency. */
    private suspend fun executeEffect(effect: SignalEffect) {
        when (effect) {
            is SignalEffect.SetStreamPhaseIdle -> runEffect("setStreamPhaseIdle") {
                // UNGATED — used by StreamingCompleted. The messageId is the message that
                // just finished streaming, so the phase must reset to IDLE regardless of
                // whether the user switched sessions between signal emission and effect
                // execution. Without this, a session switch during the effect window would
                // leave _streamPhase stuck at STREAMING (perpetual Stop button, no streaming).
                setStreamPhaseIdle()
            }
            is SignalEffect.SetStreamPhaseIdleGated -> runEffect("setStreamPhaseIdleGated") {
                // GATED on isActiveMessage — used by Error backstop. Only reset the active
                // session's stream phase if the error was for a message in the active session.
                // This prevents a background Error (messageId not in the active session) from
                // forcing IDLE while the active session is legitimately streaming for a
                // different message.
                if (isActiveMessage(effect.messageId)) {
                    setStreamPhaseIdle()
                }
            }
            is SignalEffect.SetStreamPhaseIdleForSession -> runEffect("setStreamPhaseIdleForSession") {
                // sessionId-gating is handled at the ChatViewModel injection site
                // (see ChatViewModel.setStreamPhaseIdleForSession) — the executor
                // correctly passes sessionId through unchanged.
                setStreamPhaseIdleForSession(effect.sessionId)
            }
            is SignalEffect.NotifyResponseComplete -> runEffect("notifyResponseComplete") {
                // Three gates (mirrors original ChatViewModel.kt:247-251):
                //  1. naturalCompletion — already checked by SignalRouter
                //     (only emits this effect when naturalCompletion=true).
                //  2. !isActiveSessionChild — checked here.
                //  3. isActiveMessage — checked here.
                // NOTE: isActiveMessage checks the ACTIVE session's messages. If the user
                // switched sessions between StreamingCompleted signal emission and effect
                // execution, isActiveMessage returns false and the notification is suppressed.
                // This is intentional: the notification is scoped to the active session, so if
                // the user switched away, suppression is the desired behavior.
                if (!isActiveSessionChild() && isActiveMessage(effect.messageId)) {
                    OpenCodeNotifications.notifyResponseComplete(project)
                }
            }
            is SignalEffect.NotifyPermissionNeeded -> runEffect("notifyPermissionNeeded") {
                OpenCodeNotifications.notifyPermissionNeeded(project)
            }
            is SignalEffect.NotifyQuestionAsked -> runEffect("notifyQuestionAsked") {
                OpenCodeNotifications.notifyQuestionAsked(project)
            }
            is SignalEffect.ComputeSessionContext -> runEffect("computeSessionContext") {
                withTimeoutOrNull(effectTimeoutMs) { computeSessionContext() }
                    ?: logger.warn { "[ACP] computeSessionContext timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.FetchTodos -> runEffect("fetchTodos") {
                withTimeoutOrNull(effectTimeoutMs) { fetchTodos() }
                    ?: logger.warn { "[ACP] fetchTodos timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.LoadSessions -> runEffect("loadSessions") {
                withTimeoutOrNull(effectTimeoutMs) { service.loadSessions() }
                    ?: logger.warn { "[ACP] loadSessions timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.DrainQueue -> runEffect("drainQueue") {
                messageQueueManager.drainQueue()
            }
            is SignalEffect.RefreshReviewFiles -> runEffect("refreshReviewFiles") {
                refreshReviewFiles()
            }
            is SignalEffect.RefreshActiveSessionMessages -> runEffect("refreshActiveSessionMessages") {
                withTimeoutOrNull(effectTimeoutMs) { service.refreshActiveSessionMessages() }
                    ?: logger.warn { "[ACP] refreshActiveSessionMessages timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.HandleSessionDeleted -> runEffect("loadSessions [HandleSessionDeleted]") {
                // loadSessions() refreshes the sidebar; SessionManager.processEvent
                // already evicted the cache and switched active session if needed.
                withTimeoutOrNull(effectTimeoutMs) { service.loadSessions() }
                    ?: logger.warn { "[ACP] loadSessions [HandleSessionDeleted] timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.RemoveStreamingSession -> runEffect("removeStreamingSession") {
                service.removeStreamingSession(effect.sessionId)
            }
            is SignalEffect.StartPermissionTimeout -> runEffect("startPermissionTimeout") {
                permissionViewModel.startPermissionTimeout()
            }
            is SignalEffect.SetPermissionPrompt -> runEffect("setPermissionPrompt") {
                permissionViewModel.setPermissionPrompt(effect.prompt)
            }
            is SignalEffect.SetSelectionPrompt -> runEffect("setSelectionPrompt") {
                permissionViewModel.setSelectionPrompt(effect.prompt)
            }
            is SignalEffect.AddChildPermissionPrompt -> runEffect("addChildPermissionPrompt") {
                permissionViewModel.addChildPermissionPrompt(effect.prompt)
            }
            is SignalEffect.HandlePermissionReplied -> runEffect("handlePermissionReplied", securityRelevant = true) {
                withTimeoutOrNull(effectTimeoutMs) {
                    permissionHandler.handlePermissionReplied(effect.permissionId, effect.reply, effect.sessionId)
                } ?: logger.warn { "[ACP] handlePermissionReplied timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.HandlePermissionTimedOut -> runEffect("handlePermissionTimedOut", securityRelevant = true) {
                withTimeoutOrNull(effectTimeoutMs) {
                    permissionHandler.handlePermissionTimedOut(effect.permissionId, effect.sessionId, effect.toolName)
                } ?: logger.warn { "[ACP] handlePermissionTimedOut timed out after ${effectTimeoutMs}ms" }
            }
            is SignalEffect.EmitFileChangeSignal -> runEffect("emitFileChangeSignal") {
                emitFileChangeSignal()
            }
            is SignalEffect.ComputeSessionContextLocal -> runEffect("computeSessionContextLocal") {
                computeSessionContextLocal()
            }
            is SignalEffect.LogSessionError -> runEffect("logSessionError") {
                // Log the session error message at WARN level for idea.log visibility.
                // Preserves the errorMessage field that was previously discarded by the router.
                val msg = effect.errorMessage ?: "(no error message)"
                logger.warn { "[ACP] Session error: session=${effect.sessionId}, error=$msg" }
            }
        }
    }
}