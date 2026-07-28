package com.opencode.acp.chat.markdown

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.MarkdownBlock.ListBlock.OrderedList
import org.jetbrains.jewel.markdown.MarkdownBlock.ListBlock.UnorderedList
import org.junit.jupiter.api.Test

/**
 * Regression guard tests for [clampOrderedLists].
 *
 * Jewel's `NumberFormatStyle` implementations (Decimal, Roman, Alphabetical) throw
 * `IllegalArgumentException("Input must be a positive integer")` for `number <= 0`.
 * CommonMark allows `startFrom = 0` (e.g. `0. item`), and nested lists can produce
 * surprising `startFrom` values. `clampOrderedLists` walks the parsed markdown block
 * tree and replaces any [OrderedList] with `startFrom <= 0` with a fresh instance
 * using `startFrom = 1`.
 *
 * This crash was observed in production logs from [CollapsibleThinkingPill] rendering
 * thinking content containing `0. item` markdown. See AGENTS.md
 * "Jewel Markdown" notes for context.
 */
class OrderedListClamperTest {

    @Test
    fun `ordered list with startFrom zero is clamped to one`() {
        val original = OrderedList(emptyList(), isTight = true, startFrom = 0, delimiter = ".")
        val result = clampOrderedLists(listOf(original))

        result shouldHaveSize 1
        val clamped = result.single()
        clamped.shouldBeInstanceOf<OrderedList>()
        (clamped as OrderedList).startFrom shouldBe 1
    }

    @Test
    fun `ordered list with negative startFrom is clamped to one`() {
        val original = OrderedList(emptyList(), isTight = false, startFrom = -3, delimiter = ")")
        val result = clampOrderedLists(listOf(original))

        result shouldHaveSize 1
        val clamped = result.single() as OrderedList
        clamped.startFrom shouldBe 1
    }

    @Test
    fun `ordered list with positive startFrom is preserved`() {
        val original = OrderedList(emptyList(), isTight = true, startFrom = 5, delimiter = ".")
        val result = clampOrderedLists(listOf(original))

        result shouldHaveSize 1
        val preserved = result.single() as OrderedList
        preserved.startFrom shouldBe 5
        preserved.isTight shouldBe true
        preserved.delimiter shouldBe "."
    }

    @Test
    fun `nested ordered list inside unordered list item with startFrom zero is clamped`() {
        val nestedOrdered = OrderedList(emptyList(), isTight = true, startFrom = 0, delimiter = ".")
        val listItem = MarkdownBlock.ListItem(listOf(nestedOrdered), level = 1)
        val unordered = UnorderedList(listOf(listItem), isTight = true, marker = "-")

        val result = clampOrderedLists(listOf(unordered))

        result shouldHaveSize 1
        val clampedUnordered = result.single() as UnorderedList
        clampedUnordered.children shouldHaveSize 1
        val clampedListItem = clampedUnordered.children.single()
        clampedListItem.children shouldHaveSize 1
        val clampedNested = clampedListItem.children.single() as OrderedList
        clampedNested.startFrom shouldBe 1
    }

    @Test
    fun `ordered list inside block quote with startFrom zero is clamped`() {
        val ordered = OrderedList(emptyList(), isTight = false, startFrom = 0, delimiter = ".")
        val blockQuote = MarkdownBlock.BlockQuote(listOf(ordered))

        val result = clampOrderedLists(listOf(blockQuote))

        result shouldHaveSize 1
        val clampedQuote = result.single() as MarkdownBlock.BlockQuote
        clampedQuote.children shouldHaveSize 1
        val clampedOrdered = clampedQuote.children.single() as OrderedList
        clampedOrdered.startFrom shouldBe 1
    }

    @Test
    fun `unordered list is passed through unchanged`() {
        val listItem = MarkdownBlock.ListItem(emptyList(), level = 0)
        val unordered = UnorderedList(listOf(listItem), isTight = true, marker = "*")

        val result = clampOrderedLists(listOf(unordered))

        result shouldHaveSize 1
        val passed = result.single() as UnorderedList
        passed.marker shouldBe "*"
        passed.isTight shouldBe true
        passed.children shouldHaveSize 1
    }

    @Test
    fun `paragraph is passed through unchanged`() {
        val paragraph = MarkdownBlock.Paragraph(emptyList())

        val result = clampOrderedLists(listOf(paragraph))

        result shouldHaveSize 1
        result.single().shouldBeInstanceOf<MarkdownBlock.Paragraph>()
    }

    @Test
    fun `empty list is handled`() {
        val result = clampOrderedLists(emptyList())
        result shouldBe emptyList()
    }

    @Test
    fun `top-level list item containing ordered list with startFrom zero is clamped`() {
        val ordered = OrderedList(emptyList(), isTight = true, startFrom = 0, delimiter = ".")
        val listItem = MarkdownBlock.ListItem(listOf(ordered), level = 1)

        val result = clampOrderedLists(listOf(listItem))

        result shouldHaveSize 1
        val clampedListItem = result.single() as MarkdownBlock.ListItem
        clampedListItem.children shouldHaveSize 1
        val clampedOrdered = clampedListItem.children.single() as OrderedList
        clampedOrdered.startFrom shouldBe 1
    }

    @Test
    fun `multiple ordered lists are each clamped independently`() {
        val first = OrderedList(emptyList(), isTight = true, startFrom = 0, delimiter = ".")
        val second = OrderedList(emptyList(), isTight = true, startFrom = 3, delimiter = ".")
        val third = OrderedList(emptyList(), isTight = true, startFrom = -1, delimiter = ".")

        val result = clampOrderedLists(listOf(first, second, third))

        result shouldHaveSize 3
        (result[0] as OrderedList).startFrom shouldBe 1
        (result[1] as OrderedList).startFrom shouldBe 3
        (result[2] as OrderedList).startFrom shouldBe 1
    }
}