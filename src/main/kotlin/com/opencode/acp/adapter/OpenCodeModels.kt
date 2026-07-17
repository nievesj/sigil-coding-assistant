package com.opencode.acp.adapter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Internal HTTP response/request types for the OpenCode REST API.
 *
 * This file contains pure data models only — no serialization logic
 * (see [OpenCodePartSerializer]) or conversion logic (see [OpenCodeMessageConverter]).
 */

@Serializable
data class OpenCodeSession(
    val id: String,
    val slug: String = "",
    @SerialName("projectID") val projectID: String = "",
    val directory: String = "",
    val path: String = "",
    @SerialName("parentID") val parentID: String? = null,
    val title: String = "",
    val agent: String? = null,
    val model: SessionModel? = null,
    val version: String = "",
    val cost: Double = 0.0,
    val tokens: SessionTokens? = null,
    val time: SessionTime? = null,
    val summary: SessionSummary? = null
)

@Serializable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0
)

@Serializable
data class SessionModel(
    val id: String = "",
    @SerialName("providerID") val providerID: String = "",
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
data class TodoItem(
    val content: String,
    val status: String,
    val priority: String
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
    val error: MessageError? = null,
    // Fields from AssistantMessage — present only when role == "assistant"
    @SerialName("modelID") val modelID: String? = null,
    @SerialName("providerID") val providerID: String? = null,
    val cost: Double? = null,
    val tokens: SessionTokens? = null
)

@Serializable
data class MessageError(
    val name: String = "",
    val message: String? = null,
    val retries: Int? = null
)

@Serializable(OpenCodePartSerializer::class)
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
        @SerialName("tool")
        val name: String = "tool",
        val input: JsonObject = kotlinx.serialization.json.buildJsonObject {},
        // Title is in state.title on the server, injected by OpenCodePartSerializer
        val title: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String,
        val content: List<OpenCodePart>,
        val isError: Boolean = false
    ) : OpenCodePart

    @Serializable
    @SerialName("step-start")
    data class StepStart(
        val snapshot: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("step-finish")
    data class StepFinish(
        val snapshot: String? = null,
        /** Token usage fields — nullable because server may not send all fields.
         *  Verify actual server payload before relying on these. */
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val reasoningTokens: Long? = null,
        val totalCost: Double? = null,
    ) : OpenCodePart

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : OpenCodePart

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : OpenCodePart

    /**
     * **Inbound-only.** Used to parse assistant-generated image parts from SSE events
     * (see `ContentMapper.kt`). Must NEVER be used for outbound messages — the OpenCode
     * server does not recognize `type: "image"` parts on the request wire. Outbound
     * images must use [OpenCodePart.File] with a `data:` URI (e.g.
     * `data:image/png;base64,...`) and `mime` set to the image MIME type.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val mime: String,
        val url: String,
        val filename: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("patch")
    data class Patch(
        val hash: String,
        val files: List<String>
    ) : OpenCodePart

    @Serializable
    @SerialName("agent")
    data class Agent(
        val name: String
    ) : OpenCodePart

    @Serializable
    @SerialName("retry")
    data class Retry(
        val attempt: Int = 1,
        @SerialName("maxAttempts") val maxAttempts: Int = 3,
        val error: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("compaction")
    data class Compaction(
        val summary: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val id: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("subtask")
    data class Subtask(
        val prompt: String? = null,
        val description: String? = null,
        val agent: String? = null,
        val model: String? = null
    ) : OpenCodePart

    /** Catch-all for any part type the server sends that we don't recognize. */
    data class Unknown(val type: String, val rawJson: JsonObject) : OpenCodePart
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
    val id: String? = null,
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