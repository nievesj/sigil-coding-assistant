package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.config.AcpDefaults
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.mcp.McpConfigWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.ServerSocket

/**
 * Manages the OpenCode server process lifecycle and connection state.
 * Owns [OpenCodeClient] (which owns its own [HttpClient] internally).
 * Does NOT own message processing — that's the processor's job.
 *
 * Created once per project via [OpenCodeService]. Survives tool window disposal.
 */
class ProcessManager(private val scope: CoroutineScope) {

    private var projectBasePath: String = "."

    private val logger = KotlinLogging.logger {}

    // --- Connection state ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile private var openCodeClient: OpenCodeClient? = null
    @Volatile private var openCodeProcess: Process? = null
    private var processWatcherJob: Job? = null
    @Volatile var initialized: Boolean = false
        private set

    @Volatile var host: String = AcpDefaults.DEFAULT_OPENCODE_HOST
        private set
    @Volatile var port: Int = AcpDefaults.DEFAULT_OPENCODE_PORT
        private set
    @Volatile var authToken: String? = null
        private set

    /** Path to the binary we launched (if we started the process ourselves). */
    @Volatile private var launchedBinaryPath: String? = null

    /** The current OpenCode client. Null until [initialize] succeeds. */
    val client: OpenCodeClient? get() = openCodeClient

    /** Callback invoked when the OpenCode process auto-restarts.
     *  Used by OpenCodeService to reset MCP registration state. */
    var onMcpReset: (() -> Unit)? = null

    // --- Initialization ---

    companion object {
        /** Maximum time to wait for the server to become healthy. */
        private const val INIT_TIMEOUT_MS = 60_000L
        /** Initial health check delay after launch. */
        private const val INIT_DELAY_MS = 500L
        /** Maximum number of ports to try beyond the configured port. */
        private const val MAX_PORT_ATTEMPTS = 10
    }

    /**
     * Connect to an OpenCode server by launching our own binary.
     *
     * Strategy:
     * 1. Always launch our own binary. If the configured port is already
     *    occupied by another process, find the next available port.
     * 2. Wait for the server to become healthy via exponential backoff polling.
     * 3. Kill any orphan processes from previous sessions to prevent zombie pile-ups.
     *
     * @return true if connection succeeded, false on fatal error
     */
    suspend fun initialize(projectBasePath: String = "."): Boolean {
        this.projectBasePath = projectBasePath
        if (initialized) {
            logger.info { "Already initialized — skipping" }
            return true
        }

        val settings = OpenCodeSettingsState.getInstance().state
        host = AcpDefaults.DEFAULT_OPENCODE_HOST
        val configuredPort = settings.port

        // Find an available port — if the configured port is occupied by another
        // process, try the next ports up to MAX_PORT_ATTEMPTS.
        port = findAvailablePort(configuredPort)
        if (port != configuredPort) {
            logger.info { "[ACP] ProcessManager.initialize: configured port $configuredPort is in use — using port $port instead" }
        }

        logger.info { "[ACP] ProcessManager.initialize: launching opencode at $host:$port" }
        _connectionState.value = ConnectionState.CONNECTING

        // Create OpenCodeClient — it owns its HttpClient internally
        val opencodeClient = OpenCodeClient(
            baseUrl = "http://$host:$port",
            authToken = authToken
        )
        openCodeClient = opencodeClient

        // Write MCP config to .opencode/opencode.json BEFORE launching the binary.
        // OpenCode reads opencode.json on startup, so MCP servers must be in the
        // config file before the process starts.
        if (settings.enableIntellijMcp || settings.additionalMcpServers.isNotBlank()) {
            val configWriter = McpConfigWriter(java.nio.file.Path.of(projectBasePath), settings)
            configWriter.write()
        } else {
            // Clear plugin-managed entries when MCP is disabled
            val configWriter = McpConfigWriter(java.nio.file.Path.of(projectBasePath), settings)
            configWriter.clearAllEntries()
        }

        // Launch our own binary on the chosen port
        if (settings.binaryPath.isNotBlank()) {
            launchOpenCodeBinary(settings.binaryPath)
        } else {
            logger.warn { "[ACP] ProcessManager.initialize: no binary path configured" }
            _connectionState.value = ConnectionState.ERROR
            return false
        }

        // Poll health check with backoff until the server is ready or timeout
        val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                if (opencodeClient.healthCheck()) {
                    logger.info { "[ACP] ProcessManager.initialize: health check passed (attempt ${attempt + 1})" }
                    _connectionState.value = ConnectionState.CONNECTED
                    initialized = true
                    return true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Connection refused or similar — server not ready yet
            }
            attempt++

            // Check if the launched process died — if so, fail fast instead of polling to timeout
            val processStatus = checkProcessAlive()
            if (processStatus != null) {
                logger.error { "[ACP] ProcessManager.initialize: $processStatus" }
                _connectionState.value = ConnectionState.ERROR
                return false
            }

            if (attempt == 1) {
                logger.info { "[ACP] ProcessManager.initialize: server not ready yet, polling..." }
            }
            delay(calculateInitBackoff(attempt))
        }

        // Timed out
        logger.warn { "[ACP] ProcessManager.initialize: server did not become healthy within ${INIT_TIMEOUT_MS / 1000}s" }
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

            // No shutdown hook — IntelliJ's Disposer handles cleanup via
            // OpenCodeService.dispose(). Shutdown hooks leak ClassLoader references
            // during dynamic plugin reload (no JVM restart), preventing the old
            // plugin from being unloaded. The process is killed in shutdown().

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
        // Use cancellable polling instead of Future.get() which is uncancellable
        // and leaks IO threads. Polling with delay() is cooperative — respects
        // coroutine cancellation and doesn't block any thread pool.
        processWatcherJob = scope.launch {
            while (isActive && proc.isAlive) {
                delay(500)
            }
            if (!isActive) return@launch

            val exitCode = try { proc.exitValue() } catch (_: Exception) { -1 }
            logger.warn { "[ACP] Process watcher: opencode process exited with code $exitCode" }

            // Only restart if we weren't cancelled (i.e., process died unexpectedly,
            // not because shutdown() was called). Check launchedBinaryPath != null
            // and scope is still active.
            if (isActive && launchedBinaryPath != null) {
                logger.info { "[ACP] Process watcher: auto-restarting opencode..." }
                launchOpenCodeBinary(launchedBinaryPath!!)
                if (initialized) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    initialized = false
                    // Reset MCP registration state — the new server instance
                    // won't have dynamic registrations from the previous instance.
                    onMcpReset?.invoke()
                }
            }
        }
    }

    /**
     * Find an available port starting from [startPort].
     * Tests each port by trying to bind a ServerSocket — if binding succeeds,
     * the port is free and we close the socket immediately. If binding fails,
     * the port is occupied and we try the next one.
     *
     * Returns [startPort] if available, or the next available port up to
     * [startPort] + [MAX_PORT_ATTEMPTS] - 1. If none are available, returns
     * [startPort] (will fail later at health-check time with a clear error).
     */
    private fun findAvailablePort(startPort: Int): Int {
        for (candidate in startPort until (startPort + MAX_PORT_ATTEMPTS)) {
            try {
                ServerSocket(candidate).use { /* port is available */ }
                return candidate
            } catch (_: java.net.BindException) {
                // Port is occupied — try next
                logger.info { "[ACP] findAvailablePort: port $candidate is in use, trying next" }
            }
        }
        // All attempts failed — return configured port and let the health-check
        // timeout produce a meaningful error.
        logger.warn { "[ACP] findAvailablePort: no available port found in range $startPort..${startPort + MAX_PORT_ATTEMPTS - 1}" }
        return startPort
    }

    /** Check if our launched process is still alive. Returns error text if dead, null if alive. */
    private fun checkProcessAlive(): String? {
        val proc = openCodeProcess ?: return "No process was launched"
        if (proc.isAlive) return null
        val exitVal = proc.exitValue()
        return "Process exited with code $exitVal"
    }

    private fun killProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        try {
            val pid = process.pid()
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                // Use waitFor with timeout — taskkill can hang if the process tree is
                // large or has stuck child processes. Without a timeout, this blocks
                // the EDT indefinitely during IDE restart/plugin update.
                val taskkill = Runtime.getRuntime().exec(arrayOf("taskkill", "/F", "/T", "/PID", pid.toString()))
                if (!taskkill.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn { "[ACP] taskkill timed out after 5s for PID $pid — falling back to destroyForcibly" }
                    taskkill.destroyForcibly()
                }
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
        logger.info { "[ACP] ProcessManager.disconnect() called" }
        processWatcherJob?.cancel()
        processWatcherJob = null
        // Close HTTP client on a daemon thread — httpClient.close() can block
        // on connection pool drain (especially with lingering SSE connections).
        // Calling it on EDT would freeze the IDE during plugin update/restart.
        val client = openCodeClient
        openCodeClient = null
        initialized = false
        _connectionState.value = ConnectionState.DISCONNECTED
        if (client != null) {
            Thread({
                try { client.close() } catch (_: Exception) { }
            }, "opencode-client-close").apply { isDaemon = true; start() }
        }
        // Do NOT kill the process — it may still be healthy. initialize() will
        // find the same port occupied by our own process and launch on the same port.
    }

    /**
     * Reset state for retry. Kills the running process and clears the
     * initialized flag so the next initialize() call actually re-initializes.
     * Used by ChatViewModel.retryConnection() to bypass the initialized guard.
     */
    fun resetForRetry() {
        logger.info { "[ACP] ProcessManager.resetForRetry() called" }
        val processToKill = openCodeProcess
        openCodeProcess = null
        if (processToKill != null) {
            Thread({
                killProcess(processToKill)
            }, "opencode-kill-retry").apply { isDaemon = true; start() }
        }
        initialized = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Full shutdown: disconnect AND kill the opencode process.
     * Use this only for IDE shutdown (dispose) where we own the process
     * and should clean it up.
     *
     * IMPORTANT: killProcess() runs on a daemon thread to avoid blocking EDT.
     * The process is captured and nulled immediately; actual termination is async.
     */
    fun shutdown() {
        logger.info { "[ACP] ProcessManager.shutdown() called" }
        disconnect()
        // Kill process on a daemon thread — taskkill.waitFor() can block for
        // seconds on Windows if the process tree is large or stuck.
        val processToKill = openCodeProcess
        openCodeProcess = null
        if (processToKill != null) {
            Thread({
                killProcess(processToKill)
            }, "opencode-kill").apply { isDaemon = true; start() }
        }
    }

    /** @deprecated Use [disconnect] for retry, or [shutdown] for IDE close. */
    fun close() {
        // Default to disconnect — callers that truly want to kill the process
        // should use shutdown() explicitly.
        disconnect()
    }
}
