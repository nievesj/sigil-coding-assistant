package com.opencode.acp.chat.util

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI.CurrentTheme
import java.awt.Color
import javax.swing.UIManager

/**
 * Theme-aware color constants for the OpenCode chat interface.
 * All colors are resolved at runtime from the active IntelliJ theme.
 * Use fun() instead of val to re-evaluate on every access (supports future theme-change listeners).
 */
object ChatColors {

    // ── Backgrounds ──

    fun panelBg(): Color = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground

    fun editorBg(): Color = UIManager.getColor("Editor.background") ?: JBColor(0xFFFFFF, 0x2B2D30)

    fun toolWindowBg(): Color = CurrentTheme.ToolWindow.background()

    // ── Text ──

    fun textPrimary(): Color = UIUtil.getLabelForeground()

    fun textSecondary(): Color = CurrentTheme.Label.disabledForeground()

    fun textMuted(): Color = UIUtil.getContextHelpForeground()

    fun textLink(): Color = UIManager.getColor("Link.foreground") ?: JBColor(0x589DF6, 0x589DF6)

    // ── Borders ──

    fun border(): Color = JBColor.border()

    fun separator(): Color = JBColor.namedColor("Separator.foreground", JBColor.border())

    // ── Semantic ──

    fun error(): Color = JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))

    fun success(): Color = JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))

    // ── Buttons ──

    fun buttonBg(): Color = UIManager.getColor("Button.background") ?: JBColor.PanelBackground

    // ── Star favorites (theme-independent for visual consistency) ──

    val starFavorite: Color = Color(0xD4, 0xA0, 0x17)

    val starHover: Color = Color(0xFF, 0xD7, 0x00)

    fun starMuted(): Color = CurrentTheme.Label.disabledForeground()

    // ── Editor Font Helpers ──

    /** Get the editor's configured code font name (e.g. "JetBrains Mono"). */
    fun codeFontName(): String = try {
        EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).fontName
    } catch (_: Exception) { "JetBrains Mono, Menlo, Consolas, monospace" }

    /** Get the editor's configured code font size. */
    fun codeFontSize(): Int = try {
        EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).size
    } catch (_: Exception) { 13 }

    /** Get the editor's default background color (matches actual editor area). */
    fun editorDefaultBg(): String = try {
        val bg = EditorColorsManager.getInstance().globalScheme.defaultBackground
        toCssHex(bg)
    } catch (_: Exception) {
        toCssHex(editorBg())
    }

    /** Get the editor's default foreground color. */
    fun editorDefaultFg(): String = try {
        val fg = EditorColorsManager.getInstance().globalScheme.defaultForeground
        toCssHex(fg)
    } catch (_: Exception) {
        toCssHex(textPrimary())
    }

    // ── Helpers ──

    /** Convert Color to CSS hex (#RRGGBB) without alpha bleed. */
    fun toCssHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.rgb)

    /**
     * Build a themed HTML wrapper for assistant message content.
     * Code blocks use the actual editor font and colors from EditorColorsManager.
     * Body text uses the platform UI font.
     *
     * CAUTION: Only uses CSS properties Swing's HTMLEditorKit supports.
     * Avoid: margin, padding, line-height, border-radius — these crash Swing's CSS parser.
     */
    fun buildThemedHtml(content: String): String {
        val codeFont = codeFontName()
        val codeSize = codeFontSize()
        val editorFg = editorDefaultFg()
        val editorBgCss = editorDefaultBg()
        val bg = toCssHex(toolWindowBg())
        val fg = toCssHex(textPrimary())
        val linkFg = toCssHex(textLink())
        val borderCss = toCssHex(border())
        return """
            <html><head><style>
                body {
                    background: $bg;
                    color: $fg;
                    font-family: 'Segoe UI', 'Helvetica Neue', sans-serif;
                    font-size: ${codeSize}pt;
                }
                pre {
                    background: $editorBgCss;
                    color: $editorFg;
                    border: 1px solid $borderCss;
                    font-family: '$codeFont', 'Menlo', 'Consolas', monospace;
                    font-size: ${codeSize}pt;
                }
                code {
                    background: $editorBgCss;
                    color: $editorFg;
                    font-family: '$codeFont', 'Menlo', 'Consolas', monospace;
                    font-size: ${codeSize}pt;
                }
                a { color: $linkFg; }
                h1, h2, h3 { font-weight: bold; }
                h1 { font-size: ${codeSize + 5}pt; }
                h2 { font-size: ${codeSize + 3}pt; }
                h3 { font-size: ${codeSize + 1}pt; }
            </style></head><body>$content</body></html>
        """.trimIndent()
    }
}
