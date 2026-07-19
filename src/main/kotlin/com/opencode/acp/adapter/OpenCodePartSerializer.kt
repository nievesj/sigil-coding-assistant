package com.opencode.acp.adapter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.KSerializer
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

private val serializerLogger = KotlinLogging.logger {}

/**
 * Custom serializer for [OpenCodePart] that gracefully handles unknown part types.
 * Instead of crashing when the server sends a type we don't recognize (e.g. "reasoning",
 * "step-start"), it falls back to [OpenCodePart.Unknown].
 *
 * Each known type is deserialized using its own specific serializer to avoid the
 * infinite recursion that would occur if we called json.decodeFromString<OpenCodePart>().
 *
 * Extracted from [OpenCodeModels] per TDD §4.2.5 (SRP: Split OpenCodeModels).
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
                serializerLogger.warn { "[ACP] OpenCodePartSerializer.serialize(): encoding ${value::class.simpleName} as empty JSON object — this type is not expected outbound" }
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
                    for ((key, value) in element) {
                        if (key != "type" && key != "state") put(key, value)
                    }
                    stateInput?.let { put("input", it) }
                    // Inject state.title at top level for ToolUse
                    if (stateTitle != null) put("title", kotlinx.serialization.json.JsonPrimitive(stateTitle))
                    // Also inject state.output at top level for ToolResult
                    if (stateOutput != null && element["output"] == null) put("output", stateOutput)
                }
                return json.decodeFromJsonElement(serializer, reconstructed)
            }
            return json.decodeFromJsonElement(serializer, element)
        }

        // Unknown type — capture it as-is without crashing
        serializerLogger.debug { "[ACP] OpenCodePartSerializer: unknown part type='$type', keys=${element.keys}" }
        return OpenCodePart.Unknown(type ?: "unknown", element)
    }
}