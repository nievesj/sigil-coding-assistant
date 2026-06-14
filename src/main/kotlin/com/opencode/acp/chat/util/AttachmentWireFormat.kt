package com.opencode.acp.chat.util

/**
 * Returns the MIME type to send on the wire for a file attachment.
 *
 * Normalizes text-based MIME types to `text/plain` so the OpenCode server's
 * `resolvePart` routes them through the Read tool path (`execRead`) instead
 * of the binary catch-all (`fsys.readFile` + base64). Matches the OpenCode
 * desktop TUI's behavior (build-request-parts.ts sends `text/plain` for
 * all file attachments).
 *
 * **Normalized types (→ `text/plain`):**
 *   - All `text` types (e.g. text/x-kotlin, text/html, text/css, text/yaml, text/csv)
 *   - `application/json`, `application/xml`, `application/x-yaml`, `application/yaml`
 *   - `application/javascript`, `application/typescript`, `application/toml`
 *
 * **Pass-through types (unchanged):**
 *   - `image` types, `application/pdf`, `application/octet-stream`
 *   - Any other `application` type not in the normalized set
 *
 * Rationale: The server's `execRead` path treats all input as UTF-8 plain text,
 * which is correct for source code, config files, and structured text formats.
 * Binary formats (images, PDFs) must go through the binary path for base64
 * reconstruction.
 */
internal fun normalizeAttachmentMime(mime: String): String {
    if (mime == "text/plain") return mime
    if (mime.startsWith("text/")) return "text/plain"
    return when (mime) {
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/javascript",
        "application/typescript",
        "application/toml" -> "text/plain"
        else -> mime
    }
}
