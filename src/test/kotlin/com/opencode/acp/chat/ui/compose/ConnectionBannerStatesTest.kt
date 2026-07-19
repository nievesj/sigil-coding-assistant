package com.opencode.acp.chat.ui.compose

import com.opencode.acp.chat.model.ConnectionErrorReason
import com.opencode.acp.chat.model.ConnectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [ConnectionBanner] — verifies all 5 [ConnectionState] branches render
 * without crashing and produce visible content.
 *
 * ⚠ DISABLED: ComposePanel cannot render in plain unit tests —
 * `ComposePanel.addNotify()` triggers `androidx.lifecycle` → IntelliJ's
 * `ImmediateEdtCoroutineDispatcher` → `ModalityState` NPE (no application context).
 * See [ComposePanelTestBase] class docs for details and alternatives.
 *
 * The `bannerText` computation logic in `ConnectionBanner` is pure (a `when`
 * expression over `ConnectionState` + `ConnectionErrorReason`). To test it without
 * rendering, extract the `when` block into a top-level pure function and unit-test
 * that directly. The rendering path (Row/Icon/Text/Link) requires the IntelliJ
 * Platform test framework.
 */
@Disabled("ComposePanel.addNotify() requires IntelliJ application context — see ComposePanelTestBase docs")
class ConnectionBannerStatesTest : ComposePanelTestBase() {

    @Test
    fun `DISCONNECTED renders banner with retry link`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.DISCONNECTED,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `CONNECTING renders banner without retry link`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.CONNECTING,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `RECONNECTING renders banner without retry link`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.RECONNECTING,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `ERROR with NoBinaryConfigured renders banner`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.NoBinaryConfigured,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `ERROR with BinaryLaunchFailed renders banner`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.BinaryLaunchFailed("permission denied"),
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `ERROR with ProcessExited renders banner`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.ProcessExited(exitCode = 1, outputTail = ""),
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `ERROR with HealthCheckTimeout renders banner`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.HealthCheckTimeout,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `ERROR with ServerUnreachable renders banner`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.ServerUnreachable,
                onRetry = {},
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `CONNECTED renders nothing (early return)`() {
        val image = renderToImage {
            ConnectionBanner(
                state = ConnectionState.CONNECTED,
                onRetry = {},
            )
        }
        // CONNECTED returns early — no banner rendered.
        hasContent(image) shouldBe false
    }

    @Test
    fun `DISCONNECTED and ERROR produce different banners`() {
        val disconnected = renderToImage {
            ConnectionBanner(state = ConnectionState.DISCONNECTED, onRetry = {})
        }
        val error = renderToImage {
            ConnectionBanner(
                state = ConnectionState.ERROR,
                errorReason = ConnectionErrorReason.HealthCheckTimeout,
                onRetry = {},
            )
        }
        imagesDiffer(disconnected, error) shouldBe true
    }
}