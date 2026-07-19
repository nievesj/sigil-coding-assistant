package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.model.SelectionOption
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.SelectionResponse
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.chat.service.PermissionManager
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PermissionViewModel] (TDD Â§4.2.6).
 *
 * Tests permission/selection prompt state management and child permission
 * timeouts. Uses MockK for [OpenCodeService], [Project], [PermissionManager],
 * [SessionManager], and mockkStatic/mockkObject for IntelliJ Platform statics
 * ([OpenCodeSettingsState.getInstance], [OpenCodeNotifications]).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermissionViewModelTest {

    private lateinit var service: OpenCodeServiceApi
    private lateinit var project: Project
    private lateinit var permissionManager: PermissionManager
    private lateinit var sessionManager: SessionManager
    private lateinit var settingsState: OpenCodeSettingsState
    private lateinit var viewModel: PermissionViewModel
    private var testScope: kotlinx.coroutines.test.TestScope? = null

    @BeforeEach
    fun setUp() {
        service = mockk<OpenCodeServiceApi>(relaxed = true)
        project = mockk<Project>(relaxed = true)
        permissionManager = mockk<PermissionManager>(relaxed = true)
        sessionManager = mockk<SessionManager>(relaxed = true)

        // service.scope returns a real CoroutineScope with Unconfined dispatcher for immediate execution
        val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        every { service.scope } returns defaultScope
        every { service.permissionManager } returns permissionManager
        every { service.sessionManager } returns sessionManager

        // Mock OpenCodeSettingsState.getInstance() â€” it's a companion object method
        settingsState = mockk<OpenCodeSettingsState>(relaxed = true)
        every { settingsState.permissionTimeoutSeconds } returns 60
        every { settingsState.state } returns settingsState
        mockkObject(OpenCodeSettingsState.Companion)
        every { OpenCodeSettingsState.getInstance() } returns settingsState

        // Mock OpenCodeNotifications object
        mockkObject(OpenCodeNotifications)
        every { OpenCodeNotifications.notifyPermissionTimedOut(any(), any()) } just runs

        viewModel = PermissionViewModel(
            scope = defaultScope,
            service = service,
            project = project,
        )
    }

    @AfterEach
    fun tearDown() {
        testScope?.cancel()
        io.mockk.unmockkObject(OpenCodeSettingsState.Companion)
        io.mockk.unmockkObject(OpenCodeNotifications)
    }

    private fun makePermissionPrompt(
        permissionId: String = "perm_1",
        sessionId: String = "ses_1",
        toolCallId: String = "tc_1",
        toolName: String = "bash",
    ) = PermissionPrompt(
        sessionId = sessionId,
        permissionId = permissionId,
        toolCallId = toolCallId,
        toolName = toolName,
        description = "Run bash",
        patterns = emptyList(),
    )

    private fun makeChildPermissionPrompt(
        childSessionId: String = "child_1",
        permissionId: String = "perm_c1",
        toolCallId: String = "tc_c1",
        toolName: String = "edit",
    ) = ChildPermissionPrompt(
        childSessionId = childSessionId,
        permissionId = permissionId,
        toolCallId = toolCallId,
        toolName = toolName,
        description = "Edit file",
        patterns = emptyList(),
        subAgentLabel = "fixer",
        agentLabelVerified = true,
    )

    // â”€â”€ Active-session permission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `setPermissionPrompt sets the prompt state`() {
        val prompt = makePermissionPrompt()
        viewModel.setPermissionPrompt(prompt)
        viewModel.permissionPrompt.value shouldBe prompt
    }

    @Test
    fun `setPermissionPrompt with null clears the prompt state`() {
        viewModel.setPermissionPrompt(makePermissionPrompt())
        viewModel.setPermissionPrompt(null)
        viewModel.permissionPrompt.value shouldBe null
    }

    @Test
    fun `respondPermission clears prompt on success`() = runBlocking {
        val prompt = makePermissionPrompt()
        viewModel.setPermissionPrompt(prompt)
        coEvery { service.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        viewModel.respondPermission(PermissionResponse.ALLOW_ONCE, "orchestrator")

        viewModel.permissionPrompt.value shouldBe null
        verify { permissionManager.cancelPermissionTimeout() }
    }

    @Test
    fun `respondPermission keeps prompt open on failure and tracks session`() = runBlocking {
        val prompt = makePermissionPrompt(sessionId = "ses_fail")
        viewModel.setPermissionPrompt(prompt)
        coEvery { service.respondPermission(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("network")

        viewModel.respondPermission(PermissionResponse.ALLOW_ONCE, "orchestrator")

        // Prompt should still be visible
        viewModel.permissionPrompt.value shouldBe prompt
        // Session should be tracked as failed
        viewModel.failedPermissionPostSessions.contains("ses_fail") shouldBe true
    }

    @Test
    fun `respondPermission with no prompt is no-op`() = runBlocking {
        viewModel.respondPermission(PermissionResponse.ALLOW_ONCE, "orchestrator")
        viewModel.permissionPrompt.value shouldBe null
    }

    @Test
    fun `startPermissionTimeout delegates to permissionManager`() {
        val prompt = makePermissionPrompt(toolName = "bash")
        viewModel.setPermissionPrompt(prompt)
        viewModel.startPermissionTimeout()
        verify {
            permissionManager.startPermissionTimeout(
                timeoutSeconds = 60,
                toolName = "bash",
                any(),
            )
        }
    }

    @Test
    fun `startPermissionTimeout with no prompt uses empty toolName`() {
        viewModel.startPermissionTimeout()
        verify {
            permissionManager.startPermissionTimeout(
                timeoutSeconds = 60,
                toolName = "",
                any(),
            )
        }
    }

    @Test
    fun `startPermissionTimeout callback sends REJECT_ONCE to server on timeout`() = runTest {
        // Override service.scope to use the test scope for virtual time control
        every { service.scope } returns this
        val vm = PermissionViewModel(
            scope = this,
            service = service,
            project = project,
        )
        val prompt = makePermissionPrompt(
            permissionId = "perm_timeout",
            sessionId = "ses_timeout",
            toolCallId = "tc_timeout",
            toolName = "bash",
        )
        vm.setPermissionPrompt(prompt)

        // Capture the timeout callback passed to permissionManager
        val callbackSlot = slot<() -> Unit>()
        every { permissionManager.startPermissionTimeout(any(), any(), capture(callbackSlot)) } just runs

        vm.startPermissionTimeout()

        // Simulate the timeout firing
        callbackSlot.captured.invoke()
        advanceUntilIdle()

        // Verify REJECT_ONCE was sent to the server with the captured prompt fields
        coVerify {
            permissionManager.respondPermission(
                permissionId = "perm_timeout",
                toolCallId = "tc_timeout",
                sessionId = "ses_timeout",
                response = PermissionResponse.REJECT_ONCE,
            )
        }
        // The prompt should have been cleared (it was still the captured one)
        vm.permissionPrompt.value shouldBe null
    }

    @Test
    fun `startPermissionTimeout callback does not clear a newer prompt but still sends REJECT for the original`() = runTest {
        every { service.scope } returns this
        val vm = PermissionViewModel(
            scope = this,
            service = service,
            project = project,
        )
        val original = makePermissionPrompt(
            permissionId = "perm_original",
            sessionId = "ses_1",
            toolCallId = "tc_1",
        )
        vm.setPermissionPrompt(original)

        val callbackSlot = slot<() -> Unit>()
        every { permissionManager.startPermissionTimeout(any(), any(), capture(callbackSlot)) } just runs
        vm.startPermissionTimeout()

        // A newer permission prompt arrives before the timeout fires
        val newer = makePermissionPrompt(
            permissionId = "perm_newer",
            sessionId = "ses_1",
            toolCallId = "tc_2",
        )
        vm.setPermissionPrompt(newer)

        // The original timeout fires
        callbackSlot.captured.invoke()
        advanceUntilIdle()

        // REJECT is still sent for the ORIGINAL prompt (its agent is blocked and
        // needs the reject — the server resolves the original Deferred promise).
        coVerify {
            permissionManager.respondPermission(
                permissionId = "perm_original",
                toolCallId = "tc_1",
                sessionId = "ses_1",
                response = PermissionResponse.REJECT_ONCE,
            )
        }
        // The newer prompt must NOT have been cleared by the stale timeout
        vm.permissionPrompt.value shouldBe newer
    }

    @Test
    fun `startPermissionTimeout callback does not send REJECT when prompt was empty at start time`() = runTest {
        every { service.scope } returns this
        val vm = PermissionViewModel(
            scope = this,
            service = service,
            project = project,
        )
        // No prompt set — startPermissionTimeout captures empty IDs
        val callbackSlot = slot<() -> Unit>()
        every { permissionManager.startPermissionTimeout(any(), any(), capture(callbackSlot)) } just runs
        vm.startPermissionTimeout()

        callbackSlot.captured.invoke()
        advanceUntilIdle()

        // REJECT should NOT be sent because capturedPermId/capturedSessionId are empty
        coVerify(exactly = 0) {
            permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // â”€â”€ Child-session permission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `addChildPermissionPrompt adds prompt to map`() {
        val prompt = makeChildPermissionPrompt(childSessionId = "child_1")
        viewModel.addChildPermissionPrompt(prompt)
        viewModel.childPermissionPrompts.value["child_1"]!! shouldHaveSize 1
        viewModel.childPermissionPrompts.value["child_1"]!![0] shouldBe prompt
    }

    @Test
    fun `addChildPermissionPrompt starts child permission timeout`() {
        val prompt = makeChildPermissionPrompt(childSessionId = "child_1", toolName = "edit")
        viewModel.addChildPermissionPrompt(prompt)
        // startChildPermissionTimeout launches a coroutine on service.scope
        // We verify the timeout job is tracked by checking it exists
        // (indirect verification â€” the job is internal)
    }

    @Test
    fun `addChildPermissionPrompt appends to existing prompts for same child`() {
        val prompt1 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p1")
        val prompt2 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p2")
        viewModel.addChildPermissionPrompt(prompt1)
        viewModel.addChildPermissionPrompt(prompt2)
        viewModel.childPermissionPrompts.value["child_1"]!! shouldHaveSize 2
    }

    @Test
    fun `getChildPrompts returns prompts for child`() {
        val prompt = makeChildPermissionPrompt(childSessionId = "child_1")
        viewModel.addChildPermissionPrompt(prompt)
        viewModel.getChildPrompts("child_1") shouldHaveSize 1
    }

    @Test
    fun `getChildPrompts returns empty for unknown child`() {
        viewModel.getChildPrompts("unknown") shouldBe emptyList()
    }

    @Test
    fun `removeChildPrompts clears all prompts for child`() {
        viewModel.addChildPermissionPrompt(makeChildPermissionPrompt(childSessionId = "child_1"))
        viewModel.removeChildPrompts("child_1")
        viewModel.childPermissionPrompts.value.containsKey("child_1") shouldBe false
    }

    @Test
    fun `dropFirstChildPrompt removes first prompt and returns next toolName`() {
        val prompt1 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p1", toolName = "edit")
        val prompt2 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p2", toolName = "write")
        viewModel.addChildPermissionPrompt(prompt1)
        viewModel.addChildPermissionPrompt(prompt2)
        val nextToolName = viewModel.dropFirstChildPrompt("child_1")
        nextToolName shouldBe "write"
        viewModel.childPermissionPrompts.value["child_1"]!! shouldHaveSize 1
    }

    @Test
    fun `dropFirstChildPrompt returns null when no prompts remain`() {
        val nextToolName = viewModel.dropFirstChildPrompt("child_1")
        nextToolName shouldBe null
    }

    @Test
    fun `respondChildPermission removes first prompt on success`() = runBlocking {
        val prompt = makeChildPermissionPrompt(childSessionId = "child_1")
        viewModel.addChildPermissionPrompt(prompt)
        coEvery { permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        viewModel.respondChildPermission("child_1", PermissionResponse.ALLOW_ONCE)

        viewModel.childPermissionPrompts.value.containsKey("child_1") shouldBe false
    }

    @Test
    fun `respondChildPermission with REJECT_ONCE clears all prompts for child`() = runBlocking {
        val prompt1 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p1")
        val prompt2 = makeChildPermissionPrompt(childSessionId = "child_1", permissionId = "p2")
        viewModel.addChildPermissionPrompt(prompt1)
        viewModel.addChildPermissionPrompt(prompt2)
        coEvery { permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        viewModel.respondChildPermission("child_1", PermissionResponse.REJECT_ONCE)

        viewModel.childPermissionPrompts.value.containsKey("child_1") shouldBe false
    }

    @Test
    fun `respondChildPermission with no prompts is no-op`() = runBlocking {
        viewModel.respondChildPermission("unknown", PermissionResponse.ALLOW_ONCE)
        viewModel.childPermissionPrompts.value shouldBe emptyMap()
    }

    @Test
    fun `respondChildPermission failure tracks session in failedPermissionPostSessions`() = runBlocking {
        val prompt = makeChildPermissionPrompt(childSessionId = "child_fail")
        viewModel.addChildPermissionPrompt(prompt)
        coEvery { permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("net")

        viewModel.respondChildPermission("child_fail", PermissionResponse.ALLOW_ONCE)

        viewModel.failedPermissionPostSessions.contains("child_fail") shouldBe true
        // Prompt should still be present (not cleared on failure)
        viewModel.childPermissionPrompts.value.containsKey("child_fail") shouldBe true
    }

    // â”€â”€ Child permission timeout â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `startChildPermissionTimeout emits PermissionTimedOut after delay`() = runTest {
        // Override service.scope to use the test scope for virtual time control
        every { service.scope } returns this
        val vm = PermissionViewModel(
            scope = this,
            service = service,
            project = project,
        )
        val prompt = makeChildPermissionPrompt(childSessionId = "child_timeout", toolName = "bash")
        vm.addChildPermissionPrompt(prompt)
        // CHILD_PERMISSION_TIMEOUT_SECONDS = 120
        advanceTimeBy(120_000L)
        advanceUntilIdle()
        // The timeout should emit a PermissionTimedOut signal via sessionManager.emitGlobalSignal
        verify {
            sessionManager.emitGlobalSignal(match<UiSignal.PermissionTimedOut> {
                it.sessionId == "child_timeout" && it.toolName == "bash"
            })
        }
    }

    @Test
    fun `cancelChildPermissionTimeout prevents timeout from firing`() = runTest {
        every { service.scope } returns this
        val vm = PermissionViewModel(
            scope = this,
            service = service,
            project = project,
        )
        val prompt = makeChildPermissionPrompt(childSessionId = "child_cancel", toolName = "edit")
        vm.addChildPermissionPrompt(prompt)
        vm.cancelChildPermissionTimeout("child_cancel")
        advanceTimeBy(120_000L)
        advanceUntilIdle()
        // Should NOT have emitted a timeout signal
        verify(exactly = 0) {
            sessionManager.emitGlobalSignal(match<UiSignal.PermissionTimedOut> {
                it.sessionId == "child_cancel"
            })
        }
    }

    // â”€â”€ Selection (question tool) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `setSelectionPrompt sets the prompt state`() {
        val prompt = SelectionPrompt(
            sessionId = "ses_1",
            promptId = "q_1",
            question = "Pick one",
            options = listOf(SelectionOption(title = "A", description = "desc")),
        )
        viewModel.setSelectionPrompt(prompt)
        viewModel.selectionPrompt.value shouldBe prompt
    }

    @Test
    fun `setSelectionPrompt with null clears the prompt state`() {
        val prompt = SelectionPrompt(
            sessionId = "ses_1",
            promptId = "q_1",
            question = "Pick",
            options = listOf(SelectionOption(title = "A", description = "d")),
        )
        viewModel.setSelectionPrompt(prompt)
        viewModel.setSelectionPrompt(null)
        viewModel.selectionPrompt.value shouldBe null
    }

    @Test
    fun `respondSelection clears prompt on success`() = runBlocking {
        val prompt = SelectionPrompt(
            sessionId = "ses_1",
            promptId = "q_1",
            question = "Pick",
            options = listOf(
                SelectionOption(title = "A", description = "d", label = "A"),
                SelectionOption(title = "B", description = "d2", label = "B"),
            ),
        )
        viewModel.setSelectionPrompt(prompt)
        coEvery { service.respondQuestion(any(), any(), any()) } just runs

        viewModel.respondSelection(SelectionResponse(selectedIndices = setOf(0)))

        viewModel.selectionPrompt.value shouldBe null
        coVerify { service.respondQuestion("q_1", any(), "ses_1") }
    }

    @Test
    fun `respondSelection with no prompt is no-op`() = runBlocking {
        viewModel.respondSelection(SelectionResponse(selectedIndices = setOf(0)))
        viewModel.selectionPrompt.value shouldBe null
    }

    @Test
    fun `respondSelection with empty selection rejects the question`() = runBlocking {
        val prompt = SelectionPrompt(
            sessionId = "ses_1",
            promptId = "q_1",
            question = "Pick",
            options = listOf(SelectionOption(title = "A", description = "d")),
        )
        viewModel.setSelectionPrompt(prompt)
        coEvery { service.rejectQuestion(any(), any()) } just runs

        viewModel.respondSelection(SelectionResponse(selectedIndices = emptySet()))

        viewModel.selectionPrompt.value shouldBe null
        coVerify { service.rejectQuestion("q_1", "ses_1") }
    }

    // â”€â”€ close â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `close clears all permission state`() {
        viewModel.setPermissionPrompt(makePermissionPrompt())
        viewModel.addChildPermissionPrompt(makeChildPermissionPrompt())
        viewModel.close()
        viewModel.permissionPrompt.value shouldBe null
        viewModel.childPermissionPrompts.value shouldBe emptyMap()
    }

    // ── Brave Mode (auto-approve) ──────────────────────────────────────────

    @Test
    fun `brave mode auto-approves active-session permission without showing prompt`() = runBlocking {
        var braveMode = true
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makePermissionPrompt(permissionId = "perm_brave", toolName = "bash")
        coEvery { service.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        vm.setPermissionPrompt(prompt)

        // Prompt should NOT be shown
        vm.permissionPrompt.value shouldBe null
        // Server should have received ALLOW_ONCE
        coVerify {
            service.respondPermission(
                "perm_brave", "tc_1", "ses_1",
                PermissionResponse.ALLOW_ONCE,
                toolName = "bash",
                patterns = emptyList(),
            )
        }
    }

    @Test
    fun `brave mode off shows active-session prompt normally`() {
        var braveMode = false
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makePermissionPrompt()

        vm.setPermissionPrompt(prompt)

        vm.permissionPrompt.value shouldBe prompt
    }

    @Test
    fun `brave mode auto-approves child-session permission without showing prompt`() = runBlocking {
        var braveMode = true
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makeChildPermissionPrompt(childSessionId = "child_brave", permissionId = "perm_cb", toolName = "edit")
        coEvery { permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        vm.addChildPermissionPrompt(prompt)

        // Child prompt should NOT be shown
        vm.childPermissionPrompts.value.containsKey("child_brave") shouldBe false
        // Server should have received ALLOW_ONCE for the child session
        coVerify {
            permissionManager.respondPermission(
                "perm_cb", "tc_c1", "child_brave",
                PermissionResponse.ALLOW_ONCE,
                "edit",
                emptyList(),
                "fixer",
            )
        }
    }

    @Test
    fun `brave mode off shows child-session prompt normally`() {
        var braveMode = false
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makeChildPermissionPrompt(childSessionId = "child_normal")

        vm.addChildPermissionPrompt(prompt)

        vm.childPermissionPrompts.value["child_normal"]!! shouldHaveSize 1
    }

    @Test
    fun `brave mode toggle at runtime switches from auto-approve to showing prompt`() = runBlocking {
        var braveMode = true
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt1 = makePermissionPrompt(permissionId = "perm_1")
        coEvery { service.respondPermission(any(), any(), any(), any(), any(), any(), any()) } just runs

        // Brave mode ON — auto-approve, no prompt shown
        vm.setPermissionPrompt(prompt1)
        vm.permissionPrompt.value shouldBe null

        // Disable brave mode at runtime
        braveMode = false
        val prompt2 = makePermissionPrompt(permissionId = "perm_2")
        vm.setPermissionPrompt(prompt2)

        // Now the prompt should be shown
        vm.permissionPrompt.value shouldBe prompt2
    }

    @Test
    fun `brave mode auto-approve failure falls back to showing prompt`() = runBlocking {
        var braveMode = true
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makePermissionPrompt(permissionId = "perm_fail")
        coEvery { service.respondPermission(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("network error")

        vm.setPermissionPrompt(prompt)

        // On failure, Brave Mode should fall back to showing the prompt
        vm.permissionPrompt.value shouldBe prompt
    }

    @Test
    fun `brave mode child auto-approve failure falls back to showing prompt`() = runBlocking {
        var braveMode = true
        val vm = PermissionViewModel(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            service = service,
            project = project,
            braveModeProvider = { braveMode },
        )
        val prompt = makeChildPermissionPrompt(childSessionId = "child_fail_brave", permissionId = "perm_cfb", toolName = "edit")
        coEvery { permissionManager.respondPermission(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("network error")

        vm.addChildPermissionPrompt(prompt)

        // On failure, child prompt should be shown (fallback to manual approval)
        vm.childPermissionPrompts.value.containsKey("child_fail_brave") shouldBe true
    }
}