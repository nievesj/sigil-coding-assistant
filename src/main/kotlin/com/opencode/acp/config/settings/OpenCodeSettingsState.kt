package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for the OpenCode plugin.
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml")])
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState.State> {

    data class State(
        var binaryPath: String = "",
        var permissionTimeoutSeconds: Int = 60,
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): OpenCodeSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)
    }
}
