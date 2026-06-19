package com.opencode.acp.review

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json

/**
 * Handles JSON parsing with lenient settings and validation.
 *
 * Uses kotlinx.serialization with `ignoreUnknownKeys = true` so that unknown
 * fields from future schema versions don't crash. Trailing commas are
 * tolerated (`isLenient = true`), and default values are NOT re-encoded
 * (`encodeDefaults = false`) to keep files small.
 *
 * Designed as a class (not a Kotlin `object`) so it can be injected and
 * substituted with a fake in tests. The constructor accepts an optional list
 * of [ReviewFileMigrator]s that chain on read (v1→v2, v2→v3, …).
 */
class ReviewCommentParser(
    /** Migrators applied in order on read (v1→v2, v2→v3, ...). Empty by default. */
    private val migrators: List<ReviewFileMigrator> = emptyList(),
) {

    private val logger = KotlinLogging.logger {}

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true    // tolerate trailing commas
        encodeDefaults = false
        // The LLM writes enum values in lowercase ("open"/"warning"); the
        // Kotlin enum constants are uppercase. Without this option, the file
        // fails to parse and the comment disappears from the index/gutter.
        decodeEnumsCaseInsensitive = true
    }

    /** Parse and optionally migrate a [ReviewFile] from raw JSON content.
     *  Returns null if the file is unparseable OR has an unsupported future
     *  `formatVersion` (plugin is older than the file) to avoid silent data
     *  loss. */
    fun parseReviewFile(content: String): ReviewFile? {
        val parsed: ReviewFile = try {
            json.decodeFromString<ReviewFile>(content)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to parse .review file: ${e.message}" }
            return null
        }

        if (parsed.formatVersion > CURRENT_FORMAT_VERSION) {
            logger.warn {
                "[ACP] Skipping .review/ file with unsupported formatVersion " +
                    "${parsed.formatVersion} (max supported: $CURRENT_FORMAT_VERSION)"
            }
            return null
        }

        // Apply migrators sorted by fromVersion to ensure correct chain order.
        // Use a while loop to re-apply from the start after each migration,
        // since a migration may bump the version to match a later migrator.
        var result = parsed
        val sortedMigrators = migrators.sortedBy { it.fromVersion }
        var migrated = true
        var iterations = 0
        val maxIterations = 10 // Safety guard against cyclic migrators
        while (migrated) {
            migrated = false
            for (migrator in sortedMigrators) {
                if (result.formatVersion == migrator.fromVersion) {
                    val previousVersion = result.formatVersion
                    result = migrator.migrate(result)
                    // Guard: break if version didn't increase (prevents infinite loops)
                    if (result.formatVersion <= previousVersion) {
                        logger.warn {
                            "[ACP] Migrator ${migrator.fromVersion}→${migrator.toVersion} " +
                                "did not increase formatVersion ($previousVersion→${result.formatVersion}). " +
                                "Breaking migration loop to prevent infinite loop."
                        }
                        break
                    }
                    migrated = true
                    break  // restart from the beginning with the new version
                }
            }
            if (++iterations >= maxIterations) {
                logger.warn {
                    "[ACP] Migration loop exceeded $maxIterations iterations. " +
                        "Possible cyclic migrators. Current version: ${result.formatVersion}"
                }
                break
            }
        }
        // Auto-stamp the format version after migration
        if (result.formatVersion != parsed.formatVersion) {
            result = result.copy(formatVersion = CURRENT_FORMAT_VERSION)
        }
        return result
    }

    fun serializeReviewFile(file: ReviewFile): String =
        json.encodeToString(file)
}