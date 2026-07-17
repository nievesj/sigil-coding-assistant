package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.markdown.MarkdownSegment
import com.opencode.acp.chat.markdown.MarkdownSegmenter
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageRole
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.SessionItem
import com.opencode.acp.chat.model.ToolCallPill
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val converterLogger = KotlinLogging.logger {}

/**
 * Converts OpenCode REST API models to UI display models.
 *
 * Extracted from [OpenCodeModels] per TDD §4.2.5 (SRP: Split OpenCodeModels).
 * Contains the [OpenCodeSession.toSessionItem] and [OpenCodeMessage.toChatMessage]
 * extension functions.
 */

/** Convert an OpenCodeSession API model to a sidebar display model. */
fun OpenCodeSession.toSessionItem() = SessionItem(
    id = id,
    title = title.ifBlank { "New session" },
    updatedAt = time?.updated ?: time?.created ?: 0L,
    cost = cost,
    inputTokens = tokens?.input ?: 0L,
    outputTokens = tokens?.output ?: 0L,
    parentID = parentID
)

/** Convert an OpenCodeMessage API model to a ChatMessage UI model. */
fun OpenCodeMessage.toChatMessage(): ChatMessage {
    return try {
        toChatMessageInternal()
    } catch (e: Exception) {
        // Error is logged at error level AND surfaced to the user via the
        // synthetic Error part below — not silently swallowed.
        converterLogger.error(e) { "[ACP] toChatMessage FAILED for msg=${info.id}: ${e.message}" }
        val timestamp = info.createdAt?.let { raw ->
            try { java.time.Instant.parse(raw).toEpochMilli() } catch (_: Exception) { raw.toLongOrNull() ?: 0L }
        } ?: 0L
        ChatMessage(
            id = info.id,
            role = if (info.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
            parts = linkedMapOf(MessagePart.generatePartId() to MessagePart.Error("Failed to load message (see idea.log for details)")),
            timestamp = timestamp,
            isStreaming = false,
            modelID = info.modelID,
            providerID = info.providerID,
            serverMessageId = info.id,
        )
    }
}

/** Internal conversion — may throw on malformed data. */
private fun OpenCodeMessage.toChatMessageInternal(): ChatMessage {
    val role = when (info.role) {
        "user" -> MessageRole.USER
        else -> MessageRole.ASSISTANT
    }

    val partTypes = this.parts.map { it::class.simpleName ?: "Unknown" }
    converterLogger.debug { "[ACP] toChatMessage: msg=${info.id}, role=${info.role}, parts=${this.parts.size}, types=$partTypes" }

    // Build parts map from OpenCodeParts
    val parts = linkedMapOf<String, MessagePart>()

    // Tool call pills from ToolUse parts, matching with ToolResult parts
    val toolUseParts = this.parts.filterIsInstance<OpenCodePart.ToolUse>()
    val toolResultsByUseId = this.parts.filterIsInstance<OpenCodePart.ToolResult>()
        .groupBy { it.toolUseId }

    // Thinking content
    val thinkingContent = this.parts
        .filterIsInstance<OpenCodePart.Thinking>()
        .joinToString("") { it.text }
    val reasoningContent = this.parts
        .filterIsInstance<OpenCodePart.Reasoning>()
        .joinToString("") { it.text }
    val allThinking = thinkingContent + reasoningContent
    if (allThinking.isNotBlank()) {
        parts[MessagePart.generatePartId()] = MessagePart.Thinking(allThinking, state = PartState.Completed)
    }

    // Tool calls
    toolUseParts.forEach { toolUse ->
        val results = toolResultsByUseId[toolUse.id]
        val hasResult = results != null && results.isNotEmpty()
        val anyError = results?.any { it.isError } == true
        val status = when {
            !hasResult -> ToolCallStatus.IN_PROGRESS
            anyError -> ToolCallStatus.FAILED
            else -> ToolCallStatus.COMPLETED
        }
        // Extract output text from tool results for display in expanded pills
        val output = results?.flatMap { result ->
            result.content.mapNotNull { part ->
                when (part) {
                    is OpenCodePart.Text -> buildJsonObject { put("text", JsonPrimitive(part.text)) }
                    else -> {
                        converterLogger.debug { "[ACP] toChatMessage: dropping non-text part in tool result: ${part::class.simpleName}" }
                        null
                    }
                }
            }
        }?.ifEmpty { null }
        val baseKind = ToolMapper.toAcpKind(toolUse.name)
        val inputObj = toolUse.input.takeIf { it.isNotEmpty() }
        val resolvedKind = if (baseKind == ToolKind.OTHER) ToolMapper.detectKindFromInput(inputObj) else baseKind
        // Resolve title: prefer input.description, then state.title (from server), then tool name
        val resolvedTitle = inputObj?.let { input ->
            try { input["description"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        } ?: toolUse.title
        ?: toolUse.name
        converterLogger.info { "[ACP] REST ToolUse: id=${toolUse.id}, name=${toolUse.name}, kind=$baseKind->$resolvedKind, title=$resolvedTitle, stateTitle=${toolUse.title}, hasInput=${inputObj != null}, inputKeys=${inputObj?.keys}" }
        parts[toolUse.id] = MessagePart.ToolCall(
            pill = ToolCallPill(
                toolCallId = toolUse.id,
                toolName = toolUse.name,
                title = resolvedTitle,
                kind = resolvedKind,
                status = status,
                input = inputObj,
                output = output
            ),
            // Loaded from REST — already completed or failed
            state = if (anyError) PartState.Failed("Tool error") else PartState.Completed
        )
    }

    // Text content — segment into Text/Code/Table parts
    val textParts = this.parts.filterIsInstance<OpenCodePart.Text>()
    val textContent = textParts.joinToString("") { it.text }

    // File note
    val fileParts = this.parts.filterIsInstance<OpenCodePart.File>()
    val fileNote = if (fileParts.isNotEmpty()) {
        val names = fileParts.mapNotNull { it.filename }.ifEmpty { listOf("${fileParts.size} file(s)") }
        "\n\n📎 ${names.joinToString(", ")}"
    } else ""

    // Segment text content (without error) into MessageParts
    val fullText = textContent + fileNote
    if (fullText.isNotBlank()) {
        val segments = MarkdownSegmenter.segment(fullText)
        segments.forEach { segment ->
            when (segment.type) {
                MarkdownSegment.Type.TEXT -> {
                    if (segment.content.isNotBlank()) parts[MessagePart.generatePartId()] = MessagePart.Text(segment.content)
                }
                MarkdownSegment.Type.CODE -> {
                    if (segment.content.isNotBlank()) parts[MessagePart.generatePartId()] = MessagePart.Code(segment.language ?: "", segment.content)
                }
                MarkdownSegment.Type.TABLE -> {
                    val parsed = MarkdownSegmenter.parseTable(segment.content.lines())
                    if (parsed != null) {
                        parts[MessagePart.generatePartId()] = MessagePart.Table(
                            rawMarkdown = segment.content,
                            headers = parsed.header,
                            rows = parsed.rows,
                            alignments = parsed.alignments
                        )
                    } else {
                        parts[MessagePart.generatePartId()] = MessagePart.Text(segment.content)
                    }
                }
                MarkdownSegment.Type.TASK -> {
                    val state = segment.taskAttrs?.get("state") ?: "completed"
                    val status = when (state) {
                        "completed" -> ToolCallStatus.COMPLETED
                        "failed" -> ToolCallStatus.FAILED
                        else -> ToolCallStatus.IN_PROGRESS
                    }
                    val agentId = segment.taskAttrs?.get("id") ?: ""
                    // Use the agentId directly (truncated) instead of hashCode to avoid collisions.
                    val taskToolCallId = if (agentId.isBlank()) "task_${MessagePart.generatePartId()}" else "task_${agentId.take(64)}"
                    val output = listOf(kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive(segment.content))
                    ))
                    val pill = ToolCallPill(
                        toolCallId = taskToolCallId,
                        toolName = "task",
                        title = "task",
                        kind = ToolKind.OTHER,
                        status = status,
                        output = output,
                    )
                    parts[MessagePart.generatePartId()] = MessagePart.ToolCall(
                        pill = pill,
                        state = if (status == ToolCallStatus.COMPLETED) PartState.Completed else PartState.InProgress
                    )
                }
            }
        }
    }

    // Error part (separate from text-derived parts — renders with red styling, not markdown)
    val errorPart = info.error?.let { err ->
        val description = err.message?.ifBlank { null } ?: err.name.ifBlank { null }
        description?.let { d ->
            val retries = err.retries?.let { " (${it} retries)" } ?: ""
            MessagePart.Error("$d$retries")
        }
    }
    if (errorPart != null) {
        parts[MessagePart.generatePartId()] = errorPart
    }

    // Patch parts — show file change summary
    val patchParts = this.parts.filterIsInstance<OpenCodePart.Patch>()
    patchParts.forEach { patch ->
        parts[MessagePart.generatePartId()] = MessagePart.Patch(hash = patch.hash, files = patch.files)
    }

    // Agent parts — show agent badge
    val agentParts = this.parts.filterIsInstance<OpenCodePart.Agent>()
    agentParts.forEach { agent ->
        parts[MessagePart.generatePartId()] = MessagePart.Agent(name = agent.name)
    }

    // Retry parts
    val retryParts = this.parts.filterIsInstance<OpenCodePart.Retry>()
    retryParts.forEach { retry ->
        parts[MessagePart.generatePartId()] = MessagePart.Retry(attempt = retry.attempt, maxAttempts = retry.maxAttempts, error = retry.error)
    }

    // Compaction parts
    val compactionParts = this.parts.filterIsInstance<OpenCodePart.Compaction>()
    compactionParts.forEach { compaction ->
        parts[MessagePart.generatePartId()] = MessagePart.Compaction(summary = compaction.summary)
    }

    // Parse createdAt timestamp — try ISO-8601 first, fall back to epoch millis
    val timestamp = info.createdAt?.let { raw ->
        try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            raw.toLongOrNull() ?: 0L
        }
    } ?: 0L

    return ChatMessage(
        id = info.id,
        role = role,
        parts = parts,
        timestamp = timestamp,
        isStreaming = false,
        // Loaded from REST — already completed
        state = com.opencode.acp.chat.model.MessageState.Completed,
        modelID = info.modelID,
        providerID = info.providerID,
        inputTokens = info.tokens?.input ?: 0L,
        outputTokens = info.tokens?.output ?: 0L,
        reasoningTokens = info.tokens?.reasoning ?: 0L,
        cacheReadTokens = info.tokens?.cache?.read ?: 0L,
        cacheWriteTokens = info.tokens?.cache?.write ?: 0L,
        cost = info.cost ?: 0.0,
        serverMessageId = info.id,
    )
}