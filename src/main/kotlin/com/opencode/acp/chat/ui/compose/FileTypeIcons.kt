package com.opencode.acp.chat.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.opencode.acp.chat.ui.theme.ChatTheme
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon

/**
 * Consolidated file-type icon and color resolution.
 *
 * This object eliminates the quadruple duplication of file-type → icon mapping that
 * previously lived in [CodeBlockRenderer.languageIcon], [ReviewPanel.getFileTypeIcon],
 * [AttachMenu.fileIconForFile], and [MentionPalette] (which delegated to AttachMenu).
 *
 * ## Pure vs Compose
 *
 * The `iconKeyFor*` functions are pure (no Compose dependency, no ChatTheme reads) and
 * therefore unit-testable. [fileColorForExtension] is `@Composable` because it reads
 * [ChatTheme.colors.file.*] and must run inside a composition.
 *
 * ## PlatformIconKeys vs AllIconsKeys
 *
 * All mappings use [PlatformIconKeys] (which wraps platform icons via
 * [IntelliJIconKey.fromPlatformIcon]) instead of [org.jetbrains.jewel.ui.icons.AllIconsKeys].
 * The latter renders magenta placeholders because its SVG resources live in the IntelliJ
 * platform JARs, not the Jewel JARs. See [PlatformIconKeys] for details.
 *
 * ## Fallback behavior
 *
 * For extensions not in the explicit map, [resolveFileTypeIconFromPlatform] asks
 * IntelliJ's [FileTypeManager] for the registered file-type icon (e.g. C#/C++ on Rider).
 * The lookup is wrapped in a try/catch so it degrades to [PlatformIconKeys.FileTypes.Text]
 * when the IntelliJ application context is unavailable (e.g. in pure unit tests) or when
 * a misbehaving FileType extension throws. This keeps the pure functions total — they
 * never throw.
 */
object FileTypeIcons {

    // ── Extension → IconKey (union of all 4 prior sites) ───────────────────────

    private val extensionIcons: Map<String, IconKey> = buildMap {
        put("kt", PlatformIconKeys.Language.Kotlin)
        put("kts", PlatformIconKeys.Language.Kotlin)
        put("java", PlatformIconKeys.FileTypes.Java)
        put("js", PlatformIconKeys.FileTypes.JavaScript)
        put("jsx", PlatformIconKeys.FileTypes.JavaScript)
        put("ts", PlatformIconKeys.FileTypes.JavaScript)
        put("tsx", PlatformIconKeys.FileTypes.JavaScript)
        put("css", PlatformIconKeys.FileTypes.Css)
        put("scss", PlatformIconKeys.FileTypes.Css)
        put("less", PlatformIconKeys.FileTypes.Css)
        put("html", PlatformIconKeys.FileTypes.Html)
        put("htm", PlatformIconKeys.FileTypes.Html)
        put("xml", PlatformIconKeys.FileTypes.Xml)
        put("json", PlatformIconKeys.FileTypes.Json)
        put("yaml", PlatformIconKeys.FileTypes.Yaml)
        put("yml", PlatformIconKeys.FileTypes.Yaml)
        // Unity / game dev
        put("prefab", PlatformIconKeys.FileTypes.Yaml)
        put("asset", PlatformIconKeys.FileTypes.Yaml)
        put("meta", PlatformIconKeys.FileTypes.Yaml)
        put("unity", PlatformIconKeys.FileTypes.Yaml)
        put("mat", PlatformIconKeys.FileTypes.Yaml)
        put("controller", PlatformIconKeys.FileTypes.Yaml)
        put("anim", PlatformIconKeys.FileTypes.Yaml)
        put("cs", PlatformIconKeys.FileTypes.Java) // C# → use Java icon (closest match)
        put("asmdef", PlatformIconKeys.FileTypes.Json)
        put("asmref", PlatformIconKeys.FileTypes.Json)
        put("shader", PlatformIconKeys.FileTypes.Text)
        put("cginc", PlatformIconKeys.FileTypes.Text)
        put("compute", PlatformIconKeys.FileTypes.Text)
        put("hlsl", PlatformIconKeys.FileTypes.Text)
        put("glsl", PlatformIconKeys.FileTypes.Text)
        put("md", PlatformIconKeys.FileTypes.Text)
        put("txt", PlatformIconKeys.FileTypes.Text)
        put("properties", PlatformIconKeys.FileTypes.Text)
        put("gitignore", PlatformIconKeys.FileTypes.Text)
        put("gradle", PlatformIconKeys.FileTypes.Text)
        put("sql", PlatformIconKeys.FileTypes.Text)
        put("py", PlatformIconKeys.Language.Python)
        put("rb", PlatformIconKeys.Language.Ruby)
        put("go", PlatformIconKeys.Language.GO)
        put("rs", PlatformIconKeys.Language.Rust)
        put("scala", PlatformIconKeys.Language.Scala)
        put("php", PlatformIconKeys.Language.Php)
        put("sh", PlatformIconKeys.Nodes.Console)
        put("bash", PlatformIconKeys.Nodes.Console)
        put("zsh", PlatformIconKeys.Nodes.Console)
        put("svg", PlatformIconKeys.FileTypes.Image)
        put("png", PlatformIconKeys.FileTypes.Image)
        put("jpg", PlatformIconKeys.FileTypes.Image)
        put("jpeg", PlatformIconKeys.FileTypes.Image)
        put("gif", PlatformIconKeys.FileTypes.Image)
        put("bmp", PlatformIconKeys.FileTypes.Image)
        put("webp", PlatformIconKeys.FileTypes.Image)
    }

    // ── Language ID → IconKey (for code fences; takes language names, not extensions) ─

    private val languageIcons: Map<String, IconKey> = buildMap {
        put("kotlin", PlatformIconKeys.Language.Kotlin)
        put("kt", PlatformIconKeys.Language.Kotlin)
        put("kts", PlatformIconKeys.Language.Kotlin)
        put("java", PlatformIconKeys.FileTypes.Java)
        put("javascript", PlatformIconKeys.FileTypes.JavaScript)
        put("js", PlatformIconKeys.FileTypes.JavaScript)
        put("jsx", PlatformIconKeys.FileTypes.JavaScript)
        put("typescript", PlatformIconKeys.FileTypes.JavaScript)
        put("ts", PlatformIconKeys.FileTypes.JavaScript)
        put("tsx", PlatformIconKeys.FileTypes.JavaScript)
        put("css", PlatformIconKeys.FileTypes.Css)
        put("scss", PlatformIconKeys.FileTypes.Css)
        put("less", PlatformIconKeys.FileTypes.Css)
        put("html", PlatformIconKeys.FileTypes.Html)
        put("htm", PlatformIconKeys.FileTypes.Html)
        put("xml", PlatformIconKeys.FileTypes.Xml)
        put("json", PlatformIconKeys.FileTypes.Json)
        put("yaml", PlatformIconKeys.FileTypes.Yaml)
        put("yml", PlatformIconKeys.FileTypes.Yaml)
        // Unity / game dev
        put("csharp", PlatformIconKeys.FileTypes.Java)
        put("cs", PlatformIconKeys.FileTypes.Java)
        put("shader", PlatformIconKeys.FileTypes.Text)
        put("hlsl", PlatformIconKeys.FileTypes.Text)
        put("glsl", PlatformIconKeys.FileTypes.Text)
        put("cginc", PlatformIconKeys.FileTypes.Text)
        put("lua", PlatformIconKeys.FileTypes.Text)
        put("dart", PlatformIconKeys.FileTypes.Text)
        put("python", PlatformIconKeys.Language.Python)
        put("py", PlatformIconKeys.Language.Python)
        put("ruby", PlatformIconKeys.Language.Ruby)
        put("rb", PlatformIconKeys.Language.Ruby)
        put("rust", PlatformIconKeys.Language.Rust)
        put("rs", PlatformIconKeys.Language.Rust)
        put("go", PlatformIconKeys.Language.GO)
        put("scala", PlatformIconKeys.Language.Scala)
        put("php", PlatformIconKeys.Language.Php)
        put("shell", PlatformIconKeys.Nodes.Console)
        put("bash", PlatformIconKeys.Nodes.Console)
        put("zsh", PlatformIconKeys.Nodes.Console)
        put("sh", PlatformIconKeys.Nodes.Console)
        put("sql", PlatformIconKeys.FileTypes.Text)
    }

    // ── Pure functions ────────────────────────────────────────────────────────

    /**
     * Returns the [IconKey] for a file extension (without the leading dot).
     *
     * Examples: `"kt"` → Kotlin, `"java"` → Java, `"js"` → JavaScript.
     *
     * For extensions not in the explicit map, falls back to
     * [resolveFileTypeIconFromPlatform], which asks IntelliJ's [FileTypeManager].
     * The fallback is fail-safe: if [FileTypeManager] is unavailable (e.g. in a
     * pure unit test) or throws, [PlatformIconKeys.FileTypes.Text] is returned.
     *
     * This function is pure — no @Composable, no ChatTheme reads, no side effects.
     */
    fun iconKeyForExtension(ext: String): IconKey {
        val key = ext.lowercase()
        val cached = extensionIcons[key]
        if (cached != null) return cached
        // Unknown extension — try the platform FileTypeManager. Fail-safe to Text.
        return try {
            resolveFileTypeIconFromPlatform("dummy.$ext")
        } catch (_: Exception) {
            PlatformIconKeys.FileTypes.Text
        }
    }

    /**
     * Returns the [IconKey] for a file name. Extracts the extension (substring
     * after the last `.`) and delegates to [iconKeyForExtension].
     *
     * Examples: `"Main.kt"` → Kotlin, `"App.java"` → Java, `"App.test.js"` →
     * JavaScript (uses the last extension).
     *
     * Files with no extension or an empty name fall through to the platform
     * fallback, which is fail-safe (returns [PlatformIconKeys.FileTypes.Text]
     * if [FileTypeManager] is unavailable).
     *
     * This function is pure — no @Composable, no ChatTheme reads, no side effects.
     */
    fun iconKeyForFileName(fileName: String): IconKey {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) {
            // No extension — try the platform fallback with the raw file name so
            // FileTypeManager can match by name pattern (e.g. ".gitignore").
            return try {
                resolveFileTypeIconFromPlatform(fileName)
            } catch (_: Exception) {
                PlatformIconKeys.FileTypes.Text
            }
        }
        return iconKeyForExtension(ext)
    }

    /**
     * Returns the [IconKey] for a language identifier as used in markdown code
     * fences. Handles both full language names (`"kotlin"`, `"javascript"`) and
     * short forms (`"kt"`, `"js"`), mapping both to the same icon.
     *
     * Examples: `"kotlin"` → Kotlin, `"javascript"` → JavaScript, `"py"` → Python.
     *
     * Unknown language IDs fall back to [PlatformIconKeys.FileTypes.Text] (the
     * generic text-file icon), NOT the platform fallback — code-fence language
     * IDs are not file extensions and shouldn't be resolved by [FileTypeManager].
     *
     * This function is pure — no @Composable, no ChatTheme reads, no side effects.
     */
    fun iconKeyForLanguageId(lang: String): IconKey {
        val key = lang.lowercase()
        return languageIcons[key] ?: PlatformIconKeys.FileTypes.Text
    }

    // ── Compose-dependent ────────────────────────────────────────────────────

    /**
     * Returns the accent [Color] for a file extension, reading from
     * [ChatTheme.colors.file]. Must be called from a composition context.
     *
     * Unknown extensions fall back to [ChatTheme.colors.component.attachmentRemoveIcon].
     *
     * NOT unit-tested — requires Compose infrastructure. Covered by integration
     * tests when the chat UI is exercised.
     */
    @Composable
    fun fileColorForExtension(ext: String): Color {
        val file = ChatTheme.colors.file
        return when (ext.lowercase()) {
            "kt", "kts" -> file.kotlin
            "java" -> file.java
            "js", "jsx" -> file.javaScript
            "ts", "tsx" -> file.typeScript
            "py" -> file.python
            "rb" -> file.ruby
            "go" -> file.go
            "rs" -> file.rust
            "html", "htm" -> file.html
            "css", "scss" -> file.css
            "xml" -> file.xml
            "json" -> file.json
            "yaml", "yml" -> file.yaml
            "md" -> file.markdown
            "sql" -> file.sql
            "sh", "bash" -> file.shell
            else -> ChatTheme.colors.component.attachmentRemoveIcon
        }
    }

    // ── Platform fallback (moved from ReviewPanel.kt) ─────────────────────────

    /**
     * Fallback for file types not covered by the static [extensionIcons] map.
     *
     * Asks [FileTypeManager] for the registered [com.intellij.openapi.fileTypes.FileType]
     * for the file name and wraps its icon as a Jewel [IconKey] via
     * [IntelliJIconKey.fromPlatformIcon]. On Rider this resolves the real C#/C++/
     * F#/VB/Razor/csproj/sln icons; on IntelliJ IDEA (no .NET plugin) it falls
     * back to the plain-text file-type icon, which is the same as the previous
     * hard-coded [PlatformIconKeys.FileTypes.Text] fallback. The lookup is cheap
     * and read-safe ([FileTypeManager.getFileTypeByFileName] does not require a
     * read action), so it is safe to call from composition.
     *
     * Guarded with a try/catch so a misbehaving FileType extension can never break
     * the UI — it degrades to the generic text icon. Only [Exception] is caught;
     * JVM-level errors (OutOfMemoryError, StackOverflowError) are allowed to
     * propagate so they are not masked. Also degrades to Text when the IntelliJ
     * application context is unavailable (e.g. in pure unit tests).
     *
     * `internal` so [ReviewPanel] can still call it directly when it needs the
     * platform fallback specifically (e.g. for unknown file names with no
     * extension that should still be resolved by name pattern).
     */
    internal fun resolveFileTypeIconFromPlatform(fileName: String): IconKey {
        // Guard against calls outside the IntelliJ application context (e.g., in
        // pure unit tests or during early plugin initialization). On IntelliJ
        // Platform 2026.1+, ApplicationManager.getApplication() throws
        // IllegalStateException("Application is not initialized") when the
        // application hasn't been created — it does NOT return null. We catch
        // that here and degrade to the default icon. The broader
        // catch (Exception) below remains as a fallback for misbehaving FileType
        // extensions.
        val app = try {
            ApplicationManager.getApplication()
        } catch (_: IllegalStateException) {
            return PlatformIconKeys.FileTypes.Text
        }
        if (app == null) {
            // Belt-and-suspenders: some older platform versions or test stubs
            // may still return null. Kept for defensive compatibility.
            return PlatformIconKeys.FileTypes.Text
        }
        return try {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            val icon = fileType.icon
            if (icon != null) {
                IntelliJIconKey.fromPlatformIcon(icon)
            } else {
                PlatformIconKeys.FileTypes.Text
            }
        } catch (_: Exception) {
            PlatformIconKeys.FileTypes.Text
        }
    }
}