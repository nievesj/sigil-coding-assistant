package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.util.AttachmentConstants
import com.opencode.acp.chat.util.AttachmentPathValidator
import com.opencode.acp.util.MimeTypes
import com.opencode.acp.util.copyExternalAttachmentToAllowedDir
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Reads a [VirtualFile] into an [AttachedFile].
 *
 * Extracted from `ChatScreen.kt` (Phase 4 — TDD `docs/tdd/ui-testability-refactor.md`
 * §9 step 7). Pure I/O + validation logic — no Compose dependency, so it can be
 * unit-tested in isolation (with mocked [VirtualFile] / [Project]).
 *
 * If the file lives outside the allowed attachment directories (project base,
 * `<project>/.opencode/attachments/`, `<user.home>/.opencode/attachments/`), it is
 * copied into `<project>/.opencode/attachments/` first so the security guard in
 * `OpenCodeService.sendMessageInternal()` doesn't silently drop it. The attachment
 * chip shows the copied path.
 *
 * MUST NOT touch (per TDD §4.7.2):
 * - [copyExternalAttachmentToAllowedDir] fallback (defense in depth)
 * - Path validation via [AttachmentPathValidator] (from Phase 1)
 * - MIME detection via [MimeTypes.guessFromFileName]
 * - The `requireImage` check for image extensions
 */
object FileAttachmentService {

    // Use the shared constant so the requireImage gate stays in sync with the
    // clipboard file-list filter in ClipboardReader. See AttachmentConstants.
    private val IMAGE_EXTENSIONS = AttachmentConstants.IMAGE_EXTENSIONS

    /**
     * Reads [file] into an [AttachedFile].
     *
     * @param file The virtual file to attach.
     * @param project The current project (used for the copy-into-attachments-dir fallback).
     * @param requireImage When true, reject files whose extension is not in the image set.
     * @return The [AttachedFile], or null if the file was rejected (non-image with
     *   [requireImage], outside allowed dirs with copy failure, or read error).
     */
    fun addFileAttachment(
        file: VirtualFile,
        project: Project,
        requireImage: Boolean = false,
    ): AttachedFile? {
        return try {
            if (requireImage) {
                val ext = file.extension?.lowercase() ?: ""
                if (ext !in IMAGE_EXTENSIONS) {
                    logger.warn { "[ACP] addFileAttachment: rejecting non-image file from image dialog: ${file.name} (.$ext)" }
                    return null
                }
            }
            // VirtualFile.path is already an absolute filesystem path for local files.
            val sourceFile = java.io.File(file.path)
            val mime = MimeTypes.guessFromFile(sourceFile)
            val effectivePath: AttachedFile? = if (sourceFile.isAbsolute && sourceFile.exists()) {
                // Copy external files (Desktop, Documents, iCloud Drive, ...) into the
                // project's .opencode/attachments/ dir so they pass the service guard.
                // copyExternalAttachmentToAllowedDir returns the sourceFile unchanged
                // when it's already inside an allowed dir — in that case, use the
                // canonical path directly. If the copy fails (returns null), fall back
                // to strict canonical validation (fail-closed).
                val copied = copyExternalAttachmentToAllowedDir(sourceFile, project)
                if (copied != null) {
                    // Copy succeeded OR file was already inside allowed dirs.
                    // Use the copied file's name (handles collision-resolved names
                    // like "foo-1.png") and canonical path. If canonicalization
                    // fails (broken symlink in path), fall back to absolutePath
                    // rather than rejecting — copyExternalAttachmentToAllowedDir
                    // already validated the file is in an allowed dir.
                    val canonicalPath = AttachmentPathValidator.canonicalizeOrReject(copied.absolutePath)
                        ?: copied.absolutePath
                    AttachedFile(name = copied.name, path = canonicalPath, mime = mime)
                } else {
                    // Copy failed — check if source is outside allowed dirs using
                    // the same fail-closed canonicalization as the service guard.
                    // NOTE: The `return null` statements below use Kotlin's
                    // non-local return — they exit addFileAttachment, NOT this
                    // `else` block. Do NOT refactor without restructuring.
                    val canonicalSource = AttachmentPathValidator.canonicalizeOrReject(sourceFile.path)
                        ?: return null
                    val projectBase = project.basePath?.let { AttachmentPathValidator.canonicalizeOrReject(it) }
                    val userHome = System.getProperty("user.home")?.let { AttachmentPathValidator.canonicalizeOrReject(it) }
                    if (!AttachmentPathValidator.isAllowed(canonicalSource, projectBase, userHome)) {
                        logger.warn { "[ACP] addFileAttachment: rejecting file outside allowed dirs: ${file.name} (${sourceFile.path})" }
                        return null
                    }
                    AttachedFile(name = file.name, path = canonicalSource, mime = mime)
                }
            } else {
                // Defense-in-depth: reject non-absolute or non-existent files at
                // attach time rather than passing an unvalidated raw path to the
                // service layer. A non-existent file (deleted between picker and
                // confirm) or a non-absolute path can't be sent to the server
                // anyway — the service-level AttachmentValidator would reject it.
                // Returning null here gives the user immediate feedback (file not
                // attached) instead of a silent rejection at send time, and avoids
                // relying on a future-weakened AttachmentValidator for safety.
                logger.warn { "[ACP] addFileAttachment: rejecting non-absolute or non-existent file: ${file.name} (${file.path})" }
                null
            }
            effectivePath
        } catch (e: Exception) {
            // Skip files that can't be read (e.g., deleted between picker and confirm)
            logger.debug(e) { "[ACP] addFileAttachment: failed to attach ${file.name}" }
            null
        }
    }
}