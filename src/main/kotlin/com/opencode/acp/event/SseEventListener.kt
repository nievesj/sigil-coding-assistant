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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
            // Strip V2 version suffix (e.g. "session.next.text.delta.1" → "session.next.text.delta")
            val normalizedType = type.replace(Regex("\\.\\d+$"), "")

            return when (normalizedType) {
                // V2 Text events
                "session.next.text.started" -> SseEvent.Ignored(sessionId, "session.next.text.started", "intentional no-op")
                "session.next.text.delta" -> SseEvent.TextChunk(
                    sessionId = sessionId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "session.next.text.ended" -> SseEvent.TextReplace(
                    sessionId = sessionId,
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                )

                // V2 Reasoning events
                "session.next.reasoning.started" -> SseEvent.Ignored(sessionId, "session.next.reasoning.started", "intentional no-op")
                "session.next.reasoning.delta" -> SseEvent.ThinkingChunk(
                    sessionId = sessionId,
                    text = obj["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "session.next.reasoning.ended" -> SseEvent.ThinkingReplace(
                    sessionId = sessionId,
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                )

                // V2 Tool events
                "session.next.tool.input.started" -> null
                "session.next.tool.called" -> SseEvent.ToolUse(
                    sessionId = sessionId,
                    toolCallId = obj["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                    toolName = obj["tool"]?.jsonPrimitive?.contentOrNull ?: "tool",
                    title = obj["tool"]?.jsonPrimitive?.contentOrNull ?: "tool",
                    input = obj["input"]?.jsonObject
                )
                "session.next.tool.success" -> SseEvent.ToolResult(
                    sessionId = sessionId,
                    toolCallId = obj["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                    isError = false
                )
                "session.next.tool.failed" -> SseEvent.ToolResult(
                    sessionId = sessionId,
                    toolCallId = obj["callID"]?.jsonPrimitive?.contentOrNull ?: "",
                    isError = true
                )

                // V2 Step events
                "session.next.step.started" -> SseEvent.Ignored(sessionId, "session.next.step.started", "intentional no-op")
                "session.next.step.ended" -> SseEvent.Ignored(sessionId, "session.next.step.ended", "intentional no-op")
                "session.next.step.failed" -> SseEvent.Ignored(sessionId, "session.next.step.failed", "intentional no-op")

                // V2 Prompted event
                "session.next.prompted" -> {
                    val prompt = obj["prompt"]?.jsonObject
                    if (prompt != null) {
                        SseEvent.UserMessage(
                            sessionId = sessionId,
                            text = prompt["text"]?.jsonPrimitive?.contentOrNull ?: "",
                            files = prompt["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        )
                    } else null
                }

                // V2 Permission event
                "session.next.permission" -> SseEvent.Permission(
                    sessionId = sessionId,
                    permissionId = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    toolCallId = obj["tool"]?.jsonObject?.get("callID")?.jsonPrimitive?.contentOrNull ?: "",
                    action = obj["permission"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull,
                    patterns = obj["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                )

                // V2 Question event
                "session.next.question" -> {
                    val requestId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
                    val questionsArray = obj["questions"]?.jsonArray ?: return null
                    val questions = questionsArray.mapNotNull { element ->
                        val qObj = element.jsonObject
                        val question = qObj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val header = qObj["header"]?.jsonPrimitive?.contentOrNull ?: ""
                        val multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
                        val custom = qObj["custom"]?.jsonPrimitive?.booleanOrNull ?: true
                        val options = qObj["options"]?.jsonArray?.mapNotNull { optElement ->
                            val optObj = optElement.jsonObject
                            val label = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val desc = optObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                            com.opencode.acp.SseQuestionOption(label = label, description = desc)
                        } ?: emptyList()
                        com.opencode.acp.SseQuestionInfo(
                            question = question,
                            header = header,
                            options = options,
                            multiple = multiple,
                            custom = custom
                        )
                    }
                    SseEvent.QuestionAsked(
                        sessionId = sessionId,
                        requestId = requestId,
                        questions = questions
                    )
                }

                // V2 Todo event
                "session.next.todo.updated" -> {
                    val todosArray = obj["todos"]?.jsonArray
                    if (todosArray != null) {
                        val todos = todosArray.mapNotNull { element ->
                            val todoObj = element.jsonObject
                            val content = todoObj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val status = todoObj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                            val priority = todoObj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium"
                            com.opencode.acp.SseTodoItem(content = content, status = status, priority = priority)
                        }
                        SseEvent.TodoUpdated(sessionId = sessionId, todos = todos)
                    } else null
                }

                // V2 Stop event
                "session.next.stopped" -> SseEvent.Stop(
                    sessionId = sessionId,
                    stopReason = obj["stopReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
                )

                // V2 Session created
                "session.next.created" -> SseEvent.SessionCreated(sessionId = sessionId)

                // V2 new part type events
                "session.next.patch" -> {
                    val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: return null
                    val files = obj["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    SseEvent.Patch(sessionId = sessionId, hash = hash, files = files)
                }
                "session.next.agent" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.Agent(sessionId = sessionId, agentName = name)
                }
                "session.next.retry" -> {
                    val attempt = obj["attempt"]?.jsonPrimitive?.intOrNull ?: 1
                    val maxAttempts = obj["maxAttempts"]?.jsonPrimitive?.intOrNull ?: 3
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Retry(sessionId = sessionId, attempt = attempt, maxAttempts = maxAttempts, error = error)
                }
                "session.next.compaction" -> {
                    val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Compaction(sessionId = sessionId, summary = summary)
                }
                "session.next.subtask" -> {
                    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
                    val description = obj["description"]?.jsonPrimitive?.contentOrNull
                    val agent = obj["agent"]?.jsonPrimitive?.contentOrNull
                    val model = obj["model"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Subtask(sessionId = sessionId, prompt = prompt, description = description, agent = agent, model = model)
                }

                // Legacy events
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
                    description = obj["description"]?.jsonPrimitive?.content,
                    patterns = obj["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
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
