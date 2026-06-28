package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.CompactionConstants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped cache for detecting duplicate file reads.
 * Only active when settings.detectDuplicateReads is true.
 * Tracks (path, mtime, size) tuples to identify unchanged files.
 *
 * On duplicate read: emits a short placeholder instead of re-emitting full content.
 * On file write/edit: invalidates the cache entry (forces re-read).
 *
 * Thread safety: Uses ConcurrentHashMap with compute() for atomic check-and-record.
 * The isDuplicateRead() + recordRead() two-call pattern is NOT atomic (TOCTOU) —
 * use checkAndRecord() for atomic operation. The TOCTOU window is small and the
 * worst case (serving stale content once) is acceptable for an opt-in cache.
 *
 * Size bound: [CompactionConstants.FILE_READ_CACHE_MAX_ENTRIES] limits cache growth.
 * When exceeded, the oldest entry (by lastReadAtMs) is evicted. Prevents unbounded
 * growth from tools that read many unique temp files or paths with varying normalization.
 */
class FileReadCache {

    private val logger = KotlinLogging.logger {}

    data class FileRecord(
        val path: String,
        val mtimeMs: Long,
        val sizeBytes: Long,
        val readCount: Int = 1,
        val lastReadAtMs: Long = System.currentTimeMillis(),
    )

    private val cache = ConcurrentHashMap<String, FileRecord>()

    /**
     * Atomic check-and-record: returns true if this is a duplicate read
     * (same path, mtime, and size as a previous read), false if it's a new/changed file.
     * Records the read atomically regardless of the result.
     */
    fun checkAndRecord(path: String, mtimeMs: Long, sizeBytes: Long): Boolean {
        var isDuplicate = false
        cache.compute(path) { _, existing ->
            if (existing != null && existing.mtimeMs == mtimeMs && existing.sizeBytes == sizeBytes) {
                isDuplicate = true
                existing.copy(readCount = existing.readCount + 1, lastReadAtMs = System.currentTimeMillis())
            } else {
                FileRecord(path = path, mtimeMs = mtimeMs, sizeBytes = sizeBytes)
            }
        }
        // Evict oldest if over capacity. The size check and eviction are NOT atomic
        // (TOCTOU) — concurrent compute() calls can insert entries between the check
        // and eviction. Worst case: cache grows to size + concurrent_threads, which
        // is bounded and acceptable for an opt-in cache. The alternative (synchronized
        // eviction) would serialize all compute() calls, negating ConcurrentHashMap
        // benefits. We use a tolerance of 2x to avoid evicting on every insert when
        // near capacity under concurrent access.
        if (cache.size > CompactionConstants.FILE_READ_CACHE_MAX_ENTRIES * 2) {
            evictOldest()
        }
        if (isDuplicate) {
            logger.debug { "[ACP] Duplicate read detected: $path (read ${cache[path]?.readCount} times)" }
        }
        return isDuplicate
    }

    /** Non-atomic check — use [checkAndRecord] for atomic operation. */
    fun isDuplicateRead(path: String, mtimeMs: Long, sizeBytes: Long): Boolean {
        val existing = cache[path] ?: return false
        return existing.mtimeMs == mtimeMs && existing.sizeBytes == sizeBytes
    }

    /** Record a read without checking for duplicates. */
    fun recordRead(path: String, mtimeMs: Long, sizeBytes: Long) {
        cache.compute(path) { _, existing ->
            if (existing != null) {
                existing.copy(readCount = existing.readCount + 1, lastReadAtMs = System.currentTimeMillis())
            } else {
                FileRecord(path = path, mtimeMs = mtimeMs, sizeBytes = sizeBytes)
            }
        }
        // Same tolerance-based eviction as checkAndRecord — see that method for TOCTOU analysis.
        if (cache.size > CompactionConstants.FILE_READ_CACHE_MAX_ENTRIES * 2) {
            evictOldest()
        }
    }

    /** Invalidate a cache entry (e.g., after a file write/edit). */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    /** Clear all entries. Called on session switch. */
    fun clear() {
        cache.clear()
    }

    /** Number of cached entries. */
    fun size(): Int = cache.size

    private fun evictOldest() {
        val oldest = cache.entries.minByOrNull { it.value.lastReadAtMs } ?: return
        cache.remove(oldest.key)
        logger.debug { "[ACP] FileReadCache: evicted oldest entry ${oldest.key}" }
    }
}