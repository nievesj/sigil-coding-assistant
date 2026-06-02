package com.opencode.acp.adapter

import com.opencode.acp.PlanEntry
import com.opencode.acp.SseEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
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

    /** SSE-dedicated client for raw HTTP streaming. */
    private val sseClient: HttpClient by lazy {
        HttpClient(CIO) {
            // If an auth token is configured, apply it as a default header
            authToken?.let { token ->
                defaultRequest {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Request / Response DTOs (private)
    // -------------------------------------------------------------------------

    @Serializable
    private data class CreateSessionRequest(
        val cwd: String,
        val model: String? = null,
        val agent: String? = null
    )

    @Serializable
    private data class SendMessageRequest(val parts: List<OpenCodePart>)

    @Serializable
    private data class SendMessageResponse(
        val correlationId: String? = null,
        val id: String? = null
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
        return json.decodeFromString<T>(response.bodyAsText())
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
        return json.decodeFromString<T>(response.bodyAsText())
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
        cwd: String,
        model: String? = null,
        agent: String? = null
    ): OpenCodeSession =
        postJson("/session", CreateSessionRequest(cwd = cwd, model = model, agent = agent))

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
     */
    suspend fun sendMessageAsync(
        sessionId: String,
        parts: List<OpenCodePart>
    ): String {
        val response = httpClient.post("$baseUrl/session/$sessionId/message") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SendMessageRequest(parts = parts)))
        }
        val responseBody: SendMessageResponse = json.decodeFromString(response.bodyAsText())
        return responseBody.correlationId ?: responseBody.id
            ?: error("No correlation ID or message ID in response")
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
                sseClient.prepareGet("$baseUrl/global/event") {
                    authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }.execute { response ->
                    val inputStream = response.bodyAsChannel().toInputStream()
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val eventTypeBuffer = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val ln = line ?: break
                        if (ln.startsWith("event:")) {
                            eventTypeBuffer.clear()
                            eventTypeBuffer.append(ln.removePrefix("event:").trim())
                        } else if (ln.startsWith("data:")) {
                            val data = ln.removePrefix("data:").trim()
                            val eventType = eventTypeBuffer.toString()
                            if (eventType.isNotEmpty() && data.isNotEmpty()) {
                                val parsed = parseSseEventFromData(eventType, data)
                                if (parsed != null) {
                                    send(parsed)
                                }
                            }
                            eventTypeBuffer.clear()
                        }
                    }
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
     * Parses SSE event data string into our internal [SseEvent] hierarchy.
     */
    private fun parseSseEventFromData(eventType: String, data: String): SseEvent? {
        val obj: JsonObject = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            logger.warn { "Failed to parse SSE event data as JSON: $data" }
            return null
        }

        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null

        return try {
            when (eventType) {
                "text_chunk" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.TextChunk(sessionId = sessionId, text = text)
                }

                "tool_use" -> {
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull ?: return null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    val input = obj["input"]?.jsonObject
                    SseEvent.ToolUse(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        title = title,
                        input = input
                    )
                }

                "tool_result" -> {
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val isError = obj["isError"]?.jsonPrimitive?.let {
                        if (it.isString) it.content.lowercase() == "true" else it.toString().lowercase() == "true"
                    } ?: false
                    val content = obj["content"]?.jsonArray?.mapNotNull { element ->
                        element as? JsonObject
                    }
                    SseEvent.ToolResult(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        isError = isError,
                        content = content
                    )
                }

                "plan" -> {
                    val entriesArray = obj["entries"]?.jsonArray ?: return null
                    val entries = entriesArray.mapNotNull { entryElement ->
                        val entryObj = entryElement as? JsonObject ?: return@mapNotNull null
                        val description = entryObj["description"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val priority = entryObj["priority"]?.jsonPrimitive?.contentOrNull ?: "MEDIUM"
                        val status = entryObj["status"]?.jsonPrimitive?.contentOrNull ?: "PENDING"
                        PlanEntry(
                            description = description,
                            priority = priority,
                            status = status
                        )
                    }
                    SseEvent.Plan(sessionId = sessionId, entries = entries)
                }

                "stop" -> {
                    val stopReason = obj["stopReason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    SseEvent.Stop(sessionId = sessionId, stopReason = stopReason)
                }

                "permission" -> {
                    val toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: return null
                    val description = obj["description"]?.jsonPrimitive?.contentOrNull
                    SseEvent.Permission(
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        action = action,
                        description = description
                    )
                }

                "error" -> {
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                    val code = obj["code"]?.jsonPrimitive?.intOrNull
                    SseEvent.Error(
                        sessionId = sessionId,
                        message = message,
                        code = code
                    )
                }

                "session_created" -> {
                    SseEvent.SessionCreated(sessionId = sessionId)
                }

                "message_complete" -> {
                    val messageId = obj["messageId"]?.jsonPrimitive?.contentOrNull ?: return null
                    SseEvent.MessageComplete(
                        sessionId = sessionId,
                        messageId = messageId
                    )
                }

                else -> {
                    logger.debug { "Unknown SSE event type: $eventType" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing SSE event of type: $eventType" }
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
        // Only close the SSE client we created; the main httpClient is owned by the caller
        sseClient.close()
    }
}
