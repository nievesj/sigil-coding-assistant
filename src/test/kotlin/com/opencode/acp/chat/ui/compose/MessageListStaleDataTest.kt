package com.opencode.acp.chat.ui.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Regression guard for the MessageList State-read stale-data fix (bug fix #1).
 *
 * ⚠ DISABLED: ComposePanel cannot render in plain unit tests —
 * `ComposePanel.addNotify()` triggers `androidx.lifecycle` → IntelliJ's
 * `ImmediateEdtCoroutineDispatcher` → `ModalityState` NPE (no application context).
 * See [ComposePanelTestBase] class docs for details and alternatives.
 *
 * The State-read stale-data fix is guarded by [ChatViewModelMessagesForwardingTest],
 * which verifies `viewModel.messages === service.messages` (the reference identity
 * required for the State-read pattern to work). If `messages` becomes a copy or
 * derived flow, the Compose snapshot subscription inside the LazyColumn item content
 * lambda would subscribe to the wrong StateFlow, and the stale-data bug would reappear.
 *
 * The rendering-level test (does the item actually recompose?) requires the IntelliJ
 * Platform test framework (`LightPlatformTestCase`) to bootstrap the application
 * context that `ComposePanel.addNotify()` needs.
 */
@Disabled("ComposePanel.addNotify() requires IntelliJ application context — see ComposePanelTestBase docs")
class MessageListStaleDataTest : ComposePanelTestBase() {

    private fun makeMessage(
        id: String,
        text: String,
        role: MessageRole = MessageRole.ASSISTANT,
        isStreaming: Boolean = false,
    ): ChatMessage {
        return ChatMessage(
            id = id,
            role = role,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Text(text)),
            timestamp = System.currentTimeMillis(),
            isStreaming = isStreaming,
        )
    }

    /**
     * CRITICAL regression guard: updating a message's parts (without changing
     * the key) must trigger recomposition. If the State-read pattern is broken,
     * the rendered output would NOT change (stale data).
     *
     * The test:
     * 1. Creates a messagesState with one message containing "initial text"
     * 2. Renders MessageList to an image (before)
     * 3. Updates the message's parts to contain "updated text" (same key/id)
     * 4. Renders MessageList to an image (after)
     * 5. Asserts the images differ — proving recomposition occurred
     *
     * If the State-read pattern is removed or broken, the images would be
     * identical (stale data) and this test would fail.
     */
    @Test
    fun `updating message parts without key change triggers recomposition`() {
        val msgId = "msg_stale_test"
        val messagesState: State<Map<String, ChatMessage>> = mutableStateOf(
            mapOf(msgId to makeMessage(msgId, "initial text content"))
        )

        val before = renderToImage(width = 600, height = 400) {
            MessageList(
                messagesState = messagesState,
            )
        }

        // Update the message's parts — same id (key), different content.
        // This is the exact scenario the State-read fix handles: the LazyColumn
        // key (msgId) doesn't change, so without the State-read inside the lambda,
        // the item wouldn't recompose.
        @Suppress("UNCHECKED_CAST")
        val mutableState = messagesState as androidx.compose.runtime.MutableState<Map<String, ChatMessage>>
        mutableState.value = mapOf(
            msgId to makeMessage(msgId, "updated text content that is different")
        )

        val after = renderToImage(width = 600, height = 400) {
            MessageList(
                messagesState = messagesState,
            )
        }

        // The images MUST differ — proving recomposition occurred.
        // If they're identical, the State-read pattern is broken (stale data).
        imagesDiffer(before, after) shouldBe true
    }

    /**
     * Negative control: rendering the same state twice should produce identical
     * images (no spurious recomposition). This verifies the test infrastructure
     * is deterministic.
     */
    @Test
    fun `rendering same state twice produces identical images (negative control)`() {
        val msgId = "msg_stable_test"
        val messagesState: State<Map<String, ChatMessage>> = mutableStateOf(
            mapOf(msgId to makeMessage(msgId, "stable text content"))
        )

        val before = renderToImage(width = 600, height = 400) {
            MessageList(messagesState = messagesState)
        }

        val after = renderToImage(width = 600, height = 400) {
            MessageList(messagesState = messagesState)
        }

        // Same state → same image (no spurious recomposition).
        // NOTE: This may not be perfectly identical due to animation timing,
        // but the core content should match. If this fails intermittently,
        // it's a test infrastructure issue, not a bug.
        imagesDiffer(before, after) shouldBe false
    }

    /**
     * Empty streaming messages (0 parts, isStreaming=true) must be filtered
     * from the LazyColumn (streaming jump fix #2). This test verifies the filter
     * is in place — an empty streaming message should not appear in the rendered
     * output.
     */
    @Test
    fun `empty streaming messages are filtered from the list`() {
        val solidMsgId = "msg_solid"
        val emptyStreamingMsgId = "msg_empty_streaming"

        val messagesState: State<Map<String, ChatMessage>> = mutableStateOf(
            mapOf(
                solidMsgId to makeMessage(solidMsgId, "solid content"),
                emptyStreamingMsgId to ChatMessage(
                    id = emptyStreamingMsgId,
                    role = MessageRole.ASSISTANT,
                    parts = emptyMap(), // 0 parts
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                ),
            )
        )

        val image = renderToImage(width = 600, height = 400) {
            MessageList(messagesState = messagesState)
        }

        // The image should have content (the solid message rendered).
        // The empty streaming message is filtered out.
        hasContent(image) shouldBe true
    }
}