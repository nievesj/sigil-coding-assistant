package com.opencode.acp.review

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A single review comment attached to a range of lines in a source file.
 * JSON stores startLine/endLine as 1-based. PSI offsets and LineCommentMap keys
 * are 0-based — conversion is done in [LineCommentMap.build].
 */
@Serializable
data class ReviewComment(
    val id: String,
    val startLine: Int,
    val endLine: Int,
    val comment: String,
    val severity: ReviewSeverity = ReviewSeverity.WARNING,
    val status: ReviewStatus = ReviewStatus.OPEN,
    val author: String = "user",
    val createdAt: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
    val revision: String? = null,
    val revisionLabel: String? = null,
    val resolvedAt: String? = null,
    val resolution: String? = null,
) {
    /** Validate constraints that must hold before write.
     *  Includes format validation for the comment ID to catch malformed entries
     *  from external tools (LLM agents, CI). */
    fun validate(): Boolean =
        id.isNotBlank() && id.matches(Regex("^cmt_[0-9a-fA-F]{12}$")) &&
            startLine >= 1 && endLine >= startLine && endLine <= 10_000_000 &&
            comment.isNotBlank()

    companion object {
        /** Generate a fresh comment ID: `cmt_` + 12 hex chars (matches the TDD schema). */
        fun generateId(): String =
            "cmt_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

@Serializable
enum class ReviewSeverity { INFO, WARNING, ERROR }

@Serializable
enum class ReviewStatus { OPEN, RESOLVED, DELETED }

/**
 * Wrapper for one JSON file — one per reviewed source file.
 *
 * The `etag` field supports optimistic concurrency: a random opaque token
 * regenerated on every plugin write. Writers must read the current etag,
 * modify, then write — if the etag on disk differs from the read value,
 * the write is retried (re-read, re-apply modifier to latest content,
 * re-write up to [ReviewCommentRepository.MAX_UPDATE_RETRIES] attempts).
 *
 * The default is `""` (empty string) so that files written by external
 * tools (LLM agents, CI, manual edits) that omit the `etag` field parse
 * with a stable value instead of a fresh random UUID on every parse —
 * which would cause the retry loop to never converge.
 *
 * `formatVersion` enables future schema evolution. When introducing a new
 * format version, add a [ReviewFileMigrator] and increment the constant.
 */
@Serializable
data class ReviewFile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val etag: String = "",
    val comments: List<ReviewComment> = emptyList(),
) {
    companion object {
        fun generateEtag(): String = java.util.UUID.randomUUID().toString().take(8)
    }
}

/** Current schema version. Increment when making breaking changes. */
const val CURRENT_FORMAT_VERSION = 1

/**
 * Migrates ReviewFile from older format versions to the current one.
 * Registered in a list so multiple migrators can chain (v1→v2, v2→v3, ...).
 */
interface ReviewFileMigrator {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(file: ReviewFile): ReviewFile
}

/**
 * In-memory index: file path → its review comments (loaded from .review/).
 * Built by [ReviewCommentFileWatcher] and [ReviewCommentManager.loadAll].
 *
 * IMMUTABILITY CONTRACT: this data class is strictly immutable. Every
 * mutating operation returns a NEW [ReviewIndex] with a NEW map instance
 * (never mutates the existing map). This is required because
 * [ReviewCommentLineMarkerProvider] reads `commentsByFile` on the EDT
 * while [ReviewCommentFileWatcher] / [ReviewCommentManager.updateFile]
 * swap the index on a background dispatcher. `StateFlow.value` swap is
 * atomic for the reference, but the *contents* of the old map must
 * remain stable for any in-progress EDT iteration — hence copy-on-write.
 */
data class ReviewIndex(
    val commentsByFile: Map<String, List<ReviewComment>> = emptyMap(),
    val totalOpen: Int = 0,
) {
    /** Get all comments for a file. */
    fun forFile(path: String): List<ReviewComment> =
        commentsByFile[path].orEmpty()

    /** Get only OPEN comments for a file. */
    fun openForFile(path: String): List<ReviewComment> =
        forFile(path).filter { it.status == ReviewStatus.OPEN }

    /** Build a NEW index with [sourcePath] set to [file]'s comments
     *  (or removed if file is null/empty). Copy-on-write: allocates a
     *  fresh map so concurrent EDT readers keep seeing the old snapshot. */
    fun withFile(sourcePath: String, file: ReviewFile?): ReviewIndex {
        val newMap = commentsByFile.toMutableMap()  // shallow copy of the map
        if (file == null || file.comments.isEmpty()) {
            newMap.remove(sourcePath)
        } else {
            // Store an immutable List copy so callers can't mutate it.
            newMap[sourcePath] = file.comments.toList()
        }
        return ReviewIndex(
            commentsByFile = newMap,
            totalOpen = newMap.values.flatten().count { it.status == ReviewStatus.OPEN },
        )
    }
}

/**
 * Immutable map: line number (0-based) → list of open comments on that line.
 *
 * JSON stores startLine/endLine as 1-based. This map's keys are 0-based
 * (matching Document.getLineNumber(offset) and PSI offsets used by
 * [ReviewCommentLineMarkerProvider]). UI surfaces that display line numbers
 * to the user (tooltips, the Review tab) MUST convert back to 1-based by
 * adding 1.
 */
class LineCommentMap private constructor(
    private val map: Map<Int, List<ReviewComment>>,
) {
    /** O(1) lookup: comments on a given 0-based line number. */
    fun forLine(line: Int): List<ReviewComment> = map[line] ?: emptyList()

    val isEmpty: Boolean get() = map.isEmpty()

    companion object {
        val EMPTY = LineCommentMap(emptyMap())

        fun build(comments: List<ReviewComment>): LineCommentMap {
            val map = java.util.TreeMap<Int, MutableList<ReviewComment>>()
            for (c in comments) {
                // startLine/endLine are 1-based in JSON; convert to 0-based for PSI offsets
                for (line in (c.startLine - 1) until c.endLine) {
                    map.getOrPut(line) { mutableListOf() }.add(c)
                }
            }
            return LineCommentMap(map.mapValues { it.value.toList() })
        }
    }
}
