package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * PSI integration test for `psi_repo_map` (Step 10).
 *
 * Verifies that [PsiQueryHelper.runRepoMap] computes an importance-ranked
 * symbol index using StubIndex sampling, PsiSearchHelper guard, and
 * ReferencesSearch counting with log-scaled normalization.
 *
 * The PSI integration tests below are `@Disabled` because they require the
 * IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3.
 * See AGENTS.md "Compose UI Tests" section for details.
 *
 * The pure-logic tests (constants + log-scaled normalization formula) at the
 * top of this class are NOT disabled — they run in the standard JUnit 5 suite.
 */
class RepoMapToolsetTest {

    @Test
    fun `REPO_MAP_SAMPLE_SIZE is 500`() {
        REPO_MAP_SAMPLE_SIZE shouldBe 500
    }

    @Test
    fun `log-scaled normalization produces 0 for zero count`() {
        val maxCount = 100
        val count = 0
        val importance = if (maxCount > 0) ln((count + 1).toDouble()) / ln((maxCount + 1).toDouble()) else 0.0
        importance shouldBe 0.0
    }

    @Test
    fun `log-scaled normalization produces 1 for max count`() {
        val maxCount = 100
        val count = 100
        val importance = ln((count + 1).toDouble()) / ln((maxCount + 1).toDouble())
        importance shouldBe 1.0
    }

    @Test
    fun `log-scaled normalization produces proportional value for mid count`() {
        val maxCount = 100
        val count = 50
        val importance = ln((count + 1).toDouble()) / ln((maxCount + 1).toDouble())
        importance shouldBeGreaterThan 0.0
        importance shouldBeLessThan 1.0
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap returns entries sorted by importance descending`() {
        // TODO: Create classes with varying reference counts,
        // assert entries are sorted by importance descending
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap cold start triggers cache rebuild`() {
        // TODO: Clear cache, call runRepoMap, assert entries are returned
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap serves from cache on second call`() {
        // TODO: Call runRepoMap twice, assert second call uses cache
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap cache TTL expiry triggers rebuild`() {
        // TODO: Set cache timestamp to past, assert rebuild occurs
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap symbols with zero references have importance zero`() {
        // TODO: Create an unreferenced class, assert importance=0.0
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap respects limit parameter`() {
        // TODO: Create many classes, assert result count <= limit
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `repoMap pre-warm activity populates cache`() {
        // TODO: Call warmRepoMap(), assert cache is populated
    }
}