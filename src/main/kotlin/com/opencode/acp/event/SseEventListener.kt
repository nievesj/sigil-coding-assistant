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
               val type = element["type"]?.jsonPrimitive?.content
                   ?: return SseEvent.Ignored(sessionId, "unknown", "parse error: missing type field")
               parseByType(type, element, sessionId)
           } catch (e: Exception) {
               logger.warn(e) { "Failed to parse SSE event: $data" }
                SseEvent.Ignored(sessionId, "parse_error", "parse exception: ${e.message}")
           }
        }

        // TODO (TDD Phase 1 follow-up): Replace this 267-line near-duplicate of
        // OpenCodeClient.parseSseEvent (now extracted into SseEventParser at
        // com.opencode.acp.adapter.sse.SseEventParser) with a call to
        // SseEventParser.parse(). This is deferred because parseByType has a
        // different contract (returns SseEvent? nullable, does not handle
        // message.part.delta/message.part.updated/message.updated, uses
        // "content" instead of "output" for tool_result, and does not pass
        // messageId/partId). The ACP path is not used by the plugin (see
        // AGENTS.md "ACP Mode") but is retained as a reference implementation.
        private fun parseByType(type: String, obj: JsonObject, sessionId: String): SseEvent? {
            // Strip V2 version suffix (e.g. "session.next.text.delta.1" → "session.next.text.delta")
            val normalizedType = type.replace(Regex("\\.\\d+$"), "")

            // V2 SyncEvents nest payload under "data"; V1 BusEvents nest under "properties".
            // Extract the payload container for field access. For V2 events, fields like
            // "sessionID", "delta", "callID" are inside "data". For V1, they're inside
            // "properties". If neither wrapper exists, use the root object (legacy flat format).
            val v2Data = obj["data"]?.jsonObject
            val props: JsonObject = when {
                v2Data != null -> v2Data
                obj["properties"] != null -> obj["properties"]?.jsonObject ?: obj
                else -> obj
            }

            return when (normalizedType) {
                // V2 Text events
                "session.next.text.started" -> SseEvent.Ignored(sessionId, "session.next.text.started", "intentional no-op")
                "session.next.text.delta" -> {
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull
                    if (delta != null) SseEvent.TextChunk(sessionId = sessionId, text = delta) else null
                }
                "session.next.text.ended" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: return null
                    SseEvent.TextReplace(sessionId = sessionId, text = text)
                }

                // V2 Reasoning events
                "session.next.reasoning.started" -> SseEvent.Ignored(sessionId, "session.next.reasoning.started", "intentional no-op")
                "session.next.reasoning.delta" -> {
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull
                    if (delta != null) SseEvent.ThinkingChunk(sessionId = sessionId, text = delta) else null
                }
                "session.next.reasoning.ended" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: return null
                    SseEvent.ThinkingReplace(sessionId = sessionId, text = text)
                }

                // V2 Tool events
                "session.next.tool.input.started" -> SseEvent.Ignored(sessionId, "session.next.tool.input.started", "intentional no-op — dedup: only tool.called creates a pill")
                "session.next.tool.called" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    val tool = props["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = callID,
                        toolName = tool,
                        title = tool,
                        input = props["input"]?.jsonObject
                    )
                }
                "session.next.tool.success" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.ToolResult(sessionId = sessionId, toolCallId = callID, isError = false)
                }
                "session.next.tool.failed" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.ToolResult(sessionId = sessionId, toolCallId = callID, isError = true)
                }

                // V2 Step events
                "session.next.step.started" -> SseEvent.Ignored(sessionId, "session.next.step.started", "intentional no-op")
                "session.next.step.ended" -> SseEvent.Ignored(sessionId, "session.next.step.ended", "intentional no-op")
                "session.next.step.failed" -> SseEvent.Ignored(sessionId, "session.next.step.failed", "intentional no-op")

                // V2 Prompted event
                "session.next.prompted" -> {
                    val prompt = props["prompt"]?.jsonObject
                    if (prompt != null) {
                        SseEvent.UserMessage(
                            sessionId = sessionId,
                            text = prompt["text"]?.jsonPrimitive?.contentOrNull ?: "",
                            files = prompt["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        )
                    } else null
                }

                // V2 Permission event
                "session.next.permission" -> {
                    val permissionId = props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return null
                    val permission = props["permission"]?.jsonPrimitive?.contentOrNull
                        ?: return null
                    SseEvent.Permission(
                        sessionId = sessionId,
                        permissionId = permissionId,
                        toolCallId = props["tool"]?.jsonObject?.get("callID")?.jsonPrimitive?.contentOrNull ?: "",
                        action = permission,
                        description = props["description"]?.jsonPrimitive?.contentOrNull,
                        patterns = props["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    )
                }

                "session.next.permission.replied" -> {
                    val sid = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId
                    SseEvent.PermissionReplied(
                        sessionId = sid,
                        permissionId = props["requestID"]?.jsonPrimitive?.contentOrNull
                            ?: props["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        reply = props["reply"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }

                // V2 Question event
                "session.next.question" -> {
                    val requestId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
                    val questionsArray = props["questions"]?.jsonArray ?: return null
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
                    val todosArray = props["todos"]?.jsonArray
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
                    stopReason = props["stopReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
                )

                // V2 Session created
                "session.next.created" -> SseEvent.SessionCreated(sessionId = sessionId)

                // Session-level events (V1 bus) — data is in props (extracted from properties wrapper)
                "session.idle" -> SseEvent.SessionIdle(sessionId = sessionId)
                "session.error" -> {
                    // session.error sends a structured error object: { name: "...", data: { message: "..." } }
                    val errorObj = props["error"]?.jsonObject
                    val dataObj = errorObj?.get("data")?.jsonObject
                    val errorMessage = dataObj?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: errorObj?.get("name")?.jsonPrimitive?.contentOrNull?.replace("Error", " error")
                    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull
                    SseEvent.SessionError(sessionId = sessionId, errorMessage = errorMessage, messageId = messageId)
                }
                "session.created" -> SseEvent.SessionCreated(sessionId = sessionId)
                "session.deleted" -> SseEvent.SessionDeleted(sessionId = sessionId)
                "session.updated" -> SseEvent.Ignored(sessionId, "session.updated", "intentional no-op — handled via REST")
                "session.compacted" -> SseEvent.SessionCompacted(sessionId = sessionId)
                "message.removed" -> {
                    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull
                    SseEvent.MessageRemoved(sessionId = sessionId, messageId = messageId)
                }

                // V2 new part type events
                "session.next.patch" -> {
                    val hash = props["hash"]?.jsonPrimitive?.contentOrNull ?: return null
                    val files = props["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    SseEvent.Patch(sessionId = sessionId, hash = hash, files = files)
                }
                "session.next.agent" -> {
                    val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.Agent(sessionId = sessionId, agentName = name)
                }
                "session.next.retry" -> {
                    val attempt = props["attempt"]?.jsonPrimitive?.intOrNull ?: 1
                    val maxAttempts = props["maxAttempts"]?.jsonPrimitive?.intOrNull ?: 3
                    val error = props["error"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Retry(sessionId = sessionId, attempt = attempt, maxAttempts = maxAttempts, error = error)
                }
                "session.next.compaction" -> {
                    val summary = props["summary"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Compaction(sessionId = sessionId, summary = summary)
                }
                "session.next.subtask" -> {
                    val prompt = props["prompt"]?.jsonPrimitive?.contentOrNull
                    val description = props["description"]?.jsonPrimitive?.contentOrNull
                    val agent = props["agent"]?.jsonPrimitive?.contentOrNull
                    val model = props["model"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Subtask(sessionId = sessionId, prompt = prompt, description = description, agent = agent, model = model)
                }

                // Legacy events
                "text_chunk" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                    if (text != null) SseEvent.TextChunk(sessionId = sessionId, text = text) else null
                }
                "tool_use" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull
                    val toolName = props["toolName"]?.jsonPrimitive?.contentOrNull
                    if (toolCallId != null && toolName != null) {
                        SseEvent.ToolUse(
                            sessionId = sessionId,
                            toolCallId = toolCallId,
                            toolName = toolName,
                            title = props["title"]?.jsonPrimitive?.contentOrNull,
                            input = props["input"]?.jsonObject
                        )
                    } else null
                }
                "tool_result" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull
                    if (toolCallId != null) {
                        SseEvent.ToolResult(
                            sessionId = sessionId,
                            toolCallId = toolCallId,
                            isError = props["isError"]?.jsonPrimitive?.let {
                                if (it.isString) it.content.lowercase() == "true" else it.toString().lowercase() == "true"
                            } ?: false,
                            content = (props["content"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { (it as? JsonObject) }
                        )
                    } else null
                }
                "plan" -> SseEvent.Plan(
                    sessionId = sessionId,
                    entries = (props["entries"] as? kotlinx.serialization.json.JsonArray)
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
                    stopReason = props["stopReason"]?.jsonPrimitive?.content ?: "end_turn"
                )
                "permission" -> SseEvent.Permission(
                    sessionId = sessionId,
                    permissionId = props["permissionId"]?.jsonPrimitive?.content ?: "",
                    toolCallId = props["toolCallId"]?.jsonPrimitive?.content ?: "",
                    action = props["action"]?.jsonPrimitive?.content ?: "",
                    description = props["description"]?.jsonPrimitive?.content,
                    patterns = props["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                )
                "permission.replied" -> {
                    val sid = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId
                    SseEvent.PermissionReplied(
                        sessionId = sid,
                        permissionId = props["requestID"]?.jsonPrimitive?.contentOrNull
                            ?: props["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        reply = props["reply"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                "error" -> SseEvent.Error(
                    sessionId = sessionId,
                    message = props["message"]?.jsonPrimitive?.content ?: "Unknown error",
                    code = props["code"]?.jsonPrimitive?.intOrNull
                )
                "session_created" -> SseEvent.SessionCreated(sessionId = sessionId)
                "message_complete" -> SseEvent.MessageComplete(
                    sessionId = sessionId,
                    messageId = props["messageId"]?.jsonPrimitive?.content ?: ""
                )
                else -> {
                    logger.debug { "Unknown SSE event type: $type" }
                    SseEvent.Ignored(sessionId, type, "unknown type")
                }
            }
        }
    }
}
