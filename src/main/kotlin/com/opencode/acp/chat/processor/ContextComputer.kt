package com.opencode.acp.chat.processor

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.ControlBarState
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.ProviderModel
import com.opencode.acp.chat.model.SessionContext
import com.opencode.acp.chat.model.SessionContextState
import com.opencode.acp.chat.model.SessionListState
import com.opencode.acp.config.settings.OpenCodeContextSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracted from [SessionManager] (TDD §4.2.3). Owns session context computation:
 * token accumulation from the local message cache, model resolution with fallback,
 * context pressure monitoring, and the proportional breakdown.
 *
 * Thread safety: [fullComputeInFlight] and [localComputeInFlight] are AtomicBooleans
 * guarding concurrent computations. The published [_sessionContextState] is a
 * StateFlow (thread-safe). The active-session ID and messages are read from
 * [SessionManager] via the injected providers, which are thread-safe reads.
 *
 * Token data: inputTokens and cacheReadTokens are CUMULATIVE (last non-zero assistant
 * message); outputTokens/reasoningTokens/cacheWriteTokens/cost are PER-MESSAGE (summed).
 * See AGENTS.md § API Testing — the V1 API returns session.tokens/cost as always-zero,
 * so token data comes from the local message cache (kept accurate by MessageFinalized
 * SSE events), NOT from session metadata.
 */
class ContextComputer(
    /** Provides the currently active session ID (null if none). */
    private val activeSessionIdProvider: () -> String?,
    /** Provides the active session's messages snapshot (empty if no active session). */
    private val messagesProvider: () -> Map<String, ChatMessage>,
    /** Provides the OpenCodeClient (null if not initialized). */
    private val clientProvider: () -> OpenCodeClient?,
    /** Provides the project base path (null = no filter). */
    private val projectBasePathProvider: () -> String?,
    /** Provides the current session list state (for session title lookup). */
    private val sessionListStateProvider: () -> SessionListState,
) {

    private val logger = KotlinLogging.logger {}

    // ── Published state ──

    private val _sessionContextState = MutableStateFlow<SessionContextState>(SessionContextState.Loading)
    val sessionContextState: StateFlow<SessionContextState> = _sessionContextState.asStateFlow()

    // ── In-flight guards ──

    /** In-flight guard for full computeSessionContext (REST path). Prevents stacking
     *  concurrent full computations. Local-only refreshes use [localComputeInFlight] instead
     *  so they are not blocked by a slow REST call. */
    private val fullComputeInFlight = AtomicBoolean(false)

    /** In-flight guard for computeSessionContextLocal (no REST). Local refreshes are
     *  cheap and should not be starved by a full computation blocked on getSession(). */
    private val localComputeInFlight = AtomicBoolean(false)

    // ── Smart Context Manager: pressure monitor ──

    /** Tracks context pressure via rolling growth rate. Reset on session switch/compaction. */
    internal val pressureMonitor = ContextPressureMonitor()

    // ── Public API ──

    /** Full context computation — fetches session metadata (summary, time, model) via REST. */
    suspend fun compute(controlState: ControlBarState? = null): SessionContextState {
        // NOTE: Do NOT acquire switchMutex here — switchSession() and
        // createAndSwitchSession() already hold it when they call this method,
        // and kotlinx.coroutines.sync.Mutex is non-reentrant (would deadlock).
        // The session-switch race window (reading activeSessionId then messages) is
        // negligible — both are thread-safe reads, and a switch between them only
        // means slightly stale messages for one frame.
        val currentSessionId = activeSessionIdProvider() ?: return SessionContextState.Loading
        val messages = messagesProvider()
        val c = clientProvider() ?: return SessionContextState.Loading

        // In-flight guard: skip if another full computation is already running.
        // Uses a dedicated guard so local refreshes are not blocked.
        if (!fullComputeInFlight.compareAndSet(false, true)) {
            // Another full computation is in progress — return the current state as-is.
            // If it's Loading, the in-progress computation will replace it soon.
            return _sessionContextState.value
        }
        try {
            return computeInternal(currentSessionId, messages, c, controlState, fetchSession = true)
        } finally {
            fullComputeInFlight.set(false)
        }
    }

    /**
     * Local-only context computation — no REST call. Used during streaming and on
     * intermediate [UiSignal.MessageUpdated] signals. Reads token/cost/model data
     * from the local message cache only; summary/time fields reuse the last loaded
     * values (they don't change mid-stream).
     *
     * Thread-safe via [localComputeInFlight] — local refreshes use a separate guard
     * from full (REST) refreshes so they are not starved during streaming.
     */
    suspend fun computeLocal(controlState: ControlBarState? = null): SessionContextState {
        val currentSessionId = activeSessionIdProvider() ?: return SessionContextState.Loading
        val messages = messagesProvider()
        val c = clientProvider() ?: return SessionContextState.Loading

        // Use a separate guard so local refreshes are NOT blocked by a full computation
        // blocked on REST getSession(). Local refreshes are cheap (no REST) and should
        // update the indicator promptly during streaming.
        if (!localComputeInFlight.compareAndSet(false, true)) {
            // Another local computation is in progress — return the current state as-is.
            return _sessionContextState.value
        }
        try {
            return computeInternal(currentSessionId, messages, c, controlState, fetchSession = false)
        } finally {
            localComputeInFlight.set(false)
        }
    }

    // ── Internal ──

    /**
     * Shared computation logic for both full (REST) and local-only context computation.
     *
     * @param fetchSession if true, fetches session metadata (summary, time, model) via REST;
     *                     if false, reuses the last loaded summary/time values (they don't
     *                     change mid-stream) and falls back to controlState for model info.
     */
    private suspend fun computeInternal(
        currentSessionId: String,
        messages: Map<String, ChatMessage>,
        c: OpenCodeClient,
        controlState: ControlBarState?,
        fetchSession: Boolean,
    ): SessionContextState {
        // Best-effort session fetch — only when fetchSession=true (full path).
        // Token/cost data comes from the local message cache (kept accurate by
        // MessageFinalized SSE events), NOT from session.tokens/session.cost
        // (the V1 API returns these as always-zero — see AGENTS.md § API Testing).
        val session = if (fetchSession) {
            try {
                // sessionId validation is handled by OpenCodeClient.getSession (validatePathId)
                c.getSession(currentSessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Failed to fetch session for context" }
                null
            }
        } else {
            // Local-only path: reuse last loaded session metadata for summary/time
            // (they don't change mid-stream). Model falls back to controlState.
            null
        }

        // ── Token data ──
        // inputTokens and cacheReadTokens are CUMULATIVE (each message's value
        // represents the full prompt context sent for that LLM call, including all
        // prior messages). Use the LAST assistant message with non-zero tokens —
        // NOT a sum across all messages (that would double-count).
        // outputTokens, reasoningTokens, cacheWriteTokens, and cost are PER-MESSAGE
        // (incremental for that step). Sum these across all assistant messages.
        val assistantMessages = messages.values.filter { it.role == MessageRole.ASSISTANT }

        // Cumulative fields: last message with non-zero input tokens (not 0L fallback).
        // Falls back to the previous message's tokens when the last assistant is still
        // streaming (no MessageFinalized yet) — prevents the indicator from dropping to 0.
        val lastWithInput = assistantMessages.findLast { it.inputTokens > 0 }
        val inputTokens = lastWithInput?.inputTokens ?: 0L
        val cacheReadTokens = lastWithInput?.cacheReadTokens ?: 0L

        // Per-message fields: sum across all assistant messages
        val outputTokens = assistantMessages.sumOf { it.outputTokens }
        val reasoningTokens = assistantMessages.sumOf { it.reasoningTokens }
        val cacheWriteTokens = assistantMessages.sumOf { it.cacheWriteTokens }
        val totalTokens = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens
        val totalCost = assistantMessages.sumOf { it.cost }

        // ── Model info: from session metadata, fallback to controlState ──
        val modelId = session?.model?.id?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.modelID
        val providerId = session?.model?.providerID?.takeIf { it.isNotBlank() } ?: controlState?.selectedModel?.providerID

        val (providerName, modelName) = resolveModelNames(
            controlState?.models ?: emptyList(), modelId, providerId
        )
        val contextLimit = resolveContextLimit(
            controlState?.allModels?.ifEmpty { controlState.models } ?: emptyList(),
            providerId, modelId
        )

        val usagePercent = if (contextLimit > 0L) {
            (totalTokens.toFloat() / contextLimit.toFloat()) * 100f
        } else 0f

        // ── Smart Context Manager: record turn for pressure monitoring ──
        // Only record on the full (REST) path — local refreshes during streaming
        // would produce noisy intermediate data points.
        if (fetchSession && inputTokens > 0) {
            try {
                pressureMonitor.recordTurn(inputTokens, System.currentTimeMillis())
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] pressureMonitor.recordTurn failed" }
            }
        }

        // ── Message counts: from local cache ──
        val messageCount = messages.size
        val userMessageCount = messages.values.count { it.role == MessageRole.USER }
        val assistantMessageCount = assistantMessages.size

        val sessionTitle = (sessionListStateProvider() as? SessionListState.Loaded)
            ?.sessions?.find { it.id == currentSessionId }?.title ?: "Untitled"

        // ── Summary/time: from REST (full path) or reuse last loaded (local path) ──
        val lastLoaded = (_sessionContextState.value as? SessionContextState.Loaded)?.context
        val additions = session?.summary?.additions ?: lastLoaded?.additions ?: 0
        val deletions = session?.summary?.deletions ?: lastLoaded?.deletions ?: 0
        val filesModified = session?.summary?.files ?: lastLoaded?.filesModified ?: 0
        val sessionCreated = session?.time?.created ?: lastLoaded?.sessionCreated ?: 0L
        val lastUpdated = session?.time?.updated ?: lastLoaded?.lastUpdated ?: 0L

        // Read pruner heartbeat once — used for both breakdown adjustment and SessionContext fields
        val currentProjectBasePath = projectBasePathProvider()
        val prunerHeartbeat = if (currentProjectBasePath != null) {
            try { PrunerHeartbeatReader.readHeartbeat(currentProjectBasePath) } catch (_: Exception) { null }
        } else null

        val result = SessionContextState.Loaded(
            context = SessionContext(
                sessionId = currentSessionId,
                title = sessionTitle,
                providerID = providerId ?: "",
                modelID = modelId ?: "",
                providerName = providerName,
                modelName = modelName,
                contextLimit = contextLimit,
                totalTokens = totalTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                usagePercent = usagePercent,
                totalCost = totalCost,
                messageCount = messageCount,
                userMessageCount = userMessageCount,
                assistantMessageCount = assistantMessageCount,
                additions = additions,
                deletions = deletions,
                filesModified = filesModified,
                sessionCreated = sessionCreated,
                lastUpdated = lastUpdated,
                breakdown = computeBreakdownSafely(messages, contextLimit, totalTokens, prunerHeartbeat),
                pressure = computePressureSafely(totalTokens, contextLimit),
                prunerTokensSaved = prunerHeartbeat?.tokensSaved ?: 0,
                prunerOutputsPruned = prunerHeartbeat?.outputsPruned ?: 0,
                prunerInputsPruned = prunerHeartbeat?.inputsPruned ?: 0,
                prunerLastRunMs = prunerHeartbeat?.timestampMs ?: 0,
            )
        )
        logger.info { "[ACP] computeSessionContext(${if (fetchSession) "full" else "local"}): session=$currentSessionId totalTokens=$totalTokens cost=$totalCost usagePercent=${"%.1f".format(usagePercent)}% model=$modelId provider=$providerId" }
        // Staleness guard: don't publish if the active session changed during the
        // (possibly slow) computation. Prevents session A's context from appearing
        // under session B after a switch.
        if (currentSessionId != activeSessionIdProvider()) {
            logger.info { "[ACP] computeSessionContext: session changed during computation ($currentSessionId → ${activeSessionIdProvider()}) — discarding result" }
            return result
        }
        _sessionContextState.value = result
        return result
    }

    // ── Model resolution helpers ──

    private fun resolveModelNames(models: List<ProviderModel>, modelId: String?, providerId: String?): Pair<String, String> {
        if (modelId.isNullOrBlank() && providerId.isNullOrBlank()) return Pair("Unknown", "Unknown")
        if (models.isEmpty()) return Pair(providerId ?: "Unknown", modelId ?: "Unknown")

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null) {
            return splitDisplayName(exactMatch.displayName, exactMatch.providerID, exactMatch.modelID)
        }

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null) {
            return splitDisplayName(modelOnlyMatch.displayName, modelOnlyMatch.providerID, modelOnlyMatch.modelID)
        }

        return Pair(providerId ?: "Unknown", modelId ?: "Unknown")
    }

    /**
     * Split a displayName in "provider / model" format using the LAST occurrence
     * of " / " as the delimiter. This handles provider names that contain " / "
     * (e.g., "AI / ML Provider / gpt-4" → provider="AI / ML Provider", model="gpt-4").
     * Falls back to the provided providerID/modelID if the format doesn't match.
     */
    private fun splitDisplayName(displayName: String, providerID: String, modelID: String): Pair<String, String> {
        val separator = " / "
        val lastIdx = displayName.lastIndexOf(separator)
        if (lastIdx > 0) {
            val provider = displayName.substring(0, lastIdx)
            val model = displayName.substring(lastIdx + separator.length)
            if (provider.isNotBlank() && model.isNotBlank()) {
                return Pair(provider, model)
            }
        }
        return Pair(providerID, modelID)
    }

    private fun resolveContextLimit(models: List<ProviderModel>, providerId: String?, modelId: String?): Long {
        if (modelId.isNullOrBlank()) return 0L
        if (models.isEmpty()) return 0L

        val exactMatch = models.find { it.providerID == providerId && it.modelID == modelId }
        if (exactMatch != null && exactMatch.contextWindow > 0) return exactMatch.contextWindow.toLong()

        val modelOnlyMatch = models.find { it.modelID == modelId }
        if (modelOnlyMatch != null && modelOnlyMatch.contextWindow > 0) return modelOnlyMatch.contextWindow.toLong()

        return 0L
    }

    // ── Smart Context Manager helpers ──

    /** Compute breakdown safely — returns null on failure (non-fatal).
     *  If [prunerHeartbeat] is provided, subtracts pruner-estimated tokens saved
     *  from the tool category so the UI reflects what the LLM actually sees.
     *  @param sessionTotalTokens the server-provided total token count, used to
     *  normalize the breakdown so its total matches the session's token count. */
    private fun computeBreakdownSafely(
        messages: Map<String, ChatMessage>,
        contextLimit: Long,
        sessionTotalTokens: Long = 0L,
        prunerHeartbeat: PrunerHeartbeat? = null,
    ): com.opencode.acp.chat.model.ContextBreakdown? {
        return try {
            if (messages.isEmpty()) return null
            val breakdown = BreakdownComputer.computeBreakdown(messages, contextLimit, sessionTotalTokens)
            val prunerSaved = prunerHeartbeat?.tokensSaved ?: 0L
            if (prunerSaved > 0) {
                // Subtract pruner-saved tokens from tool category (pruning targets tool outputs).
                // Clear the per-tool breakdown map because individual tool counts are unreliable
                // after pruner adjustment — the pruner doesn't record which tools were pruned,
                // so we can't proportionally adjust per-tool entries. The UI's tool breakdown
                // sub-view is hidden when the map is empty.
                val adjustedToolTokens = (breakdown.toolTokens - prunerSaved).coerceAtLeast(0L)
                val adjustedTotal = (breakdown.totalTokens - prunerSaved).coerceAtLeast(0L)
                breakdown.copy(
                    toolTokens = adjustedToolTokens,
                    totalTokens = adjustedTotal,
                    freeTokens = (contextLimit - adjustedTotal).coerceAtLeast(0L),
                    toolBreakdown = emptyMap(),
                )
            }
            breakdown
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] BreakdownComputer.computeBreakdown failed" }
            null
        }
    }

    /** Compute pressure safely — returns null on failure or insufficient data (non-fatal). */
    private suspend fun computePressureSafely(
        totalTokens: Long,
        contextLimit: Long,
    ): com.opencode.acp.chat.model.ContextPressure? {
        return try {
            pressureMonitor.computePressure(totalTokens, contextLimit)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] pressureMonitor.computePressure failed" }
            null
        }
    }

    /** Reset the pressure monitor (called by SessionManager on session switch/compaction). */
    suspend fun resetPressureMonitor() {
        pressureMonitor.reset()
    }

    /** Notify the pressure monitor that compaction occurred (called by SessionManager on session.compacted). */
    suspend fun onCompaction() {
        pressureMonitor.onCompaction()
    }

    /** Reset breakdown calibration (called by SessionManager on session switch). */
    fun resetBreakdownCalibration() {
        BreakdownComputer.resetCalibration()
    }
}