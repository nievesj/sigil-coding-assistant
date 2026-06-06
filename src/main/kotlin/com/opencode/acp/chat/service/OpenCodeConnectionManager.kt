package com.opencode.acp.chat.service

import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.config.AcpDefaults
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.model.ConnectionState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

/**
 * Manages the HTTP client, SSE connection, and connection lifecycle.
 * Owns [OpenCodeClient] and exposes the SSE event flow.
 * Does NOT own message processing — that's the processor's job.
 *
 * Created once per project via [OpenCodeService]. Survives tool window disposal.
 */
class OpenCodeConnectionManager(private val scope: CoroutineScope) {

    private val logger = KotlinLogging.logger {}

    // --- Connection state ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    private var openCodeProcess: Process? = null
    private var shutdownHook: Thread? = null
    private var sseJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    var initialized: Boolean = false
        private set

    var host: String = AcpDefaults.DEFAULT_OPENCODE_HOST
        private set
    var port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT
        private set
    var authToken: String? = null
        private set

    /** The current OpenCode client. Null until [initialize] succeeds. */
    val client: OpenCodeClient? get() = openCodeClient

    // --- Initialization ---

    companion object {
        /** Maximum time to wait for the server to become healthy. */
        private const val INIT_TIMEOUT_MS = 60_000L
        /** Initial health check delay after launch. */
        private const val INIT_DELAY_MS = 500L
    }

    /**
     * Launch the OpenCode binary (if not already running), then poll the health
     * endpoint until the server responds. No manual retry needed — this loops
     * with backoff until the server is ready or [INIT_TIMEOUT_MS] elapses.
     *
     * Does NOT load sessions or subscribe to SSE — that's done by the service
     * after the connection is established.
     *
     * @return true if connection succeeded, false on fatal error (binary not found, timeout)
     */
    suspend fun initialize(): Boolean {
        if (initialized) {
            logger.info { "Already initialized — skipping" }
            return true
        }

        val settings = OpenCodeSettingsState.getInstance().state
        host = AcpDefaults.DEFAULT_OPENCODE_HOST
        port = AcpDefaults.DEFAULT_OPENCODE_PORT

        logger.info { "[ACP] ConnectionManager.initialize: connecting to OpenCode at $host:$port" }
        _connectionState.value = ConnectionState.CONNECTING

        // Create HTTP client and OpenCodeClient first
        val client = HttpClient(Java) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                })
            }
            install(SSE)
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 300_000
                connectTimeoutMillis = 10_000
                val sseTimeoutSeconds = OpenCodeSettingsState.getInstance().sseSocketTimeoutSeconds
                socketTimeoutMillis = sseTimeoutSeconds * 1000L
            }
        }
        httpClient = client

        val opencodeClient = OpenCodeClient(
            baseUrl = "http://$host:$port",
            httpClient = client,
            authToken = authToken
        )
        openCodeClient = opencodeClient

        // Launch the binary if configured and not already running
        if (settings.binaryPath.isNotBlank()) {
            if (!isServerReachable()) {
                launchOpenCodeBinary(settings.binaryPath)
            } else {
                logger.info { "[ACP] ConnectionManager.initialize: server already running at $host:$port" }
            }
        } else {
            logger.warn { "[ACP] ConnectionManager.initialize: no binary path configured" }
            _connectionState.value = ConnectionState.ERROR
            return false
        }

        // Poll health check with backoff until the server is ready or timeout
        val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                if (opencodeClient.healthCheck()) {
                    logger.info { "[ACP] ConnectionManager.initialize: health check passed (attempt ${attempt + 1})" }
                    _connectionState.value = ConnectionState.CONNECTED
                    initialized = true
                    return true
                }
            } catch (_: Exception) {
                // Connection refused or similar — server not ready yet
            }
            attempt++
            if (attempt == 1) {
                logger.info { "[ACP] ConnectionManager.initialize: server not ready yet, polling..." }
            }
            delay(calculateInitBackoff(attempt))
        }

        // Timed out
        logger.warn { "[ACP] ConnectionManager.initialize: server did not become healthy within ${INIT_TIMEOUT_MS / 1000}s" }
        _connectionState.value = ConnectionState.ERROR
        return false
    }

    /** Exponential backoff for init polling: 500ms → 1s → 2s → 4s → capped at 8s. */
    private fun calculateInitBackoff(attempt: Int): Long {
        val exponential = INIT_DELAY_MS * (1L shl attempt.coerceAtMost(4))
        return exponential.coerceAtMost(8_000L)
    }

    // --- SSE subscription ---

    /**
     * Start SSE subscription for the given session.
     * Returns a [Flow] of parsed [SseEvent] objects.
     * Events are filtered by sessionId before being emitted.
     *
     * The caller (service) connects this flow to the processor.
     */
    fun subscribeSession(sessionId: String): Flow<SseEvent> {
        val client = openCodeClient ?: return emptyFlow()
        val capturedId = sessionId
        logger.info { "[ACP] startSseSubscription: targetSessionId=$capturedId" }
        return client.subscribeGlobalEvents()
            .filter { event ->
                val match = event.sessionId == capturedId || event is SseEvent.SessionCreated
                if (!match) {
                    logger.info { "[ACP] SSE FILTERED OUT: event.sid=${event.sessionId} != captured=$capturedId (${event::class.simpleName})" }
                }
                match
            }
    }

    /**
     * Launch an SSE subscription job for the given session.
     * Events are delivered to [onEvent] callback.
     * Handles reconnection on stream end.
     */
    fun startSseSubscription(sessionId: String, onEvent: (SseEvent) -> Unit): Job {
        val capturedId = sessionId
        return scope.launch {
            try {
                subscribeSession(capturedId).collect { event ->
                    onEvent(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // SSE error — stream ended
            }
            // Stream ended without cancellation — trigger reconnection
            if (isActive && initialized && this@OpenCodeConnectionManager.isActiveSession(capturedId)) {
                logger.info { "SSE stream ended for session $capturedId — triggering reconnection" }
                triggerReconnect(capturedId, onEvent)
            }
        }
    }

    fun cancelSseSubscription() {
        sseJob?.cancel()
        sseJob = null
    }

    /** Whether the given session is still the active one. */
    private fun isActiveSession(sessionId: String): Boolean {
        // This is checked by the service — connection manager doesn't track sessionId
        return initialized
    }

    // --- Reconnection ---

    private fun triggerReconnect(sessionId: String, onEvent: (SseEvent) -> Unit) {
        reconnectJob?.cancel()
        reconnectAttempt = 0

        reconnectJob = scope.launch {
            _connectionState.value = ConnectionState.RECONNECTING
            logger.info { "Starting reconnection..." }

            while (isActive && initialized) {
                val delayMs = calculateBackoff(reconnectAttempt)
                if (reconnectAttempt > 0) {
                    logger.info { "Reconnect attempt ${reconnectAttempt + 1}, waiting ${delayMs}ms" }
                    delay(delayMs)
                }

                val client = openCodeClient
                if (client == null) {
                    _connectionState.value = ConnectionState.ERROR
                    initialized = false
                    return@launch
                }

                try {
                    if (!client.healthCheck()) {
                        reconnectAttempt++
                        continue
                    }
                } catch (_: Exception) {
                    reconnectAttempt++
                    continue
                }

                // Server healthy — re-subscribe
                sseJob = startSseSubscription(sessionId, onEvent)
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
                logger.info { "Reconnected successfully" }
                return@launch
            }

            if (initialized && _connectionState.value == ConnectionState.RECONNECTING) {
                _connectionState.value = ConnectionState.ERROR
                initialized = false
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val base = com.opencode.acp.chat.model.ChatConstants.RECONNECT_DELAY_MS
        val max = com.opencode.acp.chat.model.ChatConstants.RECONNECT_MAX_DELAY_MS
        val exponential = (base * (1L shl attempt.coerceAtMost(10))).coerceAtMost(max)
        val jitter = (exponential * kotlin.random.Random.nextDouble(0.0, 0.2)).toLong()
        return (exponential + jitter).coerceAtMost(max)
    }

    // --- Binary management ---

    private fun launchOpenCodeBinary(binaryPath: String) {
        if (isServerReachable()) {
            logger.info { "OpenCode server already running at $host:$port — skipping launch" }
            return
        }
        try {
            logger.info { "Launching: $binaryPath serve --host $host --port $port" }
            val pb = ProcessBuilder(binaryPath, "serve", "--host", host, "--port", port.toString())
                .redirectErrorStream(true)
                .apply {
                    environment().putIfAbsent("OPENCODE_CLIENT", "cli")
                }
            openCodeProcess = pb.start()
            logger.info { "OpenCode process started (PID: ${openCodeProcess?.pid()})" }

            Thread({
                openCodeProcess?.inputStream?.bufferedReader()?.use { reader ->
                    reader.lines().forEach { line ->
                        logger.debug { "[opencode] $line" }
                    }
                }
            }, "opencode-stdout-drain").apply {
                isDaemon = true
                start()
            }

            if (shutdownHook == null) {
                shutdownHook = Thread({
                    killProcess(openCodeProcess)
                }, "opencode-shutdown").apply { isDaemon = true }
                Runtime.getRuntime().addShutdownHook(shutdownHook)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to launch OpenCode binary at $binaryPath" }
        }
    }

    private fun isServerReachable(): Boolean {
        return try {
            val url = java.net.URI("http://$host:$port/global/health").toURL()
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            val reachable = conn.responseCode == 200
            conn.disconnect()
            reachable
        } catch (_: Exception) {
            false
        }
    }

    private fun killProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        try {
            val pid = process.pid()
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                Runtime.getRuntime().exec(arrayOf("taskkill", "/F", "/T", "/PID", pid.toString())).waitFor()
            } else {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            process.destroyForcibly()
        }
    }

    // --- Cleanup ---

    fun close() {
        logger.info { "[ACP] ConnectionManager.close() called" }
        reconnectJob?.cancel()
        reconnectJob = null
        sseJob?.cancel()
        sseJob = null
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        killProcess(openCodeProcess)
        openCodeProcess = null
        initialized = false
    }

    fun resetReconnectState() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
    }
}
