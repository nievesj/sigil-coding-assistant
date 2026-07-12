package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.config.AcpDefaults
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.processor.PrunerConfigWriter
import com.opencode.acp.chat.processor.PrunerResourceExtractor
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

    private val _connectionErrorReason = MutableStateFlow<ConnectionErrorReason?>(null)
    val connectionErrorReason: StateFlow<ConnectionErrorReason?> = _connectionErrorReason.asStateFlow()

    /** Public setter for transitioning to an error state from outside ProcessManager.
     *  Used by the SSE circuit breaker in OpenCodeService to signal that the server
     *  is permanently unreachable after exhausting reconnection attempts. */
    fun setConnectionError(reason: ConnectionErrorReason) {
        _connectionErrorReason.value = reason
        _connectionState.value = ConnectionState.ERROR
    }

    @Volatile private var openCodeClient: OpenCodeClient? = null
    @Volatile private var openCodeProcess: Process? = null
    private var processWatcherJob: Job? = null
    private val outputBuffer = StringBuffer()
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

        // Extract sigil-pruner.ts and write pruner config BEFORE launching the binary.
        // The OpenCode server loads plugins from .opencode/plugins/ on startup and
        // the TS plugin reads .opencode/sigil-pruner.json on load.
        if (settings.enableContextPruner) {
            val extracted = PrunerResourceExtractor.extractPlugin(projectBasePath)
            if (!extracted) {
                logger.warn { "[ACP] ProcessManager.initialize: failed to extract sigil-pruner.ts — pruning unavailable" }
            }
            PrunerConfigWriter.writeConfig(projectBasePath, settings)
        } else {
            // Clean up pruner files when disabled
            PrunerResourceExtractor.removePlugin(projectBasePath)
            PrunerConfigWriter.clearConfig(projectBasePath)
        }

        // Launch our own binary on the chosen port
        if (settings.binaryPath.isNotBlank()) {
            val launched = launchOpenCodeBinary(settings.binaryPath)
            if (!launched) {
                // Only set generic error if launchOpenCodeBinary didn't set a specific reason
                if (_connectionErrorReason.value == null) _connectionErrorReason.value = ConnectionErrorReason.BinaryLaunchFailed(
                    detail = "Could not start ${settings.binaryPath}. Check the path and permissions."
                )
                _connectionState.value = ConnectionState.ERROR
                return false
            }
        } else {
            logger.warn { "[ACP] ProcessManager.initialize: no binary path configured" }
            _connectionErrorReason.value = ConnectionErrorReason.NoBinaryConfigured
            _connectionState.value = ConnectionState.ERROR
            return false
        }

        // Poll health check with backoff until the server is ready or timeout.
        // Extracted into a local function so the TOCTOU retry (below) can re-poll
        // without duplicating the loop body.
        suspend fun pollHealthCheck(client: OpenCodeClient, timeoutMs: Long, label: String): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (client.healthCheck()) {
                        logger.info { "[ACP] ProcessManager.initialize: $label health check passed (attempt ${attempt + 1})" }
                        return true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.net.ConnectException) {
                    // Connection refused — server not ready yet
                } catch (e: java.net.SocketTimeoutException) {
                    // Health check request timed out — server not ready yet
                } catch (e: java.io.IOException) {
                    // Transient I/O error — server not ready yet
                }
                attempt++

                // Check if the launched process died — if so, fail fast instead of polling to timeout
                val processStatus = checkProcessAlive()
                if (processStatus != null) {
                    val exitCode = try { openCodeProcess?.exitValue() ?: -1 } catch (_: Exception) { -1 }
                    val outputTail = synchronized(outputBuffer) { outputBuffer.toString().takeIf { it.isNotBlank() } }
                    logger.error { "[ACP] ProcessManager.initialize: $label $processStatus" }
                    _connectionErrorReason.value = ConnectionErrorReason.ProcessExited(
                        exitCode = exitCode,
                        outputTail = outputTail
                    )
                    _connectionState.value = ConnectionState.ERROR
                    return false
                }

                if (attempt == 1) {
                    logger.info { "[ACP] ProcessManager.initialize: $label server not ready yet, polling..." }
                }
                delay(calculateInitBackoff(attempt))
            }
            return false
        }

        // First attempt: poll with the full timeout on the initially chosen port.
        if (pollHealthCheck(opencodeClient, INIT_TIMEOUT_MS, "attempt 1")) {
            _connectionErrorReason.value = null
            _connectionState.value = ConnectionState.CONNECTED
            initialized = true
            return true
        }

        // TOCTOU retry: the port we chose may have been taken between findAvailablePort's
        // test bind and the OpenCode server's actual bind. If the binary was launched and
        // the health check timed out (process still alive, not ProcessExited), try ONE
        // more port before giving up. This handles the race described in findAvailablePort.
        if (launchedBinaryPath != null && checkProcessAlive() == null) {
            val retryPort = findAvailablePort(port + 1)
            if (retryPort != port) {
                logger.warn { "[ACP] ProcessManager.initialize: first launch health check timed out on port $port — retrying on port $retryPort (TOCTOU retry)" }
                // Kill the current process and relaunch on the new port
                killProcess(openCodeProcess)
                openCodeProcess = null
                port = retryPort
                // Close the old client and create a new one with the new base URL.
                // OpenCodeClient.baseUrl is immutable, so we must recreate the client.
                try { opencodeClient.close() } catch (_: Exception) { }
                val retryClient = OpenCodeClient(
                    baseUrl = "http://$host:$port",
                    authToken = authToken
                )
                openCodeClient = retryClient
                val relaunched = launchOpenCodeBinary(settings.binaryPath)
                if (relaunched && pollHealthCheck(retryClient, 15_000L, "attempt 2 (TOCTOU retry)")) {
                    _connectionErrorReason.value = null
                    _connectionState.value = ConnectionState.CONNECTED
                    initialized = true
                    return true
                }
                logger.warn { "[ACP] ProcessManager.initialize: TOCTOU retry on port $retryPort also failed" }
            } else {
                logger.warn { "[ACP] ProcessManager.initialize: no alternate port available for TOCTOU retry (tried ${port + 1}..${port + MAX_PORT_ATTEMPTS})" }
            }
        }

        // Timed out
        logger.warn { "[ACP] ProcessManager.initialize: server did not become healthy within ${INIT_TIMEOUT_MS / 1000}s" }
        _connectionErrorReason.value = ConnectionErrorReason.HealthCheckTimeout
        _connectionState.value = ConnectionState.ERROR
        return false
    }

    /** Exponential backoff for init polling: 500ms → 1s → 2s → 4s → capped at 8s. */
    private fun calculateInitBackoff(attempt: Int): Long {
        val exponential = INIT_DELAY_MS * (1L shl attempt.coerceAtMost(4))
        return exponential.coerceAtMost(8_000L)
    }

    // --- Binary management ---

    // TODO: The OpenCode binary version is pinned by the configured binaryPath (see OpenCodeSettingsState.binaryPath).
    //   If you bump the binary version, you MUST re-verify the V1/V2 SSE wire format in OpenCodeClient.subscribeGlobalEvents.
    //   A version bump without re-verification will silently drop ALL SSE events.
    //   See AGENTS.md "SSE V2 SyncEvent Wire Format — Critical Parsing Fix".

    private fun launchOpenCodeBinary(binaryPath: String): Boolean {
        // Kill any previously launched process before starting a new one
        if (openCodeProcess?.isAlive == true) {
            killProcess(openCodeProcess)
            openCodeProcess = null
        }

        return try {
            // Validate the configured binary path before executing it. A misconfigured
            // or compromised settings value could otherwise execute arbitrary code
            // under the IDE's privileges.
            val binaryFile = java.io.File(binaryPath)
            if (!binaryFile.isFile) {
                logger.error { "[ACP] launchOpenCodeBinary: binary path does not exist or is not a regular file: $binaryPath" }
                return false
            }
            if (!binaryFile.canExecute()) {
                logger.error { "[ACP] launchOpenCodeBinary: binary path is not executable: $binaryPath" }
                return false
            }

            logger.info { "Launching: ${binaryFile.canonicalPath} serve --hostname $host --port $port (cwd=$projectBasePath)" }
            val pb = ProcessBuilder(binaryPath, "serve", "--hostname", host, "--port", port.toString())
                .directory(java.io.File(projectBasePath))
                .redirectErrorStream(true)
                .apply {
                    environment().putIfAbsent("OPENCODE_CLIENT", "cli")
                }
            // Capture the specific Process instance so the stdout-drain thread
            // reads from this exact process rather than the mutable @Volatile
            // openCodeProcess var (which may be reassigned by a later launch).
            val proc = pb.start()
            openCodeProcess = proc
            launchedBinaryPath = binaryPath
            logger.info { "OpenCode process started (PID: ${proc.pid()})" }

            // Drain stdout/stderr to a buffer so we can report it if the process dies
            outputBuffer.setLength(0)
            Thread({
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (proc.isAlive) {
                            val line = reader.readLine() ?: break
                            logger.debug { "[opencode] $line" }
                            synchronized(outputBuffer) {
                                if (outputBuffer.length < 4096) {
                                    outputBuffer.append(line).append('\n')
                                }
                            }
                        }
                        // Drain any remaining lines after process exits
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.debug { "[opencode] $line" }
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
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to launch OpenCode binary at $binaryPath" }
            outputBuffer.append(e.message)
            false
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
                val restarted = launchOpenCodeBinary(launchedBinaryPath!!)
                if (initialized) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    initialized = false
                    // Reset MCP registration state — the new server instance
                    // won't have dynamic registrations from the previous instance.
                    onMcpReset?.invoke()
                }
                if (!restarted) {
                    _connectionErrorReason.value = ConnectionErrorReason.BinaryLaunchFailed(
                        detail = "Could not restart $launchedBinaryPath"
                    )
                    _connectionState.value = ConnectionState.ERROR
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
        // NOTE: This has an intentional time-of-check/time-of-use (TOCTOU) race —
        // the port can be taken by another process between this test bind and the
        // OpenCode server's actual bind. We accept this because re-trying the next
        // candidate port on a server bind failure would require parsing the
        // server's stdout for a bind error, which is fragile. Instead, if the port
        // is taken between test and use, the OpenCode server fails to bind and the
        // health-check timeout (INIT_TIMEOUT_MS) surfaces a clear error to the user.
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
                if (taskkill.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    val exitCode = taskkill.exitValue()
                    if (exitCode != 0) {
                        val stderr = try { taskkill.inputStream.bufferedReader().readText().take(200) } catch (_: Exception) { "" }
                        logger.warn { "[ACP] taskkill exited with code $exitCode for PID $pid — stderr: $stderr" }
                        // Still fall through to destroyForcibly as a fallback
                        process.destroyForcibly()
                    }
                } else {
                    logger.warn { "[ACP] taskkill timed out after 5s for PID $pid — falling back to destroyForcibly" }
                    taskkill.destroyForcibly()
                    process.destroyForcibly()
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
        _connectionErrorReason.value = null
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
        // Cancel the process watcher BEFORE nulling openCodeProcess. Otherwise the
        // old watcher holds a reference to the previous process and, when that
        // process exits, auto-restarts it via launchOpenCodeBinary() — racing with
        // the new initialize() call from retryConnection() and leaving two opencode
        // server processes running.
        processWatcherJob?.cancel()
        processWatcherJob = null
        val processToKill = openCodeProcess
        openCodeProcess = null
        if (processToKill != null) {
            Thread({
                killProcess(processToKill)
            }, "opencode-kill-retry").apply { isDaemon = true; start() }
        }
        initialized = false
        _connectionErrorReason.value = null
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

    /** @deprecated Use disconnect for retry, or shutdown for IDE close. */
    @Deprecated("Use disconnect for retry, or shutdown for IDE close.", level = DeprecationLevel.ERROR)
    fun close() = disconnect()
}
