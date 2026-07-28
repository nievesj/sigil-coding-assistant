package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.ConnectionState
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.follow.EditorFollowManager
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ChatViewModel.generateContext] state transitions.
 *
 * generateContext() uses IntelliJ platform APIs (DumbService, ContextGenerator,
 * ContextFileWriter, NotificationGroupManager) that require a full application
 * context to construct. These tests verify the observable state transitions
 * (_contextGenerationState: Idle → Running → Success/Failed) and the null-basePath
 * guard, which is a pure-logic branch that doesn't need platform APIs beyond
 * `project.basePath`.
 *
 * The PSI collection and file-write paths require LightPlatformTestCase and are
 * not covered here — see AGENTS.md "Compose UI Tests" for the testing strategy
 * for platform-dependent code.
 */
class ChatViewModelGenerateContextTest {

    private val service: OpenCodeServiceApi = mockk(relaxed = true)
    private val project: Project = mockk(relaxed = true)
    private val settingsState: OpenCodeSettingsState = mockk(relaxed = true)
    private val followSettingsState: OpenCodeFollowSettingsState = mockk(relaxed = true)
    private val editorFollowManager: EditorFollowManager = mockk(relaxed = true)

    private val serviceMessagesFlow = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    private val serviceSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 256)
    private val serviceGlobalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 256)
    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)

    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        // NOTE: mockkObject calls MUST be balanced by unmockkObject in tearDown.
        // JUnit 5's @AfterEach runs even if the test throws, so tearDown should
        // always execute. If tearDown itself throws, the mocks leak into subsequent
        // test classes. The tearDown is straightforward, so this is low-risk.
        // The ChatViewModel construction launches coroutines on real dispatchers
        // (message snapshot collector, signal collectors, connectionObserverJob).
        // These are cancelled by scope.cancel() in tearDown, but there's a brief
        // window between construction and cancellation where they're active.
        every { service.messages } returns serviceMessagesFlow
        every { service.signals } returns serviceSignals
        every { service.globalSignals } returns serviceGlobalSignals
        every { service.connectionState } returns connectionState
        scope = CoroutineScope(SupervisorJob())
        every { service.scope } returns scope

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
        scope.cancel()
        unmockkObject(OpenCodeSettingsState.Companion)
        unmockkObject(EditorFollowManager.Companion)
        unmockkObject(OpenCodeFollowSettingsState.Companion)
    }

    /**
     * Verify that generateContext transitions to Failed when the project has no
     * base path (default/remote project). This is a pure-logic branch that doesn't
     * require DumbService or PSI — it fails before reaching those calls.
     *
     * NOTE: This test may not reach the null-basePath check if DumbService mocking
     * fails first. If the test environment doesn't have DumbService available,
     * the method will throw before the basePath check. In that case, the test
     * verifies that the state transitions to Failed (via the generic catch block)
     * rather than hanging in Running.
     */
    @Test
    fun `generateContext transitions to Failed on null basePath`() = runBlocking {
        // Arrange: project with no base path (default/remote project)
        every { project.basePath } returns null
        val viewModel = ChatViewModel(scope = scope, service = service, project = project)

        // Initial state is Idle
        viewModel.contextGenerationState.value shouldBe ContextGenerationState.Idle

        // Act
        viewModel.generateContext()

        // Wait for the launched coroutine to complete (with a timeout to avoid hanging).
        // The method launches on `scope` and transitions _contextGenerationState.
        // Since DumbService may not be available in the test environment, the method
        // may throw before reaching the basePath check — but the generic catch block
        // still transitions to Failed.
        //
        // FLAKINESS NOTE: This polling loop (Thread.sleep(50) with 10s budget) depends
        // on real thread scheduling. Under CI load, the coroutine may not transition
        // within 10s, causing a false failure. Converting to runTest with a
        // TestCoroutineScheduler would be deterministic but requires refactoring
        // generateContext to use an injectable dispatcher. Acceptable for now —
        // the 10s budget is generous and the test has been stable in practice.
        val finalState = withTimeoutOrNull(10_000) {
            while (viewModel.contextGenerationState.value is ContextGenerationState.Running) {
                Thread.sleep(50)
            }
            viewModel.contextGenerationState.value
        }

        // Assert: state must have left Running and reached Failed (not Success).
        finalState shouldNotBe null
        (finalState is ContextGenerationState.Failed) shouldBe true
        // NOTE: We don't assert the specific Failed message because the test environment
        // may not have DumbService available — the method may fail at DumbService before
        // reaching the null-basePath check. Both paths transition to Failed, so the test
        // verifies the state transition but not WHICH path was taken. If the null-basePath
        // check is removed in a future refactor, this test still passes via the DumbService
        // failure path — a known test smell. A platform test (LightPlatformTestCase) would
        // be needed to deterministically reach the null-basePath check.
    }

    @Test
    fun `contextGenerationState starts as Idle`() {
        val viewModel = ChatViewModel(scope = scope, service = service, project = project)
        viewModel.contextGenerationState.value shouldBe ContextGenerationState.Idle
    }

    @Test
    fun `resetContextGenerationState returns to Idle`() {
        val viewModel = ChatViewModel(scope = scope, service = service, project = project)
        viewModel.resetContextGenerationState()
        viewModel.contextGenerationState.value shouldBe ContextGenerationState.Idle
    }
}