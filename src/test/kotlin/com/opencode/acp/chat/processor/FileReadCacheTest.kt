package com.opencode.acp.chat.processor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the real [FileReadCache].
 *
 * Tests the (path, mtime, size) tuple-based duplicate detection, atomic
 * check-and-record, non-atomic isDuplicateRead/recordRead, invalidation,
 * clearing, and size accounting. No fakes — the real class is exercised directly.
 */
class FileReadCacheTest {

    // 1. First read of a file is NOT a duplicate
    @Test
    fun `first read is not a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
    }

    // 2. Second read with same (path, mtime, size) IS a duplicate
    @Test
    fun `second read with same tuple is a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
    }

    // 3. Second read with different mtime is NOT a duplicate
    @Test
    fun `second read with different mtime is not a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 2000L, sizeBytes = 100L) shouldBe false
    }

    // 4. Second read with different size is NOT a duplicate
    @Test
    fun `second read with different size is not a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 200L) shouldBe false
    }

    // 5. invalidate removes the entry → next read is NOT a duplicate
    @Test
    fun `invalidate removes entry so next read is not a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.invalidate("/foo.txt")
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
    }

    // 6. clear removes all entries → next read is NOT a duplicate
    @Test
    fun `clear removes all entries so next read is not a duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.checkAndRecord(path = "/bar.txt", mtimeMs = 2000L, sizeBytes = 200L) shouldBe false
        cache.clear()
        cache.size() shouldBe 0
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
    }

    // 7. size returns the number of cached entries
    @Test
    fun `size returns number of cached entries`() {
        val cache = FileReadCache()
        cache.size() shouldBe 0
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L)
        cache.size() shouldBe 1
        cache.checkAndRecord(path = "/bar.txt", mtimeMs = 2000L, sizeBytes = 200L)
        cache.size() shouldBe 2
        // Duplicate read does NOT add a new entry (same path).
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
        cache.size() shouldBe 2
    }

    // 8. isDuplicateRead (non-atomic) returns false for unknown path
    @Test
    fun `isDuplicateRead returns false for unknown path`() {
        val cache = FileReadCache()
        cache.isDuplicateRead(path = "/unknown.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
    }

    // 9. isDuplicateRead returns true for matching (path, mtime, size)
    @Test
    fun `isDuplicateRead returns true for matching tuple`() {
        val cache = FileReadCache()
        cache.recordRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L)
        cache.isDuplicateRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
    }

    @Test
    fun `isDuplicateRead returns false for mismatched mtime`() {
        val cache = FileReadCache()
        cache.recordRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L)
        cache.isDuplicateRead(path = "/foo.txt", mtimeMs = 2000L, sizeBytes = 100L) shouldBe false
    }

    @Test
    fun `isDuplicateRead returns false for mismatched size`() {
        val cache = FileReadCache()
        cache.recordRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L)
        cache.isDuplicateRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 200L) shouldBe false
    }

    // 10. recordRead records without checking → subsequent isDuplicateRead returns true
    @Test
    fun `recordRead records without checking`() {
        val cache = FileReadCache()
        cache.recordRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L)
        cache.isDuplicateRead(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
        // recordRead on a new path records it.
        cache.recordRead(path = "/bar.txt", mtimeMs = 2000L, sizeBytes = 200L)
        cache.isDuplicateRead(path = "/bar.txt", mtimeMs = 2000L, sizeBytes = 200L) shouldBe true
        cache.size() shouldBe 2
    }

    // 11. checkAndRecord increments readCount on duplicate
    @Test
    fun `checkAndRecord increments readCount on duplicate`() {
        val cache = FileReadCache()
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe false
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
        // After two reads, isDuplicateRead still true (entry exists with matching tuple).
        // The readCount increment is internal; we verify via the duplicate flag staying true.
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 1000L, sizeBytes = 100L) shouldBe true
        // A non-duplicate read (changed mtime) resets the entry (readCount back to 1).
        cache.checkAndRecord(path = "/foo.txt", mtimeMs = 3000L, sizeBytes = 100L) shouldBe false
    }
}