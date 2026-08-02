package com.opencode.acp.config.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.opencode.acp.config.AgentRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for Custom Agents (v1: coding-assistant + council;
 * v2: coder + researcher + planner + tester + per-agent models) — follows the
 * same XStream-safe pattern as [OpenCodeContextSettingsState] and
 * [OpenCodeFollowSettingsState].
 *
 * Uses a plain class (not data class) with var fields for reliable XStream
 * serialization. [CouncilMember] and [AgentModelBinding] entries use
 * [java.util.ArrayList] (not List) for XStream compatibility, mirroring the
 * [com.opencode.acp.chat.model.CommandHistoryEntry] pattern.
 *
 * See `docs/tdd/custom-agents.md` (v1) and `docs/tdd/custom-agents-v2.md` (v2).
 */
@Service(Service.Level.APP)
@State(
    name = "OpenCodeAgentSettings",
    storages = [Storage("opencode-agent-settings.xml", roamingType = RoamingType.DISABLED)]
)
class OpenCodeAgentSettingsState : PersistentStateComponent<OpenCodeAgentSettingsState> {

    private val logger = KotlinLogging.logger {}

    // ── v1 (unchanged) ──────────────────────────────────────────────────

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

    // ── v2 new ──────────────────────────────────────────────────────────

    /**
     * Default OFF — subagents are opt-in. Each v2 subagent is independently
     * toggleable in Settings → Tools → Sigil → Agents.
     */
    var enableCoder: Boolean = false
    var enableResearcher: Boolean = false
    var enablePlanner: Boolean = false
    var enableTester: Boolean = false

    /**
     * Per-agent model bindings. One entry per agent that has a configured
     * model. Absent agentName = inherit parent's model (v1 default behavior).
     * Stored as [java.util.ArrayList] for XStream compatibility.
     *
     * See `docs/tdd/custom-agents-v2.md` §4.7.1B and Q2 (XStream round-trip).
     */
    var agentModels: java.util.ArrayList<AgentModelBinding> = java.util.ArrayList()

    override fun getState(): OpenCodeAgentSettingsState = this

    override fun loadState(state: OpenCodeAgentSettingsState) {
        // ── v1 (unchanged) ──
        enableCodingAssistant = state.enableCodingAssistant
        enableCouncil = state.enableCouncil
        // Filter blanks AND dedup (defense-in-depth: duplicates in the persisted
        // XML would otherwise flow into buildTaskPermissionYaml and emit
        // duplicate YAML keys — see AgentConfigWriter.buildTaskPermissionYaml).
        taskAllowedAgents = java.util.ArrayList(state.taskAllowedAgents.filter { it.isNotBlank() }.distinct())
        // Copy to avoid shared references, filter invalid members. Deep-copy
        // each CouncilMember so mutations to `state` members do not alias into
        // `this` (the elements are mutable var-field classes, not data classes).
        val validMembers = state.councilMembers.filter { it.isValid() }
        // Dedup by (providerID, modelID, thinkingVariant)
        val seen = mutableSetOf<Triple<String, String, String>>()
        councilMembers = java.util.ArrayList(validMembers.filter {
            seen.add(Triple(it.providerID, it.modelID, it.thinkingVariant))
        }.map { member ->
            // Deep copy: new instance with the same field values.
            CouncilMember(member.providerID, member.modelID, member.thinkingVariant)
        })

        // ── v2 new ──
        enableCoder = state.enableCoder
        enableResearcher = state.enableResearcher
        enablePlanner = state.enablePlanner
        enableTester = state.enableTester
        // Filter invalid bindings (blank/unsafe agentName). Normalize a
        // non-null-but-invalid inner CouncilMember (e.g. XStream deserialized
        // `<model/>` as a blank-fields CouncilMember instead of null) to a
        // null-model binding so the "inherit" intent survives the round-trip
        // instead of being dropped. Then dedup by agentName, PREFERRING
        // model-bearing bindings over null-model (inherit) bindings: a
        // null-model binding appearing before a valid-model binding for the
        // same agent must NOT silently discard the valid model. We sort so
        // `hasModel()==true` comes first, then take the first per agentName.
        agentModels = java.util.ArrayList(
            state.agentModels
                .map { binding ->
                    // Normalize blank/invalid inner model to null (inherit sentinel).
                    val m = binding.model
                    if (m != null && !m.isValid()) AgentModelBinding(binding.agentName, null)
                    else AgentModelBinding(
                        binding.agentName,
                        m?.let { CouncilMember(it.providerID, it.modelID, it.thinkingVariant) })
                }
                .filter { it.isValid() }
                .groupBy { it.agentName }
                .values
                .map { group -> group.sortedByDescending { it.hasModel() }.first() }
        )
        // Log orphan agentModels (agentName not in AgentRegistry.ALL_NAMES) at
        // debug for diagnostics. Orphans are PRESERVED (not dropped) for
        // forward-compat (an agent renamed then renamed back should survive),
        // but accumulating stale entries from hand-edited XML or old plugin
        // versions bloats state. The log surfaces them so they are diagnosable.
        val knownNames = AgentRegistry.ALL_NAMES.toSet()
        for (binding in agentModels) {
            if (binding.agentName !in knownNames) {
                logger.debug { "[ACP] OpenCodeAgentSettingsState: preserving orphan agentModels binding for unknown agent '${binding.agentName}' (not in AgentRegistry.ALL_NAMES) - kept for forward-compat, no UI row matches it" }
            }
        }
    }

    /**
     * Helper: get the configured model for an agent, or `null` = inherit.
     *
     * Returns null when:
     * - no [AgentModelBinding] exists for [agentName], OR
     * - the binding exists but its [CouncilMember] is null/invalid (the
     *   "inherit" sentinel state).
     *
     * Used by [com.opencode.acp.config.AgentConfigWriter] to decide whether to
     * emit `model:`/`variant:` frontmatter for the agent file.
     */
    fun modelFor(agentName: String): CouncilMember? {
        val binding = agentModels.find { it.agentName == agentName } ?: return null
        // Reuse AgentModelBinding.hasModel() to keep the validity check in one place
        // (DRY — avoids the same `model != null && model.isValid()` logic diverging).
        return if (binding.hasModel()) binding.model else null
    }

    companion object {
        @JvmStatic
        fun getInstance(): OpenCodeAgentSettingsState =
            ApplicationManager.getApplication().getService(OpenCodeAgentSettingsState::class.java)
    }
}
