# Technical Design Document: Chat Display Revamp with Theme Integration

> **Status:** Draft (Updated after adversarial review)
> **Last Updated:** 2026-06-02
> **Related docs:** `DESIGN.md`, `docs/tdd/model-favorites.md`

---

## 1. TL;DR

Revamp the chat display to feel like a native IntelliJ feature. Extract a `ChatColors` object (all theme-aware via `UIManager`/`JBUI.CurrentTheme`), replace HTML rendering in user messages with `JBLabel` (with HTML wrapping for multi-line), refactor assistant message HTML to use a themed template with correct hex color formatting, apply `JBUI.Borders` for DPI-aware spacing, update all components (`MessageListComponent`, `InputAreaComponent`, `ChatPanel`, `ConnectionBannerComponent`, `PermissionPromptComponent`, `ControlBarComponent`) to use theme tokens, and add `allowRawHtml(false)` to Flexmark for XSS prevention.

---

## 2. Context & Scope

### 2.1 Current State

The chat display has several issues:
- **Hardcoded colors** (`0x2b2d31`, `0xdbdee1`, `0xa9b7c6`, `0x589df6`, `0xf0c674`) that break on Light and High Contrast themes
- **HTML rendering crashes** — Swing's CSS parser throws `NullPointerException` on `border-radius`, `margin`, `padding` in inline styles
- **Inconsistent spacing** — uses `EmptyBorder` (not DPI-aware) and hardcoded pixel values
- **No font consistency** — raw `Font.PLAIN, 12f` instead of `JBUI.Fonts`/`JBFont`
- **`allowRawHtml` not disabled** on Flexmark parser — potential XSS from LLM output
- **`HTMLEditorKit.DEFAULT_CSS` client property** set on `JEditorPane` — conflicts with themed HTML template
- **`Color.toString()` injected into CSS** — `buildAssistantHtml()` uses raw color string interpolation that produces invalid CSS
- **`Integer.toHexString()` with alpha bleed** — hex color conversion includes alpha channel, breaking CSS colors
- **`ControlBarComponent` hardcoded colors** — 5+ JBColor instances that don't adapt to Light/High Contrast themes

### 2.2 Problem Statement

The chat UI looks like a third-party plugin bolted onto IntelliJ, not a native feature. Users see color mismatches with their selected theme, inconsistent spacing, occasional rendering crashes, and potential XSS vectors. Every component needs to use IntelliJ's native LaF tokens.

---

## 3. Goals & Non-Goals

### Goals

1. **Theme-adaptive colors** — All colors derived from `UIManager`/`JBUI.CurrentTheme`, no hardcoded values.
2. **Crash-free rendering** — Fix all CSS rendering bugs; correct hex conversion (`String.format("#%06X", color.rgb & 0xFFFFFF)`); remove `Color.toString()` from CSS.
3. **XSS prevention** — Set `allowRawHtml(false)` on Flexmark parser.
4. **DPI-aware spacing** — All dimensions use `JBUI.scale()`, borders use `JBUI.Borders`.
5. **Consistent typography** — Use `JBUI.Fonts`/`JBFont` throughout.
6. **Theme-change resilience** — Colors resolve on creation; note that theme changes require re-render via `LafManagerListener`.
7. **ControlBarComponent themed** — Model dropdown colors use theme tokens, not hardcoded.

### Non-Goals

- Custom message bubbles with `paintComponent()` (too complex for this iteration)
- Markdown rendering engine replacement (keep Flexmark, fix XSS + styling)
- User message editing/deletion
- Message reordering or search
- Live theme-change listener (tracked as future improvement)

---

## 4. Proposed Solution

### 4.1 Summary

Create a centralized `ChatColors` object that resolves all colors from IntelliJ's theme at runtime. Fix the themed HTML template to use correct CSS hex format and avoid `Color.toString()`. Enable `allowRawHtml(false)` on Flexmark. Replace hardcoded borders with `JBUI.Borders`. Update all components to use `ChatColors`. Add `ControlBarComponent` to the scope.

### 4.2 New File: `ChatColors.kt`

```kotlin
package com.opencode.acp.chat.util

import com.intellij.ui.JBColor
import com.intellij.ui.JBUI
import com.intellij.ui.util.UIUtil
import com.intellij.util.ui.JBUI.CurrentTheme
import java.awt.Color
import javax.swing.UIManager

object ChatColors {
    // Backgrounds
    fun panelBg(): Color = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
    fun editorBg(): Color = UIManager.getColor("Editor.background") ?: JBColor(0xFFFFFF, 0x2B2D30)
    fun toolWindowBg(): Color = CurrentTheme.ToolWindow.background()

    // Text
    fun textPrimary(): Color = UIUtil.getLabelForeground()
    fun textSecondary(): Color = CurrentTheme.Label.disabledForeground()
    fun textMuted(): Color = UIUtil.getContextHelpForeground()
    fun textLink(): Color = UIManager.getColor("Link.foreground") ?: JBColor(0x589DF6, 0x589DF6)

    // Borders
    fun border(): Color = JBColor.border()
    fun separator(): Color = JBColor.namedColor("Separator.foreground", JBColor.border())

    // Semantic
    fun error(): Color = JBColor.namedColor("Component.errorFocusColor", JBColor(0xDB4437, 0xE55341))
    fun success(): Color = JBColor.namedColor("Component.successForeground", JBColor(0x499C54, 0x6BBE50))

    // Buttons
    fun buttonBg(): Color = UIManager.getColor("Button.background") ?: JBColor.PanelBackground
    fun buttonHover(): Color = UIManager.getColor("Button.default.hoverBackground") ?: buttonBg()

    // Star favorites (theme-independent for visual consistency)
    val starFavorite: Color = Color(0xD4, 0xA0, 0x17)
    val starHover: Color = Color(0xFF, 0xD7, 0x00)
    fun starMuted(): Color = CurrentTheme.Label.disabledForeground()

    /** Convert Color to CSS hex (#RRGGBB) without alpha bleed. */
    fun toCssHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.rgb)
}
```

Key changes from pre-review version:
- All `get()` properties changed to `fun()` calls to ensure re-evaluation on every access (theme changes)
- `toCssHex()` added — the critical fix for the hex color bug
- `buttonBg()` and `buttonHover()` added
- `starFavorite`/`starHover` kept as `val Color` (intentional constants)

### 4.3 Refactored: `MessageListComponent.kt`

**User messages:**
- `JBLabel` with escaped text, wrapped in `<html>` for multi-line wrapping
- Background: `ChatColors.editorBg()`, `isOpaque = true`
- Border: `JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))`
- Font: `JBUI.Fonts.label()`

**Assistant messages:**
- `JEditorPane` with `contentType = "text/html"` for rendered markdown
- Strip all inline styles from Flexmark output before rendering
- Wrap in themed HTML template using `ChatColors.toCssHex()` for colors
- **Remove** `HTMLEditorKit.DEFAULT_CSS` client property (line 104-107 of current code)
- Border: `JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(12))`
- Background: transparent (`isOpaque = false`)

**Tool pills:**
- `JPanel` with `BorderLayout`
- Icon from `ToolStatusDisplay.icon()` (IntelliJ platform icons)
- Label: `JBLabel` with `JBUI.Fonts.smallFont()` (11px), `ChatColors.textSecondary()`
- Border: `JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(16), JBUI.scale(2), JBUI.scale(8))`
- Background: `ChatColors.panelBg()`

**Thinking pills:**
- `JPanel` with `BorderLayout`
- Label: "Thinking..." in italic, `ChatColors.textMuted()`
- Border: `JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(16), JBUI.scale(2), JBUI.scale(8))`

### 4.4 Refactored: `InputAreaComponent.kt`

- Text area background: `ChatColors.editorBg()`
- Text area font: `JBUI.Fonts.label()` (13px)
- Border: `JBUI.Borders.compound(JBUI.Borders.customLineTop(ChatColors.border()), JBUI.Borders.empty(JBUI.scale(8)))`
- Send button: `ChatColors.buttonBg()`, `ChatColors.textPrimary()`
- Cancel button: `ChatColors.buttonBg()`, `ChatColors.error()`
- Button border: `JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6))`
- Convert all `BorderFactory.createEmptyBorder()` to `JBUI.Borders.empty()`
- Convert all raw `Font()`/`deriveFont()` to `JBUI.Fonts.label()`

### 4.5 Refactored: `ChatPanel.kt`

- Background: `ChatColors.toolWindowBg()`
- Top border: `JBUI.Borders.customLineTop(ChatColors.border())`
- South panel: `JBUI.Borders.empty(JBUI.scale(8))`

### 4.6 Refactored: `ConnectionBannerComponent.kt`

- Use `ChatColors.textMuted()` for disconnected label foreground
- Use `ChatColors.error()` for error state
- Use `ChatColors.success()` for connected state
- Border: `JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))`
- Background: transparent or `ChatColors.toolWindowBg()`

### 4.7 Refactored: `PermissionPromptComponent.kt`

- Background: `ChatColors.panelBg()`
- Border: `JBUI.Borders.compound(JBUI.Borders.customLineTop(ChatColors.border()), JBUI.Borders.empty(JBUI.scale(8)))`
- Title text: `ChatColors.textPrimary()`
- Description text: `ChatColors.textMuted()`
- Buttons: standard IntelliJ button style via `UIManager.getColor("Button.background")`
- Icon: keep `AllIcons.Actions.Lightning`

### 4.8 Refactored: `ControlBarComponent.kt`

- Header label (ProviderHeader): `ChatColors.textLink()` instead of `JBColor(0x589df6, 0x589df6)`
- Star favorite: `ChatColors.starFavorite` instead of `JBColor(0xf0c674, 0xf0c674)`
- Star muted: `ChatColors.starMuted()` instead of `JBColor(0x6b6b6b, 0x6b6b6b)`
- Star hover: `ChatColors.starHover` instead of `JBColor(0xffd700, 0xffd700)`
- Model label foreground: `ChatColors.textPrimary()` instead of `list.foreground`
- All hardcoded `JBColor(...)` replaced with `ChatColors.*` calls
- Remove `import com.intellij.ui.JBColor` if no longer needed directly

### 4.9 Fixed: `ChatUtils.kt` — Markdown Rendering

**Enable XSS protection:**
```kotlin
val parser = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
    .allowRawHtml(false)  // Prevent XSS from LLM output
    .build()
```

**Strip unsafe HTML attributes:**
```kotlin
return renderer.render(document)
    .replace(Regex(""" style\s*=\s*"[^"]*""""), "")
    .replace(Regex(""" style\s*=\s*'[^']*'"""), "")
    .replace(Regex("""\s+class\s*=\s*"[^"]*""""), "")
    .replace(Regex("""\s+class\s*=\s*'[^']*'"""), "")
```

### 4.10 Fixed: Themed HTML Template (replaces `buildAssistantHtml()`)

```kotlin
fun buildThemedHtml(content: String): String {
    val bg = ChatColors.toolWindowBg()
    val fg = ChatColors.textPrimary()
    val codeBg = ChatColors.editorBg()
    val linkFg = ChatColors.textLink()
    val borderCss = "#${ChatColors.toCssHex(ChatColors.border())}"
    return """
        <html><head><style>
            body {
                background: ${ChatColors.toCssHex(bg)};
                color: ${ChatColors.toCssHex(fg)};
                font-family: 'JetBrains Sans', 'Segoe UI', 'Helvetica Neue', sans-serif;
                font-size: 13px;
                line-height: 1.5;
            }
            pre {
                background: ${ChatColors.toCssHex(codeBg)};
                border: 1px solid $borderCss;
                padding: 8px;
                font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
                font-size: 12px;
            }
            code {
                font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
                font-size: 12px;
            }
            a { color: ${ChatColors.toCssHex(linkFg)}; }
            h1, h2, h3 { font-weight: bold; color: ${ChatColors.toCssHex(fg)}; }
            h1 { font-size: 18px; }
            h2 { font-size: 16px; }
            h3 { font-size: 14px; }
            ul, ol { margin: 8px 0; padding-left: 24px; }
            li { margin-bottom: 4px; }
        </style></head><body>${renderedHtml}</body></html>
    """.trimIndent()
}
```

Key fixes:
- Uses `ChatColors.toCssHex()` to avoid alpha bleed (`color.rgb & 0xFFFFFF`)
- Uses `ChatColors` dotted accessors instead of raw `UIManager.getColor()`
- No `Color.toString()` injection
- Complete font-family fallback chains

### 4.11 Cleanup: `HTMLEditorKit.DEFAULT_CSS` Removal

Remove the client property set in `MessageListComponent.createAssistantMessageComponent()`:
```kotlin
// REMOVE:
putClientProperty(HTMLEditorKit.DEFAULT_CSS,
    "body { background: #2b2d31; color: #dbdee1; ... }")
```
This conflicts with the themed template. The template handles all styling.

### 4.12 File List

| File | Status | Changes |
|------|--------|---------|
| `chat/util/ChatColors.kt` | NEW | Theme-aware color constants with `toCssHex()` |
| `chat/ui/MessageListComponent.kt` | MODIFIED | ChatColors, JBUI.Borders, remove DEFAULT_CSS, fix HTML |
| `chat/ui/InputAreaComponent.kt` | MODIFIED | ChatColors, JBUI.Borders, JBUI.Fonts |
| `chat/ui/ChatPanel.kt` | MODIFIED | ChatColors, JBUI.Borders |
| `chat/ui/ConnectionBannerComponent.kt` | MODIFIED | ChatColors |
| `chat/ui/PermissionPromptComponent.kt` | MODIFIED | ChatColors |
| `chat/ui/ControlBarComponent.kt` | MODIFIED | ChatColors for stars, headers, labels |
| `chat/util/ChatUtils.kt` | MODIFIED | `allowRawHtml(false)`, strip single-quoted styles |
| `DESIGN.md` | NEW | Style system documentation |

---

## 5. Open Questions

1. **Theme change at runtime** — Colors are resolved lazily (functions, not properties), so re-rendering on theme change requires iterating all messages and calling `setText()` with freshly computed HTML. A `LafManagerListener` should be added but is out of scope for this iteration. Documented as future improvement.

2. **`ChatColors.toCssHex()` accessor vs property** — Using `fun()` calls ensures re-evaluation, but the star constants (`starFavorite`, `starHover`) are `val Color` — they won't change. This is intentional for visual consistency.

3. **`JBFont.mono()` availability** — Not all IntelliJ versions have `JBFont.mono()`. If unavailable, fall back to `font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(12f).toFloat())`.

---

## 6. Document History

| Date | Change | Author |
|------|--------|--------|
| 2026-06-02 | Initial draft | — |
| 2026-06-02 | Post-adversarial-review update: fixed hex color format (String.format with mask), removed Color.toString() from CSS, added allowRawHtml(false), added ControlBarComponent to scope, added button colors to ChatColors, added DEFAULT_CSS cleanup, added font-family fallback chains, added LAF-manager listener note, added single-quote style attribute regex | — |
