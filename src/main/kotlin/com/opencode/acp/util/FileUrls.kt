package com.opencode.acp.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

/**
 * Converts an absolute filesystem path to a file:// URL suitable for use as
 * the `url` field of an OpenCodePart.File sent to the OpenCode server.
 *
 * Handles:
 *   - Windows drive: D:\foo\bar.kt  →  file:///D:/foo/bar.kt  (RFC 8089 §2)
 *   - Windows UNC: \\server\share\path  →  file:////server/share/path  (RFC 8089 §2)
 *   - POSIX:    /home/user/foo.kt  →  file:///home/user/foo.kt
 *   - Special chars: percent-encodes spaces, Unicode, and reserved URL characters
 *
 * The drive-letter colon is NOT percent-encoded (RFC 8089 compliance).
 * Path segments are percent-encoded to handle spaces, Unicode filenames, etc.
 *
 * Returns null for blank or relative paths (logs a warning instead of throwing).
 */
fun pathToFileUrl(path: String): String? {
    if (path.isBlank()) {
        logger.warn { "[ACP] pathToFileUrl: path is blank — returning null" }
        return null
    }
    if (!File(path).isAbsolute) {
        logger.warn { "[ACP] pathToFileUrl: path is not absolute: $path — returning null" }
        return null
    }

    // Detect UNC paths (start with \\ on any OS)
    val isUnc = path.startsWith("\\\\")
    if (isUnc) {
        // UNC: \\server\share\path → file:////server/share/path (4 slashes per RFC 8089 §2)
        val normalized = path.replace('\\', '/')
        // Skip the leading two backslashes, then encode each segment
        val withoutPrefix = normalized.removePrefix("//") // leaves "server/share/..."
        val encoded = withoutPrefix.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
        }
        return "file:////$encoded"  // 4 slashes per RFC 8089 §2: file:// + authority /server/share/path
    }

    // POSIX or Windows drive letter
    val normalized = path.replace('\\', '/')
    val hasDriveLetter = normalized.length >= 2 && normalized[1] == ':'

    // Encode each path segment (drive letter colon is NOT encoded per RFC 8089)
    val segments = normalized.split('/')
    val encodedSegments = segments.mapIndexed { index, segment ->
        if (hasDriveLetter && index == 1) {
            segment  // Keep "D:" as-is (don't encode the colon)
        } else {
            URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
        }
    }

    // For POSIX paths, the first segment is empty (from the leading /).
    // Drop it and reconstruct the leading "/" explicitly to avoid 4-slash output.
    val effectiveSegments = if (!hasDriveLetter && encodedSegments.isNotEmpty() && encodedSegments[0].isEmpty()) {
        encodedSegments.drop(1)
    } else {
        encodedSegments
    }
    val encodedPath = effectiveSegments.joinToString("/")

    return "file:///$encodedPath"
}
