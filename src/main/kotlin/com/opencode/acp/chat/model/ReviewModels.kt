package com.opencode.acp.chat.model

import com.intellij.openapi.vfs.VirtualFile

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

/** Sealed state for the review panel. */
sealed interface ReviewState {
    data object Loading : ReviewState
    data class Loaded(val files: List<ChangedFile>) : ReviewState
    data object Empty : ReviewState           // Git repo exists but no changes
    data object NoGitRepository : ReviewState
    data class Error(val message: String, val retryable: Boolean = true) : ReviewState
}