package com.opencode.acp.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.opencode.acp.chat.model.ChatConstants
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicLong

/**
 * IDE-level notifications for the OpenCode plugin.
 *
 * Uses IntelliJ's [Notification] API to show balloon notifications when:
 * - The LLM finishes a response (only if the IDE window is not focused)
 * - The LLM asks a question that needs user input (always)
 * - The LLM requests permission for a tool (always)
 *
 * Notifications include an "Open" action that focuses the OpenCode tool window.
 * When the IDE is not focused, [requestWindowAttention] plays a system beep
 * to alert the user without forcibly stealing focus.
 */
object OpenCodeNotifications {

    private const val GROUP_ID = "Sigil"

    /** Minimum interval (ms) between response-complete notifications. */
    private const val RESPONSE_NOTIFY_MIN_INTERVAL_MS = 5_000L

    /** Atomic dedup counter — prevents TOCTOU race on rapid SSE events. */
    private val lastResponseNotifyTimeMs = AtomicLong(0L)

    /**
     * Notify the user that the LLM response is complete.
     * Only fires when the IDE window is not focused — if the user is in the IDE,
     * they can already see the chat updating.
     *
     * Deduped: skips if a response-complete notification was shown within the last
     * [RESPONSE_NOTIFY_MIN_INTERVAL_MS] to avoid balloon stacking from rapid responses.
     */
    fun notifyResponseComplete(project: Project) {
        if (isIdeWindowFocused(project)) return

        val now = System.currentTimeMillis()
        val last = lastResponseNotifyTimeMs.get()
        if (now - last < RESPONSE_NOTIFY_MIN_INTERVAL_MS) return
        // Atomic CAS: only one thread wins the dedup check
        if (!lastResponseNotifyTimeMs.compareAndSet(last, now)) return

        requestWindowAttention(project)
        // Post notification on EDT — Notification.notify() requires the Event Dispatch Thread.
        // This method may be called from coroutine background threads (SSE event handlers).
        ApplicationManager.getApplication().invokeLater {
            Notification(
                GROUP_ID,
                "Sigil",
                "Response complete.",
                NotificationType.INFORMATION
            ).addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    focusToolWindow(project)
                }
            ).notify(project)
        }
    }

    /**
     * Notify the user that the LLM is asking a question that requires input.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyQuestionAsked(project: Project) {
        if (!isIdeWindowFocused(project)) {
            requestWindowAttention(project)
        } else {
            // IDE is focused — play a single beep to alert without stealing focus.
            // requestWindowAttention() already beeps when IDE is not focused, so
            // we only beep here for the focused case to avoid a double beep.
            Toolkit.getDefaultToolkit().beep()
        }
        // Post notification on EDT — may be called from coroutine threads.
        ApplicationManager.getApplication().invokeLater {
            Notification(
                GROUP_ID,
                "Sigil",
                "The model is asking a question — your input is needed.",
                NotificationType.WARNING
            ).addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    focusToolWindow(project)
                }
            ).notify(project)
        }
    }

    /**
     * Notify the user that the LLM is requesting permission for a tool.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyPermissionNeeded(project: Project) {
        if (!isIdeWindowFocused(project)) {
            requestWindowAttention(project)
        } else {
            // IDE is focused — play a single beep to alert without stealing focus.
            Toolkit.getDefaultToolkit().beep()
        }
        // Post notification on EDT — may be called from coroutine threads.
        ApplicationManager.getApplication().invokeLater {
            Notification(
                GROUP_ID,
                "Sigil",
                "The model needs permission to proceed.",
                NotificationType.WARNING
            ).addAction(
                NotificationAction.createSimpleExpiring("Open") {
                    focusToolWindow(project)
                }
            ).notify(project)
        }
    }

    /**
     * Show a restart-needed balloon notification. Informational only — does not block.
     * Used when MCP settings change and the OpenCode server is being re-initialized,
     * to inform the user that a restart may be needed for full effect.
     */
    fun showRestartWarning(message: String) {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
            ?: run {
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}
                    .warn { "[ACP] Cannot show restart warning: no open project" }
                return
            }
        Notification(
            GROUP_ID,
            "Restart Needed",
            message,
            NotificationType.WARNING
        ).notify(project)
    }

    /** Check if the IDE project frame is currently focused. */
    private fun isIdeWindowFocused(project: Project): Boolean {
        val frame = WindowManager.getInstance().getFrame(project)
        return frame?.isActive == true
    }

    /**
     * Request user attention: plays a system beep.
     * Does NOT call toFront()/requestFocus() which would forcibly steal focus
     * from the user's current application — the notification balloon itself
     * provides visual attention.
     */
    private fun requestWindowAttention(project: Project) {
        Toolkit.getDefaultToolkit().beep()
    }

    /** Focus the OpenCode tool window, showing the chat panel. */
    private fun focusToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ChatConstants.TOOL_WINDOW_ID)
        if (toolWindow != null) {
            toolWindow.show()
        }
    }
}
