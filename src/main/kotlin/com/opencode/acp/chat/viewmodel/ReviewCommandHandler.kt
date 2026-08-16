package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.StagedFilesResult
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewMessages
import com.opencode.acp.review.ReviewSkill
import com.opencode.acp.util.ModelArgResolver
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes the `/review-*` slash commands.
 *
 * Extracted from [ChatViewModel] per TDD §4.2.3. Owns:
 *  - `/review-perform [model...]` — adversarial review of VCS-changed files
 *  - `/review-perform-gaming [model...]` — game-engine-specific checklist variant
 *  - `/review-resolve` — summarize open review comments + resolution workflow
 *  - `/review-recheck [model...]` — re-run review with existing comments as context
 *  - the multi-model review loop ([executeMultiModelReview]) with cancellation
 *  - the reply-preservation safety net for `/review-recheck`
 *
 * The actual message send is delegated to the [sendFunction] and
 * [sendWithModelFunction] callbacks (which route through the ViewModel's
 * `sendMessage` / `sendMessageWithModel` so `_streamPhase` and
 * `streamingSessionIds` stay consistent with the UI).
 *
 * @param scope Coroutine scope for launching review command coroutines.
 * @param project The IntelliJ project — used for [ReviewCommentManager.getInstance].
 * @param gitService Cached [GitService] for fetching VCS-changed files.
 * @param controlStateProvider Returns the current [com.opencode.acp.chat.model.ControlBarState]
 *   (for the selected model, thinking effort, and available models list).
 * @param sendFunction Suspends to send a message with the control-bar model.
 * @param sendWithModelFunction Suspends to send a message with an explicit model.
 * @param injectLocalMessage Injects a local (non-LLM) message into the chat.
 * @param refreshReviewFiles Triggers a disk re-read of `.review/` files; returns
 *   the launched [kotlinx.coroutines.Job] so callers can `.join()` it.
 * @param isCancelledProvider Returns true if the user clicked Cancel (sets the
 *   multi-model review cancellation flag). The loop checks this between iterations.
 * @param resetCancelled Resets the multi-model review cancellation flag (called
 *   at the start of [executeMultiModelReview] and after the loop completes).
 */
class ReviewCommandHandler(
    private val scope: CoroutineScope,
    private val project: Project,
    private val gitService: GitService,
    private val controlStateProvider: () -> com.opencode.acp.chat.model.ControlBarState,
    private val sendFunction: suspend (String, List<AttachedFile>) -> SendMessageResult,
    private val sendWithModelFunction: suspend (
        text: String,
        modelID: String?,
        providerID: String?,
        variant: String?,
        model: OpenCodeClient.MessageModel?,
    ) -> SendMessageResult,
    private val injectLocalMessage: (String) -> Unit,
    private val refreshReviewFiles: () -> kotlinx.coroutines.Job,
    private val isCancelledProvider: () -> Boolean,
    private val resetCancelled: () -> Unit,
) {

    private val logger = KotlinLogging.logger {}

    /** Execute `/review-perform [model...]` — instructs the LLM to adversarially
     *  review the VCS-changed files and add review comments to `.review/` JSON files.
     *
     *  ## Model selection
     *
     *  - **No args** (`/review-perform`): uses the currently-selected control-bar
     *    model. Backward-compatible with the original behavior.
     *  - **One or more model args** (`/review-perform glm5.2 claude-sonnet`):
     *    each arg is fuzzy-matched against the server-fetched model list
     *    ([ModelArgResolver]) and the review prompt is sent once per matched
     *    model, **sequentially** (each response completes before the next starts).
     *    Each response is prefixed with a `### Review by <model>` header so the
     *    user can compare findings across models in the same chat thread.
     *  - **`*` wildcard** (`/review-perform *`): runs the review on all available
     *    models. Use with caution — can be slow and costly.
     *  - **Unresolved args**: if any arg doesn't match a model, an error message
     *    is shown in the chat and only the resolved models run (or the control-bar
     *    model if none resolved). */
    fun executeReviewPerformCommand(args: String = "") {
        scope.launch {
            executeReviewWithPromptBuilder(args) { changedPaths ->
                ReviewSkill.buildPerformPrompt(changedPaths)
            }
        }
    }

    /** Execute `/review-perform-gaming [model...]` — like
     *  [executeReviewPerformCommand] but injects the game-engine-specific
     *  adversarial checklist (Unreal C++ GC/threading/lifecycle, Unity C#
     *  allocations/coroutines/leaks, frame budgets, Blueprint interop,
     *  replication). Model arg handling is identical to
     *  [executeReviewPerformCommand]. */
    fun executeReviewPerformGamingCommand(args: String = "") {
        scope.launch {
            executeReviewWithPromptBuilder(args) { changedPaths ->
                ReviewSkill.buildPerformGamingPrompt(changedPaths)
            }
        }
    }

    /**
     * Shared staging-check + multi-model-review logic for both
     * [executeReviewPerformCommand] and [executeReviewPerformGamingCommand].
     *
     * Fetches staged files, handles the three non-Staged branches
     * (NothingStaged/NoGitRepository/Error), and delegates to
     * [executeMultiModelReview] with the [promptBuilder]-produced prompt.
     */
    private suspend fun executeReviewWithPromptBuilder(
        args: String,
        promptBuilder: (List<String>) -> String,
    ) {
        // GitService.getStagedFiles must run inside a read action.
        // Uses Dispatchers.IO because runReadActionBlocking may spin-wait
        // for a write action to complete — IO threads handle blocking.
        val result = withContext(Dispatchers.IO) {
            runReadActionBlocking {
                gitService.getStagedFiles()
            }
        }
        when (result) {
            is StagedFilesResult.Staged -> {
                // Sanitize file paths before interpolating into the LLM prompt.
                // Paths from git diff are semi-trusted but a malicious repo could
                // contain a file whose path is an instruction string with newlines
                // (legal on Linux/macOS). Stripping control chars prevents the path
                // from breaking out of the delimited file list in the prompt.
                val changedPaths = result.files.map { sanitizeFilePathForPrompt(it.filePath) }
                val prompt = promptBuilder(changedPaths)
                executeMultiModelReview(args, prompt)
            }

            StagedFilesResult.NothingStaged -> {
                injectLocalMessage(ReviewMessages.NOTHING_STAGED)
            }

            StagedFilesResult.NoGitRepository -> {
                injectLocalMessage(ReviewMessages.NO_GIT_REPO)
            }

            is StagedFilesResult.Error -> {
                injectLocalMessage(ReviewMessages.GIT_ERROR_PREFIX + result.message)
            }
        }
    }

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow. */
    fun executeReviewResolveCommand() {
        scope.launch {
            val index = ReviewCommentManager.getInstance(project).getIndex()
            // Route through the ViewModel's sendMessage() so _streamPhase,
            // streamingSessionIds, and recordCommand() stay consistent with the UI.
            sendFunction(ReviewSkill.buildResolvePrompt(index), emptyList())
        }
    }

    /** Execute `/review-recheck [model...]` — re-runs the adversarial review with
     *  existing comments + replies as context. The LLM verifies replies against
     *  the actual code, re-raises unresolved issues, marks resolved comments, and
     *  adds new comments. Model arg handling is identical to
     *  [executeReviewPerformCommand] (via [executeMultiModelReview]).
     *
     *  ## Reply preservation safety net
     *
     *  After the LLM finishes and [refreshReviewFiles] re-reads the `.review/` files,
     *  the plugin verifies no pre-existing replies were dropped by the LLM's file
     *  rewrite and re-merges any that were via [ReviewCommentManager.restoreMissingReplies].
     *  This is a structural guarantee independent of prompt compliance — see TDD §4. */
    fun executeReviewRecheckCommand(args: String = "") {
        scope.launch {
            val manager = ReviewCommentManager.getInstance(project)
            val preRecheckIndex = manager.getIndex()
            val replySnapshot = manager.snapshotReplyIds(preRecheckIndex)
            val result = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getStagedFiles()
                }
            }
            when (result) {
                is StagedFilesResult.Staged -> {
                    // Sanitize file paths before interpolating into the LLM prompt
                    // (see executeReviewWithPromptBuilder for rationale).
                    val changedPaths = result.files.map { sanitizeFilePathForPrompt(it.filePath) }
                    val prompt = ReviewSkill.buildRecheckPrompt(preRecheckIndex, changedPaths)
                    try {
                        executeMultiModelReview(args, prompt)
                    } finally {
                        // After the LLM writes updated .review/ files, refresh the index
                        // and WAIT for loadAll() to finish before checking for dropped replies.
                        // The restore reads stateHolder.value which must reflect the post-LLM state.
                        //
                        // NonCancellable: reply preservation must run even if the user
                        // cancelled mid-recheck — otherwise the LLM's partial .review/ rewrite
                        // could drop replies and the safety net would be skipped. Wrapping
                        // in NonCancellable ensures the refresh + restore completes regardless
                        // of cancellation state.
                        withContext(NonCancellable) {
                            refreshReviewFiles().join()
                            // Structural safety net: re-merge any replies the LLM dropped.
                            val restored = manager.restoreMissingReplies(replySnapshot, preRecheckIndex)
                            if (restored > 0) {
                                logger.warn { "[ACP] /review-recheck restored $restored dropped reply(ies)" }
                                refreshReviewFiles()
                            }
                        }
                    }
                }

                StagedFilesResult.NothingStaged -> {
                    injectLocalMessage(ReviewMessages.NOTHING_STAGED)
                    return@launch
                }

                StagedFilesResult.NoGitRepository -> {
                    injectLocalMessage(ReviewMessages.NO_GIT_REPO)
                    return@launch
                }

                is StagedFilesResult.Error -> {
                    injectLocalMessage(ReviewMessages.GIT_ERROR_PREFIX + result.message)
                    return@launch
                }
            }
        }
    }

    /** Shared logic for both review-perform variants: resolve model args and
     *  send the prompt once per model (or once with the control-bar model if
     *  no args). Sequential — each send blocks until that model's response
     *  completes (via the service's sendMutex + responseDeferred).
     *
     *  The user can cancel the loop by clicking the Cancel button, which sets
     *  the cancellation flag (via [isCancelledProvider]). The loop checks this
     *  flag between iterations AND during each in-flight send (via a polling
     *  race — see [sendWithCancellationPoll]). If the flag flips during a send,
     *  the in-flight send's coroutine is cancelled, interrupting the stream.
     *  This ensures Cancel stops the CURRENT response, not just remaining models.
     *
     *  `resetCancelled()` is called in a `finally` block so the flag is always
     *  cleared — even if a send throws an exception or the coroutine is cancelled. */
    private suspend fun executeMultiModelReview(args: String, prompt: String) {
        resetCancelled()
        if (args.isBlank()) {
            // No model args — use the currently-selected control-bar model.
            // Route through the ViewModel's sendMessage() so _streamPhase and
            // streamingSessionIds stay consistent with the UI.
            try {
                sendFunction(prompt, emptyList())
            } finally {
                resetCancelled()
            }
            return
        }

        // Use connected-providers models only (controlState.models), NOT
        // allModels — allModels includes disconnected providers whose models
        // would 500 when sent to the server.
        val models = controlStateProvider().models
        val resolution = ModelArgResolver.resolveAll(args, models)

        // Surface unresolved args as a chat message so the user sees the typo.
        if (resolution.unresolved.isNotEmpty()) {
            // Escape user-provided args to prevent markdown injection (e.g., [evil](http://malicious.com)
            // rendering as a link). Use double-backtick code spans (`` `` ``) so user-supplied
            // backticks cannot break out of the code span (single-backtick spans are terminated
            // by any backtick regardless of backslash escaping per CommonMark).
            val unresolvedStr = resolution.unresolved.joinToString(", ") { "`` ${escapeMarkdownInline(it)} ``" }
            val availableHints = models.take(5).joinToString(", ") {
                "`` ${escapeMarkdownInline("${it.providerID}/${it.modelID}")} ``"
            }
            val errorMsg = "[User Notification] ⚠️ Could not resolve model(s): $unresolvedStr. " +
                    "Available models include: $availableHints" +
                    if (models.size > 5) ", …" else "."
            injectLocalMessage(errorMsg)
        }

        if (resolution.models.isEmpty()) {
            // Nothing resolved — don't run a review with the wrong model silently.
            resetCancelled()
            return
        }

        // Send one review per model, sequentially.
        // For reasoning models that have variants, pick the first variant
        // (or the control-bar's current thinking effort if the model supports it).
        val currentVariant = controlStateProvider().thinkingEffort.variant
        try {
            for (model in resolution.models) {
                currentCoroutineContext().ensureActive()
                // Check if the user cancelled the review loop (via Cancel button).
                if (isCancelledProvider()) {
                    injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                    break
                }
                // If the model has variants and the current thinking effort isn't
                // null, use it. Otherwise pick the first variant if available, or
                // null (server default) if the model has no variants.
                val variant = when {
                    model.variants.isEmpty() -> null
                    currentVariant != null && currentVariant in model.variants -> currentVariant
                    else -> model.variants.firstOrNull()
                }
                val header = "### Review by ${sanitizeModelName(model.displayName)}\n\n"
                // Re-check cancellation flag immediately before send to close TOCTOU window
                // (cancel() may have set the flag between the loop-top check and this point).
                if (isCancelledProvider()) {
                    injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                    break
                }
                // Route through the ViewModel's sendMessageWithModel() so _streamPhase and
                // streamingSessionIds stay consistent with the UI (stop button, spinner).
                // sendWithCancellationPoll launches the send in a child job and concurrently
                // polls the cancellation flag — if the user cancels DURING the send, the
                // child job is cancelled, interrupting the in-flight stream.
                val result = try {
                    sendWithCancellationPoll {
                        sendWithModelFunction(
                            header + prompt,
                            model.modelID,
                            model.providerID,
                            variant,
                            OpenCodeClient.MessageModel(providerID = model.providerID, modelID = model.modelID)
                        )
                    }
                } catch (e: CancellationException) {
                    // The send was cancelled mid-flight (user clicked Cancel during the stream).
                    // The polling helper cancels the child job, which propagates as CancellationException
                    // out of await. Inject the cancel message and break.
                    injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                    break
                }
                // If a review fails (timeout, error), stop the loop — no point
                // continuing with the remaining models if the session is in a
                // bad state.
                if (result is SendMessageResult.Error) {
                    // If the user cancelled AND the send failed, prefer the cancel message
                    if (isCancelledProvider()) {
                        injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                    } else {
                        injectLocalMessage(
                            "⚠️ Review with ${model.displayName} failed: ${result.message}. " +
                                    "Remaining models skipped."
                        )
                    }
                    break
                }
            }
        } finally {
            // Reset the cancellation flag after the loop completes, breaks, or throws
            // — so the next review invocation starts with a clean state. The finally
            // block ensures the flag is cleared even if a send throws an exception or
            // the coroutine is cancelled (CancellationException would skip a bare
            // resetCancelled() call after the loop).
            resetCancelled()
        }
    }

    /**
     * Runs [sendBlock] in a child coroutine while concurrently polling the
     * cancellation flag ([isCancelledProvider]). If the flag flips to true
     * during the send, the child coroutine is cancelled — interrupting the
     * in-flight stream so the user's Cancel takes effect immediately rather
     * than after the current model's response completes.
     *
     * The poll interval is 200ms — fast enough that Cancel feels responsive,
     * slow enough that it doesn't measurably load the CPU during long streams.
     *
     * If the send completes (Success or Error) before cancellation, its result
     * is returned. If cancelled, the child job's [CancellationException] propagates.
     */
    private suspend fun sendWithCancellationPoll(
        sendBlock: suspend () -> SendMessageResult,
    ): SendMessageResult = coroutineScope {
        val result = CompletableDeferred<SendMessageResult>()
        // Launch the actual send in a child job so we can cancel it independently.
        val sendJob = launch {
            try {
                result.complete(sendBlock())
            } catch (e: CancellationException) {
                // Propagate cancellation to the awaiter below.
                result.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                // Any other throwable — complete exceptionally so await sees it.
                result.completeExceptionally(e)
            }
        }
        // Poll the cancellation flag concurrently. If the user cancels,
        // cancel the send job — this interrupts the in-flight stream.
        try {
            while (!result.isCompleted) {
                if (isCancelledProvider()) {
                    sendJob.cancel()
                    // await the cancellation so CancellationException propagates
                    throw CancellationException("Review cancelled by user")
                }
                delay(CANCEL_POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // Ensure the send job is cancelled before we propagate.
            sendJob.cancel()
            throw e
        }
        // If the poll loop exited because result completed, await the result.
        // If the send threw, this re-throws the original exception.
        result.await()
    }

    /**
     * Sanitize a server-provided model display name before interpolating it into
     * an LLM prompt. Strips markdown header syntax (`#`) and newlines that could
     * be used for prompt injection (e.g., a displayName like
     * `"claude\n\n<ignore previous instructions>"` would inject content into
     * the prompt). The display name is server-controlled data that should be
     * treated as untrusted at the trust boundary.
     */
    private fun sanitizeModelName(name: String): String {
        return name
            .replace("#", "")       // Prevent markdown header injection
            .replace("\n", " ")      // Prevent line-break-based prompt injection
            .replace("\r", " ")      // Handle Windows-style line endings
            .trim()
            .ifBlank { "unknown" }  // Never produce an empty header
    }

    /**
     * Sanitize a file path before interpolating it into an LLM prompt. File paths
     * from `git diff` are semi-trusted (from the user's own staged changes) but a
     * malicious or unusual repo could contain a file whose path is an instruction
     * string (e.g. a Linux file literally named `\n\nIgnore previous instructions...`).
     * This strips newlines/control chars that could break out of the delimited file
     * list and inject content into the prompt (CWE-89 / LLM prompt injection).
     *
     * The review prompt wraps each path in backticks and the whole list under a
     * `### Files to review` header. Stripping newlines ensures a path can't start
     * a new markdown header or paragraph that the LLM would treat as instructions.
     */
    private fun sanitizeFilePathForPrompt(path: String): String {
        return path
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\u0000", "")   // NUL — strip entirely
            .trim()
            .ifBlank { "(empty path)" }
    }

    companion object {
        /** Poll interval for the cancellation-during-send race (ms). */
        private const val CANCEL_POLL_INTERVAL_MS = 200L
    }

    /**
     * Escape markdown inline syntax that could be used for injection (links, images,
     * and code-span-breaking backticks). Escapes brackets/parens that form link/image
     * syntax: [text](url) and ![alt](url). Also escapes backticks so that wrapping
     * the result in a double-backtick code span (`` `` ... ``) cannot be broken by
     * a user-supplied backtick.
     *
     * Note: Single-backtick code spans (`` `...` ``) are terminated by ANY backtick
     * regardless of backslash escaping (CommonMark spec). Callers MUST use
     * double-backtick delimiters (`` `` ${escapeMarkdownInline(it)} `` ``) to
     * actually prevent code-span termination.
     */
    private fun escapeMarkdownInline(text: String): String =
        text.replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("`", "\\`")
}