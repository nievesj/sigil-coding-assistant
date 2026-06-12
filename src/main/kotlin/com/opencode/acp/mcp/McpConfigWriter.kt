package com.opencode.acp.mcp

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Writes MCP server configurations to `.opencode/opencode.json` in the project directory.
 *
 * This is the PRIMARY way the IntelliJ plugin registers MCP servers with OpenCode.
 * The config file is read by OpenCode on startup and persists across process restarts.
 *
 * Key behaviors:
 * - MERGES with existing config (preserves `model`, `agent`, `provider`, and other `mcp` entries)
 * - Only adds/updates `mcp` entries for the plugin's managed servers
 * - Disabled servers are REMOVED from the config, not set to `enabled: false`
 * - Creates `.opencode/` directory if it doesn't exist
 * - Writes atomically via temp file + rename
 * - Logs errors without throwing — config write failure should not crash the plugin
 */
class McpConfigWriter(
    private val projectBasePath: Path,
    private val settings: OpenCodeSettingsState
) {

    /**
     * Write MCP server configurations to `.opencode/opencode.json`.
     *
     * @return true if the config was written successfully, false otherwise
     */
    fun write(): Boolean {
        return try {
            val opencodeDir = projectBasePath.resolve(".opencode")
            Files.createDirectories(opencodeDir)

            val configFile = opencodeDir.resolve("opencode.json")

            // Read existing config or start with empty object
            val existingConfig = if (Files.exists(configFile)) {
                try {
                    val content = Files.readString(configFile)
                    if (content.isNotBlank()) {
                        Json.parseToJsonElement(content).jsonObject
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] McpConfigWriter: failed to parse existing config, starting fresh" }
                    null
                }
            } else {
                null
            }

            val config = existingConfig ?: buildJsonObject {}

            // Get the existing mcp section or start with empty object
            val existingMcp = config["mcp"]?.jsonObject ?: buildJsonObject {}

            // Build new mcp entries from settings
            val newMcp = buildMcpEntries(existingMcp)

            // Merge: start with existing config, replace/add mcp section
            val mergedConfig = buildJsonObject {
                // Copy all existing keys except mcp
                for ((key, value) in config) {
                    if (key != "mcp" && key != "\$schema") {
                        put(key, value)
                    }
                }
                // Add $schema if not present
                if (!config.containsKey("\$schema")) {
                    put("\$schema", "https://opencode.ai/config.json")
                }
                // Add/replace mcp section
                put("mcp", newMcp)
            }

            // Write atomically via temp file
            val tempFile = Files.createTempFile(opencodeDir, "opencode.json.", ".tmp")
            try {
                val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
                Files.writeString(tempFile, json.encodeToString(JsonObject.serializer(), mergedConfig))
                Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                logger.info { "[ACP] McpConfigWriter: wrote MCP config to $configFile" }
                true
            } catch (e: Exception) {
                // Clean up temp file on failure
                try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] McpConfigWriter: failed to write MCP config" }
            false
        }
    }

    /**
     * Build the mcp section by merging existing config with plugin-managed entries.
     *
     * Plugin-managed entries are added/updated based on current settings.
     * Entries for disabled servers are removed.
     * Existing entries from other sources are preserved.
     */
    private fun buildMcpEntries(existingMcp: JsonObject): JsonObject {
        // Determine which keys the plugin manages:
        // 1. Built-in IntelliJ MCP (always managed — either present or absent)
        // 2. Any key that matches a name from the additionalMcpServers list
        val pluginManagedKeys = mutableSetOf(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
        if (settings.additionalMcpServers.isNotBlank()) {
            try {
                val array = Json.parseToJsonElement(settings.additionalMcpServers).jsonArray
                for (element in array) {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (name.isNotBlank()) {
                        pluginManagedKeys.add(name)
                    }
                }
            } catch (_: Exception) {
                // parse errors handled below in the write path
            }
        }

        return buildJsonObject {
            // Copy existing entries that are NOT plugin-managed
            for ((key, value) in existingMcp) {
                if (key !in pluginManagedKeys) {
                    put(key, value)
                }
            }

            // Add built-in IntelliJ MCP if enabled with a valid URL
            if (settings.enableIntellijMcp && settings.mcpServerUrl.isNotBlank()) {
                put(ChatConstants.MCP_SERVER_NAME_INTELLIJ, buildJsonObject {
                    put("type", "remote")
                    put("url", settings.mcpServerUrl)
                    put("oauth", false)
                    put("enabled", true)
                    put("timeout", 5000)
                })
            }
            // If disabled or URL blank, the key is simply not added — entry is removed.

            // Add additional MCP servers from settings
            if (settings.additionalMcpServers.isNotBlank()) {
                try {
                    val array = Json.parseToJsonElement(settings.additionalMcpServers).jsonArray
                    for (element in array) {
                        val obj = element.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (name.isNotBlank() && url.isNotBlank()) {
                            put(name, buildJsonObject {
                                put("type", "remote")
                                put("url", url)
                                put("oauth", false)
                                put("enabled", true)
                                put("timeout", 5000)
                            })
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] McpConfigWriter: failed to parse additionalMcpServers JSON" }
                }
            }
        }
    }

    /**
     * Remove all plugin-managed MCP entries from the config file.
     * Useful when disabling MCP integration entirely.
     *
     * @return true if the config was written successfully, false otherwise
     */
    fun clearAllEntries(): Boolean {
        return try {
            val opencodeDir = projectBasePath.resolve(".opencode")
            val configFile = opencodeDir.resolve("opencode.json")

            if (!Files.exists(configFile)) {
                return true
            }

            val content = Files.readString(configFile)
            if (content.isBlank()) {
                return true
            }

            val config = Json.parseToJsonElement(content).jsonObject
            val existingMcp = config["mcp"]?.jsonObject ?: return true

            // Determine plugin-managed keys (same logic as write())
            val pluginManagedKeys = mutableSetOf(ChatConstants.MCP_SERVER_NAME_INTELLIJ)
            if (settings.additionalMcpServers.isNotBlank()) {
                try {
                    val array = Json.parseToJsonElement(settings.additionalMcpServers).jsonArray
                    for (element in array) {
                        val obj = element.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (name.isNotBlank()) pluginManagedKeys.add(name)
                    }
                } catch (_: Exception) { /* best effort */ }
            }

            // Rebuild mcp section without plugin-managed entries
            val cleanedMcp = buildJsonObject {
                for ((key, value) in existingMcp) {
                    if (key !in pluginManagedKeys) {
                        put(key, value)
                    }
                }
            }

            // Rebuild config with cleaned mcp
            val cleanedConfig = buildJsonObject {
                for ((key, value) in config) {
                    if (key != "mcp") {
                        put(key, value)
                    }
                }
                put("mcp", cleanedMcp)
            }

            val tempFile = Files.createTempFile(opencodeDir, "opencode.json.", ".tmp")
            try {
                val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
                Files.writeString(tempFile, json.encodeToString(JsonObject.serializer(), cleanedConfig))
                Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                logger.info { "[ACP] McpConfigWriter: cleared plugin MCP entries from $configFile" }
                true
            } catch (e: Exception) {
                try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] McpConfigWriter: failed to clear MCP config" }
            false
        }
    }
}
