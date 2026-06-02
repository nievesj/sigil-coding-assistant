package com.opencode.acp.config.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stateless utility that discovers the `opencode` binary on the system.
 */
object BinaryDiscovery {

    private val BINARY_NAMES = listOf("opencode", "opencode.exe")

    private val COMMON_HOME_LOCATIONS = listOf(
        ".opencode/bin",
        "go/bin",
        ".local/bin",
        "AppData/Local/opencode",
        "AppData/Local/Programs/opencode",
    )

    private val GLOBAL_PREFIXES = listOf(
        "/usr/local/bin",
        "/opt/homebrew/bin",
        "/opt/bin",
    )

    /**
     * Splits the system `PATH` environment variable into existing directories.
     */
    private fun pathDirectories(): List<Path> {
        val pathEnv = System.getenv("PATH") ?: return emptyList()
        val separator = System.getProperty("path.separator") ?: ":"
        return pathEnv.split(separator)
            .map { Paths.get(it) }
            .filter { Files.isDirectory(it) }
    }

    /**
     * Returns all executable candidate paths, ordered by likelihood:
     * 1. PATH directories
     * 2. Common home locations under `$user.home`
     * 3. Global prefixes (`/usr/local/bin` etc.)
     *
     * Only directories that exist are traversed.
     */
    fun candidatePaths(): List<Path> {
        val candidates = mutableListOf<Path>()

        // 1. PATH directories (most likely)
        for (dir in pathDirectories()) {
            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate)) {
                    candidates.add(candidate)
                }
            }
        }

        // 2. Common home locations under $user.home
        val userHome = Paths.get(System.getProperty("user.home", ""))
        for (loc in COMMON_HOME_LOCATIONS) {
            val dir = userHome.resolve(loc)
            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate)) {
                    candidates.add(candidate)
                }
            }
        }

        // 3. Global prefixes
        for (prefix in GLOBAL_PREFIXES) {
            val dir = Paths.get(prefix)
            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate)) {
                    candidates.add(candidate)
                }
            }
        }

        return candidates.distinct()
    }

    /**
     * Discovers the first executable `opencode` binary on the system, or `null` if none found.
     */
    fun discover(): Path? {
        return candidatePaths().firstOrNull()
    }
}
