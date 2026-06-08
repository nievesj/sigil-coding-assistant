package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.opencode.acp.chat.model.CommandHistoryEntry
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
@State(name = "OpenCodeSettings", storages = [Storage("opencode-settings.xml", roamingType = RoamingType.DISABLED)])
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
    /** Maximum number of entries kept in the input command history. */
    var commandHistorySize: Int = 15
    /** SSE socket timeout in seconds (how long to wait between SSE events before reconnecting). */
    var sseSocketTimeoutSeconds: Int = 60
    /** Whether to automatically connect when the plugin opens. */
    var autoConnect: Boolean = true
    /** Port for the OpenCode server (default 4096). */
    var port: Int = 4096
    /** Persisted input command history (most recent first). Trimmed to [commandHistorySize] on save. */
    var commandHistory: java.util.ArrayList<CommandHistoryEntry> = java.util.ArrayList()

    override fun getState(): OpenCodeSettingsState {
        return this
    }

    override fun loadState(state: OpenCodeSettingsState) {
        binaryPath = state.binaryPath
        permissionTimeoutSeconds = state.permissionTimeoutSeconds
        favoriteModels = state.favoriteModels
        inlineCodeColor = state.inlineCodeColor
        listNumberColor = state.listNumberColor
        lastSelectedModelKey = state.lastSelectedModelKey
        sidebarVisible = state.sidebarVisible
        commandHistorySize = if (state.commandHistorySize > 0) state.commandHistorySize else 15
        sseSocketTimeoutSeconds = if (state.sseSocketTimeoutSeconds > 0) state.sseSocketTimeoutSeconds else 60
        autoConnect = state.autoConnect
        port = if (state.port in 1024..65535) state.port else 4096
        commandHistory = state.commandHistory
    }

    companion object {
        fun getInstance(): OpenCodeSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)

        /** Uses slash to separate provider and model IDs (safe for XML, unlike \x1F). */
        fun modelKey(providerID: String, modelID: String) = "$providerID/$modelID"
    }

    fun isFavoriteModel(providerID: String, modelID: String): Boolean {
        val key = modelKey(providerID, modelID)
        return favoriteModels.contains(key)
    }

    fun toggleFavoriteModel(providerID: String, modelID: String) {
        val key = modelKey(providerID, modelID)
        if (favoriteModels.contains(key)) {
            favoriteModels.remove(key)
        } else {
            favoriteModels.add(key)
        }
    }

    /** Remove stale favorites for models that no longer exist. Only runs when models are loaded. */
    fun cleanupStaleFavorites(allModels: List<ProviderModel>) {
        val validKeys = allModels.map { modelKey(it.providerID, it.modelID) }.toSet()
        favoriteModels.removeAll { it !in validKeys }
    }
}