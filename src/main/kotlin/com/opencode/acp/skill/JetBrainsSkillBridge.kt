package com.opencode.acp.skill

import io.github.oshai.kotlinlogging.KotlinLogging
import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * Detects JetBrains AI Assistant skill directories that OpenCode
 * does not scan by default, so they can be bridged via skills.paths
 * in opencode.json.
 *
 * This is a stateless utility — no instance state, no lifecycle.
 *
 * Testability: the pure path-construction logic is extracted into
 * [detectSkillPathsPure], which takes basePath as a parameter. The public
 * [detectSkillPaths] delegates to it with the real Platform value. Unit tests
 * call [detectSkillPathsPure] directly — no mocking of PathManager required.
 *
 * Plugin ID history: the AI Assistant plugin was identified by
 * `com.intellij.ai.assistant` in older IDE versions and is now
 * `com.intellij.ml.llm` (verified in IntelliJ 2026.2). Rather than chase
 * renames, the IDE path is gated on **directory existence** only — if
 * `aia/agents/.agents/skills` exists, the AI Assistant created skills there
 * and they should be bridged regardless of whether the plugin is currently
 * loaded.
 */
object JetBrainsSkillBridge {

    /**
     * Returns skill directories that should be bridged to OpenCode.
     *
     * Detects the IDE-level skill storage:
     * `{PathManager.getSystemPath()}/aia/agents/.agents/skills`
     * — gated on directory existence only (not plugin presence).
     *   The AI Assistant plugin ID changed across versions
     *   (`com.intellij.ai.assistant` → `com.intellij.ml.llm`), so directory
     *   existence is the reliable signal: if the dir is there, the AI
     *   Assistant created skills and they should be bridged.
     *
     * Returns an empty list if the directory does not exist.
     */
    fun detectSkillPaths(): List<String> = detectSkillPathsPure(
        basePath = PathManager.getSystemPath(),
        log = true,
    )

    /**
     * Pure, testable core of [detectSkillPaths]. All Platform dependencies
     * are passed as parameters so this can be unit-tested without mocking.
     *
     * @param log when true, logs detected/missing paths via [KotlinLogging]
     *   for diagnostics. The pure function is silent by default so unit tests
     *   do not produce log noise.
     */
    fun detectSkillPathsPure(
        basePath: String,
        log: Boolean = false,
    ): List<String> {
        val paths = mutableListOf<String>()

        // IDE-level skill storage (gated on directory existence).
        // The plugin-presence check was removed because the AI Assistant
        // plugin ID changed across versions (`com.intellij.ai.assistant` in
        // older IDEs, `com.intellij.ml.llm` in 2026.2), causing the gate to
        // fail silently and bridge zero skills. Directory existence is the
        // reliable signal: the `aia/agents/.agents/skills` path is created
        // only by the AI Assistant, so its presence proves skills exist.
        val idePath = Paths.get(basePath, "aia", "agents", ".agents", "skills")
        if (Files.isDirectory(idePath)) {
            paths.add(idePath.toString())
        } else if (log) {
            logger.info { "[ACP] JetBrainsSkillBridge: IDE skills dir not found at $idePath" }
        }

        if (log) {
            logger.info { "[ACP] JetBrainsSkillBridge: detected ${paths.size} skill path(s): $paths" }
        }
        return paths
    }

    /**
     * Returns true if [path] is a plugin-managed skill path (i.e., one that
     * [detectSkillPaths] would produce). Used by McpConfigWriter.writeSkillPaths()
     * to evict stale plugin-managed paths while preserving user-added paths.
     *
     * Matching is path-shape-based, not sentinel/comment-based.
     * Handles both forward-slash and backslash separators.
     */
    fun isPluginManagedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.endsWith("/aia/agents/.agents/skills")
    }
}