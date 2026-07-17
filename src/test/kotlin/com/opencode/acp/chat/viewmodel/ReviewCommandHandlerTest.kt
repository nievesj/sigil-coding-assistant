package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.ChangedFile
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.FileChangeStatus
import com.opencode.acp.chat.model.LineDelta
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewIndex
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
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
        every { gitService.getChangedFiles() } returns listOf(changedFile("src/test.kt"))
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        sendCalls shouldHaveSize 1
        sendWithModelCalls shouldHaveSize 0
        // The perform prompt references the changed file
        sendCalls[0].text.contains("src/test.kt") shouldBe true
    }

    @Test
    fun `executeReviewPerformCommand with no args uses control bar model`() {
        every { gitService.getChangedFiles() } returns emptyList()
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        // No model args → single sendFunction call (control-bar model)
        sendCalls shouldHaveSize 1
        sendWithModelCalls shouldHaveSize 0
    }

    // ── executeReviewPerformCommand (with model args) ─────────────────────

    @Test
    fun `executeReviewPerformCommand with resolvable model arg calls sendWithModelFunction`() {
        every { gitService.getChangedFiles() } returns listOf(changedFile("src/main.kt"))
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
        every { gitService.getChangedFiles() } returns emptyList()
        controlState = ControlBarState(models = emptyList())
        runHandlerAwaiting { it.executeReviewPerformCommand("nonexistent") }

        // Unresolved arg → error injected, no sends
        injectedMessages shouldHaveSize 1
        injectedMessages[0].contains("nonexistent") shouldBe true
        sendCalls shouldHaveSize 0
        sendWithModelCalls shouldHaveSize 0
    }

    @Test
    fun `executeReviewPerformCommand with multiple models sends once per model`() {
        every { gitService.getChangedFiles() } returns emptyList()
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
        every { gitService.getChangedFiles() } returns emptyList()
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
        every { gitService.getChangedFiles() } returns emptyList()
        runHandlerAwaiting { it.executeReviewPerformCommand("") }

        // resetCancelled called at start (no-args path returns early after send)
        resetCancelledCalls shouldHaveSize 1
    }

    // ── executeReviewRecheckCommand ───────────────────────────────────────

    @Test
    fun `executeReviewRecheckCommand with no args calls sendFunction then refreshReviewFiles`() {
        every { gitService.getChangedFiles() } returns listOf(changedFile("src/recheck.kt"))
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }

        sendCalls shouldHaveSize 1
        // refreshReviewFiles is called in the finally block
        refreshCalls shouldHaveSize 1
    }

    @Test
    fun `executeReviewRecheckCommand with model args sends with model and refreshes`() {
        every { gitService.getChangedFiles() } returns emptyList()
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        val model = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        controlState = ControlBarState(models = listOf(model))
        runHandlerAwaiting { it.executeReviewRecheckCommand("claude-sonnet") }

        sendWithModelCalls shouldHaveSize 1
        refreshCalls shouldHaveSize 1
    }

    @Test
    fun `executeReviewRecheckCommand restores missing replies after refresh`() {
        every { gitService.getChangedFiles() } returns emptyList()
        every { reviewCommentManager.getIndex() } returns ReviewIndex()
        coEvery { reviewCommentManager.restoreMissingReplies(any(), any()) } returns 2
        runHandlerAwaiting { it.executeReviewRecheckCommand("") }

        // restoreMissingReplies returns 2 → triggers a second refresh
        refreshCalls shouldHaveSize 2
    }

    // ── executeReviewPerformGamingCommand ────────────────────────────────

    @Test
    fun `executeReviewPerformGamingCommand with no args calls sendFunction`() {
        every { gitService.getChangedFiles() } returns listOf(changedFile("src/game.cpp"))
        runHandlerAwaiting { it.executeReviewPerformGamingCommand("") }

        sendCalls shouldHaveSize 1
        // Gaming prompt references the changed file
        sendCalls[0].text.contains("src/game.cpp") shouldBe true
    }

    @Test
    fun `executeReviewPerformGamingCommand with model args calls sendWithModelFunction`() {
        every { gitService.getChangedFiles() } returns listOf(changedFile("src/game.cpp"))
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
        every { gitService.getChangedFiles() } returns emptyList()
        val model1 = ProviderModel("anthropic", "claude-sonnet", "Claude Sonnet")
        val model2 = ProviderModel("openai", "gpt-4", "GPT-4")
        controlState = ControlBarState(models = listOf(model1, model2))
        runHandlerAwaiting { it.executeReviewPerformCommand("*") }

        // * should resolve to all available models
        sendWithModelCalls shouldHaveSize 2
    }
}