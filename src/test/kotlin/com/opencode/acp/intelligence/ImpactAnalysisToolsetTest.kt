package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * PSI integration test for `psi_impact_analysis` (Step 9).
 *
 * Verifies that [PsiQueryHelper.runImpactAnalysis] computes the blast radius
 * of changing a symbol: direct references, overrides (methods), inheritors
 * (classes), transitive closure (BFS with depth), and risk scoring.
 *
 * The PSI integration tests below are `@Disabled` because they require the
 * IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3.
 * See AGENTS.md "Compose UI Tests" section for details.
 *
 * The pure-logic tests (constants) at the top of this class are NOT disabled
 * — they run in the standard JUnit 5 suite.
 */
class ImpactAnalysisToolsetTest {

    @Test
    fun `MAX_IMPACT_DEPTH is 3`() {
        MAX_IMPACT_DEPTH shouldBe 3
    }

    @Test
    fun `MAX_IMPACT_RESULTS is 300`() {
        MAX_IMPACT_RESULTS shouldBe 300
    }

    @Test
    fun `TOOL_TIMEOUT_IMPACT_ANALYSIS_MS is 60 seconds`() {
        TOOL_TIMEOUT_IMPACT_ANALYSIS_MS shouldBe 60_000L
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis on method returns direct references`() {
        // TODO: Create foo() called by bar() and baz(),
        // assert impactAnalysis("foo", depth=1) returns 2 affected symbols
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis on class returns inheritors`() {
        // TODO: Create interface Foo implemented by Bar and Baz,
        // assert impactAnalysis("Foo") returns [Bar, Baz] with "inherits"
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis on public method returns CRITICAL risk`() {
        // TODO: Create public method, assert riskLevel=CRITICAL
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis with depth 2 includes transitive references`() {
        // TODO: Create 2-level reference chain, assert depth=2 finds both
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis timeout returns retryable error`() {
        // TODO: Mock slow search, assert timeout error with retry=true
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `impactAnalysis path traversal on file param returns error`() {
        // TODO: Pass file outside project, assert rejection
    }
}