package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.SkillInfo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SkillManager] (TDD §8.2 — SkillManager scenarios).
 *
 * Uses mockk to stub [OpenCodeClient] (concrete class — mockk handles final classes).
 * The manager's [SkillManager.fetchAvailableSkills] is a suspend function, so tests
 * run inside `runTest`.
 *
 * Staleness note: [SkillManager.SKILL_STALENESS_MS] is 30 seconds. Tests that verify
 * the staleness skip behavior rely on real wall-clock time (System.currentTimeMillis)
 * advancing between calls — the gap between two synchronous `runTest` calls is well
 * under 30s, so the cache is considered fresh. To force a re-fetch, pass `force=true`.
 */
class SkillManagerTest {

    private lateinit var client: OpenCodeClient
    private lateinit var manager: SkillManager

    @BeforeEach
    fun setup() {
        client = mockk(relaxed = true)
        manager = SkillManager(clientProvider = { client })
    }

    @Test
    fun `fetchAvailableSkills populates availableSkills StateFlow`() = runTest {
        val skills = listOf(
            SkillInfo(name = "git-release", description = "Create releases", content = "..."),
            SkillInfo(name = "refactor", description = "Refactor code", content = "...")
        )
        coEvery { client.listSkills() } returns skills

        manager.fetchAvailableSkills()

        manager.availableSkills.value shouldBe skills
    }

    @Test
    fun `fetchAvailableSkills handles error gracefully without throwing`() = runTest {
        coEvery { client.listSkills() } throws RuntimeException("server error")

        // Should not throw — error is caught internally.
        manager.fetchAvailableSkills()

        // Initial empty state is preserved (no successful fetch has occurred).
        manager.availableSkills.value shouldHaveSize 0
    }

    @Test
    fun `fetchAvailableSkills does NOT clear cached list on fetch error`() = runTest {
        val skills = listOf(
            SkillInfo(name = "git-release", description = "Create releases", content = "...")
        )
        // First call succeeds — populates the cache.
        coEvery { client.listSkills() } returns skills
        manager.fetchAvailableSkills()
        manager.availableSkills.value shouldBe skills

        // Second call throws — cached list must be preserved.
        coEvery { client.listSkills() } throws RuntimeException("server error")
        manager.fetchAvailableSkills(force = true)

        manager.availableSkills.value shouldBe skills
    }

    @Test
    fun `fetchAvailableSkills with force=false skips fetch when cache is fresh`() = runTest {
        val skills = listOf(SkillInfo(name = "git-release", description = "Create releases"))
        coEvery { client.listSkills() } returns skills

        // First call fetches.
        manager.fetchAvailableSkills()
        // Second call (immediately, cache fresh) should skip.
        manager.fetchAvailableSkills(force = false)

        // listSkills() should have been invoked exactly once.
        coVerify(exactly = 1) { client.listSkills() }
        manager.availableSkills.value shouldBe skills
    }

    @Test
    fun `fetchAvailableSkills with force=true always fetches`() = runTest {
        val skills = listOf(SkillInfo(name = "git-release", description = "Create releases"))
        coEvery { client.listSkills() } returns skills

        // First call fetches.
        manager.fetchAvailableSkills()
        // Second call with force=true fetches again even though cache is fresh.
        manager.fetchAvailableSkills(force = true)

        // listSkills() should have been invoked exactly twice.
        coVerify(exactly = 2) { client.listSkills() }
    }

    @Test
    fun `fetchAvailableSkills returns early when client is null`() = runTest {
        val nullClientManager = SkillManager(clientProvider = { null })

        // Should not throw and should not attempt any client call.
        nullClientManager.fetchAvailableSkills()

        nullClientManager.availableSkills.value shouldHaveSize 0
    }

    @Test
    fun `fetchAvailableSkills rethrows CancellationException`() = runTest {
        coEvery { client.listSkills() } throws kotlinx.coroutines.CancellationException("test")

        var threw = false
        try {
            manager.fetchAvailableSkills(force = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        threw shouldBe true
    }
}