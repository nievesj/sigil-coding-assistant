# Technical Design Document: Message Part Type Coverage

> **Status:** Open
> **Created:** 2026-06-06
> **Related docs:** [message-processor.md](message-processor.md), [AGENTS.md](../../../AGENTS.md)

---

## 1. TL;DR

OpenCode defines 13 message part types (12 document-level + `image`). The plugin maps 2 server types to display types (text, tool), has a confirmed V1 reasoning routing bug (server sends `field: "text"` for reasoning — all reasoning appears as regular text on V1 path), has REST-based workarounds for 3 more (file, image, subtask), and has no SSE handling for the remaining 7. This TDD tracks the gaps, the reasoning routing bug, the missing V2 event coverage, and the visual components needed for each part type.

---

## 2. V1 vs V2 Event Systems

OpenCode has two internal event systems running in parallel during a migration:

- **V1 (Legacy Bus Events):** Always active. Types use flat names: `message.part.delta`, `message.part.updated`, `tool_use`, `stop`, etc. Published directly to the bus via `session.updatePart()` / `session.updatePartDelta()`.
- **V2 (EventV2 / Core Events):** New system, gated behind `flags.experimentalEventSystem`. Types use namespaced names: `session.next.text.delta`, `session.next.reasoning.delta`, `session.next.tool.called`, etc. Published via `EventV2.Service`.

**How they coexist:** The `event-v2-bridge.ts` (labeled "Temporary V2 bridge") re-publishes V2 events as V1 bus events. The HTTP SSE endpoint (`/event`) subscribes to `Bus.subscribeAll()`, so **both V1 originals and V2-bridged-as-V1 events arrive on the wire**. The plugin handles both naming conventions.

**Practical impact:** V2 event types (`session.next.*`) are more semantically precise (e.g., `session.next.reasoning.delta` clearly identifies reasoning). V1 types are ambiguous (`message.part.delta` with `field: "text"` carries both text AND reasoning content — the part type is only identifiable from the corresponding `message.part.updated` event).

**Version suffixes:** V2 event types carry a `.N` version suffix on the wire (e.g., `session.next.text.delta.1`). Both `OpenCodeClient.parseSseEvent()` and `SseEventListener.parseByType()` strip the suffix before matching. Type names throughout this document use the unsuffixed form for clarity — implementers should be aware suffix stripping already exists in the code.

**Server behavior:** As of June 2026, the OpenCode server sends V1 BusEvents exclusively (not V2 SyncEvents) — see AGENTS.md "SSE V2 SyncEvent Wire Format — Critical Parsing Fix" for details. V2 handling code in the plugin is retained for future compatibility. All V1 events use the `"properties"` wrapper, not the `"data"` wrapper used by V2.

---

## 3. Part Types vs Plugin Handling

OpenCode defines 13 message part types in `message-v2.ts` (12 document-level types + `image`). The table below covers all 13.

| Part Type | OpenCode Type | Plugin Status | Visual Component | Notes |
|-----------|--------------|---------------|------------------|-------|
| **Text** | `text` | ✅ Handled | `MessagePart.Text` → Jewel `Markdown` composable | Mapped to `SseEvent.TextChunk` / `TextReplace`. Also produces `MessagePart.Code` and `MessagePart.Table` via `MarkdownSegmenter`. |
| **Reasoning** | `reasoning` | ❌ V1 bug (reasoning appears as text) | `MessagePart.Thinking` → `ThinkingPill` composable (plain italic gray text, no border or collapse; `ThinkingIndicator` spinner shows "Thinking..." during active stream) | **Bug confirmed:** server sends `field: "text"` for reasoning deltas on V1 bus (`processor.ts:334-340`), so all reasoning on V1 path is misrouted to `TextChunk`. The `"thinking"`/`"reasoning"` branches in `OpenCodeClient.kt:697` are dead code. V2 path correctly routed. Fix: track reasoning `partID` set from `message.part.updated`, disambiguate in delta handler. See §4. |
| **Tool** | `tool` / `tool_use` | ✅ Handled | `MessagePart.ToolCall` → `ToolPill` composable (icon + tool name + status, compact when completed, expandable for input/output) | Mapped to `SseEvent.ToolUse` / `ToolResult`. V1 uses both `"tool"` and `"tool_use"` type names. **Deserialization gap:** `OpenCodePartSerializer.knownTypes` only registers `"tool_use"` — V1 `message.part.updated` events with `part.type == "tool"` deserialize as `OpenCodePart.Unknown`. The SSE event routing in `OpenCodeClient.kt:702` handles this correctly (routes by `partType` before deserialization), but any REST-based tool part deserialization would fail for `type: "tool"`. |
| **File** | `file` | 🔶 Partially handled (REST only) | `MessagePart.AssistantFile` → planned inline file card. Currently: REST path renders `[File: filename (mime)]` text via `ContentMapper`; SSE path drops it. | Assistant-generated files. Fields: mime, url, filename, source. `OpenCodePart.File` deserializes correctly, but SSE `message.part.updated` with `part.type == "file"` is silently skipped. Note: `MessagePart.FileChange` already exists for tool-caused file edits — the `AssistantFile` type represents a distinct concept. |
| **Image** | `image` | 🔶 Partially handled (REST only) | `MessagePart.Image` → planned thumbnail. Currently: `OpenCodePart.Image` deserializes but has no SSE handling or chat UI rendering. | Deferred per AGENTS.md "Image Content Support (v2)". Same gap as `file` — REST path maps to text, SSE path drops it. |
| **Step Start** | `step-start` | ❌ Ignored | Subtle pill or divider: `"Step started"` (dimmed, collapsed by default). Could group tool calls and text that follow into a visual step block. | Marks step boundary. `OpenCodePart.StepStart` model exists, but SSE events (`session.next.step.started`, `message.part.updated` with `type == "step-start"`) return `null`. Low priority — mainly useful for multi-step agent flows. |
| **Step Finish** | `step-finish` | ❌ Ignored | Token usage pill: `"2.1k in / 856 out / 1.2k reasoning"` — shown inline at end of step, or in the sidebar context panel. Updates the context indicator doughnut ring with accurate per-step data. | `OpenCodePart.StepFinish` model exists but only has `snapshot: String?`. SSE events return `null` (neither V1 `message.part.updated` nor V2 `session.next.step.ended` is handled). **Before implementation:** capture the actual server payload for `step-finish` events to determine which usage/cost fields exist, then add them to the model. Currently relies on stale message-level REST data. |
| **Snapshot** | `snapshot` | ❌ Ignored | No visual needed — internal state marker. Could optionally show a subtle `"Snapshot saved"` in debug mode. | Internal use only. No standalone `OpenCodePart` model — would deserialize as `Unknown`. Not to be confused with the `snapshot: String?` field on `StepStart`/`StepFinish` (those are per-step markers; the `snapshot` part type is a separate concept). |
| **Patch** | `patch` | ❌ Ignored | Inline card in the message (not the ReviewPanel sidebar). Shows "N Changed files" with per-file additions/deletions. **Data gap:** `patch` only provides `files: string[]` (paths) and `hash` (git tree hash for revert). Line counts (+X -Y) must be computed by running `git diff --numstat <hash>` against the hash. See §5 for full implementation details. **Replaces current approach:** Currently, file changes are extracted from tool call input JSON via `extractFilePath()` (MessageProcessorManager.kt). The `patch` part is authoritative server-side data. No `OpenCodePart` model — would deserialize as `Unknown`. | Contains hash (git tree object) and files (paths only). Plugin must run git diff to get line counts. Replaces extractFilePath() hack. |
| **Agent** | `agent` | ❌ Ignored | Agent badge: small pill next to the message showing which agent handled the step (e.g., `"explorer"` or `"fixer"`). Helps users understand multi-agent flows. | Agent name for the step. No `OpenCodePart` model — would deserialize as `Unknown`. |
| **Retry** | `retry` | ❌ Ignored | Retry pill: `"Retry 2/3 — rate limit"` (yellow/orange, collapsible for error details). Auto-dismisses when the retry succeeds. | Retry attempt + error. No `OpenCodePart` model — would deserialize as `Unknown`. |
| **Compaction** | `compaction` | ❌ Ignored | Info pill: `"Context compacted — summary preserved"` (dimmed, non-interactive). Appears once per compaction event. | Context compaction. No `OpenCodePart` model — would deserialize as `Unknown`. Server handles compaction automatically (see AGENTS.md). No SSE event type for compaction notification — would need server addition. |
| **Subtask** | `subtask` | 🔶 Partially handled (REST, no SSE) | Subagent pill (reuses existing `SubagentSessionBar` composable). Shows agent name, task description, status (running/completed). **Clickable** — currently switches entire chat to child session (breaks parent streaming). Split view is planned — see §6. | Currently populated via REST: `ChatViewModel.loadSessions()` finds child sessions by `parentID`, then `injectSubagentRefs()` creates `MessagePart.Subagent`. No SSE `subtask` event type exists yet — §6.4 describes the desired SSE-driven flow awaiting server support. |

> **Unknown parts handling:** Parts with unrecognized `type` values deserialize as `OpenCodePart.Unknown(type, rawJson)` via the catch-all in `OpenCodePartSerializer`. The current behavior is silent discard — `Unknown` parts are never surfaced to the user or logged. **Decision needed:** Should unknown parts be logged at DEBUG level for diagnostics? Should they be stored for future rendering when new types are added? At minimum, a DEBUG log entry would help identify new server part types before the plugin adds support.

---

## 4. Reasoning Routing on V1 Path — Bug Confirmed

> **Status:** Verified against server source (`packages/opencode/src/session/processor.ts`). The `"thinking"` and `"reasoning"` branches in the V1 handler are dead code — the server never sends those field values.

**Current code:** `OpenCodeClient.kt:692-699` — the V1 `message.part.delta` handler routes by `field` value:

```kotlin
when (field) {
    "text" -> SseEvent.TextChunk(sessionId = sessionId, text = delta)
    "thinking", "reasoning" -> SseEvent.ThinkingChunk(sessionId = sessionId, text = delta)
    else -> null
}
```

### Server behavior (verified)

In `processor.ts:329-340`, when the AI SDK emits a `reasoning-delta`, the server calls `updatePartDelta` with `field: "text"` — the same field value used for regular text deltas (line 645-651):

```typescript
// reasoning-delta (processor.ts:334-340)
yield* session.updatePartDelta({
  sessionID: ctx.reasoningMap[value.id].sessionID,
  messageID: ctx.reasoningMap[value.id].messageID,
  partID: ctx.reasoningMap[value.id].id,
  field: "text",        // ← ALWAYS "text", even for reasoning
  delta: value.text,
})

// text-delta (processor.ts:645-651) — identical field value
yield* session.updatePartDelta({
  sessionID: ctx.currentText.sessionID,
  messageID: ctx.currentText.messageID,
  partID: ctx.currentText.id,
  field: "text",
  delta: value.text,
})
```

**The server never sends `field: "thinking"` or `field: "reasoning"`.** Both reasoning and text deltas use `field: "text"` on the V1 bus. The plugin's `"thinking"` and `"reasoning"` branches are unreachable.

### Impact

**Without `flags.experimentalEventSystem`:** Only V1 `message.part.delta` fires → all reasoning content is misrouted to `TextChunk` and appears as regular text in the chat.

**With `flags.experimentalEventSystem`:** Both V1 `message.part.delta` (misrouted to `TextChunk`) and V2-bridged `session.next.reasoning.delta` (correctly routed to `ThinkingChunk`) fire → reasoning content appears **twice** (once as text, once as thinking).

### Why `partID` is the fix

The V1 `PartDelta` schema (`message-v2.ts:536-545`) **does include `partID`** — the earlier assumption that it was absent was incorrect:

```typescript
PartDelta: BusEvent.define(
  "message.part.delta",
  Schema.Struct({
    sessionID: SessionID,
    messageID: MessageID,
    partID: PartID,       // ← present on the wire
    field: Schema.String,
    delta: Schema.String,
  }),
),
```

The plugin ignores `partID` at line 693 — it only reads `field` and `delta`. Meanwhile, `message.part.updated` (line 702-718) correctly identifies reasoning parts by `part.type == "reasoning"` and includes the part ID. So the information to distinguish reasoning from text deltas exists but is unused.

### Recommended fix

1. Maintain a `Set<String>` of reasoning `partID` values, populated when `message.part.updated` arrives with `part.type == "reasoning"` (or `"thinking"`)
2. In the `message.part.delta` handler, when `field == "text"`, extract `partID` from `props["partID"]` and check if it is in the reasoning set
3. If yes → `SseEvent.ThinkingChunk`; if no → `SseEvent.TextChunk`
4. Remove the dead `"thinking"` / `"reasoning"` field branches
5. Clean up the reasoning set on `message.part.updated` with `part.type == "reasoning"` + end marker, or on session completion

**Effort:** Low — ~15 lines of state tracking + routing logic.

**V2 path already works:** `session.next.reasoning.delta` is correctly routed to `ThinkingChunk` in both `SseEventListener.kt:85` (ACP path) and `OpenCodeClient.kt:605` (chat UI path). The bug only affects the V1 path.

---

## 5. Patch Part: Implementation & Migration

> The `patch` part replaces the current `extractFilePath()` hack with authoritative server-side data.

### 5.1 What patch provides

```typescript
{ type: "patch", hash: string, files: string[] }
```

- `files` = absolute file paths changed during the step (no line counts)
- `hash` = git tree object hash from `git write-tree` at step-start (base for revert)

### 5.2 What plugin must compute

To render the "N Changed files +71 -2" card, the plugin needs to run git commands against the hash:

| Data needed | Git command | Used for |
|-------------|-------------|----------|
| Line counts per file | `git diff --numstat <hash> -- .` | "+71 -2" display per file row |
| File status (added/modified/deleted) | `git diff --name-status <hash> -- .` | Icon/color per file (green=added, yellow=modified, red=deleted) |
| Revert a file | `git checkout <hash> -- <file>` | "Revert" action on file row |
| Full diff | `git diff <hash> -- <file>` | Diff viewer (future) |

### 5.3 Current approach (to be replaced)

| Component | Current | Replacement |
|-----------|---------|-------------|
| File path extraction | `extractFilePath()` in MessageProcessorManager.kt — parses tool input JSON for `file_path`/`filePath`/`path` keys | `patch.files[]` — server-provided, authoritative |
| Storage | `ctx.pendingFileChanges: MutableList<ChatFileChange>` in ProcessorContext | `MessagePart.Patch(hash, files)` in message parts list |
| Signal | `UiSignal.FileChanged` → `_fileChangeSignal` → ReviewPanel refresh | `MessagePart.Patch` rendered inline in `AssistantMessage` |
| Trigger | Per tool call (ToolKind.EDIT only) | Once per step at step-finish |
| Coverage | Only tools with file path in input | All file changes during the step (server diffs git tree) |

### 5.4 Migration steps

**Phase 1 — Infrastructure (must come first):**

1. Add `OpenCodePart.Patch(hash: String, files: List<String>)` to `OpenCodePart` sealed interface and register in `OpenCodePartSerializer.knownTypes` (alongside `image`, `agent`, `retry`, `compaction`, `snapshot` — add models for all remaining types)
2. Add `SseEvent.Patch(sessionId, hash, files)` to `SseEvent` sealed interface
3. Handle `message.part.updated` with `part.type == "patch"` in `OpenCodeClient.parseSseEvent()` and `SseEventListener.parseByType()` → emit `SseEvent.Patch`
4. Verify all `OpenCodePart` models have the fields the server actually sends (add usage/cost fields to `StepFinish`, add fields for `agent`, `retry`, `compaction`, etc.)

**Phase 2 — Display layer:**

5. Add `MessagePart.Patch(hash: String, files: List<String>)` to `MessagePart` sealed interface
6. In `MessageProcessorManager.processEvent()`: store `patch` parts in message parts list when `SseEvent.Patch` arrives
7. Add `PatchCard` composable: runs `git diff --numstat <hash>` to get line counts, renders the card
8. Remove `extractFilePath()`, `pendingFileChanges`, `UiSignal.FileChanged`, `_fileChangeSignal`
9. Remove `ChatFileChange` model class (replaced by `MessagePart.Patch`)
10. Update `RenderFileChanges` / `FileChangesList` to use patch data instead of `ChatFileChange`

### 5.5 Git hash caveat

`patch.hash` is described as a git tree object hash from `git write-tree`. The git commands in §5.2 assume this is a tree-ish. If the server provides a **commit hash** instead of a tree hash, `git diff --numstat <hash>^{tree} -- .` would be needed, or the command needs to be verified against actual server output. Verify against the server source (`packages/opencode/src/session/message-v2.ts`) or capture test data before implementing.

---

## 6. Subtask Interaction Model

> **Problem:** Currently, clicking a sub session in the sidebar switches the entire chat view to that session, stopping the parent task's streaming. The user loses context and cannot monitor both tasks simultaneously.

> **Goal:** Clicking a subtask pill should show the sub session's activity **without stopping the parent task**. The user should be able to switch between parent and child views freely.

### 6.1 Current Behavior (Broken)

```
User sends message -> Main task starts streaming
  -> Subtask pill appears (sub session created)
  -> User clicks subtask pill
  -> Chat switches to sub session -> MAIN TASK STOPS STREAMING
  -> User cannot return to main task without losing sub task context
```

### 6.2 Desired Behavior

```
User sends message -> Main task starts streaming
  -> Subtask pill appears (sub session created)
  -> User clicks subtask pill
  -> Sub session opens in split view / tab / overlay -> MAIN TASK CONTINUES
  -> User can switch between parent and child freely
  -> Both sessions stream independently
  -> User can close sub view and return to parent
```

### 6.3 Implementation Approach

**Option A: Split View (Recommended)**
- Clicking a subtask pill splits the chat panel vertically (or opens a side panel)
- Left/primary panel: parent session (continues streaming)
- Right/secondary panel: child session (streams independently)
- Both panels share the same `MessageProcessorManager` infrastructure but have separate instances
- A breadcrumb trail shows session hierarchy: `Parent > Subtask: "explore codebase"`

**Option B: Tab View**
- Subtask opens as a new tab in the chat tool window
- Parent tab continues streaming in background
- User switches tabs to check progress
- Simpler to implement but less contextual

**Option C: Overlay/Popover**
- Subtask content shows in a floating panel over the parent chat
- Parent continues streaming behind the overlay
- Good for quick checks but not sustained monitoring

### 6.4 Data Flow

**Current (REST-based):**
```
1. ChatViewModel.loadSessions() fetches all sessions via GET /session
2. Child sessions identified by parentID matching current session
3. injectSubagentRefs() creates MessagePart.Subagent for each child
4. SubagentSessionBar renders the pill with agent name + status
5. Clicking pill → switchSession() → parent streaming STOPS
```

**Desired (SSE-driven):**
```
1. SSE `session.next.subtask` -> processor emits `SseEvent.Subtask`     ← AWAITING SERVER IMPLEMENTATION
2. Processor creates `MessagePart.Subtask(prompt, description, agent, model)`
3. `SubagentSessionBar` renders the pill with agent name + task description
4. User clicks pill -> ViewModel opens sub session in split view
5. ViewModel creates a SECOND `MessageProcessorManager` instance for the child session
6. Child SSE subscription runs independently (same `OpenCodeClient`, different session ID)
7. Parent SSE subscription continues uninterrupted
8. Both `MessageProcessorManager` instances call ViewModel callbacks to update their respective message lists (following the existing pattern where `ChatViewModel` owns `_messages` StateFlow, not the processor)
9. Each panel collects from its own ViewModel's StateFlow
```

**Note:** `SseEvent.Subtask` and the server-side `session.next.subtask` event do not exist yet. The current REST-based subagent detection is functional but limited — it only discovers child sessions after they appear in the session list, not in real-time during streaming. The split view architecture (Option A) can be built on the REST baseline and upgraded to SSE-driven later.

### 6.5 Key Constraints

- **Parent must not stop:** The parent session's SSE subscription and message processing continue while the child is open. This requires the `ChatViewModel` to support multiple concurrent active sessions (currently it only tracks one `sessionId`).
- **Session IDs are independent:** Parent and child have separate `ses_xxx` IDs. SSE subscriptions are per-session. No shared state between processors except the `OpenCodeClient` HTTP connection.
- **Cleanup:** Closing the sub view cancels the child's SSE subscription and processor. The parent's state is untouched.
- **History:** Both sessions persist server-side. Switching back to a session loads its history from `GET /session/:id`.

---

## 7. Visual Component Design Notes

- **Pill** = single-line bordered row with icon + text, similar to existing `ToolPill` but lighter (no background, 11sp font)
- **Badge** = small inline tag (e.g., colored chip with agent name)
- **Card** = bordered container with header + expandable detail section
- **Divider** = horizontal line with optional label, used for step boundaries

**Current rendering order** in `MessageProcessorManager.flushToMessages()` (lines 472-493):
1. `ThinkingPill` (reasoning)
2. `ToolPill` (tool calls)
3. Text/Code/Table content
4. `FileChange` (file changes from extractFilePath)
5. `Error` (error messages)
6. `Subagent` (injected externally via `injectSubagentRefs()`, appends after all other parts)

**Proposed rendering order** (extends current to add new part types):
1. `ThinkingPill` (reasoning)
2. `ToolPill` (tool calls)
3. `AgentBadge` (agent) — inline, right-aligned or as prefix to next part
4. Text/Code/Table content
5. `PatchCard` (patch) — inline at end of turn, shows "N Changed files +X -Y" (replaces FileChange)
6. `AssistantFile` cards (file)
7. `SubagentSessionBar` (subtask) — currently at end after Error
8. `StepFinishPill` (token counts) — at end of step
9. `RetryPill` (retry) — only if retrying
10. `CompactionPill` (compaction) — only if compacted
11. `Error` parts

---

## 8. Missing Event Handling in Chat UI

`OpenCodeClient.parseSseEvent()` handles V2 types for core streaming events (text, reasoning, tool) but is **missing 5 V2 event types** that `SseEventListener` (ACP path) already handles. If the V2 bridge becomes the primary path or V1 events are deprecated, these gaps will cause silent failures in the chat UI:

| V2 Event Type | `SseEventListener` | `OpenCodeClient` | Chat UI Impact |
|---|---|---|---|
| `session.next.permission` | ✅ | ❌ (only V1 `permission.asked`) | Permission prompts silently fail |
| `session.next.question` | ✅ | ❌ (only V1 `question.asked`) | Selection prompts silently fail |
| `session.next.todo.updated` | ✅ | ❌ (only V1 `todo.updated`) | Todo panel doesn't update |
| `session.next.stopped` | ✅ | ❌ (only V1 `stop`) | Streams don't end properly |
| `session.next.created` | ✅ | ❌ (only V1 `session.created`) | New sessions not detected |

Additionally, `OpenCodeClient` does not parse 2 V1 event types that `SseEventListener` handles:

| V1 Event Type | `SseEventListener` | `OpenCodeClient` | Chat UI Impact |
|---|---|---|---|
| `plan` | ✅ → `SseEvent.Plan` | ❌ Not parsed | Plan events silently dropped (chat UI handles `SseEvent.Plan` as no-op, but never receives it) |
| `message_complete` | ✅ → `SseEvent.MessageComplete` | ❌ Not parsed | Message completion events silently dropped (chat UI handles `SseEvent.MessageComplete` as no-op, but never receives it) |

> **Note on `SseEventListener.parseByType()`:** This static method is **dead code** in the main event flow. `SseEventListener` wraps `openCodeClient.subscribeGlobalEvents()` and receives already-parsed `SseEvent` objects — it does NOT call `parseByType()` at runtime. The method is only reachable via the `parseEvent()` companion function, which is not called from the main event flow. The asymmetry table above compares parsing capability, but implementers should be aware that `parseByType()` is not an active alternative parser.

**Fix:** Add V2 parsing branches for all 5 event types in `OpenCodeClient.parseSseEvent()`, mirroring the existing V1 handling. Add V1 `plan` and `message_complete` parsing if the server sends these events. The data extraction patterns already exist in `SseEventListener.parseByType()` and can be adapted directly.

---

## 9. Priority Implementation Order

| Priority | Part Type / Fix | Effort | Impact |
|----------|------------------|--------|--------|
| P0 | Reasoning routing (V1 path) | Low | **Bug confirmed.** Server sends `field: "text"` for both text and reasoning — `"thinking"`/`"reasoning"` branches are dead code. All V1 reasoning appears as regular text. Fix: track reasoning `partID` set from `message.part.updated`, disambiguate in `message.part.delta` handler — see §4 |
| P0 | V2 event coverage (5 missing types in OpenCodeClient) | Low | Chat UI doesn't silently break when V2 bridge is primary path — see §8 |
| P0 | V1 `plan` + `message_complete` parsing | Low | Chat UI currently drops these events silently — see §8 |
| P0 | Register `"tool"` in `OpenCodePartSerializer.knownTypes` | Low | V1 `type: "tool"` parts deserialize as `Unknown` — SSE routing works but REST deserialization fails |
| P1 | `subtask` (split view) | High | Concurrent parent/child monitoring — see §6 |
| P1 | `step-finish` (token counts) | Medium | Accurate token/cost display per step. **Prerequisite:** Add usage/cost fields to `OpenCodePart.StepFinish` model |
| P2 | `file` | Medium | Show assistant-generated files inline |
| P2 | `compaction` | Low | "Context compacted" notification. **Note:** Needs server-side SSE event addition |
| P2 | `patch` | Medium | Authoritative file change data — replaces extractFilePath() hack. **Prerequisite:** Add `OpenCodePart` + `SseEvent` models — see §5.4 |
| P2 | `image` | Medium | Inline image rendering (deferred per AGENTS.md) |
| P3 | `retry` | Low | Show retry status |
| P3 | `agent` | Low | Show active agent name |
| Low | `step-start`, `snapshot` | Low | Internal/niche — minimal user-facing value |

**Infrastructure prerequisites** — these must be done before the visual components:
- Add `OpenCodePart` data classes for `Patch`, `Agent`, `Retry`, `Compaction`, `Snapshot` and register them in `OpenCodePartSerializer.knownTypes`
- Add `SseEvent` variants for each new part type:
  - `SseEvent.Patch(sessionId, hash, files)` — file change summary from server
  - `SseEvent.Agent(sessionId, agentName)` — active agent identification
  - `SseEvent.Retry(sessionId, attempt, maxAttempts, error)` — retry status
  - `SseEvent.Compaction(sessionId)` — context compaction notification
  - `SseEvent.Snapshot(sessionId)` — snapshot marker (internal)
  - `SseEvent.StepFinish(sessionId, usage/cost fields)` — step completion with token counts
  - `SseEvent.File(sessionId, mime, url, filename)` — assistant-generated file
  - `SseEvent.Image(sessionId, mime, url, filename)` — assistant-generated image
  - `SseEvent.Subtask(sessionId, prompt, description, agent, model)` — subtask creation (awaiting server support)
- Add `MessagePart` subclasses for `Patch`, `AssistantFile`, `Image`, `Agent`, `StepFinish`, `Retry`, `Compaction` — these extend the `sealed interface MessagePart` hierarchy (currently 8 subclasses)
- Add SSE parsing branches in `OpenCodeClient.parseSseEvent()` and `SseEventListener.parseByType()`
- Update `MessageProcessorManager.flushToMessages()` to include new part types in the rendering order (see §7 for proposed order)
- Add usage/cost fields to `OpenCodePart.StepFinish` beyond just `snapshot` (verify server payload first)
- Register `"tool"` in `OpenCodePartSerializer.knownTypes` (currently only `"tool_use"` is registered — V1 `type: "tool"` parts deserialize as `Unknown`)

---

## 10. Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-06-06 | — | Initial draft — extracted from message-processor.md §11 amendment |
| 2026-06-06 | — | Review fixes: corrected status counts (2 handled + 1 verification needed + 3 partial + 7 ignored, not 5+1+2+5); added `image` as 13th type; fixed `ThinkingPill` description (plain text, not collapsible); noted `File` and `Subtask` as partially handled via REST; noted `StepFinish` model missing usage/cost fields; corrected `extractFilePath()` location to MessageProcessorManager.kt; added V2 event coverage gap (§8); added infrastructure prerequisites to priority table; noted `session.next.subtask` SSE event doesn't exist yet; added `OpenCodePart`/`SseEvent` model gaps for all new types; noted `compaction` needs server-side SSE addition; added git hash caveat (§5.5); clarified current rendering order vs proposed |
| 2026-06-06 | — | Second review: added version suffix note + AGENTS.md cross-reference to §2; added verification warnings to §4 reasoning bug (unverified `field` claim, unverified `partID` availability, cleanup lifecycle); clarified StepFinish payload needs capture before model changes (§3); renamed `FileAttachment` → `AssistantFile` to avoid confusion with `FileChange`; clarified Snapshot ambiguity (§3); added server file reference to git hash caveat (§5.5); fixed §6.4 StateFlow ownership (processors call ViewModel callbacks, not direct emission); added `MessagePart` subclass additions + `flushToMessages()` update to §9 infrastructure prerequisites |
| 2026-06-06 | — | Codebase verification review: corrected status counts to 2+1+3+7=13 (was 5+1+2+5); reframed §4 reasoning routing as "needs verification" (code already handles `field: "thinking"`/`"reasoning"` — the "bug" claim is unverified); added V1 `plan` and `message_complete` parsing gaps to §8; noted `SseEventListener.parseByType()` is dead code in main event flow; flagged `type: "tool"` deserialization gap in `OpenCodePartSerializer` (only `"tool_use"` registered); added Unknown parts handling decision to §3; enumerated specific `SseEvent` variants needed in §9; added `"tool"` registration to §9 infrastructure prerequisites |
| 2026-06-06 | — | §4 reasoning routing verified against server source (`processor.ts:334-340`): bug confirmed — server sends `field: "text"` for reasoning deltas, `"thinking"`/`"reasoning"` branches are dead code; `partID` is present on V1 wire (schema at `message-v2.ts:541`); documented dual-event duplication risk with experimental flag; added recommended fix (partID tracking set); promoted from "Verify" to "P0" in §9 priority table |
