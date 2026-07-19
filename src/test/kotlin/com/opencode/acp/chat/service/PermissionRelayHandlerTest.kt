package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.PermissionResponse
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PermissionRelayHandler] — the Brave Mode relay-point
 * auto-approve and orphan-retry fallback logic extracted from
 * [OpenCodeService.startGlobalSignalCollection].
 *
 * Covers six scenarios:
 *  1. Brave Mode ON + child permission → auto-approve at relay point (no relay)
 *  2. Brave Mode OFF + relay succeeds → ChildPermissionRequested emitted
 *  3. Brave Mode OFF + orphan (relay fails, loadSessions retry succeeds) → emitted
 *  4. Brave Mode OFF + orphan (retry also fails) → fallback synthetic prompt emitted
 *  5. Active session permission request → NOT handled (returns false)
 *  6. Brave Mode ON + auto-approve POST fails → falls back to relay
 *
 * Uses MockK for [SessionManager], [PermissionManager], [ChildPermissionRelay].
 * The [braveModeProvider] lambda is injected directly (no static mock needed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRelayHandlerTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var childPermissionRelay: ChildPermissionRelay
    private lateinit var handler: PermissionRelayHandler
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        sessionManager = mockk<SessionManager>(relaxed = true)
        permissionManager = mockk<PermissionManager>(relaxed = true)
        childPermissionRelay = mockk<ChildPermissionRelay>(relaxed = true)
        // NOTE: Dispatchers.Unconfined executes coroutines synchronously, which
        // masks async race conditions that would occur with Dispatchers.Default.
        // The tests pass because everything is synchronous, but production may have
        // timing-dependent bugs. If async races are suspected, add a test variant
        // using Dispatchers.Default or StandardTestDispatcher.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Default: active session is "parent_1" so child requests are non-active.
        every { sessionManager.activeSessionId } returns
            kotlinx.coroutines.flow.MutableStateFlow("parent_1")
        every { sessionManager.emitGlobalSignal(any()) } just runs
        every { sessionManager.markChildPendingPermission(any()) } just runs
        coEvery { sessionManager.loadSessions(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun makePrompt(sessionId: String = "child_1") = PermissionPrompt(
        sessionId = sessionId,
        permissionId = "perm_1",
        toolCallId = "tc_1",
        toolName = "bash",
        description = "Run bash",
        patterns = listOf("**/*.sh"),
    )

    private fun makeSignal(sessionId: String = "child_1") =
        UiSignal.PermissionRequested(makePrompt(sessionId))

    // ── Test 1: Brave Mode ON + child permission → auto-approve ─────────────

    @Test
    fun `Brave Mode ON auto-approves child permission at relay point without relaying`() = runTest {
        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { true },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        // respondPermission called with ALLOW_ONCE
        coVerify {
            permissionManager.respondPermission(
                permissionId = "perm_1",
                toolCallId = "tc_1",
                sessionId = "child_1",
                response = PermissionResponse.ALLOW_ONCE,
                toolName = "bash",
                patterns = listOf("**/*.sh"),
            )
        }
        // relayChildPermission NOT called (bypassed)
        verify(exactly = 0) { childPermissionRelay.relayChildPermission(any(), any()) }
        // No ChildPermissionRequested emitted to globalSignals
        verify(exactly = 0) { sessionManager.emitGlobalSignal(any()) }
    }

    // ── Test 2: Brave Mode OFF + relay succeeds → ChildPermissionRequested ──

    @Test
    fun `Brave Mode OFF relays to parent and emits ChildPermissionRequested`() = runTest {
        val relayedSignal = UiSignal.ChildPermissionRequested(
            ChildPermissionPrompt(
                childSessionId = "child_1",
                permissionId = "perm_1",
                toolCallId = "tc_1",
                toolName = "bash",
                description = "Run bash",
                patterns = listOf("**/*.sh"),
                subAgentLabel = "fixer",
                agentLabelVerified = true,
            )
        )
        every { childPermissionRelay.relayChildPermission("child_1", any()) } returns relayedSignal

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { false },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        verify(exactly = 1) { childPermissionRelay.relayChildPermission("child_1", any()) }
        verify(exactly = 1) { sessionManager.emitGlobalSignal(relayedSignal) }
        verify(exactly = 1) { sessionManager.markChildPendingPermission("child_1") }
        // respondPermission NOT called (no Brave Mode)
        coVerify(exactly = 0) {
            permissionManager.respondPermission(any(), any(), any(), any(), any(), any())
        }
    }

    // ── Test 3: Brave Mode OFF + orphan (retry succeeds) → emitted ──────────

    @Test
    fun `Brave Mode OFF orphan triggers loadSessions and retries relay`() = runTest {
        val relayedSignal = UiSignal.ChildPermissionRequested(
            ChildPermissionPrompt(
                childSessionId = "child_1",
                permissionId = "perm_1",
                toolCallId = "tc_1",
                toolName = "bash",
                description = "Run bash",
                patterns = listOf("**/*.sh"),
                subAgentLabel = "fixer",
                agentLabelVerified = true,
            )
        )
        // First relay returns null (orphan), second relay (after loadSessions) returns signal
        every { childPermissionRelay.relayChildPermission("child_1", any()) } returns null andThen relayedSignal

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { false },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        // loadSessions called once for the orphan retry
        coVerify(exactly = 1) { sessionManager.loadSessions(any()) }
        // relayChildPermission called twice (initial + retry)
        verify(exactly = 2) { childPermissionRelay.relayChildPermission("child_1", any()) }
        // Retry succeeded → emit signal
        verify(exactly = 1) { sessionManager.emitGlobalSignal(relayedSignal) }
        verify(exactly = 1) { sessionManager.markChildPendingPermission("child_1") }
    }

    // ── Test 4: Brave Mode OFF + orphan (retry also fails) → fallback ───────

    @Test
    fun `Brave Mode OFF orphan retry fails surfaces fallback synthetic ChildPermissionRequested`() = runTest {
        // Both relay attempts return null (orphan persists after loadSessions)
        every { childPermissionRelay.relayChildPermission("child_1", any()) } returns null

        // Install captor BEFORE invocation so we capture the first (and only) emit
        val signalSlot = mutableListOf<UiSignal>()
        every { sessionManager.emitGlobalSignal(capture(signalSlot)) } just runs

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { false },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        coVerify(exactly = 1) { sessionManager.loadSessions(any()) }
        verify(exactly = 2) { childPermissionRelay.relayChildPermission("child_1", any()) }

        // Should emit a ChildPermissionRequested with synthetic prompt
        signalSlot.size shouldBe 1
        val emitted = signalSlot[0]
        emitted::class shouldBe UiSignal.ChildPermissionRequested::class
        val fallback = (emitted as UiSignal.ChildPermissionRequested).prompt
        fallback.childSessionId shouldBe "child_1"
        fallback.permissionId shouldBe "perm_1"
        fallback.toolCallId shouldBe "tc_1"
        fallback.toolName shouldBe "bash"
        fallback.subAgentLabel shouldBe "sub-agent"
        fallback.agentLabelVerified shouldBe false
        verify(atLeast = 1) { sessionManager.markChildPendingPermission("child_1") }
    }

    // ── Test 5: Active session permission request → NOT handled ─────────────

    @Test
    fun `active session permission request is not handled by relay handler`() = runTest {
        // Active session IS the requesting session
        every { sessionManager.activeSessionId } returns
            kotlinx.coroutines.flow.MutableStateFlow("child_1")

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { true }, // Even with Brave Mode, active session is skipped
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        // Returns false — caller should handle via activeSignals
        handled shouldBe false
        verify(exactly = 0) { childPermissionRelay.relayChildPermission(any(), any()) }
        coVerify(exactly = 0) {
            permissionManager.respondPermission(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { sessionManager.emitGlobalSignal(any()) }
    }

    // ── Test 6: Brave Mode ON + auto-approve POST fails → fallback to relay ─

    @Test
    fun `Brave Mode ON auto-approve failure falls back to relay`() = runTest {
        // respondPermission throws → fallback to relay path
        coEvery {
            permissionManager.respondPermission(
                permissionId = "perm_1",
                toolCallId = "tc_1",
                sessionId = "child_1",
                response = PermissionResponse.ALLOW_ONCE,
                toolName = "bash",
                patterns = listOf("**/*.sh"),
            )
        } throws RuntimeException("server POST failed")

        val relayedSignal = UiSignal.ChildPermissionRequested(
            ChildPermissionPrompt(
                childSessionId = "child_1",
                permissionId = "perm_1",
                toolCallId = "tc_1",
                toolName = "bash",
                description = "Run bash",
                patterns = listOf("**/*.sh"),
                subAgentLabel = "sub-agent",
                agentLabelVerified = false,
            )
        )
        every { childPermissionRelay.relayChildPermission("child_1", any()) } returns relayedSignal

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { true },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        // Auto-approve was attempted
        coVerify(exactly = 1) {
            permissionManager.respondPermission(
                permissionId = "perm_1",
                toolCallId = "tc_1",
                sessionId = "child_1",
                response = PermissionResponse.ALLOW_ONCE,
                toolName = "bash",
                patterns = listOf("**/*.sh"),
            )
        }
        // Fallback: relay was called
        verify(exactly = 1) { childPermissionRelay.relayChildPermission("child_1", any()) }
        // Fallback signal emitted
        verify(exactly = 1) { sessionManager.emitGlobalSignal(relayedSignal) }
        verify(exactly = 1) { sessionManager.markChildPendingPermission("child_1") }
    }

    // ── Test 7: Brave Mode ON + auto-approve fails + relay fails → orphan fallback ─

    @Test
    fun `Brave Mode ON auto-approve fails and relay also fails surfaces fallback`() = runTest {
        // respondPermission throws → fallback to relay path
        coEvery {
            permissionManager.respondPermission(
                permissionId = "perm_1",
                toolCallId = "tc_1",
                sessionId = "child_1",
                response = PermissionResponse.ALLOW_ONCE,
                toolName = "bash",
                patterns = listOf("**/*.sh"),
            )
        } throws RuntimeException("server POST failed")

        // Both relay attempts return null (orphan persists after loadSessions)
        every { childPermissionRelay.relayChildPermission("child_1", any()) } returns null

        val signalSlot = mutableListOf<UiSignal>()
        every { sessionManager.emitGlobalSignal(capture(signalSlot)) } just runs

        handler = PermissionRelayHandler(
            scope = scope,
            sessionManager = sessionManager,
            permissionManager = permissionManager,
            childPermissionRelay = childPermissionRelay,
            braveModeProvider = { true },
        )

        val signal = makeSignal("child_1")
        val handled = handler.handlePermissionRequested("child_1", signal)
        advanceUntilIdle()

        handled shouldBe true
        // Auto-approve was attempted
        coVerify(exactly = 1) {
            permissionManager.respondPermission(any(), any(), any(), any(), any(), any())
        }
        // Fallback: relay was called (twice — initial + retry after loadSessions)
        verify(exactly = 2) { childPermissionRelay.relayChildPermission("child_1", any()) }
        // Fallback synthetic prompt emitted
        signalSlot.size shouldBe 1
        val emitted = signalSlot[0]
        emitted::class shouldBe UiSignal.ChildPermissionRequested::class
        val fallback = (emitted as UiSignal.ChildPermissionRequested).prompt
        fallback.childSessionId shouldBe "child_1"
        fallback.subAgentLabel shouldBe "sub-agent"
        fallback.agentLabelVerified shouldBe false
    }
}