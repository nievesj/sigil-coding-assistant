package com.opencode.acp.mcp

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeMcpSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val settings: OpenCodeMcpSettingsState
) {

    /** Serializes all writeConfig calls to prevent concurrent read-modify-write races.
     *  Uses a file-level lock shared across all McpConfigWriter instances targeting
     *  the same project path, preventing cross-instance write races. */
    private val writeLock: ReentrantLock = projectLocks.computeIfAbsent(
        projectBasePath.toAbsolutePath().toString()
    ) { ReentrantLock() }

    companion object {
        /** File-level locks keyed by canonical project path — prevents concurrent writes
         *  from multiple McpConfigWriter instances targeting the same project. */
        private val projectLocks = java.util.concurrent.ConcurrentHashMap<String, ReentrantLock>()
    }

    /**
     * Read-modify-write the opencode.json config file atomically.
     *
     * Handles the full lifecycle: ensure directory exists, read existing config
     * (or start with empty object), apply the transform, add `$schema` if missing,
     * and write atomically via temp file + rename.
     *
     * @param transform Receives the existing config JsonObject, returns the new config to write.
     * @return true if written successfully, false on error.
     */
    private fun writeConfig(transform: (JsonObject) -> JsonObject): Boolean {
        return writeLock.withLock {
            try {
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

                // Apply the caller's transform to produce the new config
                val newConfig = transform(config)

                // Add $schema if not present
                val finalConfig = if (!newConfig.containsKey("\$schema")) {
                    buildJsonObject {
                        for ((key, value) in newConfig) {
                            put(key, value)
                        }
                        put("\$schema", "https://opencode.ai/config.json")
                    }
                } else {
                    newConfig
                }

                // Write atomically via temp file
                val tempFile = Files.createTempFile(opencodeDir, "opencode.json.", ".tmp")
                try {
                    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
                    Files.writeString(tempFile, json.encodeToString(JsonObject.serializer(), finalConfig))
                    try {
                        Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        logger.warn { "[ACP] McpConfigWriter: atomic move not supported, falling back to non-atomic replace" }
                        Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                } catch (e: Exception) {
                    // Clean up temp file on failure
                    try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                    throw e
                }
            } catch (e: Exception) {
                logger.error(e) { "[ACP] McpConfigWriter: failed to write config" }
                false
            }
        }
    }

    /**
     * Write MCP server configurations to `.opencode/opencode.json`.
     *
     * @return true if the config was written successfully, false otherwise
     */
    fun write(): Boolean {
        val success = writeConfig { config ->
            // Get the existing mcp section or start with empty object
            val existingMcp = config["mcp"]?.jsonObject ?: buildJsonObject {}

            // Build new mcp entries from settings
            val newMcp = buildMcpEntries(existingMcp)

            // Merge: start with existing config, replace/add mcp section
            buildJsonObject {
                // Copy all existing keys except mcp and $schema
                for ((key, value) in config) {
                    if (key != "mcp" && key != "\$schema") {
                        put(key, value)
                    }
                }
                // Add/replace mcp section
                put("mcp", newMcp)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote MCP config" }
        }
        return success
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
     * Write tool permission rules to `.opencode/opencode.json`.
     *
     * Permission rules control which tools the LLM can use. They are written
     * as per-agent permission rules in the config file. The permissions are
     * merged with existing agent config, preserving other agent settings.
     *
     * @param permissions Map of tool name to permission (allow/deny/ask)
     * @param agentName The agent to apply permissions to (default: "orchestrator")
     * @return true if the config was written successfully, false otherwise
     */
    fun writeToolPermissions(permissions: Map<String, ToolPermission>, agentName: String = "orchestrator"): Boolean {
        val success = writeConfig { config ->
            // Get existing agent section or start with empty object
            val existingAgents = config["agent"]?.jsonObject ?: buildJsonObject {}

            // Get existing agent config or start with empty object
            val existingAgentConfig = existingAgents[agentName]?.jsonObject ?: buildJsonObject {}

            // Get existing permission section or start with empty object
            val existingPermissions = existingAgentConfig["permission"]?.jsonObject ?: buildJsonObject {}

            // Build new permission entries
            val newPermissions = buildPermissionEntries(permissions, existingPermissions)

            // Build updated agent config
            val updatedAgentConfig = buildJsonObject {
                // Copy all existing agent config keys except permission
                for ((key, value) in existingAgentConfig) {
                    if (key != "permission") {
                        put(key, value)
                    }
                }
                // Add/replace permission section
                put("permission", newPermissions)
            }

            // Build updated agents section
            val updatedAgents = buildJsonObject {
                // Copy all existing agents except the one we're updating
                for ((key, value) in existingAgents) {
                    if (key != agentName) {
                        put(key, value)
                    }
                }
                // Add/replace the updated agent
                put(agentName, updatedAgentConfig)
            }

            // Merge: start with existing config, replace/add agent section
            buildJsonObject {
                // Copy all existing keys except agent and $schema
                for ((key, value) in config) {
                    if (key != "agent" && key != "\$schema") {
                        put(key, value)
                    }
                }
                // Add/replace agent section
                put("agent", updatedAgents)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote tool permissions" }
        }
        return success
    }

    /**
     * Write a single "always allow" permission rule to the config file.
     * Called after the user clicks "Always Allow" in the runtime permission prompt
     * AND the server has confirmed the response (POST succeeded).
     *
     * Updates `agent.{agentName}.permission.{toolName}` to `"allow"`.
     * For pattern-based tools (e.g., bash), patterns are stored but the tool-level
     * permission is set to "allow" — the server handles pattern-level evaluation.
     *
     * @param agentName The agent to apply the permission to (e.g., "orchestrator", "fixer")
     * @param toolName The tool name to allow (e.g., "bash", "read", "edit")
     * @param patterns Optional patterns for pattern-based tools (currently informational —
     *   the tool-level permission is set to "allow")
     * @return true if the config was written successfully, false otherwise
     */
    fun writeAlwaysAllowRule(agentName: String, toolName: String, patterns: List<String>): Boolean {
        // Validate inputs — agentName and toolName become JSON object keys in the
        // config file. Reject values that are blank or contain path separators /
        // control characters to prevent writing config entries for non-existent
        // agents or tools (e.g., the fallback label "sub-agent" from a missed
        // Subtask SSE event, or a malicious server-provided string).
        if (!isValidConfigKey(agentName) || !isValidConfigKey(toolName)) {
            logger.warn { "[ACP] McpConfigWriter.writeAlwaysAllowRule: rejected invalid key — agentName='$agentName', toolName='$toolName'" }
            return false
        }

        val success = writeConfig { config ->
            // Get existing agent section or start with empty object
            val existingAgents = config["agent"]?.jsonObject ?: buildJsonObject {}

            // Get existing agent config or start with empty object
            val existingAgentConfig = existingAgents[agentName]?.jsonObject ?: buildJsonObject {}

            // Get existing permission section or start with empty object
            val existingPermissions = existingAgentConfig["permission"]?.jsonObject ?: buildJsonObject {}

            // Build updated permissions: copy existing + add/override the tool.
            // If the existing value is a JsonObject (pattern-specific rules), merge
            // a wildcard "allow" key instead of replacing the entire object — this
            // preserves pattern-specific deny rules (e.g., "rm -rf /": "deny").
            val existingToolPermission = existingPermissions[toolName]
            val updatedPermissions = buildJsonObject {
                for ((key, value) in existingPermissions) {
                    put(key, value)
                }
                if (existingToolPermission is JsonObject) {
                    // Guard: do NOT silently flip an existing wildcard "deny" to "allow".
                    // A user who previously denied all commands for this tool should not
                    // have their deny rule overwritten by a single "Always Allow" click.
                    val existingWildcard = existingToolPermission["*"]
                    if (existingWildcard is JsonPrimitive && existingWildcard.content == "deny") {
                        logger.warn { "[ACP] McpConfigWriter: refusing to overwrite existing wildcard 'deny' rule for tool '$toolName' — keeping deny. User must manually update config if they want to allow." }
                        // Keep the existing permission object unchanged — do NOT add "*": "allow"
                        put(toolName, existingToolPermission)
                    } else {
                        // Merge: add wildcard "allow" without destroying pattern rules
                        put(toolName, buildJsonObject {
                            for ((k, v) in existingToolPermission) put(k, v)
                            put("*", JsonPrimitive("allow"))
                        })
                    }
                } else {
                    put(toolName, JsonPrimitive("allow"))
                }
            }

            // Build updated agent config
            val updatedAgentConfig = buildJsonObject {
                for ((key, value) in existingAgentConfig) {
                    if (key != "permission") {
                        put(key, value)
                    }
                }
                put("permission", updatedPermissions)
            }

            // Build updated agents section
            val updatedAgents = buildJsonObject {
                for ((key, value) in existingAgents) {
                    if (key != agentName) {
                        put(key, value)
                    }
                }
                put(agentName, updatedAgentConfig)
            }

            // Merge: start with existing config, replace agent section
            buildJsonObject {
                for ((key, value) in config) {
                    if (key != "agent" && key != "\$schema") {
                        put(key, value)
                    }
                }
                put("agent", updatedAgents)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote always-allow rule for agent=$agentName, tool=$toolName" }
        }
        return success
    }

    /**
     * Build permission entries from a map of tool permissions.
     *
     * Merges new permissions with existing ones. New permissions override existing
     * ones for the same tool name. Existing permissions for tools not in the new
     * map are preserved.
     *
     * @param newPermissions Map of tool name to permission (allow/deny/ask)
     * @param existingPermissions Existing permission JsonObject to merge with
     * @return JsonObject with merged permissions
     */
    private fun buildPermissionEntries(
        newPermissions: Map<String, ToolPermission>,
        existingPermissions: JsonObject
    ): JsonObject {
        return buildJsonObject {
            // Copy existing permissions
            for ((key, value) in existingPermissions) {
                put(key, value)
            }
            // Add/override with new permissions
            for ((toolName, permission) in newPermissions) {
                put(toolName, JsonPrimitive(permission.toActionString()))
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
        // If no config file exists, nothing to clear
        val configFile = projectBasePath.resolve(".opencode").resolve("opencode.json")
        if (!Files.exists(configFile)) {
            return true
        }

        val success = writeConfig { config ->
            val existingMcp = config["mcp"]?.jsonObject ?: return@writeConfig config

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
            buildJsonObject {
                for ((key, value) in config) {
                    if (key != "mcp") {
                        put(key, value)
                    }
                }
                put("mcp", cleanedMcp)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: cleared plugin MCP entries" }
        }
        return success
    }

    /**
     * Validate that a string is safe to use as a JSON object key in the config file.
     * Rejects blank strings, strings with path separators, and strings with
     * control characters. This prevents writing config entries for non-existent
     * agents or tools from untrusted SSE-provided data.
     */
    private fun isValidConfigKey(key: String): Boolean {
        if (key.isBlank()) return false
        // Reject keys starting with '$' (JSONPath/reserved prefix) to avoid
        // conflicts with $schema and other reserved JSON keys.
        if (key.startsWith("$")) return false
        // Reject excessively long keys from untrusted SSE data
        if (key.length > 128) return false
        // Reject path separators and traversal sequences
        if (key.contains('/') || key.contains('\\') || key.contains("..")) return false
        // Reject control characters
        if (key.any { it.code < 0x20 }) return false
        return true
    }
}
