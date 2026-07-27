package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure-logic parts of [RepoMapCacheService].
 *
 * These tests do NOT require a [com.intellij.openapi.project.Project] and
 * therefore run in the main test task (no `@Tag("psi")`).
 *
 * Note: [RepoMapCacheService] is a project-scoped service (`@Service(PROJECT)`),
 * so we cannot construct it directly without a Project. Instead, we test the
 * cache freshness logic via the pure companion function
 * [RepoMapCacheService.isCacheFresh] and the TTL constant.
 */
class RepoMapCacheServiceTest {

    @Test
    fun `isCacheFresh with null cache returns false`() {
        RepoMapCacheService.isCacheFresh(null) shouldBe false
    }

    @Test
    fun `isCacheFresh with fresh cache returns true`() {
        val ttl = REPO_MAP_CACHE_TTL_MS
        val now = System.currentTimeMillis()
        val cache = RepoMapCacheService.RepoMapCache(
            entries = emptyList(),
            timestamp = now - (ttl - 1000),
        )
        RepoMapCacheService.isCacheFresh(cache, now) shouldBe true
    }

    @Test
    fun `isCacheFresh with stale cache returns false`() {
        val ttl = REPO_MAP_CACHE_TTL_MS
        val now = System.currentTimeMillis()
        val cache = RepoMapCacheService.RepoMapCache(
            entries = emptyList(),
            timestamp = now - (ttl + 1000),
        )
        RepoMapCacheService.isCacheFresh(cache, now) shouldBe false
    }

    @Test
    fun `isCacheFresh at exact TTL boundary returns false`() {
        val ttl = REPO_MAP_CACHE_TTL_MS
        val now = System.currentTimeMillis()
        val cache = RepoMapCacheService.RepoMapCache(
            entries = emptyList(),
            timestamp = now - ttl, // exactly at TTL
        )
        // now - timestamp < TTL → ttl < ttl → false (boundary is exclusive)
        RepoMapCacheService.isCacheFresh(cache, now) shouldBe false
    }

    @Test
    fun `REPO_MAP_CACHE_TTL_MS is 5 minutes`() {
        REPO_MAP_CACHE_TTL_MS shouldBe 300_000L
    }

    @Test
    fun `REPO_MAP_COMPUTATION_TIMEOUT_MS is 15 seconds`() {
        REPO_MAP_COMPUTATION_TIMEOUT_MS shouldBe 15_000L
    }

    @Test
    fun `REPO_MAP_SAMPLE_SIZE is 500`() {
        REPO_MAP_SAMPLE_SIZE shouldBe 500
    }
}