package com.opencode.acp.chat.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FileTypeIcons] pure functions (TDD §8.2 — FileTypeIconsTest).
 *
 * Tests only the pure functions ([iconKeyForExtension], [iconKeyForFileName],
 * [iconKeyForLanguageId]). The @Composable [FileTypeIcons.fileColorForExtension]
 * is NOT tested here — it requires Compose infrastructure and is covered by
 * integration tests.
 *
 * The platform fallback ([FileTypeIcons.resolveFileTypeIconFromPlatform]) is
 * fail-safe: in a pure unit test (no IntelliJ application context), it catches
 * the exception and returns [PlatformIconKeys.FileTypes.Text]. This means
 * unknown extensions degrade to Text in tests, which is the intended behavior.
 */
class FileTypeIconsTest {

    // ── iconKeyForExtension ─────────────────────────────────────────────────

    @Test
    fun `iconKeyForExtension kt returns Kotlin`() {
        FileTypeIcons.iconKeyForExtension("kt") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForExtension kts returns Kotlin`() {
        FileTypeIcons.iconKeyForExtension("kts") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForExtension java returns Java`() {
        FileTypeIcons.iconKeyForExtension("java") shouldBe PlatformIconKeys.FileTypes.Java
    }

    @Test
    fun `iconKeyForExtension js returns JavaScript`() {
        FileTypeIcons.iconKeyForExtension("js") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForExtension jsx returns JavaScript`() {
        FileTypeIcons.iconKeyForExtension("jsx") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForExtension ts returns JavaScript`() {
        FileTypeIcons.iconKeyForExtension("ts") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForExtension tsx returns JavaScript`() {
        FileTypeIcons.iconKeyForExtension("tsx") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForExtension css returns Css`() {
        FileTypeIcons.iconKeyForExtension("css") shouldBe PlatformIconKeys.FileTypes.Css
    }

    @Test
    fun `iconKeyForExtension scss returns Css`() {
        FileTypeIcons.iconKeyForExtension("scss") shouldBe PlatformIconKeys.FileTypes.Css
    }

    @Test
    fun `iconKeyForExtension less returns Css`() {
        FileTypeIcons.iconKeyForExtension("less") shouldBe PlatformIconKeys.FileTypes.Css
    }

    @Test
    fun `iconKeyForExtension html returns Html`() {
        FileTypeIcons.iconKeyForExtension("html") shouldBe PlatformIconKeys.FileTypes.Html
    }

    @Test
    fun `iconKeyForExtension htm returns Html`() {
        FileTypeIcons.iconKeyForExtension("htm") shouldBe PlatformIconKeys.FileTypes.Html
    }

    @Test
    fun `iconKeyForExtension xml returns Xml`() {
        FileTypeIcons.iconKeyForExtension("xml") shouldBe PlatformIconKeys.FileTypes.Xml
    }

    @Test
    fun `iconKeyForExtension json returns Json`() {
        FileTypeIcons.iconKeyForExtension("json") shouldBe PlatformIconKeys.FileTypes.Json
    }

    @Test
    fun `iconKeyForExtension yaml returns Yaml`() {
        FileTypeIcons.iconKeyForExtension("yaml") shouldBe PlatformIconKeys.FileTypes.Yaml
    }

    @Test
    fun `iconKeyForExtension yml returns Yaml`() {
        FileTypeIcons.iconKeyForExtension("yml") shouldBe PlatformIconKeys.FileTypes.Yaml
    }

    @Test
    fun `iconKeyForExtension md returns Text`() {
        FileTypeIcons.iconKeyForExtension("md") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension txt returns Text`() {
        FileTypeIcons.iconKeyForExtension("txt") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension properties returns Text`() {
        FileTypeIcons.iconKeyForExtension("properties") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension gitignore returns Text`() {
        FileTypeIcons.iconKeyForExtension("gitignore") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension gradle returns Text`() {
        FileTypeIcons.iconKeyForExtension("gradle") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension sql returns Text`() {
        FileTypeIcons.iconKeyForExtension("sql") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension py returns Python`() {
        FileTypeIcons.iconKeyForExtension("py") shouldBe PlatformIconKeys.Language.Python
    }

    @Test
    fun `iconKeyForExtension rb returns Ruby`() {
        FileTypeIcons.iconKeyForExtension("rb") shouldBe PlatformIconKeys.Language.Ruby
    }

    @Test
    fun `iconKeyForExtension go returns GO`() {
        FileTypeIcons.iconKeyForExtension("go") shouldBe PlatformIconKeys.Language.GO
    }

    @Test
    fun `iconKeyForExtension rs returns Rust`() {
        FileTypeIcons.iconKeyForExtension("rs") shouldBe PlatformIconKeys.Language.Rust
    }

    @Test
    fun `iconKeyForExtension scala returns Scala`() {
        FileTypeIcons.iconKeyForExtension("scala") shouldBe PlatformIconKeys.Language.Scala
    }

    @Test
    fun `iconKeyForExtension php returns Php`() {
        FileTypeIcons.iconKeyForExtension("php") shouldBe PlatformIconKeys.Language.Php
    }

    @Test
    fun `iconKeyForExtension sh returns Console`() {
        FileTypeIcons.iconKeyForExtension("sh") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForExtension bash returns Console`() {
        FileTypeIcons.iconKeyForExtension("bash") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForExtension zsh returns Console`() {
        FileTypeIcons.iconKeyForExtension("zsh") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForExtension svg returns Image`() {
        FileTypeIcons.iconKeyForExtension("svg") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension png returns Image`() {
        FileTypeIcons.iconKeyForExtension("png") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension jpg returns Image`() {
        FileTypeIcons.iconKeyForExtension("jpg") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension jpeg returns Image`() {
        FileTypeIcons.iconKeyForExtension("jpeg") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension gif returns Image`() {
        FileTypeIcons.iconKeyForExtension("gif") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension bmp returns Image`() {
        FileTypeIcons.iconKeyForExtension("bmp") shouldBe PlatformIconKeys.FileTypes.Image
    }

    @Test
    fun `iconKeyForExtension webp returns Image`() {
        FileTypeIcons.iconKeyForExtension("webp") shouldBe PlatformIconKeys.FileTypes.Image
    }

    // ── iconKeyForExtension — edge cases (fail-safe) ─────────────────────────

    @Test
    fun `iconKeyForExtension unknown does not throw`() {
        // Unknown extension falls through to resolveFileTypeIconFromPlatform,
        // which is fail-safe (try/catch returns Text when FileTypeManager unavailable).
        FileTypeIcons.iconKeyForExtension("unknownext123") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension empty does not throw`() {
        FileTypeIcons.iconKeyForExtension("") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForExtension is case-insensitive`() {
        FileTypeIcons.iconKeyForExtension("KT") shouldBe PlatformIconKeys.Language.Kotlin
        FileTypeIcons.iconKeyForExtension("Java") shouldBe PlatformIconKeys.FileTypes.Java
        FileTypeIcons.iconKeyForExtension("JS") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    // ── iconKeyForFileName ───────────────────────────────────────────────────

    @Test
    fun `iconKeyForFileName Main kt returns Kotlin`() {
        FileTypeIcons.iconKeyForFileName("Main.kt") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForFileName App java returns Java`() {
        FileTypeIcons.iconKeyForFileName("App.java") shouldBe PlatformIconKeys.FileTypes.Java
    }

    @Test
    fun `iconKeyForFileName App test js returns JavaScript (last extension)`() {
        FileTypeIcons.iconKeyForFileName("App.test.js") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForFileName with path returns icon for last extension`() {
        FileTypeIcons.iconKeyForFileName("src/main/kotlin/App.kt") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForFileName no extension does not throw`() {
        FileTypeIcons.iconKeyForFileName("noextension") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForFileName empty does not throw`() {
        FileTypeIcons.iconKeyForFileName("") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForFileName dotfile gitignore returns Text`() {
        // ".gitignore" → ext is "gitignore" → mapped to Text
        FileTypeIcons.iconKeyForFileName(".gitignore") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForFileName is case-insensitive`() {
        FileTypeIcons.iconKeyForFileName("Main.KT") shouldBe PlatformIconKeys.Language.Kotlin
        FileTypeIcons.iconKeyForFileName("App.JAVA") shouldBe PlatformIconKeys.FileTypes.Java
    }

    // ── iconKeyForLanguageId ─────────────────────────────────────────────────

    @Test
    fun `iconKeyForLanguageId kotlin returns Kotlin`() {
        FileTypeIcons.iconKeyForLanguageId("kotlin") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForLanguageId kt returns Kotlin`() {
        FileTypeIcons.iconKeyForLanguageId("kt") shouldBe PlatformIconKeys.Language.Kotlin
    }

    @Test
    fun `iconKeyForLanguageId javascript returns JavaScript`() {
        FileTypeIcons.iconKeyForLanguageId("javascript") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForLanguageId js returns JavaScript`() {
        FileTypeIcons.iconKeyForLanguageId("js") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForLanguageId typescript returns JavaScript`() {
        FileTypeIcons.iconKeyForLanguageId("typescript") shouldBe PlatformIconKeys.FileTypes.JavaScript
    }

    @Test
    fun `iconKeyForLanguageId python returns Python`() {
        FileTypeIcons.iconKeyForLanguageId("python") shouldBe PlatformIconKeys.Language.Python
    }

    @Test
    fun `iconKeyForLanguageId py returns Python`() {
        FileTypeIcons.iconKeyForLanguageId("py") shouldBe PlatformIconKeys.Language.Python
    }

    @Test
    fun `iconKeyForLanguageId ruby returns Ruby`() {
        FileTypeIcons.iconKeyForLanguageId("ruby") shouldBe PlatformIconKeys.Language.Ruby
    }

    @Test
    fun `iconKeyForLanguageId rust returns Rust`() {
        FileTypeIcons.iconKeyForLanguageId("rust") shouldBe PlatformIconKeys.Language.Rust
    }

    @Test
    fun `iconKeyForLanguageId go returns GO`() {
        FileTypeIcons.iconKeyForLanguageId("go") shouldBe PlatformIconKeys.Language.GO
    }

    @Test
    fun `iconKeyForLanguageId scala returns Scala`() {
        FileTypeIcons.iconKeyForLanguageId("scala") shouldBe PlatformIconKeys.Language.Scala
    }

    @Test
    fun `iconKeyForLanguageId php returns Php`() {
        FileTypeIcons.iconKeyForLanguageId("php") shouldBe PlatformIconKeys.Language.Php
    }

    @Test
    fun `iconKeyForLanguageId shell returns Console`() {
        FileTypeIcons.iconKeyForLanguageId("shell") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForLanguageId bash returns Console`() {
        FileTypeIcons.iconKeyForLanguageId("bash") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForLanguageId sh returns Console`() {
        FileTypeIcons.iconKeyForLanguageId("sh") shouldBe PlatformIconKeys.Nodes.Console
    }

    @Test
    fun `iconKeyForLanguageId sql returns Text`() {
        FileTypeIcons.iconKeyForLanguageId("sql") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForLanguageId unknown returns Text`() {
        // Unknown language IDs fall back to Text (NOT the platform fallback,
        // which is for file extensions, not language identifiers).
        FileTypeIcons.iconKeyForLanguageId("unknownlang") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForLanguageId empty returns Text`() {
        FileTypeIcons.iconKeyForLanguageId("") shouldBe PlatformIconKeys.FileTypes.Text
    }

    @Test
    fun `iconKeyForLanguageId is case-insensitive`() {
        FileTypeIcons.iconKeyForLanguageId("KOTLIN") shouldBe PlatformIconKeys.Language.Kotlin
        FileTypeIcons.iconKeyForLanguageId("JavaScript") shouldBe PlatformIconKeys.FileTypes.JavaScript
        FileTypeIcons.iconKeyForLanguageId("PYTHON") shouldBe PlatformIconKeys.Language.Python
    }
}