package com.opencode.acp.skill

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Detects JetBrains AI Assistant skill directories that OpenCode
 * does not scan by default, so they can be bridged via skills.paths
 * in opencode.json.
 *
 * This is a stateless utility — no instance state, no lifecycle.
 *
 * Testability: the pure path-construction logic is extracted into
 * [detectSkillPathsPure], which takes basePath + isAiAssistantInstalled
 * as parameters. The public [detectSkillPaths] delegates to it with
 * real Platform values. Unit tests call [detectSkillPathsPure] directly
 * — no mocking of PluginManager or PathManager required.
 */
object JetBrainsSkillBridge {

    // Verify against the actual installed plugin before relying on this.
    // If the ID changes in a future AI Assistant version, the bridge silently
    // stops working (returns empty list for the IDE path).
    private const val AI_ASSISTANT_PLUGIN_ID = "com.intellij.ai.assistant"

    /**
     * Returns skill directories that should be bridged to OpenCode.
     *
     * Detects two paths:
     *  1. IDE-level skill storage: {PathManager.getSystemPath()}/aia/agents/.agents/skills
     *     — gated on AI Assistant plugin presence + directory existence.
     *  2. Codex Global scope: ~/.codex/skills
     *     — gated on directory existence only (no plugin to detect).
     *
     * Returns an empty list if neither directory exists.
     */
    fun detectSkillPaths(): List<String> = detectSkillPathsPure(
        basePath = PathManager.getSystemPath(),
        isAiAssistantInstalled = PluginManager.isPluginInstalled(
            PluginId.getId(AI_ASSISTANT_PLUGIN_ID)
        ),
        userHome = System.getProperty("user.home"),
    )

    /**
     * Pure, testable core of [detectSkillPaths]. All Platform dependencies
     * are passed as parameters so this can be unit-tested without mocking.
     */
    fun detectSkillPathsPure(
        basePath: String,
        isAiAssistantInstalled: Boolean,
        userHome: String?,
    ): List<String> {
        val paths = mutableListOf<String>()

        // 1. IDE-level skill storage (gated on plugin presence)
        if (isAiAssistantInstalled) {
            val idePath = Paths.get(basePath, "aia", "agents", ".agents", "skills")
            if (Files.isDirectory(idePath)) {
                paths.add(idePath.toString())
            }
        }

        // 2. Codex Global scope (existence check only — no plugin to detect)
        // Guard against null userHome (can be cleared via -Duser.home= or in
        // security-restricted environments). Paths.get(null, ...) would throw NPE.
        if (userHome != null) {
            val codexPath = Paths.get(userHome, ".codex", "skills")
            if (Files.isDirectory(codexPath)) {
                paths.add(codexPath.toString())
            }
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
        return normalized.endsWith("/aia/agents/.agents/skills") ||
            normalized.endsWith("/.codex/skills")
    }
}