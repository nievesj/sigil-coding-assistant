package com.opencode.acp.mcp

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [SseTransport], [SseConnection], and [HttpUrlSseTransport]
 * (TDD §4.2.4).
 *
 * [HttpUrlSseTransport] makes real HTTP connections, so these tests exercise
 * the failure paths (connection refused, malformed URL) which do not require a
 * running server. [FakeSseTransport] and [FakeSseConnection] demonstrate the
 * testability the interfaces provide for consumers.
 */
class SseTransportTest {

    // ── HttpUrlSseTransport: connection refused ───────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `connect throws IOException when connection is refused`() {
        val transport = HttpUrlSseTransport()
        // Port 1 is a privileged port that nothing listens on — connection refused.
        assertThrows<IOException> {
            kotlinx.coroutines.runBlocking {
                transport.connect("http://127.0.0.1:1/no-such-port")
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `connect does not hang when connection is refused`() {
        val transport = HttpUrlSseTransport()
        // If this hangs, the @Timeout annotation will fail the test.
        assertThrows<IOException> {
            kotlinx.coroutines.runBlocking {
                transport.connect("http://127.0.0.1:1/no-such-port")
            }
        }
    }

    // ── HttpUrlSseTransport: malformed URL ─────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `connect throws exception for malformed URL`() {
        val transport = HttpUrlSseTransport()
        assertThrows<Exception> {
            kotlinx.coroutines.runBlocking {
                transport.connect("invalid-url")
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `connect throws exception for empty URL`() {
        val transport = HttpUrlSseTransport()
        assertThrows<Exception> {
            kotlinx.coroutines.runBlocking {
                transport.connect("")
            }
        }
    }

    // ── SseTransport / SseConnection interface contract ───────────────────

    @Test
    fun `FakeSseTransport returns the configured connection`() = kotlinx.coroutines.runBlocking {
        val connection = FakeSseConnection(response = JsonPrimitive("ok"))
        val transport = FakeSseTransport(connection)
        transport.connect("http://anything") shouldBe connection
        transport.connectCount shouldBe 1
        transport.lastBaseUrl shouldBe "http://anything"
    }

    @Test
    fun `FakeSseConnection returns the configured response`() = kotlinx.coroutines.runBlocking {
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject { put("tools", "[]") })
        }
        val connection = FakeSseConnection(response = response)
        val result = connection.sendRequest("http://base", "tools/list", null)
        result shouldBe response
    }

    @Test
    fun `FakeSseConnection records method and params for each call`() = kotlinx.coroutines.runBlocking {
        val connection = FakeSseConnection(response = JsonPrimitive("ok"))
        val params = buildJsonObject { put("cursor", "next") }
        connection.sendRequest("http://base", "tools/list", params)
        connection.sendRequest("http://base", "tools/call", null)

        connection.callCount shouldBe 2
        connection.calls[0].method shouldBe "tools/list"
        connection.calls[0].params shouldBe params
        connection.calls[1].method shouldBe "tools/call"
        connection.calls[1].params shouldBe null
    }

    @Test
    fun `FakeSseConnection returns null when configured with null response`() = kotlinx.coroutines.runBlocking {
        val connection = FakeSseConnection(response = null)
        connection.sendRequest("http://base", "tools/list", null) shouldBe null
    }

    @Test
    fun `FakeSseConnection close records invocation`() {
        val connection = FakeSseConnection(response = null)
        connection.close()
        connection.closed shouldBe true
    }
}

/**
 * Fake [SseTransport] for testing consumers without hitting real network
 * endpoints. Returns the configured [SseConnection] and records call details.
 */
class FakeSseTransport(private val connectResult: SseConnection) : SseTransport {
    var connectCount: Int = 0
        private set
    var lastBaseUrl: String? = null
        private set

    override suspend fun connect(baseUrl: String): SseConnection {
        connectCount++
        lastBaseUrl = baseUrl
        return connectResult
    }
}

/**
 * Fake [SseConnection] for testing consumers. Returns a configurable
 * [JsonElement] response and records each request's method and params.
 */
class FakeSseConnection(private val response: JsonElement?) : SseConnection {
    data class RecordedCall(val baseUrl: String, val method: String, val params: JsonElement?)

    val calls = mutableListOf<RecordedCall>()
    var closed: Boolean = false
        private set

    val callCount: Int get() = calls.size

    override suspend fun sendRequest(baseUrl: String, method: String, params: JsonElement?): JsonElement? {
        calls.add(RecordedCall(baseUrl, method, params))
        return response
    }

    override fun close() {
        closed = true
    }
}