package com.opencode.acp.chat.model

/**
 * Exhaustive state machine for the input area.
 * Only one variant is active at a time — impossible to have invalid combinations
 * like "streaming + disconnected" or "prompt + sending".
 *
 * Computed in ChatViewModel from existing StateFlows.
 * Composables switch on this instead of combining booleans.
 */
sealed interface ChatInputState {
    /** Input is completely disabled — no text entry, no buttons. */
    data object Disabled : ChatInputState

    /** Normal idle state — text field active, green Send button when text present. */
    data object Idle : ChatInputState

    /** Message dispatched, awaiting first LLM token.
     *  Visually identical to [Streaming] — both show glow, stop button, pulse. */
    data object Sending : ChatInputState

    /** AI is generating a response. Text field active.
     *  Send button visible when text present (steer), Stop button when empty.
     *  Visually identical to [Sending]. */
    data object Streaming : ChatInputState

    /** Tool permission prompt is showing — input disabled, user must respond. */
    data class AwaitingPermission(val prompt: PermissionPrompt) : ChatInputState

    /** Selection/question prompt is showing — input disabled, user must respond. */
    data class AwaitingSelection(val prompt: SelectionPrompt) : ChatInputState
}