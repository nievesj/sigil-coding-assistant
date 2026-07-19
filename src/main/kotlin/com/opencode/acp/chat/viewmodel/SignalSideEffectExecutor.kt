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

    /** Execute a single [SignalEffect]. Dispatches to the appropriate dependency. */
    private suspend fun executeEffect(effect: SignalEffect) {
        when (effect) {
            is SignalEffect.SetStreamPhaseIdle -> {
                setStreamPhaseIdle()
            }
            is SignalEffect.SetStreamPhaseIdleForSession -> {
                // sessionId-gating is handled at the ChatViewModel injection site
                // (see ChatViewModel.setStreamPhaseIdleForSession) — the executor
                // correctly passes sessionId through unchanged.
                setStreamPhaseIdleForSession(effect.sessionId)
            }
            is SignalEffect.NotifyResponseComplete -> {
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
            is SignalEffect.NotifyPermissionNeeded -> {
                OpenCodeNotifications.notifyPermissionNeeded(project)
            }
            is SignalEffect.NotifyQuestionAsked -> {
                OpenCodeNotifications.notifyQuestionAsked(project)
            }
            is SignalEffect.ComputeSessionContext -> {
                try {
                    computeSessionContext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] computeSessionContext failed after StreamingCompleted" }
                }
            }
            is SignalEffect.FetchTodos -> {
                try {
                    fetchTodos()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] fetchTodos failed after StreamingCompleted" }
                }
            }
            is SignalEffect.LoadSessions -> {
                try {
                    service.loadSessions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] loadSessions failed after StreamingCompleted" }
                }
            }
            is SignalEffect.DrainQueue -> {
                try {
                    messageQueueManager.drainQueue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] drainQueue failed after StreamingCompleted" }
                }
            }
            is SignalEffect.RefreshReviewFiles -> {
                try {
                    refreshReviewFiles()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] refreshReviewFiles failed after StreamingCompleted" }
                }
            }
            is SignalEffect.RefreshActiveSessionMessages -> {
                try {
                    service.refreshActiveSessionMessages()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] refreshActiveSessionMessages failed after SessionCompacted" }
                }
            }
            is SignalEffect.RemoveStreamingSession -> {
                service.removeStreamingSession(effect.sessionId)
            }
            is SignalEffect.StartPermissionTimeout -> {
                permissionViewModel.startPermissionTimeout()
            }
            is SignalEffect.SetPermissionPrompt -> {
                permissionViewModel.setPermissionPrompt(effect.prompt)
            }
            is SignalEffect.SetSelectionPrompt -> {
                permissionViewModel.setSelectionPrompt(effect.prompt)
            }
            is SignalEffect.AddChildPermissionPrompt -> {
                permissionViewModel.addChildPermissionPrompt(effect.prompt)
            }
            is SignalEffect.HandlePermissionReplied -> {
                permissionHandler.handlePermissionReplied(effect.permissionId, effect.reply, effect.sessionId)
            }
            is SignalEffect.HandlePermissionTimedOut -> {
                permissionHandler.handlePermissionTimedOut(effect.permissionId, effect.sessionId, effect.toolName)
            }
            is SignalEffect.EmitFileChangeSignal -> {
                emitFileChangeSignal()
            }
            is SignalEffect.ComputeSessionContextLocal -> {
                try {
                    computeSessionContextLocal()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] computeSessionContextLocal failed after MessageUpdated" }
                }
            }
        }
    }
}