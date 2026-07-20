package com.opencode.acp.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the real [MimeTypes] object.
 *
 * Tests the comprehensive extension → MIME map directly — no fakes, no mocks.
 * Covers source code, web, data/config, shell, docs, images, documents, archives,
 * case-insensitivity, path stripping, dotfiles, and fallback behavior.
 */
class MimeTypesTest {

    // ── Source code ────────────────────────────────────────────────────────
    @Test
    fun `kt maps to text-x-kotlin`() {
        MimeTypes.guessFromFileName("Main.kt") shouldBe "text/x-kotlin"
    }

    @Test
    fun `kts maps to text-x-kotlin`() {
        MimeTypes.guessFromFileName("build.gradle.kts") shouldBe "text/x-kotlin"
    }

    @Test
    fun `java maps to text-x-java`() {
        MimeTypes.guessFromFileName("Hello.java") shouldBe "text/x-java"
    }

    @Test
    fun `py maps to text-x-python`() {
        MimeTypes.guessFromFileName("script.py") shouldBe "text/x-python"
    }

    @Test
    fun `rb maps to text-x-ruby`() {
        MimeTypes.guessFromFileName("app.rb") shouldBe "text/x-ruby"
    }

    @Test
    fun `rs maps to text-x-rust`() {
        MimeTypes.guessFromFileName("lib.rs") shouldBe "text/x-rust"
    }

    @Test
    fun `go maps to text-x-go`() {
        MimeTypes.guessFromFileName("main.go") shouldBe "text/x-go"
    }

    // ── Web ────────────────────────────────────────────────────────────────
    @Test
    fun `js maps to text-javascript`() {
        MimeTypes.guessFromFileName("app.js") shouldBe "text/javascript"
    }

    @Test
    fun `jsx maps to text-javascript`() {
        MimeTypes.guessFromFileName("Component.jsx") shouldBe "text/javascript"
    }

    @Test
    fun `ts maps to text-typescript`() {
        MimeTypes.guessFromFileName("index.ts") shouldBe "text/typescript"
    }

    @Test
    fun `tsx maps to text-typescript`() {
        MimeTypes.guessFromFileName("App.tsx") shouldBe "text/typescript"
    }

    @Test
    fun `html maps to text-html`() {
        MimeTypes.guessFromFileName("index.html") shouldBe "text/html"
    }

    @Test
    fun `htm maps to text-html`() {
        MimeTypes.guessFromFileName("index.htm") shouldBe "text/html"
    }

    @Test
    fun `css maps to text-css`() {
        MimeTypes.guessFromFileName("styles.css") shouldBe "text/css"
    }

    // ── Data / config ──────────────────────────────────────────────────────
    @Test
    fun `json maps to application-json`() {
        MimeTypes.guessFromFileName("package.json") shouldBe "application/json"
    }

    @Test
    fun `yaml maps to text-yaml`() {
        MimeTypes.guessFromFileName("config.yaml") shouldBe "text/yaml"
    }

    @Test
    fun `yml maps to text-yaml`() {
        MimeTypes.guessFromFileName("config.yml") shouldBe "text/yaml"
    }

    // ── Unity / game dev ──────────────────────────────────────────────────
    @Test
    fun `prefab maps to text-yaml`() {
        MimeTypes.guessFromFileName("Player.prefab") shouldBe "text/yaml"
    }

    @Test
    fun `asset maps to text-yaml`() {
        MimeTypes.guessFromFileName("ProjectSettings.asset") shouldBe "text/yaml"
    }

    @Test
    fun `meta maps to text-yaml`() {
        MimeTypes.guessFromFileName("Main.cs.meta") shouldBe "text/yaml"
    }

    @Test
    fun `unity scene maps to text-yaml`() {
        MimeTypes.guessFromFileName("MainScene.unity") shouldBe "text/yaml"
    }

    @Test
    fun `mat maps to text-yaml`() {
        MimeTypes.guessFromFileName("Wood.mat") shouldBe "text/yaml"
    }

    @Test
    fun `asmdef maps to text-json`() {
        MimeTypes.guessFromFileName("Assembly-CSharp.asmdef") shouldBe "text/json"
    }

    @Test
    fun `shader maps to text-x-c`() {
        MimeTypes.guessFromFileName("Unlit.shader") shouldBe "text/x-c"
    }

    @Test
    fun `toml maps to text-toml`() {
        MimeTypes.guessFromFileName("Cargo.toml") shouldBe "text/toml"
    }

    @Test
    fun `xml maps to application-xml`() {
        MimeTypes.guessFromFileName("pom.xml") shouldBe "application/xml"
    }

    // ── Documentation ───────────────────────────────────────────────────────
    @Test
    fun `md maps to text-markdown`() {
        MimeTypes.guessFromFileName("README.md") shouldBe "text/markdown"
    }

    @Test
    fun `txt maps to text-plain`() {
        MimeTypes.guessFromFileName("notes.txt") shouldBe "text/plain"
    }

    // ── Objective-C / Objective-C++ ─────────────────────────────────────────

    @Test
    fun `m maps to text-x-objective-c`() {
        MimeTypes.guessFromFileName("AppDelegate.m") shouldBe "text/x-objective-c"
    }

    @Test
    fun `mm maps to text-x-objective-c++`() {
        MimeTypes.guessFromFileName("AppController.mm") shouldBe "text/x-objective-c++"
    }

    // ── Shell / scripts ────────────────────────────────────────────────────

    @Test
    fun `sh maps to text-x-shellscript`() {
        MimeTypes.guessFromFileName("deploy.sh") shouldBe "text/x-shellscript"
    }

    @Test
    fun `bash maps to text-x-shellscript`() {
        MimeTypes.guessFromFileName("run.bash") shouldBe "text/x-shellscript"
    }

    @Test
    fun `zsh maps to text-x-shellscript`() {
        MimeTypes.guessFromFileName("run.zsh") shouldBe "text/x-shellscript"
    }

    @Test
    fun `sql maps to text-x-sql`() {
        MimeTypes.guessFromFileName("query.sql") shouldBe "text/x-sql"
    }

    // ── Images ─────────────────────────────────────────────────────────────
    @Test
    fun `png maps to image-png`() {
        MimeTypes.guessFromFileName("logo.png") shouldBe "image/png"
    }

    @Test
    fun `jpg maps to image-jpeg`() {
        MimeTypes.guessFromFileName("photo.jpg") shouldBe "image/jpeg"
    }

    @Test
    fun `jpeg maps to image-jpeg`() {
        MimeTypes.guessFromFileName("photo.jpeg") shouldBe "image/jpeg"
    }

    @Test
    fun `gif maps to image-gif`() {
        MimeTypes.guessFromFileName("animation.gif") shouldBe "image/gif"
    }

    @Test
    fun `svg maps to image-svg+xml`() {
        MimeTypes.guessFromFileName("icon.svg") shouldBe "image/svg+xml"
    }

    // ── Documents / archives ───────────────────────────────────────────────
    @Test
    fun `pdf maps to application-pdf`() {
        MimeTypes.guessFromFileName("doc.pdf") shouldBe "application/pdf"
    }

    @Test
    fun `zip maps to application-zip`() {
        MimeTypes.guessFromFileName("archive.zip") shouldBe "application/zip"
    }

    // ── Fallback behavior ──────────────────────────────────────────────────
    @Test
    fun `unknown extension falls back to application-octet-stream`() {
        MimeTypes.guessFromFileName("file.xyzunknown") shouldBe "application/octet-stream"
    }

    @Test
    fun `no extension falls back to application-octet-stream`() {
        // Unknown extensionless names fall back to application/octet-stream.
        // (Known extensionless names like Makefile/Dockerfile are handled by
        // fullNameMap — see the full-name tests below.)
        MimeTypes.guessFromFileName("unknownfile") shouldBe "application/octet-stream"
    }

    @Test
    fun `empty string falls back to application-octet-stream`() {
        MimeTypes.guessFromFileName("") shouldBe "application/octet-stream"
    }

    // ── Full-name lookup (extensionless / multi-dot dotfiles) ──────────────
    @Test
    fun `Makefile maps to text-x-makefile via fullNameMap`() {
        MimeTypes.guessFromFileName("Makefile") shouldBe "text/x-makefile"
    }

    @Test
    fun `Dockerfile maps to text-x-dockerfile via fullNameMap`() {
        MimeTypes.guessFromFileName("Dockerfile") shouldBe "text/x-dockerfile"
    }

    @Test
    fun `makefile is case-insensitive via fullNameMap`() {
        MimeTypes.guessFromFileName("makefile") shouldBe "text/x-makefile"
        MimeTypes.guessFromFileName("MAKEFILE") shouldBe "text/x-makefile"
    }

    @Test
    fun `dockerfile is case-insensitive via fullNameMap`() {
        MimeTypes.guessFromFileName("dockerfile") shouldBe "text/x-dockerfile"
        MimeTypes.guessFromFileName("DOCKERFILE") shouldBe "text/x-dockerfile"
    }

    @Test
    fun `env local maps to text-plain via fullNameMap`() {
        MimeTypes.guessFromFileName(".env.local") shouldBe "text/plain"
    }

    @Test
    fun `env development maps to text-plain via fullNameMap`() {
        MimeTypes.guessFromFileName(".env.development") shouldBe "text/plain"
    }

    @Test
    fun `env production maps to text-plain via fullNameMap`() {
        MimeTypes.guessFromFileName(".env.production") shouldBe "text/plain"
    }

    @Test
    fun `env local is case-insensitive via fullNameMap`() {
        MimeTypes.guessFromFileName(".ENV.LOCAL") shouldBe "text/plain"
    }

    // ── Case-insensitivity & path handling ─────────────────────────────────
    @Test
    fun `extension lookup is case-insensitive`() {
        MimeTypes.guessFromFileName("Main.KT") shouldBe "text/x-kotlin"
    }

    @Test
    fun `mixed-case extension is lowercased before lookup`() {
        MimeTypes.guessFromFileName("App.TsX") shouldBe "text/typescript"
    }

    @Test
    fun `file with path maps by extension`() {
        MimeTypes.guessFromFileName("src/main/Main.kt") shouldBe "text/x-kotlin"
    }

    @Test
    fun `dotfile with known extension maps correctly`() {
        // ".gitignore" → ext is "gitignore" → text/plain (per extensionMap)
        MimeTypes.guessFromFileName(".gitignore") shouldBe "text/plain"
    }

    @Test
    fun `dotfile with editorconfig maps to text-plain`() {
        MimeTypes.guessFromFileName(".editorconfig") shouldBe "text/plain"
    }
}