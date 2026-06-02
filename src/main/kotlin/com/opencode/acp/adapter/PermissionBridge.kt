package com.opencode.acp.adapter

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind

/**
 * Bridges OpenCode permission events to ACP [PermissionOption] lists.
 */
class PermissionBridge {

    /**
     * Returns the default set of permission options for user-facing permission prompts.
     * These map to the standard allow/reject/always-allow pattern.
     */
    fun defaultOptions(): List<PermissionOption> = listOf(
        PermissionOption(
            optionId = PermissionOptionId("allow-once"),
            name = "Allow once",
            kind = PermissionOptionKind.ALLOW_ONCE
        ),
        PermissionOption(
            optionId = PermissionOptionId("reject-once"),
            name = "Reject",
            kind = PermissionOptionKind.REJECT_ONCE
        ),
        PermissionOption(
            optionId = PermissionOptionId("allow-always"),
            name = "Always allow",
            kind = PermissionOptionKind.ALLOW_ALWAYS
        )
    )

    /**
     * Maps a response string to a PermissionOption by [optionId].
     * Returns null if no matching option is found.
     */
    fun findOption(optionId: String): PermissionOption? =
        defaultOptions().find { it.optionId.value == optionId }
}
