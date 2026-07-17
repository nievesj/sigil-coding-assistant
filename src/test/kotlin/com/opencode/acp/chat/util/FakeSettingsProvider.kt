package com.opencode.acp.chat.util

import com.opencode.acp.config.settings.OpenCodeSettingsState

/**
 * In-memory settings provider for tests. Returns a pre-configured
 * OpenCodeSettingsState instance.
 */
class FakeSettingsProvider(
    private val settings: OpenCodeSettingsState = OpenCodeSettingsState(),
) : SettingsProvider {
    override fun get(): OpenCodeSettingsState = settings
}