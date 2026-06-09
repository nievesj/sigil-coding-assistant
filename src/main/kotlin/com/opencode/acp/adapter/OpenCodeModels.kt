package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.ui.compose.MarkdownSegmenter
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Internal HTTP response/request types for the OpenCode REST API.
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
        val filename: String? = null,
        val source: FileSource? = null
    ) : OpenCodePart

    @Serializable
    data class FileSource(
        val type: String = "file",
        val path: String,
        val text: FileSourceText? = null
    )

    @Serializable
    data class FileSourceText(
        val value: String,
        val start: Int = 0,
        val end: Int? = null
    )

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
        "image" to OpenCodePart.Image.serializer(),
        "tool" to OpenCodePart.ToolUse.serializer(),
        "patch" to OpenCodePart.Patch.serializer(),
        "agent" to OpenCodePart.Agent.serializer(),
        "retry" to OpenCodePart.Retry.serializer(),
        "compaction" to OpenCodePart.Compaction.serializer(),
        "snapshot" to OpenCodePart.Snapshot.serializer(),
        "subtask" to OpenCodePart.Subtask.serializer()
    )

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OpenCodePart")

    override fun serialize(encoder: Encoder, value: OpenCodePart) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: error("OpenCodePartSerializer requires JsonEncoder")
        val jsonElement: kotlinx.serialization.json.JsonElement = when (value) {
            is OpenCodePart.Text -> kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                put("text", kotlinx.serialization.json.JsonPrimitive(value.text))
            }
            is OpenCodePart.File -> kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("file"))
                put("mime", kotlinx.serialization.json.JsonPrimitive(value.mime))
                put("url", kotlinx.serialization.json.JsonPrimitive(value.url))
                value.filename?.let { put("filename", kotlinx.serialization.json.JsonPrimitive(it)) }
                value.source?.let { src ->
                    put("source", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive(src.type))
                        put("path", kotlinx.serialization.json.JsonPrimitive(src.path))
                        src.text?.let { txt ->
                            put("text", kotlinx.serialization.json.buildJsonObject {
                                put("value", kotlinx.serialization.json.JsonPrimitive(txt.value))
                                put("start", kotlinx.serialization.json.JsonPrimitive(txt.start))
                                put("end", kotlinx.serialization.json.JsonPrimitive(txt.end ?: txt.value.length))
                            })
                        }
                    })
                }
            }
            is OpenCodePart.Image -> kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("image"))
                put("mime", kotlinx.serialization.json.JsonPrimitive(value.mime))
                put("url", kotlinx.serialization.json.JsonPrimitive(value.url))
                value.filename?.let { put("filename", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            else -> {
                // ToolUse, ToolResult, StepStart, StepFinish, Thinking, Reasoning,
                // Patch, Agent, Retry, Compaction, Snapshot, Subtask, Unknown
                // These aren't sent outbound — encode as empty object
                kotlinx.serialization.json.buildJsonObject {}
            }
        }
        jsonEncoder.encodeJsonElement(jsonElement)
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
            // Special handling for tool/tool_use: server puts input inside state.input,
            // but ToolUse expects it at top level. Restructure before deserializing.
            if ((type == "tool" || type == "tool_use") && element["input"] == null) {
                val stateObj = element["state"]?.jsonObject
                val stateInput = stateObj?.get("input")?.jsonObject
                val stateTitle = stateObj?.get("title")?.jsonPrimitive?.contentOrNull
                val stateOutput = stateObj?.get("output")?.let { output ->
                    // Normalize output: if it's a string, wrap in a text JSON array for ToolResult
                    if (output is JsonPrimitive) {
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("text", output)
                            })
                        }
                    } else output
                }
                val reconstructed = kotlinx.serialization.json.buildJsonObject {
                    for ((key, value) in element) put(key, value)
                    if (stateInput != null) put("input", stateInput!!)
                    // Inject state.title at top level for ToolUse
                    if (stateTitle != null) put("title", kotlinx.serialization.json.JsonPrimitive(stateTitle))
                    // Also inject state.output at top level for ToolResult
                    if (stateOutput != null && element["output"] == null) put("output", stateOutput!!)
                }
                return json.decodeFromJsonElement(serializer, reconstructed)
            }
            return json.decodeFromJsonElement(serializer, element)
        }

        // Unknown type — capture it as-is without crashing
        logger.debug { "[ACP] OpenCodePartSerializer: unknown part type='$type', keys=${element.keys}" }
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

/** Convert an OpenCodeSession API model to a sidebar display model. */
fun OpenCodeSession.toSessionItem() = SessionItem(
    id = id,
    title = title.ifBlank { "New session" },
    updatedAt = time?.updated ?: time?.created ?: 0L,
    cost = cost,
    inputTokens = tokens?.input ?: 0L,
    outputTokens = tokens?.output ?: 0L,
    parentID = parentID
)

/** Convert an OpenCodeMessage API model to a ChatMessage UI model. */
fun OpenCodeMessage.toChatMessage(): ChatMessage {
    return try {
        toChatMessageInternal()
    } catch (e: Exception) {
            logger.error(e) { "[ACP] toChatMessage FAILED for msg=${info.id}: ${e.message}" }
        val timestamp = info.createdAt?.let { raw ->
            try { java.time.Instant.parse(raw).toEpochMilli() } catch (_: Exception) { raw.toLongOrNull() ?: 0L }
        } ?: 0L
        ChatMessage(
            id = info.id,
            role = if (info.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Error("Failed to load message: ${e.message ?: e::class.simpleName}")),
            timestamp = timestamp,
            isStreaming = false,
            modelID = info.modelID,
            providerID = info.providerID,
            serverMessageId = info.id,
        )
    }
}

/** Internal conversion — may throw on malformed data. */
private fun OpenCodeMessage.toChatMessageInternal(): ChatMessage {
    val role = when (info.role) {
        "user" -> MessageRole.USER
        else -> MessageRole.ASSISTANT
    }

    val partTypes = this.parts.map { it::class.simpleName ?: "Unknown" }
    logger.debug { "[ACP] toChatMessage: msg=${info.id}, role=${info.role}, parts=${this.parts.size}, types=$partTypes" }

    // Build parts map from OpenCodeParts
    val parts = linkedMapOf<String, MessagePart>()

    // Tool call pills from ToolUse parts, matching with ToolResult parts
    val toolUseParts = this.parts.filterIsInstance<OpenCodePart.ToolUse>()
    val toolResultsByUseId = this.parts.filterIsInstance<OpenCodePart.ToolResult>()
        .groupBy { it.toolUseId }

    // Thinking content
    val thinkingContent = this.parts
        .filterIsInstance<OpenCodePart.Thinking>()
        .joinToString("") { it.text }
    val reasoningContent = this.parts
        .filterIsInstance<OpenCodePart.Reasoning>()
        .joinToString("") { it.text }
    val allThinking = thinkingContent + reasoningContent
    if (allThinking.isNotBlank()) {
        parts[MessagePart.generatePartId()] = MessagePart.Thinking(allThinking, state = PartState.Completed)
    }

    // Tool calls
    toolUseParts.forEach { toolUse ->
        val results = toolResultsByUseId[toolUse.id]
        val hasResult = results != null && results.isNotEmpty()
        val anyError = results?.any { it.isError } == true
        val status = when {
            !hasResult -> ToolCallStatus.COMPLETED
            anyError -> ToolCallStatus.FAILED
            else -> ToolCallStatus.COMPLETED
        }
        // Extract output text from tool results for display in expanded pills
        val output = results?.flatMap { result ->
            result.content.mapNotNull { part ->
                when (part) {
                    is OpenCodePart.Text -> buildJsonObject { put("text", JsonPrimitive(part.text)) }
                    else -> null
                }
            }
        }?.ifEmpty { null }
        val baseKind = ToolMapper.toAcpKind(toolUse.name)
        val inputObj = toolUse.input.takeIf { it.isNotEmpty() }
        val resolvedKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(inputObj) else baseKind
        // Resolve title: prefer input.description, then state.title (from server), then tool name
        val resolvedTitle = inputObj?.let { input ->
            try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        } ?: toolUse.title
        ?: toolUse.name
        logger.info { "[ACP] REST ToolUse: id=${toolUse.id}, name=${toolUse.name}, kind=$baseKind->$resolvedKind, title=$resolvedTitle, stateTitle=${toolUse.title}, hasInput=${inputObj != null}, inputKeys=${inputObj?.keys}" }
        parts[toolUse.id] = MessagePart.ToolCall(
            pill = ToolCallPill(
                toolCallId = toolUse.id,
                toolName = toolUse.name,
                title = resolvedTitle,
                kind = resolvedKind,
                status = status,
                input = inputObj,
                output = output
            ),
            // Loaded from REST — already completed or failed
            state = if (anyError) com.opencode.acp.chat.model.PartState.Failed("Tool error") else com.opencode.acp.chat.model.PartState.Completed
        )
    }

    // Text content — segment into Text/Code/Table parts
    val textParts = this.parts.filterIsInstance<OpenCodePart.Text>()
    val textContent = textParts.joinToString("") { it.text }

    // File note
    val fileParts = this.parts.filterIsInstance<OpenCodePart.File>()
    val fileNote = if (fileParts.isNotEmpty()) {
        val names = fileParts.mapNotNull { it.filename }.ifEmpty { listOf("${fileParts.size} file(s)") }
        "\n\n📎 ${names.joinToString(", ")}"
    } else ""

    // Segment text content (without error) into MessageParts
    val fullText = textContent + fileNote
    if (fullText.isNotBlank()) {
        val segments = MarkdownSegmenter.segment(fullText)
        segments.forEach { segment ->
            when (segment.type) {
                com.opencode.acp.chat.ui.compose.MarkdownSegment.Type.TEXT -> {
                    if (segment.content.isNotBlank()) parts[MessagePart.generatePartId()] = MessagePart.Text(segment.content)
                }
                com.opencode.acp.chat.ui.compose.MarkdownSegment.Type.CODE -> {
                    if (segment.content.isNotBlank()) parts[MessagePart.generatePartId()] = MessagePart.Code(segment.language ?: "", segment.content)
                }
                com.opencode.acp.chat.ui.compose.MarkdownSegment.Type.TABLE -> {
                    val parsed = MarkdownSegmenter.parseTable(segment.content.lines())
                    if (parsed != null) {
                        parts[MessagePart.generatePartId()] = MessagePart.Table(
                            rawMarkdown = segment.content,
                            headers = parsed.header,
                            rows = parsed.rows,
                            alignments = parsed.alignments
                        )
                    } else {
                        parts[MessagePart.generatePartId()] = MessagePart.Text(segment.content)
                    }
                }
                com.opencode.acp.chat.ui.compose.MarkdownSegment.Type.TASK -> {
                    val state = segment.taskAttrs?.get("state") ?: "completed"
                    val status = when (state) {
                        "completed" -> com.agentclientprotocol.model.ToolCallStatus.COMPLETED
                        "failed" -> com.agentclientprotocol.model.ToolCallStatus.FAILED
                        else -> com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS
                    }
                    val agentId = segment.taskAttrs?.get("id") ?: ""
                    val output = listOf(kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive(segment.content))
                    ))
                    val pill = ToolCallPill(
                        toolCallId = "task_${agentId.hashCode().toString(16).takeLast(8)}",
                        toolName = "task",
                        title = "task",
                        kind = com.agentclientprotocol.model.ToolKind.OTHER,
                        status = status,
                        output = output,
                    )
                    parts[MessagePart.generatePartId()] = MessagePart.ToolCall(
                        pill = pill,
                        state = if (status == com.agentclientprotocol.model.ToolCallStatus.COMPLETED) com.opencode.acp.chat.model.PartState.Completed else com.opencode.acp.chat.model.PartState.InProgress
                    )
                }
            }
        }
    }

    // Error part (separate from text-derived parts — renders with red styling, not markdown)
    val errorPart = info.error?.let { err ->
        val description = err.message?.ifBlank { null } ?: err.name.ifBlank { null }
        description?.let { d ->
            val retries = err.retries?.let { " (${it} retries)" } ?: ""
            MessagePart.Error("$d$retries")
        }
    }
    if (errorPart != null) {
        parts[MessagePart.generatePartId()] = errorPart
    }

    // Patch parts — show file change summary
    val patchParts = this.parts.filterIsInstance<OpenCodePart.Patch>()
    patchParts.forEach { patch ->
        parts[MessagePart.generatePartId()] = MessagePart.Patch(hash = patch.hash, files = patch.files)
    }

    // Agent parts — show agent badge
    val agentParts = this.parts.filterIsInstance<OpenCodePart.Agent>()
    agentParts.forEach { agent ->
        parts[MessagePart.generatePartId()] = MessagePart.Agent(name = agent.name)
    }

    // StepFinish parts — show token usage
    val stepFinishParts = this.parts.filterIsInstance<OpenCodePart.StepFinish>()
    // StepFinish with snapshot is informational; only render if it has meaningful data
    // (token counts are at message level, not part level in current server)

    // Retry parts
    val retryParts = this.parts.filterIsInstance<OpenCodePart.Retry>()
    retryParts.forEach { retry ->
        parts[MessagePart.generatePartId()] = MessagePart.Retry(attempt = retry.attempt, maxAttempts = retry.maxAttempts, error = retry.error)
    }

    // Compaction parts
    val compactionParts = this.parts.filterIsInstance<OpenCodePart.Compaction>()
    compactionParts.forEach { compaction ->
        parts[MessagePart.generatePartId()] = MessagePart.Compaction(summary = compaction.summary)
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
        parts = parts,
        timestamp = timestamp,
        isStreaming = false,
        // Loaded from REST — already completed
        state = com.opencode.acp.chat.model.MessageState.Completed,
        modelID = info.modelID,
        providerID = info.providerID,
        inputTokens = info.tokens?.input ?: 0L,
        outputTokens = info.tokens?.output ?: 0L,
        reasoningTokens = info.tokens?.reasoning ?: 0L,
        cacheReadTokens = info.tokens?.cache?.read ?: 0L,
        cacheWriteTokens = info.tokens?.cache?.write ?: 0L,
        cost = info.cost ?: 0.0,
        serverMessageId = info.id,
    )
}
