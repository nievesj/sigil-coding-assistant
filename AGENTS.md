# AGENTS.md — Tracked Items & Future Work

This document tracks incomplete features, deferred decisions, and known gaps
that are intentionally left for future implementation.

---

## Developer Notes

### runIde Log Location

Logs for the plugin when running via `runIde` are NOT in the main IDEA log.
They are in the sandbox directory:

```
.intellijPlatform/sandbox/intellij-opencode-plugin/IU-2026.1/log/idea.log
```

To see logs in the Run tool window:
1. Edit run configuration → add log file from sandbox path above
2. Or use `Help → Debug Log Settings` in the running sandbox IDE and add:
   ```
   #com.opencode.acp.chat.viewmodel.ChatViewModel
   #com.opencode.acp.adapter.OpenCodeClient
   ```

To see stdout from the plugin, use `println()` — it appears in the Run tool
window console (not in idea.log).

### Jewel Markdown: Cannot Override DefaultMarkdownBlockRenderer for Code Blocks

Jewel's `DefaultMarkdownBlockRenderer` in IU-261 has method overrides like
`render(FencedCodeBlock, Fenced, enabled, modifier)` that **compile** but
**never fire at runtime**. Confirmed via `println` debug logging: the override
compiles, the renderer instance is created and passed to `ProvideMarkdownStyling`
and `Markdown(blockRenderer=...)`, yet the `when` dispatch inside
`DefaultMarkdownBlockRenderer.render(block: MarkdownBlock, ...)` never reaches
our override. Root cause unclear (classloader isolation? bundling patching?),
but the effect is consistent and reproducible.

**Solution:** Bypass Jewel's renderer entirely for code blocks. `MarkdownSegmenter`
uses a line-by-line state machine to split raw markdown into TEXT and CODE segments.
TEXT segments go through Jewel's `Markdown` composable (default renderer). CODE
segments render directly via our `ChatFencedCodeBlock` composable (dark theme,
language header, copy button, line numbers, syntax highlighting via
`LocalCodeHighlighter`).

**Why state machine, not regex:** Initial regex `^```(\S*)\n(.*?)^``` ` failed
because LLMs sometimes emit ``` mid-line (e.g., `text```css`) or omit trailing
newlines. The state machine handles all fence positions and captures the language
identifier correctly. Language names are mapped to Jewel's expected identifiers
via `mapLanguageId()` (e.g., "css" → "CSS", "js" → "JavaScript").

- **Files:** `MarkdownSegmenter.kt`, `MessageList.kt`, `CodeBlockRenderer.kt`
- **Deleted:** `ChatMarkdownBlockRenderer.kt` (dead override code)
- **Warning:** Do NOT attempt to override `DefaultMarkdownBlockRenderer` methods
  for code blocks again — it compiles but does not dispatch at runtime in IU-261.

### Jewel Markdown: Inline Code Background (InlinesStyling Propagation)

Jewel renders inline code backgrounds via `SpanStyle.background` in
`AnnotatedString` — it is NOT a composable-level `Modifier.background()`.
This means `SpanStyle(background = Color.Transparent)` CAN remove the gray
background on inline code.

**Root cause of the "can't remove" bug:** `MarkdownStyling.create()` propagates
`inlinesStyling` only to `Paragraph`. Each `Heading.Hn.create()` creates its
OWN `InlinesStyling` with the default `inlineCodeBackgroundColor` (gray). So
inline code in **paragraphs** had the transparent background, but inline code
in **headings** still showed gray.

**Fix:** Pass `customInlinesStyling` explicitly to each heading level:
```kotlin
MarkdownStyling.create(
    inlinesStyling = customInlinesStyling,
    heading = Heading.create(
        h1 = Heading.H1.create(inlinesStyling = customInlinesStyling),
        h2 = Heading.H2.create(inlinesStyling = customInlinesStyling),
        // ... through h6
    ),
)
```

- **Files:** `MessageList.kt`
- **Key insight:** Any custom `InlinesStyling` MUST be passed to ALL block types
  that accept it (Paragraph, Heading.H1–H6, BlockQuote, List). Jewel's `create()`
  factory methods default to creating fresh `InlinesStyling` instances when the
  parameter is not explicitly provided.

### Ctrl+V / Clipboard Image Paste — Must Use IntelliJ AnAction, NOT Compose onPreviewKeyEvent

IntelliJ's action system (`IdeKeyEventDispatcher`) intercepts Ctrl+V (the `$Paste`
action) **before** Compose's `onPreviewKeyEvent` receives it. Registering Ctrl+V in
`Modifier.onPreviewKeyEvent` on a Compose `TextArea` simply never fires because
IntelliJ's keymap dispatching consumes the event first.

**Solution:** Register a `DumbAwareAction` with `CustomShortcutSet(Ctrl+V, Cmd+V)` on
the tool window's content component. The action emits a signal to the ViewModel
(`pasteImageSignal: SharedFlow`), which the Compose UI collects via `LaunchedEffect`
to check the clipboard for images and attach them.

- **Files:** `ChatToolWindowFactory.kt` (AnAction registration), `ChatViewModel.kt`
  (`pasteImageSignal` + `requestImagePaste()`), `ChatScreen.kt`
  (`LaunchedEffect` collecting signal), `InputArea.kt` (`readClipboardImage()`)
- **Warning:** Do NOT attempt to handle Ctrl+V via Compose `onPreviewKeyEvent` in
  an IntelliJ plugin — it does not work. The IDE action system consumes the event
  before it reaches the Compose layer.
- **Reference:** phodal/auto-dev uses the same pattern (`IdeaDevInInput.kt`) —
  `DumbAwareAction` + `registerCustomShortcutSet` for Ctrl+V on Swing components

### OpenCode Server API

- **Docs:** https://opencode.ai/docs/server/
- **OpenAPI spec:** http://127.0.0.1:4096/doc (when server is running)
- **Health check:** `GET /global/health` → `{ healthy: true, version: string }`
- **Create session:** `POST /session` → body `{ parentID?, title? }` → returns `Session` object
- **Session model:** `id` (required, starts with `ses_`), `slug`, `projectID`, `directory`, `title`, `version`, `time` (required), plus optional fields
- **Send message:** `POST /session/:id/message` → body `{ parts }` → returns message
- **SSE events:** `GET /event` (global) or `GET /global/event` → stream of typed events
- **TUI vs serve:** `opencode` starts TUI + server. `opencode serve` starts server only. Both expose the same HTTP API.

### ACP Mode (not used by this plugin)

- **Docs:** https://opencode.ai/docs/acp/
- `opencode acp` starts an ACP server communicating via JSON-RPC over stdio
- Used by Zed, JetBrains AI Assistant, Neovim plugins
- Our plugin uses `opencode serve` (HTTP REST + SSE) instead — gives more control over custom UI

### IntelliJ Platform Icons (AllIcons) — Confirmed Available

Icons referenced via `AllIcons.*` that are **known to compile** in this project.
Use `IntelliJIconKey.fromPlatformIcon(AllIcons.X.Y)` to wrap for Jewel `Icon`.

#### File Types
| Icon | Constant |
|------|----------|
| JavaScript | `AllIcons.FileTypes.JavaScript` |
| Java | `AllIcons.FileTypes.Java` |
| CSS | `AllIcons.FileTypes.Css` |
| HTML | `AllIcons.FileTypes.Html` |
| XML | `AllIcons.FileTypes.Xml` |
| JSON | `AllIcons.FileTypes.Json` |
| YAML | `AllIcons.FileTypes.Yaml` |
| Text (generic) | `AllIcons.FileTypes.Text` |
| Image | `AllIcons.FileTypes.Image` |

#### Language Icons
| Icon | Constant |
|------|----------|
| Kotlin | `AllIcons.Language.Kotlin` |
| Python | `AllIcons.Language.Python` |
| Ruby | `AllIcons.Language.Ruby` |
| Rust | `AllIcons.Language.Rust` |
| Go | `AllIcons.Language.GO` |
| Scala | `AllIcons.Language.Scala` |
| PHP | `AllIcons.Language.Php` |

#### Actions
| Icon | Constant |
|------|----------|
| Search | `AllIcons.Actions.Search` |
| Forward (chevron) | `AllIcons.Actions.Forward` |
| Copy | `AllIcons.Actions.Copy` |
| Close (×) | `AllIcons.Actions.Close` |
| Add (+) | `AllIcons.General.Add` |
| Suspend (stop) | `AllIcons.Actions.Suspend` |
| MoveUp (send arrow) | `AllIcons.Actions.MoveUp` |
| Execute (play) | `AllIcons.Actions.Execute` |
| Checked | `AllIcons.Actions.Checked` |
| Cancel | `AllIcons.Actions.Cancel` |
| Lightning | `AllIcons.Actions.Lightning` |

#### Nodes
| Icon | Constant |
|------|----------|
| Folder | `AllIcons.Nodes.Folder` |
| Console (terminal) | `AllIcons.Nodes.Console` |

#### General
| Icon | Constant |
|------|----------|
| BalloonError | `AllIcons.General.BalloonError` |
| BalloonInformation | `AllIcons.General.BalloonInformation` |

**Icons that DO NOT exist** (don't waste time looking for these):
- `AllIcons.FileTypes.Kotlin` — use `AllIcons.Language.Kotlin` instead
- `AllIcons.FileTypes.TypeScript` — use `AllIcons.FileTypes.JavaScript` instead
- `AllIcons.FileTypes.Markdown` — use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.Gradle` — use `AllIcons.Nodes.Folder` as fallback (no Gradle-specific icon confirmed)
- `AllIcons.FileTypes.Any_type` — use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.File` — use `AllIcons.FileTypes.Text` instead

### File Type Icon Mapping (Review Tab)

The Review tab uses file type icons for visual identification. Use `getFileTypeIcon()` in `ReviewPanel.kt`:

| Extension | Icon Constant |
|-----------|--------------|
| `.kt`, `.kts` | `AllIcons.Language.Kotlin` |
| `.java` | `AllIcons.FileTypes.Java` |
| `.xml` | `AllIcons.FileTypes.Xml` |
| `.json` | `AllIcons.FileTypes.Json` |
| `.yaml`, `.yml` | `AllIcons.FileTypes.Yaml` |
| `.md`, `.txt`, `.properties`, `.gitignore` | `AllIcons.FileTypes.Text` |
| `.js`, `.jsx`, `.ts`, `.tsx` | `AllIcons.FileTypes.JavaScript` |
| `.css` | `AllIcons.FileTypes.Css` |
| `.html`, `.htm` | `AllIcons.FileTypes.Html` |
| `.py` | `AllIcons.Language.Python` |
| `.rb` | `AllIcons.Language.Ruby` |
| `.rs` | `AllIcons.Language.Rust` |
| `.go` | `AllIcons.Language.GO` |
| `.scala` | `AllIcons.Language.Scala` |
| `.php` | `AllIcons.Language.Php` |
| `.svg`, `.png`, `.jpg`, etc. | `AllIcons.FileTypes.Image` |
| Default | `AllIcons.FileTypes.Text` |

---

## Deferred Features

### ConnectionState.RECONNECTING

- **Status:** Enum value defined, `ConnectionBannerComponent` has a UI branch for it, but no reconnect logic triggers it.
- **Action:** Implement exponential-backoff reconnection when the SSE stream drops or the health check fails intermittently.
- **Files:** `ChatModels.kt` (enum), `ConnectionBannerComponent.kt` (branch), `ChatViewModel.kt` (trigger logic).

### Image Content Support (v2)

- **Status:** Planned per TDD but not yet implemented.
- **Action:** Add `ImageContent` to message parts, render inline in chat messages.
- **Files:** `ChatModels.kt`, `MessageListComponent.kt`, `OpenCodeClient.kt`.

### Multi-Tab Sessions (v2)

- **Status:** Single active session only. Multi-tab planned for v2.
- **Action:** Allow multiple concurrent sessions with tab-based switching.
- **Files:** `ChatPanel.kt`, `ChatViewModel.kt`, `MessageListComponent.kt`.

### Tool Pill Detail Expansion

- **Status:** Tool pills show name + status. No expandable detail view.
- **Action:** Add click-to-expand showing tool input/output JSON.
- **Files:** `MessageListComponent.kt`, `ToolCallPill` model.

---

## Technical Debt

### `addBrowseFolderListener` Deprecation

- **Status:** `OpenCodeSettingsPanel.kt` uses the deprecated 4-arg overload.
- **Action:** Migrate to the newer `addBrowseFolderListener` with `Producer<Project?>` parameter.
- **File:** `OpenCodeSettingsPanel.kt:18`

### AcpServerConfig `parse()` Method

- **Status:** `AcpServerConfig.parse()` reads CLI args and env vars but is only used in `Main.kt` (standalone server mode). The IntelliJ plugin uses `OpenCodeSettingsState` instead.
- **Action:** Decide whether to keep the standalone server path or consolidate.
- **Files:** `AcpServerConfig.kt`, `Main.kt`

### Permission Bridge (ACP SDK)

- **Status:** `PermissionBridge.kt` exists but is unused — the chat UI uses `OpenCodeClient.respondPermission()` directly.
- **Action:** Either remove `PermissionBridge.kt` or reconcile with the ACP SDK approach.
- **File:** `PermissionBridge.kt`

---

## Completed (for reference)

- [x] Settings page (binary discovery, permission timeout)
- [x] Permission prompt UI with Allow/Reject/Always Allow
- [x] Dead code cleanup (configure(), setConnectionState(), getViewModel(), ProviderInfo, ModelInfo, OpenCodePlugin, BinaryDiscovery.verify())
- [x] Plugin always launches its own OpenCode instance on tool window open
- [x] Fixed SSE event parsing to match actual server format (message.part.delta, message.updated, etc.)
- [x] Fixed SVG icon (was symlink with @media style rules)
- [x] Fixed CSS rendering crash (Swing HTML renderer can't handle border-radius, margin, etc.)
- [x] Dark theme chat UI with proper typography and colors
- [x] Code block rendering with custom ChatFencedCodeBlock via MarkdownSegmenter (bypasses dead DefaultMarkdownBlockRenderer override)
- [x] Inline code gray background removed — fix was SpanStyle(background=Transparent) propagation to Heading.H1–H6 (not a composable-level background)
