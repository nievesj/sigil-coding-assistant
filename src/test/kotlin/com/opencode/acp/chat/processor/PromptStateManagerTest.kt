package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.SelectionOption
import com.opencode.acp.chat.model.SelectionPrompt
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PromptStateManager] (TDD §7.1.1).
 *
 * No IntelliJ Platform dependencies. [PromptStateManager] operates on shared
 * [MutableSharedFlow] / [MutableStateFlow] instances. setPermission and
 * setSelection do NOT emit signals (the caller is responsible), so these
 * tests only verify the StateFlow values and the hasPendingPermission flag.
 */
class PromptStateManagerTest {

    private lateinit var signals: MutableSharedFlow<UiSignal>
    private lateinit var pendingPermission: MutableStateFlow<PermissionPrompt?>
    private lateinit var pendingSelection: MutableStateFlow<SelectionPrompt?>
    private lateinit var manager: PromptStateManager

    @BeforeEach
    fun setUp() {
        signals = MutableSharedFlow(extraBufferCapacity = 15)
        pendingPermission = MutableStateFlow(null)
        pendingSelection = MutableStateFlow(null)
        manager = PromptStateManager(
            signals = signals,
            pendingPermission = pendingPermission,
            pendingSelection = pendingSelection,
            sessionId = "test-session",
        )
    }

    private fun makePermissionPrompt(): PermissionPrompt = PermissionPrompt(
        sessionId = "test-session",
        permissionId = "perm_1",
        toolCallId = "tc_1",
        toolName = "read",
        description = "Reading file",
        patterns = listOf("**/*.kt"),
    )

    private fun makeSelectionPrompt(): SelectionPrompt = SelectionPrompt(
        sessionId = "test-session",
        promptId = "que_1",
        question = "What is your favorite animal?",
        subtitle = "Animal choice",
        options = listOf(SelectionOption(title = "Dog", description = "Loyal companion")),
        allowCustomInput = true,
        multiSelect = false,
    )

    @Test
    fun `setPermission - prompt set, hasPendingPermission true`() {
        val prompt = makePermissionPrompt()

        manager.setPermission(prompt)

        manager.hasPendingPermission shouldBe true
        pendingPermission.value shouldBe prompt
    }

    @Test
    fun `setPendingPermission false - flag cleared, pendingPermission null`() {
        manager.setPermission(makePermissionPrompt())
        manager.hasPendingPermission shouldBe true

        manager.setPendingPermission(false)

        manager.hasPendingPermission shouldBe false
        pendingPermission.value shouldBe null
    }

    @Test
    fun `setSelection - prompt set`() {
        val prompt = makeSelectionPrompt()

        manager.setSelection(prompt)

        pendingSelection.value shouldBe prompt
    }

    @Test
    fun `clearSelection - pendingSelection null`() {
        manager.setSelection(makeSelectionPrompt())
        pendingSelection.value shouldNotBe null

        manager.clearSelection()

        pendingSelection.value shouldBe null
    }
}