package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for Context & Compaction — extracted from [OpenCodeSettingsState]
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
    name = "OpenCodeContextSettings",
    storages = [Storage("opencode-context-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodeContextSettingsState : PersistentStateComponent<OpenCodeContextSettingsState> {

    // ── Context & Compaction settings (Tools → Sigil → Context) ─────────────
    /** When on, tool results exceeding the char limit are truncated at insertion time. */
    var truncateToolOutput: Boolean = false
    /** Max chars per tool output before truncation. Clamped to 10_000..200_000 on set. */
    var toolOutputCharLimit: Int = 50_000
    /** When on, repeated reads of unchanged files emit [unchanged] instead of re-emitting content. */
    var detectDuplicateReads: Boolean = false
    /** When on, pre-computes compaction summaries in the background for instant swap.
     *  OFF by default — the server's /summarize endpoint performs ACTUAL compaction
     *  (not a preview), so auto-triggering it compacts the session immediately.
     *  Retained as a setting in case a preview API is added in the future. */
    var enableBackgroundCompaction: Boolean = false
    /** Context usage % at which background checkpointing starts. Clamped to 40f..80f on set. */
    var checkpointThresholdPercent: Float = 60f
    /** Context usage % at which pre-computed summary is ready for instant swap. Clamped to 60f..95f on set. */
    var swapThresholdPercent: Float = 80f
    /** Show the 5-category proportional bar in Context tab. */
    var showContextBreakdown: Boolean = true
    /** When to show pressure warnings on the context indicator. One of NEVER / ELEVATED / HIGH / CRITICAL. */
    var pressureNotificationThreshold: String = "HIGH"
    /** Ask for confirmation before triggering manual compaction. */
    var compactConfirmation: Boolean = true

    // ── Context Pruner settings (Tools → Sigil → Context) ─────────────
    /** When on, the sigil-pruner.ts plugin is extracted and loaded by the OpenCode server.
     *  Performs server-side deterministic pruning (dedup, old tool output pruning, errored
     *  tool input pruning) and LLM-driven compression via a compress tool. */
    var enableContextPruner: Boolean = false
    /** Prune tool outputs older than N messages. Clamped to 5..100 on set. */
    var prunerMaxToolOutputMessages: Int = 20
    /** Prune errored tool inputs after N turns. Clamped to 1..20 on set. */
    var prunerErroredToolTurns: Int = 4
    /** Enable LLM-driven compression (compress tool). */
    var prunerCompressEnabled: Boolean = true
    /** Compression mode: "range" or "message". Whitelisted on set. */
    var prunerCompressMode: String = "range"

    // ── Context Pruner: Nudge settings ────────────────────────────────
    /** When on, injects a system reminder prompting the model to call the compress
     *  tool when context usage exceeds the threshold. Two levels: gentle (threshold)
     *  and urgent (urgentPercent). Cooldown prevents nagging every turn. */
    var prunerNudgeEnabled: Boolean = true
    /** Gentle nudge threshold (% of context limit). Clamped to 30..90 on set. */
    var prunerNudgeThresholdPercent: Int = 60
    /** Urgent nudge threshold (% of context limit). Clamped to 50..99 on set. */
    var prunerNudgeUrgentPercent: Int = 80
    /** Minimum turns between nudges. Clamped to 1..10 on set. */
    var prunerNudgeCooldownTurns: Int = 3
    /** Fallback context limit when the model object doesn't expose one. Clamped to 1000..2_000_000 on set. */
    var prunerDefaultContextLimit: Int = 128000

    override fun getState(): OpenCodeContextSettingsState = this

    override fun loadState(state: OpenCodeContextSettingsState) {
        // Context & Compaction settings (with clamping for corrupt/out-of-range values)
        truncateToolOutput = state.truncateToolOutput
        toolOutputCharLimit = state.toolOutputCharLimit.coerceIn(10_000, 200_000)
        detectDuplicateReads = state.detectDuplicateReads
        enableBackgroundCompaction = state.enableBackgroundCompaction
        checkpointThresholdPercent = state.checkpointThresholdPercent.coerceIn(40f, 80f)
        swapThresholdPercent = state.swapThresholdPercent.coerceIn(60f, 95f)
        showContextBreakdown = state.showContextBreakdown
        pressureNotificationThreshold = when (state.pressureNotificationThreshold) {
            "NEVER", "ELEVATED", "HIGH", "CRITICAL" -> state.pressureNotificationThreshold
            else -> "HIGH"
        }
        compactConfirmation = state.compactConfirmation
        // Context Pruner settings (with clamping for corrupt/out-of-range values)
        enableContextPruner = state.enableContextPruner
        prunerMaxToolOutputMessages = state.prunerMaxToolOutputMessages.coerceIn(5, 100)
        prunerErroredToolTurns = state.prunerErroredToolTurns.coerceIn(1, 20)
        prunerCompressEnabled = state.prunerCompressEnabled
        prunerCompressMode = state.prunerCompressMode.takeIf { it in listOf("range", "message") } ?: "range"
        prunerNudgeEnabled = state.prunerNudgeEnabled
        prunerNudgeThresholdPercent = state.prunerNudgeThresholdPercent.coerceIn(30, 90)
        prunerNudgeUrgentPercent = state.prunerNudgeUrgentPercent.coerceIn(50, 99)
        prunerNudgeCooldownTurns = state.prunerNudgeCooldownTurns.coerceIn(1, 10)
        prunerDefaultContextLimit = state.prunerDefaultContextLimit.coerceIn(1000, 2_000_000)
    }

    companion object {
        fun getInstance(): OpenCodeContextSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeContextSettingsState::class.java)
    }
}