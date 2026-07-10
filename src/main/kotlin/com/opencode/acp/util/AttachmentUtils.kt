package com.opencode.acp.util

import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Returns true if [file] is inside one of the allowed attachment directories
 * (project base, `<project>/.opencode/attachments/`, or `<user.home>/.opencode/attachments/`).
 *
 * Mirrors the guard in `OpenCodeService.sendMessageInternal()` so the UI layer can
 * decide whether a copy is needed before sending.
 */
fun isInsideAllowedAttachmentDirs(file: File, project: Project?): Boolean {
    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
    val projectBase = project?.basePath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
    val userHome = System.getProperty("user.home")?.let { runCatching { File(it).canonicalPath }.getOrNull() }

    // RESOLVED (false positive): File.separator IS appended before startsWith,
    // ensuring the path boundary. E.g., `C:\Project2\file`.startsWith(`C:\Project\`)
    // is FALSE — the separator prevents prefix-confusion (CWE-22).
    val isInsideProject = projectBase != null && canonical.startsWith(projectBase + File.separator)
    val isInsideProjectAttachments = projectBase != null &&
        canonical.startsWith(projectBase + File.separator + ".opencode" + File.separator + "attachments" + File.separator)
    val isInsideUserHomeAttachments = userHome != null &&
        canonical.startsWith(userHome + File.separator + ".opencode" + File.separator + "attachments" + File.separator)
    return isInsideProject || isInsideProjectAttachments || isInsideUserHomeAttachments
}

/**
 * Resolves the `.opencode/attachments/` directory used for stashing external files.
 *
 * Prefers `<project.basePath>/.opencode/attachments/` (auto-cleaned with the project).
 * Falls back to `<user.home>/.opencode/attachments/` when the project base path is
 * unavailable (won't be auto-cleaned on project close — only on IDE shutdown).
 *
 * RESOLVED: The user.home fallback directory is NOT auto-cleaned by the plugin.
 * Users should manually clear `<user.home>/.opencode/attachments/` periodically
 * to reclaim disk space from stashed external attachments. This is documented
 * behavior, not a bug — the fallback only triggers when basePath is unavailable
 * (rare), and auto-deleting user.home files on the user's behalf would be
 * surprising and potentially destructive.
 *
 * Creates the directory if it doesn't exist. Returns null on failure.
 */
private fun resolveAttachmentsDir(project: Project?): File? {
    val baseDir = project?.basePath
    if (baseDir == null) {
        logger.warn { "[ACP] No project basePath; copying external attachment to user.home/.opencode/attachments (will not be auto-cleaned)" }
    }
    val effectiveBase = baseDir ?: System.getProperty("user.home") ?: return null
    val attachmentsDir = File(effectiveBase, ".opencode/attachments")
    if (!attachmentsDir.exists() && !attachmentsDir.mkdirs()) {
        logger.error { "[ACP] Could not create ${attachmentsDir.absolutePath} — external attachment not copied" }
        return null
    }
    return attachmentsDir
}

/**
 * Copies a file from outside the allowed attachment directories into
 * `<project>/.opencode/attachments/` (or `<user.home>/.opencode/attachments/` as
 * fallback) so it passes the security guard in `OpenCodeService.sendMessageInternal()`.
 *
 * - Preserves the original filename; on name collision, appends `-<n>` before the
 *   extension (e.g. `foo.png` → `foo-1.png`, `foo-1.png` → `foo-2.png`).
 * - If [sourceFile] is already inside an allowed directory, returns it unchanged.
 * - Returns null on I/O failure (caller should fall back to the original path and
 *   let the service guard log/skip it).
 *
 * This is the file-attachment analog of [saveClipboardImageToDisk] and uses the same
 * directory layout and fallback strategy.
 */
fun copyExternalAttachmentToAllowedDir(sourceFile: File, project: Project?): File? {
    if (!sourceFile.exists() || !sourceFile.canRead()) {
        logger.warn { "[ACP] copyExternalAttachmentToAllowedDir: source not readable: ${sourceFile.absolutePath}" }
        return null
    }
    if (isInsideAllowedAttachmentDirs(sourceFile, project)) {
        return sourceFile
    }
    val attachmentsDir = resolveAttachmentsDir(project) ?: return null

    // Security: refuse to write into a symlinked attachments directory. A symlink
    // could redirect writes outside the intended location, bypassing the path
    // traversal guards in OpenCodeService.sendMessageInternal(). This is a
    // defense-in-depth measure — the canonical path check above already prevents
    // most exfiltration, but a symlinked attachments dir could still trick the
    // isInsideProjectAttachments check into allowing writes to arbitrary locations.
    if (java.nio.file.Files.isSymbolicLink(attachmentsDir.toPath())) {
        logger.warn { "[ACP] copyExternalAttachmentToAllowedDir: attachments dir is a symlink — refusing to write: ${attachmentsDir.absolutePath}" }
        return null
    }

    val originalName = sourceFile.name
    val dotIdx = originalName.lastIndexOf('.')
    val baseName = if (dotIdx > 0) originalName.substring(0, dotIdx) else originalName
    val ext = if (dotIdx > 0) originalName.substring(dotIdx) else ""

    var target = File(attachmentsDir, originalName)
    var counter = 1
    while (target.exists()) {
        target = File(attachmentsDir, "$baseName-$counter$ext")
        counter++
    }

    return try {
        sourceFile.copyTo(target, overwrite = false)
        logger.info { "[ACP] Copied external attachment '${sourceFile.name}' -> '${target.absolutePath}'" }
        target
    } catch (e: Exception) {
        logger.warn(e) { "[ACP] Failed to copy external attachment '${sourceFile.name}' into ${attachmentsDir.absolutePath}" }
        null
    }
}