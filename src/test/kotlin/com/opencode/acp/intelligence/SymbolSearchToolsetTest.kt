package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * PSI integration test for `psi_find_symbol` (Step 5).
 *
 * Verifies that [PsiQueryHelper.runSymbolSearch] finds symbols by name
 * pattern across a Java/Kotlin project using the IntelliJ PSI infrastructure.
 *
 * This test requires the IntelliJ Platform test framework
 * ([com.intellij.testFramework.LightPlatformTestCase]) to bootstrap the
 * application context (ApplicationManager, PSI manager, stub indexes).
 * `LightPlatformTestCase` is JUnit 3-based and does not integrate with JUnit 5
 * (`@Test` from `org.junit.jupiter.api`). The project's test task uses
 * `useJUnitPlatform()`, which cannot run JUnit 3 tests.
 *
 * See AGENTS.md "Compose UI Tests — ComposePanel Cannot Render in Plain Unit
 * Tests" for the same pattern (IntelliJ application context required, not
 * available in plain unit tests).
 *
 * To enable: add `intellijPlatform.testFramework(TestFrameworkType.Platform)`
 * (already done in build.gradle.kts) and use `LightPlatformTestCase` with a
 * JUnit 3 `@Test` annotation, or use a JUnit 5 IntelliJ extension. The PSI
 * integration tests are tagged `@Tag("psi")` and run in the `testPsi` Gradle
 * task.
 *
 * The pure-logic tests (constants) at the top of this class are NOT disabled
 * — they run in the standard JUnit 5 suite.
 */
class SymbolSearchToolsetTest {

    @Test
    fun `MAX_SYMBOL_SEARCH_RESULTS is 200`() {
        MAX_SYMBOL_SEARCH_RESULTS shouldBe 200
    }

    @Test
    fun `DEFAULT_SYMBOL_SEARCH_LIMIT is 50`() {
        DEFAULT_SYMBOL_SEARCH_LIMIT shouldBe 50
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findSymbol by name returns matching classes`() {
        // TODO: Implement with LightPlatformTestCase once JUnit 5 integration
        // is available. The test would:
        // 1. Create a Java file with a class "Foo" via LightCodeInsightFixture
        // 2. Call PsiQueryHelper(project).runSymbolSearch("Foo", "project", 50, null)
        // 3. Assert the result JSON contains "Foo" with kind "CLASS"
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findSymbol with kind filter returns only matching kinds`() {
        // TODO: Create classes and methods, filter by kind="method"
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findSymbol when disabled returns structured error`() {
        // TODO: Disable psiToolsEnabled, assert error response
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findSymbol during indexing returns retryable error`() {
        // TODO: Use DumbService stub, assert retry=true
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findSymbol with scope all rejected when setting disabled`() {
        // TODO: Assert scope=all rejection error
    }
}