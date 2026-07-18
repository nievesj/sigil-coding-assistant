package com.opencode.acp.chat.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Plugin-side EDT freeze detector that captures thread dumps WITHOUT depending on the EDT.
 *
 * The IDE's built-in freeze reporter creates directories but often fails to write the
 * actual thread dump files (observed: 14 empty directories). This happens because the
 * built-in reporter depends on EDT cooperation or gets killed on force-quit.
 *
 * This detector runs on a dedicated thread. It posts a [CountDownLatch] to
 * the EDT via `invokeLater` and waits up to 5 seconds. If the EDT doesn't process it,
 * the EDT is frozen. The detector then captures [Thread.getAllStackTraces] directly
 * (no EDT dependency) and writes the dump to disk via [Files.write] with
 * [StandardOpenOption.SYNC] for immediate flush.
 *
 * Dumps are written to `<project>/.opencode/freezes/` and teed to `idea.log` as fallback.
 * The last 20 dumps are retained; older ones are deleted.
 *
 * Started from [com.opencode.acp.chat.ChatToolWindowFactory.createToolWindowContent]
 * and stopped on tool window dispose.
 */
// Constructor is internal to prevent external instantiation with user-controlled paths.
// ChatToolWindowFactory passes a safe path (project.basePath + ".opencode/freezes").
class FreezeDetector internal constructor(private val dumpDir: File) {

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "acp-freeze-detector").apply {
            // Daemon so the thread does not prevent IDE shutdown. Dumps are written
            // synchronously with StandardOpenOption.SYNC, so they are on disk
            // before the thread exits even if the IDE is shutting down.
            isDaemon = true
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        dumpDir.mkdirs()
        executor.submit {
            while (running.get()) {
                try {
                    checkEdtResponsiveness()
                } catch (e: Exception) {
                    logger.debug(e) { "[ACP] FreezeDetector: error during check" }
                }
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun checkEdtResponsiveness() {
        val latch = CountDownLatch(1)
        try {
            javax.swing.SwingUtilities.invokeLater {
                latch.countDown()
            }
        } catch (e: Exception) {
            // IDE may be shutting down
            return
        }

        val edtResponsive = latch.await(EDT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!edtResponsive) {
            // EDT is frozen — capture thread dump immediately
            captureFreeze()
        }
    }

    private fun captureFreeze() {
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
            .format(java.time.LocalDateTime.now())
        val baseFileName = "acp-freeze-$timestamp"
        val dumpFile = File(dumpDir, "$baseFileName.txt")

        try {
            val sb = StringBuilder()
            sb.appendLine("=== ACP Freeze Detector — EDT unresponsive > ${EDT_TIMEOUT_MS}ms ===")
            sb.appendLine("Timestamp: ${java.util.Date()}")
            sb.appendLine("Approximate thread count: ${Thread.activeCount()}")
            sb.appendLine()

            // Capture all thread stacks directly — no EDT dependency
            val allStacks = Thread.getAllStackTraces()
            for ((thread, stack) in allStacks) {
                val state = thread.state
                val isEdt = thread.name == "AWT-EventQueue-0"
                val marker = if (isEdt) " <<< EDT (FROZEN)" else ""
                sb.appendLine("\"${thread.name}\" $state$marker")
                sb.appendLine("  priority=${thread.priority} daemon=${thread.isDaemon}")
                for (frame in stack) {
                    sb.appendLine("  at $frame")
                }
                sb.appendLine()
            }

            // Write synchronously with SYNC flag — on disk before thread exits.
            // Use CREATE_NEW to atomically fail if the file exists (collision-safe).
            // Retry with an incremented suffix if the millisecond timestamp collides.
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            var written = false
            var suffixIdx = 0
            while (!written) {
                val targetFile = if (suffixIdx == 0) dumpFile else File(dumpDir, "$baseFileName-$suffixIdx.txt")
                try {
                    Files.write(
                        targetFile.toPath(),
                        bytes,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.SYNC
                    )
                    written = true
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    suffixIdx++
                    if (suffixIdx > 100) {
                        // Give up after 100 attempts — extremely unlikely
                        logger.error { "[ACP] FreezeDetector: could not find unique dump filename after 100 attempts" }
                        return
                    }
                }
            }

            // Also log to idea.log as fallback
            logger.warn { "[ACP] FreezeDetector: EDT frozen > ${EDT_TIMEOUT_MS}ms — thread dump written to ${dumpFile.absolutePath}" }

            // Retain only last 20 dumps
            cleanupOldDumps()
        } catch (e: Exception) {
            logger.error(e) { "[ACP] FreezeDetector: failed to write freeze dump" }
        }
    }

    // INVARIANT: cleanupOldDumps() and captureFreeze() both run on the same
    // single-thread executor, so they cannot interleave. This makes the
    // listFiles → sort → delete sequence safe without explicit locking.
    // If the executor is ever changed to multi-threaded, add a lock here.
    private fun cleanupOldDumps() {
        try {
            val dumps = dumpDir.listFiles { f -> f.name.startsWith("acp-freeze-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            if (dumps.size > MAX_DUMPS) {
                dumps.drop(MAX_DUMPS).forEach { it.delete() }
            }
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 2000L
        /** EDT timeout threshold — 2s is enough to cause MCP tool timeouts (MCP server timeout is 5s).
         *  Lower than the typical 5s IDE freeze threshold so we capture freezes that cause
         *  MCP disconnections before the MCP server gives up. */
        private const val EDT_TIMEOUT_MS = 2000L
        private const val MAX_DUMPS = 20
    }
}