package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.util.copyExternalAttachmentToAllowedDir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Unit tests for [FileAttachmentService] (TDD §9 step 7 — FileAttachmentServiceTest).
 *
 * Tests cover:
 * - `requireImage` extension check (rejects non-image files, accepts image files)
 * - Path validation via [com.opencode.acp.chat.util.AttachmentPathValidator] when the
 *   external-copy fallback fails (file outside allowed dirs → null)
 * - In-project file → [AttachedFile] returned
 *
 * [VirtualFile] is mocked with mockk (see [ProjectFileSearchTest] for the same pattern).
 * [copyExternalAttachmentToAllowedDir] is stubbed via `mockkStatic` to control whether
 * the copy succeeds or fails — the real implementation does filesystem I/O that depends
 * on a real [Project.getBasePath], which is not available without a bootstrapped
 * IntelliJ application context.
 *
 * Tests that require a running IDE (real `Project.basePath`, real VFS, real filesystem
 * copy) are covered by integration tests, not here.
 */
class FileAttachmentServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var projectBaseFile: java.io.File

    @BeforeEach
    fun setUp() {
        projectBaseFile = tempDir.toFile()
        project = mockk(relaxed = true)
        every { project.basePath } returns projectBaseFile.absolutePath
        mockkStatic("com.opencode.acp.util.AttachmentUtilsKt")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("com.opencode.acp.util.AttachmentUtilsKt")
    }

    // ── requireImage ──────────────────────────────────────────────────────────

    @Test
    fun `requireImage rejects non-image file`() {
        val file = mockk<VirtualFile>()
        every { file.name } returns "test.kt"
        every { file.extension } returns "kt"

        val result = FileAttachmentService.addFileAttachment(file, project, requireImage = true)

        result shouldBe null
    }

    @Test
    fun `requireImage rejects file with no extension`() {
        val file = mockk<VirtualFile>()
        every { file.name } returns "README"
        every { file.extension } returns null

        val result = FileAttachmentService.addFileAttachment(file, project, requireImage = true)

        result shouldBe null
    }

    @Test
    fun `requireImage rejects file with empty extension`() {
        val file = mockk<VirtualFile>()
        every { file.name } returns "README."
        every { file.extension } returns ""

        val result = FileAttachmentService.addFileAttachment(file, project, requireImage = true)

        result shouldBe null
    }

    @Test
    fun `requireImage accepts png image and returns AttachedFile when copy succeeds`() {
        // Source file inside the project — copyExternalAttachmentToAllowedDir returns
        // the same file (already inside allowed dirs), so the AttachedFile path is
        // the source path.
        val sourceFile = java.io.File(projectBaseFile, "screenshot.png").apply { writeText("png") }
        val file = mockk<VirtualFile>()
        every { file.name } returns "screenshot.png"
        every { file.extension } returns "png"
        every { file.path } returns sourceFile.absolutePath

        // copyExternalAttachmentToAllowedDir returns the source itself (already in project)
        every {
            copyExternalAttachmentToAllowedDir(any(), any())
        } returns sourceFile

        val result = FileAttachmentService.addFileAttachment(file, project, requireImage = true)

        result.shouldBeInstanceOf<AttachedFile>()
        result.name shouldBe "screenshot.png"
        result.path shouldBe sourceFile.canonicalPath
        result.mime shouldBe "image/png"
    }

    @Test
    fun `requireImage accepts jpg image (case-insensitive extension)`() {
        val sourceFile = java.io.File(projectBaseFile, "photo.JPG").apply { writeText("jpg") }
        val file = mockk<VirtualFile>()
        every { file.name } returns "photo.JPG"
        every { file.extension } returns "JPG"
        every { file.path } returns sourceFile.absolutePath

        every {
            copyExternalAttachmentToAllowedDir(any(), any())
        } returns sourceFile

        val result = FileAttachmentService.addFileAttachment(file, project, requireImage = true)

        result.shouldBeInstanceOf<AttachedFile>()
        result.name shouldBe "photo.JPG"
    }

    // ── Path validation (copy fails) ───────────────────────────────────────────

    @Test
    fun `file outside allowed dirs with failed copy returns null`() {
        // Create a file OUTSIDE the project temp dir (in the system temp root).
        val outsideDir = tempDir.parent ?: tempDir
        val outsideFile = java.io.File(outsideDir.toFile(), "external-secret.txt").apply { writeText("secret") }
        try {
            val file = mockk<VirtualFile>()
            every { file.name } returns "external-secret.txt"
            every { file.extension } returns "txt"
            every { file.path } returns outsideFile.absolutePath

            // Simulate copy failure (e.g. attachments dir not writable)
            every {
                copyExternalAttachmentToAllowedDir(any(), any())
            } returns null

            val result = FileAttachmentService.addFileAttachment(file, project)

            result shouldBe null
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun `file inside project with failed copy returns AttachedFile using source path`() {
        // File inside the project — copy fails, but the source is already inside
        // allowed dirs, so the path validation passes and we return an AttachedFile
        // pointing at the source.
        val sourceFile = java.io.File(projectBaseFile, "in-project.kt").apply { writeText("kt") }
        val file = mockk<VirtualFile>()
        every { file.name } returns "in-project.kt"
        every { file.extension } returns "kt"
        every { file.path } returns sourceFile.absolutePath

        every {
            copyExternalAttachmentToAllowedDir(any(), any())
        } returns null

        val result = FileAttachmentService.addFileAttachment(file, project)

        result.shouldBeInstanceOf<AttachedFile>()
        result.name shouldBe "in-project.kt"
        result.path shouldBe sourceFile.canonicalPath
        result.mime shouldBe "text/x-kotlin"
    }

    // ── Copy succeeds (external file copied into attachments dir) ──────────────

    @Test
    fun `external file copied into attachments dir returns AttachedFile with copied path`() {
        // Source file outside the project (in the system temp root).
        val outsideDir = tempDir.parent ?: tempDir
        val outsideFile = java.io.File(outsideDir.toFile(), "external-doc.md").apply { writeText("doc") }
        try {
            // Simulate a successful copy into the project's attachments dir.
            val attachmentsDir = java.io.File(projectBaseFile, ".opencode/attachments").apply { mkdirs() }
            val copiedFile = java.io.File(attachmentsDir, "external-doc.md").apply { writeText("doc") }

            val file = mockk<VirtualFile>()
            every { file.name } returns "external-doc.md"
            every { file.extension } returns "md"
            every { file.path } returns outsideFile.absolutePath

            every {
                copyExternalAttachmentToAllowedDir(any(), any())
            } returns copiedFile

            val result = FileAttachmentService.addFileAttachment(file, project)

            result.shouldBeInstanceOf<AttachedFile>()
            // Display name uses the copied file's name (handles collision-resolved
            // names like "foo-1.png"); path points to the copied file's canonical path.
            result.name shouldBe "external-doc.md"
            result.path shouldBe copiedFile.canonicalPath
            result.mime shouldBe "text/markdown"
        } finally {
            outsideFile.delete()
        }
    }

    // ── Non-absolute / non-existent path ───────────────────────────────────────

    // ── Collision handling ─────────────────────────────────────────────────────

    @Test
    fun `collision during copy uses collision-resolved filename for display name`() {
        // When copyExternalAttachmentToAllowedDir copies a file and a name collision
        // occurs, the on-disk filename is "foo-1.png" (not "foo.png"). The AttachedFile
        // should use the collision-resolved name so the display name matches the path.
        val outsideDir = tempDir.parent ?: tempDir
        val outsideFile = java.io.File(outsideDir.toFile(), "image.png").apply { writeText("png") }
        try {
            val attachmentsDir = java.io.File(projectBaseFile, ".opencode/attachments").apply { mkdirs() }
            // Simulate a collision-resolved copy: the copied file is named "image-1.png"
            val copiedFile = java.io.File(attachmentsDir, "image-1.png").apply { writeText("png") }

            val file = mockk<VirtualFile>()
            every { file.name } returns "image.png"
            every { file.extension } returns "png"
            every { file.path } returns outsideFile.absolutePath

            every {
                copyExternalAttachmentToAllowedDir(any(), any())
            } returns copiedFile

            val result = FileAttachmentService.addFileAttachment(file, project)

            result.shouldBeInstanceOf<AttachedFile>()
            // Display name uses the collision-resolved name, not the original.
            result.name shouldBe "image-1.png"
            result.path shouldBe copiedFile.canonicalPath
            result.mime shouldBe "image/png"
        } finally {
            outsideFile.delete()
        }
    }

    @Test
    fun `non-existent relative path returns null for defense in depth`() {
        // sourceFile.isAbsolute == false OR sourceFile.exists() == false → the
        // else-branch now returns null (defense-in-depth) instead of an
        // AttachedFile with the raw unvalidated path. A non-absolute or
        // non-existent file can't be sent to the server anyway — the
        // service-level AttachmentValidator would reject it. Returning null
        // here gives the user immediate feedback (file not attached) rather
        // than a silent rejection at send time.
        val file = mockk<VirtualFile>()
        every { file.name } returns "memory-only.txt"
        every { file.extension } returns "txt"
        every { file.path } returns "/in-memory/memory-only.txt"

        val result = FileAttachmentService.addFileAttachment(file, project)

        result shouldBe null
    }

    // ── Exception handling ─────────────────────────────────────────────────────

    @Test
    fun `exception during processing returns null`() {
        val file = mockk<VirtualFile>()
        every { file.name } returns "boom.kt"
        every { file.extension } returns "kt"
        // file.path throws — simulates a disposed/invalid VFS file
        every { file.path } throws RuntimeException("VFS disposed")

        val result = FileAttachmentService.addFileAttachment(file, project)

        result shouldBe null
    }
}