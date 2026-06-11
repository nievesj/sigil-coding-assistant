package com.opencode.acp.chat

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.opencode.acp.chat.model.ChatConstants
import java.awt.Toolkit
import java.awt.event.WindowEvent

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
 * and requests OS-level taskbar attention (flash/bounce).
 */
object OpenCodeNotifications {

    private const val GROUP_ID = "OpenCode"

    /** Minimum interval (ms) between response-complete notifications. */
    private const val RESPONSE_NOTIFY_MIN_INTERVAL_MS = 5_000L

    @Volatile
    private var lastResponseNotifyTimeMs = 0L

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
        if (now - lastResponseNotifyTimeMs < RESPONSE_NOTIFY_MIN_INTERVAL_MS) return
        lastResponseNotifyTimeMs = now

        requestWindowAttention(project)
        Notification(
            GROUP_ID,
            "OpenCode",
            "Response complete.",
            NotificationType.INFORMATION
        ).addAction(
            NotificationAction.createSimpleExpiring("Open") {
                focusToolWindow(project)
            }
        ).notify(project)
    }

    /**
     * Notify the user that the LLM is asking a question that requires input.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyQuestionAsked(project: Project) {
        if (!isIdeWindowFocused(project)) {
            requestWindowAttention(project)
        }
        Toolkit.getDefaultToolkit().beep()
        Notification(
            GROUP_ID,
            "OpenCode",
            "The model is asking a question — your input is needed.",
            NotificationType.WARNING
        ).addAction(
            NotificationAction.createSimpleExpiring("Open") {
                focusToolWindow(project)
            }
        ).notify(project)
    }

    /**
     * Notify the user that the LLM is requesting permission for a tool.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyPermissionNeeded(project: Project) {
        if (!isIdeWindowFocused(project)) {
            requestWindowAttention(project)
        }
        Toolkit.getDefaultToolkit().beep()
        Notification(
            GROUP_ID,
            "OpenCode",
            "The model needs permission to proceed.",
            NotificationType.WARNING
        ).addAction(
            NotificationAction.createSimpleExpiring("Open") {
                focusToolWindow(project)
            }
        ).notify(project)
    }

    /** Check if the IDE project frame is currently focused. */
    private fun isIdeWindowFocused(project: Project): Boolean {
        val frame = WindowManager.getInstance().getFrame(project)
        return frame?.isActive == true
    }

    /**
     * Request OS-level attention: plays a system beep and attempts to
     * flash/bounce the taskbar icon via [WindowEvent.WINDOW_DEACTIVATED]
     * dispatch on the project frame.
     */
    private fun requestWindowAttention(project: Project) {
        Toolkit.getDefaultToolkit().beep()
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        // toFront() + requestFocus() trigger OS taskbar flash/bounce on most platforms
        frame.toFront()
        frame.requestFocus()
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
