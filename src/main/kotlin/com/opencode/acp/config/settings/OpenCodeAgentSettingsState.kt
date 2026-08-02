package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for Custom Agents (coding-assistant + council) —
 * follows the same XStream-safe pattern as [OpenCodeContextSettingsState]
 * and [OpenCodeFollowSettingsState].
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. [CouncilMember] entries use [java.util.ArrayList] (not List)
 * for XStream compatibility, mirroring the [CommandHistoryEntry] pattern.
 */
@Service(Service.Level.APP)
@State(
    name = "OpenCodeAgentSettings",
    storages = [Storage("opencode-agent-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodeAgentSettingsState : PersistentStateComponent<OpenCodeAgentSettingsState> {

    /** Default ON — the feature's primary purpose is hands-on coding. */
    var enableCodingAssistant: Boolean = true
    /** Default OFF — requires member configuration before it is useful. */
    var enableCouncil: Boolean = false

    /**
     * Shared dynamic allowlist of agent names that can be delegated to via the
     * `task` tool. Applies to BOTH coding-assistant and council. Driven by the
     * settings UI. Stored as [java.util.ArrayList] for XStream compatibility.
     */
    var taskAllowedAgents: java.util.ArrayList<String> = java.util.ArrayList(listOf("explore", "general"))

    /** XStream-compatible ArrayList of [CouncilMember] (mirrors commandHistory pattern). */
    var councilMembers: java.util.ArrayList<CouncilMember> = java.util.ArrayList()

    override fun getState(): OpenCodeAgentSettingsState = this

    override fun loadState(state: OpenCodeAgentSettingsState) {
        enableCodingAssistant = state.enableCodingAssistant
        enableCouncil = state.enableCouncil
        taskAllowedAgents = java.util.ArrayList(state.taskAllowedAgents.filter { it.isNotBlank() })
        // Copy to avoid shared references, filter invalid members
        val validMembers = state.councilMembers.filter { it.isValid() }
        // Dedup by (providerID, modelID, thinkingVariant)
        val seen = mutableSetOf<Triple<String, String, String>>()
        councilMembers = java.util.ArrayList(validMembers.filter {
            seen.add(Triple(it.providerID, it.modelID, it.thinkingVariant))
        })
    }

    companion object {
        @JvmStatic
        fun getInstance(): OpenCodeAgentSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeAgentSettingsState::class.java)
    }
}