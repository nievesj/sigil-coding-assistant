# Technical Design Document: Central UI State & Theme

> **Status:** Draft
> **Last Updated:** 2026-06-10
> **Related docs:** [Mid-Generation Steering](mid-generation-steering.md), [AGENTS.md](../../AGENTS.md)
> **Review:** [First review](#10-document-history) found 20+ issues — all addressed in this revision.

---

## 1. TL;DR

Create two centralized objects — `ChatInputState` (a sealed interface modeling all valid input-area modes) and `ChatTheme` (a `@Composable`-scoped object that reads semantic colors from IntelliJ's `UIManager` via `retrieveColorOrUnspecified()` and holds chat-specific accent colors, dimensions, shapes, fonts, and animation constants). Today, these decisions are scattered across 20+ composable files with ~200 hardcoded `Color(0xFF…)` literals, ad-hoc `isStreaming && permissionPrompt == null && …` boolean chains, and no single place to audit or change them. This TDD consolidates them into single-source-of-truth objects, eliminating the "two thinking bubbles" bug class and making theme changes a one-line edit.

---

## 2. Context & Scope

### 2.1 Current State

**UI state is fragmented.** The question "what can the user do right now?" is answered by combining 5+ independent `StateFlow`s across 2 files:

| File | Variable | Type | Purpose |
|------|----------|------|---------|
| `ChatScreen.kt:381` | `inputEnabled` | `Boolean` (inline val) | AND of `connectionState`, `permissionPrompt`, `selectionPrompt` |
| `ChatScreen.kt:131` | `isStreaming` | `StateFlow<Boolean>` | Collected from ViewModel, used by streaming indicator & button mode |
| `ChatViewModel.kt:50` | `_isStreaming` | `MutableStateFlow<Boolean>` | Set from 9+ places (sendMessage, cancel, steerMessage, SSE signals, session switch) |
| `ChatViewModel.kt:53` | `_permissionPrompt` | `MutableStateFlow<PermissionPrompt?>` | Blocks input when non-null |
| `ChatViewModel.kt:56` | `_selectionPrompt` | `MutableStateFlow<SelectionPrompt?>` | Blocks input when non-null |
| `ChatViewModel.kt:37` | `connectionState` | `StateFlow<ConnectionState>` | Forwarded from service (DISCONNECTED blocks input) |

These evolve independently with no type safety. Invalid combinations are possible: `isStreaming == true` while `connectionState == DISCONNECTED`, or `permissionPrompt != null` while `isStreaming == true`. Each composable reacts to individual flows independently, producing inconsistent UI states.

**The "two thinking bubbles" bug.** `MessageList.kt:498` renders `ThinkingIndicator()` when `message.isStreaming && !hasThinking && !hasToolCall && no Text/Code/Table parts`. Meanwhile, `CollapsibleThinkingPill()` renders for each `MessagePart.Thinking`. When the SSE event order produces a timing gap (streaming started, thinking part not yet added), or when a second assistant message is auto-created while the first is still streaming (SessionState.kt:500-514), both indicators can appear for the same message. The root cause: `isStreaming` is a boolean on the message, and the thinking indicator condition is an ad-hoc 4-way AND. There's no state machine that says "this message is in the Thinking phase — show exactly one indicator."

**Visual constants are duplicated.** There are ~200 hardcoded color literals across 20+ files. The same semantic color is defined differently in different files:

| Semantic color | Files using it | Literal |
|---------------|---------------|---------|
| Green accent (primary) | `InputArea.kt:470`, `CodeBlockRenderer.kt:188`, `ContextIndicator.kt:47`, `ContextPanel.kt:159`, `MessageList.kt:324,517`, `TableRenderer.kt:54,105`, `TodoListPanel.kt:33` | `0xFF6BBE50` |
| Green accent (light/bright) | `InputArea.kt:935` (send button icon tint) | `0xFF4EAF4E` |
| Stop red (errors, stop button) | `InputArea.kt:471`, `ContextIndicator.kt:49`, `ContextPanel.kt:76` | `0xFFE5534B` |
| Delete red (removed lines) | `MessageList.kt:477,653,907`, `ReviewPanel.kt:238`, `ToolPill.kt:155,459` | `0xFFFF7B72` |
| Tool pill bg (near-white alpha) | `ToolPill.kt:92` | `0x0CFFFFFF` |
| Interrupted divider | `MessageList.kt:487,494` | `0xFF444444` |
| Star/context dimmed | `ModelPickerPanel.kt:65`, `Selectors.kt:50` | `0xFF606060` |
| Star gold (favorite) | `ModelPickerPanel.kt:64,384` | `0xFFE5C100` |
| Selected list bg | `ModelPickerPanel.kt:67`, `Selectors.kt:55` | `0xFF2D4F6D` |
| Hover bg | `ModelPickerPanel.kt:66`, `Selectors.kt:54` | `0xFF363636` |
| Dark surface (todo, table hover, palette) | `TodoListPanel.kt:29`, `TableRenderer.kt:58`, `SlashCommandPalette.kt:42` | `0xFF252525` |
| Dark card surface | `MessageList.kt:429,449,462,692,801,839` | `0xFF2D2D2D` |
| Table header bg | `TableRenderer.kt:57` | `0xFF2A2A2A` |

These are all defined independently per file — there's no single place to audit or change them. Changing the theme requires hunting through 20+ files and updating dozens of scattered literals. Note: `0xFFE5534B` (stop red) and `0xFFFF7B72` (delete red) are *intentionally* different — the delete/salmon color is the OpenCode desktop app convention for removed lines, while the stop button uses a darker red. Both are correctly consolidated under separate names in `ChatTheme`.

**IntelliJ theme integration is inconsistent.** Some files already use `retrieveColorOrUnspecified()` (Jewel's bridge to IntelliJ's `UIManager`) for theme-aware colors — `SessionSidebar.kt` (34 calls), `MessageList.kt` (5 calls), `ReviewPanel.kt` (3 calls). Other files hardcode the same semantic colors. For example, `SessionSidebar.kt:502` reads `List.selectionBackground` from the IDE theme, while `Selectors.kt:55` hardcodes `0xFF2D4F6D` for the same semantic purpose. The chat should respect the user's IDE theme for standard UI colors (backgrounds, text, borders, selection) while using hardcoded values only for chat-specific accents (green, red, blue).

### 2.2 Problem Statement

1. **UI state is ad-hoc and combinatorially explosive.** Every new feature that affects input enablement (steering, clear-all, future edit-mode) adds another `&& condition` to `inputEnabled`. Invalid state combinations are not prevented at compile time.
2. **Visual constants are scattered and inconsistent.** Same semantic color has different hex values in different files. Dimension constants are duplicated. Theme changes require editing 20+ files.
3. **The "two thinking bubbles" bug class.** Because there's no single source of truth for "what phase is this message in?", rendering conditions are ad-hoc boolean chains that can overlap or gap.
4. **IntelliJ theme integration is partial.** Some composables read from `UIManager`, others hardcode the same colors. The chat doesn't consistently respect the user's IDE theme.

---

## 3. Goals & Non-Goals

### Goals
1. `ChatInputState` sealed interface replaces the ad-hoc `inputEnabled` boolean with an explicit state machine — every valid input mode is a named state carrying its own data
2. `ChatTheme` consolidates every color, dimension, shape, font, and animation constant into a single file — semantic colors read from IntelliJ's `UIManager` via `retrieveColorOrUnspecified()`, chat-specific accents are hardcoded
3. All composables reference `ChatTheme` instead of hardcoded literals
4. The "two thinking bubbles" bug is eliminated — `MessageRenderPhase` replaces the ad-hoc `isStreaming && !hasThinking && …` condition
5. No behavior changes — this is a pure refactor. The UI looks and behaves identically

### Non-Goals
- **Light theme / theme switching** — `ChatTheme` reads from the IDE's current theme (which is dark in the standard IntelliJ setup). A future TDD could add explicit light-theme color overrides.
- **Jetpack Compose Material Theme integration** — Jewel provides its own theming. `ChatTheme` coexists with Jewel's `JewelTheme` for chat-specific colors. We do not replace Jewel's theme system.
- **Refactoring SessionState or SessionManager** — the internal SSE processing pipeline stays as-is. Only the UI-facing state derivation changes.
- **Changing the visual design** — colors, sizes, and shapes stay the same. We're consolidating, not redesigning.

---

## 4. Proposed Solution

**Create two single-source-of-truth objects: `ChatInputState` (a sealed interface modeling all valid input-area states, computed from the existing StateFlows in ChatViewModel) and `ChatTheme` (a `@Composable`-scoped object that reads semantic colors from IntelliJ's `UIManager` and holds chat-specific accent colors, dimensions, shapes, fonts, and animation constants). Composables switch on `ChatInputState` instead of combining booleans, and reference `ChatTheme` instead of `Color(0xFF…)`. A `MessageRenderPhase` enum replaces the ad-hoc thinking indicator condition, ensuring exactly one visual indicator per message.**

### 4.3 API / Interface Design

**No new server endpoints.** This is entirely client-side.

### 4.5 Technology Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| Language | Kotlin | Existing codebase |
| UI Framework | Compose for Desktop (Jewel) | Existing |
| State | Kotlin sealed interfaces + StateFlow | Existing coroutines |
| Theme | `retrieveColorOrUnspecified()` + `CompositionLocal` | Jewel bridge to IntelliJ UIManager |

### 4.7 Implementation Blueprint

#### 4.7.1 Data Models & Schemas

**A. `ChatInputState` — sealed interface**

```kotlin
package com.opencode.acp.chat.model

/**
 * Exhaustive state machine for the input area.
 * Only one variant is active at a time — impossible to have invalid combinations
 * like "streaming + disconnected" or "prompt + sending".
 *
 * Computed in ChatViewModel from existing StateFlows.
 * Composables switch on this instead of combining booleans.
 */
sealed interface ChatInputState {
    /** Input is completely disabled — no text entry, no buttons. */
    data object Disabled : ChatInputState

    /** Normal idle state — text field active, green Send button when text present. */
    data object Idle : ChatInputState

    /** AI is generating a response. Text field active.
     *  Send button visible when text present (steer), Stop button when empty. */
    data object Streaming : ChatInputState

    /** Tool permission prompt is showing — input disabled, user must respond. */
    data class AwaitingPermission(val prompt: PermissionPrompt) : ChatInputState

    /** Selection/question prompt is showing — input disabled, user must respond. */
    data class AwaitingSelection(val prompt: SelectionPrompt) : ChatInputState
}
```

**State priority** (first match wins):

| Priority | Condition | State |
|----------|-----------|-------|
| 1 | `connectionState != CONNECTED` | `Disabled` |
| 2 | `permissionPrompt != null` | `AwaitingPermission(prompt)` |
| 3 | `selectionPrompt != null` | `AwaitingSelection(prompt)` |
| 4 | `isStreaming` | `Streaming` |
| 5 | (default) | `Idle` |

This priority ensures mutually exclusive states. A `Disabled` state overrides everything — you can't type while disconnected, even if a permission prompt was showing when the connection dropped.

**B. `MessageRenderPhase` — enum**

```kotlin
package com.opencode.acp.chat.model

/**
 * The visual rendering phase of a single message, used ONLY for the standalone
 * ThinkingIndicator at the bottom of the message. Does NOT control per-part
 * rendering (CollapsibleThinkingPill, ToolPill, etc.) — those render in the
 * `for` loop based on part type, which is compositional (multiple indicators
 * per message).
 *
 * Replaces the ad-hoc boolean condition:
 *   `isStreaming && !hasThinking && !hasToolCall && no Text/Code/Table parts`
 *
 * Exactly one phase is active per message per frame for the ThinkingIndicator.
 */
enum class MessageRenderPhase {
    /** Message is streaming with no content parts yet — show spinner + "Thinking…" */
    THINKING,

    /** Message has content (thinking, tool calls, text, code, or table) —
     *  no standalone ThinkingIndicator needed. Per-part indicators render
     *  compositionally in the `for` loop. */
    HAS_CONTENT,

    /** Message is not streaming — render all parts as final */
    COMPLETE,
}
```

**Phase derivation** (computed per message per recomposition):

```kotlin
fun ChatMessage.renderPhase(): MessageRenderPhase {
    if (!isStreaming) return MessageRenderPhase.COMPLETE

    val hasAnyContent = parts.values.any {
        it is MessagePart.Thinking ||
        it is MessagePart.ToolCall ||
        it is MessagePart.Text ||
        it is MessagePart.Code ||
        it is MessagePart.Table
    }

    return if (hasAnyContent) MessageRenderPhase.HAS_CONTENT
            else MessageRenderPhase.THINKING
}
```

This eliminates the two-bubbles bug: `THINKING` can only fire when the message has zero parts. The moment any part arrives (Thinking, ToolCall, Text, etc.), the phase transitions to `HAS_CONTENT`, and the standalone `ThinkingIndicator()` is suppressed. The `for` loop continues to render `CollapsibleThinkingPill` for each `MessagePart.Thinking` — this is compositional, not exclusive.

**Why `HAS_CONTENT` instead of separate `THINKING_CONTENT`/`TOOL_CALL`/`CONTENT` phases:** The current code renders multiple indicators per message (e.g., both `CollapsibleThinkingPill` and `ToolPill` for a message with both Thinking and ToolCall parts). Making `MessageRenderPhase` exclusive (one phase per message) would break this compositional rendering. The enum only needs to answer one question: "should the standalone ThinkingIndicator show?" — `THINKING` means yes, `HAS_CONTENT` means no.

**C. `ChatTheme` — `@Composable` object with `CompositionLocal`**

`ChatTheme` is a `@Composable` function that returns a `ChatThemeData` object. Semantic colors (backgrounds, text, borders, selection, hover) are read from IntelliJ's `UIManager` via `retrieveColorOrUnspecified()` at composition time. Chat-specific accent colors (green, red, blue) are hardcoded constants. This ensures the chat respects the user's IDE theme for standard UI elements while maintaining consistent brand colors.

```kotlin
package com.opencode.acp.chat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.shape.RoundedCornerShape
import androidx.compose.ui.shape.CircleShape
import org.jetbrains.jewel.bridge.retrieveColorOrUnspecified

/**
 * Immutable data class holding all chat UI visual constants.
 * Created once per composition root via [ChatTheme] composable.
 *
 * Semantic colors (backgrounds, text, borders, selection) are read from
 * IntelliJ's UIManager at composition time — they adapt to the user's IDE theme.
 * Chat-specific accent colors (green, red, blue) are hardcoded constants.
 */
@Immutable
data class ChatThemeData(
    val colors: Colors,
    val dims: Dims,
    val fonts: Fonts,
    val fontWeights: FontWeights,
    val shapes: Shapes,
    val animations: Animations,
) {
    @Immutable
    data class Colors(
        // ── Semantic colors (from IntelliJ UIManager) ─────────────────────
        // These adapt to the user's IDE theme (dark, light, high contrast).

        // Backgrounds
        val surfacePrimary: Color,          // "Panel.background" — main background
        val surfaceSecondary: Color,        // "Panel.background" darker variant — input area, sidebar
        val surfaceCard: Color,             // "Panel.background" slightly lighter — cards, code blocks
        val surfaceDark: Color,             // Darker panel — todo, table hover, slash palette
        val surfaceElevated: Color,         // "MenuItem.selectionBackground" — hover backgrounds
        val surfaceTableHeader: Color,      // Between surfaceDark and surfaceCard

        // Text
        val textPrimary: Color,             // "Panel.foreground" — primary text, headings
        val textSecondary: Color,           // "Panel.foreground" slightly dimmed — body text
        val textMuted: Color,               // "Panel.foreground" alpha 0.5 — muted text
        val textDisabled: Color,            // "Label.disabledForeground" — disabled text
        val textInverse: Color,             // White or near-white — text on colored backgrounds

        // Borders & separators
        val borderDefault: Color,           // "Component.borderColor" — standard borders
        val borderSubtle: Color,            // "Component.borderColor" darker — disabled borders
        val borderAccent: Color,            // "Component.borderColor" lighter — tooltip borders

        // Selection & hover
        val selectionBg: Color,             // "List.selectionBackground"
        val selectionFg: Color,             // "List.selectionForeground"
        val hoverBg: Color,                 // "MenuItem.selectionBackground"

        // Links
        val linkColor: Color,               // "Link.activeForeground"

        // Errors
        val errorColor: Color,              // "Component.errorFocusColor"

        // ── Chat-specific accent colors (hardcoded) ───────────────────────
        // These are brand colors that don't change with the IDE theme.

        // Primary palette
        val accentGreen: Color,             // 0xFF6BBE50 — Send button, context OK, completed
        val accentGreenLight: Color,        // 0xFF4EAF4E — Lighter green, send button icon
        val accentBlue: Color,              // 0xFF3574F0 — Links, selections, hover accent
        val stopRed: Color,                 // 0xFFE5534B — Stop button, errors
        val warningYellow: Color,           // 0xFFE5A617 — Warnings, in-progress indicators
        val infoBlue: Color,                // 0xFF589DF6 — Info banners, section headers

        // Context indicator
        val contextGreen: Color,            // 0xFF6BBE50 — 0-50% usage
        val contextYellow: Color,           // 0xFFE5A617 — 50-75% usage
        val contextRed: Color,              // 0xFFE5534B — 75%+ usage
        val contextUnknown: Color,          // 0xFF808080 — No data

        // Code & diffs
        val codeAdded: Color,               // 0xFF7EE787 — Added lines, +N
        val codeDeleted: Color,             // 0xFFFF7B72 — Deleted lines, -N
        val codeModified: Color,            // 0xFFF0C674 — Changed lines, search/fetch accent

        // Inline code
        val inlineCodeBackground: Color,    // Color.Transparent — no background on inline code

        // Block quote
        val blockQuoteLine: Color,          // 0xFF3E3E3E — Left border line
        val blockQuoteText: Color,          // 0xFF9E9E9E — Quoted text color

        // Tool pill
        val pillContainerBg: Color,         // 0x0CFFFFFF — Near-transparent white on dark

        // Transparency overlays
        val overlaySemiTransparent: Color,  // 0x88000000 — 53% black overlay for dialogs
        val highlightBlueAlpha: Color,      // 0x182196F3 — ~9% blue for selected items
        val highlightBlueBorder: Color,     // 0x402196F3 — ~25% blue for hover borders

        // Error / info banner backgrounds
        val bannerErrorBg: Color,           // 0x1ADB4437 — ~10% red background
        val bannerErrorBorder: Color,       // 0x40DB4437 — ~25% red border
        val bannerInfoBg: Color,            // 0x1A589DF6 — ~10% blue background
        val bannerInfoBorder: Color,        // 0x40589DF6 — ~25% blue border

        // Provider accent colors
        val providerAnthropic: Color,       // 0xFFCC785C
        val providerOpenAI: Color,          // 0xFF10A37F
        val providerDeepSeek: Color,        // 0xFF4D6BFE
        val providerGoogle: Color,          // 0xFF4285F4
        val providerOllama: Color,          // 0xFFCCCCCC
        val providerMistral: Color,         // 0xFFDDA63A
        val providerCohere: Color,          // 0xFF39D98A
        val providerGroq: Color,            // 0xFFF55036
        val providerTogether: Color,        // 0xFF6366F1
        val providerFireworks: Color,       // 0xFFFF6B35
        val providerNvidia: Color,          // 0xFF76B900
        val providerOpenRouter: Color,      // 0xFF8B5CF6
        val providerGitHubCopilot: Color,   // 0xFFCCCCCC
        val providerXAI: Color,             // 0xFFCCCCCC
        val providerAlibaba: Color,         // 0xFFFF6A00
        val providerMiniMax: Color,         // 0xFF6366F1
        val providerMoonshot: Color,        // 0xFFCCCCCC
        val providerStepFun: Color,         // 0xFFCCCCCC
        val providerZhipu: Color,           // 0xFF4D6BFE
        val providerKimi: Color,            // 0xFFCCCCCC

        /** Maps server providerId strings to ChatTheme colors. */
        val providerColorMap: Map<String, Color>,

        // File type colors
        val fileKotlin: Color,              // 0xFFA97BFF
        val fileJava: Color,                // 0xFFED8B00
        val fileJavaScript: Color,          // 0xFFF7DF1E
        val fileTypeScript: Color,          // 0xFF3178C6
        val filePython: Color,              // 0xFF3776AB
        val fileRuby: Color,                // 0xFFCC342D
        val fileGo: Color,                  // 0xFF00ADD8
        val fileRust: Color,                // 0xFFCE422B
        val fileHtml: Color,                // 0xFFE44D26
        val fileCss: Color,                 // 0xFF264DE4
        val fileXml: Color,                 // 0xFF0060AC
        val fileJson: Color,                // 0xFFBBBBBB
        val fileYaml: Color,                // 0xFFCB171E
        val fileMarkdown: Color,            // 0xFF519ABA
        val fileSql: Color,                 // 0xFFE38C00
        val fileShell: Color,               // 0xFF4EAA25

        // Tool kind accent colors
        val toolExecute: Color,             // 0xFF3574F0
        val toolEdit: Color,                // 0xFF7EE787
        val toolRead: Color,                // 0xFFBBBBBB
        val toolSearch: Color,              // 0xFFF0C674
        val toolDelete: Color,              // 0xFFFF7B72
        val toolMove: Color,                // 0xFFBBBBBB
        val toolFetch: Color,               // 0xFFF0C674
        val toolThink: Color,               // 0xFF9E9E9E
        val toolSwitchMode: Color,          // 0xFF9E9E9E
        val toolOther: Color,               // 0xFF9E9E9E

        // Session sidebar shimmer
        val sidebarShimmerCreating: Color,  // 0xFFFFC107
        val sidebarShimmerStreaming: Color, // 0xFF4CAF50

        // Splash screen
        val splashConnected: Color,         // 0xFF4CAF50
        val splashError: Color,             // 0xFFF44336
        val splashRetry: Color,             // 0xFFFF9800

        // Task/subagent pills
        val taskAccent: Color,              // 0xFF7EE787
        val taskRunning: Color,             // 0xFF808080
        val taskCompleted: Color,           // 0xFFCCCCCC
        val taskFailed: Color,              // 0xFFFF7B72
        val taskPending: Color,             // 0xFF808080

        // Retry pills
        val retryBg: Color,                 // 0xFF5C4A00
        val retryText: Color,               // 0xFFFFD666
        val retryErrorDetail: Color,        // 0xFFCCAA44

        // Selection prompt
        val selectionCheckboxFill: Color,   // 0xFF2196F3
        val selectionCheckboxBorder: Color, // Color.Gray
        val selectionCustomBullet: Color,   // 0xFF4CAF50

        // Interrupted / divider
        val interruptedDivider: Color,      // 0xFF444444

        // Markdown table
        val tableHeaderText: Color,         // 0xFF6BBE50
        val tableCellText: Color,           // 0xFFD4D4D4
        val tableSeparator: Color,          // 0xFF3E3E3E
        val tableHeaderBg: Color,           // 0xFF2A2A2A
        val tableHoverBg: Color,            // 0xFF252525
        val tableBorder: Color,             // 0xFF3E3E3E
        val tableContainerBg: Color,        // 0xFF1E1E1E

        // Streaming glow animation
        val glowTransparent: Color,         // Color.Transparent
        val glowStart: Color,               // 0xFF4A9EFF alpha 0.15
        val glowPeak: Color,                // 0xFF4A9EFF alpha 0.45
        val glowHot: Color,                 // 0xFF00D4FF alpha 0.85

        // User message bubble
        val userBubbleBg: Color,            // 0xFF3574F0 alpha 0.12
        val userAvatarBorder: Color,        // 0xFF3574F0
        val userAvatarFill: Color,          // 0xFF5E9AFF

        // Permission prompt
        val permissionBorder: Color,        // 0x40808080
        val permissionBg: Color,            // 0x10808080

        // Input area
        val inputBg: Color,                 // 0xFF2B2B2B
        val inputBorder: Color,             // 0xFF3E3E3E
        val inputCursor: Color,             // Color.White
        val inputText: Color,               // 0xFFCCCCCC
        val inputPlaceholder: Color,        // 0xFF808080
        val dragActiveBg: Color,            // 0xFF2E3A2E

        // Attachment thumbnails
        val attachmentBg: Color,            // 0xFF3E3E3E
        val attachmentRemoveIcon: Color,    // 0xFFBBBBBB
        val attachmentImageOverlay: Color,  // 0xFF555555
        val attachmentImageRemove: Color,   // 0xFFCCCCCC
        val attachmentFileSize: Color,      // 0xFF808080

        // Context panel
        val contextPanelLabel: Color,       // 0xFF999999
        val contextPanelValue: Color,       // 0xFFDDDDDD
        val contextPanelSeparator: Color,   // 0xFF3E3E3E
        val contextProgressBarBg: Color,    // 0xFF3C3C3C

        // Indicator
        val indicatorBg: Color,             // 0xFF3C3C3C
        val tooltipBg: Color,               // 0xFF2B2B2B
        val tooltipBorder: Color,           // 0xFF4A4A4A
        val tooltipText: Color,             // 0xFFCCCCCC
        val tooltipMuted: Color,            // 0xFF999999

        // Code block
        val codeCopyIcon: Color,            // 0xFF6BBE50
        val codeLanguageLabel: Color,       // 0xFFBBBBBB

        // Todo panel
        val todoBg: Color,                  // 0xFF252525
        val todoHeader: Color,              // 0xFF808080
        val todoPending: Color,             // 0xFF808080
        val todoInProgress: Color,          // 0xFFE5A617
        val todoCompleted: Color,           // 0xFF6BBE50
        val todoCancelled: Color,           // 0xFF666666
        val todoAccent: Color,              // 0xFF3574F0
        val todoActiveText: Color,          // 0xFFDDDDDD

        // Compaction pill
        val compactionText: Color,          // 0xFF7EBF7E
        val compactionIcon: Color,          // 0xFF7EBF7E

        // Selector hover tint
        val selectorHoverTint: Color,       // 0xFF3574F0

        // Star ratings
        val starGold: Color,                // 0xFFE5C100
        val starMuted: Color,               // 0xFF606060

        // Selected row
        val selectedRowBg: Color,           // 0xFF2D4F6D

        // Thinking indicator
        val thinkingText: Color,            // Color.Gray
        val thinkingChevron: Color,         // 0xFF9E9E9E

        // MIME text
        val mimeText: Color,                // 0xFF7E7E7E

        // Review panel "Added" label
        val reviewAddedLabel: Color,        // 0xFF7CB342
    )

    @Immutable
    data class Dims(
        // Sidebar
        val sidebarWidth: Dp,
        val sidebarContextWidth: Dp,
        val sidebarReviewWidth: Dp,

        // Context indicator
        val contextIndicatorSize: Dp,
        val contextRingStroke: Dp,
        val contextRingGap: Dp,
        val contextTooltipWidth: Dp,
        val contextTooltipPadding: Dp,
        val contextErrorBadgeBorder: Dp,

        // Input area
        val inputOuterPaddingH: Dp,
        val inputOuterPaddingV: Dp,
        val inputCornerRadius: Dp,
        val inputMinHeight: Dp,
        val inputMaxHeight: Dp,
        val inputLineHeight: Dp,
        val inputContentPaddingH: Dp,
        val inputContentPaddingV: Dp,
        val inputDragBorderWidth: Dp,
        val inputDefaultBorderWidth: Dp,

        // Action buttons
        val actionButtonSize: Dp,
        val actionButtonCornerRadius: Dp,
        val actionIconSize: Dp,
        val stopIconSize: Dp,

        // Attachments
        val attachmentThumbnailSize: Dp,
        val attachmentThumbnailCornerRadius: Dp,
        val attachmentChipPaddingH: Dp,
        val attachmentChipPaddingV: Dp,
        val attachmentFileIconSize: Dp,
        val attachmentImageRemoveSize: Dp,
        val attachmentImageRemoveBadge: Dp,
        val attachmentFileRemoveBadge: Dp,

        // Model picker
        val modelPickerButtonSize: Dp,
        val modelPickerPanelWidthMin: Dp,
        val modelPickerPanelWidthMax: Dp,
        val modelPickerPanelMaxHeight: Dp,
        val modelPickerPanelCornerRadius: Dp,
        val modelPickerRowHeight: Dp,

        // Agent/thinking selectors
        val selectorSize: Dp,
        val selectorCornerRadius: Dp,

        // Messages
        val messageBubbleCornerRadius: Dp,
        val messagePaddingH: Dp,
        val messagePaddingV: Dp,
        val userAvatarSize: Dp,

        // Code blocks
        val codeBlockCornerRadius: Dp,
        val codeHeaderPaddingH: Dp,
        val codeHeaderPaddingV: Dp,
        val codeCopyIconSize: Dp,
        val codeLanguageIconSize: Dp,

        // Tool pills
        val toolPillCornerRadius: Dp,
        val toolAccentStripWidth: Dp,
        val toolAccentStripHeight: Dp,
        val toolAccentStripCornerRadius: Dp,

        // Session sidebar
        val sessionRowCornerRadius: Dp,
        val sessionRowPaddingH: Dp,
        val sessionRowPaddingV: Dp,
        val sessionChevronSize: Dp,
        val sessionIconSize: Dp,
        val sessionIndentPerLevel: Dp,

        // Context panel
        val contextProgressBarHeight: Dp,
        val contextProgressBarCornerRadius: Dp,

        // Todo panel
        val todoCornerRadius: Dp,
        val todoPaddingH: Dp,
        val todoPaddingV: Dp,
        val todoStatusIconSize: Dp,
        val todoChevronSize: Dp,

        // Slash command palette
        val paletteCornerRadius: Dp,
        val paletteMaxWidth: Dp,
        val paletteRowCornerRadius: Dp,

        // Permission prompt
        val permissionCornerRadius: Dp,
        val permissionBorderWidth: Dp,
        val permissionPaddingH: Dp,
        val permissionPaddingV: Dp,

        // Selection prompt
        val selectionCornerRadius: Dp,
        val selectionBorderWidth: Dp,
        val selectionCheckboxSize: Dp,
        val selectionCheckboxCornerRadius: Dp,
        val selectionRowCornerRadius: Dp,
        val selectionMaxHeight: Dp,

        // Connection banner
        val bannerBorderWidth: Dp,
        val bannerCornerRadius: Dp,
        val bannerPaddingH: Dp,
        val bannerPaddingV: Dp,
        val bannerIconSize: Dp,

        // Splash screen
        val splashLogoSize: Dp,
        val splashIndicatorSize: Dp,
        val splashSettingsDotSize: Dp,
        val splashButtonCornerRadius: Dp,

        // Thinking indicator
        val thinkingSpinnerSize: Dp,
        val thinkingStreamingSpinnerSize: Dp,

        // Review panel
        val reviewFileIconSize: Dp,
        val reviewOpenFileIconSize: Dp,

        // Dialog overlay
        val overlayCornerRadius: Dp,
    )

    @Immutable
    data class Fonts(
        val inputText: TextUnit,
        val inputPlaceholder: TextUnit,
        val attachmentFileName: TextUnit,
        val attachmentFileSize: TextUnit,
        val messageBody: TextUnit,
        val messageOrderedListItem: TextUnit,
        val messagePatchFileCount: TextUnit,
        val messagePatchFileName: TextUnit,
        val messageFileName: TextUnit,
        val messageFileMime: TextUnit,
        val messageInterrupted: TextUnit,
        val messageAgentBadge: TextUnit,
        val messageStepFinish: TextUnit,
        val messageError: TextUnit,
        val codeBody: TextUnit,
        val codeLanguageLabel: TextUnit,
        val codeCopiedLabel: TextUnit,
        val toolKindLabel: TextUnit,
        val toolFileName: TextUnit,
        val toolLineDelta: TextUnit,
        val toolTaskDescription: TextUnit,
        val toolTaskStatus: TextUnit,
        val thinkingLabel: TextUnit,
        val thinkingChevron: TextUnit,
        val contextPercent: TextUnit,
        val contextErrorBadge: TextUnit,
        val contextTooltipLabel: TextUnit,
        val contextTooltipValue: TextUnit,
        val contextTooltipSub: TextUnit,
        val contextPanelTitle: TextUnit,
        val contextSectionHeader: TextUnit,
        val contextDetailLabel: TextUnit,
        val contextDetailValue: TextUnit,
        val contextProgressBarPercent: TextUnit,
        val sessionTitle: TextUnit,
        val sessionFooter: TextUnit,
        val sessionDialogTitle: TextUnit,
        val sessionDialogBody: TextUnit,
        val paletteCommand: TextUnit,
        val paletteDescription: TextUnit,
        val paletteEmpty: TextUnit,
        val pickerModelName: TextUnit,
        val pickerContextWindow: TextUnit,
        val pickerProviderLetter: TextUnit,
        val pickerSectionHeader: TextUnit,
        val pickerSearchPlaceholder: TextUnit,
        val pickerFooter: TextUnit,
        val todoHeader: TextUnit,
        val todoCount: TextUnit,
        val todoContent: TextUnit,
        val todoMoreHint: TextUnit,
        val selectorChip: TextUnit,
        val selectorItem: TextUnit,
        val bannerText: TextUnit,
        val splashTitle: TextUnit,
        val splashStatus: TextUnit,
        val splashSettingsLabel: TextUnit,
        val splashError: TextUnit,
        val tableCell: TextUnit,
        val attachMenuFileName: TextUnit,
        val attachMenuFilePath: TextUnit,
        val attachMenuSectionLabel: TextUnit,
        val attachMenuSearchPlaceholder: TextUnit,
        val reviewFileName: TextUnit,
        val reviewStatusLabel: TextUnit,
        val reviewFilePath: TextUnit,
        val reviewLineDelta: TextUnit,
        val reviewLoading: TextUnit,
        val reviewEmpty: TextUnit,
        val reviewError: TextUnit,
        val permissionDescription: TextUnit,
        val selectionQuestion: TextUnit,
        val selectionSubtitle: TextUnit,
        val selectionCustomBullet: TextUnit,
        val selectionCheckmark: TextUnit,
        val selectionOptionTitle: TextUnit,
        val selectionOptionDescription: TextUnit,
    )

    @Immutable
    data class FontWeights(
        val sectionHeader: FontWeight,
        val badge: FontWeight,
        val semibold: FontWeight,
        val detailLabel: FontWeight,
        val detailValue: FontWeight,
        val contextPercent: FontWeight,
        val todoHeader: FontWeight,
        val selectionQuestion: FontWeight,
        val selectionCheckmark: FontWeight,
        val selectionOptionTitle: FontWeight,
        val commandName: FontWeight,
        val toolKindLabel: FontWeight,
        val toolLineDelta: FontWeight,
        val bannerText: FontWeight,
        val splashTitle: FontWeight,
        val splashError: FontWeight,
        val tableHeader: FontWeight,
        val sessionTitleSelected: FontWeight,
        val sessionTitleUnselected: FontWeight,
    )

    @Immutable
    data class Shapes(
        val inputCornerRadius: RoundedCornerShape,
        val attachmentCornerRadius: RoundedCornerShape,
        val actionButtonCornerRadius: RoundedCornerShape,
        val messageBubbleCornerRadius: RoundedCornerShape,
        val codeBlockCornerRadius: RoundedCornerShape,
        val toolPillCornerRadius: RoundedCornerShape,
        val toolAccentCornerRadius: RoundedCornerShape,
        val sessionRowCornerRadius: RoundedCornerShape,
        val contextIndicatorShape: CircleShape,
        val contextErrorBadgeShape: CircleShape,
        val contextTooltipCornerRadius: RoundedCornerShape,
        val contextProgressBarCornerRadius: RoundedCornerShape,
        val todoCornerRadius: RoundedCornerShape,
        val paletteCornerRadius: RoundedCornerShape,
        val paletteRowCornerRadius: RoundedCornerShape,
        val permissionCornerRadius: RoundedCornerShape,
        val selectionCornerRadius: RoundedCornerShape,
        val selectionCheckboxCornerRadius: RoundedCornerShape,
        val selectionRowCornerRadius: RoundedCornerShape,
        val bannerCornerRadius: RoundedCornerShape,
        val splashButtonCornerRadius: RoundedCornerShape,
        val splashSettingsCornerRadius: RoundedCornerShape,
        val chipCornerRadius: RoundedCornerShape,
        val pickerCornerRadius: RoundedCornerShape,
        val pickerRowCornerRadius: RoundedCornerShape,
        val modelPickerButtonShape: CircleShape,
        val fileChangeRowCornerRadius: RoundedCornerShape,
        val errorBadgeCornerRadius: RoundedCornerShape,
        val avatarShape: CircleShape,
        val imageRemoveBadgeShape: CircleShape,
        val fileRemoveBadgeShape: CircleShape,
        val searchIconShape: CircleShape,
        val splashSettingsIndicatorShape: RoundedCornerShape,
        val sidebarLeftCornerRadius: RoundedCornerShape,
        val overlayCornerRadius: RoundedCornerShape,
        val dialogCornerRadius: RoundedCornerShape,
        val retryPillCornerRadius: RoundedCornerShape,
        val attachMenuCornerRadius: RoundedCornerShape,
        val attachFileRowCornerRadius: RoundedCornerShape,
    )

    @Immutable
    data class Animations(
        val glowPulseMs: Int,
        val contextPulseMs: Int,
        val shimmerSweepMs: Int,
        val chipHoverMs: Int,
        val thinkingIndicatorDelayMs: Long,
    )
}

// ── CompositionLocal ────────────────────────────────────────────────────────

val LocalChatTheme = staticCompositionLocalOf<ChatThemeData> {
    error("No ChatThemeData provided — wrap your composable tree in ChatTheme {}")
}

/**
 * Provides [ChatThemeData] to the composition tree.
 * Reads semantic colors from IntelliJ's UIManager via [retrieveColorOrUnspecified].
 * Must be called inside a Compose context (e.g., inside a JewelTheme).
 *
 * Usage:
 * ```
 * ChatTheme {
 *     // All children can access ChatTheme.colors, ChatTheme.dims, etc.
 *     val colors = LocalChatTheme.current.colors
 * }
 * ```
 */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val themeData = rememberChatThemeData()
    CompositionLocalProvider(LocalChatTheme provides themeData) {
        content()
    }
}

/**
 * Convenience accessor: `ChatTheme.colors.accentGreen`
 */
object ChatTheme {
    val colors: Colors @Composable get() = LocalChatTheme.current.colors
    val dims: Dims @Composable get() = LocalChatTheme.current.dims
    val fonts: Fonts @Composable get() = LocalChatTheme.current.fonts
    val fontWeights: FontWeights @Composable get() = LocalChatTheme.current.fontWeights
    val shapes: Shapes @Composable get() = LocalChatTheme.current.shapes
    val animations: Animations @Composable get() = LocalChatTheme.current.animations
}

/**
 * Creates [ChatThemeData] by reading semantic colors from IntelliJ's UIManager.
 * Called once by [ChatTheme] composable. Hardcoded values for chat-specific accents.
 */
@Composable
private fun rememberChatThemeData(): ChatThemeData {
    // Semantic colors — read from IDE theme
    val panelBg = retrieveColorOrUnspecified("Panel.background")
    val panelFg = retrieveColorOrUnspecified("Panel.foreground")
    val borderColor = retrieveColorOrUnspecified("Component.borderColor")
    val selectionBg = retrieveColorOrUnspecified("List.selectionBackground")
    val selectionFg = retrieveColorOrUnspecified("List.selectionForeground")
    val hoverBg = retrieveColorOrUnspecified("MenuItem.selectionBackground")
    val linkColor = retrieveColorOrUnspecified("Link.activeForeground")
    val errorColor = retrieveColorOrUnspecified("Component.errorFocusColor")
    val disabledFg = retrieveColorOrUnspecified("Label.disabledForeground")

    return ChatThemeData(
        colors = ChatThemeData.Colors(
            // Semantic colors from UIManager
            surfacePrimary = panelBg,
            surfaceSecondary = panelBg.copy(red = panelBg.red * 0.85f, green = panelBg.green * 0.85f, blue = panelBg.blue * 0.85f),
            surfaceCard = panelBg.copy(red = panelBg.red * 0.9f, green = panelBg.green * 0.9f, blue = panelBg.blue * 0.9f),
            surfaceDark = panelBg.copy(red = panelBg.red * 0.75f, green = panelBg.green * 0.75f, blue = panelBg.blue * 0.75f),
            surfaceElevated = hoverBg,
            surfaceTableHeader = panelBg.copy(red = panelBg.red * 0.82f, green = panelBg.green * 0.82f, blue = panelBg.blue * 0.82f),
            textPrimary = panelFg,
            textSecondary = panelFg.copy(alpha = 0.85f),
            textMuted = panelFg.copy(alpha = 0.5f),
            textDisabled = disabledFg,
            textInverse = Color.White,
            borderDefault = borderColor,
            borderSubtle = borderColor.copy(alpha = 0.6f),
            borderAccent = borderColor.copy(red = borderColor.red * 1.1f, green = borderColor.green * 1.1f, blue = borderColor.blue * 1.1f),
            selectionBg = selectionBg,
            selectionFg = selectionFg,
            hoverBg = hoverBg,
            linkColor = linkColor,
            errorColor = errorColor,

            // Chat-specific accent colors (hardcoded)
            accentGreen = Color(0xFF6BBE50),
            accentGreenLight = Color(0xFF4EAF4E),
            accentBlue = Color(0xFF3574F0),
            stopRed = Color(0xFFE5534B),
            warningYellow = Color(0xFFE5A617),
            infoBlue = Color(0xFF589DF6),
            contextGreen = Color(0xFF6BBE50),
            contextYellow = Color(0xFFE5A617),
            contextRed = Color(0xFFE5534B),
            contextUnknown = Color(0xFF808080),
            codeAdded = Color(0xFF7EE787),
            codeDeleted = Color(0xFFFF7B72),
            codeModified = Color(0xFFF0C674),
            inlineCodeBackground = Color.Transparent,
            blockQuoteLine = Color(0xFF3E3E3E),
            blockQuoteText = Color(0xFF9E9E9E),
            pillContainerBg = Color(0x0CFFFFFF),
            overlaySemiTransparent = Color(0x88000000),
            highlightBlueAlpha = Color(0x182196F3),
            highlightBlueBorder = Color(0x402196F3),
            bannerErrorBg = Color(0x1ADB4437),
            bannerErrorBorder = Color(0x40DB4437),
            bannerInfoBg = Color(0x1A589DF6),
            bannerInfoBorder = Color(0x40589DF6),
            providerAnthropic = Color(0xFFCC785C),
            providerOpenAI = Color(0xFF10A37F),
            providerDeepSeek = Color(0xFF4D6BFE),
            providerGoogle = Color(0xFF4285F4),
            providerOllama = Color(0xFFCCCCCC),
            providerMistral = Color(0xFFDDA63A),
            providerCohere = Color(0xFF39D98A),
            providerGroq = Color(0xFFF55036),
            providerTogether = Color(0xFF6366F1),
            providerFireworks = Color(0xFFFF6B35),
            providerNvidia = Color(0xFF76B900),
            providerOpenRouter = Color(0xFF8B5CF6),
            providerGitHubCopilot = Color(0xFFCCCCCC),
            providerXAI = Color(0xFFCCCCCC),
            providerAlibaba = Color(0xFFFF6A00),
            providerMiniMax = Color(0xFF6366F1),
            providerMoonshot = Color(0xFFCCCCCC),
            providerStepFun = Color(0xFFCCCCCC),
            providerZhipu = Color(0xFF4D6BFE),
            providerKimi = Color(0xFFCCCCCC),
            providerColorMap = mapOf(
                "anthropic" to Color(0xFFCC785C),
                "openai" to Color(0xFF10A37F),
                "deepseek" to Color(0xFF4D6BFE),
                "google" to Color(0xFF4285F4),
                "ollama-cloud" to Color(0xFFCCCCCC),
                "mistral" to Color(0xFFDDA63A),
                "cohere" to Color(0xFF39D98A),
                "groq" to Color(0xFFF55036),
                "togetherai" to Color(0xFF6366F1),
                "fireworks-ai" to Color(0xFFFF6B35),
                "nvidia" to Color(0xFF76B900),
                "openrouter" to Color(0xFF8B5CF6),
                "github-copilot" to Color(0xFFCCCCCC),
                "xai" to Color(0xFFCCCCCC),
                "alibaba" to Color(0xFFFF6A00),
                "minimax" to Color(0xFF6366F1),
                "moonshotai" to Color(0xFFCCCCCC),
                "stepfun" to Color(0xFFCCCCCC),
                "zhipuai" to Color(0xFF4D6BFE),
                "kimi-for-coding" to Color(0xFFCCCCCC),
            ),
            fileKotlin = Color(0xFFA97BFF),
            fileJava = Color(0xFFED8B00),
            fileJavaScript = Color(0xFFF7DF1E),
            fileTypeScript = Color(0xFF3178C6),
            filePython = Color(0xFF3776AB),
            fileRuby = Color(0xFFCC342D),
            fileGo = Color(0xFF00ADD8),
            fileRust = Color(0xFFCE422B),
            fileHtml = Color(0xFFE44D26),
            fileCss = Color(0xFF264DE4),
            fileXml = Color(0xFF0060AC),
            fileJson = Color(0xFFBBBBBB),
            fileYaml = Color(0xFFCB171E),
            fileMarkdown = Color(0xFF519ABA),
            fileSql = Color(0xFFE38C00),
            fileShell = Color(0xFF4EAA25),
            toolExecute = Color(0xFF3574F0),
            toolEdit = Color(0xFF7EE787),
            toolRead = Color(0xFFBBBBBB),
            toolSearch = Color(0xFFF0C674),
            toolDelete = Color(0xFFFF7B72),
            toolMove = Color(0xFFBBBBBB),
            toolFetch = Color(0xFFF0C674),
            toolThink = Color(0xFF9E9E9E),
            toolSwitchMode = Color(0xFF9E9E9E),
            toolOther = Color(0xFF9E9E9E),
            sidebarShimmerCreating = Color(0xFFFFC107),
            sidebarShimmerStreaming = Color(0xFF4CAF50),
            splashConnected = Color(0xFF4CAF50),
            splashError = Color(0xFFF44336),
            splashRetry = Color(0xFFFF9800),
            taskAccent = Color(0xFF7EE787),
            taskRunning = Color(0xFF808080),
            taskCompleted = Color(0xFFCCCCCC),
            taskFailed = Color(0xFFFF7B72),
            taskPending = Color(0xFF808080),
            retryBg = Color(0xFF5C4A00),
            retryText = Color(0xFFFFD666),
            retryErrorDetail = Color(0xFFCCAA44),
            selectionCheckboxFill = Color(0xFF2196F3),
            selectionCheckboxBorder = Color.Gray,
            selectionCustomBullet = Color(0xFF4CAF50),
            interruptedDivider = Color(0xFF444444),
            tableHeaderText = Color(0xFF6BBE50),
            tableCellText = Color(0xFFD4D4D4),
            tableSeparator = Color(0xFF3E3E3E),
            tableHeaderBg = Color(0xFF2A2A2A),
            tableHoverBg = Color(0xFF252525),
            tableBorder = Color(0xFF3E3E3E),
            tableContainerBg = Color(0xFF1E1E1E),
            glowTransparent = Color.Transparent,
            glowStart = Color(0xFF4A9EFF).copy(alpha = 0.15f),
            glowPeak = Color(0xFF4A9EFF).copy(alpha = 0.45f),
            glowHot = Color(0xFF00D4FF).copy(alpha = 0.85f),
            userBubbleBg = Color(0xFF3574F0).copy(alpha = 0.12f),
            userAvatarBorder = Color(0xFF3574F0),
            userAvatarFill = Color(0xFF5E9AFF),
            permissionBorder = Color(0x40808080),
            permissionBg = Color(0x10808080),
            inputBg = Color(0xFF2B2B2B),
            inputBorder = Color(0xFF3E3E3E),
            inputCursor = Color.White,
            inputText = Color(0xFFCCCCCC),
            inputPlaceholder = Color(0xFF808080),
            dragActiveBg = Color(0xFF2E3A2E),
            attachmentBg = Color(0xFF3E3E3E),
            attachmentRemoveIcon = Color(0xFFBBBBBB),
            attachmentImageOverlay = Color(0xFF555555),
            attachmentImageRemove = Color(0xFFCCCCCC),
            attachmentFileSize = Color(0xFF808080),
            contextPanelLabel = Color(0xFF999999),
            contextPanelValue = Color(0xFFDDDDDD),
            contextPanelSeparator = Color(0xFF3E3E3E),
            contextProgressBarBg = Color(0xFF3C3C3C),
            indicatorBg = Color(0xFF3C3C3C),
            tooltipBg = Color(0xFF2B2B2B),
            tooltipBorder = Color(0xFF4A4A4A),
            tooltipText = Color(0xFFCCCCCC),
            tooltipMuted = Color(0xFF999999),
            codeCopyIcon = Color(0xFF6BBE50),
            codeLanguageLabel = Color(0xFFBBBBBB),
            todoBg = Color(0xFF252525),
            todoHeader = Color(0xFF808080),
            todoPending = Color(0xFF808080),
            todoInProgress = Color(0xFFE5A617),
            todoCompleted = Color(0xFF6BBE50),
            todoCancelled = Color(0xFF666666),
            todoAccent = Color(0xFF3574F0),
            todoActiveText = Color(0xFFDDDDDD),
            compactionText = Color(0xFF7EBF7E),
            compactionIcon = Color(0xFF7EBF7E),
            selectorHoverTint = Color(0xFF3574F0),
            starGold = Color(0xFFE5C100),
            starMuted = Color(0xFF606060),
            selectedRowBg = Color(0xFF2D4F6D),
            thinkingText = Color.Gray,
            thinkingChevron = Color(0xFF9E9E9E),
            mimeText = Color(0xFF7E7E7E),
            reviewAddedLabel = Color(0xFF7CB342),
        ),
        dims = ChatThemeData.Dims(
            sidebarWidth = 260.dp,
            sidebarContextWidth = 320.dp,
            sidebarReviewWidth = 260.dp,
            contextIndicatorSize = 28.dp,
            contextRingStroke = 2.dp,
            contextRingGap = 2.dp,
            contextTooltipWidth = 240.dp,
            contextTooltipPadding = 8.dp,
            contextErrorBadgeBorder = 1.dp,
            inputOuterPaddingH = 12.dp,
            inputOuterPaddingV = 8.dp,
            inputCornerRadius = 12.dp,
            inputMinHeight = 56.dp,
            inputMaxHeight = 156.dp,
            inputLineHeight = 20.dp,
            inputContentPaddingH = 12.dp,
            inputContentPaddingV = 8.dp,
            inputDragBorderWidth = 2.dp,
            inputDefaultBorderWidth = 1.dp,
            actionButtonSize = 28.dp,
            actionButtonCornerRadius = 6.dp,
            actionIconSize = 16.dp,
            stopIconSize = 12.dp,
            attachmentThumbnailSize = 48.dp,
            attachmentThumbnailCornerRadius = 6.dp,
            attachmentChipPaddingH = 6.dp,
            attachmentChipPaddingV = 4.dp,
            attachmentFileIconSize = 14.dp,
            attachmentImageRemoveSize = 16.dp,
            attachmentImageRemoveBadge = 10.dp,
            attachmentFileRemoveBadge = 10.dp,
            modelPickerButtonSize = 32.dp,
            modelPickerPanelWidthMin = 140.dp,
            modelPickerPanelWidthMax = 240.dp,
            modelPickerPanelMaxHeight = 320.dp,
            modelPickerPanelCornerRadius = 8.dp,
            modelPickerRowHeight = 32.dp,
            selectorSize = 28.dp,
            selectorCornerRadius = 6.dp,
            messageBubbleCornerRadius = 8.dp,
            messagePaddingH = 12.dp,
            messagePaddingV = 4.dp,
            userAvatarSize = 28.dp,
            codeBlockCornerRadius = 8.dp,
            codeHeaderPaddingH = 12.dp,
            codeHeaderPaddingV = 4.dp,
            codeCopyIconSize = 14.dp,
            codeLanguageIconSize = 16.dp,
            toolPillCornerRadius = 8.dp,
            toolAccentStripWidth = 3.dp,
            toolAccentStripHeight = 40.dp,
            toolAccentStripCornerRadius = 1.5.dp,
            sessionRowCornerRadius = 6.dp,
            sessionRowPaddingH = 10.dp,
            sessionRowPaddingV = 8.dp,
            sessionChevronSize = 16.dp,
            sessionIconSize = 14.dp,
            sessionIndentPerLevel = 16.dp,
            contextProgressBarHeight = 18.dp,
            contextProgressBarCornerRadius = 4.dp,
            todoCornerRadius = 8.dp,
            todoPaddingH = 12.dp,
            todoPaddingV = 6.dp,
            todoStatusIconSize = 12.dp,
            todoChevronSize = 12.dp,
            paletteCornerRadius = 8.dp,
            paletteMaxWidth = 300.dp,
            paletteRowCornerRadius = 4.dp,
            permissionCornerRadius = 4.dp,
            permissionBorderWidth = 1.dp,
            permissionPaddingH = 8.dp,
            permissionPaddingV = 4.dp,
            selectionCornerRadius = 8.dp,
            selectionBorderWidth = 1.dp,
            selectionCheckboxSize = 18.dp,
            selectionCheckboxCornerRadius = 3.dp,
            selectionRowCornerRadius = 4.dp,
            selectionMaxHeight = 240.dp,
            bannerBorderWidth = 1.dp,
            bannerCornerRadius = 4.dp,
            bannerPaddingH = 8.dp,
            bannerPaddingV = 4.dp,
            bannerIconSize = 16.dp,
            splashLogoSize = 64.dp,
            splashIndicatorSize = 16.dp,
            splashSettingsDotSize = 12.dp,
            splashButtonCornerRadius = 8.dp,
            thinkingSpinnerSize = 16.dp,
            thinkingStreamingSpinnerSize = 14.dp,
            reviewFileIconSize = 16.dp,
            reviewOpenFileIconSize = 18.dp,
            overlayCornerRadius = 8.dp,
        ),
        fonts = ChatThemeData.Fonts(
            inputText = 13.sp,
            inputPlaceholder = 13.sp,
            attachmentFileName = 12.sp,
            attachmentFileSize = 11.sp,
            messageBody = 13.sp,
            messageOrderedListItem = 14.sp,
            messagePatchFileCount = 12.sp,
            messagePatchFileName = 11.sp,
            messageFileName = 12.sp,
            messageFileMime = 10.sp,
            messageInterrupted = 12.sp,
            messageAgentBadge = 11.sp,
            messageStepFinish = 11.sp,
            messageError = 11.sp,
            codeBody = 13.sp,
            codeLanguageLabel = 11.sp,
            codeCopiedLabel = 12.sp,
            toolKindLabel = 13.sp,
            toolFileName = 12.sp,
            toolLineDelta = 12.sp,
            toolTaskDescription = 12.sp,
            toolTaskStatus = 11.sp,
            thinkingLabel = 13.sp,
            thinkingChevron = 16.sp,
            contextPercent = 10.sp,
            contextErrorBadge = 8.sp,
            contextTooltipLabel = 10.sp,
            contextTooltipValue = 11.sp,
            contextTooltipSub = 9.sp,
            contextPanelTitle = 12.sp,
            contextSectionHeader = 12.sp,
            contextDetailLabel = 11.sp,
            contextDetailValue = 11.sp,
            contextProgressBarPercent = 7.sp,
            sessionTitle = 12.sp,
            sessionFooter = 11.sp,
            sessionDialogTitle = 14.sp,
            sessionDialogBody = 12.sp,
            paletteCommand = 12.sp,
            paletteDescription = 11.sp,
            paletteEmpty = 12.sp,
            pickerModelName = 12.sp,
            pickerContextWindow = 10.sp,
            pickerProviderLetter = 9.sp,
            pickerSectionHeader = 11.sp,
            pickerSearchPlaceholder = 12.sp,
            pickerFooter = 10.sp,
            todoHeader = 11.sp,
            todoCount = 10.sp,
            todoContent = 11.sp,
            todoMoreHint = 10.sp,
            selectorChip = 12.sp,
            selectorItem = 12.sp,
            bannerText = 13.sp,
            splashTitle = 24.sp,
            splashStatus = 14.sp,
            splashSettingsLabel = 12.sp,
            splashError = 14.sp,
            tableCell = 13.sp,
            attachMenuFileName = 12.sp,
            attachMenuFilePath = 10.sp,
            attachMenuSectionLabel = 11.sp,
            attachMenuSearchPlaceholder = 12.sp,
            reviewFileName = 12.sp,
            reviewStatusLabel = 10.sp,
            reviewFilePath = 10.sp,
            reviewLineDelta = 11.sp,
            reviewLoading = 12.sp,
            reviewEmpty = 12.sp,
            reviewError = 12.sp,
            permissionDescription = 12.sp,
            selectionQuestion = 14.sp,
            selectionSubtitle = 12.sp,
            selectionCustomBullet = 16.sp,
            selectionCheckmark = 12.sp,
            selectionOptionTitle = 13.sp,
            selectionOptionDescription = 11.sp,
        ),
        fontWeights = ChatThemeData.FontWeights(
            sectionHeader = FontWeight.Bold,
            badge = FontWeight.Medium,
            semibold = FontWeight.SemiBold,
            detailLabel = FontWeight.SemiBold,
            detailValue = FontWeight.Normal,
            contextPercent = FontWeight.Bold,
            todoHeader = FontWeight.SemiBold,
            selectionQuestion = FontWeight.Bold,
            selectionCheckmark = FontWeight.Bold,
            selectionOptionTitle = FontWeight.Medium,
            commandName = FontWeight.SemiBold,
            toolKindLabel = FontWeight.Bold,
            toolLineDelta = FontWeight.Medium,
            bannerText = FontWeight.Medium,
            splashTitle = FontWeight.Bold,
            splashError = FontWeight.Medium,
            tableHeader = FontWeight.Bold,
            sessionTitleSelected = FontWeight.Medium,
            sessionTitleUnselected = FontWeight.Normal,
        ),
        shapes = ChatThemeData.Shapes(
            inputCornerRadius = RoundedCornerShape(12.dp),
            attachmentCornerRadius = RoundedCornerShape(6.dp),
            actionButtonCornerRadius = RoundedCornerShape(6.dp),
            messageBubbleCornerRadius = RoundedCornerShape(8.dp),
            codeBlockCornerRadius = RoundedCornerShape(8.dp),
            toolPillCornerRadius = RoundedCornerShape(8.dp),
            toolAccentCornerRadius = RoundedCornerShape(1.5.dp),
            sessionRowCornerRadius = RoundedCornerShape(6.dp),
            contextIndicatorShape = CircleShape,
            contextErrorBadgeShape = CircleShape,
            contextTooltipCornerRadius = RoundedCornerShape(6.dp),
            contextProgressBarCornerRadius = RoundedCornerShape(4.dp),
            todoCornerRadius = RoundedCornerShape(8.dp),
            paletteCornerRadius = RoundedCornerShape(8.dp),
            paletteRowCornerRadius = RoundedCornerShape(4.dp),
            permissionCornerRadius = RoundedCornerShape(4.dp),
            selectionCornerRadius = RoundedCornerShape(8.dp),
            selectionCheckboxCornerRadius = RoundedCornerShape(3.dp),
            selectionRowCornerRadius = RoundedCornerShape(4.dp),
            bannerCornerRadius = RoundedCornerShape(4.dp),
            splashButtonCornerRadius = RoundedCornerShape(8.dp),
            splashSettingsCornerRadius = RoundedCornerShape(2.dp),
            chipCornerRadius = RoundedCornerShape(6.dp),
            pickerCornerRadius = RoundedCornerShape(8.dp),
            pickerRowCornerRadius = RoundedCornerShape(4.dp),
            modelPickerButtonShape = CircleShape,
            fileChangeRowCornerRadius = RoundedCornerShape(4.dp),
            errorBadgeCornerRadius = RoundedCornerShape(4.dp),
            avatarShape = CircleShape,
            imageRemoveBadgeShape = CircleShape,
            fileRemoveBadgeShape = CircleShape,
            searchIconShape = CircleShape,
            splashSettingsIndicatorShape = RoundedCornerShape(2.dp),
            sidebarLeftCornerRadius = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            overlayCornerRadius = RoundedCornerShape(8.dp),
            dialogCornerRadius = RoundedCornerShape(8.dp),
            retryPillCornerRadius = RoundedCornerShape(4.dp),
            attachMenuCornerRadius = RoundedCornerShape(8.dp),
            attachFileRowCornerRadius = RoundedCornerShape(4.dp),
        ),
        animations = ChatThemeData.Animations(
            glowPulseMs = 2500,
            contextPulseMs = 800,
            shimmerSweepMs = 1500,
            chipHoverMs = 150,
            thinkingIndicatorDelayMs = 300L,
        ),
    )
}
```

**ChatTheme + JewelTheme coexistence rules:**

| Use case | Source | Example |
|----------|--------|---------|
| Standard UI chrome (backgrounds, text, borders, selection, hover) | `retrieveColorOrUnspecified()` via `ChatTheme.colors.surfacePrimary`, etc. | Session sidebar rows, input area background, tooltip background |
| Chat-specific accent colors | `ChatTheme.colors.accentGreen`, etc. (hardcoded) | Send button, context indicator, code added/deleted |
| IDE-integrated elements (editor colors, code highlighting) | `EditorColorsManager` directly | Code block foreground/background (already in `CodeBlockRenderer.kt`) |
| Jewel standard components (buttons, links, text fields) | `JewelTheme` (unchanged) | `DefaultButton`, `OutlinedButton`, `Link`, `TextField` |

**User-configurable colors:** `parseColorOrDefault(settings.inlineCodeColor, defaultGreen)` in `MessageList.kt` reads from `OpenCodeSettingsState`. These user-specified colors override `ChatTheme` defaults. The pattern: composables read from settings first, fall back to `ChatTheme` for the default.

#### 4.7.2 Class & Interface Definitions

**A. `ChatViewModel` — add `inputState` StateFlow**

```kotlin
// ChatViewModel.kt — new computed StateFlow

val inputState: StateFlow<ChatInputState> = combine(
    connectionState,       // ChatViewModel.kt:37
    permissionPrompt,      // ChatViewModel.kt:54
    selectionPrompt,       // ChatViewModel.kt:57
    isStreaming,           // ChatViewModel.kt:51
) { conn, perm, sel, streaming ->
    when {
        conn != ConnectionState.CONNECTED -> ChatInputState.Disabled
        perm != null -> ChatInputState.AwaitingPermission(perm)
        sel != null -> ChatInputState.AwaitingSelection(sel)
        streaming -> ChatInputState.Streaming
        else -> ChatInputState.Idle
    }
}.stateIn(scope, SharingStarted.WhileSubscribed(), ChatInputState.Disabled)
```

Note: `combine()` with 4 parameters uses the standard overload (no vararg needed). The previous draft's 6-parameter `combine` required the vararg overload — removing `Clearing` and `Steering` simplifies this.

**B. `ChatScreen` — replace `inputEnabled` with `inputState`**

Current (ad-hoc boolean):
```kotlin
val inputEnabled = connectionState == ConnectionState.CONNECTED
    && permissionPrompt == null
    && selectionPrompt == null
```

New (switch on state):
```kotlin
val inputState by viewModel.inputState.collectAsState()

// InputArea enabled when state allows typing
val inputEnabled = inputState !is ChatInputState.Disabled

// InputArea streaming state for button mode
val isStreaming = inputState is ChatInputState.Streaming

InputArea(
    enabled = inputEnabled,
    isStreaming = isStreaming,
    ...
)
```

**C. `MessageList` — replace thinking condition with `renderPhase()`**

Current (fragile 4-way AND):
```kotlin
if (message.isStreaming && !hasThinking && !hasToolCall &&
    message.parts.values.none { it is MessagePart.Text || it is MessagePart.Code || it is MessagePart.Table }) {
    ThinkingIndicator()
}
```

New (single dispatch):
```kotlin
// After the for loop that renders all parts:
when (message.renderPhase()) {
    MessageRenderPhase.THINKING -> ThinkingIndicator()
    else -> { /* HAS_CONTENT or COMPLETE — no standalone indicator needed */ }
}
```

The `CollapsibleThinkingPill` rendering inside the `for` loop stays unchanged — it renders for each `MessagePart.Thinking`. The standalone `ThinkingIndicator()` at the end is now guarded by `renderPhase() == THINKING`, which can only fire when the message has zero parts.

**D. `ChatTheme` usage pattern — before/after**

Before (scattered):
```kotlin
// InputArea.kt
val inputBg = Color(0xFF2B2B2B)
val inputBorder = Color(0xFF3E3E3E)
// ... 20 more colors
```

After (centralized):
```kotlin
// InputArea.kt
import com.opencode.acp.chat.ui.theme.ChatTheme

@Composable
fun InputArea(...) {
    val colors = ChatTheme.colors
    val dims = ChatTheme.dims

    // Use colors.inputBg instead of Color(0xFF2B2B2B)
    // Use dims.inputCornerRadius instead of 12.dp
    // Use ChatTheme.fonts.inputText instead of 13.sp
}
```

#### 4.7.3 Function Signatures

```kotlin
// ChatModels.kt — extension function on ChatMessage
fun ChatMessage.renderPhase(): MessageRenderPhase

// ChatViewModel.kt — new computed StateFlow
val inputState: StateFlow<ChatInputState>

// ChatTheme.kt — composable providing theme data
@Composable fun ChatTheme(content: @Composable () -> Unit)

// No signature changes to InputArea, MessageList, or other composables.
// Composables simply replace Color(0xFF…) / dp / sp literals with ChatTheme references.
```

#### 4.7.5 Enums, Constants & Configuration

Defined in §4.7.1 above:
- `ChatInputState` sealed interface
- `MessageRenderPhase` enum
- `ChatThemeData` data class with `Colors`, `Dims`, `Fonts`, `FontWeights`, `Shapes`, `Animations` nested data classes
- `ChatTheme` composable + `LocalChatTheme` CompositionLocal

`ChatConstants` constants are split between `ChatTheme` and a retained `ChatConstants`:
- **Move to `ChatThemeData.Animations`:** `THINKING_INDICATOR_DELAY_MS`
- **Move to `ChatThemeData.Dims`:** `SIDEBAR_WIDTH_DP`, `SIDEBAR_CONTEXT_WIDTH_DP`, `SIDEBAR_REVIEW_WIDTH_DP`, `CONTEXT_INDICATOR_SIZE_DP`
- **Retain in `ChatConstants`:** `TOOL_WINDOW_ID`, `MAX_MESSAGE_HISTORY`, `PERMISSION_TIMEOUT_MS`, `RECONNECT_DELAY_MS`, `RECONNECT_MAX_DELAY_MS`, `SSE_HEALTH_CHECK_INTERVAL_MS`, `SSE_HEALTH_CHECK_TIMEOUT_MS`
  — these are used by non-UI layers (`OpenCodeService`, `SessionState`, `ChatViewModel` timeout handling). Moving them into a UI package would create a backwards dependency. `ChatConstants` remains but is stripped to service-level constants only.

---

## 5. Assumptions & Dependencies

### Assumptions
1. **Compose recomposition is atomic per frame** — a `when` on `renderPhase()` will not render `THINKING` and `HAS_CONTENT` in the same frame for the same message, because the `when` expression evaluates to exactly one branch.
2. **`ChatInputState` priority order is stable** — the priority (Disabled > AwaitingPermission > AwaitingSelection > Streaming > Idle) matches user expectations. If a permission prompt appears while streaming, the prompt wins.
3. **No new visual states are needed** — the current visual appearance is correct; we're consolidating, not redesigning. All hex values in `ChatThemeData.Colors` are copied verbatim from the existing composables.
4. **`combine()` is sufficient for `inputState`** — the 4 input StateFlows are already in `ChatViewModel`. `combine` re-derives `inputState` whenever any input changes. No new reactive primitives needed.
5. **`retrieveColorOrUnspecified()` returns valid colors** — Jewel's bridge function returns `Color.Unspecified` for unknown keys. The `rememberChatThemeData()` function should handle this gracefully (e.g., fall back to hardcoded dark-theme defaults).

### Dependencies
- Kotlin coroutines `combine` (already used in codebase)
- Compose `collectAsState()` (already used)
- Existing StateFlows in `ChatViewModel` (no API changes)
- Jewel bridge `retrieveColorOrUnspecified()` (already used in `SessionSidebar.kt`, `MessageList.kt`, `ReviewPanel.kt`)
- `CompositionLocal` / `CompositionLocalProvider` (standard Compose)

---

## 6. Alternatives Considered

### Alternative: StateFlow<Boolean> for Each Condition (Current Approach)
**What it is:** Keep the current ad-hoc boolean combinations — `inputEnabled = connected && noPrompt && noSelection`.
**Why plausible:** No refactor needed.
**Why rejected:** Already causing bugs (two thinking bubbles, invalid state combinations). Every new feature adds another `&&` condition. No compile-time safety for invalid combinations. Scattered across files with no single audit point.

### Alternative: Compose M3 Theme (MaterialTheme)
**What it is:** Use Compose Multiplatform's `MaterialTheme` color palette and typography system.
**Why plausible:** Standard approach for Compose apps.
**Why rejected:** We're using Jewel (IntelliJ's theme system), which provides its own `JewelTheme` for IDE-aligned colors. Our chat colors are chat-specific (green accents, dark backgrounds) that don't map to Material's color roles. `ChatTheme` coexists with `JewelTheme` — Jewel handles IDE chrome, `ChatTheme` handles chat content.

### Alternative: Sealed Class with Detailed Data (Over-Engineered)
**What it is:** Make `ChatInputState.Streaming` carry `streamingSessionId`, `elapsedMs`, etc. Make `AwaitingPermission` carry the full permission details plus a timer.
**Why plausible:** More information available per state for UI rendering.
**Why rejected:** YAGNI. The composables already have access to the full data via their own `collectAsState()` calls. `ChatInputState` only needs to answer "what mode is the input in?" — it doesn't need to carry all the data that the composables already collect. Adding data would couple the state machine to data that changes for unrelated reasons.

### Alternative: Static `ChatTheme` Object (Previous Draft)
**What it is:** A plain `object ChatTheme` with hardcoded color constants — no `CompositionLocal`, no `retrieveColorOrUnspecified()`.
**Why plausible:** Simpler API — `ChatTheme.Colors.accentGreen` works without `@Composable` context.
**Why rejected:** Centralized hardcoding is still hardcoding. The chat wouldn't respect the user's IDE theme. `SessionSidebar.kt` already reads `List.selectionBackground` from UIManager — replacing that with a hardcoded value would be a regression. The `CompositionLocal` pattern adds minimal complexity (one `ChatTheme {}` wrapper at the root) while enabling proper theme integration.

---

## 7. Cross-Cutting Concerns

### 7.2 Reliability & Bug Prevention

**Two thinking bubbles — root cause and fix:** The current code has two independent rendering paths that can both fire for the same message:
1. `ThinkingIndicator()` (line 498) — shows when `isStreaming && !hasThinking && !hasToolCall && no content`
2. `CollapsibleThinkingPill()` (inside the `for` loop) — shows for each `MessagePart.Thinking`

These are guarded by different conditions (`hasThinking` flag vs. `isStreaming` boolean), and a timing gap during SSE event processing can cause both to render. `MessageRenderPhase` replaces the standalone `ThinkingIndicator` condition with `renderPhase() == THINKING` — which can only fire when the message has zero parts. The `for` loop continues to render `CollapsibleThinkingPill` compositionally.

**`ChatInputState` prevents invalid combinations:** The current `inputEnabled` boolean allows `isStreaming == true && connectionState == DISCONNECTED` — the composables see `isStreaming == true` but `inputEnabled == false`, and must independently decide what to show. With `ChatInputState`, this combination maps to `Disabled`, which wins over `Streaming` in the priority order. The composable doesn't need to check both conditions — the state machine already resolved them.

### 7.4 Observability

**New log line** in `ChatViewModel`:

| Location | Log Line | When |
|----------|---------|------|
| `inputState` combine block | `[ACP] inputState → $state` | On state transition (debounced to avoid log spam) |

This is debug-level and can be enabled via `Help → Debug Log Settings`.

### 7.5 Migration Strategy

This is a **pure refactor** — no behavior changes. The migration proceeds in 3 phases:

**Phase 1: Create `ChatTheme` and `ChatInputState`** (no composable changes)
- Add `ChatThemeData.kt`, `ChatTheme.kt` (composable + CompositionLocal), `ChatInputState.kt`, `MessageRenderPhase.kt`
- Add `inputState` to `ChatViewModel`
- Add `renderPhase()` extension to `ChatMessage`
- Wrap the root composable in `ChatTheme {}` (one line in `ChatToolWindowFactory.kt`)
- All existing code continues to work unchanged

**Phase 2: Migrate composables to `ChatTheme`** (one file at a time)
- Replace `Color(0xFF…)` literals with `ChatTheme.colors.*` references
- Replace `dp` literals with `ChatTheme.dims.*` references
- Replace `sp`/font weight literals with `ChatTheme.fonts.*`/`ChatTheme.fontWeights.*`
- Replace `RoundedCornerShape(…dp)` with `ChatTheme.shapes.*`
- Replace `retrieveColorOrUnspecified("…")` calls with `ChatTheme.colors.*` where a semantic mapping exists
- **Verification:** For each file, take a before/after screenshot of the chat UI in the IntelliJ sandbox. Compare visually — no pixel-level diff tool needed, just confirm colors match. If the IDE is using a non-default dark theme, verify that semantic colors (backgrounds, text, selection) adapt correctly.

**Phase 3: Migrate composables to `ChatInputState` and `MessageRenderPhase`**
- Replace `inputEnabled` computation in `ChatScreen.kt` with `inputState`
- Replace `if (isStreaming && !hasThinking && …)` in `MessageList.kt` with `when (message.renderPhase())`
- Remove the `hasThinking` local variable and inline `ThinkingIndicator` condition
- Strip `ChatConstants.kt` to service-level constants only:
  - Retain: `TOOL_WINDOW_ID`, `MAX_MESSAGE_HISTORY`, `PERMISSION_TIMEOUT_MS`, `RECONNECT_DELAY_MS`, `RECONNECT_MAX_DELAY_MS`, `SSE_HEALTH_CHECK_INTERVAL_MS`, `SSE_HEALTH_CHECK_TIMEOUT_MS`
  - Already moved to `ChatThemeData` in Phase 1: `THINKING_INDICATOR_DELAY_MS`, sidebar width constants, context indicator constant

**Phase 3 fixes the two-thinking-bubbles bug** because `when (message.renderPhase())` only fires `THINKING` when the message has zero parts — the `for` loop handles all content rendering compositionally.

---

## 8. Testing Strategy

### 8.2 Key Scenarios

| # | Scenario | Expected Behavior | Verification |
|---|----------|-------------------|--------------|
| 1 | Connected, no prompt, not streaming | `ChatInputState.Idle` — Send button when text present, no Stop button | Input active, normal send |
| 2 | Streaming, no prompt | `ChatInputState.Streaming` — Send button when text present (steer), Stop when empty | Input active, contextual buttons |
| 3 | Permission prompt visible | `ChatInputState.AwaitingPermission` — input disabled | Input grayed, only prompt buttons |
| 4 | Selection prompt visible | `ChatInputState.AwaitingSelection` — input disabled | Input grayed, only selection buttons |
| 5 | Connection lost while streaming | `ChatInputState.Disabled` — overrides Streaming | Input grayed, no buttons |
| 6 | Permission prompt while streaming | `ChatInputState.AwaitingPermission` — overrides Streaming | Streaming indicator stays, prompt shown |
| 7 | Message with no content, streaming | `renderPhase() == THINKING` — show ThinkingIndicator | Single spinner, no duplicate |
| 8 | Message with Thinking part, streaming | `renderPhase() == HAS_CONTENT` — no ThinkingIndicator | CollapsibleThinkingPill renders in for loop |
| 9 | Message with Thinking + ToolCall, streaming | `renderPhase() == HAS_CONTENT` — no ThinkingIndicator | Both CollapsibleThinkingPill and ToolPill render |
| 10 | Message aborted | `renderPhase() == COMPLETE` (not streaming) | "Interrupted" divider renders via `MessageState.Aborted` check |
| 11 | Theme migration — file by file | No visual changes | Side-by-side screenshot comparison in IntelliJ sandbox |
| 12 | Non-default IDE theme | Semantic colors adapt | Switch to a different dark theme, verify backgrounds/text/selection change |

---

## 9. Open Questions

None — all questions resolved.

---

## 10. Document History

| Date | Author        | Change |
|------|---------------|--------|
| 2026-06-10 | --            | Initial draft |
| 2026-06-10 | -- (review)   | Fixed line numbers (connectionState:37, _selectionPrompt:56), ChatConstants split strategy (retain service constants), added missing colors (starGold, interruptedDivider, pillContainerBg, surfaceDark, surfaceTableHeader, providerColorMap, providerKimi), added _isSteering lifecycle specification, fixed provider keys with server→constant mapping, removed sendButtonColor from Dims, corrected combine→stateIn() pattern, expanded color inconsistency table with 13 rows |
| 2026-06-10 | -- (review 2) | **Major revision:** Removed `ChatInputState.Clearing` and `ChatInputState.Steering` (not needed). Simplified `ChatInputState` to 4 states (Disabled, Idle, Streaming, AwaitingPermission, AwaitingSelection). Simplified `combine()` from 6 to 4 parameters. Redesigned `ChatTheme` as `@Composable` with `CompositionLocal` — semantic colors read from IntelliJ `UIManager` via `retrieveColorOrUnspecified()`, chat-specific accents hardcoded. Changed `MessageRenderPhase` from exclusive (7 phases) to composable (3 phases: THINKING, HAS_CONTENT, COMPLETE) — only controls standalone ThinkingIndicator, not per-part rendering. Added missing colors (compactionText, compactionIcon, selectorHoverTint, starGold, starMuted, selectedRowBg, thinkingText, thinkingChevron, mimeText, reviewAddedLabel). Fixed `splashConnecting` → `splashRetry` (hex `0xFFFF9800` not `0xFFFFC107`). Added JewelTheme coexistence rules. Added UIManager integration strategy. Added visual regression testing approach. |
