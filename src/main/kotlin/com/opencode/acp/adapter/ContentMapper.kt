package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock
import com.opencode.acp.UnsupportedContentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Maps between ACP [ContentBlock] and internal [OpenCodePart] types.
 */
class ContentMapper {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Converts an ACP [ContentBlock] to an [OpenCodePart].
     *
     * Mapping rules:
     * - ContentBlock.Text -> OpenCodePart.Text
     * - ContentBlock.Image -> throws UnsupportedContentException
     * - ContentBlock.Audio -> throws UnsupportedContentException
     * - ContentBlock.Resource -> throws UnsupportedContentException
     * - ContentBlock.ResourceLink -> throws UnsupportedContentException
     */
    fun toOpenCodePart(block: ContentBlock): OpenCodePart = when (block) {
        is ContentBlock.Text -> OpenCodePart.Text(
            text = block.text
        )
        is ContentBlock.Image -> throw UnsupportedContentException("Image")
        is ContentBlock.Audio -> throw UnsupportedContentException("Audio")
        is ContentBlock.Resource -> throw UnsupportedContentException("Resource")
        is ContentBlock.ResourceLink -> throw UnsupportedContentException("ResourceLink")
    }

    /**
     * Converts an [OpenCodePart] to an ACP [ContentBlock].
     *
     * Mapping rules:
     * - OpenCodePart.Text -> ContentBlock.Text
     * - OpenCodePart.ToolUse -> ContentBlock.Text (serialized JSON representation of the tool call)
     * - OpenCodePart.ToolResult -> ContentBlock.Text (concatenated result text)
     */
    fun toContentBlock(part: OpenCodePart): ContentBlock = when (part) {
        is OpenCodePart.Text -> ContentBlock.Text(
            text = part.text
        )
        is OpenCodePart.File -> ContentBlock.Text(
            text = "[File: ${part.filename ?: part.url} (${part.mime})]"
        )
        is OpenCodePart.ToolUse -> {
            val toolCallJson = buildString {
                append("Tool call: ")
                append(part.name)
                append(" (id: ")
                append(part.id)
                append(")")
                if (part.input.isNotEmpty()) {
                    append("\nInput: ")
                    append(json.encodeToJsonElement(part.input).toString())
                }
            }
            ContentBlock.Text(text = toolCallJson)
        }
        is OpenCodePart.ToolResult -> {
            val resultText = part.content.joinToString("\n") { childPart ->
                when (childPart) {
                    is OpenCodePart.Text -> childPart.text
                    is OpenCodePart.File -> "[File: ${childPart.filename ?: childPart.url}]"
                    is OpenCodePart.ToolUse -> "[ToolUse: ${childPart.name} (${childPart.id})]"
                    is OpenCodePart.ToolResult -> "[ToolResult: ${childPart.toolUseId}]"
                    is OpenCodePart.StepStart -> "[StepStart]"
                    is OpenCodePart.StepFinish -> "[StepFinish]"
                    is OpenCodePart.Thinking -> childPart.text
                    is OpenCodePart.Reasoning -> childPart.text
                    is OpenCodePart.Image -> "[Image: ${childPart.filename ?: childPart.url}]"
                    is OpenCodePart.Patch -> "[Patch: ${childPart.hash}]"
                    is OpenCodePart.Agent -> "[Agent: ${childPart.name}]"
                    is OpenCodePart.Retry -> "[Retry: ${childPart.attempt}/${childPart.maxAttempts}]"
                    is OpenCodePart.Compaction -> "[Compaction]"
                    is OpenCodePart.Snapshot -> "[Snapshot: ${childPart.id}]"
                    is OpenCodePart.Subtask -> "[Subtask: ${childPart.description ?: childPart.prompt}]"
                    is OpenCodePart.Unknown -> "[${childPart.type}]"
                }
            }
            val fullText = if (part.isError) {
                "Error: $resultText"
            } else {
                resultText
            }
            ContentBlock.Text(text = fullText)
        }
        is OpenCodePart.StepStart -> ContentBlock.Text(text = "[StepStart]")
        is OpenCodePart.StepFinish -> ContentBlock.Text(text = "[StepFinish]")
        is OpenCodePart.Thinking -> ContentBlock.Text(text = part.text)
        is OpenCodePart.Reasoning -> ContentBlock.Text(text = part.text)
        is OpenCodePart.Image -> ContentBlock.Text(text = "[Image: ${part.filename ?: part.url}]")
        is OpenCodePart.Patch -> ContentBlock.Text(text = "[Patch: ${part.hash} — ${part.files.size} file(s)]")
        is OpenCodePart.Agent -> ContentBlock.Text(text = "[Agent: ${part.name}]")
        is OpenCodePart.Retry -> ContentBlock.Text(text = "[Retry: ${part.attempt}/${part.maxAttempts}]")
        is OpenCodePart.Compaction -> ContentBlock.Text(text = "[Compaction]")
        is OpenCodePart.Snapshot -> ContentBlock.Text(text = "[Snapshot: ${part.id}]")
        is OpenCodePart.Subtask -> ContentBlock.Text(text = "[Subtask: ${part.description ?: part.prompt}]")
        is OpenCodePart.Unknown -> ContentBlock.Text(text = "[${part.type}]")
    }

    /**
     * Converts a list of ACP [ContentBlock] to a list of [OpenCodePart].
     * Blocks that throw UnsupportedContentException are skipped.
     */
    fun toOpenCodeParts(blocks: List<ContentBlock>): List<OpenCodePart> =
        blocks.mapNotNull { block ->
            try {
                toOpenCodePart(block)
            } catch (_: UnsupportedContentException) {
                null
            }
        }

    /**
     * Converts a list of [OpenCodePart] to a list of ACP [ContentBlock].
     */
    fun toContentBlocks(parts: List<OpenCodePart>): List<ContentBlock> =
        parts.map { toContentBlock(it) }
}
