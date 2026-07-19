package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.OpenCodeNotifications
import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.service.OpenCodeServiceApi
import com.opencode.acp.chat.service.PermissionManager
import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PermissionSideEffectHandler] (council review Phase 2 — testability gap).
 *
 * Tests the two most complex signal-pipeline handlers — `handlePermissionReplied` and
 * `handlePermissionTimedOut` — extracted from [SignalSideEffectExecutor]. These contain
 * multi-branch cascade logic (FIFO child-prompt dropping, timeout restart, failed-POST
 * notification, NonCancellable reject POSTs) that previously had ZERO unit tests.
 *
 * Uses MockK to mock [OpenCodeServiceApi], [Project], and [PermissionViewModel], and
 * `mockkObject(OpenCodeNotifications)` to verify static notification calls. Uses [runTest]
 * for virtual time control over the `service.scope.launch { withContext(NonCancellable) { ... } }`
 * coroutine in the timeout-reject path.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermissionSideEffectHandlerTest {

    private lateinit var service: OpenCodeServiceApi
    private lateinit var project: Project
    private lateinit var permissionManager: PermissionManager
    private lateinit var permissionViewModel: PermissionViewModel
    private lateinit var handler: PermissionSideEffectHandler

    @BeforeEach
    fun setUp() {
        service = mockk<OpenCodeServiceApi>(relaxed = true)
        project = mockk<Project>(relaxed = true)
        permissionManager = mockk<PermissionManager>(relaxed = true)
        permissionViewModel = mockk<PermissionViewModel>(relaxed = true)

        every { service.permissionManager } returns permissionManager

        // Mock OpenCodeNotifications object — all notification calls are no-ops by default,
        // individual tests verify the specific calls.
        mockkObject(OpenCodeNotifications)
        every { OpenCodeNotifications.notifyPermissionTimedOut(any(), any()) } just runs
        every { OpenCodeNotifications.notifyPermissionProcessedDespiteError(any(), any()) } just runs

        handler = PermissionSideEffectHandler(service, project, permissionViewModel)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OpenCodeNotifications)
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
        childSessionId: String = "ses_1",
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
        subAgentLabel = "sub-agent",
        agentLabelVerified = false,
    )

    private fun stubPermissionPrompt(prompt: PermissionPrompt?) {
        every { permissionViewModel.permissionPrompt } returns MutableStateFlow(prompt)
    }

    private fun stubChildPrompts(map: Map<String, List<ChildPermissionPrompt>>) {
        every { permissionViewModel.childPermissionPrompts } returns MutableStateFlow(map)
    }

    private fun stubFailedPostSessions(set: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>) {
        every { permissionViewModel.failedPermissionPostSessions } returns set
    }

    // ── handlePermissionReplied ─────────────────────────────────────────────

    @Test
    fun `replied permission matches active prompt clears it and cancels timeout`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_1"))
        stubChildPrompts(emptyMap())
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify { permissionViewModel.setPermissionPrompt(null) }
        verify { permissionManager.cancelPermissionTimeout() }
    }

    @Test
    fun `replied permission does not match active prompt does not clear it`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_1"))
        stubChildPrompts(emptyMap())
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())

        handler.handlePermissionReplied("perm_OTHER", "allow", "ses_1")

        verify(exactly = 0) { permissionViewModel.setPermissionPrompt(any()) }
        verify(exactly = 0) { permissionManager.cancelPermissionTimeout() }
    }

    @Test
    fun `replied with no active prompt does not clear or cancel`() {
        stubPermissionPrompt(null)
        stubChildPrompts(emptyMap())
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify(exactly = 0) { permissionViewModel.setPermissionPrompt(any()) }
        verify(exactly = 0) { permissionManager.cancelPermissionTimeout() }
    }

    @Test
    fun `session in failedPermissionPostSessions notifies and removes`() {
        stubPermissionPrompt(null)
        stubChildPrompts(emptyMap())
        val failedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add("ses_1") }
        stubFailedPostSessions(failedSet)

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify { OpenCodeNotifications.notifyPermissionProcessedDespiteError(project, "ses_1") }
        failedSet.contains("ses_1") shouldBe false
    }

    @Test
    fun `reject reply cascades - removes all child prompts and cancels timeout`() {
        stubPermissionPrompt(null)
        val prompt = makeChildPermissionPrompt(toolName = "edit")
        stubChildPrompts(mapOf("ses_1" to listOf(prompt)))
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())

        handler.handlePermissionReplied("perm_1", "reject", "ses_1")

        verify { permissionViewModel.removeChildPrompts("ses_1") }
        verify { permissionViewModel.cancelChildPermissionTimeout("ses_1") }
        verify(exactly = 0) { permissionViewModel.dropFirstChildPrompt(any()) }
        verify(exactly = 0) { permissionViewModel.startChildPermissionTimeout(any(), any()) }
    }

    @Test
    fun `non-reject reply with multiple child prompts drops only first and restarts timeout`() {
        stubPermissionPrompt(null)
        val prompt1 = makeChildPermissionPrompt(permissionId = "perm_c1", toolName = "tool1")
        val prompt2 = makeChildPermissionPrompt(permissionId = "perm_c2", toolName = "tool2")
        stubChildPrompts(mapOf("ses_1" to listOf(prompt1, prompt2)))
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())
        every { permissionViewModel.dropFirstChildPrompt("ses_1") } returns "tool2"

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify { permissionViewModel.dropFirstChildPrompt("ses_1") }
        verify { permissionViewModel.startChildPermissionTimeout("ses_1", "tool2") }
        verify(exactly = 0) { permissionViewModel.cancelChildPermissionTimeout(any()) }
        verify(exactly = 0) { permissionViewModel.removeChildPrompts(any()) }
    }

    @Test
    fun `non-reject reply with only one child prompt drops it and cancels timeout`() {
        stubPermissionPrompt(null)
        val prompt1 = makeChildPermissionPrompt(permissionId = "perm_c1", toolName = "tool1")
        stubChildPrompts(mapOf("ses_1" to listOf(prompt1)))
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())
        every { permissionViewModel.dropFirstChildPrompt("ses_1") } returns null

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify { permissionViewModel.dropFirstChildPrompt("ses_1") }
        verify { permissionViewModel.cancelChildPermissionTimeout("ses_1") }
        verify(exactly = 0) { permissionViewModel.startChildPermissionTimeout(any(), any()) }
        verify(exactly = 0) { permissionViewModel.removeChildPrompts(any()) }
    }

    @Test
    fun `no child prompts for session skips child handling`() {
        stubPermissionPrompt(null)
        stubChildPrompts(emptyMap())
        stubFailedPostSessions(java.util.concurrent.ConcurrentHashMap.newKeySet())

        handler.handlePermissionReplied("perm_1", "allow", "ses_1")

        verify(exactly = 0) { permissionViewModel.removeChildPrompts(any()) }
        verify(exactly = 0) { permissionViewModel.dropFirstChildPrompt(any()) }
        verify(exactly = 0) { permissionViewModel.cancelChildPermissionTimeout(any()) }
        verify(exactly = 0) { permissionViewModel.startChildPermissionTimeout(any(), any()) }
    }

    // ── handlePermissionTimedOut ────────────────────────────────────────────

    @Test
    fun `timeout notifies user`() {
        stubPermissionPrompt(null)
        every { permissionViewModel.getChildPrompts(any()) } returns emptyList()

        handler.handlePermissionTimedOut("perm_1", "ses_1", "bash")

        verify { OpenCodeNotifications.notifyPermissionTimedOut(project, "bash") }
    }

    @Test
    fun `timeout with matching active permissionId clears active prompt`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_1"))
        every { permissionViewModel.getChildPrompts(any()) } returns emptyList()

        handler.handlePermissionTimedOut("perm_1", "ses_1", "bash")

        verify { permissionViewModel.setPermissionPrompt(null) }
    }

    @Test
    fun `timeout with non-matching permissionId does not clear active prompt`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_OTHER"))
        every { permissionViewModel.getChildPrompts(any()) } returns emptyList()

        handler.handlePermissionTimedOut("perm_1", "ses_1", "bash")

        verify(exactly = 0) { permissionViewModel.setPermissionPrompt(any()) }
    }

    @Test
    fun `timeout with empty permissionId does not clear active prompt (child timeout)`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_1"))
        every { permissionViewModel.getChildPrompts(any()) } returns emptyList()

        handler.handlePermissionTimedOut("", "ses_child", "bash")

        verify(exactly = 0) { permissionViewModel.setPermissionPrompt(any()) }
    }

    @Test
    fun `timeout with child session POSTs reject for each pending prompt then clears`() = runTest {
        stubPermissionPrompt(null)
        val prompt1 = makeChildPermissionPrompt(permissionId = "perm_c1", toolCallId = "tc_c1", toolName = "tool1")
        val prompt2 = makeChildPermissionPrompt(permissionId = "perm_c2", toolCallId = "tc_c2", toolName = "tool2")
        every { permissionViewModel.getChildPrompts("ses_child") } returns listOf(prompt1, prompt2)
        // Use the TestScope's scope so the launch actually executes during runTest.
        every { service.scope } returns CoroutineScope(coroutineContext + SupervisorJob())

        handler.handlePermissionTimedOut("", "ses_child", "bash")
        advanceUntilIdle()

        coVerify(exactly = 2) {
            permissionManager.respondPermission(
                any(), any(), "ses_child", PermissionResponse.REJECT_ONCE,
            )
        }
        verify { permissionViewModel.removeChildPrompts("ses_child") }
        verify { permissionViewModel.cancelChildPermissionTimeout("ses_child") }
    }

    @Test
    fun `timeout with child session but no pending prompts skips POST and clears`() {
        stubPermissionPrompt(null)
        every { permissionViewModel.getChildPrompts("ses_child") } returns emptyList()

        handler.handlePermissionTimedOut("", "ses_child", "bash")

        coVerify(exactly = 0) {
            permissionManager.respondPermission(any(), any(), any(), any())
        }
        verify { permissionViewModel.removeChildPrompts("ses_child") }
        verify { permissionViewModel.cancelChildPermissionTimeout("ses_child") }
    }

    @Test
    fun `timeout with empty sessionId skips child handling`() {
        stubPermissionPrompt(makePermissionPrompt(permissionId = "perm_1"))

        handler.handlePermissionTimedOut("perm_1", "", "bash")

        verify(exactly = 0) { permissionViewModel.getChildPrompts(any()) }
        verify(exactly = 0) { permissionViewModel.removeChildPrompts(any()) }
        verify(exactly = 0) { permissionViewModel.cancelChildPermissionTimeout(any()) }
    }
}