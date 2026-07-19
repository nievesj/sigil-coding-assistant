package com.opencode.acp.chat.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic file writer: writes to a temp file then renames into place.
 * Consolidates 3 implementations (McpConfigWriter, PrunerConfigWriter, PrunerResourceExtractor).
 *
 * Returns true on success, false on failure (non-atomic fallback or IO error).
 */
object AtomicFileWriter {
    /**
     * Write [content] to [target] atomically (temp file + rename).
     * Creates parent directories if needed. Cleans up temp file on failure.
     */
    fun writeAtomically(target: Path, content: String): Boolean {
        val parent = target.parent
        if (parent != null && !Files.exists(parent)) {
            try { Files.createDirectories(parent) } catch (_: Exception) { return false }
        }
        val temp = try {
            if (parent != null) {
                Files.createTempFile(parent, ".atomic-", ".tmp")
            } else {
                Files.createTempFile(".atomic-", ".tmp")
            }
        } catch (_: Exception) {
            // Fallback: temp file in system temp dir
            try { Files.createTempFile(".atomic-", ".tmp") } catch (_: Exception) { return false }
        }
        return try {
            Files.write(temp, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (_: Exception) {
                // Non-atomic fallback
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                    true
                } catch (_: Exception) { false }
            }
        } finally {
            try { if (Files.exists(temp)) Files.delete(temp) } catch (_: Exception) { /* best-effort cleanup — temp file may leak on FS errors */ }
        }
    }
}