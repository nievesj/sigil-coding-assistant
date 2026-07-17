package com.opencode.acp.chat.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * EDT execution abstraction for testability. Inject into Follow*Managers and
 * Notifications (when under test). Do NOT inject into UI composables or ReviewPanel.
 */
interface EdtExecutor {
    fun runOnEdt(action: () -> Unit)
    fun runOnNonModalEdt(action: () -> Unit)
}

/** Production implementation using IntelliJ's ApplicationManager. */
class IntelliJEdtExecutor : EdtExecutor {
    override fun runOnEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action)

    override fun runOnNonModalEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.nonModal())
}