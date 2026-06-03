package com.opencode.acp.adapter

import com.opencode.acp.PlanEntry
import com.opencode.acp.SseEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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

/**
 * HTTP client for the OpenCode REST API using Ktor.
 *
 * Provides methods to interact with all OpenCode session, message,
 * agent, command, and SSE event endpoints.
 *
 * @param baseUrl the base URL of the OpenCode server (e.g. "http://localhost:3000")
 * @param httpClient the Ktor HttpClient instance (expected to be CIO-based)
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
        val response: String,
        val remember: Boolean? = null
    )

    @Serializable
    private data class ForkSessionRequest(val messageId: String? = null)

    @Serializable
    private data class RevertMessageRequest(
        val messageId: String,
        val partId: String? = null
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
        return json.decodeFromString<T>(body)
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
     * Requests summarization of a session.
     * POST /session/{id}/summarize
     */
    suspend fun summarizeSession(sessionId: String): Boolean =
        postSuccess("/session/$sessionId/summarize")

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
        val response = httpClient.post("$baseUrl/session/$sessionId/message") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "POST /session/$sessionId/message returned ${response.status}: ${body.take(500)}" }
            error("POST /session/$sessionId/message failed with ${response.status}: ${body.take(200)}")
        }
        val responseBody: SendMessageResponse = json.decodeFromString(body)
        return responseBody.info?.id
            ?: error("No message ID in response")
    }

    /**
     * Lists messages for a session, optionally limited.
     * GET /session/{id}/message?limit=N
     */
    suspend fun listMessages(
        sessionId: String,
        limit: Int? = null
    ): List<OpenCodeMessage> {
        val response = httpClient.get("$baseUrl/session/$sessionId/message") {
            applyAuth()
            limit?.let { parameter("limit", it) }
        }
        return json.decodeFromString(response.bodyAsText())
    }

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
     * POST /session/{id}/permissions/{permId}
     */
    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean? = null
    ) {
        try {
            httpClient.post("$baseUrl/session/$sessionId/permissions/$permissionId") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PermissionRequest(response = response, remember = remember)))
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to respond to permission $permissionId" }
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
                        val eventType = event.event ?: jsonObj["type"]?.jsonPrimitive?.contentOrNull ?: return@collect
                        val props = jsonObj["properties"]?.jsonObject ?: jsonObj

                        // Extract sessionId from various possible locations
                        val sessionId = when {
                            props["sessionID"] != null -> props["sessionID"]?.jsonPrimitive?.contentOrNull
                            props["sessionId"] != null -> props["sessionId"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        } ?: return@collect

                        val parsed = parseSseEvent(eventType, props, sessionId)
                        if (parsed != null) {
                            send(parsed)
                        }
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

    /**
     * Parses SSE event data into our internal [SseEvent] hierarchy.
     * Handles both old format (flat JSON) and new format (properties wrapper).
     */
    private fun parseSseEvent(eventType: String, props: JsonObject, sessionId: String): SseEvent? {
        return try {
            when (eventType) {
                "message.part.delta" -> {
                    val field = props["field"]?.jsonPrimitive?.contentOrNull
                    val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                    when (field) {
                        "text" -> SseEvent.TextChunk(sessionId = sessionId, text = delta)
                        "thinking", "reasoning" -> SseEvent.ThinkingChunk(sessionId = sessionId, text = delta)
                        else -> null
                    }
                }

                "message.part.updated" -> {
                    val part = props["part"]?.jsonObject
                    if (part != null) {
                        val partType = part["type"]?.jsonPrimitive?.contentOrNull
                        when (partType) {
                            "text" -> {
                                val text = part["text"]?.jsonPrimitive?.contentOrNull
                                if (text != null) {
                                    SseEvent.TextChunk(sessionId = sessionId, text = text)
                                } else null
                            }
                            "reasoning", "thinking" -> {
                                val text = part["text"]?.jsonPrimitive?.contentOrNull
                                if (text != null) {
                                    SseEvent.ThinkingChunk(sessionId = sessionId, text = text)
                                } else null
                            }
                            "tool_use" -> {
                                val toolCallId = part["id"]?.jsonPrimitive?.contentOrNull ?: return null
                                val toolName = part["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                                SseEvent.ToolUse(
                                    sessionId = sessionId,
                                    toolCallId = toolCallId,
                                    toolName = toolName,
                                    title = part["name"]?.jsonPrimitive?.contentOrNull,
                                    input = part["input"]?.jsonObject
                                )
                            }
                            "tool_result" -> {
                                val toolCallId = part["id"]?.jsonPrimitive?.contentOrNull ?: return null
                                SseEvent.ToolResult(
                                    sessionId = sessionId,
                                    toolCallId = toolCallId,
                                    isError = part["isError"]?.jsonPrimitive?.contentOrNull == "true"
                                )
                            }
                            else -> null
                        }
                    } else null
                }

                "message.updated" -> {
                    val info = props["info"]?.jsonObject
                    if (info != null) {
                        val finish = info["finish"]?.jsonPrimitive?.contentOrNull
                        if (finish == "stop" || finish == "end") {
                            SseEvent.Stop(sessionId = sessionId, stopReason = finish)
                        } else if (finish != null) {
                            SseEvent.Stop(sessionId = sessionId, stopReason = finish)
                        } else null
                    } else null
                }

                "session.created" -> {
                    SseEvent.SessionCreated(sessionId = sessionId)
                }

                "stop" -> {
                    val stopReason = props["stopReason"]?.jsonPrimitive?.contentOrNull ?: "stop"
                    SseEvent.Stop(sessionId = sessionId, stopReason = stopReason)
                }

                "text_chunk" -> {
                    val text = props["text"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.TextChunk(sessionId = sessionId, text = text)
                }

                "tool_use" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val toolName = props["toolName"]?.jsonPrimitive?.contentOrNull ?: return null
                    val title = props["title"]?.jsonPrimitive?.contentOrNull
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        title = title,
                        input = props["input"]?.jsonObject
                    )
                }

                "tool_result" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val isError = props["isError"]?.jsonPrimitive?.let {
                        if (it.isString) it.content.lowercase() == "true" else it.toString().lowercase() == "true"
                    } ?: false
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        isError = isError
                    )
                }

                "permission" -> {
                    val toolCallId = props["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val action = props["action"]?.jsonPrimitive?.contentOrNull ?: return null
                    val description = props["description"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Permission(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        action = action,
                        description = description
                    )
                }

                "error" -> {
                    val message = props["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    SseEvent.Error(sessionId = sessionId, message = message)
                }

                else -> null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing SSE event: $eventType" }
            null
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
    val limit: ModelLimit? = null
)

@Serializable
data class ModelLimit(
    val context: Int = 0,
    val output: Int = 0
)
