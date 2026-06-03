@file:OptIn(ExperimentalJewelApi::class, ExperimentalComposeUiApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.datatransfer.StringSelection
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

/**
 * Language name mapping: markdown code fence language → language
 * identifier accepted by the Jewel CodeHighlighter.
 */
private fun mapLanguageId(lang: String): String {
    return when (lang.lowercase()) {
        "js" -> "JavaScript"
        "ts" -> "TypeScript"
        "jsx" -> "JavaScript"
        "tsx" -> "TypeScript"
        "py" -> "Python"
        "rb" -> "Ruby"
        "rs" -> "Rust"
        "kt" -> "Kotlin"
        "kts" -> "Kotlin"
        "cs" -> "C#"
        "cpp" -> "C++"
        "objc" -> "Objective-C"
        "sh", "bash", "zsh" -> "Shell"
        "yml" -> "YAML"
        "md" -> "Markdown"
        "dockerfile" -> "Dockerfile"
        "json" -> "JSON"
        "xml" -> "XML"
        "html" -> "HTML"
        "css" -> "CSS"
        "sql" -> "SQL"
        "go" -> "Go"
        "java" -> "Java"
        "c" -> "C"
        "text", "plaintext", "txt" -> ""
        else -> lang // pass through as-is
    }
}

/**
 * Map language name to IntelliJ platform icon for the code block header.
 */
private fun languageIcon(lang: String): javax.swing.Icon = when (lang.lowercase()) {
    "javascript", "js", "jsx"     -> AllIcons.FileTypes.JavaScript
    "typescript", "ts", "tsx"     -> AllIcons.FileTypes.JavaScript
    "css", "scss", "less"         -> AllIcons.FileTypes.Css
    "java"                        -> AllIcons.FileTypes.Java
    "kotlin", "kt", "kts"         -> AllIcons.Language.Kotlin
    "python", "py"                -> AllIcons.Language.Python
    "ruby", "rb"                  -> AllIcons.Language.Ruby
    "rust", "rs"                  -> AllIcons.Language.Rust
    "go"                          -> AllIcons.Language.GO
    "scala"                       -> AllIcons.Language.Scala
    "php"                         -> AllIcons.Language.Php
    "html", "htm"                 -> AllIcons.FileTypes.Html
    "xml"                         -> AllIcons.FileTypes.Xml
    "json"                        -> AllIcons.FileTypes.Json
    "yaml", "yml"                 -> AllIcons.FileTypes.Yaml
    "shell", "bash", "zsh", "sh"  -> AllIcons.Nodes.Console
    "sql"                         -> AllIcons.FileTypes.Text
    else                          -> AllIcons.FileTypes.Text
}

@Composable
fun ChatFencedCodeBlock(
    content: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val lang = language.orEmpty().let { mapLanguageId(it) }
    val displayName = language.orEmpty().ifBlank { "Code" }

    val annotatedCode by LocalCodeHighlighter.current
        .highlight(content, lang)
        .collectAsState(AnnotatedString(content))

    val editorScheme = remember {
        EditorColorsManager.getInstance().globalScheme
    }
    val editorFontSize = remember {
        try { editorScheme.getFont(EditorFontType.PLAIN).size } catch (_: Exception) { 13 }
    }
    val editorFgColor = remember {
        Color(editorScheme.defaultForeground.rgb)
    }
    val editorBgColor = remember {
        Color(editorScheme.defaultBackground.rgb)
    }
    val lineNumberColor = remember {
        val awtColor = editorScheme.getColor(EditorColors.LINE_NUMBERS_COLOR)
            ?: editorScheme.defaultForeground
        Color(awtColor.rgb)
    }

    val codeTextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = editorFontSize.sp,
        lineHeight = (editorFontSize * 1.5).sp,
        color = editorFgColor,
    )

    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    // Derive everything from annotatedCode to avoid streaming race
    val lines = remember(annotatedCode) { annotatedCode.text.lines() }
    val lineCount = lines.size

    // Line number text: "1\n2\n3\n..." — same line count as code, uses same TextStyle
    val lineNumberText = remember(lineCount) {
        buildString {
            for (i in 1..lineCount) {
                if (i > 1) append('\n')
                append(i)
            }
        }
    }

    val lineNumberWidth = remember(lineCount) {
        val digits = lineCount.toString().length
        (digits * 12 + 16).dp
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(editorBgColor)
            .fillMaxWidth(),
    ) {
        // Language header with copy button
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(languageIcon(language.orEmpty())),
                        contentDescription = displayName,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF6BBE50),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = displayName,
                        style = codeTextStyle.copy(fontSize = 12.sp, color = Color(0xFF6BBE50)),
                    )
                }

                Icon(
                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Copy),
                    contentDescription = "Copy code",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipEntry(StringSelection(content)))
                            }
                        },
                    tint = Color(0xFFBBBBBB),
                )
            }
        }

        // Code area — line numbers and code use identical TextStyle so the layout
        // engine places baselines at exactly the same Y positions.
        // softWrap = false on both ensures no hidden line wrapping.
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            // Line numbers — NOT selectable
            DisableSelection {
                Text(
                    text = lineNumberText,
                    style = codeTextStyle.copy(color = lineNumberColor),
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(lineNumberWidth)
                        .padding(end = 8.dp),
                )
            }

            // Code — selectable, scrollable for long lines
            SelectionContainer {
                Text(
                    text = annotatedCode,
                    style = codeTextStyle,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 4.dp, end = 12.dp),
                )
            }
        }
    }
}
