package com.opencode.acp.adapter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Internal HTTP response/request types for the OpenCode REST API.
 */

@Serializable
data class OpenCodeSession(
    val id: String,
    val slug: String = "",
    val projectID: String = "",
    val directory: String = "",
    val path: String = "",
    val parentID: String? = null,
    val title: String = "",
    val agent: String? = null,
    val model: SessionModel? = null,
    val version: String = "",
    val cost: Double = 0.0,
    val tokens: SessionTokens? = null,
    val time: SessionTime? = null
)

@Serializable
data class SessionModel(
    val id: String = "",
    val providerID: String = "",
    val variant: String? = null
)

@Serializable
data class SessionTokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: TokenCache = TokenCache()
)

@Serializable
data class TokenCache(
    val read: Long = 0,
    val write: Long = 0
)

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0
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
    val name: String = "",
    val message: String? = null,
    val retries: Int? = null
)

@Serializable
sealed interface OpenCodePart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : OpenCodePart

    @Serializable
    @SerialName("file")
    data class File(
        val mime: String,
        val url: String,
        val filename: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : OpenCodePart

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String,
        val content: List<OpenCodePart>,
        val isError: Boolean = false
    ) : OpenCodePart
}

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null
) {
    val id: String get() = name
}

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
