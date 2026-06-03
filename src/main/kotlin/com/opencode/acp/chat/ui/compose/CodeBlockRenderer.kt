@file:OptIn(ExperimentalJewelApi::class)

package com.opencode.acp.chat.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
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

    val editorFontSize = remember {
        try {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).size
        } catch (_: Exception) { 13 }
    }

    val codeFontFamily = FontFamily.Monospace
    val codeTextStyle = TextStyle(
        fontFamily = codeFontFamily,
        fontSize = editorFontSize.sp,
        lineHeight = (editorFontSize * 1.5).sp,
        color = Color(0xFFD4D4D4),
    )

    val lines = remember(content) { content.lines() }
    val lineNumberWidth = remember(lines.size) {
        val digits = lines.size.toString().length
        (digits * 12 + 16).dp
    }

    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(8.dp))
            .fillMaxWidth(),
    ) {
        // Language header with copy button — NOT selectable
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        key = IntelliJIconKey.fromPlatformIcon(AllIcons.Nodes.Artifact),
                        contentDescription = "Code",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFBBBBBB),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = displayName,
                        style = codeTextStyle.copy(fontSize = 12.sp, color = Color(0xFFBBBBBB)),
                    )
                }

                Icon(
                    key = IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Copy),
                    contentDescription = "Copy code",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(content))
                        },
                    tint = Color(0xFFBBBBBB),
                )
            }
        }

        // Line numbers — NOT selectable
        DisableSelection {
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                // Line numbers column
                Column(
                    modifier = Modifier
                        .width(lineNumberWidth)
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            style = codeTextStyle.copy(
                                color = Color(0xFF858585),
                                fontSize = (editorFontSize - 1).sp,
                            ),
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                // Code text — selectable
                SelectionContainer {
                    Text(
                        text = annotatedCode,
                        style = codeTextStyle,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}
