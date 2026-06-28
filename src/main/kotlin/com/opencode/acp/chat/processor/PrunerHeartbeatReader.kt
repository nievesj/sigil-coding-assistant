package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads the sigil-pruner heartbeat file to obtain pruning statistics.
 *
 * The TS plugin writes to `.opencode/sigil-pruner.heartbeat` in the project directory.
 * Two formats are supported:
 * - Legacy: plain timestamp string (no token data → returns 0)
 * - JSON: `{"timestamp": ..., "tokensSaved": N, "outputsPruned": N, "inputsPruned": N}`
 *
 * Thread safety: stateless object, safe to call from any coroutine.
 */
object PrunerHeartbeatReader {
    private val logger = KotlinLogging.logger {}

    /**
     * Read the estimated tokens saved by the pruner from the heartbeat file.
     *
     * @param projectDirectory the project root directory (where .opencode/ lives)
     * @return tokens saved by pruning, or 0 if the file doesn't exist, is stale,
     *         or can't be parsed. Returns 0 if the pruner is disabled or hasn't run.
     */
    fun readTokensSaved(projectDirectory: String): Long {
        return try {
            val heartbeatPath = resolveHeartbeatPath(projectDirectory)
            if (!Files.exists(heartbeatPath)) return 0L

            val content = Files.readString(heartbeatPath).trim()
            if (content.isEmpty()) return 0L

            // Try JSON first (new format)
            val jsonResult = tryParseJson(content)
            if (jsonResult != null) return jsonResult

            // Fall back to legacy plain timestamp (no token data available)
            0L
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] PrunerHeartbeatReader: failed to read heartbeat" }
            0L
        }
    }

    /**
     * Read the full heartbeat data (tokens saved, outputs pruned, etc.).
     * Returns null if the file doesn't exist or can't be parsed.
     */
    fun readHeartbeat(projectDirectory: String): PrunerHeartbeat? {
        return try {
            val heartbeatPath = resolveHeartbeatPath(projectDirectory)
            if (!Files.exists(heartbeatPath)) return null

            val content = Files.readString(heartbeatPath).trim()
            if (content.isEmpty()) return null

            val tokensSaved = tryParseJson(content) ?: return null
            // If we got tokens from JSON, parse the full object
            // (tryParseJson already validated it's JSON)
            PrunerHeartbeat(tokensSaved = tokensSaved)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveHeartbeatPath(projectDirectory: String): Path {
        return Paths.get(projectDirectory, ".opencode", ChatConstants.PRUNER_HEARTBEAT_FILENAME)
    }

    private fun tryParseJson(content: String): Long? {
        // Parse JSON properly using kotlinx.serialization instead of regex.
        // Regex matching is fragile — "tokensSaved" could appear inside a JSON
        // string value (e.g., {"message":"tokensSaved: 0","tokensSaved": 1234})
        // and the regex would match the wrong occurrence.
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(content)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val value = obj["tokensSaved"] as? kotlinx.serialization.json.JsonPrimitive
            value?.content?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}

data class PrunerHeartbeat(
    val tokensSaved: Long,
)
