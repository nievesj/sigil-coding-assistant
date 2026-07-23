package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.ChildPermissionPrompt
import com.opencode.acp.chat.model.PermissionPrompt
import com.opencode.acp.chat.model.SelectionPrompt
import com.opencode.acp.chat.model.SelectionOption
import com.opencode.acp.chat.processor.UiSignal
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SignalRouter] (TDD §9 step 5, Phase 3 — highest-value test).
 *
 * The [SignalRouter] is a pure signal-to-effect mapping: it receives [UiSignal]
 * events and emits [SignalEffect] values. It owns no state and holds no
 * mutable manager references.
 *
 * The CRITICAL test is `StreamingCompleted emits ordered side effects` — it
 * asserts the emitted [SignalEffect] list matches the exact order:
 *   [SetStreamPhaseIdle, NotifyResponseComplete, ComputeSessionContext,
 *    FetchTodos, LoadSessions, DrainQueue, RefreshReviewFiles]
 * This catches reordering, not just omission.
 *
 * Uses manual [MutableSharedFlow] collection with [advanceUntilIdle] for virtual time control. Each test:
 *   1. Creates a [SignalRouter] on a [TestScope].
 *   2. Starts the router with two [MutableSharedFlow]s (signals + globalSignals).
 *   3. Launches a collector that records emitted effects into a list.
 *   4. Emits a [UiSignal] and calls [advanceUntilIdle].
 *   5. Asserts the recorded effects list.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SignalRouterTest {

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

    private fun makeSelectionPrompt(
        promptId: String = "que_1",
        sessionId: String = "ses_1",
    ) = SelectionPrompt(
        sessionId = sessionId,
        promptId = promptId,
        question = "Pick one",
        options = listOf(SelectionOption(title = "A", description = "")),
        multiSelect = false,
        allowCustomInput = false,
    )

    /**
     * Helper: run a block with a fresh [SignalRouter] on a [TestScope], with
     * two [MutableSharedFlow]s for signals and globalSignals. Collects emitted
     * effects into a list and passes the list + emit functions to the block.
     */
    private fun runRouterTest(
        block: suspend TestScope.(
            effects: MutableList<SignalEffect>,
            emitSignal: suspend (UiSignal) -> Unit,
            emitGlobal: suspend (UiSignal) -> Unit,
        ) -> Unit,
    ) = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val router = SignalRouter(this)
        val effects = mutableListOf<SignalEffect>()
        val collectorJob = launch {
            router.effects.collect { effects.add(it) }
        }
        router.start(signals, globalSignals)
        advanceUntilIdle()
        try {
            block(effects, { s -> signals.emit(s) }, { s -> globalSignals.emit(s) })
        } finally {
            collectorJob.cancel()
            router.stop()
        }
    }

    // ── Active-session signals ─────────────────────────────────────────────

    @Test
    fun `StreamingStarted emits no effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.StreamingStarted("msg_1"))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    @Test
    fun `StreamingCompleted with naturalCompletion emits ordered side effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.StreamingCompleted("msg_1", emptyList(), naturalCompletion = true))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetStreamPhaseIdle("msg_1"),
            SignalEffect.NotifyResponseComplete("msg_1"),
            SignalEffect.ComputeSessionContext(null),
            SignalEffect.FetchTodos(null),
            SignalEffect.LoadSessions(false),
            SignalEffect.DrainQueue,
            SignalEffect.RefreshReviewFiles,
        )
    }

    @Test
    fun `StreamingCompleted without naturalCompletion omits NotifyResponseComplete`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.StreamingCompleted("msg_1", emptyList(), naturalCompletion = false))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetStreamPhaseIdle("msg_1"),
            SignalEffect.ComputeSessionContext(null),
            SignalEffect.FetchTodos(null),
            SignalEffect.LoadSessions(false),
            SignalEffect.DrainQueue,
            SignalEffect.RefreshReviewFiles,
        )
    }

    @Test
    fun `PermissionRequested emits SetPermissionPrompt, NotifyPermissionNeeded, StartPermissionTimeout`() = runRouterTest { effects, emitSignal, _ ->
        val prompt = makePermissionPrompt()
        emitSignal(UiSignal.PermissionRequested(prompt))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetPermissionPrompt(prompt),
            SignalEffect.NotifyPermissionNeeded,
            SignalEffect.StartPermissionTimeout(prompt),
        )
    }

    @Test
    fun `SelectionRequested emits SetSelectionPrompt, NotifyQuestionAsked`() = runRouterTest { effects, emitSignal, _ ->
        val prompt = makeSelectionPrompt()
        emitSignal(UiSignal.SelectionRequested(prompt))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetSelectionPrompt(prompt),
            SignalEffect.NotifyQuestionAsked,
        )
    }

    @Test
    fun `FileChanged emits EmitFileChangeSignal, RefreshReviewFiles`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.FileChanged())
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.EmitFileChangeSignal,
            SignalEffect.RefreshReviewFiles,
        )
    }

    @Test
    fun `MessageUpdated emits ComputeSessionContextLocal`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.MessageUpdated("msg_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.ComputeSessionContextLocal("msg_1"),
        )
    }

    @Test
    fun `Error emits no effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.Error("msg_1", "boom"))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    @Test
    fun `TodoUpdated emits no effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.TodoUpdated(emptyList()))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    // ── Global signals ──────────────────────────────────────────────────────

    @Test
    fun `SessionCreated global emits LoadSessions`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionCreated("ses_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.LoadSessions(false),
        )
    }

    @Test
    fun `SessionIdle global emits ComputeSessionContext`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionIdle("ses_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.ComputeSessionContext(null),
        )
    }

    @Test
    fun `SessionError global emits SetStreamPhaseIdleForSession, RemoveStreamingSession`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionError("ses_1", "boom"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetStreamPhaseIdleForSession("ses_1"),
            SignalEffect.RemoveStreamingSession("ses_1"),
        )
    }

    @Test
    fun `SessionCompacted global emits RefreshActiveSessionMessages, ComputeSessionContext`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionCompacted("ses_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.RefreshActiveSessionMessages("ses_1"),
            SignalEffect.ComputeSessionContext(null),
        )
    }

    @Test
    fun `SessionDeleted global emits HandleSessionDeleted`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionDeleted("ses_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.HandleSessionDeleted("ses_1"),
        )
    }

    @Test
    fun `ChildPermissionRequested global emits AddChildPermissionPrompt, NotifyPermissionNeeded`() = runRouterTest { effects, _, emitGlobal ->
        val prompt = makeChildPermissionPrompt()
        emitGlobal(UiSignal.ChildPermissionRequested(prompt))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.AddChildPermissionPrompt(prompt),
            SignalEffect.NotifyPermissionNeeded,
        )
    }

    @Test
    fun `PermissionReplied global emits HandlePermissionReplied`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.PermissionReplied("perm_1", "allow", "ses_1"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.HandlePermissionReplied("perm_1", "allow", "ses_1"),
        )
    }

    @Test
    fun `PermissionTimedOut global emits HandlePermissionTimedOut`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.PermissionTimedOut("perm_1", "ses_1", "bash"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.HandlePermissionTimedOut("perm_1", "ses_1", "bash"),
        )
    }

    // ── Cross-stream isolation ──────────────────────────────────────────────

    @Test
    fun `global-only signals on activeSignals emit no effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.SessionCreated("ses_1"))
        emitSignal(UiSignal.SessionIdle("ses_1"))
        emitSignal(UiSignal.SessionError("ses_1", "boom"))
        emitSignal(UiSignal.SessionCompacted("ses_1"))
        emitSignal(UiSignal.SessionDeleted("ses_1"))
        emitSignal(UiSignal.ChildPermissionRequested(makeChildPermissionPrompt()))
        emitSignal(UiSignal.PermissionReplied("perm_1", "allow", "ses_1"))
        emitSignal(UiSignal.PermissionTimedOut("perm_1", "ses_1", "bash"))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    @Test
    fun `active-only signals on globalSignals emit no effects`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.StreamingStarted("msg_1"))
        emitGlobal(UiSignal.StreamingCompleted("msg_1", emptyList(), naturalCompletion = true))
        emitGlobal(UiSignal.MessageUpdated("msg_1"))
        emitGlobal(UiSignal.PermissionRequested(makePermissionPrompt()))
        emitGlobal(UiSignal.SelectionRequested(makeSelectionPrompt()))
        emitGlobal(UiSignal.Error("msg_1", "boom"))
        emitGlobal(UiSignal.TodoUpdated(emptyList()))
        emitGlobal(UiSignal.FileChanged())
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }
}