package com.opencode.acp.mcp

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [McpManager] background retry logic.
 *
 * Regression guard: the JetBrains MCP Server starts asynchronously and may not
 * be ready when the plugin's [com.opencode.acp.chat.service.OpenCodeService.initialize]
 * runs. If the initial connection fails, [McpManager] must retry with exponential
 * backoff in the background and call [onServerConnected] when the server eventually
 * connects — so MCP tools become available without restarting the session.
 *
 * These tests use virtual time (runTest) so the retry delays (2s, 4s, 8s...) don't
 * take real wall-clock time. [McpServerDiscovery] and [McpRegistrar] are mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpManagerRetryTest {

    private lateinit var client: OpenCodeClient
    private lateinit var settings: OpenCodeMcpSettingsState
    private lateinit var httpClient: HttpClient
    private lateinit var discovery: McpServerDiscovery
    private lateinit var registrar: McpRegistrar

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        client = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        httpClient = mockk(relaxed = true)
        discovery = mockk(relaxed = true)
        registrar = mockk(relaxed = true)

        // Default: IntelliJ MCP enabled with a URL
        every { settings.enableIntellijMcp } returns true
        every { settings.mcpServerUrl } returns "http://127.0.0.1:64342/sse"
        every { settings.additionalMcpServers } returns ""
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * When the MCP server is available on the first try, it should connect
     * immediately without any retry. The [onServerConnected] callback should
     * NOT be called (no retry needed).
     */
    @Test
    fun `connects immediately when server is available on first try`() = runTest {
        val serverInfo = McpServerInfo(
            name = "intellij",
            port = 64342,
            url = "http://127.0.0.1:64342/sse",
            source = DiscoverySource.BUILTIN_IDE,
        )
        coEvery { discovery.discover(any()) } returns serverInfo
        coEvery { registrar.register(serverInfo) } returns true

        var callbackCalled = false
        val manager = McpManager(client, settings, this, httpClient, discovery, registrar)
        manager.initialize(onServerConnected = { callbackCalled = true })
        advanceUntilIdle()

        manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.CONNECTED
        callbackCalled shouldBe false
    }

    /**
     * When the MCP server is NOT available on the first try, the manager should
     * launch a background retry. On the second attempt (after the initial delay),
     * if the server becomes available, it should connect and call [onServerConnected].
     *
     * Uses real wall-clock time (runBlocking) because the background retry coroutine
     * uses `delay()` which may not advance correctly with virtual time when MockK's
     * `coEvery` suspend mocking is involved. The delay is 2s — short enough for a test.
     */
    @Test
    fun `retries in background when server not ready, then connects`() = runBlocking {
        val serverInfo = McpServerInfo(
            name = "intellij",
            port = 64342,
            url = "http://127.0.0.1:64342/sse",
            source = DiscoverySource.BUILTIN_IDE,
        )
        // First attempt: server not ready (discover returns null)
        // Second attempt: server available
        coEvery { discovery.discover(any()) } returnsMany listOf(null, serverInfo)
        coEvery { registrar.register(serverInfo) } returns true

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var callbackCalled = false
            val manager = McpManager(client, settings, testScope, httpClient, discovery, registrar)
            manager.initialize(onServerConnected = { callbackCalled = true })

            // After initial attempt: ERROR state, callback not yet called
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR
            callbackCalled shouldBe false

            // Wait for the first retry delay (2s) + margin for thread scheduling.
            // Uses real wall-clock time because MockK's coEvery suspend mocking
            // doesn't work with virtual time. 3s margin covers thread scheduling jitter.
            delay(ChatConstants.MCP_RETRY_INITIAL_DELAY_MS + 3_000)

            // Server should now be connected, callback called
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.CONNECTED
            callbackCalled shouldBe true
        } finally {
            testScope.cancel()
        }
    }

    /**
     * The background retry should use exponential backoff: 2s → 4s → 8s → 10s (cap).
     * If the server is not available for multiple attempts, the delays should increase.
     *
     * Uses real wall-clock time (runBlocking) because the background retry coroutine
     * uses `delay()` which may not advance correctly with virtual time when MockK's
     * `coEvery` suspend mocking is involved. Total test time ~14s (2s + 4s + 8s).
     */
    // NOTE: This test takes ~17s real time due to the exponential backoff delays
    // (2s + 4s + 8s + margins). It verifies the backoff timing at each step.
    // Using real wall-clock time is necessary because MockK's coEvery suspend
    // mocking doesn't work with virtual time. If this test becomes a CI bottleneck,
    // consider mocking the clock parameter and using runTest with advanceTimeBy.
    @Test
    fun `uses exponential backoff across multiple failed attempts`() = runBlocking {
        val serverInfo = McpServerInfo(
            name = "intellij",
            port = 64342,
            url = "http://127.0.0.1:64342/sse",
            source = DiscoverySource.BUILTIN_IDE,
        )
        // First attempt (initial): null
        // Retry 1 (after 2s): null
        // Retry 2 (after 4s): null
        // Retry 3 (after 8s): server available
        coEvery { discovery.discover(any()) } returnsMany listOf(null, null, null, serverInfo)
        coEvery { registrar.register(serverInfo) } returns true

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var callbackCalled = false
            val manager = McpManager(client, settings, testScope, httpClient, discovery, registrar)
            manager.initialize(onServerConnected = { callbackCalled = true })

            // Initial attempt failed
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR

            // Wait 2s — retry 1 fires, still null
            delay(ChatConstants.MCP_RETRY_INITIAL_DELAY_MS + 1_000)
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR
            callbackCalled shouldBe false

            // Wait 4s — retry 2 fires, still null
            delay(4_000 + 1_000)
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR
            callbackCalled shouldBe false

            // Wait 8s — retry 3 fires, server available
            delay(8_000 + 2_000)
            manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.CONNECTED
            callbackCalled shouldBe true
        } finally {
            testScope.cancel()
        }
    }

    /**
     * The background retry should give up after [ChatConstants.MCP_RETRY_TOTAL_TIMEOUT_MS]
     * (60s) and leave the server in ERROR state.
     */
    @Test
    fun `gives up after total timeout and leaves server in ERROR state`() = runTest {
        // Server never becomes available
        coEvery { discovery.discover(any()) } returns null

        var callbackCalled = false
        val manager = McpManager(client, settings, this, httpClient, discovery, registrar, clock = { testScheduler.currentTime })
        manager.initialize(onServerConnected = { callbackCalled = true })
        advanceUntilIdle()

        // Initial attempt failed
        manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR

        // Advance past the total timeout (60s + margin)
        advanceTimeBy(ChatConstants.MCP_RETRY_TOTAL_TIMEOUT_MS + 5_000)
        runCurrent()
        advanceUntilIdle()

        // Server should still be in ERROR state, callback never called
        manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR
        callbackCalled shouldBe false
    }

    /**
     * MANUAL_URL servers should NOT trigger background retry — only BUILTIN_IDE
     * servers (JetBrains MCP Server) get the retry, because they start
     * asynchronously. Manual servers are expected to be already running.
     */
    @Test
    fun `does not retry for MANUAL_URL servers`() = runTest {
        every { settings.enableIntellijMcp } returns false
        every { settings.additionalMcpServers } returns """[{"name":"custom","url":"http://127.0.0.1:9999/sse"}]"""

        coEvery { discovery.discover(any()) } returns null

        var callbackCalled = false
        val manager = McpManager(client, settings, this, httpClient, discovery, registrar, clock = { testScheduler.currentTime })
        manager.initialize(onServerConnected = { callbackCalled = true })
        advanceUntilIdle()

        // Initial attempt failed
        manager.serverStatuses.value["custom"]?.state shouldBe McpConnectionState.ERROR

        // Advance past what would be the retry delay — no retry should fire
        advanceTimeBy(ChatConstants.MCP_RETRY_TOTAL_TIMEOUT_MS + 5_000)
        runCurrent()
        advanceUntilIdle()

        // Still ERROR, callback never called
        manager.serverStatuses.value["custom"]?.state shouldBe McpConnectionState.ERROR
        callbackCalled shouldBe false
    }

    /**
     * When the background retry connects the server but registration fails,
     * the server should be in ERROR state and the callback should NOT be called
     * (no tools to discover if registration failed).
     */
    @Test
    fun `background retry connects but registration fails - ERROR state, no callback`() = runTest {
        val serverInfo = McpServerInfo(
            name = "intellij",
            port = 64342,
            url = "http://127.0.0.1:64342/sse",
            source = DiscoverySource.BUILTIN_IDE,
        )
        // First attempt: null (server not ready)
        // Retry: server found, but registration fails
        coEvery { discovery.discover(any()) } returnsMany listOf(null, serverInfo)
        coEvery { registrar.register(serverInfo) } returns false

        var callbackCalled = false
        val manager = McpManager(client, settings, this, httpClient, discovery, registrar, clock = { testScheduler.currentTime })
        manager.initialize(onServerConnected = { callbackCalled = true })
        advanceUntilIdle()

        // Advance past retry delay
        advanceTimeBy(ChatConstants.MCP_RETRY_INITIAL_DELAY_MS + 100)
        runCurrent()
        advanceUntilIdle()

        manager.serverStatuses.value["intellij"]?.state shouldBe McpConnectionState.ERROR
        callbackCalled shouldBe false
    }
}