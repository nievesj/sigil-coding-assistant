package com.opencode.acp.chat.util

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorGutter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
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
     * A code block rendered with an EditorTextField.
     * EditorTextField self-manages editor lifecycle via removeNotify().
     */
    data class Code(
        val content: String,
        val language: String,
        val component: JComponent
    ) : ContentSegment()
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
 * Code blocks use EditorTextField for embedded IDE editor rendering with line numbers.
 */
object MarkdownSegmenter {

    private val parser = Parser.builder().build()

    /**
     * Parse markdown and produce a list of content segments.
     * Text segments use themed HTML; code blocks use IntelliJ EditorTextField instances.
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
                val component = createCodeEditor(codeContent, language, project)
                segments.add(ContentSegment.Code(codeContent, language, component))
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
     * Create an EditorTextField for code display with full IDE editor colors,
     * syntax highlighting, line numbers, and gutter.
     */
    private fun createCodeEditor(
        code: String,
        language: String,
        project: Project?
    ): JComponent {
        val ext = languageToExtension(language)
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        val doc = EditorFactory.getInstance().createDocument(code)
        val scheme = EditorColorsManager.getInstance().globalScheme

        val editorField = object : EditorTextField(
            doc,
            project,
            fileType,
            true,   // isViewer — read-only
            false   // isFontShouldBeScaled
        ) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()

                // Bind the global color scheme
                editor.colorsScheme = scheme
                editor.reinitSettings()

                // ── Line numbers and gutter ──
                editor.settings.isLineNumbersShown = true
                editor.settings.isFoldingOutlineShown = false
                editor.settings.additionalLinesCount = 0
                editor.settings.additionalColumnsCount = 0
                editor.settings.isRightMarginShown = false
                editor.settings.isCaretRowShown = false
                editor.settings.isBlockCursor = false

                // Force gutter component visible — needed for in-memory documents
                val gutter = editor.gutter
                (gutter as? JComponent)?.isVisible = true

                // Make the inner editor component opaque with the scheme background
                editor.component.isOpaque = true
                editor.component.background = scheme.defaultBackground
                editor.component.border = JBUI.Borders.empty()

                return editor
            }

            // Display-only — no focus
            override fun isFocusable(): Boolean = false
        }

        editorField.setOneLineMode(false)
        editorField.isOpaque = true
        editorField.background = scheme.defaultBackground
        editorField.border = JBUI.Borders.empty()

        // Only constrain height — width is free for BoxLayout to stretch
        val lineCount = code.count { it == '\n' } + 1
        val lineHeight = scheme.getFont(EditorFontType.PLAIN).size + JBUI.scale(4)
        val preferredHeight = lineCount * lineHeight + JBUI.scale(16)
        editorField.preferredSize = Dimension(400, preferredHeight)
        // Don't set maximumSize — BoxLayout distributes extra space based on it

        // Language label header
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

        // Wrap: header + editor in a bordered panel
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = scheme.defaultBackground
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(0))
            add(headerPanel, BorderLayout.NORTH)
            add(editorField, BorderLayout.CENTER)
        }

        return wrapper
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
