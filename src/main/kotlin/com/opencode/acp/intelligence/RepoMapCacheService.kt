package com.opencode.acp.intelligence

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.opencode.acp.intelligence.model.RepoMapEntry
import kotlinx.coroutines.sync.Mutex

/**
 * Project-scoped soft cache for `psi_repo_map` results.
 *
 * Per TDD §4.7.7 (Concurrency Model) and §4.7.8 (Project Disposal Lifecycle):
 *  - `@Volatile var cache` — readers read without locking.
 *  - `rebuildMutex` — only one rebuild at a time (cache stampede prevention).
 *  - `@Volatile var rebuilding` — flag for concurrent callers to skip.
 *  - Project-scoped service so the cache is keyed by [Project] (toolset
 *    instances are application-scoped singletons and must not hold project
 *    state).
 *
 * On cache miss: if `rebuildMutex.tryLock()` succeeds, the caller launches a
 * rebuild in the background (swapping `cache` when done, then unlocking). If
 * `tryLock()` fails, a rebuild is in progress — return stale `cache` if
 * available, or signal "cache warming, retry in 10s" if cold start.
 *
 * Readers NEVER block on the Mutex — they read the `@Volatile` field directly.
 */
@Service(Service.Level.PROJECT)
class RepoMapCacheService(private val project: Project) {

    /**
     * Cached repo map entries with the timestamp of the last successful rebuild.
     * Null when no cache has been built yet (cold start).
     *
     * **Immutability requirement:** [entries] MUST be an immutable list. The cache
     * field is `@Volatile` and read without locking; a mutable list populated
     * concurrently would expose a data race. Callers (e.g. `computeRepoMap`)
     * must ensure they pass an immutable list (e.g. from `List.take()` or
     * `List.sortedByDescending()` which return immutable lists).
     */
    data class RepoMapCache(val entries: List<RepoMapEntry>, val timestamp: Long)

    @Volatile
    var cache: RepoMapCache? = null
        set(value) {
            // Defensive copy to enforce immutability invariant — the @Volatile read
            // pattern requires an immutable list. toList() is a no-op for already-immutable
            // lists but creates a copy for mutable lists.
            field = value?.copy(entries = value.entries.toList())
        }

    /** Coordinates cache rebuilds — only one rebuild at a time. */
    val rebuildMutex = Mutex()

    /**
     * Flag indicating a rebuild is in progress (for concurrent callers).
     *
     * Advisory only — the [rebuildMutex] provides the real mutual exclusion.
     * This flag can be observably `false` briefly while a rebuild is in progress
     * (e.g., when set by [com.opencode.acp.intelligence.PsiQueryHelper.warmRepoMap]'s
     * finally block while [rebuildRepoMap] still holds the mutex). Callers should
     * not rely solely on this flag for correctness — always acquire [rebuildMutex]
     * before starting a rebuild.
     */
    @Volatile
    var rebuilding: Boolean = false

    /**
     * Returns true if the cache exists and has not exceeded the TTL
     * ([REPO_MAP_CACHE_TTL_MS], 5 minutes).
     */
    fun isCacheFresh(): Boolean = isCacheFresh(cache)

    companion object {
        fun getInstance(project: Project): RepoMapCacheService =
            project.getService(RepoMapCacheService::class.java)

        /**
         * Pure function: check if a cache entry is fresh given the current time and TTL.
         * Extracted from [isCacheFresh] for unit testing without a Project instance.
         */
        fun isCacheFresh(cache: RepoMapCache?, now: Long = System.currentTimeMillis()): Boolean {
            val c = cache ?: return false
            return now - c.timestamp < REPO_MAP_CACHE_TTL_MS
        }
    }
}