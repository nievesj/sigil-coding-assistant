package com.opencode.acp.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/**
 * Reads opencode.json config for tool, model, and agent definitions.
 */
class ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(configPath: Path): OpenCodeConfig? {
        if (!configPath.exists()) {
            logger.debug { "Config file not found: $configPath" }
            return null
        }
        return try {
            val content = configPath.readText()
            val element = json.parseToJsonElement(content).jsonObject
            OpenCodeConfig(
                model = element["model"]?.jsonPrimitive?.content,
                agent = element["agent"]?.jsonPrimitive?.content,
                provider = element["provider"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to load config from $configPath" }
            null
        }
    }
}

data class OpenCodeConfig(
    val model: String? = null,
    val agent: String? = null,
    val provider: String? = null
)
