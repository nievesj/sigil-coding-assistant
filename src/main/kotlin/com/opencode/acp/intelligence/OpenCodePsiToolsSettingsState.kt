package com.opencode.acp.intelligence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for PSI code intelligence tools — Settings → Tools → Sigil → PSI Tools.
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization, following the [com.opencode.acp.config.settings.OpenCodeMcpSettingsState] pattern.
 */
@Service(Service.Level.APP)
@State(
    name = "OpenCodePsiToolsSettings",
    storages = [Storage("opencode-psi-tools-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodePsiToolsSettingsState : PersistentStateComponent<OpenCodePsiToolsSettingsState> {

    /** Whether PSI code intelligence tools are active. When false, tool methods return
     *  a structured 'disabled' error. Tools are always registered (the toggle controls
     *  behavior, not registration) per TDD §10 Q1. */
    var psiToolsEnabled: Boolean = true

    /** Whether `scope: "all"` (includes libraries) is allowed. Default false — must be
     *  explicitly enabled by the user. When disabled, `scope: "all"` is rejected with
     *  a structured error. This is a security-relevant setting — the agent cannot make
     *  this decision. */
    var allowScopeAll: Boolean = false

    /** PSI tools log level for idea.log. One of OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL.
     *  Default INFO. Clamped in [loadState]. */
    var psiToolsLogLevel: String = "INFO"

    override fun getState(): OpenCodePsiToolsSettingsState = this

    override fun loadState(state: OpenCodePsiToolsSettingsState) {
        psiToolsEnabled = state.psiToolsEnabled
        allowScopeAll = state.allowScopeAll
        psiToolsLogLevel = when (state.psiToolsLogLevel) {
            "OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL" -> state.psiToolsLogLevel
            else -> "INFO"
        }
    }

    companion object {
        fun getInstance(): OpenCodePsiToolsSettingsState =
            ApplicationManager.getApplication().getService(OpenCodePsiToolsSettingsState::class.java)
    }
}