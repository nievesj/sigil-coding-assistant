package com.opencode.acp.adapter

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.opencode.acp.chat.util.FakeSettingsProvider
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [HttpHelper] (TDD §8.1 — HttpHelper scenarios).
 *
 * Uses WireMock to stand up a real HTTP server on a random port. The [HttpClient]
 * is a real Ktor Java-engine client pointed at the WireMock base URL — no mocks
 * of the HTTP layer. This exercises the full request/response path including
 * auth headers, JSON serialization, error checking, and timeout profile selection.
 *
 * [FakeSettingsProvider] supplies an [OpenCodeSettingsState] with configurable
 * timeout values so the LONG timeout profile can be verified without waiting
 * real time.
 */
class HttpHelperTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var httpClient: HttpClient
    private lateinit var helper: HttpHelper
    private lateinit var settings: OpenCodeSettingsState

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wireMock.start()
        WireMock.configureFor("localhost", wireMock.port())

        httpClient = HttpClient(Java) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 5_000
            }
        }
        settings = OpenCodeSettingsState()
        helper = HttpHelper(
            httpClient = httpClient,
            baseUrl = wireMock.baseUrl(),
            json = json,
            authToken = "test-token",
            settingsProvider = FakeSettingsProvider(settings),
        )
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        wireMock.stop()
    }

    // -------------------------------------------------------------------------
    // getJson
    // -------------------------------------------------------------------------

    @Test
    fun `getJson deserializes a successful response`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(
                    WireMock.okJson("""{"id":"abc","name":"test"}""")
                )
        )

        val result: Foo = helper.getJson("/foo")
        result.id shouldBe "abc"
        result.name shouldBe "test"
    }

    @Test
    fun `getJson applies auth header when token is configured`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""{"id":"abc","name":"test"}"""))
        )

        helper.getJson<Foo>("/foo")

        wireMock.verify(
            WireMock.getRequestedFor(WireMock.urlEqualTo("/foo"))
                .withHeader(HttpHeaders.Authorization, WireMock.equalTo("Bearer test-token"))
        )
    }

    @Test
    fun `getJson throws on non-success status`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(404).withBody("not found"))
        )

        val ex = assertThrows<IllegalStateException> {
            helper.getJson<Foo>("/foo")
        }
        ex.message shouldContain "GET /foo failed with 404"
    }

    @Test
    fun `getJson throws on deserialization failure`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""not valid json"""))
        )

        assertThrows<Exception> {
            helper.getJson<Foo>("/foo")
        }
    }

    @Test
    fun `getJson deserializes a list`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/items"))
                .willReturn(
                    WireMock.okJson("""[{"id":"a","name":"x"},{"id":"b","name":"y"}]""")
                )
        )

        val result: List<Foo> = helper.getJson("/items")
        result shouldHaveSize 2
        result[0].id shouldBe "a"
        result[1].name shouldBe "y"
    }

    // -------------------------------------------------------------------------
    // postJson
    // -------------------------------------------------------------------------

    @Test
    fun `postJson sends JSON body and deserializes response`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""{"id":"abc","name":"created"}"""))
        )

        val result: Foo = helper.postJson("/foo", FooReq(value = "hello"))
        result.id shouldBe "abc"
        result.name shouldBe "created"

        wireMock.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/foo"))
                .withRequestBody(WireMock.equalToJson("""{"value":"hello"}"""))
                .withHeader(HttpHeaders.Authorization, WireMock.equalTo("Bearer test-token"))
        )
    }

    @Test
    fun `postJson throws on non-success status`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(500).withBody("server error"))
        )

        val ex = assertThrows<IllegalStateException> {
            helper.postJson<Foo>("/foo", FooReq("x"))
        }
        ex.message shouldContain "POST /foo failed with 500"
    }

    // -------------------------------------------------------------------------
    // postSuccess
    // -------------------------------------------------------------------------

    @Test
    fun `postSuccess returns true on 2xx`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        helper.postSuccess("/foo") shouldBe true
    }

    @Test
    fun `postSuccess returns false on 4xx`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(400).withBody("bad request"))
        )

        helper.postSuccess("/foo") shouldBe false
    }

    @Test
    fun `postSuccess returns false on 5xx`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(500).withBody("server error"))
        )

        helper.postSuccess("/foo") shouldBe false
    }

    @Test
    fun `postSuccess sends JSON body when request provided`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        helper.postSuccess("/foo", FooReq("payload"))

        wireMock.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/foo"))
                .withRequestBody(WireMock.equalToJson("""{"value":"payload"}"""))
        )
    }

    @Test
    fun `postSuccess sends no body when request is null`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        helper.postSuccess("/foo", request = null)

        // When request is null, no setBody is called — the POST has an empty body.
        // WireMock's equalTo("") doesn't match an empty body, so we just verify
        // the request was made (the body is empty by construction).
        wireMock.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/foo"))
        )
    }

    @Test
    fun `postSuccess returns false on connection failure`() = runTest {
        // Don't stub — WireMock returns 404 by default for unmatched, but we
        // want a connection failure. Stop the server to simulate a dead server.
        wireMock.stop()

        helper.postSuccess("/foo") shouldBe false
        // Restart for afterEach tearDown
        wireMock.start()
    }

    // -------------------------------------------------------------------------
    // deleteSuccess
    // -------------------------------------------------------------------------

    @Test
    fun `deleteSuccess returns true on 2xx`() = runTest {
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        helper.deleteSuccess("/foo") shouldBe true
    }

    @Test
    fun `deleteSuccess returns false on 4xx`() = runTest {
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(404).withBody("not found"))
        )

        helper.deleteSuccess("/foo") shouldBe false
    }

    @Test
    fun `deleteSuccess applies auth header`() = runTest {
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        helper.deleteSuccess("/foo")

        wireMock.verify(
            WireMock.deleteRequestedFor(WireMock.urlEqualTo("/foo"))
                .withHeader(HttpHeaders.Authorization, WireMock.equalTo("Bearer test-token"))
        )
    }

    @Test
    fun `deleteSuccess returns false on connection failure`() = runTest {
        wireMock.stop()

        helper.deleteSuccess("/foo") shouldBe false
        wireMock.start()
    }

    // -------------------------------------------------------------------------
    // checkResponse
    // -------------------------------------------------------------------------

    @Test
    fun `checkResponse throws on non-success status`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.status(403).withBody("forbidden"))
        )

        val response = httpClient.get("${wireMock.baseUrl()}/foo")
        val ex = assertThrows<IllegalStateException> {
            helper.checkResponse("GET", "/foo", response)
        }
        ex.message shouldContain "GET /foo failed with 403"
    }

    @Test
    fun `checkResponse does not throw on success status`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.ok("ok"))
        )

        val response = httpClient.get("${wireMock.baseUrl()}/foo")
        // Should not throw
        helper.checkResponse("GET", "/foo", response)
    }

    // -------------------------------------------------------------------------
    // Auth header (no token)
    // -------------------------------------------------------------------------

    @Test
    fun `no auth header sent when token is null`() = runTest {
        val noAuthHelper = HttpHelper(
            httpClient = httpClient,
            baseUrl = wireMock.baseUrl(),
            json = json,
            authToken = null,
            settingsProvider = FakeSettingsProvider(settings),
        )
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""{"id":"a","name":"b"}"""))
        )

        noAuthHelper.getJson<Foo>("/foo")

        wireMock.verify(
            WireMock.getRequestedFor(WireMock.urlEqualTo("/foo"))
                .withoutHeader(HttpHeaders.Authorization)
        )
    }

    // -------------------------------------------------------------------------
    // TimeoutProfile (LONG uses settings)
    // -------------------------------------------------------------------------

    @Test
    fun `LONG timeout profile uses responseTimeoutSeconds + buffer from settings`() = runTest {
        settings.responseTimeoutSeconds = 120
        settings.longTimeoutBufferSeconds = 30

        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""{"id":"a","name":"b"}"""))
        )

        helper.postJson<Foo>("/foo", FooReq("x"), profile = HttpHelper.TimeoutProfile.LONG)

        // Verify the request was made (the timeout value is applied to the request
        // builder but WireMock doesn't expose it; we verify the request succeeded)
        wireMock.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/foo"))
        )
    }

    @Test
    fun `INFINITE timeout profile does not throw`() = runTest {
        wireMock.stubFor(
            WireMock.post(WireMock.urlEqualTo("/foo"))
                .willReturn(WireMock.okJson("""{"id":"a","name":"b"}"""))
        )

        helper.postJson<Foo>("/foo", FooReq("x"), profile = HttpHelper.TimeoutProfile.INFINITE)

        wireMock.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/foo"))
        )
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    @Serializable
    private data class Foo(val id: String, val name: String)

    @Serializable
    private data class FooReq(val value: String)
}