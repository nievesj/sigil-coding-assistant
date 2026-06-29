# AGENTS.md —
 Tracked Items & Future Work

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
There is no overflow banner — auto-compaction is automatic.

**Important distinction:** This warning applies to CLIENT-SIDE auto-compaction
detection (overflow detection, `/compact` as an auto-compaction trigger). The
manual `/compact` slash command (described in "Smart Compaction & Context
Management" below) is a SEPARATE feature that calls `POST /session/{id}/summarize`
for user-initiated compaction — it does NOT trigger auto-compaction and does NOT
race with the server's overflow detection.

- **Server source:** `packages/opencode/src/session/prompt.ts` (lines 1322-1328),
  `packages/opencode/src/session/overflow.ts`, `packages/opencode/src/session/compaction.ts`
- **Config:** `compaction.auto` defaults to `true`; can be disabled server-side
- **Warning:** Do NOT re-add client-side overflow detection or auto-compaction
  triggering. The server already handles this. Adding client-side compaction would
  be redundant and could race with the server's auto-compaction.

### runIde Log Location

Logs for the plugin when running via `runIde` are NOT in the main IDEA log.
They are in the sandbox directory:

```
.intellijPlatform/sandbox/sigil/IU-2026.1/log/idea.log
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

- **Do NOT use `println()`** "” only visible in Run console when launched via `runIde`.
  When installed from a zip, println output goes nowhere useful.
- **Do NOT use `java.io.File.appendText()`** "” temp files are ephemeral and hard to
  discover.
- **Do NOT use `System.err.println()`** "” same problem as println.
- **DO use `logger.info { "[ACP] ..." }`** "” prefix with `[ACP]` for grep-friendly
  filtering in idea.log.
- **For SSE debug logging**, use `logger.debug {}` (not a temp file).

To enable verbose SSE logging in idea.log, add to Help â†’ Debug Log Settings:
```
#com.opencode.acp.adapter.OpenCodeClient=debug
```

### Configurable Log Level (Settings → Tools → Sigil)

The plugin has a built-in log-level dropdown in Settings → Tools → Sigil
("Plugin log level") that controls the Logback level for the entire
`com.opencode.acp` package at runtime. This is the **preferred** mechanism —
no need to manually edit Debug Log Settings.

Options: OFF, ERROR, WARN, INFO (default), DEBUG, TRACE, ALL.

- **INFO** (default): startup, connection, MCP registration, errors, warnings
- **DEBUG**: adds SSE wire-format logs, tool call details, session state transitions
- **ALL**: no filtering — every log call flows to idea.log
- **OFF**: completely silent (even errors suppressed)

The setting is applied at IDE startup (`StartupLogConfigListener.appFrameCreated`)
and on settings Apply (`OpenCodeSettingsConfigurable.apply()`). It persists across
restarts via `OpenCodeSettingsState.logLevel`.

- **Files:** `AcpLogLevel.kt`, `DebugLogConfig.kt`, `StartupLogConfigListener.kt`,
  `OpenCodeSettingsState.kt` (`logLevel` field), `OpenCodeSettingsPanel.kt` (dropdown),
  `OpenCodeSettingsConfigurable.kt` (apply on change), `plugin.xml` (listener registration)
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
  for code blocks again "” it compiles but does not dispatch at runtime in IU-261.

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
  "” it does NOT use Jewel's `Markdown` composable. This avoids the overhead of
  creating a full markdown processor per table cell.

### Markdown Segmenter: Leading Newline Fix

`MarkdownSegmenter.flushText()` now uses `trim('\n', '\r')` instead of
`trimEnd('\n', '\r')` to eliminate leading newlines that caused empty paragraph
artifacts between code blocks and text segments.

### Jewel Markdown: Inline Code Background (InlinesStyling Propagation)

Jewel renders inline code backgrounds via `SpanStyle.background` in
`AnnotatedString` "” it is NOT a composable-level `Modifier.background()`.
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

### SSE Reconnection "” Automatic with Exponential Backoff + Idle Detection

When the SSE stream (`/event`) drops unexpectedly (not cancelled by user action),
`startGlobalSseSubscription()` detects the stream end and calls `triggerGlobalSseReconnect()`.
This sets `ConnectionState.RECONNECTING`, which the `ConnectionBanner` renders as
"Reconnecting..." (no Retry link "” reconnection is automatic).

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
120s of silence "” which was a false positive during normal user thinking time and also
failed to trigger reconnection (the `CancellationException` re-throw bypassed the
reconnection code).

**Key design:**
- `launchSseJob()` "” shared function used by both `startGlobalSseSubscription()` and
  `triggerGlobalSseReconnect()`. Prevents code divergence between the two paths.
  Handles stream end by triggering reconnection for both unexpected errors and
  cancellation (checked via `isActive` after the catch block).
- `launchHealthCheck()` "” periodic probe coroutine. Only fires when SSE has been
  silent for the full interval. Resets the timer on success.
- `CancellationException` is no longer re-thrown "” it's caught alongside other
  exceptions. After the catch, `isActive` distinguishes user-initiated stop
  (scope cancelled â†’ skip reconnect) from unexpected stream end (scope active â†’ reconnect).

**Constants:** `ChatConstants.RECONNECT_DELAY_MS = 1000`, `RECONNECT_MAX_DELAY_MS = 30000`, `SSE_HEALTH_CHECK_INTERVAL_MS = 60000`, `SSE_HEALTH_CHECK_TIMEOUT_MS = 10000`

- **Files:** `OpenCodeService.kt` (`startGlobalSseSubscription`, `launchSseJob`, `launchHealthCheck`, `triggerGlobalSseReconnect`, `sseLastEventTimeMs`), `OpenCodeClient.kt` (timing logs in `subscribeGlobalEvents`), `ChatConstants.kt` (`SSE_HEALTH_CHECK_INTERVAL_MS`, `SSE_HEALTH_CHECK_TIMEOUT_MS`), `ConnectionBanner.kt` (RECONNECTING branch), `ChatScreen.kt` (onRetry â†’ retryConnection)

### SSE V2 SyncEvent Wire Format "” Critical Parsing Fix

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

**Actual server behavior (as of June 2026, pinned OpenCode binary version):** The OpenCode server sends V1 BusEvents exclusively, NOT V2 SyncEvents. All events use `"properties"` wrapper. Tool parts use `"type": "tool"` (not `"tool_use"`) with `"callID"` (not `"id"`), `"state"` (running/completed/failed), and `"tool"` (not `"name"`). The V2 parsing code is retained for future compatibility but the server currently uses V1 only. **Re-verify after any OpenCode binary version bump** (the binary is pinned by `ProcessManager`).

**Detection logic:** Check for `jsonObj["data"]` (V2) vs `jsonObj["properties"]` (V1). For V2, strip the `.N` version suffix from the type and extract all fields from the `data` object.

**Without this fix:** Every V2 event (thinking, tools, text streaming) was silently dropped because (a) `sessionId` extraction failed, and (b) versioned type names didn't match any `when` branch.

**Tool pill deduplication:** Both `session.next.tool.input.started` and `session.next.tool.called` fire for the same tool call. Only `called` creates a pill (it has full data including tool name and input). `input.started` is skipped to avoid duplicates.

**V1 tool part format:** `message.part.updated` with `part.type = "tool"` uses `part.callID`, `part.tool`, `part.state` (running/completed/failed), and `part.input`. This differs from the documented `"tool_use"` + `"id"` + `"name"` format.

- **Files:** `OpenCodeClient.kt` (`subscribeGlobalEvents` "” V2 format detection + version suffix stripping + `data` extraction), `ChatViewModel.kt` (`addToolCallPill` "” deduplication by `toolCallId`)

### Question/Selection Prompt "” `question.asked` SSE Event

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
- `POST /question/:requestID/reply` "” body: `{ "answers": [["selectedLabel"]] }` (one array per question, containing label strings)
- `POST /question/:requestID/reject` "” empty body

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
- **Warning:** Do NOT revert to `URLConnection.guessContentTypeFromName()` alone "” it causes "file part media type application/octet-stream" errors for most dev files.

### Ctrl+V / Clipboard Image Paste "” Must Use IntelliJ AnAction, NOT Compose onPreviewKeyEvent

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
  an IntelliJ plugin "” it does not work. The IDE action system consumes the event
  before it reaches the Compose layer.
- **Reference:** phodal/auto-dev uses the same pattern (`IdeaDevInInput.kt`) "”
  `DumbAwareAction` + `registerCustomShortcutSet` for Ctrl+V on Swing components

### Todo List Panel "” Collapsible Status Indicators

The todo panel shows active (non-completed, non-cancelled) tasks from
`GET /session/:id/todo`. It auto-collapses when >4 incomplete items exist,
showing only the first 2 and a "+N more…" hint.

**Status indicators:** `âœ“` completed (green), `•` in_progress (amber), `â—‹` pending (gray),
`✗` cancelled (dim gray).

**Data flow:**
1. SSE `todo.updated` event â†’ updates `_todoItems` StateFlow in ChatViewModel
2. `fetchTodos()` called on init, session switch, and after each response
3. `TodoListPanel` composable reads `todoItems` and renders header + items
4. Clicking header toggles expanded/collapsed state

- **Files:** `TodoListPanel.kt`, `ChatViewModel.kt` (`_todoItems`, `fetchTodos()`),
  `ChatScreen.kt` (wires `todoItems` to `TodoListPanel`), `ChatModels.kt` (`TodoItem`),
  `OpenCodeClient.kt` (`getTodos()`), `SseEvent.kt` (`SseEvent.TodoUpdated`)

### Slash Command Palette "” `/` Prefix Triggering

Typing `/` at the start of the input field shows a popup palette with slash commands.
The palette filters as the user types (e.g., `/cl` shows `/clear`).

**Command sources:**
1. **Local commands** (handled by plugin, not sent to server): `/clear`, `/cancel`, `/review-perform`, `/review-perform-gaming`, `/review-resolve`, `/review-recheck`
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

### Input Command History "” Up/Down Arrow Navigation

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
"Command history size" (default 15, clamped to 1–100). Changing the setting
trims the history on the next `recordCommand` call.

**`CommandHistoryEntry`** is a non-data class with parallel `ArrayList<String>`
fields (`attachedFileNames`, `attachedFilePaths`, `attachedFileMimes`,
`attachedFileDataUris`) for XStream compatibility. `toAttachedFiles()` reconstructs
the original `List<AttachedFile>`.

**Bug fix included:** The `onSend` callback signature changed from `(String) -> Unit`
to `(String, List<AttachedFile>) -> Unit`. Previously, clicking the green Send
button or pressing Enter did NOT pass attached files "” they were silently lost.
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
- Our plugin uses `opencode serve` (HTTP REST + SSE) instead "” gives more control over custom UI

### LazyColumn items(count, key) "” Stale Data When Keys Are Stable

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

**Fix (current "” State-read pattern):** Use a stable key (just `m.id`) and read
`State<Map<String, ChatMessage>>` *inside* the item content lambda. The Compose
snapshot system registers a per-item subscription when the lambda reads `.value`,
and invalidates the item's composition when the State changes "” re-invoking the
lambda with fresh data, bypassing LazyColumn's key-diffing entirely.

```kotlin
items(
    count = messages.size,
    key = { index -> messages[index].id }
) { index ->
    // Read State INSIDE the lambda "” creates a snapshot subscription
    val currentMessage = messagesState.value[messages[index].id] ?: messages[index]
    MessageItem(currentMessage, ...)
}
```

This eliminates both the stale-data bug (snapshot system drives recomposition) and
the flicker (no dispose+recreate when key is stable). It also preserves `remember`
state (expanded pills, markdown caches) since the item composition is never disposed.

**Previous fix (superseded "” caused flicker):** Including `parts.size` and
`isStreaming` in the key forced LazyColumn to dispose+recreate the item on every
part addition. This fixed stale data but caused a visible flicker when pills/thinking
elements appeared, and reset all `remember` state (expanded/collapsed pills).

**Why `key()` composable inside the item doesn't work alone:** `key(msg.hashCode())`
inside the item content lambda is never re-evaluated because LazyColumn doesn't
re-call the item content lambda when the outer key is stable. The inner `key()`
becomes live only when the snapshot system re-invokes the lambda (via the State read).

**Why `items(items = List)` overload doesn't work:** The IntelliJ Platform bundles
an older Compose Foundation version that only has the `items(count, key)` overload.
`items(items = messages, key = { it.id })` does not compile.

**Warning:** Do NOT use `items(count, key)` with a stable-only key (e.g., just message
ID) for any list item whose data changes while visible **unless** the item content
lambda reads a `State<T>` to create a snapshot subscription. Without the State read,
the item will render stale data. With the State read, stable keys are safe and
preferable (no flicker, preserved `remember` state).

- **Files:** `MessageList.kt` (LazyColumn items key + State read), `ChatScreen.kt`
  (passes `State<Map<String, ChatMessage>>` to MessageList)

### Streaming "Jump" — animateScrollToItem on New Streaming Message

**Problem:** When a tool completes and a new assistant message starts streaming
(thinking begins), all visible chat messages briefly shift upward — a visual
"jump" or "flicker" lasting a fraction of a second. The effect is most noticeable
when the chat has many messages (500+) and `Arrangement.Bottom` is used.

**Root cause:** Three separate issues, all triggered at the `new_message` transition
(tool completes → `MessageFinalized` with new server message ID → new assistant
message auto-created → thinking starts):

1. **`finalizeStreaming` 300ms debounce (fixed):** The `new_message` path used the
   same 300ms debounced finalization as normal `stop` events. This delayed the old
   message's `isStreaming=true→false` transition by 300ms, splitting state changes
   across two frames and causing a mass LazyColumn dispose+recreate when the debounce
   finally fired. Fix: dedicated `new_message` branch in `finalizeStreaming` that
   finalizes immediately (no debounce) and does NOT emit `StreamingCompleted` (the
   new message's completion will emit it — emitting here would prematurely set
   `_streamPhase=IDLE` while the new message is actively streaming).

2. **`resegmentTextPartsFinal` using `segment()` instead of `segmentHealed()` (fixed):**
   On finalization, `resegmentTextPartsFinal` passed `overrideIsStreaming=false` to
   use `MarkdownSegmenter.segment()` instead of `segmentHealed()`. If the segment
   structure differed (healed closed unclosed markdown that non-healed treats as
   literal text), part keys changed (e.g., `text_0_0` → `text_0_0 + text_0_1`), causing
   every `key()` block in `AssistantMessage` to dispose+recreate. Fix: always use
   `segmentHealed()` (pass `overrideIsStreaming=true`) — for complete content, both
   produce identical results; consistency prevents key changes.

3. **`animateScrollToItem` on new streaming message (fixed):** When a new message
   appears in the list (delta == 1), the scroll coordinator used
   `animateScrollToItem` — a 60ms-delayed animated glide to the new bottom. With
   `Arrangement.Bottom`, this animation shifts all visible items upward, which IS
   the visible "jump." Fix: detect `isNewStreamingMessage` (delta == 1 and last
   message `isStreaming == true`) and use instant `scrollToItem` instead of animated.

**Empty streaming messages filtered:** Assistant messages with 0 parts and
`isStreaming=true` are filtered out of the `messages` list before LazyColumn sees
them. They create 0-height items that trigger LazyColumn recycling of visible
items. The message gets parts within milliseconds (first `ThinkingChunk`/`TextChunk`),
at which point it appears in the list normally. The `ThinkingIndicator` overlay
(pinned at the bottom of the Box) still shows activity during the gap.

**Diagnostic approach (if regression):**
- Enable DEBUG logging (Settings → Tools → Sigil → Plugin log level)
- Search `idea.log` for `[ACP] KEYS CHANGED` — if part keys change during
  finalization, the resegment is producing different segment structures
- Search for `[ACP] finalizeStreaming (new_message): immediate` — if this shows
  `debounced finalization (300ms)` instead, the immediate-finalization branch
  is not being reached
- Search for `[ACP] AssistantMessage: EMPTY PARTS` with `isStreaming=true` —
  if empty streaming messages appear in the list, the filter is broken
- Check `MessageList.kt` scroll coordinator: `isNewStreamingMessage` must use
  instant `scrollToItem`, not `animateScrollToItem`

- **Files:** `SessionState.kt` (`finalizeStreaming` new_message branch,
  `resegmentTextPartsFinal`), `MessageList.kt` (empty message filter,
  `isNewStreamingMessage` scroll mode, `AssistantMessage` EMPTY PARTS warning)

### IntelliJ Platform Icons (AllIcons) "” Confirmed Available

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
- `AllIcons.FileTypes.Kotlin` "” use `AllIcons.Language.Kotlin` instead
- `AllIcons.FileTypes.TypeScript` "” use `AllIcons.FileTypes.JavaScript` instead
- `AllIcons.FileTypes.Markdown` "” use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.Gradle` "” use `AllIcons.Nodes.Folder` as fallback (no Gradle-specific icon confirmed)
- `AllIcons.FileTypes.Any_type` "” use `AllIcons.FileTypes.Text` instead
- `AllIcons.Nodes.File` "” use `AllIcons.FileTypes.Text` instead

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
`Invoke-RestMethod` to test endpoints "” `jq` is not available on Windows.

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

**Verified (2026-06-08, pinned OpenCode binary version — re-verify after any version bump):**
- `GET /session/:id` returns `tokens` and `cost` fields but they are **always zero** "” the V1
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

### Context Manager "” Design Deviations from TDD

**Error state on fetch failure (TDD Â§7.1):** `computeSessionContext()` returns
`Loaded` with defaults (0 for summary/time, controlState fallback for model)
instead of `Error` when `GET /session/:id` fails. This is intentional because
token/cost data (the primary context data) is still available from the local
message cache "” only secondary metadata degrades gracefully. Showing an Error
state for a transient session-fetch failure would be too aggressive.

**Dedup guard:** `computeSessionContext()` skips re-computation if the last call
was < 300ms ago and the current state is `Loaded`. This prevents redundant REST
calls when `StreamingCompleted` and `SessionIdle` fire close together for the
same response.

### `session.compacted` SSE Event "” Auto-Compaction Cache Invalidation

The server sends `session.compacted` when auto-compaction occurs (context window
overflow). After compaction, the local message cache is stale "” compacted messages
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

### Smart Compaction & Context Management

Implements the TDD at `docs/tdd/smart-compaction.md`. Three interconnected systems:

**1. Context Breakdown Bar** — A 5-category visual breakdown (System + Tool Definitions,
User, Assistant, Tool Calls, Other) in the Context panel tab, replacing the flat token
list with a proportional bar chart. `BreakdownComputer` categorizes tokens from the local
message cache using a calibrated char-to-token ratio (default ~4, EMA-adjusted after each
`MessageFinalized` SSE event). The server does NOT expose per-part token counts — these
are estimates.

**2. Manual Compaction** — A `/compact` slash command and a "Compact Now" button in the
Context tab, wired to the existing `POST /session/{id}/summarize` endpoint (which was
previously dead code in `OpenCodeClient.compactSession()`).

**IMPORTANT: The server does NOT support a `guidance` field.** The TDD originally specified
guidance features (default guidance, per-compaction guidance input, `/compact <guidance>`),
but librarian research confirmed the server's `SummarizePayload` schema is only
`{ providerID, modelID, auto }` — unknown fields are silently ignored. All guidance
features were dropped from the implementation. The `/compact` command takes no arguments.

**3. Smart Context Manager** — Client-side preprocessing + background compaction:
- `ToolOutputTruncator` (opt-in, off by default): truncates tool results > N chars at
  insertion time, JSON-safe (keeps complete objects until limit, appends marker object)
- `FileReadCache` (opt-in, off by default): detects duplicate file reads by (path, mtime,
  size) tuple, emits `[unchanged]` instead of re-emitting content
- `BackgroundCompactor` (**DISABLED** — see warning below): pre-computes compaction summaries
  at 60% context for instant swap at 80%. Uses checkpoint/swap pattern with model mismatch guard
  and max-age staleness check (5 min). Cleared on session switch and on `session.compacted` SSE.
  The auto-trigger was removed because the server's `/summarize` endpoint performs ACTUAL
  compaction (not a preview), so calling it as a "background checkpoint" compacts the session
  immediately on load when usage > 60%. The class is retained as dead code in case a preview
  API is added in the future. The setting defaults to OFF.
- `ContextPressureMonitor`: tracks rolling growth rate (20-turn window), forecasts
  estimated turns until compaction, computes burn rate (tokens/min). Reset on session
  switch and after compaction (`onCompaction()`).

**Settings:** All compaction settings live under Settings → Tools → Sigil → Context
(new child configurable `OpenCodeContextConfigurable`). Includes: show context breakdown,
pressure notification threshold, truncate tool output + char limit, detect duplicate reads,
enable background compaction + checkpoint/swap thresholds, compact confirmation.

**Context indicator pressure badge:** The context indicator shows a pressure suffix
(`·` ELEVATED, `▲` HIGH, `⚠` CRITICAL) next to the percent when pressure meets the
user's configured notification threshold. Controlled by `pressureNotificationThreshold`
setting (NEVER / ELEVATED / HIGH / CRITICAL).

**Key files:**
- `CompactionConstants.kt` — single source of truth for all constants
- `ContextBreakdown.kt` (`BreakdownComputer`), `ContextPressureMonitor.kt`,
  `BackgroundCompactor.kt`, `ToolOutputTruncator.kt`, `FileReadCache.kt`
- `ChatModels.kt` (`ContextBreakdown`, `ToolCategoryBreakdown`, `ContextPressure`,
  `PressureLevel`, `CompactionState`, `CompactionError`, `SessionContext.breakdown/pressure`)
- `SessionManager.kt` (wiring: `pressureMonitor`, `backgroundCompactor`, `fileReadCache`,
  `resetSmartContextState()`, `onSessionCompacted()`, `maybeTruncateToolOutput()`)
- `SessionState.kt` (`updateToolCallStatus` applies truncation via `sessionManager.maybeTruncateToolOutput()`)
- `ChatViewModel.kt` (`compactSession()`, `compactionState` StateFlow, `resetCompactionState()`)
- `ContextPanel.kt` (proportional bar + breakdown legend + Compact button + compaction state)
- `ContextIndicator.kt` (pressure badge via `pressureBadgeText()`)
- `OpenCodeContextConfigurable.kt` (Settings → Tools → Sigil → Context)
- `OpenCodeSettingsState.kt` (compaction settings + clamping in `loadState`)
- `plugin.xml` (Context configurable registration)

**Warning:** Do NOT re-add a `guidance` field to the compaction request — the server's
`SummarizePayload` schema does not include it and silently ignores unknown fields.
The `/compact` command takes no arguments.

**Warning:** Do NOT re-enable the `BackgroundCompactor` auto-trigger in
`computeSessionContextInternal()`. The server's `POST /session/{id}/summarize` performs
ACTUAL compaction (removes/summarizes messages server-side), not a preview. Auto-triggering
it on context computation causes the session to compact immediately on load when usage
exceeds the checkpoint threshold (default 60%). There is no server API to pre-compute a
summary without side effects. Manual compaction (`/compact` command, "Compact Now" button)
is the correct path — it is user-initiated and expected.

### `message.removed` SSE Event "” Message Deletion

The server sends `message.removed` when a message is deleted (e.g., after
compaction). The plugin removes the message from the local cache by matching
`ChatMessage.serverMessageId`, then triggers `computeSessionContext()` to
refresh token totals.

**Key files:** `SseEvent.kt` (`MessageRemoved`), `OpenCodeClient.kt` (parsing),
`SseEventListener.kt` (standalone parser), `SessionManager.kt` (routing + context refresh),
`SessionState.kt` (`removeMessageByServerId()`)

### SseEventListener Standalone Parser "” V1 Format Fix

The standalone `parseByType` method now extracts `properties` from V1 bus events
before accessing event-specific fields. Previously, V1 events like `session.error`
read from the top-level JSON object (`obj["error"]`), which was `null` because
V1 nests data under `properties`. The fix: `val props = obj["properties"]?.jsonObject ?: obj`
"” V1 events use `props`, V2 events fall back to `obj` (no `properties` wrapper).

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
4. The plugin never connects to an externally-managed server "” it always owns
   its process to avoid stale state, auth mismatches, or version incompatibilities

**Shutdown behavior:** The plugin kills its own launched process on IDE dispose
(`shutdown()` â†’ `killProcess()`). Other OpenCode instances (e.g., the Desktop
app) are left untouched since the plugin uses a different port.

**Key files:** `OpenCodeSettingsState.kt` (`port` field), `OpenCodeSettingsPanel.kt`
(port UI), `ProcessManager.kt` (`findAvailablePort`, connection logic)

### ComposePanel.dispose() — EDT Hang on Tool Window Close / IDE Restart

**Problem:** `ComposePanel.dispose()` blocks the EDT when Skiko's render thread is
mid-frame, causing the entire IDE to lock up during tool window close or IDE restart.
This is a known issue in Compose for Desktop / Jewel (see Jewel #454, CMP-5713).

Three separate disposal paths raced to dispose the same `ComposePanel`:
1. Content disposer (`ChatToolWindowFactory`) — ran synchronously on EDT
2. `ShutdownListener` (`AppLifecycleListener.appWillBeClosed`) — ran synchronously on EDT
3. Shutdown hook (`Runtime.addShutdownHook`) — ran on daemon thread, but leaked on every
   `createToolWindowContent()` call (ClassLoader leak during dynamic plugin reload)

**Solution:** All dispose paths now use `disposeActiveComposePanelAsync()`, which disposes
the `ComposePanel` on a daemon thread. This prevents EDT blocking regardless of Skiko's
render thread state.

1. **Content disposer** — replaced synchronous `composePanelRef?.dispose()` with
   `disposeActiveComposePanelAsync()`
2. **ShutdownListener** — replaced synchronous `panel.dispose()` with
   `disposeActiveComposePanelAsync()`
3. **Shutdown hook removed** — was redundant (ShutdownListener already handles restart),
   leaked ClassLoaders on dynamic plugin reload, and raced with the content disposer

**Race condition safety:** `activeComposePanel` is `@Volatile` and
`disposeActiveComposePanelAsync()` reads + nulls atomically. Whichever path runs first
disposes; subsequent calls find `null` and skip.

- **Files:** `ChatToolWindowFactory.kt` (content disposer, shutdown hook removed),
  `ShutdownListener.kt` (async dispose)
- **Warning:** Do NOT re-add synchronous `ComposePanel.dispose()` calls on EDT — they
  block the IDE when Skiko is mid-frame.
- **Reference:** Jewel `addComposeTab()` source uses `JewelComposePanel` which wraps
  `ComposePanel`; the same async-dispose pattern is required for any `ComposePanel`
  lifecycle management in IntelliJ plugins.

### GDI nativeBlit Hang — Continuous Animation Frame Pressure (JDK-8301926)

The EDT can hang in `GDIBlitLoops.nativeBlit()` (Windows GDI `BitBlt`) when
DWM composition enters a bad state. This is a known JDK issue (suspected match:
JDK-8301926, unverified). The hang occurs below the JVM, in `win32k.sys` /
`dwm.dll`, and no plugin code change can prevent the native call from hanging.

**Why this plugin triggers it disproportionately:**

Three `rememberInfiniteTransition` animations (glow sweep in `InputArea.kt`,
pulse in `ContextIndicator.kt`, shimmer in `SessionSidebar.kt`) generate
continuous frame rendering at ~60fps. Each frame flows through Skiko SOFTWARE
render → `BufferedImage` → Swing `RepaintManager.paintDoubleBuffered()` →
`GDIBlitLoops.nativeBlit()` → GDI `BitBlt` → DWM composition. A static UI
rarely hits `nativeBlit`; animations hit it 60 times per second, giving DWM
60 chances per second to deadlock.

**Fix applied (2026-06-13):** Moved animation state reads into the Compose draw
phase and made animations conditional:

- **InputArea glow:** `rotation` captured as `State<Float>`, color-stop
  computation moved into `drawBehind { }`. Only exists when `isStreaming`.
- **ContextIndicator pulse:** New `rememberPulsingAlpha()` returns
  `mutableFloatStateOf(1f)` when idle (no transition created). `alphaState`
  read inside `Canvas` draw scope and `DoughnutRing`.
- **SessionSidebar shimmer:** `Modifier.sessionShimmer()` returns early when
  `indicator == NONE` (no transition created). `shimmerProgressState.value`
  read inside `drawBehind { }`.

This eliminates per-frame recomposition and stops animation frame generation
entirely when the UI is idle.

**Key files:** `InputArea.kt` (lines 536-597), `ContextIndicator.kt`
(lines 73-88, 130-156), `SessionSidebar.kt` (lines 689-726)

**`skiko.renderApi=SOFTWARE` must be set as JVM argument**, not runtime
`System.setProperty()`. Skiko selects its renderer at class-loading time;
setting the property in `createToolWindowContent()` is likely a no-op.

**Do NOT:**
- Disable vsync (`skiko.vsync.enabled=false`) — increases frame rate
- Use EDT watchdog — disposing ComposePanel from daemon thread while EDT is
  stuck in native call risks JVM crashes
- Rely on `-Dsun.java2d.opengl=true` as "definitive" — silently falls back
  to GDI on incompatible hardware

**Full investigation:** `docs/tdd/Done/ide-hang-investigation.md`

---

## Deferred Features

### Image Content Support (v2)

- **Status:** Data plumbing complete (`MessagePart.Image`, SSE parsing, REST model,
  markdown rendering). Image pixel rendering is NOT yet implemented — currently
  shows a metadata card (icon + filename + MIME type).
- **Action:** Implement actual image rendering in `MessageList.kt` (decode from
  URL or data-URI, display inline). Add URL/data-URI decoder to `ImageUtils.kt`.
- **Files:** `MessageList.kt:623-635` (rendering), `ImageUtils.kt` (decoder),
  `MessagePart.kt:94` (model), `ChatModels.kt:374` (markdown content).

---

## Technical Debt

### `addBrowseFolderListener` Deprecation

- **Status:** `OpenCodeSettingsPanel.kt:20` uses the 1-arg
  `TextBrowseFolderListener(FileChooserDescriptor)` constructor, which is
  deprecated in IntelliJ Platform 2026.1.
- **Action:** Migrate to `TextBrowseFolderListener(FileChooserDescriptor, Producer<Project?>)`.
- **File:** `OpenCodeSettingsPanel.kt:19-26`

### AcpServerConfig `parse()` Method

- **Status:** `AcpServerConfig.parse()` reads CLI args and env vars but is only used in `Main.kt` (standalone server mode). The IntelliJ plugin uses `OpenCodeSettingsState` instead.
- **Decision (2026-06-24, stakeholder-confirmed): Retain.** The standalone `Main.kt` + `AcpServerConfig` path is kept as a reference implementation and entry point for external consumers of the ACP SDK bridge. `AcpServerConfigTest.kt` and `OpenCodeAgentSupport.kt`'s `config` field are retained alongside it. See `docs/tdd/outstanding-tech-debt.md` §6 for the alternatives analysis.
- **Files:** `AcpServerConfig.kt`, `Main.kt`, `AcpServerConfigTest.kt`

---

### MCP Integration "” JetBrains MCP Server Discovery

The JetBrains MCP Server plugin (bundled with IntelliJ IDEA 2025.2+) runs on its **own port** (default 64342), separate from the IDE's built-in web server (port 63342). `BuiltInServerManager.getPort()` returns the web server port, NOT the MCP server port "” do NOT use it for MCP discovery.

**Correct discovery flow:**
1. User copies the SSE URL from Settings â†’ Tools â†’ MCP Server â†’ "Copy SSE Config" (e.g., `http://127.0.0.1:64342/sse`)
2. User pastes it into the OpenCode settings `mcpServerUrl` field
3. `McpServerDiscovery` validates the URL format and verifies the endpoint by sending `GET` with `Accept: text/event-stream`
4. A 200 response means the MCP server is running; 404 or connection refused means it's not

**Key design decisions:**
- No auto-detection of the MCP server port "” it's on a separate port from the built-in web server
- No `BuiltInServerManager` reflection "” wrong port, classloader issues
- No `/api/mcp/list_tools` REST endpoint "” the JetBrains MCP Server uses SSE+JSON-RPC transport only
- Verification is via SSE endpoint HTTP status, not a REST API call
- Tool listing via `McpToolList` stores an empty list per server (registration confirmation only); OpenCode manages tool details internally
- `DiscoverySource.BUILTIN_IDE` = URL from IntelliJ MCP settings, `DiscoverySource.MANUAL` = URL from additional servers config

**MCP registration "” dual approach (file + API):**
- **Primary: `.opencode/opencode.json`** "” `McpConfigWriter` writes MCP server configs to `<project>/.opencode/opencode.json` before the OpenCode binary launches. OpenCode reads this file on startup and connects to MCP servers persistently (survives restarts). The file is merged atomically "” existing config (model, agent, provider, non-plugin MCP entries) is preserved.
- **Supplement: `POST /mcp`** "” `McpRegistrar` still calls `POST /mcp` for immediate registration without requiring a restart. This is called by `McpManager.initialize()` after the OpenCode server is healthy.
- **On settings change:** `reinitializeMcpFromSettings()` writes the config file AND calls `POST /mcp` via `McpManager`.
- **On server restart:** `resetMcpOnServerRestart()` re-writes the config file (in case it's stale) and resets `McpRegistrar` state.
- **On disable:** When MCP is disabled, `McpConfigWriter.clearAllEntries()` removes plugin-managed entries from the config file.

- **Files:** `McpServerDiscovery.kt`, `McpRegistrar.kt`, `McpConfigWriter.kt`, `McpToolList.kt`, `McpManager.kt`, `McpModels.kt`, `McpStatusBarWidget.kt`, `ChatConstants.kt`, `OpenCodeSettingsPanel.kt`, `OpenCodeSettingsState.kt`, `ProcessManager.kt`, `OpenCodeService.kt`, `McpToolDiscovery.kt`, `ToolRegistry.kt`, `OpenCodeSettingsConfigurable.kt`
- **TDD deviation log:** `docs/tdd/intellij-mcp-integration.md` has a detailed "Implementation Deviations" section at the top

### Tool Permissions — Implementation Status

The tool permissions feature (TDD §10) is substantially implemented.

**Working:**
- `ToolRegistry` aggregates tools from built-in + MCP sources
- `McpToolDiscovery` implements MCP protocol `tools/list` via JSON-RPC over SSE
- `McpConfigWriter.writeToolPermissions()` writes per-agent permission rules to `.opencode/opencode.json`
- Settings panel (`OpenCodeMcpPanel`) has: Enable All/Disable All buttons, filter/search bar,
  source dropdown filter, "N/M tools enabled" counter, per-server grouping headers,
  per-tool checkbox + Allow/Ask/Deny dropdown
- Tool states persisted in `OpenCodeSettingsState` (`toolPermissions`, `discoveredToolsJson`)
- `McpManager.getServerUrls()` exposes connected server SSE URLs for discovery

**Remaining gaps:**
- Panel's Enable All/Disable All batch sync uses name-based matching in `ToolRegistry.syncEnabled()`, which may affect tools across servers when two MCP servers expose tools with the same raw name (e.g., both server A and server B have "create_file"). Both are updated because `syncEnabled` matches by raw name without server qualification.