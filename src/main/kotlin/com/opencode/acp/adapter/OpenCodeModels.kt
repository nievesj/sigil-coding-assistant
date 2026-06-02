package com.opencode.acp.adapter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Internal HTTP response/request types for the OpenCode REST API.
 */

@Serializable
data class OpenCodeSession(
    val id: String,
    val title: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class OpenCodeMessage(
    val info: MessageInfo,
    val parts: List<OpenCodePart>
)

@Serializable
data class MessageInfo(
    val id: String,
    val role: String,
    val createdAt: String? = null,
    val error: MessageError? = null
)

@Serializable
data class MessageError(
    val name: String,
    val message: String,
    val retries: Int? = null
)

@Serializable
sealed interface OpenCodePart {
    @Serializable
    data class Text(val text: String) : OpenCodePart

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : OpenCodePart

    @Serializable
    data class ToolResult(
        val toolUseId: String,
        val content: List<OpenCodePart>,
        val isError: Boolean = false
    ) : OpenCodePart
}

@Serializable
data class AgentInfo(
    val id: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class CommandInfo(
    val id: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class ConfigData(
    val model: String? = null,
    val agent: String? = null,
    val provider: String? = null
)

data class TerminalOutputChunk(
    val terminalId: String,
    val text: String,
    val isStderr: Boolean = false
)
