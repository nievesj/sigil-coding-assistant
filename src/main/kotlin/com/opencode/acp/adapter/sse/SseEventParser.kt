package com.opencode.acp.adapter.sse

import com.opencode.acp.PlanEntry
import com.opencode.acp.SseEvent
import com.opencode.acp.SseTodoItem
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses SSE event JSON into [SseEvent] instances.
 *
 * Extracted from the 691-line `OpenCodeClient.parseSseEvent()` method (TDD Phase 1).
 * Handles both V1 BusEvents and V2 SyncEvents. The V1/V2 format detection
 * (properties vs data wrapper, version suffix stripping) is done by the CALLER
 * before calling [parse] — this method receives already-normalized `eventType`
 * and `props`.
 *
 * Per-subscription state: [reasoningPartIds] tracks which part IDs are reasoning
 * parts, to disambiguate V1 `message.part.delta` events with `field: "text"`.
 * The parser should be instantiated per SSE subscription, not as a singleton.
 *
 * Returns [SseEvent.Ignored] for intentionally-ignored events (diagnostic) and
 * for unknown/unparseable events (NOT null — null would silently drop events).
 *
 * See TDD §4.2.2 — SseEventParser.
 */
class SseEventParser(
    private val logger: KLogger = KotlinLogging.logger {},
    // Per-subscription state — NOT a singleton
    private val reasoningPartIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
) {
    /**
     * Parse an SSE event into an [SseEvent].
     *
     * @param eventType The normalized event type (V2 version suffix already stripped)
     * @param props The event properties (extracted from V1 "properties" or V2 "data" wrapper)
     * @param sessionId The session ID
     * @return Parsed SseEvent (never null — unknown/unparseable events return [SseEvent.Ignored])
     */
    fun parse(eventType: String, props: JsonObject, sessionId: String): SseEvent {
        return try {
            logger.debug { "Parsing SSE event: type=$eventType, sessionId=$sessionId" }
            when (eventType) {
                // V2 Text events
                "session.next.text.started" -> {
                    // Text generation started - no action needed yet
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.text.delta" -> {
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing delta")
                    SseEvent.TextChunk(sessionId = sessionId, text = delta)
                }

                "session.next.text.ended" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing text")
                    SseEvent.TextReplace(sessionId = sessionId, text = text)
                }

                // V2 Reasoning events
                "session.next.reasoning.started" -> {
                    // Reasoning started - no action needed yet
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.reasoning.delta" -> {
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing delta")
                    SseEvent.ThinkingChunk(sessionId = sessionId, text = delta)
                }

                "session.next.reasoning.ended" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing text")
                    SseEvent.ThinkingReplace(sessionId = sessionId, text = text)
                }

                // V2 Tool events
                "session.next.tool.input.started" -> {
                    // Log the input data for debugging
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                    val tool = props["tool"]?.jsonPrimitive?.contentOrNull
                    val input = props["input"]?.jsonObject
                    logger.info { "[ACP] V2 tool.input.started: callID=$callID, tool=$tool, hasInput=${input != null}, inputKeys=${input?.keys}" }
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.tool.called" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val tool = props["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = props["input"]?.jsonObject
                    val calledMetadata = props["provider"]?.jsonObject?.get("metadata")?.jsonObject
                    logger.info { "[ACP] V2 tool.called: callID=$callID, tool=$tool, hasInput=${input != null}, inputKeys=${input?.keys}" }
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = callID,
                        toolName = tool,
                        title = tool,
                        input = input,
                        metadata = calledMetadata
                    )
                }

                "session.next.tool.success" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val output = (props["output"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                    // Pass input so ToolResult handler can re-detect kind if initial ToolUse had generic name
                    val resultInput = props["input"]?.jsonObject
                    val successMetadata = props["provider"]?.jsonObject?.get("metadata")?.jsonObject
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = callID,
                        isError = false,
                        content = output,
                        input = resultInput,
                        metadata = successMetadata
                    )
                }
                "session.next.tool.failed" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val output = (props["output"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                    val resultInput = props["input"]?.jsonObject
                    val failedMetadata = props["provider"]?.jsonObject?.get("metadata")?.jsonObject
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = callID,
                        isError = true,
                        content = output,
                        input = resultInput,
                        metadata = failedMetadata
                    )
                }

                // V2 Step events (for status indication)
                "session.next.step.started" -> {
                    // Step started - could be used for status indication
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.step.ended" -> {
                    // Step ended - could be used for status indication
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.step.failed" -> {
                    // Step failed - could be used for error indication
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                // V2 Prompted event (user message from server)
                "session.next.prompted" -> {
                    val prompt = props["prompt"]?.jsonObject
                    if (prompt != null) {
                        val text = prompt["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val files = prompt["files"]?.jsonArray?.mapNotNull {
                            it.jsonPrimitive.contentOrNull
                        } ?: emptyList()
                        SseEvent.UserMessage(
                            sessionId = sessionId,
                            text = text,
                            files = files
                        )
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing prompt")
                }

                // V2 Permission event
                "session.next.permission" -> {
                    val permissionId = props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing id")
                    val permission = props["permission"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing permission")
                    val toolObj = props["tool"]?.jsonObject
                    val toolCallId = toolObj?.get("callID")?.jsonPrimitive?.contentOrNull ?: ""
                    val patterns = props["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    val description = props["description"]?.jsonPrimitive?.contentOrNull
                        ?: if (patterns.isNullOrEmpty()) permission else "$permission: ${patterns.joinToString(", ")}"
                    SseEvent.Permission(
                        sessionId = sessionId,
                        permissionId = permissionId,
                        toolCallId = toolCallId,
                        action = permission,
                        description = description,
                        patterns = patterns ?: emptyList()
                    )
                }

                "session.next.permission.replied" -> {
                    val sid = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId
                    val requestId = props["requestID"]?.jsonPrimitive?.contentOrNull
                        ?: props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing requestID")
                    val reply = props["reply"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing reply")
                    SseEvent.PermissionReplied(
                        sessionId = sid,
                        permissionId = requestId,
                        reply = reply
                    )
                }

                // V2 Question event
                "session.next.question" -> {
                    val requestId = props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing id")
                    val questionsArray = props["questions"]?.jsonArray
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing questions")
                    val questions = questionsArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val header = obj["header"]?.jsonPrimitive?.contentOrNull ?: ""
                        val multiple = obj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
                        val custom = obj["custom"]?.jsonPrimitive?.booleanOrNull ?: true
                        val optionsArray = obj["options"]?.jsonArray ?: emptyList()
                        val options = optionsArray.mapNotNull { optElement ->
                            val optObj = optElement.jsonObject
                            val label = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val desc = optObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                            com.opencode.acp.SseQuestionOption(label = label, description = desc)
                        }
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
                            val obj = element.jsonObject
                            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                            val priority = obj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium"
                            SseTodoItem(content = content, status = status, priority = priority)
                        }
                        SseEvent.TodoUpdated(sessionId = sessionId, todos = todos)
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing todos")
                }

                // V2 Stop event
                "session.next.stopped" -> {
                    val stopReason = props["stopReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
                    SseEvent.Stop(sessionId = sessionId, stopReason = stopReason)
                }

                // V2 Session created
                "session.next.created" -> {
                    SseEvent.SessionCreated(sessionId = sessionId)
                }

                // Legacy events (kept for backward compatibility)
                "message.part.delta" -> {
                    val field = props["field"]?.jsonPrimitive?.contentOrNull
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing delta")
                    val messageId = extractMessageId(props)
                    val partId = extractPartId(props)
                    when (field) {
                        "text" -> {
                            // Server sends field: "text" for BOTH text and reasoning deltas on V1 bus.
                            // Disambiguate by checking if the partID is in the reasoning set.
                            if (partId != null && partId in reasoningPartIds) {
                                SseEvent.ThinkingChunk(sessionId = sessionId, text = delta, messageId = messageId, partId = partId)
                            } else {
                                SseEvent.TextChunk(sessionId = sessionId, text = delta, messageId = messageId, partId = partId)
                            }
                        }
                        "thinking", "reasoning" -> SseEvent.ThinkingChunk(sessionId = sessionId, text = delta, messageId = messageId, partId = partId)
                        else -> SseEvent.Ignored(sessionId, eventType, "parse error: unknown field=$field")
                    }
                }

                "message.part.updated" -> {
                    val part = props["part"]?.jsonObject
                    if (part != null) {
                        val partType = part["type"]?.jsonPrimitive?.contentOrNull
                        val partId = extractPartIdFromPart(part)
                        val messageId = extractMessageId(props)
                        when (partType) {
                            "text" -> {
                                // A text part appearing means any prior reasoning part is done.
                                // Clear the reasoning tracking set to prevent stale entries leaking
                                // across turns within the same session.
                                reasoningPartIds.clear()
                                val text = part["text"]?.jsonPrimitive?.contentOrNull
                                if (text != null) {
                                    SseEvent.TextReplace(sessionId = sessionId, text = text, messageId = messageId, partId = partId)
                                } else SseEvent.Ignored(sessionId, eventType, "parse error: missing text in text part", messageId = messageId, partId = partId)
                            }
                            "reasoning", "thinking" -> {
                                // Track this partID so message.part.delta can disambiguate reasoning from text
                                if (partId != null) reasoningPartIds.add(partId)
                                val text = part["text"]?.jsonPrimitive?.contentOrNull
                                if (text != null) {
                                    SseEvent.ThinkingReplace(sessionId = sessionId, text = text, messageId = messageId, partId = partId)
                                } else SseEvent.Ignored(sessionId, eventType, "parse error: missing text in reasoning part", messageId = messageId, partId = partId)
                            }
                            "tool_use", "tool" -> {
                                // OpenCode server uses "tool" for V1, "tool_use" is the alternate name
                                // part.id is the part ID (prt_xxx), part.callID is the tool call ID
                                val toolCallId = part["callID"]?.jsonPrimitive?.contentOrNull
                                    ?: part["id"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID in tool part")
                                val toolName = part["tool"]?.jsonPrimitive?.contentOrNull
                                    ?: part["name"]?.jsonPrimitive?.contentOrNull
                                    ?: "tool"
                                // Always log task tool events for debugging
                                if (toolName == "task") {
                                    logger.info { "[ACP] SSE TASK TOOL: callID=$toolCallId, tool=$toolName, partKeys=${part.keys}, rawPart=${part}" }
                                }
                                // state is a nested object: { status: "running"|"completed"|"error"|..., input, output, ... }
                                val stateObj = part["state"]?.jsonObject
                                val status = stateObj?.get("status")?.jsonPrimitive?.contentOrNull
                                logger.debug { "[ACP-SSE] SSE TOOL: callID=$toolCallId, tool=$toolName, status=$status, stateKeys=${stateObj?.keys}, partKeys=${part.keys}" }
                                logger.debug { "[ACP-SSE] SSE TOOL PART JSON: $part" }
                                when (status) {
                                    "completed" -> {
                                        // Parse output — can be JsonArray (most tools), string (task tool), or JsonObject
                                        val rawOutput = stateObj.get("output") ?: part["output"]
                                        val output: List<JsonObject>? = when (rawOutput) {
                                            is kotlinx.serialization.json.JsonArray -> rawOutput.mapNotNull { (it as? JsonObject) }
                                            is JsonPrimitive -> rawOutput.contentOrNull?.let { listOf(JsonObject(mapOf("text" to JsonPrimitive(it)))) }
                                            is JsonObject -> listOf(rawOutput) // wrap single object as a content block
                                            null -> null
                                            else -> {
                                                logger.warn { "[ACP] Unexpected output type in tool completed: ${rawOutput::class}" }
                                                null
                                            }
                                        }
                                        // Also try to extract input from completed state — may update existing pill
                                        val completedInput = stateObj.get("input")?.jsonObject ?: part["input"]?.jsonObject
                                        val completedMetadata = stateObj.get("metadata")?.jsonObject
                                        if (completedInput != null) {
                                            logger.info { "[ACP] V1 tool completed with input: callID=$toolCallId, tool=$toolName, inputKeys=${completedInput.keys}" }
                                        }
                                        SseEvent.ToolResult(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            isError = false,
                                            content = output,
                                            input = completedInput,
                                            metadata = completedMetadata,
                                            messageId = messageId,
                                            partId = partId
                                        )
                                    }
                                    "error" -> {
                                        val rawOutput = stateObj.get("output") ?: part["output"]
                                        val output: List<JsonObject>? = when (rawOutput) {
                                            is kotlinx.serialization.json.JsonArray -> rawOutput.mapNotNull { (it as? JsonObject) }
                                            is JsonPrimitive -> rawOutput.contentOrNull?.let { listOf(JsonObject(mapOf("text" to JsonPrimitive(it)))) }
                                            is JsonObject -> listOf(rawOutput)
                                            null -> null
                                            else -> {
                                                logger.warn { "[ACP] Unexpected output type in tool error: ${rawOutput::class}" }
                                                null
                                            }
                                        }
                                        val errorInput = stateObj.get("input")?.jsonObject ?: part["input"]?.jsonObject
                                        val errorMetadata = stateObj.get("metadata")?.jsonObject
                                        SseEvent.ToolResult(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            isError = true,
                                            content = output,
                                            input = errorInput,
                                            metadata = errorMetadata,
                                            messageId = messageId,
                                            partId = partId
                                        )
                                    }
                                    else -> {
                                        // "running", "pending", or null — emit as ToolUse
                                        val input = stateObj?.get("input")?.jsonObject ?: part["input"]?.jsonObject
                                        val runningMetadata = stateObj?.get("metadata")?.jsonObject
                                        logger.info { "[ACP] V1 tool part: callID=$toolCallId, tool=$toolName, status=$status, hasState=${stateObj != null}, stateInput=${stateObj?.get("input") != null}, partInput=${part["input"] != null}, resolvedInput=${input != null}" }
                                        SseEvent.ToolUse(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            toolName = toolName,
                                            title = stateObj?.get("title")?.jsonPrimitive?.contentOrNull ?: toolName,
                                            input = input,
                                            metadata = runningMetadata,
                                            messageId = messageId,
                                            partId = partId
                                        )
                                    }
                                }
                            }
                            "tool_result" -> {
                                val toolCallId = part["id"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing id in tool_result part")
                                val output = (part["output"] as? kotlinx.serialization.json.JsonArray)
                                    ?.mapNotNull { (it as? JsonObject) }
                                SseEvent.ToolResult(
                                    sessionId = sessionId,
                                    toolCallId = toolCallId,
                                    isError = part["isError"]?.jsonPrimitive?.contentOrNull?.lowercase() == "true",
                                    content = output,
                                    messageId = messageId,
                                    partId = partId
                                )
                            }
                            "patch" -> {
                                val hash = part["hash"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing hash in patch part")
                                val files = part["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                                SseEvent.Patch(sessionId = sessionId, hash = hash, files = files, messageId = messageId, partId = partId)
                            }
                            "file" -> {
                                val mime = part["mime"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing mime in file part")
                                val url = part["url"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing url in file part")
                                val filename = part["filename"]?.jsonPrimitive?.contentOrNull
                                SseEvent.AssistantFile(sessionId = sessionId, mime = mime, url = url, filename = filename, messageId = messageId, partId = partId)
                            }
                            "image" -> {
                                val mime = part["mime"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing mime in image part")
                                val url = part["url"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing url in image part")
                                val filename = part["filename"]?.jsonPrimitive?.contentOrNull
                                SseEvent.AssistantImage(sessionId = sessionId, mime = mime, url = url, filename = filename, messageId = messageId, partId = partId)
                            }
                            "agent" -> {
                                val name = part["name"]?.jsonPrimitive?.contentOrNull
                                    ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing name in agent part")
                                SseEvent.Agent(sessionId = sessionId, agentName = name, messageId = messageId, partId = partId)
                            }
                            "retry" -> {
                                val attempt = part["attempt"]?.jsonPrimitive?.intOrNull ?: 1
                                val maxAttempts = part["maxAttempts"]?.jsonPrimitive?.intOrNull ?: 3
                                val error = part["error"]?.jsonPrimitive?.contentOrNull
                                SseEvent.Retry(sessionId = sessionId, attempt = attempt, maxAttempts = maxAttempts, error = error, messageId = messageId, partId = partId)
                            }
                            "compaction" -> {
                                val summary = part["summary"]?.jsonPrimitive?.contentOrNull
                                SseEvent.Compaction(sessionId = sessionId, summary = summary, messageId = messageId, partId = partId)
                            }
                            "snapshot" -> {
                                val id = part["id"]?.jsonPrimitive?.contentOrNull
                                SseEvent.Snapshot(sessionId = sessionId, id = id, messageId = messageId, partId = partId)
                            }
                            "step-start" -> SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op", messageId = messageId, partId = partId) // No action needed
                            "step-finish" -> {
                                // Log raw part keys on first encounter to verify field names against actual server payload.
                                // The field names below (inputTokens, outputTokens, etc.) are assumptions —
                                // server may use different names (e.g. snake_case). Check debug logs.
                                logger.debug { "[ACP] step-finish raw part keys: ${part.keys}" }
                                val snapshot = part["snapshot"]?.jsonPrimitive?.contentOrNull
                                val inputTokens = part["inputTokens"]?.jsonPrimitive?.longOrNull
                                    ?: part["input_tokens"]?.jsonPrimitive?.longOrNull
                                val outputTokens = part["outputTokens"]?.jsonPrimitive?.longOrNull
                                    ?: part["output_tokens"]?.jsonPrimitive?.longOrNull
                                val reasoningTokens = part["reasoningTokens"]?.jsonPrimitive?.longOrNull
                                    ?: part["reasoning_tokens"]?.jsonPrimitive?.longOrNull
                                val totalCost = part["totalCost"]?.jsonPrimitive?.doubleOrNull
                                    ?: part["total_cost"]?.jsonPrimitive?.doubleOrNull
                                SseEvent.StepFinish(sessionId = sessionId, snapshot = snapshot, inputTokens = inputTokens, outputTokens = outputTokens, reasoningTokens = reasoningTokens, totalCost = totalCost, messageId = messageId, partId = partId)
                            }
                            "subtask" -> {
                                val prompt = part["prompt"]?.jsonPrimitive?.contentOrNull
                                val description = part["description"]?.jsonPrimitive?.contentOrNull
                                val agent = part["agent"]?.jsonPrimitive?.contentOrNull
                                val model = part["model"]?.jsonPrimitive?.contentOrNull
                                logger.info { "[ACP] SSE SUBTASK part: agent=$agent, desc=$description, prompt=${prompt?.take(80)}" }
                                SseEvent.Subtask(sessionId = sessionId, prompt = prompt, description = description, agent = agent, model = model, messageId = messageId, partId = partId)
                            }
                            else -> {
                                logger.debug { "[ACP-SSE] SSE SKIP part.updated: partType=$partType, partKeys=${part.keys}" }
                                SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "unknown part type: $partType", messageId = messageId, partId = partId)
                            }
                        }
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing part in message.part.updated")
                }

                "message.updated" -> {
                    val info = props["info"]?.jsonObject
                    // message.updated carries the message ID inside info.id, NOT at the top level.
                    // extractMessageId(props) looks for props["messageID"] which doesn't exist here.
                    val messageId = info?.get("id")?.jsonPrimitive?.contentOrNull ?: extractMessageId(props)
                    if (info != null) {
                        val role = info["role"]?.jsonPrimitive?.contentOrNull
                        val finish = info["finish"]?.jsonPrimitive?.contentOrNull
                        val tokens = parseInfoTokens(info)
                        val cost = info["cost"]?.jsonPrimitive?.doubleOrNull
                        val modelID = info["modelID"]?.jsonPrimitive?.contentOrNull
                        val providerID = info["providerID"]?.jsonPrimitive?.contentOrNull

                        // Only process assistant messages — user messages lack tokens/cost/modelID
                        if (role != "assistant") {
                            SseEvent.Ignored(sessionId, eventType, "non-assistant message.updated (role=$role)", messageId = messageId)
                        }
                        // Emit MessageFinalized if we have any useful data (finish is optional)
                        else if (finish != null || tokens != null || cost != null || modelID != null) {
                            SseEvent.MessageFinalized(
                                sessionId = sessionId,
                                messageId = messageId,
                                inputTokens = tokens?.input,
                                outputTokens = tokens?.output,
                                reasoningTokens = tokens?.reasoning,
                                cacheReadTokens = tokens?.cacheRead,
                                cacheWriteTokens = tokens?.cacheWrite,
                                cost = cost,
                                modelID = modelID,
                                providerID = providerID,
                                stopReason = finish,
                            )
                        } else SseEvent.Ignored(sessionId, eventType, "no useful data in message.updated", messageId = messageId)
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing info in message.updated", messageId = messageId)
                }

                "session.created" -> {
                    SseEvent.SessionCreated(sessionId = sessionId)
                }

                "session.idle" -> {
                    SseEvent.SessionIdle(sessionId = sessionId)
                }

                "session.error" -> {
                    // session.error sends a structured error object: { name: "...", data: { message: "..." } }
                    // Error variants: ProviderAuthError, UnknownError, MessageOutputLengthError,
                    // MessageAbortedError, APIError (has data.message, data.statusCode, data.isRetryable)
                    val errorObj = props["error"]?.jsonObject
                    val dataObj = errorObj?.get("data")?.jsonObject
                    val errorMessage = dataObj?.get("message")?.jsonPrimitive?.contentOrNull
                        // Fallback: construct readable message from error name
                        ?: errorObj?.get("name")?.jsonPrimitive?.contentOrNull?.replace("Error", " error")
                    val messageId = extractMessageId(props)
                    SseEvent.SessionError(sessionId = sessionId, errorMessage = errorMessage, messageId = messageId)
                }

                "session.updated" -> {
                    // Full Session object in properties.info — currently informational only.
                    // Could be used for push-based context updates (tokens/cost) in the future.
                    SseEvent.Ignored(sessionId, eventType, "session.updated — informational")
                }

                "session.deleted" -> {
                    SseEvent.SessionDeleted(sessionId = sessionId)
                }

                "session.compacted" -> {
                    // Server performed auto-compaction — local message cache may be stale
                    SseEvent.SessionCompacted(sessionId = sessionId)
                }

                "message.removed" -> {
                    val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull
                    SseEvent.MessageRemoved(sessionId = sessionId, messageId = messageId)
                }

                "stop" -> {
                    val stopReason = props["stopReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
                    val messageId = extractMessageId(props)
                    SseEvent.Stop(sessionId = sessionId, stopReason = stopReason, messageId = messageId)
                }

                "text_chunk" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing text")
                    val messageId = extractMessageId(props)
                    val partId = extractPartId(props)
                    SseEvent.TextChunk(sessionId = sessionId, text = text, messageId = messageId, partId = partId)
                }

                "tool_use" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing toolCallId")
                    val toolName = props["toolName"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing toolName")
                    val title = props["title"]?.jsonPrimitive?.contentOrNull
                    val messageId = extractMessageId(props)
                    val partId = extractPartId(props)
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        title = title,
                        input = props["input"]?.jsonObject,
                        messageId = messageId,
                        partId = partId
                    )
                }

                "tool_result" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing toolCallId")
                    val isError = props["isError"]?.jsonPrimitive?.let {
                        if (it.isString) it.content.lowercase() == "true" else it.toString().lowercase() == "true"
                    } ?: false
                    val output = (props["output"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                    val input = props["input"]?.jsonObject
                    val messageId = extractMessageId(props)
                    val partId = extractPartId(props)
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        isError = isError,
                        content = output,
                        input = input,
                        messageId = messageId,
                        partId = partId
                    )
                }

                "permission.asked" -> {
                    val permissionId = props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing id")
                    val permission = props["permission"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing permission")
                    val toolObj = props["tool"]?.jsonObject
                    val toolCallId = toolObj?.get("callID")?.jsonPrimitive?.contentOrNull ?: ""
                    val patterns = props["patterns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    val description = if (patterns.isNullOrEmpty()) permission else "$permission: ${patterns.joinToString(", ")}"
                    val messageId = extractMessageId(props)
                    SseEvent.Permission(
                        sessionId = sessionId,
                        permissionId = permissionId,
                        toolCallId = toolCallId,
                        action = permission,
                        description = description,
                        messageId = messageId
                    )
                }

                "permission.replied" -> {
                    val sid = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId
                    val requestId = props["requestID"]?.jsonPrimitive?.contentOrNull
                        ?: props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing requestID")
                    val reply = props["reply"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing reply")
                    SseEvent.PermissionReplied(
                        sessionId = sid,
                        permissionId = requestId,
                        reply = reply
                    )
                }

                "question.asked" -> {
                    val requestId = props["id"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing id")
                    val questionsArray = props["questions"]?.jsonArray
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing questions")
                    val messageId = extractMessageId(props)
                    val questions = questionsArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val header = obj["header"]?.jsonPrimitive?.contentOrNull ?: ""
                        val multiple = obj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
                        val custom = obj["custom"]?.jsonPrimitive?.booleanOrNull ?: true
                        val optionsArray = obj["options"]?.jsonArray ?: emptyList()
                        val options = optionsArray.mapNotNull { optElement ->
                            val optObj = optElement.jsonObject
                            val label = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val desc = optObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                            com.opencode.acp.SseQuestionOption(label = label, description = desc)
                        }
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
                        questions = questions,
                        messageId = messageId
                    )
                }

                "error" -> {
                    val message = props["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    val messageId = extractMessageId(props)
                    SseEvent.Error(sessionId = sessionId, message = message, messageId = messageId)
                }

                "todo.updated" -> {
                    val todosArray = props["todos"]?.jsonArray
                    val messageId = extractMessageId(props)
                    if (todosArray != null) {
                        val todos = todosArray.mapNotNull { element ->
                            val obj = element.jsonObject
                            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                            val priority = obj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium"
                            SseTodoItem(content = content, status = status, priority = priority)
                        }
                        SseEvent.TodoUpdated(sessionId = sessionId, todos = todos, messageId = messageId)
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing todos", messageId = messageId)
                }

                "plan" -> {
                    val entriesArray = props["entries"]?.jsonArray
                    val entries = entriesArray?.mapNotNull { element ->
                        val obj = element.jsonObject
                        PlanEntry(
                            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                            priority = obj["priority"]?.jsonPrimitive?.contentOrNull ?: "medium",
                            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                        )
                    } ?: emptyList()
                    val messageId = extractMessageId(props)
                    SseEvent.Plan(sessionId = sessionId, entries = entries, messageId = messageId)
                }

                "message_complete" -> {
                    val messageId = extractMessageId(props) ?: ""
                    SseEvent.MessageComplete(sessionId = sessionId, messageId = messageId)
                }

                else -> {
                    logger.debug { "[ACP-SSE] SSE UNKNOWN: type=$eventType, pKeys=${props.keys}" }
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "unknown type")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[ACP] SSE parse FAILED: type=$eventType, sid=$sessionId" }
            SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "parse error: ${e.message}")
        }
    }

    /** Reset reasoning part tracking (called on session switch). */
    fun resetReasoningTracking() {
        reasoningPartIds.clear()
    }

    // -------------------------------------------------------------------------
    // Helpers (moved from OpenCodeClient)
    // -------------------------------------------------------------------------

    // V1 carries messageID at the top level of props
    private fun extractMessageId(props: JsonObject): String? =
        props["messageID"]?.jsonPrimitive?.contentOrNull

    // V1 carries partID at the top level of props (for message.part.delta)
    private fun extractPartId(props: JsonObject): String? =
        props["partID"]?.jsonPrimitive?.contentOrNull

    // V1 carries part.id inside the part object (for message.part.updated)
    private fun extractPartIdFromPart(part: JsonObject): String? =
        part["id"]?.jsonPrimitive?.contentOrNull

    /**
     * Parse info.tokens JSON into nullable per-field values.
     * Returns null if the "tokens" key is absent entirely.
     * Individual fields default to null (not 0L) so callers can distinguish
     * "server sent 0" from "server omitted the field" — the `?:` fallback
     * in SessionState preserves existing ChatMessage values for omitted fields.
     */
    private fun parseInfoTokens(info: JsonObject): ParsedTokens? {
        val tokensObj = info["tokens"]?.jsonObject ?: return null
        return ParsedTokens(
            input = tokensObj["input"]?.jsonPrimitive?.longOrNull,
            output = tokensObj["output"]?.jsonPrimitive?.longOrNull,
            reasoning = tokensObj["reasoning"]?.jsonPrimitive?.longOrNull,
            cacheRead = tokensObj["cache"]?.jsonObject?.get("read")?.jsonPrimitive?.longOrNull,
            cacheWrite = tokensObj["cache"]?.jsonObject?.get("write")?.jsonPrimitive?.longOrNull,
        )
    }

    /** Intermediate token parse result — nullable fields preserve "absent vs zero" semantics. */
    private data class ParsedTokens(
        val input: Long?,
        val output: Long?,
        val reasoning: Long?,
        val cacheRead: Long?,
        val cacheWrite: Long?,
    )
}