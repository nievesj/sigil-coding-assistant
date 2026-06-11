package com.opencode.acp.config.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

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

    /** Platform-specific package names used by opencode's npm publish. */
    private val PLATFORM_PACKAGES = listOf(
        "opencode-windows-x64",
        "opencode-darwin-arm64",
        "opencode-darwin-x64",
        "opencode-linux-x64",
        "opencode-linux-arm64",
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
     * If [dir] looks like an npm global prefix (has `node_modules/`), resolves the
     * real platform-specific binary inside nested `node_modules`.
     *
     * npm global installs on Windows place a cmd shim in `%APPDATA%/npm/`, but the
     * actual native executable lives at:
     * ```
     * <npm-prefix>/node_modules/opencode-ai/node_modules/opencode-<platform>/bin/opencode[.exe]
     * ```
     */
    private fun resolveNpmGlobalBinary(dir: Path): Path? {
        val nodeModules = dir.resolve("node_modules")
        if (!Files.isDirectory(nodeModules)) return null

        val exeNames = listOf("opencode.exe", "opencode")

        // Collect package directories from node_modules (must use toList() — cannot
        // return from inside a Stream.forEach lambda).
        val packageDirs: List<Path> = try {
            Files.list(nodeModules).use { stream ->
                stream.filter { p -> Files.isDirectory(p) }.toList()
            }
        } catch (_: Exception) {
            return null
        }

        // Search order: opencode-ai first (most likely), then all other packages
        val sorted = packageDirs.sortedByDescending { it.fileName.toString() == "opencode-ai" }

        for (pkg in sorted) {
            val innerModules = pkg.resolve("node_modules")
            if (!Files.isDirectory(innerModules)) continue

            for (platformPkg in PLATFORM_PACKAGES) {
                val binDir = innerModules.resolve(platformPkg).resolve("bin")
                if (!Files.isDirectory(binDir)) continue

                for (name in exeNames) {
                    val candidate = binDir.resolve(name)
                    if (Files.isExecutable(candidate)) return candidate
                }
            }
        }

        return null
    }

    /**
     * Returns all executable candidate paths, ordered by likelihood:
     * 1. PATH directories (with npm global resolution)
     * 2. `npm config get prefix` + node_modules lookup
     * 3. Common home locations under `$user.home`
     * 4. Global prefixes (`/usr/local/bin` etc.)
     */
    fun candidatePaths(): List<Path> {
        val candidates = mutableListOf<Path>()

        // 1. PATH directories — check for npm shims and resolve to real binaries
        for (dir in pathDirectories()) {
            val npmBinary = resolveNpmGlobalBinary(dir)
            if (npmBinary != null) {
                candidates.add(npmBinary)
                continue // found the real binary inside node_modules — skip the shim
            }

            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate)) {
                    candidates.add(candidate)
                }
            }
        }

        // 2. Ask npm for its global prefix (handles non-standard installs)
        val npmPrefix = runCatching {
            val pb = ProcessBuilder("npm", "config", "get", "prefix")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (proc.exitValue() == 0 && output.isNotBlank()) Paths.get(output) else null
        }.getOrNull()

        if (npmPrefix != null && Files.isDirectory(npmPrefix)) {
            val npmBinary = resolveNpmGlobalBinary(npmPrefix)
            if (npmBinary != null && npmBinary !in candidates) {
                candidates.add(npmBinary)
            }
        }

        // 3. Common home locations under $user.home
        val userHome = Paths.get(System.getProperty("user.home", ""))
        for (loc in COMMON_HOME_LOCATIONS) {
            val dir = userHome.resolve(loc)
            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate) && candidate !in candidates) {
                    candidates.add(candidate)
                }
            }
        }

        // 4. Global prefixes
        for (prefix in GLOBAL_PREFIXES) {
            val dir = Paths.get(prefix)
            for (name in BINARY_NAMES) {
                val candidate = dir.resolve(name)
                if (Files.isExecutable(candidate) && candidate !in candidates) {
                    candidates.add(candidate)
                }
            }
        }

        return candidates
    }

    /**
     * Discovers the first executable `opencode` binary on the system, or `null` if none found.
     */
    fun discover(): Path? {
        return candidatePaths().firstOrNull()
    }
}
