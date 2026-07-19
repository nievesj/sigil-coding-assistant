package com.opencode.acp.adapter

import com.agentclientprotocol.model.ContentBlock

/**
 * Parses ACP [ContentBlock]s into internal [OpenCodePart]s.
 *
 * Extracted from [ContentMapper] per TDD §4.2.5 (SRP: Split ContentMapper).
 * Handles the ACP→OpenCode direction only — see [ContentBlockSerializer]
 * for the reverse direction.
 *
 * LSP fix (TDD §4.2.5): [toOpenCodePart] returns `OpenCodePart?` (nullable)
 * instead of throwing [UnsupportedContentException] for unsupported types.
 * This follows LSP — callers get a consistent contract (null = unsupported)
 * rather than relying on exception handling for control flow. The ACP adapter
 * path ([OpenCodeAgentSession]) already used `mapNotNull` with a try/catch;
 * now it can use `mapNotNull` directly.
 */
class ContentBlockParser {

    /**
     * Converts an ACP [ContentBlock] to an [OpenCodePart].
     *
     * Mapping rules:
     * - ContentBlock.Text -> OpenCodePart.Text
     * - ContentBlock.Image -> null (unsupported)
     * - ContentBlock.Audio -> null (unsupported)
     * - ContentBlock.Resource -> null (unsupported)
     * - ContentBlock.ResourceLink -> null (unsupported)
     *
     * @return the corresponding [OpenCodePart], or null if the block type is unsupported.
     */
    fun toOpenCodePart(block: ContentBlock): OpenCodePart? = when (block) {
        is ContentBlock.Text -> OpenCodePart.Text(
            text = block.text
        )
        is ContentBlock.Image -> null
        is ContentBlock.Audio -> null
        is ContentBlock.Resource -> null
        is ContentBlock.ResourceLink -> null
    }

    /**
     * Converts a list of ACP [ContentBlock] to a list of [OpenCodePart].
     * Unsupported block types (null results from [toOpenCodePart]) are filtered out.
     */
    fun toOpenCodeParts(blocks: List<ContentBlock>): List<OpenCodePart> =
        blocks.mapNotNull { block -> toOpenCodePart(block) }
}