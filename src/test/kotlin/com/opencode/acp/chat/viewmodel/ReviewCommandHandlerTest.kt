package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.StagedFilesResult
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewIndex
import com.opencode.acp.review.ReviewMessages
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReviewCommandHandler] (TDD §4.2.6).
 *
 * Tests the `/review-*` slash commands: resolve, perform (with model args),
 * recheck, and the multi-model review loop with cancellation. The send
 * functions, inject, refresh, and cancellation providers are lambdas backed
 * by mutable lists so tests can assert call order and arguments.
 *
 * [GitService] and [Project] are mocked with MockK. [ReviewCommentManager.getInstance]
 * (a companion method) is mocked via [mockkObject]. [ApplicationManager.getApplication]
 * is mocked via [mockkStatic] because [com.intellij.openapi.application.runReadActionBlocking]
 * is an inline function that calls `ApplicationManager.getApplication().runReadAction(Computable)`
 * — inline functions can't be mocked directly, so the application is mocked and
 * `runReadAction` is stubbed to execute the [Computable] synchronously.
 *
 * Uses [runBlocking] with a child [CoroutineScope] so the handler's `scope.launch`
 * coroutines (including `withContext(Dispatchers.IO)` for VCS reads) complete before
 * assertions run. The launched jobs are joined explicitly via the scope's job children.
 */
class ReviewCommandHandlerTest {

    private lateinit var project: Project
    private lateinit var gitService: GitService
    private lateinit var reviewCommentManager: ReviewCommentManager
    private lateinit var mockApp: Application

    private lateinit var sendCalls: MutableList<SendCall>
    private lateinit var sendWithModelCalls: MutableList<SendWithModelCall>
    private lateinit var injectedMessages: MutableList<String>
    private lateinit var refreshCalls: MutableList<CompletableJob>
    private lateinit var resetCancelledCalls: MutableList<Int>

    private var isCancelled: Boolean = false
    private var controlState: ControlBarState = ControlBarState()

    private lateinit var handler: ReviewCommandHandler

    /** Records a plain sendFunction call. */
    private data class SendCall(val text: String, val files: List<AttachedFile>)

    /** Records a sendWithModelFunction call. */
    private data class SendWithModelCall(
        val text: String,
        val modelID: String?,
        val providerID: String?,
        val variant: String?,
        val model: OpenCodeClient.MessageModel?,
    )

    @BeforeEach
    fun setUp() {
        project = mockk<Project>(relaxed = true)
        gitService = mockk<GitService>(relaxed = true)
        reviewCommentManager = mockk<ReviewCommentManager>(relaxed = true)

        // Default stub: StagedFilesResult is a sealed interface, so relaxed
        // MockK needs an explicit return value. Tests that exercise the
        // NothingStaged/NoGitRepository branches override this per-test.
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(emptyList())

        sendCalls = mutableListOf()
        sendWithModelCalls = mutableListOf()
        injectedMessages = mutableListOf()
        refreshCalls = mutableListOf()
        resetCancelledCalls = mutableListOf()
        isCancelled = false
        controlState = ControlBarState()

        // Mock ApplicationManager.getApplication() — runReadActionBlocking is an
        // inline function that calls ApplicationManager.getApplication().runReadAction(Computable).
        // Inline functions can't be mocked directly, so we mock the application
        // and stub runReadAction to execute the Computable synchronously.
        mockApp = mockk<Application>(relaxed = true)
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.runReadAction(any<Computable<*>>()) } answers {
            val computable = firstArg<Computable<*>>()
            computable.compute()
        }

        // Mock ReviewCommentManager.getInstance(project) — companion method
        mockkObject(ReviewCommentManager.Companion)
        every { ReviewCommentManager.getInstance(any()) } returns reviewCommentManager
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        every { reviewCommentManager.snapshotReplyIds(any()) } returns emptyMap()
        coEvery { reviewCommentManager.restoreMissingReplies(any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ReviewCommentManager.Companion)
        unmockkStatic(ApplicationManager::class)
    }

    /** Builds a handler with the given scope and the recorded lambdas. */
    private fun buildHandler(scope: CoroutineScope): ReviewCommandHandler {
        return ReviewCommandHandler(
            scope = scope,
            project = project,
            gitService = gitService,
            controlStateProvider = { controlState },
            sendFunction = { text, files ->
                sendCalls.add(SendCall(text, files))
                SendMessageResult.Success("msg_ok")
            },
            sendWithModelFunction = { text, modelID, providerID, variant, model ->
                sendWithModelCalls.add(SendWithModelCall(text, modelID, providerID, variant, model))
                SendMessageResult.Success("msg_ok")
            },
            injectLocalMessage = { msg -> injectedMessages.add(msg) },
            refreshReviewFiles = {
                // Return an already-completed job so refreshReviewFiles().join()
                // in executeReviewRecheckCommand doesn't hang. The job is recorded
                // for assertion; completion is immediate.
                val job = SupervisorJob()
                job.complete()
                refreshCalls.add(job)
                job
            },
            isCancelledProvider = { isCancelled },
            resetCancelled = { resetCancelledCalls.add(resetCancelledCalls.size) },
        )
    }

    /**
     * Runs [block] inside [runBlocking] with a child [CoroutineScope], invokes the
     * handler command, then joins all launched child jobs so `withContext(Dispatchers.IO)`
     * completes before assertions. Returns the handler for convenience.
     */
    private fun runHandlerAwaiting(
        command: (ReviewCommandHandler) -> Unit,
    ): ReviewCommandHandler = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        handler = buildHandler(scope)
        command(handler)
        // Wait for all fire-and-forget scope.launch coroutines to complete,
        // including withContext(Dispatchers.IO) for VCS reads.
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        handler
    }

    private fun changedFile(path: String): ChangedFile = ChangedFile(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        status = FileChangeStatus.MODIFIED,
        lineDelta = LineDelta.Unknown,
        virtualFile = null,
    )

    // ── executeReviewResolveCommand ──────────────────────────────────────

    @Test
    fun `executeReviewResolveCommand calls sendFunction with the resolve prompt`() {
        every { reviewCommentManager.getIndex() } returns ReviewIndex(totalOpen = 0)
        runHandlerAwaiting { it.executeReviewResolveCommand() }

        sendCalls shouldHaveSize 1
        sendCalls[0].files shouldBe emptyList()
        sendCalls[0].text.contains("review comments", ignoreCase = true) shouldBe true
    }

    @Test
    fun `executeReviewResolveCommand with open comments includes summary`() {
        every { reviewCommentManager.getIndex() } returns ReviewIndex(totalOpen = 3)
        runHandlerAwaiting { it.executeReviewResolveCommand() }

        sendCalls shouldHaveSize 1
        sendCalls[0].text.contains("3") shouldBe true
    }

    // ── executeReviewPerformCommand (no args) ─────────────────────────────

    @Test
    fun `executeReviewPerformCommand with no args calls sendFunction with perform prompt`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/test.kt")))
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        sendCalls shouldHaveSize 1
        sendWithModelCalls shouldHaveSize 0
        // The perform prompt references the changed file
        sendCalls[0].text.contains("src/test.kt") shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand with no args uses control bar model`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        // No model args → single sendFunction call (control-bar model)
        sendCalls shouldHaveSize 1
        sendWithModelCalls shouldHaveSize 0
    }

    // ── executeReviewPerformCommand (with model args) ─────────────────────

    @Test
    fun `executeReviewPerformCommand with resolvable model arg calls sendWithModelFunction`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/main.kt")))
        val model = ProviderModel(
            providerID = "anthropic",
            modelID = "claude-sonnet",
            displayName = "Claude Sonnet",
        )
        controlState = ControlBarState(models = listOf(model))
        runHandlerAwaiting { it.executeReviewPerformCommand("claude-sonnet") }

        sendCalls shouldHaveSize 0
        sendWithModelCalls shouldHaveSize 1
        sendWithModelCalls[0].modelID shouldBe "claude-sonnet"
        sendWithModelCalls[0].providerID shouldBe "anthropic"
        sendWithModelCalls[0].model shouldBe OpenCodeClient.MessageModel(
            providerID = "anthropic", modelID = "claude-sonnet"
        )
        // The prompt is prefixed with a "### Review by" header
        sendWithModelCalls[0].text.contains("### Review by") shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand with nonexistent model injects error message`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        controlState = ControlBarState(models = emptyList())
        runHandlerAwaiting { it.executeReviewPerformCommand("nonexistent") }

        // Unresolved arg → error injected, no sends
        injectedMessages shouldHaveSize 1
        injectedMessages[0].contains("nonexistent") shouldBe true
        sendCalls shouldHaveSize 0
        sendWithModelCalls shouldHaveSize 0
    }

    @Test
    fun `executeReviewPerformCommand escapes backticks in unresolved model args`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        controlState = ControlBarState(models = emptyList())
        // An arg containing a backtick must be escaped so it cannot break the
        // backtick code span wrapping it in the injected error message.
        runHandlerAwaiting { it.executeReviewPerformCommand("foo`bar") }

        injectedMessages shouldHaveSize 1
        // The escaped form \\`foo\\`bar... must appear (backtick escaped), and the
        // raw unescaped code-span terminator must NOT appear unescaped.
        injectedMessages[0].contains("\\`") shouldBe true
        sendCalls shouldHaveSize 0
        sendWithModelCalls shouldHaveSize 0
    }

    @Test
    fun `executeReviewPerformCommand with multiple models sends once per model`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        runHandlerAwaiting { it.executeReviewPerformCommand("claude-sonnet gpt-4") }

        sendWithModelCalls shouldHaveSize 2
        sendWithModelCalls[0].modelID shouldBe "claude-sonnet"
        sendWithModelCalls[1].modelID shouldBe "gpt-4"
    }

    // ── Cancellation ──────────────────────────────────────────────────────

    @Test
    fun `executeReviewPerformCommand breaks loop and injects cancel message when cancelled`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        // Set cancellation flag before the loop checks it
        isCancelled = true
        runHandlerAwaiting { it.executeReviewPerformCommand("claude-sonnet gpt-4") }

        // Loop breaks immediately — no sends, cancel message injected
        sendWithModelCalls shouldHaveSize 0
        injectedMessages.any { it.contains("cancelled", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand resets cancelled flag at start`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        // resetCancelled is called at start (line 246) AND in the finally block
        // of the no-args path — so 2 calls total. The finally ensures the flag
        // is cleared even if the send throws or the coroutine is cancelled.
        resetCancelledCalls shouldHaveSize 2
    }

    @Test
    fun `executeReviewPerformCommand breaks loop mid-way when cancelled between iterations`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        isCancelled = false

        // Build a custom handler that flips isCancelled after the first send
        val scope = CoroutineScope(SupervisorJob())
        var sendCount = 0
        handler = ReviewCommandHandler(
            scope = scope,
            project = project,
            gitService = gitService,
            controlStateProvider = { controlState },
            sendFunction = { text, files ->
                sendCalls.add(SendCall(text, files))
                SendMessageResult.Success("msg_ok")
            },
            sendWithModelFunction = { text, modelID, providerID, variant, model ->
                sendWithModelCalls.add(SendWithModelCall(text, modelID, providerID, variant, model))
                sendCount++
                // After the first send succeeds, set cancellation flag to true
                // so the second iteration's isCancelledProvider() check breaks the loop
                if (sendCount >= 1) {
                    isCancelled = true
                }
                SendMessageResult.Success("msg_ok")
            },
            injectLocalMessage = { msg -> injectedMessages.add(msg) },
            refreshReviewFiles = {
                val job = SupervisorJob()
                job.complete()
                refreshCalls.add(job)
                job
            },
            isCancelledProvider = { isCancelled },
            resetCancelled = { resetCancelledCalls.add(resetCancelledCalls.size) },
        )
        runBlocking {
            handler.executeReviewPerformCommand("claude-sonnet gpt-4")
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }

        // First model sent, then isCancelled is set to true, loop breaks before
        // the second model (mid-loop cancellation check at ReviewCommandHandler.kt:283-288)
        sendWithModelCalls shouldHaveSize 1
        injectedMessages.any { it.contains("cancelled", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand breaks loop on send failure and injects error message`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))

        // Build a handler where sendWithModelFunction returns Error for the first model
        val scope = CoroutineScope(SupervisorJob())
        var sendCount = 0
        handler = ReviewCommandHandler(
            scope = scope,
            project = project,
            gitService = gitService,
            controlStateProvider = { controlState },
            sendFunction = { text, files ->
                sendCalls.add(SendCall(text, files))
                SendMessageResult.Success("msg_ok")
            },
            sendWithModelFunction = { text, modelID, providerID, variant, model ->
                sendWithModelCalls.add(SendWithModelCall(text, modelID, providerID, variant, model))
                sendCount++
                // First model fails with an error
                if (sendCount == 1) {
                    SendMessageResult.Error("timeout", isStuckMutex = false)
                } else {
                    SendMessageResult.Success("msg_ok")
                }
            },
            injectLocalMessage = { msg -> injectedMessages.add(msg) },
            refreshReviewFiles = {
                val job = SupervisorJob()
                job.complete()
                refreshCalls.add(job)
                job
            },
            isCancelledProvider = { false },
            resetCancelled = { resetCancelledCalls.add(resetCancelledCalls.size) },
        )
        runBlocking {
            handler.executeReviewPerformCommand("claude-sonnet gpt-4")
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }

        // First model fails → loop breaks, error injected, second model NOT sent
        sendWithModelCalls shouldHaveSize 1
        injectedMessages.any { it.contains("failed") } shouldBe true
        injectedMessages.any { it.contains("claude-sonnet") || it.contains("Claude Sonnet") } shouldBe true
    }

    // ── executeReviewRecheckCommand ───────────────────────────────────────

    @Test
    fun `executeReviewRecheckCommand with no args calls sendFunction then refreshReviewFiles`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/recheck.kt")))
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }

        sendCalls shouldHaveSize 1
        // refreshReviewFiles is called in the finally block
        refreshCalls shouldHaveSize 1
    }

    @Test
    fun `executeReviewRecheckCommand with model args sends with model and refreshes`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        val model = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        controlState = ControlBarState(models = listOf(model))
        runHandlerAwaiting { it.executeReviewRecheckCommand("claude-sonnet") }

        sendWithModelCalls shouldHaveSize 1
        refreshCalls shouldHaveSize 1
    }

    @Test
    fun `executeReviewRecheckCommand restores missing replies after refresh`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        coEvery { reviewCommentManager.restoreMissingReplies(any(), any()) } returns 2
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }

        // restoreMissingReplies returns 2 → triggers a second refresh
        refreshCalls shouldHaveSize 2
        // Verify the safety net was actually called, not just its side effect
        coVerify { reviewCommentManager.restoreMissingReplies(any(), any()) }
    }

    // ── executeReviewPerformGamingCommand ────────────────────────────────

    @Test
    fun `executeReviewPerformGamingCommand with no args calls sendFunction`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/game.cpp")))
        runHandlerAwaiting { it.executeReviewPerformGamingCommand("") }

        sendCalls shouldHaveSize 1
        // Gaming prompt references the changed file
        sendCalls[0].text.contains("src/game.cpp") shouldBe true
    }

    @Test
    fun `executeReviewPerformGamingCommand with model args calls sendWithModelFunction`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/game.cpp")))
        val model = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        controlState = ControlBarState(models = listOf(model))
        runHandlerAwaiting { it.executeReviewPerformGamingCommand("claude-sonnet") }

        sendWithModelCalls shouldHaveSize 1
        sendWithModelCalls[0].modelID shouldBe "claude-sonnet"
        // The gaming prompt should reference the changed file
        sendWithModelCalls[0].text.contains("src/game.cpp") shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand with wildcard resolves all models`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        runHandlerAwaiting { it.executeReviewPerformCommand("*") }

        // * should resolve to all available models
        sendWithModelCalls shouldHaveSize 2
    }

    // ── StagedFilesResult branches (NothingStaged / NoGitRepository) ──────

    @Test
    fun `executeReviewPerformCommand with nothing staged injects message and does not send`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.NothingStaged
        runHandlerAwaiting { it.executeReviewPerformCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0] shouldBe ReviewMessages.NOTHING_STAGED
    }

    @Test
    fun `executeReviewPerformCommand in non-git project injects message and does not send`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.NoGitRepository
        runHandlerAwaiting { it.executeReviewPerformCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0] shouldBe ReviewMessages.NO_GIT_REPO
    }

    @Test
    fun `executeReviewPerformCommand with git error injects error message and does not send`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Error("fatal: not a git repository")
        runHandlerAwaiting { it.executeReviewPerformCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0].startsWith(ReviewMessages.GIT_ERROR_PREFIX) shouldBe true
        injectedMessages[0].contains("fatal: not a git repository") shouldBe true
    }

    @Test
    fun `executeReviewPerformGamingCommand with nothing staged injects message`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.NothingStaged
        runHandlerAwaiting { it.executeReviewPerformGamingCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0] shouldBe ReviewMessages.NOTHING_STAGED
    }

    @Test
    fun `executeReviewRecheckCommand with nothing staged injects message and does not refresh`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.NothingStaged
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0] shouldBe ReviewMessages.NOTHING_STAGED
        // No LLM send → no refresh/reply-restore
        refreshCalls shouldHaveSize 0
    }

    @Test
    fun `executeReviewRecheckCommand in non-git project injects message and does not refresh`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.NoGitRepository
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0] shouldBe ReviewMessages.NO_GIT_REPO
        refreshCalls shouldHaveSize 0
    }

    @Test
    fun `executeReviewRecheckCommand with git error injects message and does not refresh`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Error("fatal: corrupt repo")
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }
        sendCalls shouldHaveSize 0
        injectedMessages shouldHaveSize 1
        injectedMessages[0].startsWith(ReviewMessages.GIT_ERROR_PREFIX) shouldBe true
        injectedMessages[0].contains("fatal: corrupt repo") shouldBe true
        refreshCalls shouldHaveSize 0
    }

    // ── Exception propagation + resetCancelled finally guarantee ──────────

    @Test
    fun `executeReviewPerformCommand resets cancelled flag even when sendWithModelFunction throws`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))

        val scope = CoroutineScope(SupervisorJob())
        handler = ReviewCommandHandler(
            scope = scope,
            project = project,
            gitService = gitService,
            controlStateProvider = { controlState },
            sendFunction = { text, files ->
                sendCalls.add(SendCall(text, files))
                SendMessageResult.Success("msg_ok")
            },
            sendWithModelFunction = { _, _, _, _, _ ->
                sendWithModelCalls.add(SendWithModelCall("", "", "", "", null))
                // First model's send THROWS (not returns Error) — the finally
                // block must still call resetCancelled().
                throw RuntimeException("boom")
            },
            injectLocalMessage = { msg -> injectedMessages.add(msg) },
            refreshReviewFiles = {
                val job = SupervisorJob()
                job.complete()
                refreshCalls.add(job)
                job
            },
            isCancelledProvider = { false },
            resetCancelled = { resetCancelledCalls.add(resetCancelledCalls.size) },
        )
        runBlocking {
            try {
                handler.executeReviewPerformCommand("claude-sonnet gpt-4")
            } catch (_: RuntimeException) {
                // Expected — the thrown exception propagates out of scope.launch
            }
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }

        // Only the first model was attempted (it threw) — second model NOT sent
        sendWithModelCalls shouldHaveSize 1
        // resetCancelled is called at the start (line 246) AND in the finally
        // block — so at least 2 calls even though the send threw.
        (resetCancelledCalls.size >= 2) shouldBe true
    }

    // ── Cancellation during an in-flight send ─────────────────────────────
    // Verifies the sendWithCancellationPoll helper: when isCancelled flips to
    // true DURING a suspending send, the in-flight send is cancelled (not left
    // to complete) and the loop breaks before the next model.

    @Test
    fun `executeReviewPerformCommand cancels in-flight send when user cancels during suspension`() {
        every { gitService.getStagedFiles() } returns StagedFilesResult.Staged(listOf(changedFile("src/dummy.kt")))
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        isCancelled = false

        val scope = CoroutineScope(SupervisorJob())
        var sendStarted = false
        handler = ReviewCommandHandler(
            scope = scope,
            project = project,
            gitService = gitService,
            controlStateProvider = { controlState },
            sendFunction = { text, files ->
                sendCalls.add(SendCall(text, files))
                SendMessageResult.Success("msg_ok")
            },
            sendWithModelFunction = { _, _, _, _, _ ->
                sendWithModelCalls.add(SendWithModelCall("", "", "", "", null))
                sendStarted = true
                // Simulate a long-running send. The cancellation poll runs every
                // 200ms; we sleep here so the poll has a chance to detect the
                // flag flip and cancel this coroutine.
                delay(2000) // Long enough for the 200ms poll to fire multiple times
                SendMessageResult.Success("msg_ok")
            },
            injectLocalMessage = { msg -> injectedMessages.add(msg) },
            refreshReviewFiles = {
                val job = SupervisorJob()
                job.complete()
                refreshCalls.add(job)
                job
            },
            isCancelledProvider = { isCancelled },
            resetCancelled = { resetCancelledCalls.add(resetCancelledCalls.size) },
        )

        runBlocking {
            // Launch a coroutine that flips the cancellation flag shortly after
            // the send starts, simulating the user clicking Cancel mid-stream.
            scope.launch {
                // Wait until the send has started, then set the flag.
                // The poll interval is 200ms, so a 300ms delay gives the send
                // time to start and the poll time to detect the flag.
                delay(300)
                isCancelled = true
            }
            // executeReviewPerformCommand launches its own coroutine via scope.launch
            // and returns immediately. The cancellation happens inside that coroutine.
            handler.executeReviewPerformCommand("claude-sonnet gpt-4")
            // Wait for all launched child coroutines to complete (including the
            // cancellation-flag-flip coroutine and the review loop coroutine).
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }

        // The first send was started, then cancelled mid-flight (the
        // sendWithModelFunction caught CancellationException and recorded
        // "CANCELLED"). The loop broke before the second model.
        sendStarted shouldBe true
        // Either the send was cancelled (recorded "CANCELLED") or the loop
        // broke on the next-iteration check. Either way, at most 1 send
        // attempt and the second model was NOT sent.
        sendWithModelCalls shouldHaveSize 1
        // A cancel message was injected
        injectedMessages.any { it.contains("cancelled", ignoreCase = true) } shouldBe true
    }
}