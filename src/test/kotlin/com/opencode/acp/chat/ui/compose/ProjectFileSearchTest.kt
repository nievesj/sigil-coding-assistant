package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [ProjectFileSearch] (TDD §9 step 7 — ProjectFileSearchTest).
 *
 * Only [ProjectFileSearch.isSymLink] is unit-testable without a real IntelliJ
 * `Project` object (which requires the IntelliJ application context to be
 * bootstrapped). [ProjectFileSearch.searchProjectFiles] and
 * [ProjectFileSearch.computeRecentFiles] rely on IntelliJ platform services
 * (`FilenameIndex`, `FileEditorManager`, `EditorHistoryManager`,
 * `LocalFileSystem`) that are only available inside a running IDE or a
 * heavy integration test fixture. They are covered by integration tests
 * rather than pure unit tests.
 *
 * The [ProjectFileSearch.isSymLink] tests use mockk to stub [VirtualFile]
 * instances, avoiding the need for a running IntelliJ application. The real
 * filesystem symlink test uses [TempDir] + [Files.createSymbolicLink] and
 * mocks the [VirtualFile] to return the real on-disk path.
 */
class ProjectFileSearchTest {

    // ── isSymLink ────────────────────────────────────────────────────────────

    @Test
    fun `isSymLink on a non-LocalFileSystem file returns false`() {
        // A VirtualFile whose fileSystem is NOT LocalFileSystem (e.g. in-memory,
        // jar://, http://) cannot be a symlink.
        val nonLocalFs = mockk<VirtualFile>()
        val otherFs = mockk<com.intellij.openapi.vfs.VirtualFileSystem>()
        every { nonLocalFs.fileSystem } returns otherFs
        every { nonLocalFs.path } returns "/in-memory/file.txt"
        ProjectFileSearch.isSymLink(nonLocalFs) shouldBe false
    }

    @Test
    fun `isSymLink on a regular file returns false`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("regular.txt").toFile()
        file.writeText("hello")

        val localFs = mockk<LocalFileSystem>()
        val vf = mockk<VirtualFile>()
        every { vf.fileSystem } returns localFs
        every { vf.path } returns file.absolutePath

        ProjectFileSearch.isSymLink(vf) shouldBe false
    }

    @Test
    fun `isSymLink on a directory returns false`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()

        val localFs = mockk<LocalFileSystem>()
        val vf = mockk<VirtualFile>()
        every { vf.fileSystem } returns localFs
        every { vf.path } returns dir.absolutePath

        ProjectFileSearch.isSymLink(vf) shouldBe false
    }

    @Test
    fun `isSymLink on an actual symlink returns true`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("target.txt").toFile()
        target.writeText("target")
        val link = tempDir.resolve("link.txt")

        // Skip on platforms that don't support symbolic links (e.g. Windows without
        // developer mode / admin privileges, or restricted environments).
        try {
            Files.createSymbolicLink(link, target.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "Symbolic links not supported on this platform: ${e.message}")
            return
        }

        val localFs = mockk<LocalFileSystem>()
        val vf = mockk<VirtualFile>()
        every { vf.fileSystem } returns localFs
        every { vf.path } returns link.toFile().absolutePath

        ProjectFileSearch.isSymLink(vf) shouldBe true
    }

    @Test
    fun `isSymLink on a path with invalid characters fails closed as true`() {
        // A LocalFileSystem file whose path contains NUL characters cannot be
        // converted to a java.nio.file.Path, triggering InvalidPathException.
        // isSymLink fails closed (returns true) so the caller skips the file.
        val localFs = mockk<LocalFileSystem>()
        val vf = mockk<VirtualFile>()
        every { vf.fileSystem } returns localFs
        // NUL character is invalid in java.nio.file.Path on all platforms.
        every { vf.path } returns "/invalid\u0000path/file.txt"

        ProjectFileSearch.isSymLink(vf) shouldBe true
    }

    @Test
    fun `isSymLink fail-closed contract on SecurityException`() {
        // Documents the fail-closed contract: isSymLink returns true on
        // SecurityException. A SecurityException cannot be reliably injected
        // in a unit test without installing a SecurityManager (deprecated in
        // Java 17+). The contract is verified by code inspection of
        // ProjectFileSearch.isSymLink: the SecurityException catch block
        // returns `true`.
        //
        // This test is a placeholder documenting the contract. The
        // InvalidPathException path is covered by the test above.
        //
        // No assertion needed — this test exists to document the fail-closed
        // behavior for SecurityException and IOException, which cannot be
        // triggered without filesystem mocking or a SecurityManager.
    }

    // ── searchProjectFiles / computeRecentFiles ──────────────────────────────
    //
    // These functions require a real IntelliJ `Project` with the application
    // context bootstrapped (FilenameIndex, FileEditorManager, EditorHistoryManager,
    // LocalFileSystem are application-level services). They are not unit-testable
    // in isolation and are covered by integration tests in the
    // `com.opencode.acp.chat` integration test suite.
}