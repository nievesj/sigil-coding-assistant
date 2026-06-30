package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Writes the pruner configuration to `.opencode/sigil-pruner.json` atomically.
 *
 * The TS plugin reads this file on load to determine pruning settings. The file
 * is written BEFORE [ProcessManager] launches the OpenCode binary, so the plugin
 * has the config available when it registers hooks.
 *
 * Communication is one-directional: Kotlin writes, TypeScript reads. The TS plugin
 * never writes to this file (it writes heartbeat timestamps to a separate file
 * to avoid races).
 *
 * Mirrors the [com.opencode.acp.mcp.McpConfigWriter] atomic-write pattern (temp
 * file + rename).
 */
object PrunerConfigWriter {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Writes the pruner config file from the given settings.
     *
     * @param projectBasePath The project root directory (where `.opencode/` lives).
     * @param settings The current settings state.
     * @return true if written successfully, false on error.
     */
    fun writeConfig(projectBasePath: String, settings: OpenCodeSettingsState): Boolean {
        return try {
            val opencodeDir = Path.of(projectBasePath, ".opencode")
            Files.createDirectories(opencodeDir)

            val configFile = opencodeDir.resolve(ChatConstants.PRUNER_CONFIG_FILENAME)
            val config = buildConfigObject(settings)

            // Write atomically via temp file + rename. ATOMIC_MOVE may not be
            // supported on all filesystems (e.g., Windows NTFS cross-volume).
            // Fall back to non-atomic move if ATOMIC_MOVE fails.
            val tempFile = Files.createTempFile(opencodeDir, "sigil-pruner.", ".tmp")
            try {
                Files.writeString(tempFile, json.encodeToString(JsonObject.serializer(), config))
                try {
                    Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.FileSystemException) {
                    // ATOMIC_MOVE not supported — fall back to non-atomic move
                    Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING)
                }
                logger.info { "[ACP] PrunerConfigWriter: wrote config to $configFile" }
                true
            } catch (e: Exception) {
                try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] PrunerConfigWriter: failed to write config" }
            false
        }
    }

    /**
     * Removes the config file. Called when the pruner is disabled in settings.
     *
     * @param projectBasePath The project root directory.
     * @return true if removed (or was already absent), false on error.
     */
    fun clearConfig(projectBasePath: String): Boolean {
        return try {
            val configFile = Path.of(projectBasePath, ".opencode", ChatConstants.PRUNER_CONFIG_FILENAME)
            if (Files.exists(configFile)) {
                Files.delete(configFile)
                logger.info { "[ACP] PrunerConfigWriter: removed config file" }
            }
            true
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] PrunerConfigWriter: failed to remove config file" }
            false
        }
    }

    /**
     * Builds the config JSON object from settings.
     *
     * Schema (matches the TS plugin's `PrunerConfig` interface):
     * ```json
     * {
     *   "enabled": true,
     *   "pluginApiVersion": 1,
     *   "deterministic": {
     *     "dedupEnabled": true,
     *     "pruneOldToolOutputs": true,
     *     "maxToolOutputMessages": 20,
     *     "pruneErroredToolInputs": true,
     *     "erroredToolTurns": 4
     *   },
     *   "compress": {
     *     "enabled": true,
     *     "mode": "range",
     *     "protectedTools": ["task", "skill", "todowrite", "todoread", "write", "edit"]
     *   },
     *   "nudge": {
     *     "enabled": true,
     *     "thresholdPercent": 60,
     *     "urgentPercent": 80,
     *     "cooldownTurns": 3,
     *     "defaultContextLimit": 128000
     *   }
     * }
     * ```
     */
    private fun buildConfigObject(settings: OpenCodeSettingsState): JsonObject {
        return buildJsonObject {
            put("enabled", JsonPrimitive(settings.enableContextPruner))
            put("pluginApiVersion", JsonPrimitive(ChatConstants.PRUNER_API_VERSION))
            put("deterministic", buildJsonObject {
                put("dedupEnabled", JsonPrimitive(true))
                put("pruneOldToolOutputs", JsonPrimitive(true))
                put("maxToolOutputMessages", JsonPrimitive(settings.prunerMaxToolOutputMessages))
                put("pruneErroredToolInputs", JsonPrimitive(true))
                put("erroredToolTurns", JsonPrimitive(settings.prunerErroredToolTurns))
            })
            put("compress", buildJsonObject {
                put("enabled", JsonPrimitive(settings.prunerCompressEnabled))
                put("mode", JsonPrimitive(settings.prunerCompressMode))
                put("protectedTools", kotlinx.serialization.json.buildJsonArray {
                    listOf("task", "skill", "todowrite", "todoread", "write", "edit").forEach {
                        add(JsonPrimitive(it))
                    }
                })
            })
            put("nudge", buildJsonObject {
                put("enabled", JsonPrimitive(settings.prunerNudgeEnabled))
                put("thresholdPercent", JsonPrimitive(settings.prunerNudgeThresholdPercent))
                put("urgentPercent", JsonPrimitive(settings.prunerNudgeUrgentPercent))
                put("cooldownTurns", JsonPrimitive(settings.prunerNudgeCooldownTurns))
                put("defaultContextLimit", JsonPrimitive(settings.prunerDefaultContextLimit))
            })
        }
    }
}