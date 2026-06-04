package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.ToolCallPill
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    @Serializable
    @SerialName("step-start")
    data class StepStart(
        val snapshot: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("step-finish")
    data class StepFinish(
        val snapshot: String? = null
    ) : OpenCodePart

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : OpenCodePart

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : OpenCodePart

    @Serializable
    @SerialName("image")
    data class Image(
        val mime: String,
        val url: String,
        val filename: String? = null
    ) : OpenCodePart

    /** Catch-all for any part type the server sends that we don't recognize. */
    data class Unknown(val type: String, val rawJson: JsonObject) : OpenCodePart
}

/**
 * Custom serializer for [OpenCodePart] that gracefully handles unknown part types.
 * Instead of crashing when the server sends a type we don't recognize (e.g. "reasoning",
 * "step-start"), it falls back to [OpenCodePart.Unknown].
 *
 * Each known type is deserialized using its own specific serializer to avoid the
 * infinite recursion that would occur if we called json.decodeFromString<OpenCodePart>().
 */
object OpenCodePartSerializer : KSerializer<OpenCodePart> {
    private val json = Json { ignoreUnknownKeys = true }

    // Dispatch map: type discriminator -> specific serializer
    private val knownTypes: Map<String, KSerializer<out OpenCodePart>> = mapOf(
        "text" to OpenCodePart.Text.serializer(),
        "file" to OpenCodePart.File.serializer(),
        "tool_use" to OpenCodePart.ToolUse.serializer(),
        "tool_result" to OpenCodePart.ToolResult.serializer(),
        "step-start" to OpenCodePart.StepStart.serializer(),
        "step-finish" to OpenCodePart.StepFinish.serializer(),
        "thinking" to OpenCodePart.Thinking.serializer(),
        "reasoning" to OpenCodePart.Reasoning.serializer(),
        "image" to OpenCodePart.Image.serializer()
    )

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OpenCodePart")

    override fun serialize(encoder: Encoder, value: OpenCodePart) {
        when (value) {
            is OpenCodePart.Text -> encoder.encodeSerializableValue(OpenCodePart.Text.serializer(), value)
            is OpenCodePart.File -> encoder.encodeSerializableValue(OpenCodePart.File.serializer(), value)
            is OpenCodePart.ToolUse -> encoder.encodeSerializableValue(OpenCodePart.ToolUse.serializer(), value)
            is OpenCodePart.ToolResult -> encoder.encodeSerializableValue(OpenCodePart.ToolResult.serializer(), value)
            is OpenCodePart.StepStart -> encoder.encodeSerializableValue(OpenCodePart.StepStart.serializer(), value)
            is OpenCodePart.StepFinish -> encoder.encodeSerializableValue(OpenCodePart.StepFinish.serializer(), value)
            is OpenCodePart.Thinking -> encoder.encodeSerializableValue(OpenCodePart.Thinking.serializer(), value)
            is OpenCodePart.Reasoning -> encoder.encodeSerializableValue(OpenCodePart.Reasoning.serializer(), value)
            is OpenCodePart.Image -> encoder.encodeSerializableValue(OpenCodePart.Image.serializer(), value)
            is OpenCodePart.Unknown -> { /* Unknown types are not serialized back */ }
        }
    }

    override fun deserialize(decoder: Decoder): OpenCodePart {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("OpenCodePartSerializer requires JsonDecoder")

        val element = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: error("OpenCodePart must be a JSON object")

        val type = (element["type"] as? JsonPrimitive)?.contentOrNull

        // Look up the specific serializer for this type
        val serializer = knownTypes[type]
        if (serializer != null) {
            return json.decodeFromJsonElement(serializer, element)
        }

        // Unknown type — capture it as-is without crashing
        return OpenCodePart.Unknown(type ?: "unknown", element)
    }
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

/** Convert an OpenCodeSession API model to a sidebar display model. */
fun OpenCodeSession.toSessionItem() = SessionItem(
    id = id,
    title = title.ifBlank { "New session" },
    createdAt = time?.updated ?: time?.created ?: 0L,
    cost = cost,
    inputTokens = tokens?.input ?: 0L,
    outputTokens = tokens?.output ?: 0L,
    parentID = parentID
)

/** Convert an OpenCodeMessage API model to a ChatMessage UI model. */
fun OpenCodeMessage.toChatMessage(): ChatMessage {
    val role = when (info.role) {
        "user" -> MessageRole.USER
        else -> MessageRole.ASSISTANT
    }

    // Check for errors — surface failed messages (skip blank error info)
    val errorSuffix = info.error?.let { err ->
        val description = err.message?.ifBlank { null } ?: err.name.ifBlank { null }
        description?.let { d ->
            val retries = err.retries?.let { " ($it retries)" } ?: ""
            "\n\n**Error${retries}:** $d"
        }
    } ?: ""

    // Concatenate all Text parts into a single content string.
    // File parts are noted as attachments (images/files cannot be rehydrated from history).
    val textParts = parts.filterIsInstance<OpenCodePart.Text>()
    val fileParts = parts.filterIsInstance<OpenCodePart.File>()
    val textContent = textParts.joinToString("") { it.text }
    val fileNote = if (fileParts.isNotEmpty()) {
        val names = fileParts.mapNotNull { it.filename }.ifEmpty { listOf("${fileParts.size} file(s)") }
        "\n\n📎 ${names.joinToString(", ")}"
    } else ""
    val content = textContent + fileNote + errorSuffix

    // Build ToolCallPill list from ToolUse parts, matching with ToolResult parts.
    // Use groupBy (not associateBy) to handle multiple ToolResults per toolUseId.
    val toolUseParts = parts.filterIsInstance<OpenCodePart.ToolUse>()
    val toolResultsByUseId = parts.filterIsInstance<OpenCodePart.ToolResult>()
        .groupBy { it.toolUseId }

    val toolCalls = toolUseParts.map { toolUse ->
        val results = toolResultsByUseId[toolUse.id]
        val hasResult = results != null && results.isNotEmpty()
        val anyError = results?.any { it.isError } == true
        val status = when {
            !hasResult -> ToolCallStatus.COMPLETED
            anyError -> ToolCallStatus.FAILED
            else -> ToolCallStatus.COMPLETED
        }
        ToolCallPill(
            toolCallId = toolUse.id,
            toolName = toolUse.name,
            title = toolUse.name,
            kind = ToolKind.OTHER,
            status = status
        )
    }

    // Parse createdAt timestamp — try ISO-8601 first, fall back to epoch millis
    val timestamp = info.createdAt?.let { raw ->
        try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            raw.toLongOrNull() ?: 0L
        }
    } ?: 0L

    return ChatMessage(
        id = info.id,
        role = role,
        content = content,
        timestamp = timestamp,
        toolCalls = toolCalls,
        thinkingContent = "",
        isStreaming = false
    )
}
