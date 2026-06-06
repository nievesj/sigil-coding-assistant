# AGENTS.md — Tracked Items & Future Work

This document tracks incomplete features, deferred decisions, and known gaps
that are intentionally left for future implementation.

---

## Developer Notes

### Auto-Compaction is Server-Side

OpenCode handles context compaction automatically on the server. After each
assistant response, the server's prompt loop checks `compaction.isOverflow()`
(`packages/opencode/src/session/overflow.ts`) and, if token usage exceeds the
usable limit, creates a compaction task with `auto: true`. The plugin does NOT
need to detect overflow or trigger compaction — the server does it transparently.

The plugin's context indicator shows usage percentage (from the last assistant
message's tokens vs. the model's context limit) for informational purposes only.
There is no `/compact` command or overflow banner — compaction is automatic.

- **Server source:** `packages/opencode/src/session/prompt.ts` (lines 1322-1328),
  `packages/opencode/src/session/overflow.ts`, `packages/opencode/src/session/compaction.ts`
- **Config:** `compaction.auto` defaults to `true`; can be disabled server-side
- **Warning:** Do NOT re-add client-side overflow detection or a `/compact` command.
  The server already handles this. Adding client-side compaction would be redundant
  and could race with the server's auto-compaction.

### runIde Log Location

Logs for the plugin when running via `runIde` are NOT in the main IDEA log.
They are in the sandbox directory:

```
.intellijPlatform/sandbox/intellij-opencode-plugin/IU-2026.1/log/idea.log
```

### Installed Plugin Log Location

When the plugin is installed from a zip (not runIde), logs go to the main
IDEA installation log:

```
C:\Users\<username>\AppData\Local\JetBrains\IntelliJIdea<version>\log\idea.log
```

For this project's developer machine: `C:\Users\josen\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log`

To search for plugin-specific log lines, grep for `[ACP]`:
```bash
Select-String -Path "C:\Users\josen\AppData\Local\JetBrains\IntelliJIdea2026.1\log\idea.log" -Pattern "\[ACP\]"
```

To enable verbose SSE debugging in the installed plugin, add to
`Help → Debug Log Settings`:
```
#com.opencode.acp.adapter.OpenCodeClient=debug
```

### Plugin Logging Convention

All plugin logging must use `logger.info {}` / `logger.error {}` / `logger.debug {}`
(from `io.github.oshai.kotlinlogging.KotlinLogging`). This writes to IntelliJ's
`idea.log` (Help → Show Log in Finder/Explorer), which is visible regardless of
how the plugin is launched (runIde or installed zip).

- **Do NOT use `println()`** — only visible in Run console when launched via `runIde`.
  When installed from a zip, println output goes nowhere useful.
- **Do NOT use `java.io.File.appendText()`** — temp files are ephemeral and hard to
  discover.
- **Do NOT use `System.err.println()`** — same problem as println.
- **DO use `logger.info { "[ACP] ..." }`** — prefix with `[ACP]` for grep-friendly
  filtering in idea.log.
- **For SSE debug logging**, use `logger.debug {}` (not a temp file).

To enable verbose SSE logging in idea.log, add to Help → Debug Log Settings:
```
#com.opencode.acp.adapter.OpenCodeClient=debug
```

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

### Markdown Streaming: StreamHealer for Inline Formatting

During streaming, incomplete markdown (unclosed `**`, backticks, partial links)
causes raw syntax to flash in the UI because the CommonMark parser treats unclosed
delimiters as literal text.

**Solution:** `StreamHealer.heal()` is called on streaming message content before
segmentation. It closes unclosed backticks, bold markers, and strips incomplete
link syntax (`[text](` without closing `)`). This mirrors OpenCode's `remend`
library behavior.

`MessageList.kt` uses `MarkdownSegmenter.segmentHealed()` (which calls
`StreamHealer.heal()` then `segment()`) for streaming messages, and the regular
`segment()` for completed messages.

- **Files:** `StreamHealer.kt`, `MarkdownSegmenter.kt`, `MessageList.kt`
- **Why not in Jewel:** Jewel's `Markdown` composable re-parses on each recomposition.
  Healing before parsing eliminates the visual "flash of raw syntax" during streaming.

### Markdown Tables: Inline Formatting and Column Alignment

Table cells now render inline markdown (bold, italic, code, strikethrough) via
`InlineMarkdownText` composable, and column alignment (`:---`, `:---:`, `---:`)
is now applied to header and data cells.

- **Files:** `InlineMarkdownText.kt`, `TableRenderer.kt`, `MarkdownSegmenter.kt`
- **Note:** `InlineMarkdownText` strips delimiters and applies `SpanStyle` directly
  — it does NOT use Jewel's `Markdown` composable. This avoids the overhead of
  creating a full markdown processor per table cell.

### Markdown Segmenter: Leading Newline Fix

`MarkdownSegmenter.flushText()` now uses `trim('\n', '\r')` instead of
`trimEnd('\n', '\r')` to eliminate leading newlines that caused empty paragraph
artifacts between code blocks and text segments.

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

### SSE Reconnection — Automatic with Exponential Backoff

When the SSE stream (`/event`) drops unexpectedly (not cancelled by user action),
`startSseSubscription()` detects the stream end and calls `triggerReconnect()`.
This sets `ConnectionState.RECONNECTING`, which the `ConnectionBanner` renders as
"Reconnecting..." (no Retry link — reconnection is automatic).

**Reconnection strategy:**
1. Health check with exponential backoff: 1s → 2s → 4s → ... → 30s cap
2. ±20% random jitter on each delay to prevent synchronization
3. On success: re-subscribe SSE, set `CONNECTED`
4. On scope cancellation or client loss: set `ERROR`, `initialized = false`
5. In-flight streaming responses are aborted with an error message via `abortInFlightResponse()`

**Key guards:**
- `CancellationException` in `startSseSubscription()` is re-thrown (no reconnect on user-initiated cancel)
- `triggerReconnect()` checks `isActive && initialized && sessionId == capturedId` before firing
- `reconnectJob` is cancelled in `close()`, `switchSession()`, `createAndSwitchSession()`
- `retryConnection()` (for the "Retry" button in ERROR state) does full `close()` + `initialize()`

**Constants:** `ChatConstants.RECONNECT_DELAY_MS = 1000`, `RECONNECT_MAX_DELAY_MS = 30000`

- **Files:** `ChatViewModel.kt` (`triggerReconnect`, `calculateBackoff`, `abortInFlightResponse`, `retryConnection`), `ConnectionBanner.kt` (RECONNECTING branch), `ChatScreen.kt` (onRetry → retryConnection)

### SSE V2 SyncEvent Wire Format — Critical Parsing Fix

OpenCode server sends two different SSE wire formats depending on event version:

**V1 BusEvents** (legacy):
```json
{ "type": "message.part.delta", "properties": { "sessionID": "...", "field": "text", "delta": "..." } }
```

**V2 SyncEvents** (new):
```json
{ "id": "...", "seq": 1, "type": "session.next.text.delta.1", "aggregateID": "...", "data": { "timestamp": "...", "sessionID": "...", "delta": "..." } }
```

**Key differences:**
1. V2 types have a version suffix: `"session.next.text.delta.1"` (not `"session.next.text.delta"`)
2. V2 payload is in `"data"` (not `"properties"`)
3. V2 puts `sessionID` inside `data` (not at root or in `properties`)

**Actual server behavior (as of June 2026):** The OpenCode server sends V1 BusEvents exclusively, NOT V2 SyncEvents. All events use `"properties"` wrapper. Tool parts use `"type": "tool"` (not `"tool_use"`) with `"callID"` (not `"id"`), `"state"` (running/completed/failed), and `"tool"` (not `"name"`). The V2 parsing code is retained for future compatibility but the server currently uses V1 only.

**Detection logic:** Check for `jsonObj["data"]` (V2) vs `jsonObj["properties"]` (V1). For V2, strip the `.N` version suffix from the type and extract all fields from the `data` object.

**Without this fix:** Every V2 event (thinking, tools, text streaming) was silently dropped because (a) `sessionId` extraction failed, and (b) versioned type names didn't match any `when` branch.

**Tool pill deduplication:** Both `session.next.tool.input.started` and `session.next.tool.called` fire for the same tool call. Only `called` creates a pill (it has full data including tool name and input). `input.started` is skipped to avoid duplicates.

**V1 tool part format:** `message.part.updated` with `part.type = "tool"` uses `part.callID`, `part.tool`, `part.state` (running/completed/failed), and `part.input`. This differs from the documented `"tool_use"` + `"id"` + `"name"` format.

- **Files:** `OpenCodeClient.kt` (`subscribeGlobalEvents` — V2 format detection + version suffix stripping + `data` extraction), `ChatViewModel.kt` (`addToolCallPill` — deduplication by `toolCallId`)

### Question/Selection Prompt — `question.asked` SSE Event

The OpenCode server sends `question.asked` events when the agent uses the `question` tool to ask the user for input (multiple-choice or freeform). The plugin renders these as `SelectionPrompt` inline in the chat.

**Wire format (V1 BusEvent):**
```json
{
  "type": "question.asked",
  "properties": {
    "id": "que_abc123",
    "sessionID": "ses_xyz789",
    "questions": [{
      "question": "What is your favorite animal?",
      "header": "Animal choice",
      "options": [{ "label": "Dog", "description": "Loyal companion" }],
      "multiple": false,
      "custom": true
    }]
  }
}
```

**Response endpoints:**
- `POST /question/:requestID/reply` — body: `{ "answers": [["selectedLabel"]] }` (one array per question, containing label strings)
- `POST /question/:requestID/reject` — empty body

**Data flow:**
1. SSE `question.asked` → `SseEvent.QuestionAsked` → sets `_selectionPrompt` StateFlow
2. `SelectionPrompt` composable renders the UI
3. User selects options → `respondSelection()` → `client.respondQuestion(requestId, answers)` or `client.rejectQuestion(requestId)`

**Key detail:** `SelectionOption.label` stores the server's `label` field for round-tripping in the answer payload. The UI displays `title` (which defaults to `label`).

- **Files:** `SseEvent.kt` (`QuestionAsked`, `SseQuestionInfo`, `SseQuestionOption`), `OpenCodeClient.kt` (`respondQuestion`, `rejectQuestion`, `question.asked` parsing), `ChatViewModel.kt` (`_selectionPrompt`, `respondSelection`), `ChatModels.kt` (`SelectionPrompt`, `SelectionOption`, `SelectionResponse`), `SelectionPrompt.kt` (UI), `ChatScreen.kt` (wiring)

### MIME Type Detection for File Attachments

`URLConnection.guessContentTypeFromName()` only knows ~20 common extensions and returns `null` (→ `application/octet-stream`) for most source code files (`.kt`, `.json`, `.yaml`, `.py`, `.rs`, `.go`, `.ts`, `.tsx`, etc.). The server rejects `application/octet-stream` for file parts.

**Solution:** `MimeTypes.guessFromFileName()` in `util/MimeTypes.kt` provides a comprehensive extension→MIME mapping for 80+ development file types, falling back to `URLConnection` and then `application/octet-stream`.

- **Files:** `util/MimeTypes.kt`, `ChatScreen.kt:454` (`addFileAttachment`), `InputArea.kt:891` (`toAttachedFile`)
- **Warning:** Do NOT revert to `URLConnection.guessContentTypeFromName()` alone — it causes "file part media type application/octet-stream" errors for most dev files.

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

### Todo List Panel — Collapsible Status Indicators

The todo panel shows active (non-completed, non-cancelled) tasks from
`GET /session/:id/todo`. It auto-collapses when >4 incomplete items exist,
showing only the first 2 and a "+N more…" hint.

**Status indicators:** `✓` completed (green), `•` in_progress (amber), `○` pending (gray),
`✗` cancelled (dim gray).

**Data flow:**
1. SSE `todo.updated` event → updates `_todoItems` StateFlow in ChatViewModel
2. `fetchTodos()` called on init, session switch, and after each response
3. `TodoListPanel` composable reads `todoItems` and renders header + items
4. Clicking header toggles expanded/collapsed state

- **Files:** `TodoListPanel.kt`, `ChatViewModel.kt` (`_todoItems`, `fetchTodos()`),
  `ChatScreen.kt` (wires `todoItems` to `TodoListPanel`), `ChatModels.kt` (`TodoItem`),
  `OpenCodeClient.kt` (`getTodos()`), `SseEvent.kt` (`SseEvent.TodoUpdated`)

### Slash Command Palette — `/` Prefix Triggering

Typing `/` at the start of the input field shows a popup palette with slash commands.
The palette filters as the user types (e.g., `/cl` shows `/clear`).

**Command sources:**
1. **Local commands** (handled by plugin, not sent to server): `/clear`, `/cancel`
2. **Server commands** (fetched from `GET /command`): `/init`, `/review`, `/simplify`, etc.
   — any command the OpenCode server exposes dynamically

**Flow:**
1. On init and session switch, `ChatViewModel.fetchAvailableCommands()` calls
   `client.listCommands()` (GET /command) and stores results in `_availableCommands`
   StateFlow as `SlashCommand(isServerCommand = true)` instances.
2. `ChatScreen.kt` merges local commands (clear/cancel) with server commands
   (`localCommands + availableCommands`) and passes the combined list to `InputArea`.
3. `InputArea` passes the list to `SlashCommandPalette` for filtering/display.
4. On selection: local commands route to ViewModel methods
   (`createAndSwitchSession()`, `cancel()`). Server commands route to
   `executeServerCommand(name)` → `client.executeCommand(sessionId, name, "")`.

**Key detail:** `CommandInfo.id` (not `name`) is the command string sent to the server.

- **Files:** `SlashCommandPalette.kt` (data class + composable), `ChatViewModel.kt`
  (`_availableCommands`, `fetchAvailableCommands()`, `executeServerCommand()`),
  `InputArea.kt` (palette state + Popup + `commands` param),
  `ChatScreen.kt` (merges commands + `onSlashCommand` handler)

- **Docs:** https://opencode.ai/docs/server/
- **OpenAPI spec:** http://127.0.0.1:4096/doc (when server is running)
- **Health check:** `GET /global/health` → `{ healthy: true, version: string }`
- **Create session:** `POST /session` → body `{ parentID?, title? }` → returns `Session` object
- **Session model:** `id` (required, starts with `ses_`), `slug`, `projectID`, `directory`, `title`, `version`, `time` (required), plus optional fields
- **Send message:** `POST /session/:id/message` → body `{ parts }` → returns message
- **List commands:** `GET /command` → returns `CommandInfo[]` (each with `id`, `name`, `description`)
- **Execute command:** `POST /session/:id/command` → body `{ command, arguments }` → returns message
- **SSE events:** `GET /event` (global) or `GET /global/event` → stream of typed events
- **Todo events:** SSE `todo.updated` event sends `{ type: "todo.updated", properties: { todos: [...] } }` with same schema as GET
- **OpenCodeAgentSession:** `SseEvent.TodoUpdated` branch added to exhaustive `when` (informational only for ACP path; chat UI handles via ChatViewModel)
- **TUI vs serve:** `opencode` starts TUI + server. `opencode serve` starts server only. Both expose the same HTTP API.

### Input Command History — Up/Down Arrow Navigation

The input area remembers past commands (including file attachments) and lets the
user recall them with Up/Down arrow keys, similar to a shell history.

**Behavior:**
- **Up arrow** at the input: saves the current draft (text + files), then loads
  the next older history entry.
- **Down arrow** while navigating: loads the next newer entry. At the newest
  entry, restores the saved draft.
- **Escape** while navigating: restores the draft and exits history mode.
- **Enter** (send): sends the current text + files, exits history mode, and
  resets the history index.
- History only cycles when the slash command palette is NOT visible.
- Deduplication: sending the same text again moves the existing entry to the
  front rather than creating a duplicate.

**Persistence:** History is stored in `OpenCodeSettingsState` (`commandHistory:
ArrayList<CommandHistoryEntry>`), which is an XStream-serialized
`PersistentStateComponent`. The entries survive IDE restarts.

**History size setting:** Configurable in `Settings → Tools → OpenCode` as
"Command history size" (default 15, clamped to 1–100). Changing the setting
trims the history on the next `recordCommand` call.

**`CommandHistoryEntry`** is a non-data class with parallel `ArrayList<String>`
fields (`attachedFileNames`, `attachedFilePaths`, `attachedFileMimes`,
`attachedFileDataUris`) for XStream compatibility. `toAttachedFiles()` reconstructs
the original `List<AttachedFile>`.

**Bug fix included:** The `onSend` callback signature changed from `(String) -> Unit`
to `(String, List<AttachedFile>) -> Unit`. Previously, clicking the green Send
button or pressing Enter did NOT pass attached files — they were silently lost.
Now both paths pass the current `attachedFiles` list to `onSend`.

- **Files:** `ChatModels.kt` (`CommandHistoryEntry`), `OpenCodeSettingsState.kt`
  (`commandHistorySize`, `commandHistory`), `OpenCodeSettingsPanel.kt`
  (`commandHistorySizeField`), `ChatViewModel.kt` (`_commandHistory`,
  `recordCommand()`), `InputArea.kt` (Up/Down key handlers, `commandHistory` +
  `onLoadHistoryEntry` params, `onSend` signature), `ChatScreen.kt` (wiring)

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

### Image Content Support (v2)

- **Status:** Planned per TDD but not yet implemented.
- **Action:** Add `ImageContent` to message parts, render inline in chat messages.
- **Files:** `ChatModels.kt`, `MessageListComponent.kt`, `OpenCodeClient.kt`.

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

- **Status:** `PermissionBridge.kt` is used by the ACP SDK path (`OpenCodeAgentSession`, `OpenCodeAgentSupport`). The chat UI uses `OpenCodeClient.respondPermission()` directly. Both paths are valid for their respective use cases.
- **Action:** No action needed — `PermissionBridge` serves the ACP SDK integration, not the chat UI.
- **File:** `PermissionBridge.kt`

### CommandInfo Deserialization — Missing `id` Field

- **Status:** `GET /command` returns `CommandInfo` objects without an `id` field. The `CommandInfo` data class (`OpenCodeModels.kt:261`) requires `id` as a non-optional field, causing `MissingFieldException` on deserialization. The slash command palette fails to populate with server commands.
- **Action:** Make `id` optional in `CommandInfo` (e.g., `val id: String? = null`) or add `@JsonIgnoreUnknownKeys` / fallback. The server response only has `name` and `description`.
- **File:** `OpenCodeModels.kt:261` (`CommandInfo` data class), `OpenCodeClient.kt:1365` (`listCommands()`)
- **Impact:** Non-critical — local commands (`/clear`, `/cancel`) still work. Server commands (`/init`, `/review`, `/simplify`, etc.) are unavailable in the palette.

---

## Completed (for reference)

- [x] Settings page (binary discovery, permission timeout)
- [x] Permission prompt UI with Allow/Reject/Always Allow
- [x] Dead code cleanup (configure(), setConnectionState(), getViewModel(), ProviderInfo, ModelInfo, OpenCodePlugin, BinaryDiscovery.verify())
- [x] Plugin always launches its own OpenCode instance on tool window open
- [x] Fixed SSE event parsing to match actual server format (message.part.delta, message.updated, etc.)
- [x] Fixed SSE V2 SyncEvent parsing — V2 events use `data` wrapper + versioned type names (`.1` suffix) instead of `properties` wrapper; all V2 events (thinking, tools, text) were silently dropped
- [x] Fixed SVG icon (was symlink with @media style rules)
- [x] Fixed CSS rendering crash (Swing HTML renderer can't handle border-radius, margin, etc.)
- [x] Dark theme chat UI with proper typography and colors
- [x] Code block rendering with custom ChatFencedCodeBlock via MarkdownSegmenter (bypasses dead DefaultMarkdownBlockRenderer override)
- [x] Inline code gray background removed — fix was SpanStyle(background=Transparent) propagation to Heading.H1–H6 (not a composable-level background)
- [x] Context usage indicator (doughnut ring + tooltip + sidebar panel) — auto-compaction is server-side
- [x] SSE `todo.updated` event handling + `GET /session/:id/todo` fetch
- [x] Todo list panel (collapsible, `✓`/`•`/`○` status indicators, auto-collapse >4 items)
- [x] Slash command palette (`/clear`, `/cancel`) triggered by `/` prefix in input
- [x] StreamHealer for inline markdown formatting during streaming
- [x] Question/selection prompt wired to server (`question.asked` SSE → `POST /question/:id/reply` or `/reject`)
- [x] MIME type detection for file attachments — `MimeTypes.guessFromFileName()` replaces `URLConnection.guessContentTypeFromName()` (which returns `application/octet-stream` for most dev files)
- [x] Markdown tables with column alignment and inline formatting via InlineMarkdownText
- [x] SSE reconnection with exponential backoff (1s→2s→4s→...→30s cap, ±20% jitter, abort in-flight response, retryConnection for ERROR state)
- [x] Input command history with Up/Down arrow navigation (configurable size, persists with attachments, draft save/restore, `onSend` signature fixed to pass files)
- [x] `toChatMessage()` now uses `ToolMapper.toAcpKind()` instead of hardcoded `ToolKind.OTHER` — historical tool pills match live ones
- [x] `ToolPill` defaults collapsed (`expanded = false`) — was defaulting expanded
- [x] `SseEvent.ToolResult.content` now populated from V2 and V1 SSE events — tool output visible in expanded pills
- [x] `handleSseEvent()` no longer drops `TodoUpdated`/`QuestionAsked`/`SessionCreated`/`UserMessage` when `activeAssistantMessageId` is null — these events are handled before the null check
- [x] `SubagentStatus` now inferred from session token usage (`outputTokens > 0` → COMPLETED, else RUNNING) — was always RUNNING
- [x] `CommandHistoryEntry` dedup now compares `attachedFileDataUris` in addition to paths — clipboard images with same text no longer falsely deduped
- [x] Removed unreachable `MimeTypes` compound extension entry `"gradle.kts"` (never matched by `substringAfterLast('.')`)
- [x] Removed empty `loadSettings()` method and its call from `ChatViewModel.initialize()`
- [x] Removed all `println()` debug statements from production code (ChatScreen.kt, InputArea.kt, AttachMenu.kt, OpenCodeSettingsState.kt, ProviderIconLoader.kt)
- [x] `SseEventListener` now handles V2 event types (`session.next.*`) and passes `patterns` to `SseEvent.Permission`
- [x] `addMessage()` FIFO eviction now rebuilds `toolCallIndex` alongside `messageIndex` — no stale entries after 500+ messages
- [x] `pendingFileChanges` cleared on session switch via `resetSessionState()` — no memory leak across sessions
- [x] `addShutdownHook` only registered once (stored in `shutdownHook` field) — no thread leak on re-initialization
- [x] `respondPermission`/`respondQuestion`/`rejectQuestion` in `OpenCodeClient` now throw on failure instead of swallowing — ViewModel catch blocks are reachable
- [x] `SessionItem.createdAt` renamed to `updatedAt` (was actually `time.updated`, not `time.created`)
- [x] `SessionContext` fields (`additions`/`deletions`/`filesModified`/`sessionCreated`/`lastUpdated`) now populated from `GET /session/:id` summary — were hardcoded to 0
