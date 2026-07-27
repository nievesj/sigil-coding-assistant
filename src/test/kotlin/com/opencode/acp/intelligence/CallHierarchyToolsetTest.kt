package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * PSI integration test for `psi_call_hierarchy` (Step 8).
 *
 * Verifies that [PsiQueryHelper.runCallHierarchy] builds caller and callee
 * trees using ReferencesSearch (callers) and PsiCallExpression resolution
 * (callees) with cycle detection and depth limiting.
 *
 * The PSI integration tests below are `@Disabled` because they require the
 * IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3.
 * See AGENTS.md "Compose UI Tests" section for details.
 *
 * The pure-logic tests (constants) at the top of this class are NOT disabled
 * — they run in the standard JUnit 5 suite.
 */
class CallHierarchyToolsetTest {

    @Test
    fun `MAX_CALL_HIERARCHY_DEPTH is 4`() {
        MAX_CALL_HIERARCHY_DEPTH shouldBe 4
    }

    @Test
    fun `MAX_CALL_HIERARCHY_NODES_PER_LEVEL is 50`() {
        MAX_CALL_HIERARCHY_NODES_PER_LEVEL shouldBe 50
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `callHierarchy callers returns enclosing methods of references`() {
        // TODO: Create foo() called by bar() called by baz(),
        // assert callers of foo returns [bar, [baz]]
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `callHierarchy callees returns resolved call targets`() {
        // TODO: Create foo() that calls bar() and baz(),
        // assert callees of foo returns [bar, baz]
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `callHierarchy with depth limit truncates tree`() {
        // TODO: Create 3-level call chain, assert depth=2 truncates
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `callHierarchy cycle detection prevents infinite recursion`() {
        // TODO: Create mutual recursion (foo calls bar calls foo),
        // assert no infinite loop
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `callHierarchy for non-method returns error`() {
        // TODO: Pass a class name, assert "not a method" error
    }
}