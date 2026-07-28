package com.opencode.acp.chat.markdown

import org.jetbrains.jewel.markdown.MarkdownBlock

/**
 * Clamp non-positive [MarkdownBlock.ListBlock.OrderedList.startFrom] values in a parsed
 * markdown block tree.
 *
 * All of Jewel's `NumberFormatStyle` implementations (Decimal, Roman, Alphabetical)
 * throw [IllegalArgumentException] for `number <= 0`. CommonMark allows `startFrom = 0`
 * (e.g. `0. item`), and nested lists can produce surprising `startFrom` values.
 * This function walks the block tree and replaces any [MarkdownBlock.ListBlock.OrderedList]
 * with `startFrom <= 0` with a fresh instance using `startFrom = 1`.
 *
 * This is used by both [com.opencode.acp.chat.ui.compose.MessageList] (assistant text
 * rendering) and [com.opencode.acp.chat.ui.compose.CollapsibleThinkingPill] (thinking
 * content rendering) to prevent an `IllegalArgumentException: Input must be a positive
 * integer` crash from Jewel's `DefaultMarkdownBlockRenderer.RenderOrderedList` when
 * markdown contains an ordered list starting at 0 or a negative number.
 */
internal fun clampOrderedLists(blocks: List<MarkdownBlock>): List<MarkdownBlock> =
    blocks.map { block -> clampBlock(block) }

private fun clampBlock(block: MarkdownBlock): MarkdownBlock = when (block) {
    is MarkdownBlock.ListBlock.OrderedList -> {
        val fixedChildren = block.children.map { clampListItem(it) }
        if (block.startFrom <= 0) {
            MarkdownBlock.ListBlock.OrderedList(fixedChildren, block.isTight, 1, block.delimiter)
        } else {
            MarkdownBlock.ListBlock.OrderedList(fixedChildren, block.isTight, block.startFrom, block.delimiter)
        }
    }
    is MarkdownBlock.ListBlock.UnorderedList -> {
        val fixedChildren = block.children.map { clampListItem(it) }
        MarkdownBlock.ListBlock.UnorderedList(fixedChildren, block.isTight, block.marker)
    }
    is MarkdownBlock.ListItem -> clampListItem(block)
    is MarkdownBlock.BlockQuote -> MarkdownBlock.BlockQuote(block.children.map { clampBlock(it) })
    else -> block
}

private fun clampListItem(item: MarkdownBlock.ListItem): MarkdownBlock.ListItem {
    val fixedChildren = item.children.map { clampBlock(it) }
    return MarkdownBlock.ListItem(fixedChildren, item.level)
}