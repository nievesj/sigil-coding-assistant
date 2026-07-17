package com.opencode.acp.chat.viewmodel

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.service.GitService
import com.opencode.acp.review.ReviewCommentManager
import com.opencode.acp.review.ReviewSkill
import com.opencode.acp.util.ModelArgResolver
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
    private val sendFunction: suspend (String) -> SendMessageResult,
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
            // GitService.getChangedFiles must run inside a read action.
            // Uses Dispatchers.IO because runReadActionBlocking may spin-wait
            // for a write action to complete — IO threads handle blocking.
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildPerformPrompt(changedPaths)
            executeMultiModelReview(args, prompt)
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
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildPerformGamingPrompt(changedPaths)
            executeMultiModelReview(args, prompt)
        }
    }

    /** Execute `/review-resolve` — injects the [ReviewSkill.buildResolvePrompt]
     *  summarizing all open review comments and the resolution workflow. */
    fun executeReviewResolveCommand() {
        scope.launch {
            val index = ReviewCommentManager.getInstance(project).getIndex()
            // Route through the ViewModel's sendMessage() so _streamPhase,
            // streamingSessionIds, and recordCommand() stay consistent with the UI.
            sendFunction(ReviewSkill.buildResolvePrompt(index))
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
            val changedFiles = withContext(Dispatchers.IO) {
                runReadActionBlocking {
                    gitService.getChangedFiles()
                }
            }
            val changedPaths = changedFiles.map { it.filePath }
            val prompt = ReviewSkill.buildRecheckPrompt(preRecheckIndex, changedPaths)
            try {
                executeMultiModelReview(args, prompt)
            } finally {
                // After the LLM writes updated .review/ files, refresh the index
                // and WAIT for loadAll() to finish before checking for dropped replies.
                // The restore reads stateHolder.value which must reflect the post-LLM state.
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

    /** Shared logic for both review-perform variants: resolve model args and
     *  send the prompt once per model (or once with the control-bar model if
     *  no args). Sequential — each send blocks until that model's response
     *  completes (via the service's sendMutex + responseDeferred).
     *
     *  The user can cancel the loop mid-way by clicking the Cancel button,
     *  which sets the cancellation flag (via [isCancelledProvider]). The loop
     *  checks this flag between iterations and stops if set. */
    private suspend fun executeMultiModelReview(args: String, prompt: String) {
        resetCancelled()
        if (args.isBlank()) {
            // No model args — use the currently-selected control-bar model.
            // Route through the ViewModel's sendMessage() so _streamPhase and
            // streamingSessionIds stay consistent with the UI.
            sendFunction(prompt)
            return
        }

        // Use connected-providers models only (controlState.models), NOT
        // allModels — allModels includes disconnected providers whose models
        // would 500 when sent to the server.
        val models = controlStateProvider().models
        val resolution = ModelArgResolver.resolveAll(args, models)

        // Surface unresolved args as a chat message so the user sees the typo.
        if (resolution.unresolved.isNotEmpty()) {
            val unresolvedStr = resolution.unresolved.joinToString(", ") { "`$it`" }
            val availableHints = models.take(5).joinToString(", ") {
                "`${it.providerID}/${it.modelID}`"
            }
            val errorMsg = "[User Notification] ⚠️ Could not resolve model(s): $unresolvedStr. " +
                "Available models include: $availableHints" +
                if (models.size > 5) ", …" else "."
            injectLocalMessage(errorMsg)
        }

        if (resolution.models.isEmpty()) {
            // Nothing resolved — don't run a review with the wrong model silently.
            return
        }

        // Send one review per model, sequentially.
        // For reasoning models that have variants, pick the first variant
        // (or the control-bar's current thinking effort if the model supports it).
        val currentVariant = controlStateProvider().thinkingEffort.variant
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
            val header = "### Review by ${model.displayName}\n\n"
            // Re-check cancellation flag immediately before send to close TOCTOU window
            // (cancel() may have set the flag between the loop-top check and this point).
            if (isCancelledProvider()) {
                injectLocalMessage("⏹ Review cancelled by user. Remaining models skipped.")
                break
            }
            // Route through the ViewModel's sendMessageWithModel() so _streamPhase and
            // streamingSessionIds stay consistent with the UI (stop button, shimmer).
            val result = sendWithModelFunction(
                header + prompt,
                model.modelID,
                model.providerID,
                variant,
                OpenCodeClient.MessageModel(providerID = model.providerID, modelID = model.modelID)
            )
            // If a review fails (timeout, error), stop the loop — no point
            // continuing with the remaining models if the session is in a
            // bad state.
            if (result is SendMessageResult.Error) {
                injectLocalMessage(
                    "⚠️ Review with ${model.displayName} failed: ${result.message}. " +
                        "Remaining models skipped."
                )
                break
            }
        }
        // Reset the cancellation flag after the loop completes (or breaks on
        // error/cancel) so the next review invocation starts with a clean state.
        resetCancelled()
    }
}