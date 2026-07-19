package com.opencode.acp.chat

import com.intellij.openapi.project.Project

/**
 * Notification service interface for testability.
 *
 * See TDD §4.2.4 — only [OpenCodeNotifications] (which has side effects via
 * IntelliJ's [com.intellij.notification.NotificationGroup] / [com.intellij.notification.Notification]
 * API) needs interface extraction among the singleton objects. Other singletons
 * (e.g. [com.opencode.acp.chat.processor.PrunerConfigWriter]) are being converted
 * to classes separately.
 *
 * The default implementation is [OpenCodeNotifications], which delegates to
 * IntelliJ balloon notifications. Tests can provide a fake implementation that
 * records calls without touching the IDE notification system.
 */
interface NotificationService {
    /**
     * Notify the user that the LLM response is complete.
     * Only fires when the IDE window is not focused.
     */
    fun notifyResponseComplete(project: Project)

    /**
     * Notify the user that the LLM is asking a question that requires input.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyQuestionAsked(project: Project)

    /**
     * Notify the user that the LLM is requesting permission for a tool.
     * Always fires — this blocks the conversation and needs user action.
     */
    fun notifyPermissionNeeded(project: Project)

    /**
     * Notify the user that a permission prompt timed out.
     */
    fun notifyPermissionTimedOut(project: Project, toolName: String)

    /**
     * Notify the user that a permission was processed by the server despite a network error.
     */
    fun notifyPermissionProcessedDespiteError(project: Project, sessionId: String)

    /**
     * Show a restart-needed balloon notification. Informational only — does not block.
     */
    fun showRestartWarning(message: String)
}