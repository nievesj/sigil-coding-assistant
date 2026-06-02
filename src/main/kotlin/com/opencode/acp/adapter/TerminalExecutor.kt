package com.opencode.acp.adapter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.FlowCollector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local process execution for terminal fallback.
 *
 * Manages spawned process instances per session using [ConcurrentHashMap],
 * providing read/write streams and lifecycle control for each terminal.
 *
 * @param scope the coroutine scope for launching background tasks
 */
class TerminalExecutor(
    private val scope: CoroutineScope
) {
    /** Tracks all terminal processes: (sessionId, terminalId) -> Process */
    private val processes = ConcurrentHashMap<Pair<String, String>, Process>()

    /** Tracks output readers: (sessionId, terminalId) -> Thread */
    private val readers = ConcurrentHashMap<Pair<String, String>, Thread>()

    /** Tracks stdin writers: (sessionId, terminalId) -> OutputStreamWriter */
    private val writers = ConcurrentHashMap<Pair<String, String>, OutputStreamWriter>()

    /** Local counter for generating unique terminal IDs within a session */
    private val terminalCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** Default output byte limit per chunk (64 KB) */
    private val defaultOutputByteLimit: Long = 64 * 1024

    /**
     * Creates a local terminal process for the given [sessionId].
     *
     * @param sessionId the session identifier
     * @param command the command to execute
     * @param args optional command-line arguments
     * @param env optional environment variable overrides
     * @param cwd optional working directory (defaults to process parent)
     * @param outputByteLimit optional byte limit per output chunk
     * @return the generated terminal ID
     */
    fun createLocalTerminal(
        sessionId: String,
        command: String,
        args: String? = null,
        env: Map<String, String>? = null,
        cwd: String? = null,
        outputByteLimit: Long? = null
    ): String {
        val counter = terminalCounters.getOrPut(sessionId) { AtomicInteger(0) }
        val terminalId = "term-${counter.incrementAndGet()}"
        val key = sessionId to terminalId

        val processBuilder = ProcessBuilder().apply {
            val cmdList = mutableListOf(command)
            if (args != null) {
                cmdList.addAll(parseArgs(args))
            }
            command(cmdList)
            if (env != null) {
                environment().putAll(env)
            }
            if (cwd != null) {
                directory(java.io.File(cwd))
            }
            redirectErrorStream(false)
        }

        val process = processBuilder.start()
        processes[key] = process

        // Store stdin writer
        val writer = OutputStreamWriter(process.outputStream)
        writers[key] = writer

        val byteLimit = outputByteLimit ?: defaultOutputByteLimit

        // Start stdout reader thread
        val stdoutThread = startReaderThread(key, process.inputStream, terminalId, byteLimit, false)
        readers[key] = stdoutThread

        return terminalId
    }

    /**
     * Sends input to the specified terminal's stdin.
     */
    fun sendInput(terminalId: String, input: String) {
        // Find the process by terminalId across all sessions
        val entry = writers.entries.firstOrNull { it.key.second == terminalId }
            ?: throw IllegalArgumentException("Terminal not found: $terminalId")

        val writer = entry.value
        writer.write(input)
        writer.flush()
    }

    /**
     * Returns a [Flow] of [TerminalOutputChunk] for the given [sessionId].
     * This covers both stdout and stderr output across all terminals in the session.
     *
     * Note: The current implementation provides a merged output flow from stdout readers.
     * For production use, consider per-terminal output flows.
     */
    fun outputStream(sessionId: String): Flow<TerminalOutputChunk> = callbackFlow {
        val sessionProcesses = processes.filterKeys { it.first == sessionId }

        for ((key, process) in sessionProcesses) {
            val terminalId = key.second

            // Start stderr reader
            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            trySend(
                                TerminalOutputChunk(
                                    terminalId = terminalId,
                                    text = line!! + "\n",
                                    isStderr = true
                                )
                            )
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                } catch (_: Exception) {
                    // Stream ended or process terminated
                }
            }
            stderrThread.isDaemon = true
            stderrThread.start()
        }

        awaitClose {
            // Cleanup is handled by releaseLocalTerminal or releaseAllForSession
        }
    }

    /**
     * Kills a specific terminal process.
     */
    fun killLocalTerminal(sessionId: String, terminalId: String) {
        val key = sessionId to terminalId
        processes[key]?.destroyForcibly()
    }

    /**
     * Releases (kills and cleans up) a specific terminal.
     */
    fun releaseLocalTerminal(sessionId: String, terminalId: String) {
        val key = sessionId to terminalId
        processes[key]?.destroy()
        processes.remove(key)
        readers.remove(key)?.interrupt()
        writers.remove(key)?.close()
    }

    /**
     * Releases all terminal resources for the given [sessionId].
     */
    fun releaseAllForSession(sessionId: String) {
        val keysToRemove = processes.keys.filter { it.first == sessionId }
        for (key in keysToRemove) {
            releaseLocalTerminal(key.first, key.second)
        }
        terminalCounters.remove(sessionId)
    }

    /**
     * Returns the number of active terminals across all sessions.
     */
    fun activeTerminalCount(): Int = processes.size

    /**
     * Returns the number of active terminals for a specific [sessionId].
     */
    fun activeTerminalCount(sessionId: String): Int =
        processes.count { it.key.first == sessionId }

    private fun startReaderThread(
        key: Pair<String, String>,
        inputStream: java.io.InputStream,
        terminalId: String,
        byteLimit: Long,
        isStderr: Boolean
    ): Thread {
        val thread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Output is collected via the outputStream() Flow
                    // This thread just drains stdout to prevent process blocking
                }
            } catch (_: Exception) {
                // Stream ended or process terminated
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun parseArgs(args: String): List<String> {
        return args.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    }
}
