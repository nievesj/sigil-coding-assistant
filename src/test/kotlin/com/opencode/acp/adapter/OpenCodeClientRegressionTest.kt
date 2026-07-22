package com.opencode.acp.adapter

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for [OpenCodeClient] review fixes.
 *
 * Covers:
 *  - unshareSession HTTP status check (cmt_e5f6a7b8c9d0): server errors must
 *    surface as IllegalStateException, not SerializationException.
 *  - mcpHttpClient thread-safe lazy init (cmt_f6a7b8c9d0e1): concurrent access
 *    must construct exactly one HttpClient.
 */
class OpenCodeClientRegressionTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var client: OpenCodeClient

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wireMock.start()
        WireMock.configureFor("localhost", wireMock.port())
        client = OpenCodeClient(baseUrl = wireMock.baseUrl(), authToken = null)
    }

    @AfterEach
    fun tearDown() {
        client.shutdown()
        wireMock.stop()
    }

    // ── unshareSession status check (cmt_e5f6a7b8c9d0) ───────────────────────

    @Test
    fun `unshareSession throws IllegalStateException on 404 not SerializationException`() = runTest {
        // Regression: before the fix, unshareSession skipped the HTTP status
        // check and fed the error body directly to json.decodeFromString,
        // producing a misleading SerializationException.
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/session/ses_abc/share"))
                .willReturn(WireMock.status(404).withBody("session not found"))
        )

        val ex = assertThrows<IllegalStateException> {
            client.unshareSession("ses_abc")
        }
        ex.message shouldContain "DELETE /session/ses_abc/share failed with 404"
    }

    @Test
    fun `unshareSession throws IllegalStateException on 500`() = runTest {
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/session/ses_abc/share"))
                .willReturn(WireMock.status(500).withBody("internal server error"))
        )

        val ex = assertThrows<IllegalStateException> {
            client.unshareSession("ses_abc")
        }
        ex.message shouldContain "DELETE /session/ses_abc/share failed with 500"
    }

    @Test
    fun `unshareSession succeeds on 200 with valid JSON`() = runTest {
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/session/ses_abc/share"))
                .willReturn(WireMock.okJson("""{"id":"ses_abc","slug":"test"}"""))
        )

        val result = client.unshareSession("ses_abc")
        result.id shouldBe "ses_abc"
    }

    @Test
    fun `unshareSession on HTML error body throws IllegalStateException not SerializationException`() = runTest {
        // Before the fix, an HTML error page would be fed to the JSON deserializer
        // and throw SerializationException. Now it should throw IllegalStateException.
        wireMock.stubFor(
            WireMock.delete(WireMock.urlEqualTo("/session/ses_abc/share"))
                .willReturn(WireMock.status(502).withBody("<html><body>Bad Gateway</body></html>"))
        )

        val ex = assertThrows<IllegalStateException> {
            client.unshareSession("ses_abc")
        }
        // Must be IllegalStateException (the status check), NOT SerializationException
        ex::class shouldBe IllegalStateException::class
        ex.message shouldContain "502"
    }

    // ── mcpHttpClient thread-safe lazy init (cmt_f6a7b8c9d0e1) ──────────────

    @Test
    fun `mcpHttpClient returns the same instance on concurrent access`() = runBlocking {
        // Regression: before the DCL fix, two threads could both observe null
        // and both construct an HttpClient. The losing client was never closed
        // by shutdown(), leaking sockets.
        //
        // We can't directly count HttpClient constructions, but we can verify
        // that all concurrent accesses return the SAME instance (identity).
        val threads = 10
        val callsPerThread = 20
        val instances = ConcurrentHashMap.newKeySet<HttpClient>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)

        repeat(threads) {
            Thread({
                startLatch.await(5, TimeUnit.SECONDS)
                repeat(callsPerThread) {
                    instances.add(client.mcpHttpClient)
                }
                doneLatch.countDown()
            }, "mcp-client-test-$it").start()
        }

        startLatch.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)

        // All 200 calls across 10 threads must return the exact same instance.
        instances.size shouldBe 1
    }

    @Test
    fun `mcpHttpClient returns same instance on sequential access`() {
        val a = client.mcpHttpClient
        val b = client.mcpHttpClient
        (a === b) shouldBe true
    }

    @Test
    fun `shutdown closes the mcpHttpClient`() = runBlocking {
        // Regression guard: if shutdown() is refactored to skip closing _mcpHttpClient,
        // this test catches it. After shutdown, the mcpHttpClient getter returns null
        // (or a fresh instance), so the previously-obtained reference must be closed.
        val mcpClient = client.mcpHttpClient
        client.shutdown()
        // A closed HttpClient throws when you try to make a request on it.
        // We verify by attempting a request and expecting an exception.
        var threw = false
        try {
            mcpClient.get("http://localhost:${wireMock.port()}/test")
        } catch (e: Exception) {
            threw = true
        }
       threw shouldBe true
    }

    // ── Path traversal validation on permission/question endpoints ────────────

    @Test
    fun `respondPermission rejects invalid sessionId with IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            client.respondPermission("perm_123", "../admin", "allow_once")
        }
    }

    @Test
    fun `respondPermission rejects invalid permissionId with IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            client.respondPermission("../../etc", "ses_abc", "allow_once")
        }
    }

    @Test
    fun `respondQuestion rejects invalid requestId with IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            client.respondQuestion("../inject", listOf(listOf("yes")))
        }
    }

    @Test
    fun `rejectQuestion rejects invalid requestId with IllegalArgumentException`() = runTest {
        assertThrows<IllegalArgumentException> {
            client.rejectQuestion("../inject")
        }
    }
}