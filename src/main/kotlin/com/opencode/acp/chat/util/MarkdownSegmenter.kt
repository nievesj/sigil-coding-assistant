package com.opencode.acp.chat.util

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.parser.Parser
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A segment of rendered message content — either HTML text or a code block Editor.
 */
sealed class ContentSegment {
    /** HTML-formatted text content. */
    data class Html(val html: String) : ContentSegment()

    /**
     * A code block rendered with a real IntelliJ Editor.
     * @param editor the created EditorEx — must be released via [dispose] when no longer needed
     * @param component the JComponent to embed in the UI
     */
    data class Code(
        val content: String,
        val language: String,
        val editor: EditorEx,
        val component: JComponent
    ) : ContentSegment() {
        /** Release the editor back to EditorFactory. MUST be called when the component is removed. */
        fun dispose() {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}

/** Language display names for the code block header. */
private fun languageDisplayName(language: String): String = when (language.lowercase().trim()) {
    "kt", "kotlin" -> "Kotlin"
    "java" -> "Java"
    "py", "python" -> "Python"
    "js", "javascript" -> "JavaScript"
    "ts", "typescript" -> "TypeScript"
    "tsx" -> "TSX"
    "jsx" -> "JSX"
    "go" -> "Go"
    "rs", "rust" -> "Rust"
    "rb", "ruby" -> "Ruby"
    "c" -> "C"
    "cpp", "c++", "h", "hpp" -> "C++"
    "cs", "csharp" -> "C#"
    "scala" -> "Scala"
    "swift" -> "Swift"
    "php" -> "PHP"
    "sql" -> "SQL"
    "xml", "html" -> "HTML"
    "css" -> "CSS"
    "json" -> "JSON"
    "yaml", "yml" -> "YAML"
    "md", "markdown" -> "Markdown"
    "sh", "bash", "shell", "zsh" -> "Shell"
    "dockerfile", "docker" -> "Dockerfile"
    "gradle" -> "Gradle"
    "toml" -> "TOML"
    "properties" -> "Properties"
    "diff" -> "Diff"
    "plain", "text", "" -> ""
    else -> language.take(20).uppercase()
}

/**
 * Parses markdown and renders it into segments: HTML text + code block editors.
 * Code blocks use a **real IntelliJ Editor** (via EditorFactory) for full IDE rendering:
 * line numbers, gutter, syntax highlighting, indent guides, selection, etc.
 */
object MarkdownSegmenter {

    private val parser = Parser.builder().build()

    /**
     * Parse markdown and produce a list of content segments.
     * Text segments use themed HTML; code blocks use real IntelliJ Editor instances.
     */
    fun segment(markdown: String, project: Project?): List<ContentSegment> {
        val document = parser.parse(markdown)
        val segments = mutableListOf<ContentSegment>()
        val textParts = mutableListOf<String>()
        var lastEndOffset = 0

        visitCodeBlocks(document) { node ->
            val textBefore = markdown.substring(lastEndOffset, node.startOffset)
            if (textBefore.isNotBlank()) {
                textParts.add(textBefore)
            }

            val codeContent = node.contentChars.normalizeEOL()
            val language = node.info.toString().trim()
            if (codeContent.isNotEmpty()) {
                if (textParts.isNotEmpty()) {
                    segments.add(ContentSegment.Html(ChatColors.buildThemedHtml(renderMarkdownToHtml(textParts.joinToString("")))))
                    textParts.clear()
                }
                val (editor, component) = createIdeEditor(codeContent, language, project)
                segments.add(ContentSegment.Code(codeContent, language, editor, component))
            }

            lastEndOffset = node.endOffset
        }

        val remaining = markdown.substring(lastEndOffset)
        if (remaining.isNotBlank()) {
            textParts.add(remaining)
        }

        if (textParts.isNotEmpty()) {
            segments.add(ContentSegment.Html(ChatColors.buildThemedHtml(renderMarkdownToHtml(textParts.joinToString("")))))
        }

        if (segments.isEmpty()) {
            segments.add(ContentSegment.Html(ChatColors.buildThemedHtml(renderMarkdownToHtml(markdown))))
        }

        return segments
    }

    /**
     * Create a **real IntelliJ Editor** that looks identical to the IDE editor.
     * Uses EditorFactory.createEditor() — NOT EditorTextField.
     *
     * Configures every available public editor setting to match the IDE appearance:
     * - Line numbers in the gutter
     * - Proper color scheme (Darcula, Light, etc.)
     * - Read-only (viewer mode)
     * - Full IDE chrome visible
     */
    private fun createIdeEditor(
        code: String,
        language: String,
        project: Project?
    ): Pair<EditorEx, JComponent> {
        val ext = languageToExtension(language)
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        val doc = EditorFactory.getInstance().createDocument(code)
        val scheme = EditorColorsManager.getInstance().globalScheme

        // Create a REAL editor — this gives us the full IDE rendering pipeline
        val editor = EditorFactory.getInstance().createEditor(doc, project, fileType, true) as EditorEx

        // Bind the global color scheme so all tokens render with the active theme
        editor.colorsScheme = scheme
        editor.reinitSettings()

        // ── Editor Settings — match IDE defaults exactly ──
        editor.settings.apply {
            // Line numbers — ON
            isLineNumbersShown = true

            // Right margin — hidden
            isRightMarginShown = false

            // Caret row — OFF (read-only, no caret)
            isCaretRowShown = false

            // Folding — OFF for cleaner display in chat
            isFoldingOutlineShown = false

            // Additional lines — minimal padding
            additionalLinesCount = 0
            additionalColumnsCount = 0

            // Block cursor — OFF (read-only)
            isBlockCursor = false
        }

        // Make it read-only at the document level too
        editor.isViewer = true

        // ── Component appearance ──
        val editorComponent = editor.contentComponent
        editorComponent.isOpaque = true
        editorComponent.background = scheme.defaultBackground
        editorComponent.border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(4))

        // Make the entire editor component (including gutter) match
        editor.component.isOpaque = true
        editor.component.background = scheme.defaultBackground

        // ── Height calculation — match IDE line height ──
        val lineCount = code.count { it == '\n' } + 1
        val lineHeight = scheme.getFont(EditorFontType.PLAIN).size + JBUI.scale(6)
        val headerHeight = JBUI.scale(24) // language label header
        val preferredHeight = lineCount * lineHeight + JBUI.scale(8) + headerHeight

        // ── Wrap in a panel with language header ──
        val displayName = languageDisplayName(language)
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = scheme.defaultBackground
            border = JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12), JBUI.scale(2), JBUI.scale(12))

            if (displayName.isNotEmpty()) {
                val langLabel = com.intellij.ui.components.JBLabel(displayName).apply {
                    font = JBUI.Fonts.smallFont().deriveFont(java.awt.Font.PLAIN)
                    foreground = UIUtil.getContextHelpForeground()
                }
                add(langLabel, BorderLayout.EAST)
            }
        }

        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = scheme.defaultBackground
            preferredSize = Dimension(0, preferredHeight)
            maximumSize = Dimension(Int.MAX_VALUE, preferredHeight)
            add(headerPanel, BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }

        return Pair(editor, wrapper)
    }

    /** Map language name to file extension for syntax highlighting. */
    private fun languageToExtension(language: String): String = when (language.lowercase().trim()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "groovy", "gvy" -> "gvy"
        "go" -> "go"
        "rust", "rs" -> "rs"
        "ruby", "rb" -> "rb"
        "c" -> "c"
        "cpp", "c++", "h", "hpp" -> "cpp"
        "csharp", "cs" -> "cs"
        "scala" -> "scala"
        "swift" -> "swift"
        "php" -> "php"
        "sql" -> "sql"
        "xml", "html" -> "html"
        "css" -> "css"
        "json" -> "json"
        "yaml", "yml" -> "yml"
        "markdown", "md" -> "md"
        "bash", "sh", "shell", "zsh" -> "sh"
        "dockerfile", "docker" -> "Dockerfile"
        "gradle" -> "gradle"
        "toml" -> "toml"
        "properties" -> "properties"
        "diff" -> "diff"
        "plain", "text", "" -> "txt"
        else -> language.take(10)
    }

    /** Visit all FencedCodeBlock nodes in the AST. */
    private fun visitCodeBlocks(node: com.vladsch.flexmark.util.ast.Node, block: (FencedCodeBlock) -> Unit) {
        if (node is FencedCodeBlock) {
            block(node)
        }
        var child = node.firstChild
        while (child != null) {
            visitCodeBlocks(child, block)
            child = child.next
        }
    }
}
