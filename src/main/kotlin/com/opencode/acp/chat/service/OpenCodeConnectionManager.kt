package com.opencode.acp.chat.service

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

    private var projectBasePath: String = "."

    private val logger = KotlinLogging.logger {}

    // --- Connection state ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var openCodeClient: OpenCodeClient? = null
    private var httpClient: HttpClient? = null
    private var openCodeProcess: Process? = null
    private var processWatcherJob: Job? = null
    private var shutdownHook: Thread? = null
    var initialized: Boolean = false
        private set

    var host: String = AcpDefaults.DEFAULT_OPENCODE_HOST
        private set
    var port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT
        private set
    var authToken: String? = null
        private set

    /** Path to the binary we launched (if we started the process ourselves). */
    private var launchedBinaryPath: String? = null

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
    suspend fun initialize(projectBasePath: String = "."): Boolean {
        this.projectBasePath = projectBasePath
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

        // Always launch our own binary — don't trust isServerReachable()
        if (settings.binaryPath.isNotBlank()) {
            launchOpenCodeBinary(settings.binaryPath)
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

            // Check if the launched process died — if so, fail fast instead of polling to timeout
            val processStatus = checkProcessAlive()
            if (processStatus != null) {
                logger.error { "[ACP] ConnectionManager.initialize: $processStatus" }
                _connectionState.value = ConnectionState.ERROR
                return false
            }

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

    // --- Binary management ---

    private fun launchOpenCodeBinary(binaryPath: String) {
        // Kill any previously launched process before starting a new one
        if (openCodeProcess?.isAlive == true) {
            killProcess(openCodeProcess)
            openCodeProcess = null
        }

        try {
            logger.info { "Launching: $binaryPath serve --hostname $host --port $port (cwd=$projectBasePath)" }
            val pb = ProcessBuilder(binaryPath, "serve", "--hostname", host, "--port", port.toString())
                .directory(java.io.File(projectBasePath))
                .redirectErrorStream(true)
                .apply {
                    environment().putIfAbsent("OPENCODE_CLIENT", "cli")
                }
            openCodeProcess = pb.start()
            launchedBinaryPath = binaryPath
            logger.info { "OpenCode process started (PID: ${openCodeProcess?.pid()})" }

            // Drain stdout/stderr to a buffer so we can report it if the process dies
            val outputBuffer = StringBuffer()
            Thread({
                try {
                    openCodeProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lines().forEach { line ->
                            logger.debug { "[opencode] $line" }
                            synchronized(outputBuffer) {
                                if (outputBuffer.length < 4096) {
                                    outputBuffer.append(line).append('\n')
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
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

            // Start process watcher to detect unexpected death and auto-restart
            startProcessWatcher()
        } catch (e: Exception) {
            logger.error(e) { "Failed to launch OpenCode binary at $binaryPath" }
        }
    }

    /**
     * Watch the launched process. If it dies while [initialized] is true,
     * try to restart it immediately and set state to RECONNECTING.
     * This protects against external killers (e.g. JetBrains ACP ProcessHandler).
     */
    private fun startProcessWatcher() {
        processWatcherJob?.cancel()
        val proc = openCodeProcess ?: return
        processWatcherJob = scope.launch {
            // Wait for the process to exit (non-blocking via onExit)
            try {
                proc.onExit().get()
            } catch (_: InterruptedException) {
                return@launch
            } catch (_: java.util.concurrent.ExecutionException) {
                // Process terminated abnormally
            }

            val exitCode = proc.exitValue()
            logger.warn { "[ACP] Process watcher: opencode process exited with code $exitCode" }

            // If we're initialized (connected) or trying to connect, the process died unexpectedly.
            // Restart it and set state to recovering.
            if (launchedBinaryPath != null) {
                logger.info { "[ACP] Process watcher: auto-restarting opencode..." }
                launchOpenCodeBinary(launchedBinaryPath!!)
                // Signal to the rest of the system that connection needs recovery
                if (initialized) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    initialized = false
                }
            }
        }
    }

    /** Check if our launched process is still alive. Returns error text if dead, null if alive. */
    private fun checkProcessAlive(): String? {
        val proc = openCodeProcess ?: return "No process was launched"
        if (proc.isAlive) return null
        val exitVal = proc.exitValue()
        return "Process exited with code $exitVal"
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

    /**
     * Disconnect from the server but keep the process alive.
     * Use this for retry/reconnect scenarios where we want to re-establish
     * the HTTP/SSE connection without restarting the opencode binary.
     */
    fun disconnect() {
        logger.info { "[ACP] ConnectionManager.disconnect() called" }
        processWatcherJob?.cancel()
        processWatcherJob = null
        openCodeClient?.close()
        openCodeClient = null
        httpClient?.close()
        httpClient = null
        initialized = false
        _connectionState.value = ConnectionState.DISCONNECTED
        // Do NOT kill the process — it may still be healthy and we just need
        // to reconnect our HTTP client. The process will be reused on next
        // initialize() if it's still reachable.
    }

    /**
     * Full shutdown: disconnect AND kill the opencode process.
     * Use this only for IDE shutdown (dispose) where we own the process
     * and should clean it up.
     */
    fun shutdown() {
        logger.info { "[ACP] ConnectionManager.shutdown() called" }
        disconnect()
        killProcess(openCodeProcess)
        openCodeProcess = null
    }

    /** @deprecated Use [disconnect] for retry, or [shutdown] for IDE close. */
    fun close() {
        // Default to disconnect — callers that truly want to kill the process
        // should use shutdown() explicitly.
        disconnect()
    }
}
