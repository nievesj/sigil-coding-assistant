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
     *  failure the broken file is moved aside so it doesn't block re-reads.
     *
     *  BOM handling: Files written by external tools (LLM agents, CI) may have
     *  a UTF-8 BOM (0xEF 0xBB 0xBF) at the start. [readText] includes the BOM
     *  in the returned string as '\uFEFF', which causes kotlinx.serialization's
     *  JSON parser to fail ("Expected start of the object '{', but had '?'").
     *  Strip the BOM before parsing. */
    suspend fun readFile(sourcePath: String): ReviewFile? = withContext(Dispatchers.IO) {
        val jsonPath = jsonPathFor(sourcePath) ?: return@withContext null
        if (!jsonPath.exists()) return@withContext null
        try {
            val content = jsonPath.readText().removePrefix("\uFEFF")
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
     *  glob `.review/**/*.json.tmp` catches every orphan.
     *
     *  IMPORTANT: This method MUST only be called from within [updateFile]'s
     *  per-path Mutex (via [lockFor]). The etag check is NOT atomic with the
     *  write — two concurrent writeFile() calls for the same path would both
     *  pass their etag checks and race on the rename. The Mutex serializes
     *  the read-modify-write cycle. */
    suspend fun writeFile(
        sourcePath: String,
        file: ReviewFile,
        expectedEtag: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val jsonPath = jsonPathFor(sourcePath) ?: return@withContext false

        // Verify etag still matches what we read earlier.
        if (jsonPath.exists()) {
            try {
                val current = jsonPath.readText().removePrefix("\uFEFF")
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
     *  The retry loop IS the merge strategy: on etag mismatch (external write
     *  happened between our read and write), we re-read the latest file from
     *  disk and re-apply the modifier to it. This preserves external changes
     *  (e.g., new comments added by an LLM agent) while still applying the
     *  plugin's change (e.g., resolving a comment). The modifier must be
     *  idempotent and find its target by comment ID, not by position.
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

            // Etag mismatch — external write happened between our read and write.
            // Retry: re-read the latest file (which now has the external changes),
            // re-apply the modifier to it (merge), and write again.
            logger.info { "[ACP] Etag mismatch for $sourcePath (attempt $attempt/$MAX_UPDATE_RETRIES) — re-reading latest and re-applying change (merge)" }
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
        // No-op if old and new paths are the same — prevents acquiring the same
        // Mutex twice (kotlinx.coroutines.sync.Mutex is NOT reentrant → deadlock).
        if (oldSourcePath == newSourcePath) return
        // Acquire both locks in consistent order (sorted lexicographically) to prevent deadlock.
        // Uses String's natural ordering (Unicode codepoint comparison), which is deterministic
        // and locale-independent for ASCII file paths. If non-ASCII paths are introduced,
        // consider using a Comparator.constant to make the invariant explicit.
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
                            oldJson.moveTo(newJson, overwrite = true)
                            // C2: mark self-write AFTER the rename succeeds. This prevents
                            // a failed move from suppressing an external VFS event for 2s.
                            onWrote(newSourcePath)
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
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to clean up orphan .json.tmp files" }
        }
    }

    /**
     * Detect and strip UTF-8 BOM (0xEF 0xBB 0xBF) from `.review/` JSON files.
     *
     * External writers (LLM agents, CI, text editors) may emit files with a
     * UTF-8 BOM. While [readFile] strips the BOM at parse time for resilience,
     * leaving BOM-prefixed files on disk causes:
     * - Other consumers (git diffs, shell tools, external parsers) to see the BOM
     * - The "broken file" move-aside path in [readFile] to trigger on every load
     *   attempt (the file is never actually moved because the move fails, but the
     *   parse warning fires every time — log noise)
     * - Potential issues if a future code path reads raw bytes without BOM stripping
     *
     * This scan rewrites BOM-prefixed files in place (atomic temp-file rename,
     * same pattern as [writeFile]) so the on-disk files are clean UTF-8 without BOM.
     * Called once on startup from [ReviewCommentManager.init], alongside
     * [cleanupOrphanTempFiles].
     *
     * Returns the number of files fixed.
     */
    suspend fun stripBomFromReviewFiles(): Int = withContext(Dispatchers.IO) {
        val root = reviewRoot ?: return@withContext 0
        if (!root.exists() || !root.isDirectory()) return@withContext 0
        var fixed = 0
        try {
            // Collect BOM-prefixed files first, then process them with proper
            // suspend context (forEach inside Files.walk is NOT a coroutine body,
            // so Mutex.withLock cannot be called there).
            val bomFiles = mutableListOf<Pair<java.nio.file.Path, ByteArray>>()
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { it.name.endsWith(".json") && !it.name.endsWith(".json.tmp") }
                    .forEach { path ->
                        try {
                            val bytes = java.nio.file.Files.readAllBytes(path)
                            // UTF-8 BOM: 0xEF 0xBB 0xBF
                            if (bytes.size >= 3 &&
                                bytes[0] == 0xEF.toByte() &&
                                bytes[1] == 0xBB.toByte() &&
                                bytes[2] == 0xBF.toByte()
                            ) {
                                bomFiles.add(path to bytes)
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "[ACP] Failed to read ${path.fileName} for BOM check" }
                        }
                    }
            }
            // Process BOM files with per-path Mutex serialization (suspend-safe).
            for ((path, bytes) in bomFiles) {
                // Compute source path for per-path Mutex serialization.
                // This prevents racing with concurrent updateFile() calls
                // for the same source path (the class's concurrency contract
                // requires all plugin writes to be serialized per path).
                val relative = root.relativize(path).toString().replace('\\', '/')
                val sourcePath = relative.substringBeforeLast(".json")
                lockFor(sourcePath).withLock {
                    // Strip BOM: write the remaining bytes via temp-file rename (atomic)
                    val stripped = bytes.copyOfRange(3, bytes.size)
                    val tmpPath = path.resolveSibling("${path.fileName}.nobom.tmp")
                    java.nio.file.Files.write(tmpPath, stripped)
                    tmpPath.moveTo(path, overwrite = true)
                    fixed++
                    logger.info { "[ACP] Stripped UTF-8 BOM from ${path.fileName} (${bytes.size} → ${stripped.size} bytes)" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] Failed to scan .review files for BOM" }
        }
        if (fixed > 0) {
            logger.info { "[ACP] Stripped BOM from $fixed .review/ file(s)" }
        }
        fixed
    }

    companion object {
        const val MAX_UPDATE_RETRIES = 5
        const val RETRY_DELAY_MS = 100L
    }
}