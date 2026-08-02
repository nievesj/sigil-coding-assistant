package com.opencode.acp.intelligence.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Generates a repo-structure markdown file from PSI.
 *
 * Produces: tech stack, module structure, key symbols, source file counts, conventions.
 * Tech stack detection and file enumeration are fully language-agnostic.
 * Symbol extraction uses [PsiNamedElement] which works for ANY language with
 * a PSI parser (Kotlin, Java, C#, C++, Python, Go, Rust, JS/TS, etc.) — no
 * language-specific PSI types required.
 */
class ContextGenerator(private val project: Project) {

    companion object {
        // Maximum number of symbols to collect. Matches the take(100) in
        // collectProjectSymbols — collecting more is wasted work.
        private const val MAX_SYMBOLS = 100

        // Kotlin PSI classes are only present when the Kotlin plugin is installed
        // (IntelliJ IDEA, Android Studio). On Rider, PyCharm, WebStorm, etc. the
        // classes are absent from the classloader and an `is KtFile` check would
        // throw NoClassDefFoundError. Guard with reflection so the class is never
        // loaded when the plugin is unavailable.
        private val kotlinPluginAvailable: Boolean = try {
            Class.forName("org.jetbrains.kotlin.psi.KtFile")
            true
        } catch (_: Throwable) {
            false
        }

        // Java PSI classes (PsiClassOwner, PsiClass) are only present when the
        // Java plugin is installed. On some IDE configurations the plugin
        // classloader cannot resolve com.intellij.psi.search.PsiShortNamesCache
        // (which PsiClassOwner depends on transitively), causing
        // NoClassDefFoundError at the `psiFile is PsiClassOwner` check. Guard
        // with reflection so the class is never loaded when unavailable.
        private val javaPsiAvailable: Boolean = try {
            Class.forName("com.intellij.psi.PsiClassOwner")
            Class.forName("com.intellij.psi.search.PsiShortNamesCache")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Generate the repo-structure markdown.
     * @return The markdown content as a string.
     */
    suspend fun generate(): String {
        val data = readAction {
            collectRepoData()
        }
        return formatRepoStructureMarkdown(data)
    }

    private fun collectRepoData(): RepoData {
        val modules = ModuleManager.getInstance(project).modules.map { it.name }
        val scope = GlobalSearchScope.projectScope(project)
        val topSymbols = collectProjectSymbols(scope)
        val techStack = detectTechStack(scope)
        val sourceFileCounts = countSourceFiles(scope)
        return RepoData(
            projectName = project.name,
            basePath = project.basePath ?: "",
            modules = modules,
            techStack = techStack,
            keySymbols = topSymbols,
            sourceFileCounts = sourceFileCounts,
        )
    }

    /**
     * Collect top-level named symbols from project source files — language-agnostic.
     *
     * Uses [PsiNamedElement] which is implemented by ALL language plugins (Kotlin,
     * Java, C#, C++, Python, Go, Rust, JS/TS, etc.). For Java/Kotlin we get full
     * qualified names via [PsiClass]/[KtClass]; for other languages we get the
     * element name and file path.
     */
    private fun collectProjectSymbols(scope: GlobalSearchScope): List<SymbolEntry> {
        val entries = mutableListOf<SymbolEntry>()
        val basePath = project.basePath

        fun relPath(vfilePath: String): String =
            basePath?.let { b ->
                try {
                    val rel = java.nio.file.Paths.get(b).relativize(java.nio.file.Paths.get(vfilePath))
                    rel.toString().replace(java.io.File.separatorChar, '/')
                } catch (_: Exception) {
                    // Relativization failed (e.g., file is outside the project base path).
                    // Return a placeholder instead of the raw absolute path to avoid
                    // disclosing absolute filesystem paths in the committed context file (CWE-200).
                    "<external>"
                }
            } ?: "<external>"

        // All source file extensions we can parse PSI for.
        // If a language plugin isn't installed, PsiManager.findFile returns null
        // and the file is silently skipped — graceful degradation.
        val extensions = listOf(
            "kt", "java", "kts",                       // JVM
            "cs",                                       // C#
            "cpp", "cc", "cxx", "c", "h", "hpp",      // C/C++
            "py",                                       // Python
            "go",                                       // Go
            "rs",                                       // Rust
            "ts", "tsx", "js", "jsx",                  // JS/TS
            "rb",                                       // Ruby
            "php",                                      // PHP
            "swift",                                    // Swift
            "scala", "groovy",                          // Other JVM
            "lua", "dart", "ex", "exs", "erl", "zig",  // Misc
            "vue", "svelte",                             // Web frameworks
        )

        for (ext in extensions) {
            // Early-exit once we have enough symbols — avoids loading PSI for
            // every source file in large monorepos (the output is truncated to
            // 100 anyway, so collecting more is wasted work).
            if (project.isDisposed || entries.size >= MAX_SYMBOLS) return entries
            val files = FilenameIndex.getAllFilesByExt(project, ext, scope)
            for (vfile in files) {
                if (project.isDisposed || entries.size >= MAX_SYMBOLS) return entries
                val psiFile = PsiManager.getInstance(project).findFile(vfile) ?: continue
                val rel = relPath(vfile.path)

                // --- Java/Kotlin: use language-specific PSI for full qualified names ---
                if (kotlinPluginAvailable && psiFile is KtFile) {
                    PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
                        .filter { it.parent is KtFile }
                        .forEach { cls ->
                            entries.add(
                                SymbolEntry(
                                    cls.name ?: "<anonymous>", rel,
                                    cls.fqName?.asString() ?: cls.name ?: "", "class",
                                )
                            )
                        }
                    PsiTreeUtil.findChildrenOfType(psiFile, KtObjectDeclaration::class.java)
                        .filter { it.parent is KtFile }
                        .forEach { obj ->
                            if (obj.isCompanion()) return@forEach
                            entries.add(
                                SymbolEntry(
                                    obj.name ?: "<anonymous>", rel,
                                    obj.fqName?.asString() ?: obj.name ?: "", "object",
                                )
                            )
                        }
                    continue // KtFile handled, skip generic traversal
                }

                if (javaPsiAvailable && psiFile is PsiClassOwner) {
                    try {
                        PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
                            .filter { it.parent is PsiFile || it.parent is PsiClassOwner }
                            .forEach { cls ->
                                entries.add(
                                    SymbolEntry(
                                        cls.name ?: "<anonymous>", rel,
                                        cls.qualifiedName ?: cls.name ?: "", "class",
                                    )
                                )
                            }
                    } catch (e: NoClassDefFoundError) {
                        // Java PSI classes vanished at runtime (classloader issue) —
                        // skip this file's Java-specific extraction but continue
                        // with the generic traversal below for other languages.
                    }
                    continue // Java file handled (or skipped), avoid generic traversal
                }

                // --- All other languages: generic PsiNamedElement traversal ---
                // Find direct children of the file that are PsiNamedElement.
                // This catches top-level class/struct/enum/function declarations
                // in C#, C++, Python, Go, Rust, etc. without needing language-specific PSI types.
                // We use PsiTreeUtil.findChildrenOfType with a filter for direct children
                // of the file (parent is PsiFile) to avoid the O(depth) parent chain walk
                // and the risk of misclassifying nested elements as top-level.
                // INVARIANT: genericCounter MUST reset to 0 for EACH file (it's declared
                // inside the for-file loop). The distinctBy key is (qualifiedName to file),
                // so the per-file counter reset ensures two files can both have "Foo#0"
                // without colliding (they differ by file). If this counter is ever hoisted
                // to project-scope, the distinctBy key would need to include the counter
                // value uniquely per-project, or symbols would be wrongly deduped.
                var genericCounter = 0
                val topLevelNamed = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
                    .filter { element ->
                        // Only top-level: direct parent is the file, or parent's parent is the file
                        // (some languages wrap declarations in non-named containers)
                        val parent = element.parent
                        parent is PsiFile || (parent != null && parent.parent is PsiFile)
                    }
                for (elem in topLevelNamed) {
                    val name = elem.name ?: continue
                    // Skip trivial names (constructors, anonymous, etc.)
                    if (name.isBlank() || name == "<anonymous>") continue
                    entries.add(SymbolEntry(name, rel, "$name#${genericCounter++}", "symbol"))
                }
            }
        }
        return entries.distinctBy { it.qualifiedName to it.file }.sortedBy { it.name }.take(100)
    }

    /**
     * Count source files by extension — language-agnostic.
     */
    private fun countSourceFiles(scope: GlobalSearchScope): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val extensions = listOf(
            "kt", "kts", "java",                         // JVM
            "cs",                                         // C#
            "cpp", "cc", "cxx", "c", "h", "hpp",        // C/C++
            "py",                                         // Python
            "go",                                         // Go
            "rs",                                         // Rust
            "ts", "tsx", "js", "jsx", "mjs", "cjs",      // JS/TS
            "rb",                                         // Ruby
            "php",                                        // PHP
            "swift",                                      // Swift
            "scala", "groovy", "clj",                     // Other JVM
            "lua", "dart", "ex", "exs", "erl", "zig",    // Misc
            "vue", "svelte",                             // Web frameworks
        )
        for (ext in extensions) {
            if (project.isDisposed) return counts.toSortedMap()
            val files = FilenameIndex.getAllFilesByExt(project, ext, scope)
            if (files.isNotEmpty()) {
                counts[ext] = files.size
            }
        }
        return counts.toSortedMap()
    }

    /**
     * Detect tech stack — language-agnostic.
     */
    private fun detectTechStack(scope: GlobalSearchScope): List<String> {
        val stack = mutableListOf<String>()
        val basePath = project.basePath ?: return stack
        val baseDir = java.io.File(basePath)

        // Build systems
        if (java.io.File(baseDir, "build.gradle.kts").exists() || java.io.File(baseDir, "build.gradle").exists()) {
            stack.add("Gradle")
        }
        if (java.io.File(baseDir, "pom.xml").exists()) {
            stack.add("Maven")
        }
        if (java.io.File(baseDir, "package.json").exists()) {
            stack.add("Node.js")
        }
        if (java.io.File(baseDir, "Cargo.toml").exists()) {
            stack.add("Rust/Cargo")
        }
        if (java.io.File(baseDir, "go.mod").exists()) {
            stack.add("Go")
        }
        if (java.io.File(baseDir, "requirements.txt").exists() || java.io.File(baseDir, "pyproject.toml").exists()) {
            stack.add("Python")
        }
        if (java.io.File(baseDir, "Gemfile").exists()) {
            stack.add("Ruby")
        }
        if (java.io.File(baseDir, "composer.json").exists()) {
            stack.add("PHP")
        }
        if (java.io.File(baseDir, "Package.swift").exists()) {
            stack.add("Swift")
        }
        if (java.io.File(baseDir, "mix.exs").exists()) {
            stack.add("Elixir")
        }
        if (try {
                java.nio.file.Files.newDirectoryStream(baseDir.toPath(), "*.{csproj,sln}")
                    .use { it.iterator().hasNext() }
            } catch (_: java.io.IOException) {
                false
            }
        ) {
            stack.add(".NET/C#")
        }
        if (java.io.File(baseDir, "CMakeLists.txt").exists()) {
            stack.add("CMake")
        }

        // Languages — detected via source file presence (PSI-aware)
        val langMap = linkedMapOf(
            "kt" to "Kotlin",
            "java" to "Java",
            "cs" to "C#",
            "cpp" to "C++",
            "c" to "C",
            "py" to "Python",
            "go" to "Go",
            "rs" to "Rust",
            "ts" to "TypeScript",
            "tsx" to "TypeScript (React)",
            "js" to "JavaScript",
            "jsx" to "JavaScript (React)",
            "rb" to "Ruby",
            "php" to "PHP",
            "swift" to "Swift",
            "scala" to "Scala",
            "groovy" to "Groovy",
            "clj" to "Clojure",
            "lua" to "Lua",
            "dart" to "Dart",
            "ex" to "Elixir",
            "erl" to "Erlang",
            "zig" to "Zig",
            "vue" to "Vue",
            "svelte" to "Svelte",
        )
        for ((ext, lang) in langMap) {
            if (project.isDisposed) return stack.distinct()
            if (FilenameIndex.getAllFilesByExt(project, ext, scope).isNotEmpty()) {
                if (lang !in stack) stack.add(lang)
            }
        }

        return stack.distinct()
    }
}

/**
 * Collected repository data used to render the markdown context file.
 */
internal data class RepoData(
    val projectName: String,
    val basePath: String,
    val modules: List<String>,
    val techStack: List<String>,
    val keySymbols: List<SymbolEntry>,
    val sourceFileCounts: Map<String, Int> = emptyMap(),
)

internal data class SymbolEntry(
    val name: String,
    val file: String,
    val qualifiedName: String,
    val kind: String, // "class", "object", "symbol"
)

/**
 * Pure markdown formatter for the repo-structure context file.
 */
internal fun formatRepoStructureMarkdown(data: RepoData): String {
    val sb = StringBuilder()
    sb.appendLine("# Repository Structure: ${data.projectName}")
    sb.appendLine()
    sb.appendLine("> Auto-generated by Sigil's `/generate-context` command. Do not edit manually.")
    sb.appendLine()
    sb.appendLine("## Tech Stack")
    sb.appendLine()
    if (data.techStack.isEmpty()) {
        sb.appendLine("- (not detected)")
    } else {
        data.techStack.forEach { sb.appendLine("- $it") }
    }
    sb.appendLine()
    sb.appendLine("## Modules")
    sb.appendLine()
    if (data.modules.isEmpty()) {
        sb.appendLine("- (single module)")
    } else {
        data.modules.forEach { sb.appendLine("- $it") }
    }
    sb.appendLine()
    sb.appendLine("## Source Files")
    sb.appendLine()
    if (data.sourceFileCounts.isEmpty()) {
        sb.appendLine("- (none detected)")
    } else {
        sb.appendLine("| Extension | Count |")
        sb.appendLine("|-----------|-------|")
        data.sourceFileCounts.forEach { (ext, count) ->
            sb.appendLine("| .$ext | $count |")
        }
    }
    sb.appendLine()
    sb.appendLine("## Key Symbols")
    sb.appendLine()
    sb.appendLine("| Name | File | Qualified Name | Kind |")
    sb.appendLine("|------|------|----------------|------|")
    if (data.keySymbols.isEmpty()) {
        sb.appendLine("| (no symbols found) | | | |")
    } else {
        data.keySymbols.forEach { s ->
            val safeName = s.name.replace("|", "\\|").replace("`", "\\`").replace("\n", " ")
            val safeFile = s.file.replace("|", "\\|").replace("`", "\\`").replace("\n", " ")
            val safeQName = s.qualifiedName.replace("|", "\\|").replace("`", "\\`").replace("\n", " ")
            // Escape the kind column too — it's currently hardcoded to "class"/"object"/"symbol",
            // but defense-in-depth: if it ever becomes PSI-derived, this prevents markdown injection.
            val safeKind = s.kind.replace("|", "\\|").replace("`", "\\`").replace("\n", " ")
            sb.appendLine("| `${safeName}` | `${safeFile}` | `${safeQName}` | ${safeKind} |")
        }
    }
    sb.appendLine()
    sb.appendLine("## Conventions")
    sb.appendLine()
    sb.appendLine("- This file is included in every prompt via `opencode.json` `instructions` glob.")
    sb.appendLine("- Regenerate with `/generate-context` after major structural changes.")
    sb.appendLine("- Use `psi_find_symbol` for on-demand symbol search across all languages.")
    sb.appendLine()
    return sb.toString()
}