package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
     * Start collecting [SignalEffect] from [effects] and executing each.
     */
    fun start(effects: Flow<SignalEffect>) {
        collectJob = scope.launch {
            effects.collect { effect -> executeEffect(effect) }
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
     */
    private suspend fun runEffect(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] side-effect '$name' failed" }
        }
    }

    /** Execute a single [SignalEffect]. Dispatches to the appropriate dependency. */
    private suspend fun executeEffect(effect: SignalEffect) {
        when (effect) {
            is SignalEffect.SetStreamPhaseIdle -> runEffect("setStreamPhaseIdle") {
                setStreamPhaseIdle()
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
                computeSessionContext()
            }
            is SignalEffect.FetchTodos -> runEffect("fetchTodos") {
                fetchTodos()
            }
            is SignalEffect.LoadSessions -> runEffect("loadSessions") {
                service.loadSessions()
            }
            is SignalEffect.DrainQueue -> runEffect("drainQueue") {
                messageQueueManager.drainQueue()
            }
            is SignalEffect.RefreshReviewFiles -> runEffect("refreshReviewFiles") {
                refreshReviewFiles()
            }
            is SignalEffect.RefreshActiveSessionMessages -> runEffect("refreshActiveSessionMessages") {
                service.refreshActiveSessionMessages()
            }
            is SignalEffect.HandleSessionDeleted -> runEffect("loadSessions [HandleSessionDeleted]") {
                // loadSessions() refreshes the sidebar; SessionManager.processEvent
                // already evicted the cache and switched active session if needed.
                service.loadSessions()
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
            is SignalEffect.HandlePermissionReplied -> runEffect("handlePermissionReplied") {
                permissionHandler.handlePermissionReplied(effect.permissionId, effect.reply, effect.sessionId)
            }
            is SignalEffect.HandlePermissionTimedOut -> runEffect("handlePermissionTimedOut") {
                permissionHandler.handlePermissionTimedOut(effect.permissionId, effect.sessionId, effect.toolName)
            }
            is SignalEffect.EmitFileChangeSignal -> runEffect("emitFileChangeSignal") {
                emitFileChangeSignal()
            }
            is SignalEffect.ComputeSessionContextLocal -> runEffect("computeSessionContextLocal") {
                computeSessionContextLocal()
            }
        }
    }
}