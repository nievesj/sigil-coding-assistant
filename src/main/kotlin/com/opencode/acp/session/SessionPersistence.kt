package com.opencode.acp.session

import com.opencode.acp.StoredMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Session serialization for load/resume.
 * Stores session messages as JSON files in a directory.
 */
class SessionPersistence(
    private val dir: Path
) {
    init {
        dir.createDirectories()
    }

    suspend fun save(sessionId: String, messages: List<StoredMessage>) {
        val file = dir.resolve("$sessionId.json")
        val content = json.encodeToString(
            kotlinx.serialization.serializer<List<StoredMessage>>(),
            messages
        )
        file.writeText(content)
        logger.debug { "Saved session $sessionId with ${messages.size} messages" }
    }

    suspend fun load(sessionId: String): List<StoredMessage>? {
        val file = dir.resolve("$sessionId.json")
        if (!file.exists()) {
            logger.debug { "Session file not found: $sessionId" }
            return null
        }
        return try {
            val content = file.readText()
            json.decodeFromString(
                kotlinx.serialization.serializer<List<StoredMessage>>(),
                content
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to load session $sessionId" }
            null
        }
    }

    suspend fun delete(sessionId: String) {
        val file = dir.resolve("$sessionId.json")
        file.deleteIfExists()
        logger.debug { "Deleted session $sessionId" }
    }
}
