---
name: OpenCode IntelliJ Chat
description: Visual identity for the OpenCode chat interface within IntelliJ IDEA. Theme-adaptive, uses IntelliJ's native LaF tokens.
version: alpha
colors:
  panel-background: "Panel.background"  # UIManager key
  editor-background: "Editor.background"
  tool-window-background: "ToolWindow.background"
  text-primary: "Label.foreground"
  text-secondary: "Label.disabledForeground"
  text-muted: "Label.infoForeground"  # Falls back to UIUtil.getContextHelpForeground()
  text-link: "Link.foreground"
  border: "Component.borderColor"
  selection-background: "List.selectionBackground"
  selection-foreground: "List.selectionForeground"
  error: "Component.errorFocusColor"
  success: "Component.successFocusColor"
  separator: "Separator.foreground"
  hover-background: "ActionButton.hoverBackground"
  button-background: "Button.background"
  button-hover: "Button.default.hoverBackground"
  button-pressed: "Button.default.pressedBackground"
  star-favorite: "#D4A017"  # Gold — intentional constant for visual consistency
  star-hover: "#FFD700"     # Bright gold
  star-muted: "Label.disabledForeground"
  thinking-background: "Editor.background"
  thinking-border: "Component.borderColor"
  thinking-text: "Label.infoForeground"
  code-background: "Editor.background"
  code-border: "Component.borderColor"
  code-text: "Label.foreground"
typography:
  body:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 13px
  body-bold:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 13px
    fontWeight: bold
  heading-1:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 18px
    fontWeight: bold
  heading-2:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 16px
    fontWeight: bold
  heading-3:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 14px
    fontWeight: bold
  code:
    fontFamily: "JetBrains Mono, Menlo, Consolas, monospace"
    fontSize: 12px
  caption:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 11px
  timestamp:
    fontFamily: "JetBrains Sans, Segoe UI, Helvetica Neue, sans-serif"
    fontSize: 10px
rounded:
  sm: 4px
  md: 6px
  lg: 8px
  message-bubble: 8px    # Same as lg — use JBUI.scale(8)
  input-field: 6px       # Use JBUI.scale(6)
  pill: 4px              # Use JBUI.scale(4)
spacing:
  xs: 2px
  sm: 4px
  md: 8px
  lg: 12px
  xl: 16px
  message-vertical: 6px
  message-horizontal: 12px
  message-inner: 12px
  input-padding: 8px
  section-gap: 16px
components:
  message-user:
    backgroundColor: "Editor.background"
    textColor: "Label.foreground"
    rounded: 8px
    padding: 12px
  message-assistant:
    backgroundColor: "transparent"  # Inherits from parent
    textColor: "Label.foreground"
    rounded: 8px
    padding: 12px
  input-area:
    backgroundColor: "Editor.background"
    textColor: "Label.foreground"
    rounded: 6px
    borderColor: "Component.borderColor"
  button-send:
    backgroundColor: "Button.background"
    textColor: "Label.foreground"
    rounded: 4px
  button-send-hover:
    backgroundColor: "Button.default.hoverBackground"
  button-cancel:
    backgroundColor: "Button.background"
    textColor: "Component.errorFocusColor"
  pill-tool:
    backgroundColor: "Panel.background"
    textColor: "Label.disabledForeground"
    rounded: 4px
  pill-thinking:
    backgroundColor: "Editor.background"
    textColor: "Label.infoForeground"
    borderColor: "Component.borderColor"
    rounded: 4px
  code-block:
    backgroundColor: "Editor.background"
    textColor: "Label.foreground"
    borderColor: "Component.borderColor"
    rounded: 4px
  link:
    textColor: "Link.foreground"
---

## Overview

The OpenCode chat interface follows IntelliJ's native Look and Feel (LaF) to feel like a built-in IDE feature rather than a third-party plugin. Every color, font, spacing, and border value is derived from IntelliJ's theme tokens — no hardcoded hex values. The UI adapts seamlessly to Darcula (dark), Light, and High Contrast themes.

**Important:** All tokens in the YAML front matter are `UIManager.getColor()` keys or literal hex values. They are resolved at runtime. The prose below provides context for how to apply them.

### Theme Change Handling

Colors are resolved at component creation time. To handle runtime theme changes (user switches from Darcula to Light), the chat panel must listen for `LafManagerListener` and re-render all message HTML and update component backgrounds. This is tracked in the TDD.

## Colors

All colors are resolved at runtime from the active IntelliJ theme using `UIManager.getColor()`, `JBUI.CurrentTheme.*`, or `UIUtil` methods. Fallback values are provided for compatibility.

- **Panel Background** — `UIManager.getColor("Panel.background")`. The main background for assistant messages and the chat area.
- **Editor Background** — `UIManager.getColor("Editor.background")`. Used for user message bubbles and the input area — matches the editor's content area.
- **Text Primary** — `UIUtil.getLabelForeground()`. All body text, headings, and message content.
- **Text Secondary** — `JBUI.CurrentTheme.Label.disabledForeground()`. Tool pill labels, timestamps, and captions.
- **Text Muted** — `UIUtil.getContextHelpForeground()`. Thinking content, thinking labels.
- **Border** — `JBUI.CurrentTheme.CustomFrameDecorations.borderColor()` or `JBColor.border()`. Component borders, separator lines, code block borders.
- **Error** — `JBColor.namedColor("Component.errorFocusColor", ...)`. Cancel button, error messages.
- **Success** — `JBColor.namedColor("Component.successFocusColor", ...)`. Completed tool status.
- **Star Favorite** — Constant gold `0xD4A017` for visual consistency across themes.
- **Star Hover** — Constant bright gold `0xFFD700`.
- **Star Muted** — Derived from `Label.disabledForeground`.

## Typography

All fonts use IntelliJ's platform fonts resolved via `JBUI.Fonts.*` or `JBFont.*`. These methods resolve to the platform's default UI font (JetBrains Sans on recent builds, Inter or Dialog on older ones). Never use raw `Font()` constructors.

| Token | API | Size |
|-------|-----|------|
| body | `JBUI.Fonts.label()` | 13px |
| body-bold | `JBUI.Fonts.label().asBold()` | 13px |
| heading-1 | `JBFont.h1().asBold()` | 18px |
| heading-2 | `JBFont.h2().asBold()` | 16px |
| heading-3 | `font.deriveFont(Font.BOLD, JBUI.scaleFontSize(14f).toFloat())` | 14px |
| code | `JBFont.mono()` or `font.deriveFont(JBUI.scaleFontSize(12f).toFloat())` | 12px |
| caption | `JBUI.Fonts.smallFont()` | 11px |
| timestamp | `font.deriveFont(JBUI.scaleFontSize(10f).toFloat())` | 10px |

> **Note:** `JBFont.mono()` resolves to JetBrains Mono when available. Fall through to `Menlo, Consolas, monospace` in HTML templates.

## Layout

### Spacing Scale
All values are in logical pixels — use `JBUI.scale(N)` at runtime.

| Token | Logical px | Usage |
|-------|-----------|-------|
| xs | 2 | Tight gaps between closely coupled elements |
| sm | 4 | Default gap between related elements |
| md | 8 | Standard gap, message inner padding |
| lg | 12 | Section separation, message horizontal padding |
| xl | 16 | Major section separation, tool pill left indent |

### Message Layout
Messages use `BoxLayout.Y_AXIS` for vertical stacking. Each message component has:
- Outer margin: `JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))`
- Inner content padding: `JBUI.Borders.empty(JBUI.scale(12))` for assistant messages

### Input Area Layout
- Border: `JBUI.Borders.compound(JBUI.Borders.customLineTop(ChatColors.border), JBUI.Borders.empty(JBUI.scale(8)))`
- Minimum height: `JBUI.scale(40)`, maximum height: `JBUI.scale(100)`
- Send/cancel buttons positioned at bottom-right

## Elevation & Depth

IntelliJ's LaF handles elevation natively. No custom shadows or drop shadows.

- **Flat by default:** All components are flat unless the LaF provides elevation.
- **Borders for separation:** Use `JBUI.Borders.customLineTop()` and `Separator` components.
- **No drop shadows:** IntelliJ's LaF does not use drop shadows on Swing components.

## Shapes

All corner radii are DPI-scaled via `JBUI.scale()`. These are used only for custom-painted components (message bubbles) and `JBUI.Borders.customLine()` with corner radius — they do NOT apply to Swing's default rectangular borders.

- **Message bubbles:** `JBUI.scale(8)` — rounded rectangles for user/assistant messages (requires custom `paintComponent()`).
- **Input field:** `JBUI.scale(6)` — if custom-painted.
- **Pills:** `JBUI.scale(4)` — tool and thinking pills (if custom-painted).
- **Buttons:** `JBUI.scale(4)` — send, cancel buttons.

## Components

### Chat Panel
- Background: `UIManager.getColor("ToolWindow.background")` or `JBUI.CurrentTheme.ToolWindow.background()`
- Top border: 1px `Component.borderColor` via `JBUI.Borders.customLineTop()`
- Layout: `BorderLayout` (connectionBanner=NORTH, messageList=CENTER, southPanel=SOUTH)

### Message List
- Background: inherits from chat panel (transparent)
- Scroll pane: `JBScrollPane` with standard IntelliJ scrollbar
- Layout: `BoxLayout.Y_AXIS` for vertical message stacking

### User Message
- Implementation: `JBLabel` with escaped text
- For multi-line messages: wrap in `<html>` tags (Swing labels need HTML for text wrapping)
- Background: `UIManager.getColor("Editor.background")` with `isOpaque = true`
- Border: `JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12))`
- Text: `UIUtil.getLabelForeground()`, `JBUI.Fonts.label()` (13px)

### Assistant Message
- Implementation: `JEditorPane` with `contentType = "text/html"` for rendered markdown
- Background: transparent (`isOpaque = false`) — inherits panel color
- Border: `JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(12))`
- HTML template: uses runtime-resolved theme colors (see §4.9)
- **Important:** Remove `HTMLEditorKit.DEFAULT_CSS` client property — conflicts with themed template

### Input Area
- Background: `UIManager.getColor("Editor.background")`
- Border: `JBUI.Borders.compound(JBUI.Borders.customLineTop(Component.borderColor), JBUI.Borders.empty(JBUI.scale(8)))`
- Text area: `JBTextArea` with `JBUI.Fonts.label()` (13px), wraps text
- Send button: `UIManager.getColor("Button.background")` background, `Label.foreground` text
- Cancel button: `UIManager.getColor("Button.background")` background, `Component.errorFocusColor` text

### Tool Pill
- Background: `UIManager.getColor("Panel.background")`
- Text: `Label.disabledForeground`, `JBUI.Fonts.smallFont()` (11px)
- Left indent: `JBUI.scale(16)` via `JBUI.Borders.emptyLeft()`
- Icon: IntelliJ platform icons (AllIcons.Actions.Execute, Checked, Cancel)

### Thinking Pill
- Background: `UIManager.getColor("Editor.background")`
- Text: `Label.infoForeground`, italic, `JBUI.Fonts.smallFont()` (11px)
- Border: 1px `Component.borderColor`

### Permission Prompt
- Background: inherits from chat panel
- Border: `JBUI.Borders.compound(JBUI.Borders.customLineTop(Component.borderColor), JBUI.Borders.empty(JBUI.scale(8)))`
- Buttons: standard IntelliJ button styling via `UIManager` defaults

### Control Bar (Agent/Model/Thinking Selectors)
- Background: inherits from chat panel
- Labels: `Label.foreground`
- Headers (in model dropdown): `Label.infoForeground`
- Star unmuted: `Label.foreground`
- Star favorite: uniform gold `#D4A017`
- Star muted: `Label.disabledForeground`
- All dropdown items use `List.selectionBackground`/`List.selectionForeground` for selection

## Do's and Don'ts

### Do
- Use `UIManager.getColor()`, `JBUI.CurrentTheme.*`, or `JBColor.namedColor()` for ALL colors
- Use `JBUI.scale()` for all pixel dimensions
- Use `JBUI.Borders.*` for all borders and insets
- Use `JBUI.Fonts.*` or `JBFont.*` for all typography
- Use `JBColor.isBright()` for theme detection
- Set `isOpaque = false` on custom-painted components
- Use `JBUI.Borders.empty()` for padding/margins
- Test with Darcula, Light, and High Contrast themes
- Convert colors to CSS with `String.format("#%06X", color.rgb & 0xFFFFFF)` for hex
- Use `allowRawHtml(false)` on Flexmark's `Parser.builder()` for XSS prevention
- Register `LafManagerListener` to re-render on theme change

### Don't
- Never hardcode hex colors (e.g., `0x2b2d31`) — always resolve from theme
- Never use `Font()` constructor directly — use `JBUI.Fonts` or `JBFont`
- Never use `BorderFactory.createEmptyBorder()` — use `JBUI.Borders.empty()`
- Never use `javax.swing.border.EmptyBorder` — not DPI-aware
- Never use `java.awt.Color` directly for theme-dependent values — always `JBColor` or `UIManager`
- Never inject `Color.toString()` or raw `Integer.toHexString(color.rgb)` into CSS — alpha bleed
- Never skip `allowRawHtml(false)` on Flexmark parser — XSS vector
- Never assume the current theme is dark
