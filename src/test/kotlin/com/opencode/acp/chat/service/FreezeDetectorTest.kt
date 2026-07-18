package com.opencode.acp.chat.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Regression tests for [FreezeDetector].
 *
 * [FreezeDetector] runs on a dedicated daemon thread and posts a [java.util.concurrent.CountDownLatch]
 * to the EDT via `SwingUtilities.invokeLater`. If the EDT doesn't process it within 5 seconds,
 * a thread dump is written to the dump directory.
 *
 * These tests mock [SwingUtilities.invokeLater] to simulate either a responsive EDT
 * (runnable executed immediately) or a frozen EDT (runnable captured but never executed).
 *
 * NOTE: These tests use real wall-clock time (`Thread.sleep`) because [FreezeDetector] uses
 * real threads and real `Thread.sleep`. This mirrors [ResponseTimeoutMonitorTest] which
 * uses `System.currentTimeMillis()` alongside virtual time. The test durations (3s and 9s)
 * are short enough to not be flaky.
 */
class FreezeDetectorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var detector: FreezeDetector

    @AfterEach
    fun tearDown() {
        if (::detector.isInitialized) {
            detector.stop()
        }
        unmockkStatic(SwingUtilities::class)
    }

    @Test
    fun `no dump file created when EDT is responsive`() {
        mockkStatic(SwingUtilities::class)
        // Responsive EDT: execute the runnable immediately so the latch counts down.
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        detector = FreezeDetector(tempDir.toFile())
        detector.start()

        // Wait for at least 1.5 check cycles (2s interval + margin).
        // Since EDT responds immediately, no dump should be created.
        // 7s covers 1.5 cycles so the first check definitely ran.
        Thread.sleep(7000)

        val dumpFiles = tempDir.toFile().listFiles { f -> f.name.startsWith("acp-freeze-") }
        dumpFiles?.size shouldBe 0
    }

    @Test
    fun `dump file created when EDT is unresponsive`() {
        mockkStatic(SwingUtilities::class)
        // Frozen EDT: invokeLater captures but never executes, so the latch never counts down.
        every { SwingUtilities.invokeLater(any()) } just runs

        detector = FreezeDetector(tempDir.toFile())
        detector.start()

        // Wait for the detector to detect the freeze: 2s check interval + 2s timeout = 4s.
        // 7s covers 1.5 cycles so at least one dump is created. The detector creates a
        // dump per frozen cycle (~4s per dump). We assert AT LEAST ONE dump exists.
        Thread.sleep(7000)

        val dumpFiles = tempDir.toFile().listFiles { f -> f.name.startsWith("acp-freeze-") }
        dumpFiles shouldNotBe null
        // Read the dump content BEFORE stop() — stop() may cancel a pending dump write,
        // leaving a partial or empty file.
        dumpFiles!!.size shouldBeGreaterThan 0
        val dumpContent = dumpFiles[0].readText()
        // Stop the detector after reading to avoid creating additional dumps.
        detector.stop()
        dumpContent shouldContain "EDT unresponsive"
        dumpContent shouldContain "acp-freeze-detector"
    }
}