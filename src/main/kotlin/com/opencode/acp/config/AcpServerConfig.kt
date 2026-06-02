package com.opencode.acp.config

import com.opencode.acp.TransportMode

object AcpDefaults {
    const val DEFAULT_OPENCODE_HOST = "127.0.0.1"
    const val DEFAULT_OPENCODE_PORT = 4096
    const val SESSION_PERSISTENCE_DIR = "session-store"
    const val MAX_CONCURRENT_SESSIONS = 50
    const val SSE_RECONNECT_DELAY_MS = 1000L
    const val TERMINAL_OUTPUT_BYTE_LIMIT = 1_048_576L // 1MB default
    const val SESSION_LOAD_TIMEOUT_MS = 30_000L
}

data class AcpServerConfig(
    val openCodeHost: String = AcpDefaults.DEFAULT_OPENCODE_HOST,
    val openCodePort: Int = AcpDefaults.DEFAULT_OPENCODE_PORT,
    val openCodePassword: String? = null,
    val openCodeLaunch: Boolean = false,
    val openCodeBinaryPath: String? = null,
    val sessionPersistenceDir: String = AcpDefaults.SESSION_PERSISTENCE_DIR,
    val maxConcurrentSessions: Int = AcpDefaults.MAX_CONCURRENT_SESSIONS,
    val transport: TransportMode = TransportMode.STDIO
) {
    init {
        require(openCodePort in 0..65535) { "Invalid port: $openCodePort" }
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be > 0" }
    }

    companion object {
        fun parse(args: Array<String>): AcpServerConfig {
            var host = AcpDefaults.DEFAULT_OPENCODE_HOST
            var port = AcpDefaults.DEFAULT_OPENCODE_PORT
            var password: String? = null
            var persistenceDir = AcpDefaults.SESSION_PERSISTENCE_DIR
            var transport = TransportMode.STDIO

            val envHost = System.getenv("OPENCODE_HOST")
            val envPort = System.getenv("OPENCODE_PORT")
            val envPassword = System.getenv("OPENCODE_SERVER_PASSWORD")

            envHost?.let { host = it }
            envPort?.let { port = it.toIntOrNull() ?: port }
            envPassword?.let { password = it }

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--opencode-host" -> { host = args[++i] }
                    "--opencode-port" -> { port = args[++i].toInt() }
                    "--opencode-password" -> { password = args[++i] }
                    "--persistence-dir" -> { persistenceDir = args[++i] }
                    "--transport" -> { transport = TransportMode.valueOf(args[++i].uppercase()) }
                }
                i++
            }

            return AcpServerConfig(
                openCodeHost = host,
                openCodePort = port,
                openCodePassword = password,
                sessionPersistenceDir = persistenceDir,
                transport = transport
            )
        }
    }
}
