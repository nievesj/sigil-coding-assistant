package com.opencode.acp.config.settings

import com.opencode.acp.config.AgentConstants

/**
 * Council member model configuration (providerID + modelID + optional thinking variant).
 * Non-data class for XStream compatibility (mirrors CommandHistoryEntry pattern).
 * Must have no-arg constructor + var fields for XStream reflection.
 */
class CouncilMember {
    var providerID: String = ""
    var modelID: String = ""

    /** Thinking effort variant (e.g. "low", "medium", "high", "max"). Empty = server default. */
    var thinkingVariant: String = ""

    // Regex: alphanumeric, hyphen, underscore, dot. Max 128 chars.
    // Prevents YAML injection in frontmatter AND parsing ambiguity in promptString()
    // (which uses '/' as the providerID/modelID delimiter — allowing '/' in either
    // field would break the council prompt's "split on the FIRST slash" recovery).
    // Uses the shared AgentConstants.YAML_SAFE_IDENTIFIER (DRY — same regex as
    // AgentConfigWriter.buildTaskPermissionYaml, kept in sync via the shared constant).
    private val validPattern = AgentConstants.YAML_SAFE_IDENTIFIER

    constructor()
    constructor(providerID: String, modelID: String) {
        this.providerID = providerID
        this.modelID = modelID
    }

    constructor(providerID: String, modelID: String, thinkingVariant: String) {
        this.providerID = providerID
        this.modelID = modelID
        this.thinkingVariant = thinkingVariant
    }

    fun isValid(): Boolean = providerID.isNotBlank() && modelID.isNotBlank()
            && validPattern.matches(providerID) && validPattern.matches(modelID)
            && (thinkingVariant.isBlank() || validPattern.matches(thinkingVariant))

    /** Returns "providerID/modelID" for display. */
    fun modelString(): String = "$providerID/$modelID"

    /**
     * Returns the member line for the council prompt. Includes the thinking
     * variant as a suffix when set: `providerID/modelID:variant`.
     * The council prompt instructs the LLM to pass the variant to the task tool.
     */
    fun promptString(): String {
        val base = modelString()
        return if (thinkingVariant.isBlank()) base else "$base:$thinkingVariant"
    }

    override fun toString(): String = promptString()
}