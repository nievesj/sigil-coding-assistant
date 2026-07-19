package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock

/**
 * Maps between ACP [ContentBlock] and internal [OpenCodePart] types.
 *
 * Thin facade that delegates to [ContentBlockParser] (ACP→OpenCode) and
 * [ContentBlockSerializer] (OpenCode→ACP). Extracted per TDD §4.2.5
 * (SRP: Split ContentMapper).
 *
 * LSP fix (TDD §4.2.5): [toOpenCodePart] returns `OpenCodePart?` (nullable)
 * instead of throwing [com.opencode.acp.UnsupportedContentException] for
 * unsupported types. Callers get a consistent null-based contract.
 */
class ContentMapper {

    private val parser = ContentBlockParser()
    private val serializer = ContentBlockSerializer()

    /**
     * Converts an ACP [ContentBlock] to an [OpenCodePart].
     *
     * @return the corresponding [OpenCodePart], or null if the block type is unsupported.
     */
    fun toOpenCodePart(block: ContentBlock): OpenCodePart? = parser.toOpenCodePart(block)

    /**
     * Converts an [OpenCodePart] to an ACP [ContentBlock].
     */
    fun toContentBlock(part: OpenCodePart): ContentBlock = serializer.toContentBlock(part)

    /**
     * Converts a list of ACP [ContentBlock] to a list of [OpenCodePart].
     * Unsupported block types are filtered out.
     */
    fun toOpenCodeParts(blocks: List<ContentBlock>): List<OpenCodePart> = parser.toOpenCodeParts(blocks)

    /**
     * Converts a list of [OpenCodePart] to a list of ACP [ContentBlock].
     */
    fun toContentBlocks(parts: List<OpenCodePart>): List<ContentBlock> = serializer.toContentBlocks(parts)
}