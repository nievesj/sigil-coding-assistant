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
