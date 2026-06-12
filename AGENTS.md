# AGENTS.md â€” Tracked Items & Future Work

This document tracks incomplete features, deferred decisions, and known gaps
that are intentionally left for future implementation.

---

## Developer Notes

### Auto-Compaction is Server-Side

OpenCode handles context compaction automatically on the server. After each
assistant response, the server's prompt loop checks `compaction.isOverflow()`
(`packages/opencode/src/session/overflow.ts`) and, if token usage exceeds the
usable limit, creates a compaction task with `auto: true`. The plugin does NOT
need to detect overflow or trigger compaction â€” the server does it transparently.

The plugin's context indicator shows usage percentage (from the last assistant
message's tokens vs. the model's context limit) for informational purposes only.
There is no `/compact` command or overflow banner â€” compaction is automatic.

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
`Help â†’ Debug Log Settings`:
```
#com.opencode.acp.adapter.OpenCodeClient=debug
```

### Plugin Logging Convention

All plugin logging must use `logger.info {}` / `logger.error {}` / `logger.debug {}`
(from `io.github.oshai.kotlinlogging.KotlinLogging`). This writes to IntelliJ's
`idea.log` (Help â†’ Show Log in Finder/Explorer), which is visible regardless of
how the plugin is launched (runIde or installed zip).

- **Do NOT use `println()`** â€” only visible in Run console when launched via `runIde`.
  When installed from a zip, println output goes nowhere useful.
- **Do NOT use `java.io.File.appendText()`** â€” temp files are ephemeral and hard to
  discover.
- **Do NOT use `System.err.println()`** â€” same problem as println.
- **DO use `logger.info { "[ACP] ..." }`** â€” prefix with `[ACP]` for grep-friendly
  filtering in idea.log.
- **For SSE debug logging**, use `logger.debug {}` (not a temp file).

To enable verbose SSE logging in idea.log, add to Help â†’ Debug Log Settings:
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
via `mapLanguageId()` (e.g., "css" â†’ "CSS", "js" â†’ "JavaScript").

- **Files:** `MarkdownSegmenter.kt`, `MessageList.kt`, `CodeBlockRenderer.kt`
- **Deleted:** `ChatMarkdownBlockRenderer.kt` (dead override code)
- **Warning:** Do NOT attempt to override `DefaultMarkdownBlockRenderer` methods
  for code blocks again â€” it compiles but does not dispatch at runtime in IU-261.

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
  â€” it does NOT use Jewel's `Markdown` composable. This avoids the overhead of
  creating a full markdown processor per table cell.

### Markdown Segmenter: Leading Newline Fix

`MarkdownSegmenter.flushText()` now uses `trim('\n', '\r')` instead of
`trimEnd('\n', '\r')` to eliminate leading newlines that caused empty paragraph
artifacts between code blocks and text segments.

### Jewel Markdown: Inline Code Background (InlinesStyling Propagation)

Jewel renders inline code backgrounds via `SpanStyle.background` in
`AnnotatedString` â€” it is NOT a composable-level `Modifier.background()`.
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
  that accept it (Paragraph, Heading.H1â€“H6, BlockQuote, List). Jewel's `create()`
  factory methods default to creating fresh `InlinesStyling` instances when the
  parameter is not explicitly provided.

### SSE Reconnection â€” Automatic with Exponential Backoff + Idle Detection

When the SSE stream (`/event`) drops unexpectedly (not cancelled by user action),
`startGlobalSseSubscription()` detects the stream end and calls `triggerGlobalSseReconnect()`.
This sets `ConnectionState.RECONNECTING`, which the `ConnectionBanner` renders as
"Reconnecting..." (no Retry link â€” reconnection is automatic).

**Reconnection strategy:**
1. Health check with exponential backoff: 1s â†’ 2s â†’ 4s â†’ ... â†’ 30s cap
2. Â±20% random jitter on each delay to prevent synchronization
3. On success: re-subscribe SSE, set `CONNECTED`
4. On scope cancellation or client loss: set `ERROR`, `initialized = false`
5. In-flight streaming responses are aborted with an error message via `abortInFlightResponse()`

**SSE health-check probes (client-side):** The Java HTTP engine has no socket-level idle
timeout (see TDD Â§4.2.1), so half-open TCP connections can go undetected indefinitely.
The plugin uses periodic health-check probes to detect dead connections without killing
healthy ones during normal user thinking time.

When the SSE connection has been silent for `SSE_HEALTH_CHECK_INTERVAL_MS` (60 seconds),
the plugin sends a lightweight `GET /global/health` to verify the server is alive. If the
health check succeeds, the connection is fine and the timer resets. If it fails, the SSE
job is cancelled, which triggers automatic reconnection via `launchSseJob()`.

This replaces the old idle-detection approach that proactively killed connections after
120s of silence â€” which was a false positive during normal user thinking time and also
failed to trigger reconnection (the `CancellationException` re-throw bypassed the
reconnection code).

**Key design:**
- `launchSseJob()` â€” shared function used by both `startGlobalSseSubscription()` and
  `triggerGlobalSseReconnect()`. Prevents code divergence between the two paths.
  Handles stream end by triggering reconnection for both unexpected errors and
  cancellation (checked via `isActive` after the catch block).
- `launchHealthCheck()` â€” periodic probe coroutine. Only fires when SSE has been
  silent for the full interval. Resets the timer on success.
- `CancellationException` is no longer re-thrown â€” it's caught alongside other
  exceptions. After the catch, `isActive` distinguishes user-initiated stop
  (scope cancelled â†’ skip reconnect) from unexpected stream end (scope active â†’ reconnect).

**Constants:** `ChatConstants.RECONNECT_DELAY_MS = 1000`, `RECONNECT_MAX_DELAY_MS = 30000`, `SSE_HEALTH_CHECK_INTERVAL_MS = 60000`, `SSE_HEALTH_CHECK_TIMEOUT_MS = 10000`

- **Files:** `OpenCodeService.kt` (`startGlobalSseSubscription`, `launchSseJob`, `launchHealthCheck`, `triggerGlobalSseReconnect`, `sseLastEventTimeMs`), `OpenCodeClient.kt` (timing logs in `subscribeGlobalEvents`), `ChatConstants.kt` (`SSE_HEALTH_CHECK_INTERVAL_MS`, `SSE_HEALTH_CHECK_TIMEOUT_MS`), `ConnectionBanner.kt` (RECONNECTING branch), `ChatScreen.kt` (onRetry â†’ retryConnection)

### SSE V2 SyncEvent Wire Format â€” Critical Parsing Fix

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

- **Files:** `OpenCodeClient.kt` (`subscribeGlobalEvents` â€” V2 format detection + version suffix stripping + `data` extraction), `ChatViewModel.kt` (`addToolCallPill` â€” deduplication by `toolCallId`)

### Question/Selection Prompt â€” `question.asked` SSE Event

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
- `POST /question/:requestID/reply` â€” body: `{ "answers": [["selectedLabel"]] }` (one array per question, containing label strings)
- `POST /question/:requestID/reject` â€” empty body

**Data flow:**
1. SSE `question.asked` â†’ `SseEvent.QuestionAsked` â†’ sets `_selectionPrompt` StateFlow
2. `SelectionPrompt` composable renders the UI
3. User selects options â†’ `respondSelection()` â†’ `client.respondQuestion(requestId, answers)` or `client.rejectQuestion(requestId)`

**Key detail:** `SelectionOption.label` stores the server's `label` field for round-tripping in the answer payload. The UI displays `title` (which defaults to `label`).

- **Files:** `SseEvent.kt` (`QuestionAsked`, `SseQuestionInfo`, `SseQuestionOption`), `OpenCodeClient.kt` (`respondQuestion`, `rejectQuestion`, `question.asked` parsing), `ChatViewModel.kt` (`_selectionPrompt`, `respondSelection`), `ChatModels.kt` (`SelectionPrompt`, `SelectionOption`, `SelectionResponse`), `SelectionPrompt.kt` (UI), `ChatScreen.kt` (wiring)

### MIME Type Detection for File Attachments

`URLConnection.guessContentTypeFromName()` only knows ~20 common extensions and returns `null` (â†’ `application/octet-stream`) for most source code files (`.kt`, `.json`, `.yaml`, `.py`, `.rs`, `.go`, `.ts`, `.tsx`, etc.). The server rejects `application/octet-stream` for file parts.

**Solution:** `MimeTypes.guessFromFileName()` in `util/MimeTypes.kt` provides a comprehensive extensionâ†’MIME mapping for 80+ development file types, falling back to `URLConnection` and then `application/octet-stream`.

- **Files:** `util/MimeTypes.kt`, `ChatScreen.kt:454` (`addFileAttachment`), `InputArea.kt:891` (`toAttachedFile`)
- **Warning:** Do NOT revert to `URLConnection.guessContentTypeFromName()` alone â€” it causes "file part media type application/octet-stream" errors for most dev files.

### Ctrl+V / Clipboard Image Paste â€” Must Use IntelliJ AnAction, NOT Compose onPreviewKeyEvent

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
  an IntelliJ plugin â€” it does not work. The IDE action system consumes the event
  before it reaches the Compose layer.
- **Reference:** phodal/auto-dev uses the same pattern (`IdeaDevInInput.kt`) â€”
  `DumbAwareAction` + `registerCustomShortcutSet` for Ctrl+V on Swing components

### Todo List Panel â€” Collapsible Status Indicators

The todo panel shows active (non-completed, non-cancelled) tasks from
`GET /session/:id/todo`. It auto-collapses when >4 incomplete items exist,
showing only the first 2 and a "+N moreâ€¦" hint.

**Status indicators:** `âœ“` completed (green), `â€¢` in_progress (amber), `â—‹` pending (gray),
`âœ—` cancelled (dim gray).

**Data flow:**
1. SSE `todo.updated` event â†’ updates `_todoItems` StateFlow in ChatViewModel
2. `fetchTodos()` called on init, session switch, and after each response
3. `TodoListPanel` composable reads `todoItems` and renders header + items
4. Clicking header toggles expanded/collapsed state

- **Files:** `TodoListPanel.kt`, `ChatViewModel.kt` (`_todoItems`, `fetchTodos()`),
  `ChatScreen.kt` (wires `todoItems` to `TodoListPanel`), `ChatModels.kt` (`TodoItem`),
  `OpenCodeClient.kt` (`getTodos()`), `SseEvent.kt` (`SseEvent.TodoUpdated`)

### Slash Command Palette â€” `/` Prefix Triggering

Typing `/` at the start of the input field shows a popup palette with slash commands.
The palette filters as the user types (e.g., `/cl` shows `/clear`).

**Command sources:**
1. **Local commands** (handled by plugin, not sent to server): `/clear`, `/cancel`
2. **Server commands** (fetched from `GET /command`): `/init`, `/review`, `/simplify`, etc.
   â€” any command the OpenCode server exposes dynamically

**Flow:**
1. On init and session switch, `ChatViewModel.fetchAvailableCommands()` calls
   `client.listCommands()` (GET /command) and stores results in `_availableCommands`
   StateFlow as `SlashCommand(isServerCommand = true)` instances.
2. `ChatScreen.kt` merges local commands (clear/cancel) with server commands
   (`localCommands + availableCommands`) and passes the combined list to `InputArea`.
3. `InputArea` passes the list to `SlashCommandPalette` for filtering/display.
4. On selection: local commands route to ViewModel methods
   (`createAndSwitchSession()`, `cancel()`). Server commands route to
   `executeServerCommand(name)` â†’ `client.executeCommand(sessionId, name, "")`.

**Key detail:** `CommandInfo.id` (not `name`) is the command string sent to the server.

- **Files:** `SlashCommandPalette.kt` (data class + composable), `ChatViewModel.kt`
  (`_availableCommands`, `fetchAvailableCommands()`, `executeServerCommand()`),
  `InputArea.kt` (palette state + Popup + `commands` param),
  `ChatScreen.kt` (merges commands + `onSlashCommand` handler)

- **Docs:** https://opencode.ai/docs/server/
- **OpenAPI spec:** http://127.0.0.1:4096/doc (when server is running)
- **Health check:** `GET /global/health` â†’ `{ healthy: true, version: string }`
- **Create session:** `POST /session` â†’ body `{ parentID?, title? }` â†’ returns `Session` object
- **Session model:** `id` (required, starts with `ses_`), `slug`, `projectID`, `directory`, `title`, `version`, `time` (required), plus optional fields
- **Send message:** `POST /session/:id/message` â†’ body `{ parts }` â†’ returns message
- **List commands:** `GET /command` â†’ returns `CommandInfo[]` (each with `id`, `name`, `description`)
- **Execute command:** `POST /session/:id/command` â†’ body `{ command, arguments }` â†’ returns message
- **SSE events:** `GET /event` (global) or `GET /global/event` â†’ stream of typed events
- **Todo events:** SSE `todo.updated` event sends `{ type: "todo.updated", properties: { todos: [...] } }` with same schema as GET
- **OpenCodeAgentSession:** `SseEvent.TodoUpdated` branch added to exhaustive `when` (informational only for ACP path; chat UI handles via ChatViewModel)
- **TUI vs serve:** `opencode` starts TUI + server. `opencode serve` starts server only. Both expose the same HTTP API.

### Input Command History â€” Up/Down Arrow Navigation

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

**History size setting:** Configurable in `Settings â†’ Tools â†’ OpenCode` as
"Command history size" (default 15, clamped to 1â€“100). Changing the setting
trims the history on the next `recordCommand` call.

**`CommandHistoryEntry`** is a non-data class with parallel `ArrayList<String>`
fields (`attachedFileNames`, `attachedFilePaths`, `attachedFileMimes`,
`attachedFileDataUris`) for XStream compatibility. `toAttachedFiles()` reconstructs
the original `List<AttachedFile>`.

**Bug fix included:** The `onSend` callback signature changed from `(String) -> Unit`
to `(String, List<AttachedFile>) -> Unit`. Previously, clicking the green Send
button or pressing Enter did NOT pass attached files â€” they were silently lost.
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
- Our plugin uses `opencode serve` (HTTP REST + SSE) instead â€” gives more control over custom UI

### LazyColumn items(count, key) â€” Stale Data When Keys Are Stable

**Problem:** Compose Foundation's `LazyColumn` with `items(count, key)` does NOT
re-render items that stay in the viewport when only the underlying data changes
(same count, same keys). LazyColumn reuses the existing item composition without
re-evaluating the item content lambda, so the composable renders stale data.

**Symptoms:**
- Thinking pill disappears when tool pills appear (data is correct, UI is stale)
- Scrolling items off-screen and back makes them reappear (disposal + recreation
  uses fresh data)
- Diagnostic: `LaunchedEffect(message.parts.keys)` never fires again after initial
  render, even though the data model changes

**Root cause:** `items(count, key)` registers item content as a lambda captured in a
closure. When LazyColumn detects stable keys (same message IDs), it reuses the
existing composition slot. The OLD lambda (capturing OLD data) is called, not the new
one. This is a known behavior in Compose Foundation bundled with IntelliJ Platform.

**Fix:** Include data-dependent fields in the LazyColumn key so key changes when
data changes, forcing LazyColumn to treat it as a new item:

```kotlin
items(
    count = messages.size,
    key = { index ->
        val m = messages[index]
        "${m.id}_${m.parts.size}_${m.isStreaming}"
    }
) { index ->
    MessageItem(messages[index], ...)
}
```

**Why `key()` composable inside the item doesn't work:** `key(msg.hashCode())`
inside the item content lambda is never re-evaluated because LazyColumn doesn't
re-call the item content lambda when the outer key is stable. The inner `key()`
is dead code in this context.

**Why `items(items = List)` overload doesn't work:** The IntelliJ Platform bundles
an older Compose Foundation version that only has the `items(count, key)` overload.
`items(items = messages, key = { it.id })` does not compile.

**Trade-off:** When the key changes (e.g., `parts.size` grows), LazyColumn disposes
the old item and creates a new one. This resets any internal `remember` state
(expanded/collapsed pills). This is acceptable because pill expanded state is
managed by settings defaults + streaming state, not persistent user toggles.

**Warning:** Do NOT use `items(count, key)` with a stable-only key (e.g., just message
ID) for any list item whose data changes while visible. Always include
data-dependent fields in the key, or use a different data flow pattern.

- **Files:** `MessageList.kt` (LazyColumn items key)

### IntelliJ Platform Icons (AllIcons) â€” Confirmed Available

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
| Close (Ã—) | `AllIcons.Actions.Close` |
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
- `AllIcons.FileTypes.Kotlin` â€” use `AllIcons.Language.Kotlin` instead
- `AllIcons.FileTypes.TypeScript` â€” use `AllIcons.FileTypes.JavaScript` instead
- `AllIcons.FileTypes.Markdown` â€” use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.Gradle` â€” use `AllIcons.Nodes.Folder` as fallback (no Gradle-specific icon confirmed)
- `AllIcons.FileTypes.Any_type` â€” use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.File` â€” use `AllIcons.FileTypes.Text` instead

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

### API Testing (REST Endpoints)

The OpenCode server exposes a REST API at `http://127.0.0.1:4096`. Use PowerShell's
`Invoke-RestMethod` to test endpoints â€” `jq` is not available on Windows.

**List sessions:**
```powershell
pwsh -Command "(Invoke-RestMethod -Uri 'http://127.0.0.1:4096/session') | ForEach-Object { '{0} - {1}' -f `$_.id, `$_.title }"
```

**Session detail (tokens, cost, model, summary, time):**
```powershell
pwsh -Command "$s = Invoke-RestMethod -Uri 'http://127.0.0.1:4096/session/SESSION_ID'; Write-Output ($s | ConvertTo-Json -Depth 3)"
```

**List messages with tokens/cost:**
```powershell
pwsh -Command "$msgs = Invoke-RestMethod -Uri 'http://127.0.0.1:4096/session/SESSION_ID/message'; foreach ($m in $msgs) { Write-Output ('{0} role={1} cost={2} tokens=[in:{3} out:{4}] model={5}' -f `$m.info.id, `$m.info.role, `$m.info.cost, `$m.info.tokens.input, `$m.info.tokens.output, `$m.info.modelID) }"
```

**Health check:**
```powershell
pwsh -Command "Invoke-RestMethod -Uri 'http://127.0.0.1:4096/global/health' | ConvertTo-Json"
```

**Verified (2026-06-08):**
- `GET /session/:id` returns `tokens` and `cost` fields but they are **always zero** â€” the V1
  API does NOT populate cumulative session-level token/cost data. Per-message
  `tokens`/`cost`/`modelID` are populated correctly in `GET /session/:id/message` and
  `message.updated` SSE events.
- `GET /session/:id` returns `model` as an **optional** field (`{ id, providerID, variant }`).
  Per the OpenAPI spec it exists on `Session`, but its population in practice varies.
  `computeSessionContext()` uses it with `takeIf { isNotBlank() }` and falls back to
  `controlState.selectedModel` when absent or empty.
- `GET /session/:id` DOES correctly return `summary` (`additions`, `deletions`, `files`)
  and `time` (`created`, `updated`).
- `session.error` SSE events carry a **structured error object** (`{ name, data: { message } }`),
  not a flat string. Both `OpenCodeClient` and `SseEventListener` parse `data.message` with
  fallback to `name`.

**Implication:** `computeSessionContext()` must accumulate token/cost data from the
local message cache (summing across all `ChatMessage` fields populated by
`MessageFinalized` SSE events and REST-loaded messages), NOT from
`OpenCodeSession.tokens`/`.cost`. Session `summary` and `time` can be read from
the REST response.

### Context Manager â€” Design Deviations from TDD

**Error state on fetch failure (TDD Â§7.1):** `computeSessionContext()` returns
`Loaded` with defaults (0 for summary/time, controlState fallback for model)
instead of `Error` when `GET /session/:id` fails. This is intentional because
token/cost data (the primary context data) is still available from the local
message cache â€” only secondary metadata degrades gracefully. Showing an Error
state for a transient session-fetch failure would be too aggressive.

**Dedup guard:** `computeSessionContext()` skips re-computation if the last call
was < 300ms ago and the current state is `Loaded`. This prevents redundant REST
calls when `StreamingCompleted` and `SessionIdle` fire close together for the
same response.

### `session.compacted` SSE Event â€” Auto-Compaction Cache Invalidation

The server sends `session.compacted` when auto-compaction occurs (context window
overflow). After compaction, the local message cache is stale â€” compacted messages
are removed/summarized server-side, so local token accumulation would produce
inflated numbers.

**Data flow:**
1. SSE `session.compacted` â†’ `SseEvent.SessionCompacted` â†’ `UiSignal.SessionCompacted`
2. `ChatViewModel` handles `SessionCompacted` â†’ calls `service.refreshActiveSessionMessages()`
3. `SessionManager.refreshActiveSessionMessages()` â†’ `GET /session/:id/message` â†’ `SessionState.replaceAllMessages()`
4. Then `computeSessionContext()` runs on the fresh cache

**Key files:** `SseEvent.kt` (`SessionCompacted`), `OpenCodeClient.kt` (parsing),
`SseEventListener.kt` (standalone parser), `SessionManager.kt` (routing + refresh),
`SessionState.kt` (`replaceAllMessages()`), `ChatViewModel.kt` (signal handling)

### `message.removed` SSE Event â€” Message Deletion

The server sends `message.removed` when a message is deleted (e.g., after
compaction). The plugin removes the message from the local cache by matching
`ChatMessage.serverMessageId`, then triggers `computeSessionContext()` to
refresh token totals.

**Key files:** `SseEvent.kt` (`MessageRemoved`), `OpenCodeClient.kt` (parsing),
`SseEventListener.kt` (standalone parser), `SessionManager.kt` (routing + context refresh),
`SessionState.kt` (`removeMessageByServerId()`)

### SseEventListener Standalone Parser â€” V1 Format Fix

The standalone `parseByType` method now extracts `properties` from V1 bus events
before accessing event-specific fields. Previously, V1 events like `session.error`
read from the top-level JSON object (`obj["error"]`), which was `null` because
V1 nests data under `properties`. The fix: `val props = obj["properties"]?.jsonObject ?: obj`
â€” V1 events use `props`, V2 events fall back to `obj` (no `properties` wrapper).

### Configurable Server Port

The plugin has a configurable port setting in `Settings â†’ Tools â†’ OpenCode`
(default: 4096). This allows the plugin to coexist with other opencode consumers
(e.g., the OpenCode Desktop app) by automatically finding a free port.

**Connection strategy (`ProcessManager.initialize()`):**
1. Always launch the plugin's own `opencode serve` instance
2. If the configured port is already occupied, find the next available port
   (tries up to 10 ports beyond the configured one)
3. Launch `opencode serve --hostname $host --port $actualPort` and wait for
   health check with exponential backoff
4. The plugin never connects to an externally-managed server â€” it always owns
   its process to avoid stale state, auth mismatches, or version incompatibilities

**Shutdown behavior:** The plugin kills its own launched process on IDE dispose
(`shutdown()` â†’ `killProcess()`). Other OpenCode instances (e.g., the Desktop
app) are left untouched since the plugin uses a different port.

**Key files:** `OpenCodeSettingsState.kt` (`port` field), `OpenCodeSettingsPanel.kt`
(port UI), `ProcessManager.kt` (`findAvailablePort`, connection logic)

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
- **Action:** No action needed â€” `PermissionBridge` serves the ACP SDK integration, not the chat UI.
- **File:** `PermissionBridge.kt`

### CommandInfo Deserialization â€” Missing `id` Field

- **Status:** `GET /command` returns `CommandInfo` objects without an `id` field. The `CommandInfo` data class (`OpenCodeModels.kt:261`) requires `id` as a non-optional field, causing `MissingFieldException` on deserialization. The slash command palette fails to populate with server commands.
- **Action:** Make `id` optional in `CommandInfo` (e.g., `val id: String? = null`) or add `@JsonIgnoreUnknownKeys` / fallback. The server response only has `name` and `description`.
- **File:** `OpenCodeModels.kt:261` (`CommandInfo` data class), `OpenCodeClient.kt:1365` (`listCommands()`)
- **Impact:** Non-critical â€” local commands (`/clear`, `/cancel`) still work. Server commands (`/init`, `/review`, `/simplify`, etc.) are unavailable in the palette.

### IDE-Level Notifications (Response Complete / Question / Permission)

The plugin shows IntelliJ balloon notifications for events that need user attention.
Uses the `Notification` API with group ID `"OpenCode"` (registered in `plugin.xml`).

**Three notification types:**
1. **Response complete** (`notifyResponseComplete`) â€” fires when `StreamingCompleted` sets `_isStreaming = false`. Only fires when the IDE window is NOT focused (checked via `WindowManager.getInstance().getFrame(project)?.isActive`). Plays system beep.
2. **Question asked** (`notifyQuestionAsked`) â€” fires when `SelectionRequested` arrives. Always fires (blocks conversation). Plays system beep.
3. **Permission needed** (`notifyPermissionNeeded`) â€” fires when `PermissionRequested` arrives. Always fires (blocks conversation). Plays system beep.

All notifications include an "Open" action button that focuses the OpenCode tool window.

**Focus detection:** `WindowManager.getInstance().getFrame(project)?.isActive` checks if the
project frame has focus. Response-complete notifications are suppressed when the IDE is focused
(since the user can already see the chat updating).

**Sound:** `Toolkit.getDefaultToolkit().beep()` â€” system beep. IntelliJ also lets users assign
custom sounds per notification group via Settings â†’ Appearance & Behavior â†’ Notifications.

- **Files:** `Notifications.kt` (utility), `ChatViewModel.kt` (signal handlers), `plugin.xml` (notification group), `OpenCodeService.kt` (`project` made public)

---

## Completed (for reference)

- [x] Settings page (binary discovery, permission timeout)
- [x] Permission prompt UI with Allow/Reject/Always Allow
- [x] Dead code cleanup (configure(), setConnectionState(), getViewModel(), ProviderInfo, ModelInfo, OpenCodePlugin, BinaryDiscovery.verify())
- [x] Plugin always launches its own OpenCode instance on tool window open
- [x] Fixed SSE event parsing to match actual server format (message.part.delta, message.updated, etc.)
- [x] Fixed SSE V2 SyncEvent parsing â€” V2 events use `data` wrapper + versioned type names (`.1` suffix) instead of `properties` wrapper; all V2 events (thinking, tools, text) were silently dropped
- [x] Fixed SVG icon (was symlink with @media style rules)
- [x] Fixed CSS rendering crash (Swing HTML renderer can't handle border-radius, margin, etc.)
- [x] Dark theme chat UI with proper typography and colors
- [x] Code block rendering with custom ChatFencedCodeBlock via MarkdownSegmenter (bypasses dead DefaultMarkdownBlockRenderer override)
- [x] Inline code gray background removed â€” fix was SpanStyle(background=Transparent) propagation to Heading.H1â€“H6 (not a composable-level background)
- [x] Context usage indicator (doughnut ring + tooltip + sidebar panel) â€” auto-compaction is server-side
- [x] SSE `todo.updated` event handling + `GET /session/:id/todo` fetch
- [x] Todo list panel (collapsible, `âœ“`/`â€¢`/`â—‹` status indicators, auto-collapse >4 items)
- [x] Slash command palette (`/clear`, `/cancel`) triggered by `/` prefix in input
- [x] StreamHealer for inline markdown formatting during streaming
- [x] Question/selection prompt wired to server (`question.asked` SSE â†’ `POST /question/:id/reply` or `/reject`)
- [x] MIME type detection for file attachments â€” `MimeTypes.guessFromFileName()` replaces `URLConnection.guessContentTypeFromName()` (which returns `application/octet-stream` for most dev files)
- [x] Markdown tables with column alignment and inline formatting via InlineMarkdownText
- [x] SSE reconnection with exponential backoff (1sâ†’2sâ†’4sâ†’...â†’30s cap, Â±20% jitter, abort in-flight response, retryConnection for ERROR state)
- [x] SSE idle detection â€” replaced with health-check probes (`launchHealthCheck`) that verify server liveness without killing healthy connections during user thinking time (see TDD Â§4.2.1)
- [x] SSE observability â€” connect/disconnect/uptime/last-event timing logged with `[ACP]` prefix
- [x] Removed `socketTimeoutMillis` from HttpClient config â€” it's a no-op on Java engine (TDD Â§4.2.1)
- [x] Replaced `sseSocketTimeoutSeconds` setting with `responseTimeoutSeconds` â€” controls `withTimeout` on `deferred.await()` (was hardcoded 5 min; see TDD Â§7.1)
- [x] `ProcessManager` (was `OpenCodeConnectionManager`) no longer sets `socketTimeoutMillis` on `HttpTimeout` plugin â€” no effect on Java engine
- [x] `HttpClient` ownership centralized in `OpenCodeClient` â€” creates, configures, and closes internally; `ProcessManager` no longer creates or stores `HttpClient`
- [x] Timeout profiles (SHORT/LONG/INFINITE) added to `OpenCodeClient` â€” `executeCommand` and `compactSession` use LONG profile (`responseTimeoutSeconds + longTimeoutBufferSeconds`), `sendMessageAsync` uses INFINITE, all others use SHORT (60s)
- [x] `postSuccess`, `deleteSuccess`, `healthCheck` now propagate `CancellationException` instead of swallowing it â€” callers can distinguish timeout/cancellation from server errors
- [x] `longTimeoutBufferSeconds` setting added (default 30, minimum 10) â€” configurable in Settings â†’ Tools â†’ OpenCode
- [x] `OpenCodeService.dispose()` now cancels `sseReconnectJob` and `sseHealthCheckJob` before `shutdown()` â€” eliminates gap where reconnection could run against a closed client
- [x] Input command history with Up/Down arrow navigation (configurable size, persists with attachments, draft save/restore, `onSend` signature fixed to pass files)
- [x] `toChatMessage()` now uses `ToolMapper.toAcpKind()` instead of hardcoded `ToolKind.OTHER` â€” historical tool pills match live ones
- [x] `ToolPill` defaults collapsed (`expanded = false`) â€” was defaulting expanded
- [x] `SseEvent.ToolResult.content` now populated from V2 and V1 SSE events â€” tool output visible in expanded pills
- [x] `handleSseEvent()` no longer drops `TodoUpdated`/`QuestionAsked`/`SessionCreated`/`UserMessage` when `activeAssistantMessageId` is null â€” these events are handled before the null check
- [x] `SubagentStatus` now inferred from session token usage (`outputTokens > 0` â†’ COMPLETED, else RUNNING) â€” was always RUNNING
- [x] `CommandHistoryEntry` dedup now compares `attachedFileDataUris` in addition to paths â€” clipboard images with same text no longer falsely deduped
- [x] Removed unreachable `MimeTypes` compound extension entry `"gradle.kts"` (never matched by `substringAfterLast('.')`)
- [x] Removed empty `loadSettings()` method and its call from `ChatViewModel.initialize()`
- [x] Removed all `println()` debug statements from production code (ChatScreen.kt, InputArea.kt, AttachMenu.kt, OpenCodeSettingsState.kt, ProviderIconLoader.kt)
- [x] `SseEventListener` now handles V2 event types (`session.next.*`) and passes `patterns` to `SseEvent.Permission`
- [x] `addMessage()` FIFO eviction now rebuilds `toolCallIndex` alongside `messageIndex` â€” no stale entries after 500+ messages
- [x] `pendingFileChanges` cleared on session switch via `resetSessionState()` â€” no memory leak across sessions
- [x] `addShutdownHook` only registered once (stored in `shutdownHook` field) â€” no thread leak on re-initialization
- [x] `respondPermission`/`respondQuestion`/`rejectQuestion` in `OpenCodeClient` now throw on failure instead of swallowing â€” ViewModel catch blocks are reachable
- [x] IDE-level notifications (response complete, question asked, permission needed) â€” `Notifications.kt`, focus detection via `WindowManager`, system beep, "Open" action to focus tool window
- [x] `SessionItem.createdAt` renamed to `updatedAt` (was actually `time.updated`, not `time.created`)
- [x] `SessionContext` fields (`additions`/`deletions`/`filesModified`/`sessionCreated`/`lastUpdated`) now populated from `GET /session/:id` summary â€” were hardcoded to 0
- [x] `computeSessionContext()` accumulates token/cost from local message cache (all assistant messages) instead of always-zero `session.tokens`/`.cost` or single `lastAssistant` lookup
- [x] `MessageFinalized` SSE event parses `message.updated` `info.tokens`/`cost`/`modelID`/`providerID` â€” local message cache stays accurate for accumulation
- [x] `finalizeStreaming()` extracted as shared logic between `Stop` and `MessageFinalized` handlers â€” eliminates duplication
- [x] `session.idle` SSE event triggers `computeSessionContext()` immediately â€” eliminates 300ms debounce dependency
- [x] `session.error` SSE event parsed with structured error object (`data.message` with fallback to `name`)
- [x] `session.compacted` SSE event triggers message cache refresh (`refreshActiveSessionMessages()`) + context recomputation â€” prevents inflated token counts after auto-compaction
- [x] `message.removed` SSE event removes message from local cache by `serverMessageId` + triggers context refresh
- [x] `computeSessionContext()` dedup guard â€” skips re-computation if < 300ms since last call and state is Loaded (prevents double REST calls when `StreamingCompleted` + `SessionIdle` fire close together)
- [x] `SseEventListener` standalone parser now extracts `properties` wrapper for V1 bus events â€” `session.error` parsing was broken (read from top-level `obj` instead of `obj["properties"]`)
- [x] `SessionState.replaceAllMessages()` and `removeMessageByServerId()` â€” support cache invalidation after compaction/message deletion
- [x] Sidebar session pagination â€” `displayLimit` high-water mark on `SessionListState.Loaded`, `loadMoreSessions()` increments by 10, `resetDisplayLimit()` on init/switch, `clearAllSessions()` deletes all sessions except active, `ClearAllConfirmationDialog` with progress feedback, `SessionListFooter` with "X of Y sessions loaded" + "Load more" + "Clear all", `loadAllSessions` settings toggle

---

### MCP Integration â€” JetBrains MCP Server Discovery

The JetBrains MCP Server plugin (bundled with IntelliJ IDEA 2025.2+) runs on its **own port** (default 64342), separate from the IDE's built-in web server (port 63342). `BuiltInServerManager.getPort()` returns the web server port, NOT the MCP server port â€” do NOT use it for MCP discovery.

**Correct discovery flow:**
1. User copies the SSE URL from Settings â†’ Tools â†’ MCP Server â†’ "Copy SSE Config" (e.g., `http://127.0.0.1:64342/sse`)
2. User pastes it into the OpenCode settings `mcpServerUrl` field
3. `McpServerDiscovery` validates the URL format and verifies the endpoint by sending `GET` with `Accept: text/event-stream`
4. A 200 response means the MCP server is running; 404 or connection refused means it's not

**Key design decisions:**
- No auto-detection of the MCP server port â€” it's on a separate port from the built-in web server
- No `BuiltInServerManager` reflection â€” wrong port, classloader issues
- No `/api/mcp/list_tools` REST endpoint â€” the JetBrains MCP Server uses SSE+JSON-RPC transport only
- Verification is via SSE endpoint HTTP status, not a REST API call
- Tool listing via `McpToolList` stores an empty list per server (registration confirmation only); OpenCode manages tool details internally
- `DiscoverySource.BUILTIN_IDE` = URL from IntelliJ MCP settings, `DiscoverySource.MANUAL` = URL from additional servers config

**MCP registration â€” dual approach (file + API):**
- **Primary: `.opencode/opencode.json`** â€” `McpConfigWriter` writes MCP server configs to `<project>/.opencode/opencode.json` before the OpenCode binary launches. OpenCode reads this file on startup and connects to MCP servers persistently (survives restarts). The file is merged atomically â€” existing config (model, agent, provider, non-plugin MCP entries) is preserved.
- **Supplement: `POST /mcp`** â€” `McpRegistrar` still calls `POST /mcp` for immediate registration without requiring a restart. This is called by `McpManager.initialize()` after the OpenCode server is healthy.
- **On settings change:** `reinitializeMcpFromSettings()` writes the config file AND calls `POST /mcp` via `McpManager`.
- **On server restart:** `resetMcpOnServerRestart()` re-writes the config file (in case it's stale) and resets `McpRegistrar` state.
- **On disable:** When MCP is disabled, `McpConfigWriter.clearAllEntries()` removes plugin-managed entries from the config file.

- **Files:** `McpServerDiscovery.kt`, `McpRegistrar.kt`, `McpConfigWriter.kt`, `McpToolList.kt`, `McpManager.kt`, `McpModels.kt`, `McpStatusBarWidget.kt`, `ChatConstants.kt`, `OpenCodeSettingsPanel.kt`, `OpenCodeSettingsState.kt`, `ProcessManager.kt`, `OpenCodeService.kt`, `McpToolDiscovery.kt`, `ToolRegistry.kt`, `OpenCodeSettingsConfigurable.kt`
- **TDD deviation log:** `docs/tdd/intellij-mcp-integration.md` has a detailed "Implementation Deviations" section at the top

### Tool Permissions â€” Partial Implementation

The tool permissions feature (TDD Â§10) is partially implemented. What works: `ToolRegistry` aggregates tools from built-in + MCP sources, `McpToolDiscovery` implements MCP protocol `tools/list` via JSON-RPC over SSE, `McpConfigWriter.writeToolPermissions()` writes per-agent permission rules to `.opencode/opencode.json`, and the settings panel has basic tool discovery (`GET /experimental/tool/ids`) with per-tool checkbox + Allow/Ask/Deny dropdown.

**Known gaps:**
- `OpenCodeSettingsConfigurable.discoverMcpTools()` is a **stub** returning `emptyMap()` â€” the existing `McpToolDiscovery` class is NOT wired into the settings flow
- Two parallel data models: `ToolInfo` (in `ToolRegistry.kt`) vs `ToolPermissionInfo` (inner class of `OpenCodeSettingsPanel.kt`) â€” these should be unified
- Missing UI features: Enable All/Disable All buttons, filter/search bar, source dropdown, "N/M enabled" counter, per-server grouping headers (e.g., "MCP: intellij (12 tools)"), restart warning
- Tool states are NOT persisted in `OpenCodeSettingsState` â€” must re-discover on each settings panel open
- `McpManager` does not expose connected server SSE URLs for `McpToolDiscovery` to use
