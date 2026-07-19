package com.opencode.acp.adapter

import com.opencode.acp.chat.util.SettingsProvider
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * HTTP helper methods extracted from [OpenCodeClient].
 *
 * Consolidates the GET/POST/DELETE request patterns with shared error checking,
 * auth header application, and per-request timeout profile selection. Owning
 * these generic helpers here keeps [OpenCodeClient] focused on endpoint-specific
 * request/response DTO wiring.
 *
 * The [TimeoutProfile] enum is the canonical type used by both [HttpHelper] and
 * [OpenCodeClient] (via a `typealias`). The [applyTimeoutProfile] / [applyAuth]
 * helpers live here next to the request execution; [OpenCodeClient] retains
 * thin private copies for its direct (non-helper) calls (e.g. `sendMessageAsync`,
 * `createSession`) that bypass the generic helpers.
 *
 * [checkResponse] consolidates the 10x repeated error-checking pattern
 * (status check + log + throw) into one place (DRY fix, TDD §4.7.6).
 *
 * @param httpClient the Ktor client used for all requests (owned by the caller)
 * @param baseUrl base URL of the OpenCode server (e.g. "http://localhost:4096")
 * @param json the polymorphic Json instance used for manual (de)serialization
 *   (must have `classDiscriminator = "type"` for OpenCodePart polymorphism)
 * @param authToken optional bearer token applied to every request
 * @param settingsProvider provides [OpenCodeSettingsState] for timeout profile
 *   resolution. Injected (rather than read statically) so HttpHelper is testable
 *   without IntelliJ's service locator.
 *
 * See TDD §4.2.3 — HttpHelper.
 */
class HttpHelper(
    @PublishedApi internal val httpClient: HttpClient,
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val json: Json,
    @PublishedApi internal val authToken: String? = null,
    private val settingsProvider: SettingsProvider = com.opencode.acp.chat.util.IntelliJSettingsProvider(),
) {
    @PublishedApi internal val logger = KotlinLogging.logger {}

    /** Timeout profiles for different endpoint categories.
     *  SHORT — fast server-side operations (uses the client-wide default)
     *  LONG — operations that may trigger LLM generation (responseTimeoutSeconds + buffer)
     *  INFINITE — blocks for entire LLM generation (no request timeout) */
    enum class TimeoutProfile { SHORT, LONG, INFINITE }

    /** Applies the given timeout profile to an HTTP request builder.
     *  SHORT uses the client-wide default — no override.
     *  LONG uses responseTimeoutSeconds + longTimeoutBufferSeconds (both from settings).
     *  INFINITE uses no request timeout (the activity monitor handles generation timeouts). */
    @PublishedApi internal fun io.ktor.client.request.HttpRequestBuilder.applyTimeoutProfile(profile: TimeoutProfile) {
        when (profile) {
            TimeoutProfile.SHORT -> {
                // SHORT uses the client-wide default (60s) — no override needed.
                logger.debug { "[ACP] Applying timeout profile $profile (60s default)" }
            }
            TimeoutProfile.LONG -> {
                val settings = settingsProvider.get()
                val timeoutMs = settings.responseTimeoutSeconds * 1000L + settings.longTimeoutBufferSeconds * 1000L
                timeout { requestTimeoutMillis = timeoutMs }
                logger.debug { "[ACP] Applying timeout profile $profile (${timeoutMs}ms)" }
            }
            TimeoutProfile.INFINITE -> {
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                logger.debug { "[ACP] Applying timeout profile $profile (infinite)" }
            }
        }
    }

    /** Public bridge so [OpenCodeClient] can apply the shared timeout policy to
     *  request builders used in its direct (non-helper) HTTP calls
     *  (e.g. `sendMessageAsync`, `createSession`). Keeps the timeout logic in
     *  one place while letting [OpenCodeClient] retain bespoke request bodies. */
    fun applyTimeoutProfileTo(
        builder: io.ktor.client.request.HttpRequestBuilder,
        profile: TimeoutProfile,
    ) {
        builder.applyTimeoutProfile(profile)
    }

    @PublishedApi internal fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** Public bridge so [OpenCodeClient] can apply the shared auth header to
     *  request builders used in its direct (non-helper) HTTP calls. */
    fun applyAuthTo(builder: io.ktor.client.request.HttpRequestBuilder) {
        builder.applyAuth()
    }

    /**
     * Consolidated response error checking (DRY fix, TDD §4.7.6).
     *
     * Logs the failed response and throws an [IllegalStateException] with a
     * concise message. Used by [getJson] / [postJson] and exposed for callers
     * that perform direct HTTP requests but want the same error handling.
     *
     * NOTE: The response body is logged at ERROR level. If the server echoes
     * sensitive data (auth tokens, PII, secrets) in error responses, it will be
     * written to idea.log. Sanitize upstream if this is a concern.
     *
     * @param method HTTP method name (e.g. "GET", "POST") for the error message
     * @param path the request path (used in log + error message)
     * @param response the HTTP response to check
     * @throws IllegalStateException if `response.status` is not successful
     */
    suspend fun checkResponse(method: String, path: String, response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            logger.error { "$method $path returned ${response.status}: ${body.take(500)}" }
            error("$method $path failed with ${response.status}: ${body.take(200)}")
        }
    }

    /**
     * Performs an HTTP GET and deserializes the response body as [T].
     *
     * NOTE: On deserialization failure, a 1000-char body preview is logged at
     * ERROR level for diagnostics. This is verbose and may leak response data
     * to idea.log; in production it should be gated behind DEBUG level.
     */
    suspend inline fun <reified T> getJson(path: String): T {
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
     *
     * NOTE: On deserialization failure, a 1000-char body preview is logged at
     * ERROR level for diagnostics. This is verbose and may leak response data
     * to idea.log; in production it should be gated behind DEBUG level.
     */
    suspend inline fun <reified T> postJson(
        path: String,
        request: Any,
        profile: TimeoutProfile = TimeoutProfile.SHORT,
    ): T {
        val response = httpClient.post("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(kotlinx.serialization.serializer(request::class.java), request))
            applyTimeoutProfile(profile)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "POST $path returned ${response.status}: ${body.take(500)}" }
            error("POST $path failed with ${response.status}: ${body.take(200)}")
        }
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            logger.error(e) { "POST $path deserialization failed. Body preview: ${body.take(1000)}" }
            throw e
        }
    }

    /**
     * Performs an HTTP POST with optional JSON body and returns true if the status is successful.
     * Propagates [CancellationException] (including [kotlinx.coroutines.TimeoutCancellationException])
     * so callers can distinguish timeout/cancellation from server errors.
     *
     * NOTE: Returns `false` for both 'request failed' and 'server state unknown' (e.g.,
     * network error after server processed the request). Callers must NOT assume
     * `false` means the server definitely did not process the request — only that
     * the client did not receive a success response.
     */
    suspend fun postSuccess(
        path: String,
        request: Any? = null,
        profile: TimeoutProfile = TimeoutProfile.SHORT,
    ): Boolean = try {
        val response = httpClient.post("$baseUrl$path") {
            applyAuth()
            contentType(ContentType.Application.Json)
            if (request != null) {
                setBody(json.encodeToString(kotlinx.serialization.serializer(request::class.java), request))
            }
            applyTimeoutProfile(profile)
        }
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e  // Propagate coroutine cancellation (includes TimeoutCancellationException)
    } catch (e: Exception) {
        logger.warn(e) { "POST $path failed" }
        false
    }

    /**
     * Performs an HTTP DELETE and returns true if the status is successful.
     * Propagates [CancellationException] (including [kotlinx.coroutines.TimeoutCancellationException])
     * so callers can distinguish timeout/cancellation from server errors.
     */
    suspend fun deleteSuccess(path: String): Boolean = try {
        val response = httpClient.delete("$baseUrl$path") {
            applyAuth()
        }
        response.status.isSuccess()
    } catch (e: CancellationException) {
        throw e  // Propagate coroutine cancellation (includes timeout)
    } catch (e: Exception) {
        logger.warn(e) { "DELETE $path failed" }
        false
    }
}