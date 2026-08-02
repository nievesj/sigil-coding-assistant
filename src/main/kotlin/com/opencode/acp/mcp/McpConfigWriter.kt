package com.opencode.acp.mcp

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.AgentConstants
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
import kotlinx.serialization.json.add
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
    private val settings: OpenCodeMcpSettingsState,
    /** Predicate identifying plugin-managed skill paths for eviction in [writeSkillPaths].
     *  Defaults to [com.opencode.acp.skill.JetBrainsSkillBridge.isPluginManagedPath].
     *  Injectable for testing and to avoid a hard cross-package dependency. */
    private val isPluginManagedPath: (String) -> Boolean = com.opencode.acp.skill.JetBrainsSkillBridge::isPluginManagedPath
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

        /** Agent names that are stale leftovers from older plugin versions and should
         *  be evicted from opencode.json during [writeAgentOverrides]. The "orchestrator"
         *  agent accumulated "Always Allow" permission clicks before the custom-agents
         *  TDD introduced coding-assistant; it does not correspond to any agent the
         *  plugin defines or the server provides. */
        private val STALE_AGENT_NAMES = setOf("orchestrator")

        /**
         * Flatten a recursive glob pattern by removing recursive glob segments (double-star followed by slash).
         * Used for the "covers" check in [mergeInstructions].
         *
         * E.g., a recursive glob like `.opencode/context/RECURSIVE.md` becomes `.opencode/context/FLAT.md`
         * (where RECURSIVE means double-star-slash-prefix, FLAT means no recursion prefix).
         *
         * Limitation: this replaces ALL double-star-slash occurrences, so a multi-level
         * recursive glob becomes fully flattened. This is correct for the current single
         * use case (CONTEXT_GLOB_PATTERN) but may produce incorrect results for multi-level
         * recursive globs if reused elsewhere.
         */
        private fun flattenRecursiveGlob(glob: String): String = glob.replace("**/", "")

        /**
         * Merge the plugin's context-file glob into the existing `instructions` array.
         *
         * Contract:
         * - If [ourGlob] is already present (exact string match), return [existing] unchanged.
         * - If [existing] contains a glob that covers [ourGlob] (e.g., a flat context glob
         *   covers a recursive context glob), return [existing] unchanged.
         *  A glob A covers glob B if `flattenRecursiveGlob(B).startsWith(flattenRecursiveGlob(A))`.
         * - Otherwise, append [ourGlob] to the array.
         * - Preserve all existing entries regardless of type (string or object).
         *
         * WARNING: The "covers" check uses [flattenRecursiveGlob], which has a
         * known limitation with multi-level recursive globs (see its doc). This
         * method is safe for the current single use case (CONTEXT_GLOB_PATTERN
         * = `.opencode/context/**/*.md`) but may produce incorrect results for
         * arbitrary multi-level globs. Do not reuse for general glob merging.
         *
         * @param existing The existing instructions JsonArray (may contain strings and objects).
         * @param ourGlob The glob pattern to merge in (e.g., ".opencode/context/**/*.md").
         * @return The merged JsonArray.
         */
        fun mergeInstructions(existing: JsonArray, ourGlob: String): JsonArray {
            // Extract string entries from the existing array (skip non-string elements like objects).
            val existingStrings = existing.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

            // 1. Exact match — already present.
            if (ourGlob in existingStrings) {
                return existing
            }

            // 2. Covers check — an existing glob covers ourGlob.
            val normalizedOurs = flattenRecursiveGlob(ourGlob)
            for (existingGlob in existingStrings) {
                val normalizedExisting = flattenRecursiveGlob(existingGlob)
                // Only directory-style globs (recursive or wildcarded last segment) can cover other globs.
                // A glob like ".opencode/context/*.md" has a wildcard in its last segment and covers
                // ".opencode/context/sub/foo.md" after normalization, so it must pass the gate.
                val isRecursive =
                    existingGlob.contains("**") ||
                            existingGlob.endsWith("/") ||
                            existingGlob.substringAfterLast("/", existingGlob).contains("*")
                if (!isRecursive) continue
                // A glob A covers glob B if A's directory prefix is a prefix of B's
                // (using startsWith on the directory portion). This is a prefix match,
                // not a proper parent-directory check — a glob like ".opencode" would
                // match ".opencode-evil/" via startsWith. This is acceptable for the
                // current use case (CONTEXT_GLOB_PATTERN) but may need tightening if
                // reused for arbitrary globs.
                // Check: does normalizedOurs start with the directory portion of
                // normalizedExisting (everything up to the last path segment)?
                val existingDir = normalizedExisting.substringBeforeLast("/", "")
                if (existingDir.isEmpty()) {
                    // Existing glob has no directory — only covers if it's the same glob
                    // (e.g., "README.md" does NOT cover ".opencode/context/**/*.md")
                    if (normalizedOurs == normalizedExisting) {
                        return existing
                    }
                    // No directory prefix to match — skip this entry
                    continue
                }
                if (normalizedOurs.startsWith(existingDir + "/") || normalizedOurs == normalizedExisting) {
                    return existing
                }
            }

            // 3. Append ourGlob, preserving all existing entries.
            return buildJsonArray {
                for (element in existing) {
                    add(element)
                }
                add(JsonPrimitive(ourGlob))
            }
        }
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
                        Files.move(
                            tempFile,
                            configFile,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        logger.warn { "[ACP] McpConfigWriter: atomic move not supported, falling back to non-atomic replace" }
                        Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                } catch (e: Exception) {
                    // Clean up temp file on failure
                    try {
                        Files.deleteIfExists(tempFile)
                    } catch (_: Exception) {
                    }
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
            val existingMcp = (config["mcp"] as? JsonObject) ?: buildJsonObject {}

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
     * @param agentName The agent to apply permissions to (default: "coding-assistant")
     * @return true if the config was written successfully, false otherwise
     */
    fun writeToolPermissions(
        permissions: Map<String, ToolPermission>,
        agentName: String = AgentConstants.CODING_ASSISTANT_AGENT_NAME
    ): Boolean {
        val success = writeConfig { config ->
            // Get existing agent section or start with empty object
            val existingAgents = (config["agent"] as? JsonObject) ?: buildJsonObject {}

            // Get existing agent config or start with empty object
            val existingAgentConfig = (existingAgents[agentName] as? JsonObject) ?: buildJsonObject {}

            // Get existing permission section or start with empty object
            val existingPermissions = (existingAgentConfig["permission"] as? JsonObject) ?: buildJsonObject {}

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
     * @param agentName The agent to apply the permission to (e.g., "coding-assistant", "fixer")
     * @param toolName The tool name to allow (e.g., "bash", "read", "edit")
     * @param patterns Optional patterns for pattern-based tools (currently informational —
     *   the tool-level permission is set to "allow")
     * @return true if the rule was applied AND the config file was written successfully;
     *   false if (a) an existing deny rule was preserved by the deny-guard (the write
     *   succeeded but the "Always Allow" was NOT applied — see deniedByGuard in the
     *   implementation), or (b) the config write failed (I/O error). Callers that only
     *   care about whether the permission is now "allow" should treat false as "not applied".
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

        var deniedByGuard = false
        val success = writeConfig { config ->
            // Get existing agent section or start with empty object
            val existingAgents = (config["agent"] as? JsonObject) ?: buildJsonObject {}

            // Get existing agent config or start with empty object
            val existingAgentConfig = (existingAgents[agentName] as? JsonObject) ?: buildJsonObject {}

            // Get existing permission section or start with empty object
            val existingPermissions = (existingAgentConfig["permission"] as? JsonObject) ?: buildJsonObject {}

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
                        deniedByGuard = true
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
                    // Guard: do NOT silently flip an existing simple-string "deny" to "allow".
                    // A user who set a tool to Deny via the settings panel (writeToolPermissions
                    // writes JsonPrimitive(permission.toActionString())) should not have their
                    // deny overwritten by a single "Always Allow" click. Mirrors the object-form
                    // wildcard-deny guard above.
                    if (existingToolPermission is JsonPrimitive && existingToolPermission.content == "deny") {
                        logger.warn { "[ACP] McpConfigWriter: refusing to overwrite existing 'deny' rule for tool '$toolName' — keeping deny. User must manually update config if they want to allow." }
                        deniedByGuard = true
                        // Keep the existing deny unchanged — do NOT overwrite with "allow"
                        put(toolName, existingToolPermission)
                    } else {
                        put(toolName, JsonPrimitive("allow"))
                    }
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
        if (deniedByGuard) {
            // Config write succeeded (file updated) but the deny-guard refused
            // to flip the permission. Return false so the caller knows the
            // "Always Allow" was NOT applied to the config.
            logger.warn { "[ACP] McpConfigWriter: always-allow rule for agent=$agentName, tool=$toolName was refused by deny-guard (existing deny preserved)" }
            return false
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
            val existingMcp = (config["mcp"] as? JsonObject) ?: return@writeConfig config

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
                } catch (_: Exception) { /* best effort */
                }
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
     * Write skill paths to the "skills.paths" array in opencode.json.
     *
     * Implements stale-path eviction: overwrites the plugin-managed
     * subset of skills.paths (paths matching JetBrainsSkillBridge.isPluginManagedPath)
     * with [paths], while preserving user-added paths and skills.urls.
     *
     * Plugin-managed paths from previous writes (e.g., from an old IDE version)
     * are evicted. User-added paths (custom paths not matching the plugin-managed
     * pattern) are always preserved.
     *
     * Preserves skills.urls and all other config keys including $schema
     * (writeConfig handles $schema — do not strip it here).
     *
     * @param paths Plugin-managed skill directory paths to write (from detectSkillPaths)
     * @return true if config was written successfully, false on error
     */
    fun writeSkillPaths(paths: List<String>): Boolean {
        val success = writeConfig { config ->
            val existingSkills = config["skills"] as? JsonObject
            val existingUrls = existingSkills?.get("urls") as? JsonArray
            val existingPaths = (existingSkills?.get("paths") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            // Partition existing paths into user-added (preserve) and
            // plugin-managed (evict). Plugin-managed paths are fully
            // determined at runtime by detectSkillPaths() — there is no
            // reason to keep stale ones from old IDE versions.
            val userPaths = existingPaths.filter { !isPluginManagedPath(it) }
            val finalPaths = (userPaths + paths).distinct()

            // Build the new config. Preserve all keys except "skills" (rebuilt below).
            buildJsonObject {
                for ((key, value) in config) {
                    if (key != "skills") {
                        put(key, value)
                    }
                }
                // Only write the "skills" section if there are paths, urls, or
                // unknown keys to preserve.
                if (finalPaths.isNotEmpty() || existingUrls != null || existingSkills != null) {
                    put("skills", buildJsonObject {
                        // Preserve unknown keys from the existing skills object
                        // (e.g., future schema additions, user-added custom keys).
                        if (existingSkills != null) {
                            for ((key, value) in existingSkills) {
                                if (key != "paths" && key != "urls") {
                                    put(key, value)
                                }
                            }
                        }
                        if (finalPaths.isNotEmpty()) {
                            put("paths", buildJsonArray {
                                finalPaths.forEach { add(JsonPrimitive(it)) }
                            })
                        }
                        // Preserve existing urls if present
                        if (existingUrls != null) {
                            put("urls", existingUrls)
                        }
                    })
                }
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote ${paths.size} skill path(s)" }
        }
        return success
    }

    /**
     * Write the context-file instructions glob to `.opencode/opencode.json`.
     *
     * Merges [glob] into the existing `instructions` array with dedup.
     * Preserves all existing config keys.
     *
     * @param glob The glob pattern to add (e.g., ".opencode/context/**/*.md").
     * @return true if the config was written successfully, false otherwise.
     */
    fun writeInstructions(glob: String): Boolean {
        val success = writeConfig { config ->
            val existingInstructions = (config["instructions"] as? JsonArray) ?: buildJsonArray {}
            val merged = mergeInstructions(existingInstructions, glob)
            buildJsonObject {
                for ((key, value) in config) {
                    if (key != "instructions" && key != "\$schema") {
                        put(key, value)
                    }
                }
                put("instructions", merged)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote instructions glob: $glob" }
        }
        return success
    }

    /**
     * Writes agent enable/disable overrides to the `agent` section of opencode.json.
     *
     * Re-enables `explore` and `general` (council depends on them) and disables
     * known leaked agents (e.g., stale global agents from a developer machine's
     * global config that leak into the project session).
     *
     * Uses the existing [writeConfig] read-modify-write + ReentrantLock, mirroring
     * the [writeSkillPaths] pattern. Preserves existing agent entries except for
     * [STALE_AGENT_NAMES] (e.g., `orchestrator`), which are evicted.
     *
     * @param enableExplore re-enable the `explore` agent (set `disable: false`).
     * @param enableGeneral re-enable the `general` agent (set `disable: false`).
     * @param disabledAgentNames agent names to disable (set `disable: true`).
     * @return true if the config was written successfully, false otherwise.
     */
    fun writeAgentOverrides(
        enableExplore: Boolean = true,
        enableGeneral: Boolean = true,
        disabledAgentNames: List<String> = emptyList()
    ): Boolean {
        val success = writeConfig { config ->
            val existingAgent = (config["agent"] as? JsonObject) ?: buildJsonObject {}

            // Build updated agent section
            val updatedAgent = buildJsonObject {
                // Copy all existing agent entries EXCEPT known-stale agents.
                // The "orchestrator" agent is a stale leftover from an older plugin
                // version that accumulated "Always Allow" permission clicks before
                // the custom-agents TDD introduced coding-assistant. It does not
                // correspond to any agent the plugin defines or the server provides.
                for ((key, value) in existingAgent) {
                    if (key in STALE_AGENT_NAMES) continue
                    put(key, value)
                }
                // Re-enable explore if requested
                if (enableExplore) {
                    val existingExplore = (existingAgent["explore"] as? JsonObject) ?: buildJsonObject {}
                    put("explore", buildJsonObject {
                        for ((k, v) in existingExplore) {
                            if (k != "disable") put(k, v)
                        }
                        put("disable", JsonPrimitive(false))
                    })
                }
                // Re-enable general if requested
                if (enableGeneral) {
                    val existingGeneral = (existingAgent["general"] as? JsonObject) ?: buildJsonObject {}
                    put("general", buildJsonObject {
                        for ((k, v) in existingGeneral) {
                            if (k != "disable") put(k, v)
                        }
                        put("disable", JsonPrimitive(false))
                    })
                }
                // Disable known leaked agents
                // Validate each name via isValidConfigKey (defense-in-depth — mirrors
                // writeAlwaysAllowRule). Today disabledAgentNames is always
                // AgentConstants.KNOWN_LEAKED_AGENTS (hardcoded trusted strings), but
                // the method is public and a future caller could pass untrusted names.
                for (agentName in disabledAgentNames) {
                    if (!isValidConfigKey(agentName)) {
                        logger.warn { "[ACP] McpConfigWriter.writeAgentOverrides: skipping invalid agent name in disabledAgentNames: '$agentName'" }
                        continue
                    }
                    val existing = (existingAgent[agentName] as? JsonObject) ?: buildJsonObject {}
                    put(agentName, buildJsonObject {
                        for ((k, v) in existing) {
                            if (k != "disable") put(k, v)
                        }
                        put("disable", JsonPrimitive(true))
                    })
                }
            }

            // Build new config preserving all other keys
            buildJsonObject {
                for ((key, value) in config) {
                    if (key != "agent" && key != "\$schema") put(key, value)
                }
                put("agent", updatedAgent)
            }
        }
        if (success) {
            logger.info { "[ACP] McpConfigWriter: wrote agent overrides (explore=$enableExplore, general=$enableGeneral, disabled=${disabledAgentNames.size})" }
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
