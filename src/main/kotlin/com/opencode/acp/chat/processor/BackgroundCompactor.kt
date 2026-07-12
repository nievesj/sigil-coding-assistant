package com.opencode.acp.chat.processor

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.CompactionConstants
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings for background compaction behavior.
 * Stored in OpenCodeSettingsState, exposed in Tools → Sigil → Context.
 * Default: ON — background compaction provides instant swap with no user action.
 */
data class BackgroundCompactorSettings(
    val enabled: Boolean = true,
    val checkpointThresholdPercent: Float = CompactionConstants.DEFAULT_CHECKPOINT_THRESHOLD_PERCENT,
    val swapThresholdPercent: Float = CompactionConstants.DEFAULT_SWAP_THRESHOLD_PERCENT,
    val maxCheckpointAgeMs: Long = CompactionConstants.MAX_CHECKPOINT_AGE_MS,
)

/**
 * Pre-computes compaction summaries in the background.
 *
 * When context usage exceeds [BackgroundCompactorSettings.checkpointThresholdPercent],
 * a background coroutine snapshots the current message history and requests a
 * compaction summary from the server. The summary is stored and ready for instant
 * swap when the user triggers manual compaction or when auto-compaction fires.
 *
 * If no checkpoint is available at swap time (e.g., context grew too fast),
 * falls back to live compaction (standard POST /session/{id}/summarize).
 *
 * Thread safety:
 * - [checkpointSummary] is @Volatile — reads are lock-free
 * - [isCheckpointInProgress] is AtomicBoolean — prevents concurrent checkpoints
 * - Compaction operates on a SHALLOW COPY of the message map (reference copy),
 *   not a deep copy. ChatMessage is a data class, so individual messages are
 *   immutable once created.
 *
 * Coroutine lifecycle:
 * - The background coroutine is launched in a [CoroutineScope] tied to the
 *   session lifecycle. On session switch, [clearCheckpoint] cancels the
 *   in-progress Job AND clears the checkpoint, preventing stale writes.
 * - [maxCheckpointAgeMs] provides a safety net: even if cancellation is delayed,
 *   checkpoints older than the threshold are discarded on read.
 *
 * @param client the OpenCode HTTP client
 * @param settings background compaction settings
 * @param scope CoroutineScope tied to the active session lifecycle
 */
@Deprecated("Auto-trigger disabled — server /summarize performs actual compaction. Do NOT re-enable auto-trigger. See AGENTS.md 'Smart Compaction & Context Management'.")
class BackgroundCompactor(
    private val client: OpenCodeClient,
    private val settings: BackgroundCompactorSettings,
    private val scope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var checkpointSummary: CompactionCheckpoint? = null

    private val isCheckpointInProgress = AtomicBoolean(false)

    /** Track the in-progress checkpoint coroutine for cancellation on session switch. */
    @Volatile
    private var checkpointJob: Job? = null

    data class CompactionCheckpoint(
        val sessionId: String,
        val providerID: String,       // model at checkpoint time — validated at swap
        val modelID: String,          // model at checkpoint time — validated at swap
        val summary: String,
        val messageCountAtCheckpoint: Int,  // total message count for staleness display
        val tokensAtCheckpoint: Long,
        val createdAtMs: Long,
    )

    /**
     * Called after each assistant response completes.
     * If context usage exceeds the checkpoint threshold and no checkpoint is in progress,
     * launches a background coroutine to pre-compute the compaction summary.
     *
     * Messages are shallow-copied (reference copy of the map) — safe because
     * ChatMessage is an immutable data class.
     */
    fun maybeCheckpoint(
        sessionId: String,
        messages: Map<String, ChatMessage>,
        contextLimit: Long,
        providerID: String,
        modelID: String,
    ) {
        if (!settings.enabled) return
        if (contextLimit <= 0L) return

        val totalTokens = messages.values.sumOf { it.inputTokens + it.outputTokens }
        val usagePercent = (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        if (usagePercent < settings.checkpointThresholdPercent) return

        if (!isCheckpointInProgress.compareAndSet(false, true)) return

        logger.info {
            "[ACP] BackgroundCompactor: starting checkpoint at ${"%.1f".format(usagePercent)}% " +
                "($totalTokens/$contextLimit tokens, ${messages.size} messages)"
        }

        checkpointJob = scope.launch {
            try {
                val success = client.compactSession(
                    sessionId = sessionId,
                    providerID = providerID,
                    modelID = modelID,
                    auto = false,
                )
                if (success) {
                    // The server's compaction is synchronous — when compactSession returns,
                    // the summary is ready. The session.compacted SSE event fires as a
                    // side effect. We store a lightweight checkpoint marker; the actual
                    // summary lives on the server.
                    checkpointSummary = CompactionCheckpoint(
                        sessionId = sessionId,
                        providerID = providerID,
                        modelID = modelID,
                        summary = "checkpoint-ready",
                        messageCountAtCheckpoint = messages.size,
                        tokensAtCheckpoint = totalTokens,
                        createdAtMs = System.currentTimeMillis(),
                    )
                    logger.info { "[ACP] BackgroundCompactor: checkpoint created for session $sessionId" }
                } else {
                    logger.warn { "[ACP] BackgroundCompactor: server returned false for checkpoint" }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] BackgroundCompactor: checkpoint failed" }
            } finally {
                isCheckpointInProgress.set(false)
            }
        }
    }

    /**
     * Returns the pre-computed checkpoint if available, for the correct session,
     * and for the current model. Returns null if:
     * - No checkpoint exists
     * - Session ID doesn't match (session was switched)
     * - Model changed since checkpoint was created (model mismatch guard)
     * - Checkpoint is older than [BackgroundCompactorSettings.maxCheckpointAgeMs]
     */
    fun getCheckpoint(sessionId: String, currentProviderID: String, currentModelID: String): CompactionCheckpoint? {
        val cp = checkpointSummary ?: return null
        if (cp.sessionId != sessionId) return null
        if (cp.providerID != currentProviderID || cp.modelID != currentModelID) return null
        if (System.currentTimeMillis() - cp.createdAtMs > settings.maxCheckpointAgeMs) return null
        return cp
    }

    /** Whether a checkpoint is available and valid for the given session/model. */
    fun hasValidCheckpoint(sessionId: String, currentProviderID: String, currentModelID: String): Boolean {
        return getCheckpoint(sessionId, currentProviderID, currentModelID) != null
    }

    /**
     * Clears the checkpoint and cancels any in-progress background coroutine.
     * Called on session switch, after successful swap, on error, and after
     * server-side compaction (session.compacted SSE event) to discard stale data.
     */
    fun clearCheckpoint() {
        checkpointJob?.cancel()
        checkpointJob = null
        checkpointSummary = null
        isCheckpointInProgress.set(false)
    }
}