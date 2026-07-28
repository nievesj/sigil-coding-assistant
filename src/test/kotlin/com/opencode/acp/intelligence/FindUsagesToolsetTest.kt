package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * PSI integration test for `psi_find_references` (Step 6).
 *
 * Verifies that [PsiQueryHelper.runFindReferences] finds all references to a
 * symbol using the IntelliJ PSI reference search infrastructure.
 *
 * The PSI integration tests below are `@Disabled` because they require the
 * IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3.
 * See AGENTS.md "Compose UI Tests" section for details.
 *
 * The pure-logic tests (constants) at the top of this class are NOT disabled
 * — they run in the standard JUnit 5 suite.
 */
class FindUsagesToolsetTest {

    @Test
    fun `MAX_REFERENCE_RESULTS is 500`() {
        MAX_REFERENCE_RESULTS shouldBe 500
    }

    @Test
    fun `TOOL_TIMEOUT_FIND_REFERENCES_MS is 30 seconds`() {
        TOOL_TIMEOUT_FIND_REFERENCES_MS shouldBe 30_000L
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findReferences returns all call sites of a method`() {
        // TODO: Create a Java file with method `foo()` and a caller `bar()`,
        // assert findReferences("foo") returns a reference in bar()
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findReferences with file disambiguation resolves correct symbol`() {
        // TODO: Create two classes with same method name, disambiguate by file
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findReferences for non-existent symbol returns error`() {
        // TODO: Assert "No symbol matching" error
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findReferences with ambiguous symbol returns candidates`() {
        // TODO: Create two methods with same name, assert candidates response
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `findReferences path traversal on file param returns error`() {
        // TODO: Pass file outside project, assert rejection
    }
}