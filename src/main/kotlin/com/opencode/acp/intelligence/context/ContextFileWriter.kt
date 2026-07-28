package com.opencode.acp.intelligence.context

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Writes context files to `.opencode/context/` in the project directory.
 *
 * Uses atomic write via temp file + Files.move (same pattern as McpConfigWriter.writeConfig).
 */
class ContextFileWriter(private val projectBasePath: Path) {

    /**
     * Write the repo-structure markdown to `.opencode/context/repo-structure.md`.
     *
     * @param content The markdown content.
     * @return true if written successfully, false on error.
     */
    fun writeRepoStructure(content: String): Boolean {
        return try {
            val contextDir = projectBasePath.resolve(".opencode").resolve("context")
            Files.createDirectories(contextDir)
            val targetFile = contextDir.resolve("repo-structure.md")
            val tempFile = Files.createTempFile(contextDir, "repo-structure.", ".tmp")
            try {
                Files.writeString(tempFile, content)
                try {
                    Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    logger.warn { "[ACP] ContextFileWriter: atomic move not supported, falling back to non-atomic" }
                    Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                }
                logger.info { "[ACP] ContextFileWriter: wrote repo-structure.md (${content.length} chars)" }
                true
            } catch (e: Exception) {
                try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "[ACP] ContextFileWriter: failed to write repo-structure.md" }
            false
        }
    }
}