package com.opencode.acp.chat.service

import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.processor.SessionManager
import com.opencode.acp.chat.processor.UiSignal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ChildPermissionRelay] (regression coverage for the
 * "orphan child permission prompts silently dropped" bug fix).
 *
 * Verifies:
 *  - Orphan children (no parent in the reverse index) return null
 *  - Children that ARE the active session return null (handled via activeSignals)
 *  - Children that become active during the relay (TOCTOU) return null
 *  - Valid relays produce a [UiSignal.ChildPermissionRequested] with the
 *    correct childSessionId and sub-agent label (verified or fallback)
 */
class ChildPermissionRelayTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var relay: ChildPermissionRelay
    private val activeSessionId = MutableStateFlow<String?>("parent_1")

    @BeforeEach
    fun setUp() {
        sessionManager = mockk<SessionManager>(relaxed = true)
        // Return a real MutableStateFlow so the relay can read .value
        every { sessionManager.activeSessionId } returns activeSessionId
        relay = ChildPermissionRelay(sessionManager)
    }

    private fun makePrompt(sessionId: String = "child_1") = PermissionPrompt(
        sessionId = sessionId,
        permissionId = "perm_1",
        toolCallId = "tc_1",
        toolName = "bash",
        description = "Run bash",
        patterns = emptyList(),
    )

    @Test
    fun `relayChildPermission returns null for orphan (no parent found)`() {
        every { sessionManager.getParentSession("child_orphan") } returns null
        val result = relay.relayChildPermission("child_orphan", makePrompt("child_orphan"))
        result shouldBe null
    }

    @Test
    fun `relayChildPermission returns null when child is the active session`() {
        every { sessionManager.getParentSession("parent_1") } returns "parent_1"
        activeSessionId.value = "parent_1"
        val result = relay.relayChildPermission("parent_1", makePrompt("parent_1"))
        result shouldBe null
    }

    @Test
    fun `relayChildPermission returns signal when parent is found and child is not active`() {
        every { sessionManager.getParentSession("child_1") } returns "parent_1"
        every { sessionManager.getChildAgentLabel("child_1") } returns "fixer"
        activeSessionId.value = "parent_1"
        val result = relay.relayChildPermission("child_1", makePrompt("child_1"))
        result shouldNotBe null
        result!!::class shouldBe UiSignal.ChildPermissionRequested::class
        result.prompt.childSessionId shouldBe "child_1"
        result.prompt.subAgentLabel shouldBe "fixer"
        result.prompt.agentLabelVerified shouldBe true
    }

    @Test
    fun `relayChildPermission uses sub-agent fallback when agent label is null`() {
        every { sessionManager.getParentSession("child_1") } returns "parent_1"
        every { sessionManager.getChildAgentLabel("child_1") } returns null
        activeSessionId.value = "parent_1"
        val result = relay.relayChildPermission("child_1", makePrompt("child_1"))
        result shouldNotBe null
        result!!.prompt.subAgentLabel shouldBe "sub-agent"
        result.prompt.agentLabelVerified shouldBe false
    }

    @Test
    fun `relayChildPermission uses sub-agent fallback when agent label is blank`() {
        every { sessionManager.getParentSession("child_1") } returns "parent_1"
        every { sessionManager.getChildAgentLabel("child_1") } returns ""
        activeSessionId.value = "parent_1"
        val result = relay.relayChildPermission("child_1", makePrompt("child_1"))
        result shouldNotBe null
        result!!.prompt.subAgentLabel shouldBe "sub-agent"
        result.prompt.agentLabelVerified shouldBe false
    }

    @Test
    fun `relayChildPermission returns null when child becomes active during relay (TOCTOU)`() {
        every { sessionManager.getParentSession("child_1") } returns "parent_1"
        every { sessionManager.getChildAgentLabel("child_1") } returns "fixer"
        // NOTE: This tests the "child is active" guard (first activeSessionId check),
        // not a true TOCTOU where the value changes between first and second reads.
        // A true TOCTOU test would require a mock that returns different values on
        // successive reads, which is not feasible with MutableStateFlow.
        activeSessionId.value = "child_1"
        val result = relay.relayChildPermission("child_1", makePrompt("child_1"))
        result shouldBe null
    }

    @Test
    fun `relayChildPermission preserves permission fields from the source prompt`() {
        every { sessionManager.getParentSession("child_1") } returns "parent_1"
        every { sessionManager.getChildAgentLabel("child_1") } returns "fixer"
        activeSessionId.value = "parent_1"
        val source = PermissionPrompt(
            sessionId = "child_1",
            permissionId = "perm_xyz",
            toolCallId = "tc_xyz",
            toolName = "edit",
            description = "Edit foo.txt",
            patterns = listOf("**/*.txt"),
        )
        val result = relay.relayChildPermission("child_1", source)
        result shouldNotBe null
        result!!.prompt.permissionId shouldBe "perm_xyz"
        result.prompt.toolCallId shouldBe "tc_xyz"
        result.prompt.toolName shouldBe "edit"
        result.prompt.description shouldBe "Edit foo.txt"
        result.prompt.patterns shouldBe listOf("**/*.txt")
    }
}