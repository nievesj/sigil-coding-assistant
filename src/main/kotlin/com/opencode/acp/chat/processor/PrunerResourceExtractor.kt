package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Extracts `sigil-pruner.ts` from JAR resources to `.opencode/plugins/` before the
 * OpenCode server launches. The server loads plugins from `.opencode/plugins/`
 * on startup, so the file must be on disk before [ProcessManager] launches the binary.
 *
 * Overwrites the existing file if the version marker in the header comment differs
 * from the bundled version. This ensures updates take effect without manual cleanup.
 *
 * Mirrors the [com.opencode.acp.mcp.McpConfigWriter] pattern of preparing files
 * before `ProcessManager.initialize()` launches the binary.
 *
 * @param projectBasePath The project root directory (where `.opencode/` lives).
 *        Injected via constructor for testability — see TDD §4.2.4.
 */
class PrunerResourceExtractor(private val projectBasePath: java.nio.file.Path) {

    /**
     * Extracts `sigil-pruner.ts` from JAR resources to `.opencode/plugins/`.
     * Overwrites if the version marker differs from the bundled resource.
     *
     * @return true if extraction succeeded (or file was already up to date).
     */
    fun extractPlugin(): Boolean {
        return try {
            val targetDir = projectBasePath.resolve(".opencode").resolve("plugins")
            Files.createDirectories(targetDir)

            val target = targetDir.resolve(ChatConstants.PRUNER_PLUGIN_FILENAME)
            val resource = javaClass.getResourceAsStream(ChatConstants.PRUNER_RESOURCE_PATH)
                ?: run {
                    logger.warn { "[ACP] PrunerResourceExtractor: bundled sigil-pruner.ts not found in JAR" }
                    return false
                }

            val resourceContent = resource.use { it.readAllBytes() }.decodeToString().removePrefix("\uFEFF")
            val resourceHash = computeSha256OfString(resourceContent)

            // Check if existing file has the same version (header comment)
            if (Files.exists(target)) {
                val existing = Files.readString(target).take(200)
                val existingVersion = extractVersionMarker(existing)
                val resourceVersion = extractVersionMarker(resourceContent)
                if (existingVersion != null && existingVersion == resourceVersion) {
                    // Version matches — verify integrity
                    val diskHash = computeSha256(target)
                    if (diskHash == resourceHash) {
                        logger.debug { "[ACP] PrunerResourceExtractor: sigil-pruner.ts already up to date (v$existingVersion, integrity verified)" }
                        return true
                    } else {
                        logger.warn { "[ACP] PrunerResourceExtractor: sigil-pruner.ts version matches but hash differs — re-extracting (file may have been modified externally)" }
                        // Fall through to re-extraction
                    }
                }
            }

            // Atomic write via shared utility (temp file + rename with fallback)
            val written = com.opencode.acp.chat.util.AtomicFileWriter.writeAtomically(target, resourceContent)
            if (!written) {
                logger.error { "[ACP] PrunerResourceExtractor: failed to write sigil-pruner.ts to $target" }
                return false
            }
            logger.info { "[ACP] PrunerResourceExtractor: extracted sigil-pruner.ts to $target" }

            // Verify integrity of the extracted file
            val diskHash = computeSha256(target)
            if (diskHash == resourceHash) {
                logger.debug { "[ACP] PrunerResourceExtractor: integrity verified (SHA-256: ${diskHash.take(16)}...)" }
            } else {
                logger.error { "[ACP] PrunerResourceExtractor: integrity check FAILED after extraction — file may be corrupted. Expected: ${resourceHash.take(16)}..., got: ${diskHash.take(16)}..." }
                // Delete the corrupted file so it doesn't persist on disk.
                try { Files.delete(target) } catch (_: Exception) { }
                logger.warn { "[ACP] PrunerResourceExtractor: deleted corrupted file after integrity check failure" }
                // Return false so the pruner is disabled rather than running
                // with potentially corrupted code on the server.
                return false
            }

            true
        } catch (e: Exception) {
            logger.error(e) { "[ACP] PrunerResourceExtractor: failed to extract sigil-pruner.ts" }
            false
        }
    }

    /**
     * Removes the plugin file from `.opencode/plugins/`.
     * Called when the pruner is disabled in settings.
     */
    fun removePlugin() {
        try {
            val target = projectBasePath.resolve(".opencode").resolve("plugins").resolve(ChatConstants.PRUNER_PLUGIN_FILENAME)
            if (Files.exists(target)) {
                Files.delete(target)
                logger.info { "[ACP] PrunerResourceExtractor: removed sigil-pruner.ts" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] PrunerResourceExtractor: failed to remove sigil-pruner.ts" }
        }
    }

    /**
     * Checks if the plugin file exists on disk.
     *
     * @return true if the file exists.
     */
    fun isPluginPresent(): Boolean {
        return Files.exists(projectBasePath.resolve(".opencode").resolve("plugins").resolve(ChatConstants.PRUNER_PLUGIN_FILENAME))
    }

    /**
     * Computes SHA-256 hash of a file's content.
     *
     * NOTE: Returns an empty string on any failure (I/O error, digest error).
     * Returning null would be more explicit, but empty string is used for
     * simplicity and to match [computeSha256OfString]'s contract.
     */
    private fun computeSha256(path: java.nio.file.Path): String {
        return try {
            val bytes = Files.readAllBytes(path)
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Computes SHA-256 of a string (for comparing with on-disk file).
     */
    private fun computeSha256OfString(content: String): String {
        return try {
            val bytes = content.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Extracts the version from a header comment like `// sigil-pruner v1.2.3`.
     *
     * @param content The file content (first ~200 chars is enough).
     * @return The version string (e.g. "1.2.3") or null if not found.
     */
    private fun extractVersionMarker(content: String): String? {
        val match = Regex("""//\s*sigil-pruner\s+v([\d.]+)""").find(content)
        return match?.groupValues?.getOrNull(1)
    }
}