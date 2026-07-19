package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.SelectionPrompt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manages permission and selection prompt state: set, clear, and emit signals
 * for prompt lifecycle.
 *
 * @param signals Signal flow — for emitting PermissionRequested/SelectionRequested.
 * @param pendingPermission The permission prompt StateFlow — shared by reference with
 *        SessionState, which exposes it as a public read-only StateFlow.
 * @param pendingSelection The selection prompt StateFlow — shared by reference with
 *        SessionState, which exposes it as a public read-only StateFlow.
 * @param sessionId The session ID — used in emitted signals.
 */
internal class PromptStateManager(
    private val signals: MutableSharedFlow<UiSignal>,
    private val pendingPermission: MutableStateFlow<PermissionPrompt?>,
    private val pendingSelection: MutableStateFlow<SelectionPrompt?>,
    private val sessionId: String,
) {
    /** @Volatile — read by SessionManager for eviction guard from a different coroutine. */
    @Volatile
    var hasPendingPermission: Boolean = false
        private set

    /** Set the pending permission prompt. Caller is responsible for emitting the
     *  PermissionRequested signal (e.g., SessionState.handlePermission calls
     *  _signals.tryEmit(UiSignal.PermissionRequested(prompt)) after this). */
    fun setPermission(prompt: PermissionPrompt) {
        hasPendingPermission = true
        pendingPermission.value = prompt
    }

    /**
     * Toggle the pending permission flag. When false, also clears the pendingPermission StateFlow.
     * This wraps the combo that PermissionManager calls via SessionState.setPendingPermission(false).
     */
    fun setPendingPermission(flag: Boolean) {
        hasPendingPermission = flag
        if (!flag) pendingPermission.value = null
    }

    /** Set the pending selection prompt. Caller is responsible for emitting the
     *  SelectionRequested signal (e.g., SessionState.handleQuestionAsked calls
     *  _signals.tryEmit(UiSignal.SelectionRequested(prompt)) after this). */
    fun setSelection(prompt: SelectionPrompt) {
        pendingSelection.value = prompt
    }

    /** Clear the pending selection prompt. */
    fun clearSelection() {
        pendingSelection.value = null
    }
}