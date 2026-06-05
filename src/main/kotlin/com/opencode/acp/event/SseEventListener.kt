package com.opencode.acp.event

import com.opencode.acp.PlanEntry
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Listens to OpenCode SSE events from /global/event, parses them into
 * typed SseEvent hierarchy, and filters by sessionId/correlationId.
 */
class SseEventListener(
    private val openCodeClient: OpenCodeClient,
    private val sessionId: String,
    private val correlationId: String,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val events: Flow<SseEvent> = openCodeClient.subscribeGlobalEvents()
        .map { rawEvent ->
            // Filter to events for this session
            if (rawEvent.sessionId != sessionId) null else rawEvent
        }
        .filterNotNull()
        .catch { e ->
            if (e !is CancellationException) {
                logger.error(e) { "SSE event stream error" }
            }
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    companion object {
        /**
         * Parse an SSE data line into an SseEvent.
         * The data is expected to be a JSON object with a "type" field.
         */
        fun parseEvent(data: String, sessionId: String): SseEvent? {
            return try {
                val element = Json.parseToJsonElement(data).jsonObject
                val type = element["type"]?.jsonPrimitive?.content ?: return null
                parseByType(type, element, sessionId)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse SSE event: $data" }
                null
            }
        }

        private fun parseByType(type: String, obj: JsonObject, sessionId: String): SseEvent? {
            return when (type) {
                "text_chunk" -> SseEvent.TextChunk(
                    sessionId = sessionId,
                    text = obj["text"]?.jsonPrimitive?.content ?: ""
                )
                "tool_use" -> SseEvent.ToolUse(
                    sessionId = sessionId,
                    toolCallId = obj["toolCallId"]?.jsonPrimitive?.content ?: "",
                    toolName = obj["toolName"]?.jsonPrimitive?.content ?: "",
                    title = obj["title"]?.jsonPrimitive?.content,
                    input = obj["input"]?.jsonObject
                )
                "tool_result" -> SseEvent.ToolResult(
                    sessionId = sessionId,
                    toolCallId = obj["toolCallId"]?.jsonPrimitive?.content ?: "",
                    isError = obj["isError"]?.jsonPrimitive?.let {
                        if (it.isString) it.content.lowercase() == "true" else it.toString().lowercase() == "true"
                    } ?: false,
                    content = (obj["content"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                )
                "plan" -> SseEvent.Plan(
                    sessionId = sessionId,
                    entries = (obj["entries"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { entry ->
                            val entryObj = (entry as? JsonObject) ?: return@mapNotNull null
                            PlanEntry(
                                description = entryObj["description"]?.jsonPrimitive?.content ?: "",
                                priority = entryObj["priority"]?.jsonPrimitive?.content ?: "medium",
                                status = entryObj["status"]?.jsonPrimitive?.content ?: "pending"
                            )
                        } ?: emptyList()
                )
                "stop" -> SseEvent.Stop(
                    sessionId = sessionId,
                    stopReason = obj["stopReason"]?.jsonPrimitive?.content ?: "end_turn"
                )
                "permission" -> SseEvent.Permission(
                    sessionId = sessionId,
                    permissionId = obj["permissionId"]?.jsonPrimitive?.content ?: "",
                    toolCallId = obj["toolCallId"]?.jsonPrimitive?.content ?: "",
                    action = obj["action"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content
                )
                "error" -> SseEvent.Error(
                    sessionId = sessionId,
                    message = obj["message"]?.jsonPrimitive?.content ?: "Unknown error",
                    code = obj["code"]?.jsonPrimitive?.intOrNull
                )
                "session_created" -> SseEvent.SessionCreated(sessionId = sessionId)
                "message_complete" -> SseEvent.MessageComplete(
                    sessionId = sessionId,
                    messageId = obj["messageId"]?.jsonPrimitive?.content ?: ""
                )
                else -> {
                    logger.debug { "Unknown SSE event type: $type" }
                    null
                }
            }
        }
    }
}
