package com.opencode.acp.follow

/**
 * Shared utility for computing line-level additions/deletions between two strings
 * using multiset counting.
 *
 * Used by:
 * - [com.opencode.acp.chat.processor.FollowAgentDispatcher] for ChatFileChange extraction
 * - [FollowColorProvider] for inlay label delta display
 *
 * Multiset counting handles duplicate lines correctly: a set-difference would treat
 * 3 identical lines as 1, miscounting additions/deletions when the same line appears
 * multiple times in old or new content.
 */
object LineDeltaUtils {

    /**
     * Compute the (additions, deletions) line delta between [oldString] and [newString]
     * using multiset counting.
     *
     * - Additions: lines in [newString] not matched by a corresponding line in [oldString].
     * - Deletions: lines in [oldString] not matched by a corresponding line in [newString].
     *
     * A line appearing 3x in old and 1x in new contributes 0 additions for that line
     * (old has surplus) and 2 deletions. A line 1x in old and 3x in new contributes
     * 2 additions and 0 deletions.
     *
     * @return Pair(additions, deletions). Returns (0, 0) if either string is null.
     */
    fun computeLineDelta(oldString: String?, newString: String?): Pair<Int, Int> {
        if (oldString == null || newString == null) return Pair(0, 0)

        // Single mutable map — decrement in place, no copy.
        val oldCounts = oldString.lines().groupingBy { it }.eachCount().toMutableMap()
        var added = 0
        for (line in newString.lines()) {
            val cnt = oldCounts[line]
            if (cnt != null && cnt > 0) {
                oldCounts[line] = cnt - 1
            } else {
                added++
            }
        }
        // Deletions = remaining old lines that weren't matched
        val removed = oldCounts.values.sumOf { it.coerceAtLeast(0) }

        return Pair(added, removed)
    }

    /**
     * Format a delta string like "(+12 -3 lines)" from [oldString] and [newString].
     * Returns null if neither old nor new is present.
     */
    fun formatDelta(oldString: String?, newString: String?): String? {
        if (oldString == null && newString == null) return null
        if (oldString != null && newString != null) {
            val (added, removed) = computeLineDelta(oldString, newString)
            return "(+${added} -${removed} lines)"
        }
        // Only one side present — count lines in whichever is present (both can't be null here)
        val newOnly = newString != null
        val count = (if (newOnly) newString else oldString)?.lines()?.size ?: 0
        return if (newOnly) "(+${count} lines)" else "(-${count} lines)"
    }
}