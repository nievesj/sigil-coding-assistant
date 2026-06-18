package com.opencode.acp.review

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Shared path-normalization helpers for the review-comment subsystem.
 *
 * Consolidates the 4+ duplicated copies of "normalize basePath to forward
 * slashes, strip prefix/suffix" that previously lived in
 * [ReviewCommentManager], [ReviewCommentFileWatcher], [EditorLifecycleHook],
 * and [ReviewCommentLineMarkerProvider].
 *
 * All methods use `project.basePath` string arithmetic (NOT the deprecated
 * `project.baseDir` / `VfsUtil.getRelativePath`) matching the codebase
 * convention. Returns null when the project has no base path or the file
 * is outside it.
 */
internal object PathUtils {

    /** Normalized project base path with forward slashes, or null. */
    fun baseNorm(project: Project): String? =
        project.basePath?.replace('\\', '/')

    /** Project-relative path for a [VirtualFile], or null if outside base. */
    fun relativePath(project: Project, vf: VirtualFile): String? {
        val base = baseNorm(project) ?: return null
        val fileNorm = vf.path.replace('\\', '/')
        if (!fileNorm.startsWith("$base/")) return null
        return fileNorm.removePrefix("$base/")
    }

    /** Project-relative path from a parent [VirtualFile] + file name. */
    fun relativePath(project: Project, parent: VirtualFile?, name: String): String? {
        val base = baseNorm(project) ?: return null
        val parentPath = parent?.path?.replace('\\', '/') ?: return null
        if (!parentPath.startsWith("$base/")) return null
        val parentRel = parentPath.removePrefix("$base/").trimStart('/')
        return if (parentRel.isEmpty()) name else "$parentRel/$name"
    }

    /** Source path (without `.json`) from a `.review/` JSON [VirtualFile]. */
    fun resolveSourcePath(project: Project, reviewFile: VirtualFile): String? {
        val base = baseNorm(project) ?: return null
        val rel = reviewFile.path.replace('\\', '/')
            .removePrefix("$base/.review/")
        val normalized = rel.removeSuffix(".json")
        // Reject paths with traversal components
        if (normalized.contains("..")) return null
        return normalized
    }

    /** Source path (without `.json`) from a `.review/` JSON [Path]. */
    fun resolveSourcePath(project: Project, jsonPath: Path): String? {
        val base = baseNorm(project) ?: return null
        val jsonStr = jsonPath.toString().replace('\\', '/')
        val prefix = "$base/.review/"
        if (!jsonStr.startsWith(prefix)) return null
        val normalized = jsonStr.removePrefix(prefix).removeSuffix(".json")
        // Reject paths with traversal components
        if (normalized.contains("..")) return null
        return normalized
    }

    /** Prefix string for the `.review/` root directory (with trailing slash). */
    fun reviewRootPrefix(project: Project): String =
        baseNorm(project)?.let { "$it/.review/" } ?: ""

    /** True if the source path has open comments in the given index. */
    fun hasComments(index: ReviewIndex, sourcePath: String): Boolean =
        index.forFile(sourcePath).any { it.status == ReviewStatus.OPEN }
}