package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.CompactionConstants
import com.opencode.acp.chat.model.ContextBreakdown
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.ToolCategoryBreakdown
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.jsonPrimitive

/**
 * Regex matching the pruner's placeholder output, capturing the embedded token count.
 * Matches: "[Output pruned — was write, ~12100 tokens]"
 * Also matches the "file modified since read" variant which has no token count.
 */
private val PRUNED_OUTPUT_REGEX = Regex(
    """\[Output pruned — was \S+, ~(\d+) tokens\]"""
)

/**
 * Extract the text content from a tool output JsonObject list.
 * The server returns output as a list of JsonObject parts, each with a "text" field.
 */
private fun extractOutputText(output: List<kotlinx.serialization.json.JsonObject>?): String {
    if (output.isNullOrEmpty()) return ""
    return output.joinToString("") { part ->
        part["text"]?.jsonPrimitive?.content ?: ""
    }
}

/**
 * Estimate tool tokens for a single tool call, accounting for pruned outputs.
 *
 * If the output has been pruned by the context pruner, the placeholder embeds the
 * original token count (e.g. "[Output pruned — was write, ~12100 tokens]"). We extract
 * and use that count directly instead of estimating from the placeholder's byte length,
 * which would severely undercount.
 *
 * @param inputBytes byte length of the tool input
 * @param output the tool output JsonObject list
 * @param charsPerToken the calibrated char-to-token ratio
 * @return estimated tokens for this tool call
 */
private fun estimateToolTokens(
    inputBytes: Long,
    output: List<kotlinx.serialization.json.JsonObject>?,
    charsPerToken: Double
): Long {
    val outputText = extractOutputText(output)
    val prunedMatch = PRUNED_OUTPUT_REGEX.find(outputText)
    if (prunedMatch != null) {
        // Output was pruned — use the embedded token count from the placeholder
        val prunedTokens = prunedMatch.groupValues[1].toLongOrNull() ?: 0L
        // Input is still present (pruner only removes output), estimate it normally
        val inputTokens = (inputBytes / charsPerToken).toLong()
        return inputTokens + prunedTokens
    }
    // Normal (unpruned) output — estimate from byte length
    val outputBytes = output?.sumOf { it.toString().length.toLong() } ?: 0L
    return ((inputBytes + outputBytes) / charsPerToken).toLong()
}

/**
 * Computes a 5-category token breakdown from the local message cache.
 *
 * Classification logic (estimates — the server does not expose per-part token counts):
 * - systemPromptTokens: Estimated from the first assistant message's inputTokens minus
 *   all prior user message tokens. Approximates system prompt + tool definitions + format
 *   tokens + attached files. Labeled "System + Tool Definitions" in the UI.
 * - userTokens: Sum of all user message text parts (estimated via char count / calibratedCharsPerToken).
 * - assistantTokens: Sum of all assistant message text parts (estimated via char count / calibratedCharsPerToken).
 * - toolTokens: Sum of all tool call + tool result parts (estimated from JSON byte size / calibratedCharsPerToken).
 * - otherTokens: reasoningTokens + cacheReadTokens + cacheWriteTokens + unclassified.
 *
 * Uses a calibrated char-to-token ratio (default ~4 chars/token, adjusted via
 * [calibrate] after each MessageFinalized SSE event) as a reasonable approximation.
 *
 * Thread safety: Uses @Volatile (not Mutex) because:
 * - Lost calibration samples from concurrent calibrate() calls are tolerable
 *   (EMA smoothly recovers from a missed data point). Concurrent calibrate()
 *   calls are EXPECTED and TOLERATED — the EMA update is not atomic, but a
 *   lost sample has negligible impact on estimation accuracy.
 * - computeBreakdown() takes a snapshot (val charsPerToken = calibratedCharsPerToken)
 *   that doesn't require consistency with concurrent calibration writes.
 * - No read-modify-write atomicity needed: each calibrate() is an independent observation.
 *
 * IMPORTANT: Must call [resetCalibration] on session switch — different sessions
 * may use different models with different char-to-token ratios (Claude ~3.5,
 * Gemini ~3.8). Carrying calibration across sessions would produce incorrect estimates.
 */
object BreakdownComputer {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var calibratedCharsPerToken: Double = CompactionConstants.DEFAULT_CHARS_PER_TOKEN

    /**
     * Generation counter — incremented on [resetCalibration]. Prevents a stale
     * EMA update from overwriting a reset: if the generation changed between
     * reading the old value and writing the EMA, the update is discarded.
     */
    @Volatile
    private var calibrationGeneration: Long = 0L

    /** Lock for calibrate() EMA update — prevents lost samples from concurrent calls. */
    private val calibrateLock = Any()

    /**
     * Reset the calibrated ratio to the default. Called on session switch to prevent
     * cross-session calibration pollution when models differ between sessions.
     */
    fun resetCalibration() {
        synchronized(calibrateLock) {
            calibratedCharsPerToken = CompactionConstants.DEFAULT_CHARS_PER_TOKEN
            calibrationGeneration++
        }
    }

    /**
     * Calibrate the char-to-token ratio from actual server data.
     * Called after each MessageFinalized SSE event when inputTokens is known.
     *
     * Thread-safe: Uses [calibrateLock] to prevent lost EMA samples from
     * concurrent calibrate() calls (e.g., rapid streaming with multiple
     * MessageFinalized events). The lock is held only for the brief EMA
     * computation — no I/O or suspension under the lock.
     *
     * Reset-safe: If [resetCalibration] is called between reading the old
     * value and writing the EMA, the generation counter changes and the
     * stale update is discarded — preventing a cross-session EMA from
     * overwriting a clean reset.
     *
     * @param estimatedChars total characters in the prompt (from local message cache)
     * @param actualTokens actual inputTokens from the server
     */
    fun calibrate(estimatedChars: Long, actualTokens: Long) {
        if (actualTokens > 0 && estimatedChars > 0) {
            val observed = estimatedChars.toDouble() / actualTokens
            val genBefore = calibrationGeneration
            synchronized(calibrateLock) {
                if (calibrationGeneration != genBefore) {
                    // resetCalibration() was called between reading genBefore and
                    // acquiring the lock. The old value is stale from a previous
                    // session — discard this sample to avoid corrupting the fresh EMA.
                    return@synchronized
                }
                // Exponential moving average: 80% old + 20% new (smooth calibration)
                calibratedCharsPerToken = 0.8 * calibratedCharsPerToken + 0.2 * observed
            }
        }
    }

    /**
     * Compute a 5-category token breakdown from the local message cache.
     *
     * The per-category estimates are normalized to [sessionTotalTokens] so
     * that the breakdown's total matches the server-provided token count.
     * Without normalization, char-based estimates diverge from the server's
     * tokenizer, causing the legend percentages and progress bar to use a
     * different denominator than the header's token count.
     *
     * @param messages the active session's message map
     * @param contextLimit the model's context window limit (0 = unknown)
     * @param sessionTotalTokens the server-provided total token count from
     *   SessionManager (inputTokens + outputTokens + reasoningTokens +
     *   cacheReadTokens + cacheWriteTokens). Used as the normalization target.
     */
    fun computeBreakdown(
        messages: Map<String, ChatMessage>,
        contextLimit: Long,
        sessionTotalTokens: Long = 0L,
    ): ContextBreakdown {
        val charsPerToken = calibratedCharsPerToken
        val messageList = messages.values.toList()

        // ── System prompt estimate ──
        // First assistant message's inputTokens = full prompt sent to the LLM,
        // which includes system prompt + tool definitions + all prior messages.
        // Subtract the estimated user message tokens before it to isolate system overhead.
        val firstAssistant = messageList.firstOrNull { it.role == MessageRole.ASSISTANT && it.inputTokens > 0 }
        val systemPromptTokens = if (firstAssistant != null) {
            val priorUserChars = messageList
                .takeWhile { it.id != firstAssistant.id }
                .filter { it.role == MessageRole.USER }
                .sumOf { msg -> estimateMessageChars(msg) }
            val priorUserTokens = (priorUserChars / charsPerToken).toLong()
            (firstAssistant.inputTokens - priorUserTokens).coerceAtLeast(0L)
        } else 0L

        // ── User tokens ──
        val userTokens = messageList
            .filter { it.role == MessageRole.USER }
            .sumOf { msg -> (estimateMessageChars(msg) / charsPerToken).toLong() }

        // ── Assistant tokens (text only, not tool calls) ──
        val assistantTokens = messageList
            .filter { it.role == MessageRole.ASSISTANT }
            .sumOf { msg -> (estimateAssistantTextChars(msg) / charsPerToken).toLong() }

        // ── Tool tokens ──
        val toolBreakdownMap = mutableMapOf<String, ToolCategoryBreakdown>()
        var toolTokens = 0L
        for (msg in messageList) {
            for (part in msg.parts.values) {
                if (part is MessagePart.ToolCall) {
                    val toolName = part.pill.toolName
                    val inputBytes = part.pill.input?.toString()?.length?.toLong() ?: 0L
                    val estimated = estimateToolTokens(inputBytes, part.pill.output, charsPerToken)
                    toolTokens += estimated
                    val existing = toolBreakdownMap[toolName]
                    toolBreakdownMap[toolName] = ToolCategoryBreakdown(
                        toolName = toolName,
                        callCount = (existing?.callCount ?: 0) + 1,
                        estimatedTokens = (existing?.estimatedTokens ?: 0L) + estimated,
                        lastCallAt = part.pill.startTimeMs ?: msg.timestamp,
                    )
                }
            }
        }

        // ── Other tokens: reasoning + cache + unclassified ──
        val reasoningTokens = messageList
            .filter { it.role == MessageRole.ASSISTANT }
            .sumOf { it.reasoningTokens }
        val cacheReadTokens = messageList
            .filter { it.role == MessageRole.ASSISTANT }
            .mapNotNull { it.cacheReadTokens.takeIf { c -> c > 0 } }
            .maxOrNull() ?: 0L  // cumulative — take the last non-zero value
        val cacheWriteTokens = messageList
            .filter { it.role == MessageRole.ASSISTANT }
            .sumOf { it.cacheWriteTokens }
        val otherTokens = reasoningTokens + cacheReadTokens + cacheWriteTokens

        val rawTotal = systemPromptTokens + userTokens + assistantTokens + toolTokens + otherTokens
        val freeTokens = contextLimit - rawTotal

        // Normalize to sessionTotalTokens so the breakdown total matches the
        // server-provided token count. Without this, char-based estimates
        // diverge from the server's tokenizer (e.g., breakdown=321k vs session=192k),
        // causing legend percentages to use the wrong denominator.
        val scale = if (sessionTotalTokens > 0 && rawTotal > 0) {
            sessionTotalTokens.toDouble() / rawTotal
        } else 1.0

        val (normSystem, normUser, normAssistant, normTool, normOther, normTotal) = if (sessionTotalTokens > 0 && rawTotal > 0) {
            NormalizedBreakdown(
                (systemPromptTokens * scale).toLong(),
                (userTokens * scale).toLong(),
                (assistantTokens * scale).toLong(),
                (toolTokens * scale).toLong(),
                (otherTokens * scale).toLong(),
                sessionTotalTokens
            )
        } else {
            NormalizedBreakdown(systemPromptTokens, userTokens, assistantTokens, toolTokens, otherTokens, rawTotal)
        }

        val normalizedToolBreakdown = toolBreakdownMap.mapValues { (_, breakdown) ->
            breakdown.copy(estimatedTokens = (breakdown.estimatedTokens * scale).toLong())
        }

        logger.debug {
            "[ACP] Breakdown: system=$normSystem user=$normUser assistant=$normAssistant " +
                "tool=$normTool other=$normOther total=$normTotal free=${contextLimit - normTotal}"
        }

        return ContextBreakdown(
            systemPromptTokens = normSystem,
            userTokens = normUser,
            assistantTokens = normAssistant,
            toolTokens = normTool,
            otherTokens = normOther,
            freeTokens = contextLimit - normTotal,
            totalTokens = normTotal,
            toolBreakdown = normalizedToolBreakdown,
        )
    }

    /** Estimate total character count of a message's text content. */
    private fun estimateMessageChars(message: ChatMessage): Long {
        var chars = 0L
        for (part in message.parts.values) {
            when (part) {
                is MessagePart.Text -> chars += part.content.length
                is MessagePart.Code -> chars += part.content.length
                is MessagePart.Table -> chars += part.rawMarkdown.length
                is MessagePart.Thinking -> chars += part.content.length
                else -> { /* skip non-text parts for char estimation */ }
            }
        }
        return chars
    }

    /** Estimate character count of an assistant message's TEXT parts only (not tool calls). */
    private fun estimateAssistantTextChars(message: ChatMessage): Long {
        var chars = 0L
        for (part in message.parts.values) {
            when (part) {
                is MessagePart.Text -> chars += part.content.length
                is MessagePart.Code -> chars += part.content.length
                is MessagePart.Table -> chars += part.rawMarkdown.length
                else -> { /* skip thinking, tool calls, etc. */ }
            }
        }
        return chars
    }

    /** Tuple for normalized breakdown values. */
    private data class NormalizedBreakdown(
        val system: Long,
        val user: Long,
        val assistant: Long,
        val tool: Long,
        val other: Long,
        val total: Long,
    )
}