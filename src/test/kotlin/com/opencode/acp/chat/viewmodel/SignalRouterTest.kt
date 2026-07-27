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
    fun `Error emits SetStreamPhaseIdleGated as backstop for suppressed StreamingCompleted`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.Error("msg_1", "boom"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.SetStreamPhaseIdleGated("msg_1"),
        )
    }

    @Test
    fun `Error after StreamingCompleted still emits SetStreamPhaseIdle idempotently`() = runRouterTest { effects, emitSignal, _ ->
        // Simulate the timeout bug: StreamingCompleted fires (sets phase IDLE),
        // then Error fires (should be a no-op on phase, but still emit safely).
        emitSignal(UiSignal.StreamingCompleted("msg_1", emptyList(), naturalCompletion = false))
        emitSignal(UiSignal.Error("msg_1", "timeout"))
        advanceUntilIdle()
        // Assert the full effects list — verifies ordering (StreamingCompleted's
        // SetStreamPhaseIdle comes before Error's SetStreamPhaseIdleGated) and that
        // both carry "msg_1". A router bug that swaps the order or emits a wrong
        // messageId would fail.
        effects shouldBe listOf(
            // StreamingCompleted (naturalCompletion=false → no NotifyResponseComplete)
            SignalEffect.SetStreamPhaseIdle("msg_1"),
            SignalEffect.ComputeSessionContext(null),
            SignalEffect.FetchTodos(null),
            SignalEffect.LoadSessions(false),
            SignalEffect.DrainQueue,
            SignalEffect.RefreshReviewFiles,
            // Error backstop
            SignalEffect.SetStreamPhaseIdleGated("msg_1"),
        )
        // Double-check: exactly one SetStreamPhaseIdle and one SetStreamPhaseIdleGated,
        // both with "msg_1".
        val idleEffects = effects.filterIsInstance<SignalEffect.SetStreamPhaseIdle>()
        idleEffects shouldHaveSize 1
        idleEffects.first().messageId shouldBe "msg_1"
        val gatedEffects = effects.filterIsInstance<SignalEffect.SetStreamPhaseIdleGated>()
        gatedEffects shouldHaveSize 1
        gatedEffects.first().messageId shouldBe "msg_1"
    }

    @Test
    fun `TodoUpdated emits no effects`() = runRouterTest { effects, emitSignal, _ ->
        emitSignal(UiSignal.TodoUpdated(emptyList()))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    @Test
    fun `TodoUpdated with non-empty list emits no effects`() = runRouterTest { effects, emitSignal, _ ->
        // The router's TodoUpdated branch is a no-op regardless of content.
        // This test covers the non-empty path so a future change that adds a
        // TodoUpdated effect would be caught for both empty and populated cases.
        emitSignal(UiSignal.TodoUpdated(listOf(
            com.opencode.acp.chat.model.TodoItem(content = "Task 1", status = "pending", priority = "high"),
            com.opencode.acp.chat.model.TodoItem(content = "Task 2", status = "in_progress", priority = "medium"),
        )))
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    // ── Global signals ──────────────────────────────────────────────────────

    @Test
    fun `SessionCreated global emits Load_sessions`() = runRouterTest { effects, _, emitGlobal ->
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
    fun `SessionError global emits LogSessionError, SetStreamPhaseIdleForSession, RemoveStreamingSession`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionError("ses_1", "boom"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.LogSessionError("ses_1", "boom"),
            SignalEffect.SetStreamPhaseIdleForSession("ses_1"),
            SignalEffect.RemoveStreamingSession("ses_1"),
        )
    }

    @Test
    fun `SessionError global with null errorMessage still emits LogSessionError`() = runRouterTest { effects, _, emitGlobal ->
        emitGlobal(UiSignal.SessionError("ses_1", null))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.LogSessionError("ses_1", null),
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
        advanceUntilIdle()
        effects.shouldBeEmpty()
    }

    @Test
    fun `PermissionTimedOut on activeSignals forwards to global handler as fallback`() = runRouterTest { effects, emitSignal, _ ->
        // PermissionTimedOut should arrive on globalSignals, but if misrouted to
        // activeSignals, the router forwards it to the global handler as a fallback
        // so the permission timeout is still enforced (fail-safe, not fail-open).
        emitSignal(UiSignal.PermissionTimedOut("perm_1", "ses_1", "bash"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.HandlePermissionTimedOut("perm_1", "ses_1", "bash"),
        )
    }

    @Test
    fun `PermissionTimedOut on both streams fires effect exactly once - no double-fire`() = runRouterTest { effects, emitSignal, emitGlobal ->
        // PermissionTimedOut arriving on BOTH streams must not double-fire the effect.
        // The activeSignals fallback delegates to routeGlobalSignal, and the dedup
        // check (isDuplicatePermissionTimeout) in routeGlobalSignal ensures only the
        // first emission produces an effect, even if both streams deliver the same signal.
        emitSignal(UiSignal.PermissionTimedOut("perm_1", "ses_1", "bash"))
        emitGlobal(UiSignal.PermissionTimedOut("perm_1", "ses_1", "bash"))
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.HandlePermissionTimedOut("perm_1", "ses_1", "bash"),
        )
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

    // ── start() / stop() lifecycle ──────────────────────────────────────────

    @Test
    fun `stop prevents further effects from being emitted`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val router = SignalRouter(this)
        val effects = mutableListOf<SignalEffect>()
        val collectorJob = launch {
            router.effects.collect { effects.add(it) }
        }
        router.start(signals, globalSignals)
        advanceUntilIdle()

        // Emit a signal that DOES produce effects BEFORE stop — proves the collector was running.
        signals.emit(UiSignal.FileChanged())
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.EmitFileChangeSignal,
            SignalEffect.RefreshReviewFiles,
        )

        // Stop the router.
        router.stop()
        advanceUntilIdle()

        // Emit signals after stop — should produce NO NEW effects.
        signals.emit(UiSignal.FileChanged())
        globalSignals.emit(UiSignal.SessionCreated("ses_1"))
        advanceUntilIdle()
        // Effects list unchanged — no new effects after stop.
        effects shouldBe listOf(
            SignalEffect.EmitFileChangeSignal,
            SignalEffect.RefreshReviewFiles,
        )

        collectorJob.cancel()
    }

    @Test
    fun `double start does not orphan collect jobs or produce duplicate effects`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val router = SignalRouter(this)
        val effects = mutableListOf<SignalEffect>()
        val collectorJob = launch {
            router.effects.collect { effects.add(it) }
        }

        // Start twice — the second start should cancel the first pair of jobs.
        router.start(signals, globalSignals)
        advanceUntilIdle()
        router.start(signals, globalSignals)
        advanceUntilIdle()

        // Emit a signal — should produce effects exactly once (not duplicated).
        signals.emit(UiSignal.FileChanged())
        advanceUntilIdle()
        effects shouldBe listOf(
            SignalEffect.EmitFileChangeSignal,
            SignalEffect.RefreshReviewFiles,
        )

        collectorJob.cancel()
        router.stop()
    }

    @Test
    fun `double start with signal between calls does not duplicate effects`() = runTest {
        val signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val globalSignals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 64)
        val router = SignalRouter(this)
        val effects = mutableListOf<SignalEffect>()
        val collectorJob = launch {
            router.effects.collect { effects.add(it) }
        }

        router.start(signals, globalSignals)
        advanceUntilIdle()

        // Emit a signal, then immediately re-start WITHOUT advanceUntilIdle.
        // The fix (join() after cancel()) ensures the in-flight routeSignal completes
        // before the new collector starts, preventing duplicate effects.
        signals.emit(UiSignal.FileChanged())
        router.start(signals, globalSignals)
        advanceUntilIdle()

        // Should produce effects exactly once (not duplicated by the double-start).
        effects shouldBe listOf(
            SignalEffect.EmitFileChangeSignal,
            SignalEffect.RefreshReviewFiles,
        )

        collectorJob.cancel()
        router.stop()
    }

    /**
     * Regression guard: `start()` must set `stopped = false` SYNCHRONOUSLY
     * (before returning), NOT inside the `scope.launch { ... }` coroutine.
     *
     * Background: The original implementation set `stopped = false` inside the
     * `scope.launch` block. Because `scope.launch` is asynchronous, there was a
     * window where `stopped` was still `true` (initial value or from a prior
     * `stop()`) when a signal arrived between `start()` returning and the launch
     * coroutine running. The `routeSignal` guard `if (stopped) return` would then
     * drop the signal — most critically `StreamingCompleted`, whose loss leaves
     * `_streamPhase` stuck in STREAMING forever (the "stuck animation" regression).
     *
     * This test verifies the fix: immediately after `start()` returns (BEFORE any
     * `advanceUntilIdle()` or coroutine dispatcher advancement), a signal emitted
     * to the flows is NOT dropped by the `stopped` guard. We assert that the signal
     * produces its effects after a single `advanceUntilIdle()`, proving the guard
     * was already cleared when the collector coroutine began running.
     *
     * Note: We cannot directly assert the private `stopped` flag, so we assert its
     * observable consequence — a signal emitted right after `start()` (before
     * `advanceUntilIdle`) is routed (not dropped) once the dispatcher runs.
     */
    @Test
    fun `start sets stopped=false synchronously so signals before advanceUntilIdle are not dropped`() = runTest {
        // replay=1 so a signal emitted before the collector starts is replayed
        // to it when it attaches. The real service.signals uses replay=0 with
        // SharingStarted.Eagerly (collector is always active), but in this test
        // the router's collector launches asynchronously inside scope.launch,
        // so we need replay to bridge the gap. The test verifies the `stopped`
        // flag behavior, not SharedFlow replay semantics.
        val signals = MutableSharedFlow<UiSignal>(replay = 1, extraBufferCapacity = 64)
        val globalSignals = MutableSharedFlow<UiSignal>(replay = 1, extraBufferCapacity = 64)
        val router = SignalRouter(this)
        val effects = mutableListOf<SignalEffect>()
        val collectorJob = launch {
            router.effects.collect { effects.add(it) }
        }

        // Start the router. The fix requires `stopped = false` to be set BEFORE
        // start() returns (synchronously), not inside the scope.launch coroutine.
        router.start(signals, globalSignals)

        // Emit a signal IMMEDIATELY — before any advanceUntilIdle(). If `stopped`
        // were still true at the time the collector coroutine runs (because it was
        // set inside the launch block which hadn't executed yet), the signal would
        // be dropped by the `if (stopped) return` guard in routeSignal.
        signals.emit(UiSignal.StreamingCompleted("msg_1", emptyList(), naturalCompletion = true))

        // Now advance the dispatcher so the launch coroutine + collectors run.
        advanceUntilIdle()

        // The signal must have been routed (not dropped). If `stopped` had not been
        // cleared synchronously, the effects list would be empty (signal dropped).
        effects shouldBe listOf(
            SignalEffect.SetStreamPhaseIdle("msg_1"),
            SignalEffect.NotifyResponseComplete("msg_1"),
            SignalEffect.ComputeSessionContext(null),
            SignalEffect.FetchTodos(null),
            SignalEffect.LoadSessions(false),
            SignalEffect.DrainQueue,
            SignalEffect.RefreshReviewFiles,
        )

        collectorJob.cancel()
        router.stop()
    }
}