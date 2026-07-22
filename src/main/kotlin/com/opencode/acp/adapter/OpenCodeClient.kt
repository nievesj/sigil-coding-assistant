package com.opencode.acp.adapter

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.sse.SseEventParser

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader

private val logger = KotlinLogging.logger {}

/** Timeout profile alias shared by [OpenCodeClient] and [HttpHelper].
 *  Canonical definition: [HttpHelper.TimeoutProfile]. */
typealias TimeoutProfile = HttpHelper.TimeoutProfile

/** Debug log helper — uses logger so output goes to idea.log, not a temp file.
 *  Uses DEBUG level (not INFO) to avoid leaking prompt content in default logs. */
private fun debugLog(msg: String) {
    logger.debug { "[ACP-SSE] $msg" }
}

/**
 * HTTP client for the OpenCode REST API using Ktor.
 *
 * Provides methods to interact with all OpenCode session, message,
 * agent, command, and SSE event endpoints.
 *
 * Owns its [HttpClient] internally — creates, configures, and closes it.
 * After [shutdown], no HTTP calls can be made on this instance.
 *
 * @param baseUrl the base URL of the OpenCode server (e.g. "http://localhost:3000")
 * @param authToken optional bearer token for authenticated requests
 */
class OpenCodeClient(
    private val baseUrl: String,
    private val authToken: String? = null
) {

    /** Timeout profiles for different endpoint categories.
     *  SHORT — fast server-side operations (default 60s)
     *  LONG — operations that may trigger LLM generation (responseTimeoutSeconds + buffer)
     *  INFINITE — blocks for entire LLM generation (no request timeout)
     *
     *  Canonical definition lives in [HttpHelper.TimeoutProfile]; this typealias
     *  (`TimeoutProfile` at file scope) keeps existing call sites in [OpenCodeClient]
     *  unchanged. The timeout-application logic itself moved to [HttpHelper]. */
    // (TimeoutProfile is a file-level typealias to HttpHelper.TimeoutProfile.)

    /** Applies the given timeout profile to an HTTP request builder.
     *  Delegates to [HttpHelper]'s private implementation via the shared
     *  [httpHelper] instance so the timeout policy lives in one place. */
    private fun HttpRequestBuilder.applyTimeoutProfile(profile: TimeoutProfile) {
        httpHelper.applyTimeoutProfileTo(this, profile)
    }

    private val httpClient = HttpClient(Java) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = SHORT_TIMEOUT_MS    // 60s — client-wide default for SHORT ops
            connectTimeoutMillis = CONNECT_TIMEOUT_MS   // 10s — TCP connection timeout
        }
    }

    /**
     * Separate HttpClient for MCP server requests (discovery, verification, tool listing).
     * This client does NOT need auth headers, SSE, or ContentNegotiation — it makes
     * simple GET requests to localhost MCP servers. It's separate from [httpClient]
     * because MCP servers are on different hosts than the OpenCode server.
     * Lazy — only created when MCP is actually used, avoiding resource waste when disabled.
     */
    @Volatile
    private var _mcpHttpClient: HttpClient? = null
    private val mcpClientLock = Any()
    val mcpHttpClient: HttpClient
        get() {
            _mcpHttpClient?.let { return it }
            synchronized(mcpClientLock) {
                _mcpHttpClient?.let { return it }
                return HttpClient(Java) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 5_000   // 5s for MCP verification/tool fetch
                        connectTimeoutMillis = 3_000   // 3s TCP connection timeout
                    }
                }.also { _mcpHttpClient = it }
            }
        }

    // NOTE: Two separate Json instances are intentional — do NOT merge them.
    // 1. This instance-level Json has classDiscriminator = "type" for polymorphic deserialization
    //    used in manual json.decodeFromString<T>(body) and json.encodeToString(...) calls.
    // 2. The ContentNegotiation plugin above uses its own Json without classDiscriminator
    //    for automatic request/response serialization.
    // Merging would break polymorphic deserialization in the manual helpers.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    /** HTTP helper owning the generic GET/POST/DELETE patterns, error checking,
     *  auth header application, and per-request timeout profile selection.
     *  Extracted from OpenCodeClient (TDD §4.2.3 — HttpHelper). The private
     *  `getJson`/`postJson`/`postSuccess`/`deleteSuccess` helpers below delegate
     *  to this instance; direct (non-helper) HTTP calls use [applyAuth] and
     *  [applyTimeoutProfile] which also delegate here. */
    internal val httpHelper = HttpHelper(httpClient, baseUrl, json, authToken)

    /** SSE event parser — extracted from the former 691-line `parseSseEvent()` method (TDD Phase 1).
     *  Owns the per-subscription `reasoningPartIds` state used to disambiguate V1
     *  `message.part.delta` events with `field: "text"`. See [SseEventParser]. */
    private val sseEventParser = SseEventParser(logger)

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

    /** Applies the bearer auth header (if configured) to a request builder.
     *  Delegates to [HttpHelper] so the auth policy lives in one place. */
    private fun HttpRequestBuilder.applyAuth() {
        httpHelper.applyAuthTo(this)
    }

    // -------------------------------------------------------------------------
    // Generic HTTP helpers (delegate to HttpHelper — TDD §4.2.3)
    // -------------------------------------------------------------------------

    /**
     * Performs an HTTP GET and deserializes the response body as [T].
     * Delegates to [HttpHelper.getJson].
     */
    private suspend inline fun <reified T> getJson(path: String): T =
        httpHelper.getJson(path)

    /**
     * Performs an HTTP POST with a JSON body and deserializes the response as [T].
     * Delegates to [HttpHelper.postJson].
     */
    private suspend inline fun <reified T> postJson(
        path: String,
        request: Any,
        profile: TimeoutProfile = TimeoutProfile.SHORT,
    ): T = httpHelper.postJson(path, request, profile)

    /**
     * Performs an HTTP POST with optional JSON body and returns true if the status is successful.
     * Delegates to [HttpHelper.postSuccess].
     */
    private suspend fun postSuccess(
        path: String,
        request: Any? = null,
        profile: TimeoutProfile = TimeoutProfile.SHORT,
    ): Boolean = httpHelper.postSuccess(path, request, profile)

    /**
     * Performs an HTTP DELETE and returns true if the status is successful.
     * Delegates to [HttpHelper.deleteSuccess].
     */
    private suspend fun deleteSuccess(path: String): Boolean =
        httpHelper.deleteSuccess(path)

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    /**
     * Checks whether the OpenCode server is reachable and healthy.
     * GET /global/health
     *
     * Propagates [CancellationException] (including [kotlinx.coroutines.TimeoutCancellationException])
     * for consistency with postSuccess/deleteSuccess — coroutine cancellation should not be swallowed.
     */
    suspend fun healthCheck(): Boolean = try {
        val response = httpClient.get("$baseUrl/global/health") {
            applyAuth()
        }
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e  // Propagate coroutine cancellation (includes timeout)
    } catch (e: Exception) {
        logger.warn(e) { "Health check failed" }
        false
    }

    // -------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------

    /**
     * Creates a new OpenCode session.
     * POST /session?directory=<directory>
     *
     * @param title optional session title
     * @param directory optional project directory — the server resolves the
     *   project ID from this path so the new session is associated with the
     *   correct project.  Null = no filter (server uses its own CWD).
     */
    suspend fun createSession(
        title: String? = null,
        directory: String? = null
    ): OpenCodeSession {
        val requestBody = buildJsonObject {
            if (title != null) put("title", title)
        }.toString()
        val response = httpClient.post("$baseUrl/session") {
            applyAuth()
            contentType(ContentType.Application.Json)
            directory?.let { parameter("directory", normalizeDirectoryPath(it)) }
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
     * GET /session?directory=<directory>
     *
     * @param directory optional project directory — the server resolves the
     *   project ID from this path and returns only sessions belonging to that
     *   project.  Null = no filter (server returns all sessions).
     */
    suspend fun listSessions(directory: String? = null): List<OpenCodeSession> {
        val response = httpClient.get("$baseUrl/session") {
            applyAuth()
            directory?.let { parameter("directory", normalizeDirectoryPath(it)) }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "GET /session returned ${response.status}: ${body.take(500)}" }
            error("GET /session failed with ${response.status}: ${body.take(200)}")
        }
        return try {
            json.decodeFromString<List<OpenCodeSession>>(body)
        } catch (e: Exception) {
            logger.error(e) { "GET /session deserialization failed. Body preview: ${body.take(1000)}" }
            throw e
        }
    }

    /**
     * Deletes a session.
     * DELETE /session/{id}
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        validatePathId(sessionId, "sessionId")
        return deleteSuccess("/session/$sessionId")
    }

    /**
     * Gets a single session by ID, including full token counts, cost, and model info.
     * GET /session/{id}
     */
    suspend fun getSession(sessionId: String): OpenCodeSession {
        validatePathId(sessionId, "sessionId")
        return getJson("/session/$sessionId")
    }

    /**
     * Aborts a running session.
     * POST /session/{id}/abort
     */
    suspend fun abortSession(sessionId: String): Boolean {
        validatePathId(sessionId, "sessionId")
        return postSuccess("/session/$sessionId/abort")
    }

    /**
     * Forks (clones) a session up to (optionally) a specific message.
     * POST /session/{id}/fork
     */
    suspend fun forkSession(
        sessionId: String,
        messageId: String? = null
    ): OpenCodeSession {
        validatePathId(sessionId, "sessionId")
        messageId?.let { validatePathId(it, "messageId") }
        return postJson("/session/$sessionId/fork", ForkSessionRequest(messageId = messageId))
    }

    /**
     * Creates a share link for a session (read-only view).
     * GET /session/{id}/share
     */
    suspend fun shareSession(sessionId: String): OpenCodeSession {
        validatePathId(sessionId, "sessionId")
        return getJson("/session/$sessionId/share")
    }

    /**
     * Removes a share link from a session.
     * DELETE /session/{id}/share
     */
    suspend fun unshareSession(sessionId: String): OpenCodeSession {
        validatePathId(sessionId, "sessionId")
        return try {
            val response = httpClient.delete("$baseUrl/session/$sessionId/share") {
                applyAuth()
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                logger.error { "DELETE /session/$sessionId/share returned ${response.status}: ${body.take(500)}" }
                error("DELETE /session/$sessionId/share failed with ${response.status}: ${body.take(200)}")
            }
            json.decodeFromString(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to unshare session $sessionId" }
            throw e
        }
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
    ): Boolean {
        validatePathId(sessionId, "sessionId")
        return postSuccess("/session/$sessionId/summarize", CompactSessionRequest(
            providerID = providerID,
            modelID = modelID,
            auto = auto
        ), profile = TimeoutProfile.LONG)
    }

    /**
     * Reverts a specific message (and optionally a part within it) from the session.
     * POST /session/{id}/revert
     */
    suspend fun revertMessage(
        sessionId: String,
        messageId: String,
        partId: String? = null
    ): Boolean {
        validatePathId(sessionId, "sessionId")
        validatePathId(messageId, "messageId")
        return postSuccess("/session/$sessionId/revert", RevertMessageRequest(messageId = messageId, partId = partId))
    }

    /**
     * Un-reverts (restores) all previously reverted messages in the session.
     * POST /session/{id}/unrevert
     */
    suspend fun unrevertMessages(sessionId: String): Boolean {
        validatePathId(sessionId, "sessionId")
        return postSuccess("/session/$sessionId/unrevert")
    }

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
        validatePathId(sessionId, "sessionId")
        val requestBody = json.encodeToString(
            SendMessageRequest(parts = parts, variant = variant, agent = agent, model = model)
        )
        logger.debug { "Sending message: ${requestBody.take(200)}" }
        // NOTE: requestBody contains user prompt text — downgraded to DEBUG to avoid
        // leaking prompt content in default (INFO) logs. Aligns with the debugLog
        // helper rationale at the top of this file.
        val startTime = System.currentTimeMillis()
        try {
            val response = httpClient.post("$baseUrl/session/$sessionId/message") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(requestBody)
                // The server's POST /session/:id/message blocks until the LLM finishes
                // generating (can be minutes for complex tool chains). The activity
                // monitor in sendMessageInternal() handles generation timeouts with a
                // tool-running guard — this POST timeout must NOT compete with it.
                // Use no request timeout so the POST waits as long as the server needs.
                applyTimeoutProfile(TimeoutProfile.INFINITE)
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
        validatePathId(sessionId, "sessionId")
        val clampedLimit = limit?.let { if (it < 0) null else it.coerceAtMost(10000) }
        val path = buildString {
            append("/session/$sessionId/message")
            if (clampedLimit != null) append("?limit=$clampedLimit")
        }
        return getJson(path)
    }

    /**
     * Gets the todo list for a session.
     * GET /session/{id}/todo
     */
    suspend fun getTodos(sessionId: String): List<TodoItem> {
        validatePathId(sessionId, "sessionId")
        return getJson("/session/$sessionId/todo")
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
     *
     * Uses the LONG timeout profile because commands can trigger LLM generation
     * (e.g., /review, /init) that may take minutes.
     */
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        args: String
    ): OpenCodeMessage {
        validatePathId(sessionId, "sessionId")
        return postJson("/session/$sessionId/command", ExecuteCommandRequest(command = command, args = args), profile = TimeoutProfile.LONG)
    }

    // -------------------------------------------------------------------------
    // MCP
    // -------------------------------------------------------------------------

    /**
     * Register an MCP server dynamically with the OpenCode server.
     * POST /mcp
     *
     * @param body the full JSON body including name and config (as JsonObject)
     * @return true if the server responded with success
     */
    suspend fun addMcpServer(body: JsonObject): Boolean {
        return try {
            val response = httpClient.post("$baseUrl/mcp") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] POST /mcp failed" }
            false
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Validates that an ID is safe to interpolate into a URL path segment.
     * Delegates to [PathValidator.validatePathId].
     * Throws [IllegalArgumentException] if the ID contains characters outside
     * the strict allow-list `^[A-Za-z0-9_-]{1,128}$`.
     */
    private fun validatePathId(id: String, fieldName: String) {
        PathValidator.validatePathId(id, fieldName)
    }

    /**
     * Responds to a permission request.
     * POST /session/{sessionId}/permissions/{permissionID}
     */
    suspend fun respondPermission(
        permissionId: String,
        sessionId: String,
        response: String
    ) {
        validatePathId(permissionId, "permissionId")
        validatePathId(sessionId, "sessionId")
        val httpResponse = httpClient.post("$baseUrl/session/$sessionId/permissions/$permissionId") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PermissionRequest(response = response)))
        }
        if (!httpResponse.status.isSuccess()) {
            val body = httpResponse.bodyAsText()
            logger.error { "POST /session/$sessionId/permissions/$permissionId returned ${httpResponse.status}: ${body.take(500)}" }
            error("POST /session/$sessionId/permissions/$permissionId failed with ${httpResponse.status}: ${body.take(200)}")
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
        validatePathId(requestId, "requestId")
        val response = httpClient.post("$baseUrl/question/$requestId/reply") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(QuestionReplyRequest(answers = answers)))
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            logger.error { "POST /question/$requestId/reply returned ${response.status}: ${body.take(500)}" }
            error("POST /question/$requestId/reply failed with ${response.status}: ${body.take(200)}")
        }
    }

    /**
     * Rejects / dismisses a question from the agent.
     * POST /question/{requestID}/reject
     */
    suspend fun rejectQuestion(requestId: String) {
        validatePathId(requestId, "requestId")
        val response = httpClient.post("$baseUrl/question/$requestId/reject") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            logger.error { "POST /question/$requestId/reject returned ${response.status}: ${body.take(500)}" }
            error("POST /question/$requestId/reject failed with ${response.status}: ${body.take(200)}")
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
                    val connectTime = System.currentTimeMillis()
                    logger.info { "[ACP] SSE connected to $baseUrl/event at $connectTime" }
                    incoming.collect { event ->
                        val data = event.data ?: return@collect
                        val jsonObj: JsonObject = try {
                            json.parseToJsonElement(data).jsonObject
                        } catch (e: CancellationException) {
                            throw e
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
                        try {
                            val v2Data = jsonObj["data"]?.jsonObject
                            val props: JsonObject
                            val eventType: String

                            if (v2Data != null) {
                                // V2 SyncEvent: payload is in "data", type has version suffix (e.g. ".1")
                                // Defensive: V2 events also carry "seq" and "aggregateID". If "data"
                                // is present but "seq" is absent, this may be a V1 event with a "data"
                                // field — log a warning to catch server format drift early.
                                if (jsonObj["seq"] == null) {
                                    logger.warn { "[ACP-SSE] V2 detection heuristic: 'data' present but 'seq' absent — possible V1 misdetection. rawType=$rawEventType, keys=${jsonObj.keys}" }
                                }
                                props = v2Data
                                // Strip version suffix: "session.next.text.delta.1" → "session.next.text.delta"
                                eventType = rawEventType.replace(VERSION_SUFFIX_REGEX, "")
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

                            val parsed = sseEventParser.parse(eventType, props, sessionId)
                            if (parsed is SseEvent.Ignored) {
                                logger.debug { "[ACP-SSE] IGNORED: type=$eventType, reason=${parsed.reason}" }
                            } else {
                                debugLog("SSE OK: ${parsed::class.simpleName}")
                            }
                            send(parsed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "[ACP-SSE] Failed to process event rawType=$rawEventType, skipping" }
                            return@collect
                        }
                    }
                    logger.info { "[ACP] SSE stream ended (connected at $connectTime)" }
                }
            } catch (e: Exception) {
                // CancellationException extends Exception — re-throw to preserve
                // structured concurrency (matches healthCheck/postSuccess pattern).
                if (e is CancellationException) throw e
                logger.warn(e) { "[ACP] SSE connection closed with error" }
            }
        }
        awaitClose {
            job.cancel()
            logger.info { "SSE subscription cancelled" }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Closes the internal HttpClient. After shutdown(), no HTTP calls can be made
     * on this instance. Callers must not call shutdown() while HTTP requests are
     * in flight — the SSE subscription should be cancelled before calling this.
     *
     * ISP fix (TDD §4.2.5): This is an explicit shutdown method rather than
     * implementing [AutoCloseable]. The plugin lifecycle is managed explicitly
     * by [OpenCodeService.dispose] → [ProcessManager.shutdown] → this method.
     */
    fun shutdown() {
        synchronized(mcpClientLock) {
            _mcpHttpClient?.close()
            _mcpHttpClient = null
        }
        httpClient.close()
    }

    /** Clear reasoning part ID tracking. Call on session switch.
     *  Delegates to [SseEventParser.resetReasoningTracking]. */
    fun resetReasoningTracking() {
        sseEventParser.resetReasoningTracking()
    }

    companion object {
        /** Client-wide default request timeout for SHORT-profile (fast server-side) operations. */
        private const val SHORT_TIMEOUT_MS = 60_000L
        /** TCP connection timeout — applies to all profiles (cannot be overridden per-request). */
        private const val CONNECT_TIMEOUT_MS = 10_000L
        /** Pre-compiled regex to strip V2 SyncEvent version suffix (e.g. ".1").
         *  Hoisted out of the per-event collect lambda to avoid recompiling on
         *  every SSE event. */
        private val VERSION_SUFFIX_REGEX = Regex("\\.\\d+$")

        /**
         * Normalize a directory path for the `?directory=` query parameter.
         * Delegates to [PathValidator.normalizeDirectoryPath].
         *
         * On Windows, the server expects backslash-separated paths (e.g.
         * `D:\Projects\foo`).  Forward slashes cause the server to return
         * an empty session list, so we normalize before passing to Ktor's
         * `parameter()` builder (which handles URL-encoding correctly).
         */
        internal fun normalizeDirectoryPath(path: String): String =
            PathValidator.normalizeDirectoryPath(path)
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
