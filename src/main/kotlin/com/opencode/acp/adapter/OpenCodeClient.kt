package com.opencode.acp.adapter

import com.opencode.acp.PlanEntry
import com.opencode.acp.SseEvent
import com.opencode.acp.SseTodoItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader

private val logger = KotlinLogging.logger {}

/** Debug log helper — uses logger so output goes to idea.log, not a temp file. */
private fun debugLog(msg: String) {
    logger.info { "[ACP-SSE] $msg" }
}

/**
 * HTTP client for the OpenCode REST API using Ktor.
 *
 * Provides methods to interact with all OpenCode session, message,
 * agent, command, and SSE event endpoints.
 *
 * @param baseUrl the base URL of the OpenCode server (e.g. "http://localhost:3000")
 * @param httpClient the Ktor HttpClient instance
 * @param authToken optional bearer token for authenticated requests
 */
class OpenCodeClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val authToken: String? = null
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    /** Set of part IDs that belong to reasoning/thinking content on the V1 bus.
     *  Populated when message.part.updated arrives with part.type == "reasoning"/"thinking".
     *  Used in message.part.delta to disambiguate reasoning from text (both use field: "text"). */
    private val reasoningPartIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // -------------------------------------------------------------------------
    // Request / Response DTOs (private)
    // -------------------------------------------------------------------------

    @Serializable
    private data class CreateSessionRequest(
        val parentID: String? = null,
        val title: String? = null,
        val agent: String? = null
    )

    @Serializable
    private data class SendMessageRequest(
        val parts: List<OpenCodePart>,
        val variant: String? = null,
        val agent: String? = null,
        val model: MessageModel? = null
    )

    @Serializable
    data class MessageModel(
        val providerID: String,
        val modelID: String
    )

    @Serializable
    private data class SendMessageResponse(
        val info: MessageInfo? = null
    )

    @Serializable
    private data class ExecuteCommandRequest(
        val command: String,
        val args: String
    )

    @Serializable
    private data class PermissionRequest(
        val response: String
    )

    @Serializable
    private data class QuestionReplyRequest(
        val answers: List<List<String>>
    )

    @Serializable
    private data class ForkSessionRequest(val messageId: String? = null)

    @Serializable
    private data class RevertMessageRequest(
        val messageId: String,
        val partId: String? = null
    )

    @Serializable
    private data class CompactSessionRequest(
        val providerID: String,
        val modelID: String,
        val auto: Boolean = false
    )

    // -------------------------------------------------------------------------
    // Auth helper
    // -------------------------------------------------------------------------

    private fun HttpRequestBuilder.applyAuth() {
        authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    // -------------------------------------------------------------------------
    // Generic HTTP helpers
    // -------------------------------------------------------------------------

    /**
     * Performs an HTTP GET and deserializes the response body as [T].
     */
    private suspend inline fun <reified T> getJson(path: String): T {
        val response = httpClient.get("$baseUrl$path") {
            applyAuth()
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "GET $path returned ${response.status}: ${body.take(500)}" }
            error("GET $path failed with ${response.status}: ${body.take(200)}")
        }
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            logger.error(e) { "GET $path deserialization failed. Body preview: ${body.take(1000)}" }
            throw e
        }
    }

    /**
     * Performs an HTTP POST with a JSON body and deserializes the response as [T].
     */
    private suspend inline fun <reified T> postJson(path: String, request: Any): T {
        val response = httpClient.post("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(kotlinx.serialization.serializer(request::class.java), request))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "POST $path returned ${response.status}: ${body.take(500)}" }
            error("POST $path failed with ${response.status}: ${body.take(200)}")
        }
        return json.decodeFromString<T>(body)
    }

    /**
     * Performs an HTTP POST and returns true if the status is successful.
     */
    private suspend fun postSuccess(path: String, request: Any? = null): Boolean = try {
        val response = httpClient.post("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            if (request != null) {
                setBody(json.encodeToString(kotlinx.serialization.serializer(request::class.java), request))
            }
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        logger.warn(e) { "POST $path failed" }
        false
    }

    /**
     * Performs an HTTP DELETE and returns true if the status is successful.
     */
    private suspend fun deleteSuccess(path: String): Boolean = try {
        val response = httpClient.delete("$baseUrl$path") {
            applyAuth()
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        logger.warn(e) { "DELETE $path failed" }
        false
    }

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    /**
     * Checks whether the OpenCode server is reachable and healthy.
     * GET /global/health
     */
    suspend fun healthCheck(): Boolean = try {
        val response = httpClient.get("$baseUrl/global/health") {
            applyAuth()
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        logger.warn(e) { "Health check failed" }
        false
    }

    // -------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------

    /**
     * Creates a new OpenCode session.
     * POST /session
     */
    suspend fun createSession(
        title: String? = null
    ): OpenCodeSession {
        val requestBody = buildJsonObject {
            if (title != null) put("title", title)
        }.toString()
        val response = httpClient.post("$baseUrl/session") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "POST /session returned ${response.status}: ${body.take(500)}" }
            error("POST /session failed with ${response.status}: ${body.take(200)}")
        }
        return json.decodeFromString<OpenCodeSession>(body)
    }

    /**
     * Lists all active sessions.
     * GET /session
     */
    suspend fun listSessions(): List<OpenCodeSession> =
        getJson("/session")

    /**
     * Deletes a session.
     * DELETE /session/{id}
     */
    suspend fun deleteSession(sessionId: String): Boolean =
        deleteSuccess("/session/$sessionId")

    /**
     * Gets a single session by ID, including full token counts, cost, and model info.
     * GET /session/{id}
     */
    suspend fun getSession(sessionId: String): OpenCodeSession =
        getJson("/session/$sessionId")

    /**
     * Aborts a running session.
     * POST /session/{id}/abort
     */
    suspend fun abortSession(sessionId: String): Boolean =
        postSuccess("/session/$sessionId/abort")

    /**
     * Forks (clones) a session up to (optionally) a specific message.
     * POST /session/{id}/fork
     */
    suspend fun forkSession(
        sessionId: String,
        messageId: String? = null
    ): OpenCodeSession =
        postJson("/session/$sessionId/fork", ForkSessionRequest(messageId = messageId))

    /**
     * Creates a share link for a session (read-only view).
     * GET /session/{id}/share
     */
    suspend fun shareSession(sessionId: String): OpenCodeSession =
        getJson("/session/$sessionId/share")

    /**
     * Removes a share link from a session.
     * DELETE /session/{id}/share
     */
    suspend fun unshareSession(sessionId: String): OpenCodeSession = try {
        val response = httpClient.delete("$baseUrl/session/$sessionId/share") {
            applyAuth()
        }
        json.decodeFromString(response.bodyAsText())
    } catch (e: Exception) {
        logger.warn(e) { "Failed to unshare session $sessionId" }
        throw e
    }

    /**
     * Requests summarization (compaction) of a session.
     * POST /session/{id}/summarize
     *
     * The server will create a compaction user message, then run the compaction
     * agent to summarize the conversation history and free context space.
     *
     * @param providerID The provider ID for the model to use for compaction.
     * @param modelID The model ID to use for compaction.
     * @param auto Whether this is an automatic (vs user-initiated) compaction.
     */
    suspend fun compactSession(
        sessionId: String,
        providerID: String,
        modelID: String,
        auto: Boolean = false
    ): Boolean =
        postSuccess("/session/$sessionId/summarize", CompactSessionRequest(
            providerID = providerID,
            modelID = modelID,
            auto = auto
        ))

    /**
     * Reverts a specific message (and optionally a part within it) from the session.
     * POST /session/{id}/revert
     */
    suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String? = null
    ): Boolean =
        postSuccess("/session/$sessionId/revert", RevertMessageRequest(messageId = messageId, partId = partId))

    /**
     * Un-reverts (restores) all previously reverted messages in the session.
     * POST /session/{id}/unrevert
     */
    suspend fun unrevertMessages(sessionId: String): Boolean =
        postSuccess("/session/$sessionId/unrevert")

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    /**
     * Sends a message (list of parts) to a session and returns a correlation ID.
     * POST /session/{id}/message
     *
     * @param variant Model variant hint (e.g. "low", "medium", "high" for thinking effort)
     * @param agent Agent name override (e.g. "coder", "task")
     * @param model Model override with providerID and modelID
     */
    suspend fun sendMessageAsync(
        sessionId: String,
        parts: List<OpenCodePart>,
        variant: String? = null,
        agent: String? = null,
        model: MessageModel? = null
    ): String {
        val requestBody = json.encodeToString(
            SendMessageRequest(parts = parts, variant = variant, agent = agent, model = model)
        )
        logger.info { "Sending message: ${requestBody.take(200)}" }
        val startTime = System.currentTimeMillis()
        try {
            val response = httpClient.post("$baseUrl/session/$sessionId/message") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val elapsed = System.currentTimeMillis() - startTime
            logger.info { "POST /session/$sessionId/message got response: ${response.status} in ${elapsed}ms" }
            val body = response.bodyAsText()
            logger.debug { "POST /session/$sessionId/message body: ${body.take(500)}" }
            if (!response.status.isSuccess()) {
                logger.error { "POST /session/$sessionId/message returned ${response.status}: ${body.take(500)}" }
                error("POST /session/$sessionId/message failed with ${response.status}: ${body.take(200)}")
            }
            val responseBody: SendMessageResponse = json.decodeFromString(body)
            logger.info { "POST /session/$sessionId/message OK — msgId=${responseBody.info?.id}" }
            return responseBody.info?.id
                ?: error("No message ID in response")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            logger.error(e) { "[ACP] sendMessageAsync FAILED after ${elapsed}ms: ${e::class.simpleName}: ${e.message}" }
            throw e
        }
    }

    /**
     * Lists messages for a session, optionally limited.
     * GET /session/{id}/message?limit=N
     */
    suspend fun listMessages(
        sessionId: String,
        limit: Int? = null
    ): List<OpenCodeMessage> {
        val path = buildString {
            append("/session/$sessionId/message")
            if (limit != null) append("?limit=$limit")
        }
        return getJson(path)
    }

    /**
     * Gets the todo list for a session.
     * GET /session/{id}/todo
     */
    suspend fun getTodos(sessionId: String): List<TodoItem> =
        getJson("/session/$sessionId/todo")

    // -------------------------------------------------------------------------
    // Agents
    // -------------------------------------------------------------------------

    /**
     * Lists available agents.
     * GET /agent
     */
    suspend fun listAgents(): List<AgentInfo> =
        getJson("/agent")

    // -------------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------------

    /**
     * Lists all providers and their models.
     * GET /provider
     */
    suspend fun listProviders(): ProviderResponse =
        getJson("/provider")

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    /**
     * Lists available commands.
     * GET /command
     */
    suspend fun listCommands(): List<CommandInfo> =
        getJson("/command")

    /**
     * Executes a command in the context of a session.
     * POST /session/{id}/command
     */
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        args: String
    ): OpenCodeMessage =
        postJson("/session/$sessionId/command", ExecuteCommandRequest(command = command, args = args))

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Responds to a permission request.
     * POST /permission/{requestID}/reply
     */
    suspend fun respondPermission(
        permissionId: String,
        response: String
    ) {
        httpClient.post("$baseUrl/permission/$permissionId/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PermissionRequest(response = response)))
        }
    }

    /**
     * Responds to a question from the agent.
     * POST /question/{requestID}/reply
     * Body: { "answers": [["selectedLabel1"], ["labelA", "labelB"]] }
     */
    suspend fun respondQuestion(
        requestId: String,
        answers: List<List<String>>
    ) {
        httpClient.post("$baseUrl/question/$requestId/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(QuestionReplyRequest(answers = answers)))
        }
    }

    /**
     * Rejects / dismisses a question from the agent.
     * POST /question/{requestID}/reject
     */
    suspend fun rejectQuestion(requestId: String) {
        httpClient.post("$baseUrl/question/$requestId/reject") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
    }

    // -------------------------------------------------------------------------
    // SSE (Server-Sent Events)
    // -------------------------------------------------------------------------

    /**
     * Subscribes to the global SSE event stream and returns a [Flow] of
     * [com.opencode.acp.SseEvent] values.
     *
     * GET /global/event
     *
     * The returned flow is cold — it connects when collected and disconnects
     * when the collection is cancelled.
     */
    fun subscribeGlobalEvents(): Flow<SseEvent> = callbackFlow {
        val job = launch {
            try {
                httpClient.sse("$baseUrl/event") {
                    logger.info { "SSE connected to $baseUrl/event" }
                    incoming.collect { event ->
                        val data = event.data ?: return@collect
                        val jsonObj: JsonObject = try {
                            json.parseToJsonElement(data).jsonObject
                        } catch (_: Exception) {
                            return@collect
                        }
                        // The SSE event type is always "message" from the server.
                        // The actual event type is in jsonObj["type"].
                        val rawEventType = jsonObj["type"]?.jsonPrimitive?.contentOrNull
                            ?: event.event
                            ?: return@collect

                        // V2 SyncEvents use {id, seq, type: "session.next.text.delta.1", data: {sessionID, delta, ...}}
                        // V1 BusEvents use {type: "session.next.text.delta", properties: {sessionID, delta, ...}}
                        // Detect V2 by presence of "data" object (V2) vs "properties" object (V1)
                        val v2Data = jsonObj["data"]?.jsonObject
                        val props: JsonObject
                        val eventType: String

                        if (v2Data != null) {
                            // V2 SyncEvent: payload is in "data", type has version suffix (e.g. ".1")
                            props = v2Data
                            // Strip version suffix: "session.next.text.delta.1" → "session.next.text.delta"
                            eventType = rawEventType.replace(Regex("\\.\\d+$"), "")
                        } else {
                            // V1 BusEvent: payload is in "properties" (or flat at root)
                            props = jsonObj["properties"]?.jsonObject ?: jsonObj
                            eventType = rawEventType
                        }

                        // Extract sessionId from various possible locations
                        val sessionId = when {
                            props["sessionID"] != null -> props["sessionID"]?.jsonPrimitive?.contentOrNull
                            props["sessionId"] != null -> props["sessionId"]?.jsonPrimitive?.contentOrNull
                            jsonObj["sessionID"] != null -> jsonObj["sessionID"]?.jsonPrimitive?.contentOrNull
                            jsonObj["sessionId"] != null -> jsonObj["sessionId"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        }
                        
                        if (sessionId == null) {
                            debugLog("SSE MISS: rawType=$rawEventType, type=$eventType, hasData=${v2Data != null}, hasProps=${jsonObj["properties"] != null}, keys=${jsonObj.keys}")
                            return@collect
                        }

                        debugLog("SSE IN: rawType=$rawEventType → eventType=$eventType, v2=${v2Data != null}, sid=$sessionId, pKeys=${props.keys}")

                        val parsed = parseSseEvent(eventType, props, sessionId)
                    if (parsed is SseEvent.Ignored) {
                        logger.debug { "[ACP-SSE] IGNORED: type=$eventType, reason=${parsed.reason}" }
                    } else {
                            debugLog("SSE OK: ${parsed::class.simpleName}")
                        }
                        send(parsed)
                    }
                    logger.info { "SSE stream ended" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "SSE connection closed with error" }
            }
        }
        awaitClose {
            job.cancel()
            logger.info { "SSE subscription cancelled" }
        }
    }

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
     * Parses SSE event data into our internal [SseEvent] hierarchy.
     * Handles both old format (flat JSON) and new format (properties wrapper).
     * Now handles v2 events from OpenCode server.
     */
    private fun parseSseEvent(eventType: String, props: JsonObject, sessionId: String): SseEvent {
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
                    // Skip: pill is created from "called" event which has full data (tool name + input).
                    // Creating from "input.started" would produce incomplete pills without input data.
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "intentional no-op")
                }

                "session.next.tool.called" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val tool = props["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = props["input"]?.jsonObject
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = callID,
                        toolName = tool,
                        title = tool,
                        input = input
                    )
                }

                "session.next.tool.success" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val output = (props["output"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = callID,
                        isError = false,
                        content = output
                    )
                }

                "session.next.tool.failed" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull
                        ?: return SseEvent.Ignored(sessionId, eventType, "parse error: missing callID")
                    val output = (props["output"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonObject) }
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = callID,
                        isError = true,
                        content = output
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
                                // state is a nested object: { status: "running"|"completed"|"error"|..., input, output, ... }
                                val stateObj = part["state"]?.jsonObject
                                val status = stateObj?.get("status")?.jsonPrimitive?.contentOrNull
                                debugLog("SSE TOOL: callID=$toolCallId, tool=$toolName, status=$status, stateKeys=${stateObj?.keys}")
                                when (status) {
                                    "completed" -> {
                                        val output = (stateObj.get("output") as? kotlinx.serialization.json.JsonArray)
                                            ?.mapNotNull { (it as? JsonObject) }
                                            ?: (part["output"] as? kotlinx.serialization.json.JsonArray)
                                                ?.mapNotNull { (it as? JsonObject) }
                                        SseEvent.ToolResult(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            isError = false,
                                            content = output,
                                            messageId = messageId,
                                            partId = partId
                                        )
                                    }
                                    "error" -> {
                                        val output = (stateObj.get("output") as? kotlinx.serialization.json.JsonArray)
                                            ?.mapNotNull { (it as? JsonObject) }
                                            ?: (part["output"] as? kotlinx.serialization.json.JsonArray)
                                                ?.mapNotNull { (it as? JsonObject) }
                                        SseEvent.ToolResult(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            isError = true,
                                            content = output,
                                            messageId = messageId,
                                            partId = partId
                                        )
                                    }
                                    else -> {
                                        // "running", "pending", or null — emit as ToolUse
                                        val input = stateObj?.get("input")?.jsonObject ?: part["input"]?.jsonObject
                                        SseEvent.ToolUse(
                                            sessionId = sessionId,
                                            toolCallId = toolCallId,
                                            toolName = toolName,
                                            title = stateObj?.get("title")?.jsonPrimitive?.contentOrNull ?: toolName,
                                            input = input,
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
                                    isError = part["isError"]?.jsonPrimitive?.contentOrNull == "true",
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
                                SseEvent.Subtask(sessionId = sessionId, prompt = prompt, description = description, agent = agent, model = model, messageId = messageId, partId = partId)
                            }
                            else -> {
                                debugLog("SSE SKIP part.updated: partType=$partType, partKeys=${part.keys}")
                                SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "unknown part type: $partType", messageId = messageId, partId = partId)
                            }
                        }
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing part in message.part.updated")
                }

                "message.updated" -> {
                    val info = props["info"]?.jsonObject
                    val messageId = extractMessageId(props)
                    if (info != null) {
                        val finish = info["finish"]?.jsonPrimitive?.contentOrNull
                        if (finish == "stop" || finish == "end") {
                            SseEvent.Stop(sessionId = sessionId, stopReason = finish, messageId = messageId)
                        } else if (finish != null) {
                            SseEvent.Stop(sessionId = sessionId, stopReason = finish, messageId = messageId)
                        } else SseEvent.Ignored(sessionId, eventType, "parse error: missing finish in message.updated", messageId = messageId)
                    } else SseEvent.Ignored(sessionId, eventType, "parse error: missing info in message.updated", messageId = messageId)
                }

                "session.created" -> {
                    SseEvent.SessionCreated(sessionId = sessionId)
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
                    val messageId = extractMessageId(props)
                    val partId = extractPartId(props)
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        isError = isError,
                        content = output,
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
                    debugLog("SSE UNKNOWN: type=$eventType, pKeys=${props.keys}")
                    SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "unknown type")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] SSE parse FAILED: type=$eventType, sid=$sessionId" }
            SseEvent.Ignored(sessionId = sessionId, eventType = eventType, reason = "parse error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Closes the SSE-dedicated client.
     * The main httpClient lifecycle is managed by the owner (caller).
     */
    override fun close() {
        // No-op: SSE now uses the shared httpClient owned by the caller
    }

    /** Clear reasoning part ID tracking. Call on session switch. */
    fun resetReasoningTracking() {
        reasoningPartIds.clear()
    }
}

/** Provider info from GET /provider. */
@Serializable
data class ProviderResponse(
    val all: List<ProviderData>,
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

@Serializable
data class ProviderData(
    val id: String,
    val name: String,
    val env: List<String> = emptyList(),
    val models: Map<String, ModelData> = emptyMap()
)

@Serializable
data class ModelData(
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
    val tool_call: Boolean = false,
    val attachment: Boolean = false,
    val temperature: Boolean = false,
    val release_date: String = "",
    val limit: ModelLimit? = null,
    val variants: Map<String, Map<String, kotlinx.serialization.json.JsonElement>>? = null
)

@Serializable
data class ModelLimit(
    val context: Int = 0,
    val output: Int = 0
)
