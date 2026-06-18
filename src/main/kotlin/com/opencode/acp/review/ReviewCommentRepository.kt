package com.opencode.acp.review

import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Low-level file I/O for `.review/` files.
 *
 * ## Concurrency model
 *
 * Read-modify-write uses **etag-based optimistic concurrency PLUS a per-path
 * [Mutex]** to close the TOCTOU window between "etag matches" and "rename
 * `.tmp` → final":
 * - The **etag** field detects a *prior* concurrent write (read-etag phase).
 * - The per-path **Mutex** serializes the read-modify-write so that two
 *   plugin-internal `updateFile()` calls for the same source path cannot
 *   interleave their `.tmp`-write and rename steps. Without it, caller A
 *   could pass its etag check, then caller B writes+renames (new etag), then
 *   caller A renames its already-written `.tmp` over B's file — silent data
 *   loss.
 * - The Mutex does NOT protect against external writers (other IDE
 *   instances, the LLM agent editing `.json` directly, CI). External writers
 *   that bypass the etag protocol accept last-writer-wins semantics
 *   (documented in the TDD assumptions).
 * - The plugin's own writes are fully serialized per path.
 *
 * ## Self-write suppression
 *
 * After a successful write+rename, [writeFile] invokes [onWrote] (a callback
 * supplied by the manager) BEFORE the rename's VFS event can be processed by
 * [ReviewCommentFileWatcher]. This records the path in the manager's
 * `recentSelfWrites` set so the AsyncFileListener ignores the resulting VFS
 * event, closing the write→VFS-event→re-read feedback loop. See TDD §4.7.2-G
 * — `markSelfWrite` MUST be called before the rename, not after `updateFile`
 * returns, otherwise the VFS pipeline can race ahead and trigger a redundant
 * re-read.
 *
 * ## Null safety
 *
 * `project.basePath` is nullable (default project, remote dev, lightweight
 * test projects). All operations short-circuit with null/no-op when basePath
 * is absent — no `!!` assertions.
 */
class ReviewCommentRepository(
    project: Project,
    /** Injected parser — single shared instance, never recreated per-call. */
    private val parser: ReviewCommentParser = ReviewCommentParser(),
    /** Invoked by [writeFile] AFTER a successful rename, to suppress the
     *  resulting VFS event in the file watcher. May be a no-op. */
    private val onWrote: (sourcePath: String) -> Unit = {},
) {

    private val logger = KotlinLogging.logger {}

    /** Base path: `<project-root>/.review/` — null when project has no base path. */
    private val reviewRoot: Path? = project.basePath?.let { Path.of(it, ".review") }

    /** Per-source-path Mutex serializes read-modify-write for plugin-internal writes.
     *
     *  IMPORTANT: the Mutex MUST be cached per path — `lockFor(path)` must return the
     *  SAME [Mutex] instance for every call with the same `path`, otherwise two
     *  concurrent `updateFile(path)` calls acquire DIFFERENT mutexes and run
     *  concurrently, reopening the etag TOCTOU window (caller A passes etag check,
     *  caller B writes+renames, caller A renames its stale `.tmp` over B's file —
     *  silent data loss). Creating a fresh `Mutex()` per call is wrong.
     *
     *  Growth is bounded by the number of distinct `.review/` source paths, which is
     *  itself bounded by the number of reviewed source files in the project. A `Mutex`
     *  is ~40 bytes, so even 10 000 entries is <0.5 MB — negligible for a project-scoped
     *  service that lives only as long as the project. The manager prunes entries whose
     *  `.review/` file no longer exists via [pruneLocks] (called from [loadAll]). */
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sourcePath: String): Mutex =
        fileLocks.computeIfAbsent(sourcePath) { Mutex() }

    /** Remove lock entries for source paths whose `.review/` JSON file no longer
     *  exists on disk. Called by [ReviewCommentManager.loadAll] after the index is
     *  rebuilt so the map doesn't accumulate entries for files whose comments were
     *  deleted/resolved-away. Paths that still have a JSON file keep their lock to
     *  preserve in-flight serialization. */
    fun pruneLocks(existingSourcePaths: Set<String>) {
        fileLocks.keys.retainAll(existingSourcePaths)
    }

    /** Map source path → `.review/` JSON file path. Returns null if the
     *  project has no base path (no `.review/` directory is usable). */
    fun jsonPathFor(sourcePath: String): Path? {
        val root = reviewRoot ?: return null
        val p = root.resolve(sourcePath).normalize()
        // CWE-22: reject paths that escape the .review/ root.
        if (!p.startsWith(root)) return null
        return p.parent.resolve("${p.fileName}.json")
    }

    /** Read a single JSON file. Returns null if the file doesn't exist, the
     *  project has no base path, or the file is unparseable. On parse
     *  failure the broken file is moved aside so it doesn't block re-reads. */
    suspend fun readFile(sourcePath: String): ReviewFile? = withContext(Dispatchers.IO) {
        val jsonPath = jsonPathFor(sourcePath) ?: return@withContext null
        if (!jsonPath.exists()) return@withContext null
        try {
            val content = jsonPath.readText()
            parser.parseReviewFile(content)
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to read .review file for $sourcePath" }
            // Move broken file aside so it doesn't block re-reads.
            try {
                val brokenDir = jsonPath.parent.resolve("broken")
                brokenDir.createDirectories()
                jsonPath.moveTo(brokenDir.resolve(jsonPath.name), overwrite = true)
            } catch (moveEx: Exception) {
                logger.warn(moveEx) { "[ACP] Failed to move broken .review file aside for $sourcePath — file will be retried on next read" }
            }
            null
        }
    }

    /** Write with atomic rename: write to `.tmp`, then rename.
     *
     *  Checks that the on-disk etag matches [expectedEtag] before writing.
     *  Returns true on success, false if etag mismatch (caller retries).
     *
     *  BEFORE the rename completes, [onWrote] is invoked so the file watcher
     *  can suppress the resulting VFS event (C2: self-write feedback-loop
     *  guard). The `.tmp` suffix is always `.json.tmp` so the startup cleanup
     *  glob `.review/**/*.json.tmp` catches every orphan. */
    suspend fun writeFile(
        sourcePath: String,
        file: ReviewFile,
        expectedEtag: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val jsonPath = jsonPathFor(sourcePath) ?: return@withContext false

        // Verify etag still matches what we read earlier.
        if (jsonPath.exists()) {
            try {
                val current = jsonPath.readText()
                val currentFile = parser.parseReviewFile(current)
                if (currentFile != null && currentFile.etag != expectedEtag) {
                    return@withContext false  // etag mismatch — caller retries
                }
            } catch (e: Exception) {
                // File unparseable — back up broken file, then proceed with write
                logger.warn(e) { "[ACP] Backing up unparseable .review file before overwrite" }
                try {
                    val brokenDir = jsonPath.parent.resolve("broken")
                    brokenDir.createDirectories()
                    jsonPath.moveTo(brokenDir.resolve(jsonPath.name), overwrite = true)
                } catch (_: Exception) { /* best-effort backup */ }
            }
        }

        try {
            jsonPath.parent?.createDirectories()
            val tmpPath = jsonPath.parent.resolve("${jsonPath.name}.tmp")
            tmpPath.writeText(parser.serializeReviewFile(file))
            tmpPath.moveTo(jsonPath, overwrite = true)
            // C2: mark self-write AFTER the rename succeeds. This prevents
            // a failed write from suppressing an external VFS event for 2s.
            // The VFS event fires asynchronously after the rename, so marking
            // immediately after the synchronous moveTo is early enough.
            onWrote(sourcePath)
            true
        } catch (e: Exception) {
            logger.error(e) { "[ACP] Failed to write .review file for $sourcePath" }
            false
        }
    }

    /** Read-modify-write with etag retry loop AND per-path Mutex.
     *
     *  [modifier] receives the current [ReviewFile] (or null if absent) and
     *  returns the modified file (or null to skip the write). The modifier
     *  MUST NOT set `etag` on the returned file — [updateFile] stamps the new
     *  etag here so callers can't double-generate or omit it.
     *
     *  Retries up to [MAX_UPDATE_RETRIES] times on etag mismatch, then throws
     *  [ConcurrentModificationException]. Returns the resulting [ReviewFile]
     *  written to disk (or null if the modifier returned null and skipped
     *  the write). */
    suspend fun updateFile(
        sourcePath: String,
        modifier: suspend (ReviewFile?) -> ReviewFile?,
    ): ReviewFile? = lockFor(sourcePath).withLock {
        var lastError: Throwable? = null

        for (attempt in 1..MAX_UPDATE_RETRIES) {
            val existing = readFile(sourcePath)
            val currentEtag = existing?.etag ?: ""
            val modified = modifier(existing) ?: return@withLock null  // skip write

            // Stamp a fresh etag here (single source of truth — callers don't).
            val newFile = modified.copy(etag = ReviewFile.generateEtag())

            if (writeFile(sourcePath, newFile, currentEtag)) {
                return@withLock newFile
            }

            // Etag mismatch — retry after backoff
            lastError = ConcurrentModificationException(
                "Etag mismatch for $sourcePath after write attempt $attempt"
            )
            delay(RETRY_DELAY_MS * attempt)
        }

        throw lastError ?: ConcurrentModificationException("Failed to update $sourcePath")
    }

    /** List all `.json` files under `.review/`. Returns empty if no base path. */
    suspend fun listAllFiles(): List<Path> = withContext(Dispatchers.IO) {
        val root = reviewRoot ?: return@withContext emptyList()
        if (!root.exists() || !root.isDirectory()) return@withContext emptyList()
        try {
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { it.name.endsWith(".json") && !it.name.endsWith(".json.tmp") }
                    .toList()
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to list .review files" }
            emptyList()
        }
    }

    /** Move a `.review/` JSON file when the source file is renamed/moved.
     *  No-op if the old `.review/` file does not exist.
     *  Uses per-path Mutex to prevent concurrent moves from racing on the
     *  same source file, and marks the new path as a self-write to suppress
     *  the resulting VFS event. */
    suspend fun moveFile(oldSourcePath: String, newSourcePath: String) {
        val oldJson = jsonPathFor(oldSourcePath) ?: return
        val newJson = jsonPathFor(newSourcePath) ?: return
        if (!oldJson.exists()) return
        // Acquire both locks in consistent order (sorted lexicographically) to prevent deadlock
        val first: String
        val second: String
        if (oldSourcePath <= newSourcePath) {
            first = oldSourcePath; second = newSourcePath
        } else {
            first = newSourcePath; second = oldSourcePath
        }
        lockFor(first).withLock {
            lockFor(second).withLock {
                withContext(Dispatchers.IO) {
                    try {
                        newJson.parent?.createDirectories()
                        onWrote(newSourcePath)  // suppress VFS event for the new path
                        oldJson.moveTo(newJson, overwrite = true)
                        logger.info { "[ACP] Moved .review file: $oldSourcePath → $newSourcePath" }
                    } catch (e: Exception) {
                        logger.warn(e) { "[ACP] Failed to move .review file: $oldSourcePath → $newSourcePath" }
                    }
                }
            }
        }
    }

    /** Delete the `.review/` JSON file for a source path (used when the
     *  source file is deleted — prevents orphaned comment files). */
    suspend fun deleteReviewFile(sourcePath: String) {
        val jsonPath = jsonPathFor(sourcePath) ?: return
        lockFor(sourcePath).withLock {
            withContext(Dispatchers.IO) {
                try {
                    jsonPath.deleteIfExists()
                    logger.info { "[ACP] Deleted .review file for deleted source: $sourcePath" }
                } catch (e: Exception) {
                    logger.warn(e) { "[ACP] Failed to delete .review file for $sourcePath" }
                }
            }
        }
    }

    /** Clean up orphan `.json.tmp` files on startup (from crashed writes). */
    suspend fun cleanupOrphanTempFiles() = withContext(Dispatchers.IO) {
        val root = reviewRoot ?: return@withContext
        if (!root.exists() || !root.isDirectory()) return@withContext
        try {
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { it.name.endsWith(".json.tmp") }
                    .forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
            }
        } catch (_: Exception) { }
    }

    companion object {
        const val MAX_UPDATE_RETRIES = 3
        const val RETRY_DELAY_MS = 100L
    }
}