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
        put("m", "text/x-objective")
        put("mm", "text/x-objective++")

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
        put("makefile", "text/x-makefile")
        put("dockerfile", "text/x-dockerfile")
        put("lock", "text/plain")
        put("log", "text/plain")
        put("txt", "text/plain")
        put("env", "text/plain")
        put("gitignore", "text/plain")
        put("gitattributes", "text/plain")
        put("editorconfig", "text/plain")
        put("eslintrc", "text/plain")
        put("prettierrc", "text/plain")
        put("babelrc", "text/plain")
        put("env.local", "text/plain")
        put("env.development", "text/plain")
        put("env.production", "text/plain")
    }

    /**
     * Guess the MIME type from a filename.
     *
     * Tries a custom comprehensive map first, then falls back to
     * [URLConnection.guessContentTypeFromName], and finally defaults
     * to `application/octet-stream`.
     */
    fun guessFromFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            extensionMap[ext]?.let { return it }
        }
        return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }
}
