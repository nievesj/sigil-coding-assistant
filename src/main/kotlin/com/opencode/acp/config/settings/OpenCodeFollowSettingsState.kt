package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for Follow Agent — extracted from [OpenCodeSettingsState]
 * (Phase A of a 3-phase XStream-safe migration).
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. The fields here are also retained (as `@Deprecated`) on
 * [OpenCodeSettingsState] for one release cycle so existing XML files
 * continue to load; [OpenCodeSettingsState.loadState] forwards the values
 * here during the transition.
 */
@Service(Service.Level.APP)
@State(
    name = "OpenCodeFollowSettings",
    storages = [Storage("opencode-follow-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodeFollowSettingsState : PersistentStateComponent<OpenCodeFollowSettingsState> {

    /**
     * Whether Follow Agent is enabled (auto-opens files on tool calls).
     * Default OFF — the feature is opt-in because it opens files in the
     * editor on every read tool call, which can be jarring for users who
     * have not opted in.
     */
    var followAgentEnabled: Boolean = false
    /**
     * When Follow Agent is enabled, also show agent-executed commands in a read-only
     * console in the Run tool window. Default true — if the user opted into Follow Agent,
     * they want to see command output too.
     */
    var followCommandsInConsole: Boolean = true

    /**
     * When Follow Agent is enabled, also open IntelliJ's native Find in Files when the
     * agent performs a search. Default true — gives the user an interactive result set
     * they can navigate, filter, and group.
     */
    var followSearchesInFindWindow: Boolean = true

    /**
     * Whether Brave Mode (auto-approve all permission prompts) is enabled.
     * Default OFF — the feature auto-approves tool permission requests without
     * showing the UI prompt, which is a security-relevant decision. The server
     * still enforces explicit `deny` rules before sending the permission SSE
     * event, so Brave Mode cannot override hard denials.
     */
    var braveModeEnabled: Boolean = false

    /** Highlight color for READ tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followReadColor: String = "#5078C888"
    /** Highlight color for EDIT tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followEditColor: String = "#50A05088"
    /** Highlight color for SEARCH tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followSearchColor: String = "#C8B43C88"
    /** Highlight color for EXECUTE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followExecuteColor: String = "#B4785088"
    /** Highlight color for DELETE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followDeleteColor: String = "#C8505088"
    /** Highlight color for MOVE tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followMoveColor: String = "#A050C888"
    /** Highlight color for FETCH tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followFetchColor: String = "#50A0C888"
    /** Highlight color for OTHER tool calls as "#RRGGBBAA" hex. Default alpha 0x88 ≈53%. */
    var followOtherColor: String = "#80808088"

    override fun getState(): OpenCodeFollowSettingsState = this

    override fun loadState(state: OpenCodeFollowSettingsState) {
        followAgentEnabled = state.followAgentEnabled
        followCommandsInConsole = state.followCommandsInConsole
        followSearchesInFindWindow = state.followSearchesInFindWindow
        braveModeEnabled = state.braveModeEnabled
        followReadColor = state.followReadColor
        followEditColor = state.followEditColor
        followSearchColor = state.followSearchColor
        followExecuteColor = state.followExecuteColor
        followDeleteColor = state.followDeleteColor
        followMoveColor = state.followMoveColor
        followFetchColor = state.followFetchColor
        followOtherColor = state.followOtherColor
    }

    /**
     * Returns the persisted hex color for a [com.agentclientprotocol.model.ToolKind].
     * THINK and SWITCH_MODE have no persisted color and fall back to OTHER.
     *
     * Asymmetry with [setFollowColor]: get returns OTHER's color for THINK/SWITCH_MODE
     * (read fallback), but set is a no-op for those kinds (no persistence). This is
     * intentional — they have no dedicated UI color picker and inherit OTHER's color.
     */
    fun getFollowColor(kind: com.agentclientprotocol.model.ToolKind): String = when (kind) {
        com.agentclientprotocol.model.ToolKind.READ -> followReadColor
        com.agentclientprotocol.model.ToolKind.EDIT -> followEditColor
        com.agentclientprotocol.model.ToolKind.SEARCH -> followSearchColor
        com.agentclientprotocol.model.ToolKind.EXECUTE -> followExecuteColor
        com.agentclientprotocol.model.ToolKind.DELETE -> followDeleteColor
        com.agentclientprotocol.model.ToolKind.MOVE -> followMoveColor
        com.agentclientprotocol.model.ToolKind.FETCH -> followFetchColor
        com.agentclientprotocol.model.ToolKind.THINK,
        com.agentclientprotocol.model.ToolKind.SWITCH_MODE,
        com.agentclientprotocol.model.ToolKind.OTHER -> followOtherColor
    }

    /**
     * Persists the hex color for a [com.agentclientprotocol.model.ToolKind].
     * THINK and SWITCH_MODE have no persisted color — no-op.
     */
    fun setFollowColor(kind: com.agentclientprotocol.model.ToolKind, hex: String) {
        when (kind) {
            com.agentclientprotocol.model.ToolKind.READ -> followReadColor = hex
            com.agentclientprotocol.model.ToolKind.EDIT -> followEditColor = hex
            com.agentclientprotocol.model.ToolKind.SEARCH -> followSearchColor = hex
            com.agentclientprotocol.model.ToolKind.EXECUTE -> followExecuteColor = hex
            com.agentclientprotocol.model.ToolKind.DELETE -> followDeleteColor = hex
            com.agentclientprotocol.model.ToolKind.MOVE -> followMoveColor = hex
            com.agentclientprotocol.model.ToolKind.FETCH -> followFetchColor = hex
            com.agentclientprotocol.model.ToolKind.OTHER -> followOtherColor = hex
            com.agentclientprotocol.model.ToolKind.THINK,
            com.agentclientprotocol.model.ToolKind.SWITCH_MODE -> { /* no-op */ }
        }
    }

    companion object {
        fun getInstance(): OpenCodeFollowSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeFollowSettingsState::class.java)
    }
}