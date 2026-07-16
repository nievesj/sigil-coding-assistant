package com.opencode.acp.chat.service

import com.opencode.acp.adapter.OpenCodePart
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.util.normalizeAttachmentMime
import com.opencode.acp.util.pathToFileUrl
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Result of validating and encoding file attachments.
 * [parts] contains the wire-format parts to send to the server.
 * [rejectedFiles] contains filenames that were skipped, with reasons for logging.
 */
internal data class AttachmentValidationResult(
    val parts: List<OpenCodePart>,
    val acceptedFileNames: List<String>,
    val rejectedFiles: List<RejectedAttachment>,
)

internal data class RejectedAttachment(
    val name: String,
    val path: String,
    val reason: String,
)

/**
 * Validates and encodes file attachments for sending to the OpenCode server.
 *
 * Security checks:
 * - CWE-22 path traversal: rejects paths escaping project/user-home/attachments directories
 * - Sensitive-path denylist: rejects .env, .git, .idea, node_modules, build/target/out (root-level)
 * - TOCTOU symlink-swap: re-verify canonical path after reading image bytes
 * - Size limit: rejects images > [MAX_ATTACHMENT_SIZE_BYTES]
 *
 * Encoding:
 * - Images: base64 data-URI (server requires data: URIs, not file://)
 * - Other files: canonical-path file:// URL with normalized MIME
 *
 * @param projectBasePath IDE project root directory, CANONICALIZED (for path-boundary checks).
 *   The caller MUST pass `java.io.File(project.basePath).canonicalPath` — the `startsWith`
 *   boundary check depends on canonical form to handle symlinked project roots.
 * @param userHomePath User home directory, CANONICALIZED (for attachment directory checks).
 *   The caller MUST pass `java.io.File(System.getProperty("user.home")).canonicalPath`.
 */
internal class AttachmentValidator(
    private val projectBasePath: String?,
    private val userHomePath: String?,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Validate and encode a list of attached files into wire-format parts.
     *
     * @param files User-attached files (path, name, mime)
     * @return Validation result with accepted parts + rejected file names (for logging)
     */
    fun validateAndEncode(files: List<AttachedFile>): AttachmentValidationResult {
        val parts = mutableListOf<OpenCodePart>()
        val acceptedFileNames = mutableListOf<String>()
        val rejectedFiles = mutableListOf<RejectedAttachment>()

        files.forEach { file ->
            if (file.path.isBlank()) {
                // Pre-rev2 history entries: clipboard images stored path="".
                // Cannot send an empty URL — skip this file and log a warning.
                logger.warn { "[ACP] Skipping attached file '${file.name}' with blank path (pre-rev2 legacy)" }
                rejectedFiles.add(RejectedAttachment(file.name, file.path, "blank path (pre-rev2 legacy)"))
                return@forEach
            }
            val fileObj = java.io.File(file.path)
            if (!fileObj.exists() || !fileObj.canRead()) {
                // File was deleted or unreadable (e.g., auto-cleaned clipboard image).
                // Skip with warning instead of sending a file:// URL the server can't read.
                logger.warn { "[ACP] Skipping attached file '${file.name}' — file not found or unreadable: ${file.path}" }
                rejectedFiles.add(RejectedAttachment(file.name, file.path, "file not found or unreadable"))
                return@forEach
            }
            // CWE-22 path traversal guard: reject paths with .. sequences that escape
            // known-safe locations. Clipboard images are stored in
            // <projectDir>/.opencode/attachments/ or user.home/.opencode/attachments/.
            // Project files are under basePath. System temp directory is NOT allowed
            // (too broad — any process can write there on shared machines).
            val canonicalPath = fileObj.canonicalPath
            // RESOLVED (false positive): File.separator IS appended to projectBase
            // before startsWith, so the path boundary is enforced. E.g.,
            // `C:\Project2\file`.startsWith(`C:\Project\`) is FALSE because
            // `C:\Project2` != `C:\Project\`. The separator prevents the
            // C:\Project → C:\Project2 prefix-confusion attack (CWE-22).
            val isInsideProject = projectBasePath != null && canonicalPath.startsWith(projectBasePath + java.io.File.separator)
            // NOTE: isInsideProjectAttachments is a strict subset of isInsideProject (any path
            // under .opencode/attachments/ is also under the project). Retained for readability
            // — the intent is explicit even though the check is logically redundant.
            val isInsideProjectAttachments = projectBasePath != null && canonicalPath.startsWith(projectBasePath + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
            val isInsideUserHomeAttachments = userHomePath != null && canonicalPath.startsWith(userHomePath + java.io.File.separator + ".opencode" + java.io.File.separator + "attachments" + java.io.File.separator)
            val isAllowed = isInsideProject || isInsideProjectAttachments || isInsideUserHomeAttachments
            if (!isAllowed) {
                logger.warn { "[ACP] Skipping attached file '${file.name}' — path escapes allowed directories: ${file.path}" }
                rejectedFiles.add(RejectedAttachment(file.name, file.path, "path escapes allowed directories"))
                return@forEach
            }
            // Denylist: reject known-sensitive path segments even if inside allowed directories.
            // Prevents accidental exfiltration of secrets, credentials, and VCS metadata
            // via prompt injection or social engineering.
            // Uses path-segment matching (not substring) to avoid false positives like
            // a project directory named "my.idea" or a source dir named "build/".
            // NOTE: The entire path is lowercased before segment splitting. This is intentional
            // defense-in-depth: on case-sensitive filesystems (Linux), a directory named "Build"
            // would match the denylist entry "build". This prevents exfiltration via case
            // variation (e.g., a symlink named ".ENV" pointing to secrets). The false-positive
            // risk (rejecting a legitimate "Build" directory on Linux) is acceptable given the
            // security context — attachment paths should not include build output directories.
            val pathSegments = canonicalPath.lowercase()
                .split(java.io.File.separatorChar, '/')
                .filter { it.isNotEmpty() }
            // Root-level segments: on Windows, index 0 is the drive letter (e.g., "c:"),
            // so the first directory is at index 1. On Unix, index 0 is the first
            // directory. Check both to handle cross-platform paths correctly.
            val rootSegmentIndex = if (pathSegments.isNotEmpty() && pathSegments[0].endsWith(":")) 1 else 0
            val isDenylisted = pathSegments.any { it in DENYLIST_SEGMENTS } ||
                (pathSegments.size > rootSegmentIndex && pathSegments[rootSegmentIndex] in ROOT_ONLY_DENYLIST)
            // Apply denylist to ALL paths regardless of location.
            // A symlink in .opencode/attachments/ pointing to .env would otherwise
            // bypass the denylist. The denylist must apply universally to prevent
            // exfiltration of secrets via symlink tricks.
            if (isDenylisted) {
                logger.warn { "[ACP] Skipping attached file '${file.name}' — path matches denylist segment (sensitive location): ${file.path}" }
                rejectedFiles.add(RejectedAttachment(file.name, file.path, "path matches denylist segment"))
                return@forEach
            }
            // Image attachments: send as data: URI (base64-embedded) instead of file:// URL.
            // The OpenCode server's Image.normalize() requires data: URIs; file:// URLs are
            // fragile on Windows and silently fail. The OpenCode CLI itself sends data: URIs.
            // The wire part type remains "file" with mime "image/*" — do NOT use type "image"
            // (the server does not recognize outbound "image" parts).
            if (file.mime.startsWith("image/")) {
                try {
                    val fileSize = fileObj.length()
                    if (fileSize > MAX_ATTACHMENT_SIZE_BYTES) {
                        logger.warn { "[ACP] Skipping attached image '${file.name}' — file too large (${fileSize / (1024*1024)}MB > ${MAX_ATTACHMENT_SIZE_BYTES / (1024*1024)}MB limit): ${file.path}" }
                        rejectedFiles.add(RejectedAttachment(file.name, file.path, "image too large"))
                        return@forEach
                    }
                    val bytes = fileObj.readBytes()
                    // Re-verify canonical path after read to detect TOCTOU symlink swap
                    val postReadCanonical = fileObj.canonicalPath
                    if (postReadCanonical != canonicalPath) {
                        logger.warn { "[ACP] Skipping attached image '${file.name}' — path changed during read (possible symlink swap): ${file.path}" }
                        rejectedFiles.add(RejectedAttachment(file.name, file.path, "TOCTOU symlink swap detected"))
                        return@forEach
                    }
                    // NOTE: This detects swaps that persist after the read, but NOT
                    // mid-read swaps that are reverted before this check. For high-security
                    // contexts, use Files.readAllBytes(Path) with a pre-opened FileChannel.
                    // Low-probability attack — documented as residual risk.
                    if (bytes.isEmpty()) {
                        logger.warn { "[ACP] Skipping attached image '${file.name}' — file is empty: ${file.path}" }
                        rejectedFiles.add(RejectedAttachment(file.name, file.path, "image file is empty"))
                        return@forEach
                    }
                    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    val dataUri = "data:${file.mime};base64,$base64"
                    parts.add(OpenCodePart.File(
                        mime = file.mime, url = dataUri, filename = file.name
                    ))
                    acceptedFileNames.add(file.name)
                } catch (e: Exception) {
                    logger.warn { "[ACP] Skipping attached image '${file.name}' — failed to read for data URI: ${file.path}" }
                    rejectedFiles.add(RejectedAttachment(file.name, file.path, "failed to read image for data URI"))
                    return@forEach
                }
            } else {
                // Non-image attachments: use canonical path for the URL to prevent
                // symlink-based exfiltration.
                val url = pathToFileUrl(canonicalPath)
                if (url == null) {
                    logger.warn { "[ACP] Skipping attached file '${file.name}' — pathToFileUrl returned null for: ${file.path}" }
                    rejectedFiles.add(RejectedAttachment(file.name, file.path, "pathToFileUrl returned null"))
                    return@forEach
                }
                val wireMime = normalizeAttachmentMime(file.mime)
                parts.add(OpenCodePart.File(
                    mime = wireMime, url = url, filename = file.name
                ))
                acceptedFileNames.add(file.name)
            }
        }
        return AttachmentValidationResult(parts, acceptedFileNames, rejectedFiles)
    }

    companion object {
        const val MAX_ATTACHMENT_SIZE_BYTES = 10L * 1024 * 1024  // 10MB

        // Denylist segments — moved here from inline setOf() in sendMessageInternal
        val DENYLIST_SEGMENTS = setOf(
            ".env", ".env.local", ".env.production",
            ".git", ".hg", ".svn",
            ".idea",
            "node_modules",
        )
        val ROOT_ONLY_DENYLIST = setOf("build", "target", "out")
    }
}