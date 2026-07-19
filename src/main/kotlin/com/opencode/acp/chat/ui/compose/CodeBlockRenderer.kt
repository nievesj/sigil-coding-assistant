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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import java.awt.datatransfer.StringSelection
import org.jetbrains.jewel.foundation.code.highlighting.LocalCodeHighlighter
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import com.opencode.acp.chat.ui.theme.ChatTheme

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

    // Use a local mutableStateOf updated via a LaunchedEffect keyed on (content, lang)
    // to avoid the plain-text flash that collectAsState(AnnotatedString(content))
    // produces when lang changes mid-stream (the Flow restarts and the initial
    // AnnotatedString briefly shows unhighlighted text before the new result arrives).
    val highlighter = LocalCodeHighlighter.current
    var highlighted by remember { mutableStateOf(AnnotatedString(content)) }
    LaunchedEffect(content, lang) {
        highlighter.highlight(content, lang).collect { annotated ->
            highlighted = annotated
        }
    }

    val editorScheme = remember {
        EditorColorsManager.getInstance().globalScheme
    }
    val editorFontSize = remember {
        editorScheme.getFont(EditorFontType.PLAIN)?.size ?: 13
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

    // Derive everything from highlighted to avoid streaming race
    val lines = remember(highlighted) { highlighted.text.lines() }
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
            .clip(ChatTheme.shapes.codeBlockCornerRadius)
            .background(editorBgColor)
            .fillMaxWidth(),
    ) {
        // Language header with copy button
        DisableSelection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChatTheme.dims.codeHeaderPaddingH, vertical = ChatTheme.dims.codeHeaderPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        key = FileTypeIcons.iconKeyForLanguageId(language.orEmpty()),
                        contentDescription = displayName,
                        modifier = Modifier.size(ChatTheme.dims.codeLanguageIconSize),
                        tint = ChatTheme.colors.component.codeCopyIcon,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = displayName,
                        style = codeTextStyle.copy(fontSize = ChatTheme.fonts.codeLanguageLabel, color = ChatTheme.colors.component.codeCopyIcon),
                    )
                }

                Icon(
                    key = AllIconsKeys.Actions.Copy,
                    contentDescription = "Copy code",
                    modifier = Modifier
                        .size(ChatTheme.dims.codeCopyIconSize)
                        .clickable {
                            coroutineScope.launch {
                                // StringSelection is an AWT class; clipboard writes
                                // may require the EDT on some platforms. Dispatch
                                // to Main to be safe.
                                withContext(Dispatchers.Main) {
                                    clipboard.setClipEntry(ClipEntry(StringSelection(content)))
                                }
                            }
                        },
                    tint = ChatTheme.colors.component.codeLanguageLabel,
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
                    text = highlighted,
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
