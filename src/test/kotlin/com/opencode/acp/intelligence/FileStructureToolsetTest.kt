package com.opencode.acp.intelligence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * PSI integration test for `psi_file_structure` (Step 7).
 *
 * Verifies that [PsiQueryHelper.runFileStructure] extracts classes, fields,
 * methods, and nested classes from a Java/Kotlin file with accurate
 * signatures.
 *
 * The PSI integration tests below are `@Disabled` because they require the
 * IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3.
 * See AGENTS.md "Compose UI Tests" section for details.
 *
 * The pure-logic tests (constants) at the top of this class are NOT disabled
 * — they run in the standard JUnit 5 suite.
 */
class FileStructureToolsetTest {

    @Test
    fun `CONTEXT_FILE_PATH is repo-structure dot md`() {
        CONTEXT_FILE_PATH shouldBe ".opencode/context/repo-structure.md"
    }

    @Test
    fun `CONTEXT_GLOB_PATTERN is context glob`() {
        CONTEXT_GLOB_PATTERN shouldBe ".opencode/context/**/*.md"
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `fileStructure on Java file returns classes fields and methods`() {
        // TODO: Create a Java file with a class, fields, and methods,
        // assert fileStructure returns correct structure JSON
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `fileStructure on Kotlin file uses Analysis API for inferred types`() {
        // TODO: Create a Kotlin file with inferred return types,
        // assert signatures show resolved types or <inferred> fallback
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `fileStructure on non-existent file returns error`() {
        // TODO: Assert "File not found" error
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `fileStructure path traversal returns error`() {
        // TODO: Pass file outside project, assert rejection
    }

    @Tag("psi")
    @Disabled("Requires IntelliJ Platform test framework (LightPlatformTestCase) which is JUnit 3. JUnit 5 integration not available. See AGENTS.md 'Compose UI Tests' section for similar pattern.")
    @Test
    fun `fileStructure on nested classes returns nested structure`() {
        // TODO: Create a file with nested classes, assert nestedClasses field
    }
}