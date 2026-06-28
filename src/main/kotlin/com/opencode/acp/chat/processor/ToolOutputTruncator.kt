package com.opencode.acp.chat.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Truncates tool outputs exceeding a character limit.
 * Called by SessionState when processing tool result parts.
 * Only active when settings.truncateToolOutput is true.
 */
object ToolOutputTruncator {

    private val logger = KotlinLogging.logger {}

    /**
     * Truncate tool output JSON if it exceeds the character limit.
     *
     * JSON-safe truncation: Instead of cutting at an arbitrary character boundary
     * (which would produce invalid JSON), this method:
     * 1. Serializes each JsonObject to a string and checks total length.
     * 2. If under limit, returns the original list unchanged.
     * 3. If over limit, collects complete JSON objects until adding the next
     *    would exceed the limit, then returns the truncated list with a
     *    marker object: {"_truncated": true, "originalCount": N}.
     *
     * This ensures the output remains valid JSON that the server can parse
     * during compaction.
     *
     * @param toolName the tool that produced this output (for logging)
     * @param output the list of JSON objects from the tool result
     * @param charLimit maximum total character count (0 or negative = no truncation)
     * @return the original list if under limit, or a truncated list with a marker object
     */
    fun truncateIfNeeded(
        toolName: String,
        output: List<JsonObject>,
        charLimit: Int,
    ): List<JsonObject> {
        if (charLimit <= 0 || output.isEmpty()) return output

        // Cache serialized strings to avoid double serialization
        val serialized = output.map { it.toString() }
        val totalLength = serialized.sumOf { it.length }
        if (totalLength <= charLimit) return output

        val truncated = mutableListOf<JsonObject>()
        var currentLength = 0
        for ((i, objStr) in serialized.withIndex()) {
            if (currentLength + objStr.length > charLimit && truncated.isNotEmpty()) {
                break
            }
            truncated.add(output[i])
            currentLength += objStr.length
        }

        // Add marker object so consumers know truncation occurred
        truncated.add(buildJsonObject {
            put("_truncated", true)
            put("originalCount", output.size)
            put("truncatedCount", truncated.size - 1)
            put("originalTotalChars", totalLength)
        })

        logger.info {
            "[ACP] Truncated $toolName output from $totalLength to $currentLength chars " +
                "(${output.size} → ${truncated.size - 1} objects)"
        }

        return truncated
    }
}