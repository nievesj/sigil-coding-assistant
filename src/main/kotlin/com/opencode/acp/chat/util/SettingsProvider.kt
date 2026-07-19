package com.opencode.acp.chat.util

import com.opencode.acp.config.settings.OpenCodeSettingsState

/**
 * Settings access abstraction for testability. Inject into classes under test
 * (~5 classes: ChatViewModel, OpenCodeService, SessionManager, OpenCodeClient).
 * Do NOT inject into UI composables or settings panels — they keep `getInstance()`.
 *
 * DESIGN NOTE: Returns mutable OpenCodeSettingsState directly — no defensive copy.
 * Extracted testable classes that only READ settings are safe. Mutations should
 * continue to go through the Configurable UI path.
 */
interface SettingsProvider {
    fun get(): OpenCodeSettingsState
}

/** Production implementation using IntelliJ's service lookup. */
class IntelliJSettingsProvider : SettingsProvider {
    override fun get(): OpenCodeSettingsState = OpenCodeSettingsState.getInstance()
}