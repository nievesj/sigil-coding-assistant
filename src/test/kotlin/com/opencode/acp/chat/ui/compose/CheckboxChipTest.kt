package com.opencode.acp.chat.ui.compose

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [CheckboxChip] — the shared checkbox composable extracted from
 * InputArea's FollowAgentCheckbox and BraveModeCheckbox (Phase 4).
 *
 * ⚠ DISABLED: ComposePanel cannot render in plain unit tests —
 * `ComposePanel.addNotify()` triggers `androidx.lifecycle` → IntelliJ's
 * `ImmediateEdtCoroutineDispatcher` → `ModalityState` NPE (no application context).
 * See [ComposePanelTestBase] class docs for details and alternatives.
 *
 * The pure-logic extraction (CheckboxChip's structure) is verified by compilation
 * and by the InputArea integration tests. The visual rendering would require the
 * IntelliJ Platform test framework (`LightPlatformTestCase`).
 */
@Disabled("ComposePanel.addNotify() requires IntelliJ application context — see ComposePanelTestBase docs")
class CheckboxChipTest : ComposePanelTestBase() {

    @Test
    fun `CheckboxChip with enabled=false renders without crashing`() {
        val image = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = false,
                onToggle = {},
                color = Color(0xFF3574F0),
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `CheckboxChip with enabled=true renders without crashing`() {
        val image = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = true,
                onToggle = {},
                color = Color(0xFF3574F0),
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `CheckboxChip enabled and disabled produce different output`() {
        val disabled = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = false,
                onToggle = {},
                color = Color(0xFF3574F0),
            )
        }
        val enabled = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = true,
                onToggle = {},
                color = Color(0xFF3574F0),
            )
        }
        imagesDiffer(disabled, enabled) shouldBe true
    }

    @Test
    fun `CheckboxChip with BraveMode color renders without crashing`() {
        val image = renderToImage {
            CheckboxChip(
                label = "Brave Mode",
                tooltip = "Auto-approve tool calls without prompting",
                enabled = true,
                onToggle = {},
                color = Color(0xFFE8A030),
            )
        }
        hasContent(image) shouldBe true
    }

    @Test
    fun `CheckboxChip with different colors produces different output`() {
        val blue = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = true,
                onToggle = {},
                color = Color(0xFF3574F0),
            )
        }
        val orange = renderToImage {
            CheckboxChip(
                label = "Follow",
                tooltip = "Follow the agent's tool calls",
                enabled = true,
                onToggle = {},
                color = Color(0xFFE8A030),
            )
        }
        imagesDiffer(blue, orange) shouldBe true
    }
}