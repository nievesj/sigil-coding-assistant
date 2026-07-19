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
     * Whether Brave Mode (auto-approve all permission prompts) is enabled.
     * Default OFF — the feature auto-approves tool permission requests without
     * showing the UI prompt, which is a security-relevant decision. The server
     * still enforces explicit `deny` rules before sending the permission SSE
     * event, so Brave Mode cannot override hard denials.
     *
     * **MISPLACEMENT WARNING**: This field lives in OpenCodeFollowSettingsState for
     * historical reasons (XStream migration Phase A grouped it with Follow Agent).
     * It is conceptually a PERMISSION setting, NOT a Follow Agent setting.
     * Future refactoring should move it to a dedicated OpenCodePermissionSettingsState.
     *
     * **DO NOT add braveModeEnabled to migrateFromLegacy()** — that method deliberately
     * excludes this field to avoid overwriting the user's Brave Mode setting with the
     * default (false) from a freshly-constructed state. Adding it would silently disable
     * Brave Mode on every restart.
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

    /**
     * Load state from the XStream-loaded state object.
     *
     * **IMPORTANT**: This method copies ALL fields including braveModeEnabled.
     * It should ONLY be called by the IntelliJ persistence framework (which loads
     * from XML where braveModeEnabled is persisted). NEVER call this method with
     * a freshly-constructed OpenCodeFollowSettingsState() — that would overwrite
     * the user's Brave Mode setting with the default (false).
     *
     * For legacy migration from OpenCodeSettingsState, use [migrateFromLegacy]
     * instead, which deliberately excludes braveModeEnabled.
     */
    override fun loadState(state: OpenCodeFollowSettingsState) {
        // Detect accidental calls with a freshly-constructed default state, which would
        // silently disable Brave Mode. Log a warning — don't throw (the IntelliJ persistence
        // framework calls this with a properly-loaded state).
        if (!state.braveModeEnabled && state.followAgentEnabled == false &&
            state.followReadColor == "#5078C888" && state.followEditColor == "#50A05088") {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn {
                "[ACP] OpenCodeFollowSettingsState.loadState called with a state that looks like a fresh default — " +
                "this would silently disable Brave Mode. Use migrateFromLegacy() for legacy migration, " +
                "not loadState(). If this is a legitimate reset, ignore this warning."
            }
        }
        followAgentEnabled = state.followAgentEnabled
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
     * One-time legacy migration from [OpenCodeSettingsState].
     *
     * Copies ONLY the migrated Follow Agent fields (followAgentEnabled + the 9
     * follow*Color fields) from [state] into this singleton, leaving
     * [braveModeEnabled] UNTOUCHED. This is critical because callers construct a
     * fresh `OpenCodeFollowSettingsState()` (whose `braveModeEnabled` defaults to
     * `false`) and would otherwise overwrite the user's Brave Mode setting on
     * every restart — silently disabling auto-approve of permission prompts.
     *
     * Use [loadState] for the normal `PersistentStateComponent` flow (which
     * copies all fields from a state object loaded by XStream). Use this method
     * only when forwarding legacy fields from the parent [OpenCodeSettingsState].
     */
    fun migrateFromLegacy(state: OpenCodeFollowSettingsState) {
        followAgentEnabled = state.followAgentEnabled
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
     * THINK and SWITCH_MODE have no dedicated color and fall back to OTHER.
     * Symmetric with [setFollowColor] — both read and write fall back to
     * followOtherColor for THINK/SWITCH_MODE.
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
     * THINK and SWITCH_MODE have no highlight (colorConfigs maps them to null), so
     * persisting a color for them would be misleading — setFollowColor is a no-op
     * for those kinds. OTHER writes to followOtherColor as before.
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
            com.agentclientprotocol.model.ToolKind.SWITCH_MODE -> {
                // THINK and SWITCH_MODE have no highlight (colorConfigs maps them to null).
                // Persisting a color for them would be misleading — no-op.
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}.debug {
                    "[ACP] setFollowColor: no-op for $kind (no highlight configured)"
                }
            }
        }
    }

    companion object {
        fun getInstance(): OpenCodeFollowSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeFollowSettingsState::class.java)
    }
}