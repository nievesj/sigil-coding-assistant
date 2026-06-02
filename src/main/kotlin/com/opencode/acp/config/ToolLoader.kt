package com.opencode.acp.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/**
 * Discovers custom tools from .opencode/tools/ directory.
 * Returns a list of tool definitions for dynamic ACP tool exposure.
 */
class ToolLoader {
    fun discoverTools(toolsDir: Path): List<CustomTool> {
        if (!toolsDir.exists() || !toolsDir.isDirectory()) {
            logger.debug { "Tools directory not found: $toolsDir" }
            return emptyList()
        }

        return toolsDir.listDirectoryEntries("*.json").mapNotNull { file ->
            try {
                val content = file.readText()
                CustomTool(
                    name = file.name.removeSuffix(".json"),
                    definition = content
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load tool from $file" }
                null
            }
        }
    }
}

data class CustomTool(
    val name: String,
    val definition: String
)
