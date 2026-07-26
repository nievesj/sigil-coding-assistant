package com.opencode.acp.skill

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [JetBrainsSkillBridge] (TDD §8.2 scenarios 7-9 + 6d).
 *
 * Tests the pure function [JetBrainsSkillBridge.detectSkillPathsPure] and
 * [JetBrainsSkillBridge.isPluginManagedPath]. No mocking of PluginManager
 * or PathManager is required — all Platform dependencies are passed as
 * parameters to the pure function.
 */
class JetBrainsSkillBridgeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detectSkillPathsPure returns IDE path when skill directory exists and AI Assistant is installed`() {
        // Create the IDE skill directory structure under tempDir
        val ideSkills = tempDir.resolve("aia/agents/.agents/skills")
        Files.createDirectories(ideSkills)

        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
            isAiAssistantInstalled = true,
            userHome = tempDir.resolve("nonexistent-home").toString(), // avoid codex path collision
        )

        result shouldNotBe emptyList<String>()
        result.size shouldBe 1
        result[0] shouldBe ideSkills.toString()
    }

    @Test
    fun `detectSkillPathsPure returns empty when IDE skill directory does not exist`() {
        // Do not create the IDE skill directory
        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
            isAiAssistantInstalled = true,
            userHome = tempDir.resolve("nonexistent-home").toString(),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `detectSkillPathsPure returns empty when AI Assistant is not installed even if directory exists`() {
        // Create the IDE skill directory structure
        val ideSkills = tempDir.resolve("aia/agents/.agents/skills")
        Files.createDirectories(ideSkills)

        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
            isAiAssistantInstalled = false,
            userHome = tempDir.resolve("nonexistent-home").toString(),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `detectSkillPathsPure includes codex path when dot-codex skills exists`() {
        // Create the codex skills directory under tempDir (used as userHome)
        val codexSkills = tempDir.resolve(".codex/skills")
        Files.createDirectories(codexSkills)

        // Use a separate basePath that does NOT have the IDE skill dir
        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.resolve("nonexistent-base").toString(),
            isAiAssistantInstalled = true,
            userHome = tempDir.toString(),
        )

        result.size shouldBe 1
        result[0] shouldBe codexSkills.toString()
    }

    @Test
    fun `detectSkillPathsPure returns empty when neither directory exists`() {
        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.resolve("nonexistent-base").toString(),
            isAiAssistantInstalled = true,
            userHome = tempDir.resolve("nonexistent-home").toString(),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `detectSkillPathsPure returns both paths when both directories exist`() {
        // Create both directories under tempDir. Use tempDir as basePath for the
        // IDE path and a sub-directory as userHome for the codex path so they
        // don't collide.
        val ideSkills = tempDir.resolve("aia/agents/.agents/skills")
        Files.createDirectories(ideSkills)

        val homeDir = tempDir.resolve("home")
        Files.createDirectories(homeDir)
        val codexSkills = homeDir.resolve(".codex/skills")
        Files.createDirectories(codexSkills)

        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
            isAiAssistantInstalled = true,
            userHome = homeDir.toString(),
        )

        result.size shouldBe 2
        result[0] shouldBe ideSkills.toString()
        result[1] shouldBe codexSkills.toString()
    }

    @Test
    fun `isPluginManagedPath returns true for IDE path with forward slashes`() {
        val path = "/home/user/.cache/JetBrains/IntelliJIdea2026.1/aia/agents/.agents/skills"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe true
    }

    @Test
    fun `isPluginManagedPath returns true for IDE path with backslashes (Windows)`() {
        val path = "C:\\Users\\josen\\AppData\\Local\\JetBrains\\IntelliJIdea2026.1\\aia\\agents\\.agents\\skills"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe true
    }

    @Test
    fun `isPluginManagedPath returns true for codex path`() {
        val path = "/home/user/.codex/skills"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe true
    }

    @Test
    fun `isPluginManagedPath returns false for user-added custom path`() {
        val path = "/home/user/my-custom-skills"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe false
    }

    @Test
    fun `isPluginManagedPath returns false for path containing but not ending with pattern`() {
        // A user path that CONTAINS the pattern as a substring but does NOT
        // end with it should NOT be classified as plugin-managed.
        val path = "/home/user/my-aia/agents/.agents/skills-backup"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe false
    }

    @Test
    fun `isPluginManagedPath returns false for codex path containing but not ending with pattern`() {
        val path = "/home/user/projects/.codex/skills-backup"
        JetBrainsSkillBridge.isPluginManagedPath(path) shouldBe false
    }

    @Test
    fun `detectSkillPathsPure handles null userHome gracefully without throwing`() {
        // userHome can be null if -Duser.home= is set or in security-restricted envs.
        // Should not throw NPE — just skip the codex path.
        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.resolve("nonexistent-base").toString(),
            isAiAssistantInstalled = false,
            userHome = null as String?,
        )

        result shouldBe emptyList()
    }

    @Test
    fun `isPluginManagedPath returns true for user path ending with same suffix (known limitation)`() {
        // A user who manually adds a path ending with the plugin-managed suffix
        // will have it evicted on the next writeSkillPaths call. This is a
        // documented tradeoff (TDD §10 Q4) — the pattern is specific enough
        // that legitimate user paths are unlikely to collide, but this test
        // locks the behavior and documents the limitation for future maintainers.
        val userPath = "/home/user/custom-projects/aia/agents/.agents/skills"
        JetBrainsSkillBridge.isPluginManagedPath(userPath) shouldBe true
    }
}