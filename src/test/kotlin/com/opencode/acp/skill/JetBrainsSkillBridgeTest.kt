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
 * [JetBrainsSkillBridge.isPluginManagedPath]. No mocking of PathManager is
 * required — all Platform dependencies are passed as parameters to the pure
 * function.
 *
 * Plugin ID history: the AI Assistant plugin ID changed across IDE versions
 * (`com.intellij.ai.assistant` → `com.intellij.ml.llm` in 2026.2), so the
 * gate was switched from plugin-presence to directory-existence. These tests
 * verify the directory-existence behavior directly.
 */
class JetBrainsSkillBridgeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detectSkillPathsPure returns IDE path when skill directory exists`() {
        // Create the IDE skill directory structure under tempDir
        val ideSkills = tempDir.resolve("aia/agents/.agents/skills")
        Files.createDirectories(ideSkills)

        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
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
        )

        result shouldBe emptyList()
    }

    @Test
    fun `detectSkillPathsPure returns empty when basePath does not exist`() {
        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.resolve("nonexistent-base").toString(),
        )

        result shouldBe emptyList()
    }

    @Test
    fun `detectSkillPathsPure does not require plugin presence - directory existence is the gate`() {
        // This is the core regression test for the bug where the AI Assistant
        // plugin ID changed (`com.intellij.ai.assistant` → `com.intellij.ml.llm`)
        // and the plugin-presence gate failed silently, bridging zero skills.
        // Now the gate is directory-existence: if the dir is there, bridge it.
        val ideSkills = tempDir.resolve("aia/agents/.agents/skills")
        Files.createDirectories(ideSkills)

        val result = JetBrainsSkillBridge.detectSkillPathsPure(
            basePath = tempDir.toString(),
        )

        result.size shouldBe 1
        result[0] shouldBe ideSkills.toString()
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