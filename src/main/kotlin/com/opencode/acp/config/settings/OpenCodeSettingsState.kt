package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.opencode.acp.chat.model.ProviderModel

/**
 * Persistent settings for the OpenCode plugin.
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml")])
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState.State> {

    data class State(
        var binaryPath: String = "",
        var permissionTimeoutSeconds: Int = 60,
        /** List of favorite model keys in format "providerID\x1FmodelID" */
        var favoriteModels: List<String> = emptyList(),
    )

    private var myState: State = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): OpenCodeSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)

        /** Uses \x1F (Unit Separator) to avoid collisions with ':' in provider IDs. */
        fun modelKey(providerID: String, modelID: String) = "$providerID\u001F$modelID"
    }

    fun isFavoriteModel(providerID: String, modelID: String): Boolean =
        myState.favoriteModels.contains(modelKey(providerID, modelID))

    fun toggleFavoriteModel(providerID: String, modelID: String) {
        val key = modelKey(providerID, modelID)
        val current = myState.favoriteModels
        myState = myState.copy(
            favoriteModels = if (key in current) current - key else current + key
        )
    }

    /** Remove stale favorites for models that no longer exist. Delegates to opposite implementation. */
    fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
        val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
        val stale = myState.favoriteModels.filter { it !in validKeys }
        if (stale.isNotEmpty()) {
            myState = myState.copy(favoriteModels = myState.favoriteModels - stale.toSet())
        }
    }
}
