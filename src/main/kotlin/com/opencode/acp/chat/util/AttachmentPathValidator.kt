package com.opencode.acp.chat.util

import java.io.File

/**
 * Consolidated CWE-22 path traversal guard for file attachments.
 *
 * Security-critical: ALL path validation for file attachments goes through
 * this object. Fail-closed: any path that cannot be canonicalized is rejected.
 *
 * Relationship to [com.opencode.acp.chat.service.AttachmentValidator]:
 * AttachmentValidator handles send-time validation (before sending to server)
 * and delegates canonicalization to this object. This object handles attach-time
 * validation (when the user drops/selects a file).
 */
object AttachmentPathValidator {

    /**
     * Returns true if [canonicalPath] is inside the project base directory,
     * the project's .opencode/attachments/ directory, or the user's
     * ~/.opencode/attachments/ directory.
     *
     * @param canonicalPath The canonical (resolved, absolute) path of the file to check.
     * @param projectBase The canonical project base path (nullable for safety). Trailing separator is trimmed.
     * @param userHome The canonical user home path (nullable for safety). Trailing separator is trimmed.
     * @return true if the path is inside an allowed directory, false otherwise.
     */
    fun isAllowed(
        canonicalPath: String,
        projectBase: String?,
        userHome: String?,
    ): Boolean {
        return isInsideProject(canonicalPath, projectBase) ||
            isInsideAttachments(canonicalPath, projectBase, userHome)
    }

    /**
     * Canonicalizes [path], returning null on failure (fail-closed).
     * A path that cannot be canonicalized is untrusted (broken symlink,
     * restricted path, etc.) and must be rejected.
     *
     * @return The canonical path, or null if canonicalization fails.
     */
    fun canonicalizeOrReject(path: String): String? {
        return try {
            File(path).canonicalPath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns true if [canonicalPath] is inside the project base directory.
     * Unlike [isAllowed], this does NOT check the attachments directories.
     */
    fun isInsideProject(canonicalPath: String, projectBase: String?): Boolean {
        if (projectBase == null) return false
        val base = projectBase.trimEnd(File.separatorChar)
        return canonicalPath == base || canonicalPath.startsWith(base + File.separator)
    }

    /**
     * Returns true if [canonicalPath] is inside the project's or user's
     * .opencode/attachments/ directory.
     */
    fun isInsideAttachments(canonicalPath: String, projectBase: String?, userHome: String?): Boolean {
        val projectAttachments = projectBase?.trimEnd(File.separatorChar)?.let {
            it + File.separator + ".opencode" + File.separator + "attachments" + File.separator
        }
        val userAttachments = userHome?.trimEnd(File.separatorChar)?.let {
            it + File.separator + ".opencode" + File.separator + "attachments" + File.separator
        }
        return (projectAttachments != null && canonicalPath.startsWith(projectAttachments)) ||
            (userAttachments != null && canonicalPath.startsWith(userAttachments))
    }
}