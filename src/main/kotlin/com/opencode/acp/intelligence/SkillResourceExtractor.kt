package com.opencode.acp.intelligence

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * Extracts bundled skill markdown files from JAR resources to `.opencode/skills/`
 * before the OpenCode server launches. The server discovers skills from
 * `.opencode/skills/` on startup, so the files must be on disk before
 * [com.opencode.acp.chat.service.ProcessManager] launches the binary.
 *
 * Mirrors the [com.opencode.acp.chat.processor.PrunerResourceExtractor] pattern:
 * extract from JAR resources, but NEVER overwrite existing files — the user
 * may have customized the skill. To get a fresh copy, the user deletes the
 * file and restarts the IDE.
 *
 * @param projectBasePath The project root directory (where `.opencode/` lives).
 */
class SkillResourceExtractor(private val projectBasePath: java.nio.file.Path) {

    /**
     * Bundled skills to extract. Each entry maps a JAR resource path to a
     * target relative path under `.opencode/skills/`.
     */
    private val bundledSkills = listOf(
        "/opencode-skills/psi-code-intelligence/SKILL.md" to "psi-code-intelligence/SKILL.md",
    )

    /**
     * Extract all bundled skills to `.opencode/skills/`.
     * Only extracts files that don't already exist — never overwrites user-modified files.
     * Does NOT delete files that are no longer bundled — user may have added custom skills.
     *
     * @return true if all extractions succeeded (or were already present).
     */
    fun extractAll(): Boolean {
        val skillsDir = projectBasePath.resolve(".opencode").resolve("skills")
        var allOk = true

        // Clean up orphaned temp files from previous failed extractions
        try {
            if (Files.exists(skillsDir)) {
                Files.list(skillsDir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("skill.") && it.fileName.toString().endsWith(".tmp") }
                        .forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
                }
            }
        } catch (_: Exception) {
            // Best-effort cleanup — don't fail if cleanup doesn't work
        }

        for ((resourcePath, targetRelative) in bundledSkills) {
            try {
                val resource = javaClass.getResourceAsStream(resourcePath)
                if (resource == null) {
                    logger.warn { "[ACP] SkillResourceExtractor: resource not found: $resourcePath" }
                    allOk = false
                    continue
                }

                val resourceContent = resource.use { it.readAllBytes().decodeToString().removePrefix("\uFEFF") }

                val target = skillsDir.resolve(targetRelative)
                Files.createDirectories(target.parent)

                // Only extract if the file doesn't exist. Never overwrite existing files —
                // the user may have customized the skill. To get a fresh copy, the user
                // deletes the file and restarts the IDE.
                if (Files.exists(target)) {
                    logger.debug { "[ACP] SkillResourceExtractor: $targetRelative already exists (user may have customized) — skipping" }
                    continue
                }

                logger.info { "[ACP] SkillResourceExtractor: extracting $targetRelative" }

                // Atomic write via temp file + move (same pattern as McpConfigWriter/ContextFileWriter)
                val tempFile = Files.createTempFile(target.parent, "skill.", ".tmp")
                try {
                    Files.writeString(tempFile, resourceContent)
                    try {
                        Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        logger.warn { "[ACP] SkillResourceExtractor: atomic move not supported, falling back to non-atomic" }
                        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    try { Files.deleteIfExists(tempFile) } catch (_: Exception) {}
                    throw e
                }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] SkillResourceExtractor: failed to extract $resourcePath" }
                allOk = false
            }
        }

        return allOk
    }
}