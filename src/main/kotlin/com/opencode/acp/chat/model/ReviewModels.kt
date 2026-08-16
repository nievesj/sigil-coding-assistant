package com.opencode.acp.chat.model

import com.intellij.openapi.vfs.VirtualFile
import com.opencode.acp.review.ReviewComment

/** Display model for a single changed file.
 * Stores filePath (not Change) to avoid stale references after VCS refresh.
 */
data class ChangedFile(
    val filePath: String,           // Relative path from project root (always uses '/')
    val fileName: String,           // File name only (for display)
    val status: FileChangeStatus,   // MODIFIED, ADDED, DELETED, UNTRACKED, CONFLICTED
    val lineDelta: LineDelta,       // Line count info (may be unknown for binaries)
    val virtualFile: VirtualFile?   // Null for deleted files; used to open in editor
)

/** Line delta — distinguishes "zero changes" from "unknown/binary". */
sealed interface LineDelta {
    /** Known line counts computed via LCS diff. */
    data class Known(val additions: Int, val deletions: Int) : LineDelta

    /** Binary file, untracked file, or diff unavailable — display "—" in UI. */
    data object Unknown : LineDelta
}

/** Maps from IntelliJ's FileStatus (15+ values) to our simplified status. */
enum class FileChangeStatus {
    MODIFIED,
    ADDED,
    DELETED,
    UNTRACKED,
    CONFLICTED
}

/**
 * Mapping of file path → number of open review comments.
 * Built from ReviewIndex.totalOpen per file.
 */
data class CommentCounts(
    val countsByFile: Map<String, Int> = emptyMap(),
) {
    /** Open comments count for a given file path, or 0. */
    fun forFile(path: String): Int = countsByFile[path] ?: 0

    val totalOpen: Int get() = countsByFile.values.sum()
}

/** Sealed state for the review panel. */
sealed interface ReviewState {
    data object Loading : ReviewState
    data class Loaded(
        val files: List<ChangedFile>,
        val commentCounts: CommentCounts = CommentCounts(),
        val openCommentsByFile: Map<String, List<ReviewComment>> = emptyMap(),
    ) : ReviewState

    /** Nothing staged in a git repo — prompt user to `git add`. */
    data object NothingStaged : ReviewState

    /** Project is not a git repository, or git4idea is not loaded — feature requires git. */
    data object NotAGitRepository : ReviewState
    data class Error(val message: String, val retryable: Boolean = true) : ReviewState
}

/** Result of enumerating git-staged files. Sealed so callers distinguish
 *  the "empty", "non-git", and "error" cases from "files present" without sentinels. */
sealed interface StagedFilesResult {
    /** Staged files exist. [files] is non-empty. */
    data class Staged(val files: List<ChangedFile>) : StagedFilesResult

    /** Git repo(s) found, but nothing is staged (no `git add` yet). */
    data object NothingStaged : StagedFilesResult

    /** No git repository in the project, or git4idea plugin not loaded. */
    data object NoGitRepository : StagedFilesResult

    /** Git command failed for all repositories (e.g., corrupt repo, missing git binary,
     *  permission error). [message] contains the joined error output for diagnostics.
     *  Distinct from [NothingStaged] so callers can show an error, not a misleading
     *  "stage your files" prompt. */
    data class Error(val message: String) : StagedFilesResult
}