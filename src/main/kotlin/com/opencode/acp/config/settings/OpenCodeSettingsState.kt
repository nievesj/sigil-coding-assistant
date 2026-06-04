package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.opencode.acp.chat.model.ProviderModel

/**
 * Persistent settings for the OpenCode plugin.
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. Kotlin data classes can fail to deserialize because the
 * compiler-generated constructor uses default parameters that XStream can't
 * invoke via reflection.
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml")])
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {

    var binaryPath: String = ""
    var permissionTimeoutSeconds: Int = 60
    /** Favorite model keys in format "providerID/modelID" (slash-separated for XML safety). */
    var favoriteModels: java.util.ArrayList<String> = java.util.ArrayList()
    /** Inline code text color as "#RRGGBB" */
    var inlineCodeColor: String = "#6BBE50"
    /** List number color as "#RRGGBB" */
    var listNumberColor: String = "#6BBE50"
    /** Last selected model key in format "providerID/modelID". */
    var lastSelectedModelKey: String = ""
    /** Whether the session sidebar is visible. Persisted across tool window reopens. */
    var sidebarVisible: Boolean = true

    override fun getState(): OpenCodeSettingsState {
        println("[OpenCodeSettings] getState() called — favorites=${favoriteModels}, lastModel=$lastSelectedModelKey")
        return this
    }

    override fun loadState(state: OpenCodeSettingsState) {
        println("[OpenCodeSettings] loadState() called — incoming favorites=${state.favoriteModels}, lastModel=${state.lastSelectedModelKey}")
        binaryPath = state.binaryPath
        permissionTimeoutSeconds = state.permissionTimeoutSeconds
        favoriteModels = state.favoriteModels
        inlineCodeColor = state.inlineCodeColor
        listNumberColor = state.listNumberColor
        lastSelectedModelKey = state.lastSelectedModelKey
        sidebarVisible = state.sidebarVisible
        println("[OpenCodeSettings] loadState() done — favorites=${favoriteModels}, lastModel=$lastSelectedModelKey")
    }

    companion object {
        fun getInstance(): OpenCodeSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)

        /** Uses slash to separate provider and model IDs (safe for XML, unlike \x1F). */
        fun modelKey(providerID: String, modelID: String) = "$providerID/$modelID"
    }

    fun isFavoriteModel(providerID: String, modelID: String): Boolean {
        val key = modelKey(providerID, modelID)
        val result = favoriteModels.contains(key)
        println("[OpenCodeSettings] isFavoriteModel($providerID, $modelID) -> key=$key, found=$result, allFavorites=$favoriteModels")
        return result
    }

    fun toggleFavoriteModel(providerID: String, modelID: String) {
        val key = modelKey(providerID, modelID)
        if (favoriteModels.contains(key)) {
            favoriteModels.remove(key)
            println("[OpenCodeSettings] toggleFavoriteModel REMOVE key=$key → favorites=$favoriteModels")
        } else {
            favoriteModels.add(key)
            println("[OpenCodeSettings] toggleFavoriteModel ADD key=$key → favorites=$favoriteModels")
        }
    }

    /** Remove stale favorites for models that no longer exist. Only runs when models are loaded. */
    fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
        val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
        val before = favoriteModels.size
        favoriteModels.removeAll { it !in validKeys }
        println("[OpenCodeSettings] cleanupStaleFavorites: ${before} → ${favoriteModels.size} (removed ${before - favoriteModels.size} stale)")
    }
}