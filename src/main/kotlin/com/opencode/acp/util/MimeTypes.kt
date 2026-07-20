package com.opencode.acp.util

import java.net.URLConnection

/**
 * Comprehensive MIME type detection for file attachments.
 *
 * [URLConnection.guessContentTypeFromName] only knows ~20 common extensions
 * and returns null for most source code / config files, causing a fallback to
 * `application/octet-stream` which the OpenCode server rejects. This utility
 * provides a much broader mapping covering common development file types.
 */
object MimeTypes {

    /**
     * Maximum file size for [guessFromFile]'s content-based detection
     * ([java.nio.file.Files.probeContentType]). Files larger than this skip the
     * probe and fall back to `application/octet-stream` to avoid blocking the
     * calling thread on large file I/O.
     */
    private const val MAX_PROBE_SIZE_BYTES = 10L * 1024 * 1024 // 10 MB

    /**
     * MIME types keyed by the FULL lowercased file name (no extension stripping).
     *
     * Used for files that have no extension or whose "extension" (the substring
     * after the last dot) does not match the intended key. Examples:
     * - `Makefile`, `Dockerfile` — no dot, so `substringAfterLast('.')` returns `""`
     * - `.env.local`, `.env.development`, `.env.production` — the last dot is inside
     *   the name, so the extracted "extension" is `local`/`development`/`production`,
     *   not `env.local`
     *
     * Checked BEFORE the extension map in both [guessFromFileName] and [guessFromFile].
     * Keys are lowercase; the lookup lowercases the input name.
     */
    private val fullNameMap: Map<String, String> = buildMap {
        // Extensionless build/config files
        put("makefile", "text/x-makefile")
        put("dockerfile", "text/x-dockerfile")
        // Multi-dot dotfiles (the last dot is inside the name, not a separator)
        put(".env.local", "text/plain")
        put(".env.development", "text/plain")
        put(".env.production", "text/plain")
    }

    private val extensionMap: Map<String, String> = buildMap {
        // Source code
        put("kt", "text/x-kotlin")
        put("kts", "text/x-kotlin")
        put("java", "text/x-java")
        put("scala", "text/x-scala")
        put("groovy", "text/x-groovy")
        put("py", "text/x-python")
        put("rb", "text/x-ruby")
        put("rs", "text/x-rust")
        put("go", "text/x-go")
        put("c", "text/x-c")
        put("cpp", "text/x-c++")
        put("cc", "text/x-c++")
        put("h", "text/x-c")
        put("hpp", "text/x-c++")
        put("cs", "text/x-csharp")
        put("php", "text/x-php")
        put("swift", "text/x-swift")
        put("m", "text/x-objective-c")
        put("mm", "text/x-objective-c++")

        // Web
        put("js", "text/javascript")
        put("jsx", "text/javascript")
        put("ts", "text/typescript")
        put("tsx", "text/typescript")
        put("vue", "text/x-vue")
        put("svelte", "text/x-svelte")
        put("html", "text/html")
        put("htm", "text/html")
        put("css", "text/css")
        put("scss", "text/x-scss")
        put("less", "text/x-less")
        put("sass", "text/x-sass")

        // Data / config
        put("json", "application/json")
        put("yaml", "text/yaml")
        put("yml", "text/yaml")
        put("toml", "text/toml")
        put("xml", "application/xml")
        put("csv", "text/csv")
        put("tsv", "text/tab-separated-values")

        // Unity / game dev (YAML-based text formats)
        put("prefab", "text/yaml")
        put("asset", "text/yaml")
        put("meta", "text/yaml")
        put("unity", "text/yaml")
        put("mat", "text/yaml")
        put("controller", "text/yaml")
        put("anim", "text/yaml")
        put("overrideController", "text/yaml")
        put("physicMaterial", "text/yaml")
        put("physicsMaterial2D", "text/yaml")
        put("inputSystem", "text/yaml")
        put("gradient", "text/yaml")
        put("preset", "text/yaml")
        put("asmdef", "text/json")
        put("asmref", "text/json")
        put("cginc", "text/x-c")
        put("shader", "text/x-c")
        put("compute", "text/x-c")
        put("hlsl", "text/x-c")
        put("glsl", "text/x-c")
        put("shadergraph", "text/json")
        put("visualeffectgraph", "text/json")

        // Additional cross-platform / game dev
        put("dart", "text/x-dart")
        put("lua", "text/x-lua")
        put("r", "text/x-r")

        // Shell / scripts
        put("sh", "text/x-shellscript")
        put("bash", "text/x-shellscript")
        put("zsh", "text/x-shellscript")
        put("bat", "text/x-bat")
        put("cmd", "text/x-bat")
        put("ps1", "text/x-powershell")

        // Build / project files
        put("gradle", "text/x-gradle")
        put("properties", "text/x-properties")
        put("ini", "text/x-ini")
        put("cfg", "text/x-config")
        put("conf", "text/x-config")

        // Documentation
        put("md", "text/markdown")
        put("mdx", "text/markdown")
        put("txt", "text/plain")
        put("rst", "text/x-rst")
        put("adoc", "text/x-asciidoc")

        // Images (handled specially but included for completeness)
        put("png", "image/png")
        put("jpg", "image/jpeg")
        put("jpeg", "image/jpeg")
        put("gif", "image/gif")
        put("bmp", "image/bmp")
        put("svg", "image/svg+xml")
        put("webp", "image/webp")
        put("ico", "image/x-icon")
        put("tiff", "image/tiff")
        put("tif", "image/tiff")

        // Documents
        put("pdf", "application/pdf")
        put("doc", "application/msword")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", "application/vnd.ms-excel")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")

        // Archives
        put("zip", "application/zip")
        put("gz", "application/gzip")
        put("tar", "application/x-tar")
        put("7z", "application/x-7z-compressed")
        put("rar", "application/vnd.rar")

        // Binary / compiled
        put("class", "application/java-vm")
        put("pyc", "application/x-python-bytecode")

        // Misc
        put("sql", "text/x-sql")
        put("graphql", "text/x-graphql")
        put("proto", "text/x-protobuf")
        put("lock", "text/plain")
        put("log", "text/plain")
        put("env", "text/plain")
        put("gitignore", "text/plain")
        put("gitattributes", "text/plain")
        put("editorconfig", "text/plain")
        put("eslintrc", "text/plain")
        put("prettierrc", "text/plain")
        put("babelrc", "text/plain")
    }

    /**
     * Guess the MIME type from a filename.
     *
     * Lookup order:
     * 1. [fullNameMap] — keyed by the full lowercased file name (handles
     *    extensionless files like `Makefile`/`Dockerfile` and multi-dot
     *    dotfiles like `.env.local`).
     * 2. [extensionMap] — keyed by the substring after the last dot.
     * 3. [URLConnection.guessContentTypeFromName] — JDK built-in map (~20
     *    common extensions).
     * 4. `application/octet-stream` — final fallback.
     */
    fun guessFromFileName(fileName: String): String {
        val lowerName = fileName.lowercase()
        fullNameMap[lowerName]?.let { return it }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            extensionMap[ext]?.let { return it }
        }
        return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    /**
     * Guess the MIME type from a file on disk.
     *
     * Lookup order:
     * 1. [fullNameMap] — keyed by the full lowercased file name.
     * 2. [extensionMap] — keyed by the substring after the last dot.
     * 3. [URLConnection.guessContentTypeFromName] — JDK built-in map.
     * 4. [java.nio.file.Files.probeContentType] — content-based detection
     *    (reads file headers/magic bytes). Catches text-based files with
     *    unrecognized extensions (e.g. Unity .prefab, .asset).
     * 5. `application/octet-stream` — final fallback.
     *
     * @param file The file to detect. Must exist and be readable for content-based
     *   detection to work; falls back to extension-only if not.
     */
    fun guessFromFile(file: java.io.File): String {
        // 1. Full-name lookup (handles Makefile, Dockerfile, .env.local, etc.)
        val lowerName = file.name.lowercase()
        fullNameMap[lowerName]?.let { return it }
        // 2. Extension-based lookup (fast path)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            extensionMap[ext]?.let { return it }
        }
        // 3. JDK built-in name-based detection
        URLConnection.guessContentTypeFromName(file.name)?.let { return it }
        // 4. Content-based detection: reads file headers/magic bytes
        return try {
            if (file.exists() && file.canRead() && file.isFile) {
                // Size guard: probeContentType may read file headers/magic bytes,
                // which can be slow for very large files. Skip the probe for files
                // larger than 10MB — the extension-map fast path handles most cases,
                // and this fallback only triggers for unknown extensions. A large
                // file with an unknown extension is rare; blocking the calling
                // thread (which can be a user-facing EDT/IO path) for a multi-GB
                // probe is worse than returning octet-stream.
                if (file.length() > MAX_PROBE_SIZE_BYTES) {
                    "application/octet-stream"
                } else {
                    java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                }
            } else {
                "application/octet-stream"
            }
        } catch (_: Exception) {
            "application/octet-stream"
        }
    }
}
