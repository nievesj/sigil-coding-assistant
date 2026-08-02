package com.opencode.acp.config.settings

import com.opencode.acp.config.AgentConstants

/**
 * One per-agent model binding: maps an agent name to a [CouncilMember] model ref.
 *
 * Stored in [OpenCodeAgentSettingsState.agentModels] as an XStream-serialized
 * [java.util.ArrayList] (mirrors the [com.opencode.acp.chat.model.CommandHistoryEntry]
 * and [CouncilMember] XStream pattern).
 *
 * Semantics:
 * - `agentName` non-blank + matches [AgentConstants.YAML_SAFE_IDENTIFIER] = valid.
 * - `model == null` OR `model.providerID` blank = "inherit parent's model" (the
 *   v1 default behavior — no per-agent model configured).
 * - Otherwise the [CouncilMember] is written into the agent file's `model:` +
 *   `variant:` frontmatter by [com.opencode.acp.config.AgentConfigWriter].
 *
 * Non-data class for XStream compatibility (no-arg constructor + var fields,
 * mirrors [CouncilMember]).
 *
 * See `docs/tdd/custom-agents-v2.md` §4.7.1B and Q2 (XStream nullable nested
 * `CouncilMember` round-trip — verified by `OpenCodeAgentSettingsStateTest`).
 *
 * IDENTITY EQUALITY: this is a non-data class with `var` fields and NO custom
 * `equals`/`hashCode`. `equals` is therefore identity-based (two bindings with
 * identical fields are NOT equal). Do NOT use [AgentModelBinding] in `Set`
 * contexts or as a `Map` key, and do NOT use `List.contains(binding)` for
 * membership checks (they all use `equals`). [OpenCodeAgentSettingsState.loadState]
 * dedups by `agentName` (a String key) which is safe, but any future code that
 * compares bindings by equality will be wrong.
 */
class AgentModelBinding {
    /** Agent name this binding targets (must match [AgentConstants.V2_SUBAGENT_NAMES] or `council`). */
    var agentName: String = ""

    /**
     * The configured model, or `null` = inherit parent's model (the default).
     *
     * XStream serializes null nested objects as an empty/self-closed element;
     * round-trip fidelity is verified by the `loadState` test suite.
     */
    var model: CouncilMember? = null

    /** No-arg constructor required for XStream deserialization. */
    constructor()

    constructor(agentName: String, model: CouncilMember?) {
        this.agentName = agentName
        this.model = model
    }

    /**
     * A binding IDENTITY is valid when the agent name is non-blank and
     * YAML-safe. `model == null` is a valid identity state (means "inherit
     * parent model"). NOTE: this checks ONLY the agent name -- the inner
     * [model] is NOT validated. Use [isFullyValid] to check both the agent
     * name AND the inner model. Callers that assume `isValid()` implies the
     * model is sound will be wrong (a binding with `agentName="coder"` and
     * `model=CouncilMember("","x")` is `isValid() == true`).
     */
    fun isValid(): Boolean =
        agentName.isNotBlank() && AgentConstants.YAML_SAFE_IDENTIFIER.matches(agentName)

    /**
     * True when this binding is fully sound: the agent name is valid AND the
     * inner model is either null (inherit) or valid. Use this (not [isValid])
     * to decide whether a binding should be persisted in its entirety.
     *
     * NOTE: [OpenCodeAgentSettingsState.loadState] does NOT call `isFullyValid`
     * directly. It uses a two-step pipeline: (1) a `.map` that normalizes a
     * non-null-but-invalid inner [model] to null (the "inherit" sentinel), then
     * (2) a `.filter { it.isValid() }` (name-only). This preserves the inherit
     * intent for bindings whose inner model was deserialized as a blank-fields
     * [CouncilMember] instead of null. `isFullyValid` is provided for callers
     * that want to check both fields in one call WITHOUT the normalization step
     * - do NOT "simplify" loadState to `.filter { it.isFullyValid() }` without
     * also preserving the `.map` normalization, or blank-sentinel bindings would
     * be dropped instead of normalized to inherit.
     */
    fun isFullyValid(): Boolean = isValid() && (model == null || model!!.isValid())


    /**
     * True when this binding actually configures a model (model != null and
     * the model itself is valid). Used by [OpenCodeAgentSettingsState.modelFor]
     * to decide whether to emit `model:` frontmatter.
     */
    fun hasModel(): Boolean = model != null && model!!.isValid()

    override fun toString(): String {
        val modelStr = model?.promptString() ?: "inherit"
        return "AgentModelBinding($agentName -> $modelStr)"
    }
}
