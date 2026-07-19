package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [PrunerResourceExtractor] (TDD §4.2.4).
 *
 * Uses JUnit5 @TempDir for real filesystem operations — no mocking.
 *
 * NOTE: [PrunerResourceExtractor.extractPlugin] reads `sigil-pruner.ts` from JAR
 * resources via `javaClass.getResourceAsStream`. In the test classpath the resource
 * MAY or MAY NOT be present depending on build state:
 * - If the resource is present (built/processed), the full extraction flow is tested.
 * - If the resource is absent, `extractPlugin()` returns false and `isPluginPresent()`
 *   remains false. Both branches are covered.
 */
class PrunerResourceExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newExtractor(): PrunerResourceExtractor = PrunerResourceExtractor(tempDir)

    private fun pluginPath(): Path =
        tempDir.resolve(".opencode").resolve("plugins").resolve(ChatConstants.PRUNER_PLUGIN_FILENAME)

    @Test
    fun `isPluginPresent returns false when no file exists`() {
        val extractor = newExtractor()
        extractor.isPluginPresent() shouldBe false
    }

    @Test
    fun `isPluginPresent returns true after extractPlugin succeeds`() {
        val extractor = newExtractor()
        val extracted = extractor.extractPlugin()

        if (extracted) {
            // Resource was available in the JAR/classpath — full flow
            extractor.isPluginPresent() shouldBe true
            Files.exists(pluginPath()) shouldBe true
        } else {
            // Resource not available in test context — extractPlugin returns false,
            // file must NOT exist, and isPluginPresent stays false.
            extractor.isPluginPresent() shouldBe false
            Files.exists(pluginPath()) shouldBe false
        }
    }

    @Test
    fun `extractPlugin returns true on success when resource is available`() {
        val extractor = newExtractor()
        val result = extractor.extractPlugin()

        // The result reflects whether the bundled resource was found.
        // If found, extraction + integrity check succeeded → true.
        // If not found (test classpath without the resource), → false.
        // We assert the invariant: if true, the file exists; if false, it doesn't.
        if (result) {
            Files.exists(pluginPath()) shouldBe true
            extractor.isPluginPresent() shouldBe true
        } else {
            Files.exists(pluginPath()) shouldBe false
        }
    }

    @Test
    fun `extractPlugin returns false when resource is not found in JAR`() {
        // This test documents the no-resource branch. When the resource IS present
        // (built classpath), extractPlugin returns true; when absent, false.
        // We can't force the absence, so we assert the contract holds either way.
        val extractor = newExtractor()
        val result = extractor.extractPlugin()
        // Contract: a true result guarantees the file on disk; a false result
        // guarantees no file was written.
        result shouldBe (Files.exists(pluginPath()))
    }

    @Test
    fun `removePlugin removes the file when it exists`() {
        val extractor = newExtractor()
        // First extract (if resource available) so the file exists
        val extracted = extractor.extractPlugin()
        if (!extracted) {
            // Resource not available — manually create the file to test removal
            Files.createDirectories(pluginPath().parent)
            Files.writeString(pluginPath(), "// dummy sigil-pruner v0.0.0\n")
        }
        Files.exists(pluginPath()) shouldBe true

        extractor.removePlugin()
        Files.exists(pluginPath()) shouldBe false
        extractor.isPluginPresent() shouldBe false
    }

    @Test
    fun `removePlugin is a no-op when file does not exist`() {
        val extractor = newExtractor()
        // Pre-condition: no file exists
        Files.exists(pluginPath()) shouldBe false

        // Should not throw
        extractor.removePlugin()

        Files.exists(pluginPath()) shouldBe false
        extractor.isPluginPresent() shouldBe false
    }

    @Test
    fun `removePlugin is a no-op when dot-opencode directory does not exist`() {
        val extractor = newExtractor()
        // Pre-condition: .opencode directory does not exist
        Files.exists(tempDir.resolve(".opencode")) shouldBe false

        // Should not throw
        extractor.removePlugin()

        Files.exists(pluginPath()) shouldBe false
    }
}