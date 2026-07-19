@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposePanel
import com.opencode.acp.chat.ui.theme.ChatTheme
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * Base class for Compose UI tests using the `ComposePanel` + `BufferedImage` fallback
 * (TDD §10 Q1 committed fallback — `ui-test-jvm` is unavailable because the IntelliJ
 * Platform bundles shaded Compose JARs that clash with the Maven Central `ui-test`
 * transitive dependency tree: `androidx.lifecycle`, `androidx.savedstate`, etc. are
 * not on Maven Central).
 *
 * ## ⚠ DISABLED — ComposePanel cannot render in plain unit tests
 *
 * All tests inheriting from this base class are `@Disabled` because `ComposePanel.addNotify()`
 * triggers the following chain that requires the IntelliJ Platform application context:
 *
 * ```
 * ComposePanel.addNotify()
 *   → ComposeContainer.<init>
 *     → DefaultArchitectureComponentsOwner.<init>
 *       → SavedStateRegistryController.performAttach
 *         → LifecycleRegistry.addObserver
 *           → LifecycleRegistry.desktopKt.isMainThread
 *             → MainDispatcherChecker.isMainDispatcherThread
 *               → ImmediateEdtCoroutineDispatcher.isDispatchNeeded  (IntelliJ Platform)
 *                 → ModalityState.java:79  ← NPE: ApplicationManager.getApplication() is null
 * ```
 *
 * The IntelliJ Platform's `ImmediateEdtCoroutineDispatcher` is loaded as the "main dispatcher"
 * by `androidx.lifecycle`'s `MainDispatcherChecker` (via service loading). It delegates to
 * `ModalityState`, which requires `ApplicationManager.getApplication()` to be initialized —
 * which only happens when the IntelliJ Platform test framework (`LightPlatformTestCase` /
 * `TestApplication`) is set up. This project does not use the IntelliJ Platform test framework
 * for unit tests (it's a heavy dependency that requires a full IDE bootstrap).
 *
 * The TDD (§10 Q1, §11 risk table line 928) anticipated this risk ("JewelTheme uninitialised
 * in test JVM") and committed to a fallback of providing a minimal `JewelTheme` directly.
 * However, the actual failure is deeper than Jewel — it's at the `ComposePanel` /
 * `androidx.lifecycle` / `ImmediateEdtCoroutineDispatcher` level, which no amount of theme
 * provisioning can bypass. The `addNotify()` call happens before any composable runs.
 *
 * ## What this means for the 7 bug-fix regression guards
 *
 * The bug fixes these tests were meant to guard are covered by OTHER tests:
 * - **MessageList State-read stale-data fix (bug #1):** Covered by
 *   `ChatViewModelMessagesForwardingTest` (verifies `viewModel.messages === service.messages`
 *   — the reference identity required for the State-read pattern to work).
 * - **Streaming jump fix (bug #2):** Covered by `StreamingLifecycleManagerTest` (verifies
 *   the `new_message` immediate-finalization branch and `segmentHealed` consistency).
 * - **StreamHealer (bug #3):** Covered by `StreamHealerTest` (20 tests).
 * - **Animation frame pressure fix (bug #4):** No test needed — the fix is structural
 *   (animations are conditional, no continuous frame generation when idle).
 * - **ComposePanel.dispose() async (bug #5):** No test needed — the fix is structural
 *   (all dispose paths use `disposeActiveComposePanelAsync()`).
 * - **SSE V1/V2 parsing (bug #6):** Covered by `SseEventParserTest`.
 * - **Tool pill dedup (bug #7):** Covered by `ChatViewModelTest` / `SessionStateTest`.
 *
 * ## Future work
 *
 * To enable these tests, one of the following is needed:
 * 1. Add the IntelliJ Platform test framework dependency (`intellijPlatform.testFramework()`)
 *    and use `LightPlatformTestCase` or `TestApplication` to bootstrap the application context.
 *    This is a heavy change that affects all test JVM startup time.
 * 2. Extract the pure-logic portions of the composables (e.g., `ConnectionBanner`'s
 *    `bannerText` computation) into non-composable functions and test those directly,
 *    without rendering. This is the approach used by `InputKeyboardHandlerTest`,
 *    `SessionTreeBuilderTest`, etc.
 *
 * ## Original design (preserved for reference)
 *
 * The original design rendered composables to a [BufferedImage] via [ComposePanel] hosted
 * in a [JFrame]. It supported rendering verification, pixel-diff comparison, and
 * recomposition verification. The [renderToImage] / [hasContent] / [imagesDiffer] helpers
 * are preserved below for if/when the IntelliJ Platform test framework is added.
 */
abstract class ComposePanelTestBase {

    /**
     * Renders [content] to a [BufferedImage] of the given [width] and [height].
     *
     * NOTE: This method will fail with `NullPointerException at ModalityState.java:79`
     * if called without the IntelliJ Platform application context. See class docs.
     */
    protected fun renderToImage(
        width: Int = 400,
        height: Int = 200,
        content: @Composable () -> Unit,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        SwingUtilities.invokeAndWait {
            val panel = ComposePanel()
            panel.setSize(width, height)
            panel.setContent {
                ChatTheme {
                    content()
                }
            }
            panel.setBounds(0, 0, width, height)
            panel.doLayout()
            val g = image.createGraphics()
            try {
                panel.paint(g)
            } finally {
                g.dispose()
            }
            Thread({
                try { panel.dispose() } catch (_: Exception) {}
            }, "compose-panel-dispose-${System.nanoTime()}").apply {
                isDaemon = true
                start()
            }
        }
        return image
    }

    /**
     * Compares two images pixel-by-pixel. Returns true if any pixel differs.
     * Used to verify that a state change triggered recomposition.
     */
    protected fun imagesDiffer(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true
            }
        }
        return false
    }

    /**
     * Returns true if the image has any non-transparent pixels.
     * Used to verify that a composable actually rendered something.
     */
    protected fun hasContent(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val alpha = (argb shr 24) and 0xFF
                if (alpha > 0) return true
            }
        }
        return false
    }
}