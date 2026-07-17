package com.opencode.acp.mcp

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [ServerVerifier] and [HttpServerVerifier] (TDD §4.2.4).
 *
 * [HttpServerVerifier] makes a real HTTP connection, so these tests exercise
 * the failure paths (connection refused, malformed URL, empty URL) which do
 * not require a running server. A [FakeServerVerifier] demonstrates the
 * testability the interface provides for consumers.
 */
class ServerVerifierTest {

    // ── HttpServerVerifier: connection refused ────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `verify returns false when connection is refused`() = runTest {
        // Port 1 is a privileged port that nothing listens on — connection refused.
        val verifier = HttpServerVerifier()
        verifier.verify("http://127.0.0.1:1/no-such-port") shouldBe false
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `verify does not hang when connection is refused`() = runTest {
        val verifier = HttpServerVerifier()
        // If this hangs, the @Timeout annotation will fail the test.
        verifier.verify("http://127.0.0.1:1/no-such-port") shouldBe false
    }

    // ── HttpServerVerifier: malformed URL ──────────────────────────────────

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `verify returns false for malformed URL`() = runTest {
        val verifier = HttpServerVerifier()
        verifier.verify("invalid-url") shouldBe false
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `verify returns false for empty URL`() = runTest {
        val verifier = HttpServerVerifier()
        verifier.verify("") shouldBe false
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `verify returns false for URL with unsupported scheme`() = runTest {
        val verifier = HttpServerVerifier()
        verifier.verify("ftp://127.0.0.1:1/sse") shouldBe false
    }

    // ── ServerVerifier interface contract ─────────────────────────────────

    @Test
    fun `FakeServerVerifier returns configured value for true`() = runTest {
        val verifier = FakeServerVerifier(result = true)
        verifier.verify("http://anything") shouldBe true
        verifier.verify("http://anything") shouldBe true
        verifier.callCount shouldBe 2
    }

    @Test
    fun `FakeServerVerifier returns configured value for false`() = runTest {
        val verifier = FakeServerVerifier(result = false)
        verifier.verify("http://anything") shouldBe false
        verifier.callCount shouldBe 1
    }

    @Test
    fun `FakeServerVerifier records the last verified URL`() = runTest {
        val verifier = FakeServerVerifier(result = true)
        verifier.verify("http://127.0.0.1:64342/sse")
        verifier.lastUrl shouldBe "http://127.0.0.1:64342/sse"
    }
}

/**
 * Fake [ServerVerifier] for testing consumers without hitting real network
 * endpoints. Returns a configurable result and records call details.
 */
class FakeServerVerifier(private val result: Boolean) : ServerVerifier {
    var callCount: Int = 0
        private set
    var lastUrl: String? = null
        private set

    override suspend fun verify(url: String): Boolean {
        callCount++
        lastUrl = url
        return result
    }
}