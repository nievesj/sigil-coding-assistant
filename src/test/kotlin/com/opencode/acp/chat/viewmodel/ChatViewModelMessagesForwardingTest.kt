package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.follow.EditorFollowManager
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * §8.3 regression guard: `ChatViewModel.messages` MUST be a direct forwarding of
 * `service.messages` (the same StateFlow reference) for the MessageList State-read
 * stale-data fix to work (see AGENTS.md "LazyColumn items(count, key) — Stale Data
 * When Keys Are Stable").
 *
 * If `messages` becomes a copy or a derived flow (e.g., `service.messages.map { ... }`),
 * the Compose snapshot subscription inside the LazyColumn item content lambda would
 * subscribe to the wrong StateFlow, and the stale-data bug would reappear.
 *
 * This test verifies reference identity: `viewModel.messages === service.messages`.
 *
 * No fakes — uses mockk for the service (returns a real MutableStateFlow for
 * `messages`) and mockkObject for the IntelliJ Platform statics that ChatViewModel
 * touches at construction time (OpenCodeSettingsState, EditorFollowManager,
 * OpenCodeFollowSettingsState).
 */
class ChatViewModelMessagesForwardingTest {

    private lateinit var service: OpenCodeServiceApi
    private lateinit var project: Project
    private lateinit var settingsState: OpenCodeSettingsState
    private lateinit var followSettingsState: OpenCodeFollowSettingsState
    private lateinit var editorFollowManager: EditorFollowManager
    private lateinit var serviceMessagesFlow: MutableStateFlow<Map<String, ChatMessage>>
    private lateinit var serviceSignals: MutableSharedFlow<com.opencode.acp.chat.processor.UiSignal>
    private lateinit var serviceGlobalSignals: MutableSharedFlow<com.opencode.acp.chat.processor.UiSignal>
    private lateinit var serviceConnectionState: MutableStateFlow<com.opencode.acp.chat.model.ConnectionState>
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeEach
    fun setUp() {
        // A REAL MutableStateFlow — the test verifies that ChatViewModel forwards
        // this exact reference, not a copy or derived flow.
        serviceMessagesFlow = MutableStateFlow(emptyMap())
        // REAL SharedFlows — ChatViewModel launches coroutines that collect from
        // signals/globalSignals. A relaxed mockk returns Nothing for the collect
        // suspension point, causing KotlinNothingValueException in background
        // coroutines. Real SharedFlows suspend cleanly on collect.
        serviceSignals = MutableSharedFlow(extraBufferCapacity = 256)
        serviceGlobalSignals = MutableSharedFlow(extraBufferCapacity = 256)
        // REAL StateFlow for connectionState — ChatViewModel's connectionObserverJob
        // collects from service.connectionState. A mockk default StateFlow returns
        // Nothing on collect suspension → KotlinNothingValueException that leaks
        // into subsequent tests (CompactionViewModelTest.UncaughtExceptionsBeforeTest).
        serviceConnectionState = MutableStateFlow(com.opencode.acp.chat.model.ConnectionState.CONNECTED)

        service = mockk<OpenCodeServiceApi>(relaxed = true)
        project = mockk<Project>(relaxed = true)
        settingsState = mockk<OpenCodeSettingsState>(relaxed = true)
        followSettingsState = mockk<OpenCodeFollowSettingsState>(relaxed = true)
        editorFollowManager = mockk<EditorFollowManager>(relaxed = true)

        // service.messages returns our real MutableStateFlow.
        every { service.messages } returns serviceMessagesFlow
        // service.signals/globalSignals return real SharedFlows (not mockk defaults).
        every { service.signals } returns serviceSignals
        every { service.globalSignals } returns serviceGlobalSignals
        // service.connectionState returns a real StateFlow (not mockk default).
        every { service.connectionState } returns serviceConnectionState
        // service.scope returns a real CoroutineScope — cancelled in tearDown.
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        every { service.scope } returns scope

        // Mock the companion-object statics that ChatViewModel touches at construction.
        mockkObject(OpenCodeSettingsState.Companion)
        every { OpenCodeSettingsState.getInstance() } returns settingsState
        every { settingsState.sidebarVisible } returns false

        mockkObject(EditorFollowManager.Companion)
        every { EditorFollowManager.getInstance(project) } returns editorFollowManager
        every { editorFollowManager.isFollowEnabled() } returns false

        mockkObject(OpenCodeFollowSettingsState.Companion)
        every { OpenCodeFollowSettingsState.getInstance() } returns followSettingsState
        every { followSettingsState.braveModeEnabled } returns false
    }

    @AfterEach
    fun tearDown() {
        // Cancel ALL scopes — both the service.scope mockk return AND the scope
        // passed to ChatViewModel. ChatViewModel launches background coroutines
        // (SignalRouter, SignalSideEffectExecutor, connectionObserver, message
        // snapshot logger) that collect from service.signals/globalSignals and
        // connectionState. Without cancelling the ChatViewModel's scope, these
        // coroutines continue running after the test and may throw uncaught
        // exceptions (KotlinNothingValueException from mockk defaults) that pollute
        // subsequent tests (e.g., CompactionViewModelTest.UncaughtExceptionsBeforeTest).
        scopes.forEach { it.cancel() }
        unmockkObject(OpenCodeSettingsState.Companion)
        unmockkObject(EditorFollowManager.Companion)
        unmockkObject(OpenCodeFollowSettingsState.Companion)
    }

    @Test
    fun `ChatViewModel messages is the same reference as service messages`() {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        val viewModel = ChatViewModel(
            scope = scope,
            service = service,
            project = project,
        )

        // The critical assertion: viewModel.messages MUST be the exact same
        // StateFlow instance that service.messages returns. A derived/copied flow
        // would break the MessageList State-read stale-data fix.
        viewModel.messages shouldBeSameInstanceAs serviceMessagesFlow
        viewModel.messages shouldBe service.messages
    }

    @Test
    fun `ChatViewModel messages reflects updates to the underlying service flow`() {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        val viewModel = ChatViewModel(
            scope = scope,
            service = service,
            project = project,
        )

        // Because viewModel.messages is the same reference, updating the service
        // flow must be observable through viewModel.messages.
        viewModel.messages.value shouldBe emptyMap()

        val msg = ChatMessage(
            id = "msg_1",
            role = com.opencode.acp.chat.model.MessageRole.USER,
            parts = emptyMap(),
            timestamp = 0L,
        )
        serviceMessagesFlow.value = mapOf("msg_1" to msg)

        viewModel.messages.value shouldBe mapOf("msg_1" to msg)
        viewModel.messages.value shouldBe service.messages.value
    }
}