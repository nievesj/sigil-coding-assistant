# Technical Design Document: JetBrains Skill Bridge

> **Status:** Draft
> **Last Updated:** 2026-07-23
> **Related docs:** AGENTS.md (MCP Integration, Configurable Server Port, Slash Command Palette), `docs/tdd/opencode-service-decomposition.md` (McpConfigWriter)

---

## 1. TL;DR

Two features that make JetBrains AI Assistant skills work seamlessly with OpenCode through Sigil:

1. **Skill Bridge** — Automatically writes the IDE's skill storage path into `skills.paths` in `.opencode/opencode.json` before the OpenCode server launches, so OpenCode discovers IDE-installed skills natively. No file copying, no format conversion, no user configuration.
2. **`$` Skill Invocation** — A skill palette in the input area (mirroring the existing `/` slash command palette) that lets the user manually invoke a skill by typing `$` followed by the skill name. Skills are fetched from OpenCode's `GET /skill` endpoint. When selected, the skill content is injected into the user's message so the agent receives the skill instructions directly.

---

## 2. Context & Scope

### 2.1 Current State

JetBrains AI Assistant (2026.2) includes a Skills Manager — a marketplace UI where users browse, install, enable/disable, and uninstall agent skills. Skills are sourced from external registries (GitHub repos like `JetBrains/skills`, `dotnet/skills`) and local directories. Each skill is a folder containing a `SKILL.md` file (YAML frontmatter + Markdown body) following the Anthropic `agentskills.io` open standard.

When a user installs a skill at **IDE scope**, the files are placed at:

| Platform | Path |
|---|---|
| Windows | `%LOCALAPPDATA%\JetBrains\<product><version>\aia\agents\.agents\skills\<skill-name>\` |
| macOS | `~/Library/Caches/JetBrains/<product><version>/aia/agents/.agents/skills/<skill-name>/` |
| Linux | `~/.cache/JetBrains/<product><version>/aia/agents/.agents/skills/<skill-name>/` |

OpenCode's skill discovery (source: `packages/opencode/src/skill/skill.ts`) scans these directories **by default**:

- `~/.claude/skills/` and `~/.agents/skills/` (user-level)
- `<project>/.claude/skills/` and `<project>/.agents/skills/` (project-level, walking up to git root)
- `.opencode/skills/` and `~/.config/opencode/skills/` (OpenCode-native)
- Custom paths from `skills.paths` array in `opencode.json` (additive to defaults)

The IDE-internal `aia/agents/.agents/skills/` path is **not** among the defaults. OpenCode cannot see IDE-scoped skills without explicit configuration.

Sigil already writes `opencode.json` via `McpConfigWriter` before launching the OpenCode server (`ProcessManager.initialize()` line ~120). It currently writes `mcp` and `agent` sections only — no `skills` section.

**JetBrains' `$` invocation convention:** The AI Assistant docs state: *"Agents can run installed skills either automatically, when they are relevant to a task, or you can invoke them manually by typing the `$` sign followed by the name of the skill."* This is a JetBrains-specific convention — OpenCode does not recognize `$` as a skill prefix. The agent invokes skills through its native `skill` tool (calling `skill({ name: "..." })`), not through user-typed prefixes.

**OpenCode `GET /skill` endpoint:** The OpenCode server exposes `GET /skill` (registered in `packages/opencode/src/server/server.ts`) which returns an array of `{ name, description, location, content }` for all discovered skills — including the full SKILL.md content. This endpoint is not on the public docs page but is in the OpenAPI spec at `http://127.0.0.1:4096/doc`. This is the data source for the skill palette.

**Existing slash command palette:** Sigil already has a `/` slash command palette (`SlashCommandPalette.kt`) with filtering, keyboard navigation, and popup rendering. The `$` skill palette follows the same pattern.

### 2.2 Problem Statement

**Bridge:** A user installs a skill through the IDE's Skills Manager. They expect it to "just work" with their AI agent. But if their agent is OpenCode (via Sigil), the skill is invisible — it's stored in a directory OpenCode doesn't scan. The user has no way to know this, and no UI to fix it. The skill sits unused.

**`$` Invocation:** The user wants to manually invoke a specific skill (not wait for the agent to decide it's relevant). In JetBrains AI Assistant, they type `$skill-name`. In Sigil, there's no equivalent — the user has no way to force a skill into the agent's context. They could manually paste the SKILL.md content, but they'd have to find the file on disk first.

---

## 3. Goals & Non-Goals

### Goals

1. **Seamless bridge:** User installs a skill via the IDE Skills Manager → OpenCode sees it on the next session. No toggles, no settings, no manual path configuration.
2. **Non-destructive bridge:** The bridge adds to `skills.paths` in `opencode.json` without removing or overwriting existing entries (user-added paths, paths from other sources).
3. **Idempotent bridge:** Writing skill paths multiple times (on server launch, on server restart, on settings change) does not create duplicate entries.
4. **Graceful degradation (bridge):** If the AI Assistant plugin is not installed, the skill directory doesn't exist, or the OpenCode binary doesn't support `skills.paths`, nothing breaks — the feature silently does nothing.
5. **`$` palette:** User types `$` in the input area → a filtered skill palette appears, showing all skills OpenCode has discovered (including bridged JetBrains skills). User selects a skill → the skill content is injected into the message so the agent receives the instructions.
6. **`$` palette UX parity:** The palette matches the existing `/` slash command palette in behavior — filtering as user types, Up/Down navigation, Enter to select, Escape to dismiss.
7. **`$` invocation reliability:** The skill content is injected directly into the user's message text, not delegated to the agent's `skill` tool. This guarantees the agent sees the skill instructions regardless of whether it would have called the `skill` tool on its own.

### Non-Goals

- **Watching for real-time skill installs** — no filesystem watcher. Skills installed after server launch are picked up on the next server restart or session creation (OpenCode re-scans on new sessions). The `$` palette fetches from `GET /skill` on each session init and session switch.
- **Bridging Rider's bundled MCP-tool skills** (dotTrace, dotCover, refactoring) — those are compiled MCP tools, not SKILL.md files. They're already accessible via the IDE's MCP server, which Sigil already configures.
- **Syncing skills to external agent directories** (`~/.claude/skills/`, `~/.codex/skills/`) — the IDE's Skills Manager already offers this as a manual install scope. We don't replicate it.
- **Settings UI for the bridge** — no checkbox or configuration field. The bridge is always on when the skill directory exists.
- **Skill management UI** — no install/uninstall/enable/disable UI in Sigil. That's the JetBrains Skills Manager's job. Sigil only discovers and invokes.
- **`$` palette as a skill browser** — the palette is for invocation, not browsing. It shows skill name + description (like the slash command palette), not full skill content. Full content is injected only on selection.

---

## 4. Proposed Solution

**Two interconnected features:**

**Feature 1 — Skill Bridge:** Automatically detect the JetBrains IDE's skill storage directory at runtime and write it into `skills.paths` in `opencode.json` before the OpenCode server launches. OpenCode's native skill discovery scans `skills.paths` directories using `**/SKILL.md` glob, so no file copying or format conversion is needed — the SKILL.md format is identical (both follow the `agentskills.io` spec).

The bridge runs at three points: (1) initial server launch in `ProcessManager.initialize()`, (2) server restart in `OpenCodeService.resetMcpOnServerRestart()`, and (3) settings reapplication in `OpenCodeService.reinitializeMcpFromSettings()`. At each point, it detects the IDE skill path, merges it into the existing `skills.paths` array (deduplicating), and writes the config atomically.

**Feature 2 — `$` Skill Invocation:** A skill palette in the input area, triggered by typing `$` at the start of the input (mirroring the `/` slash command trigger). The palette shows skills fetched from OpenCode's `GET /skill` endpoint, filtered as the user types. When the user selects a skill, the `$skill-name` text is replaced with the skill's content wrapped in a delimiter, and the user's remaining text (their actual request) is appended after. The combined message is sent to OpenCode as a normal user message.

The message format on selection:
```
<skill_content name="git-release">
[full SKILL.md body content from GET /skill response]
</skill_content>

[user's remaining text, e.g., "create a release for v1.2"]
```

This guarantees the agent receives the skill instructions as part of the user's message — no dependency on the agent deciding to call the `skill` tool. The agent sees the instructions and follows them.

> **Security note (added 2026-07-26):** The `skill.name` value inserted into the
> `name="..."` attribute is untrusted data (it comes from the OpenCode server's
> `GET /skill` endpoint, which aggregates skills from external registries). The
> implementation MUST escape `skill.name` before insertion to prevent prompt
> injection — a malicious skill name containing `"` or `</skill_content>` could
> inject arbitrary content into the user's LLM message. The implementation uses
> `escapeSkillName()` (InputArea.kt) which escapes `&`, `"`, `<`, `>`.

### 4.3 API / Interface Design

**Feature 1 (Bridge) — internal interfaces only:**

**`McpConfigWriter.writeSkillPaths(paths: List<String>): Boolean`**
Read-modify-write on `opencode.json`, following the same pattern as `write()` and `writeToolPermissions()`. Implements Q4 stale-path eviction: classifies existing `skills.paths` entries as plugin-managed (via `JetBrainsSkillBridge.isPluginManagedPath()`) or user-added, evicts plugin-managed paths, and replaces them with [paths]. Preserves user-added paths, `skills.urls`, and all other config keys including `$schema`.

**`JetBrainsSkillBridge.detectSkillPaths(): List<String>`**
Static utility that returns the list of skill directories to bridge. Detects two paths (per §10 Q2): the IDE-level skill storage path (gated on AI Assistant plugin presence) and `~/.codex/skills/` (Codex Global scope, existence check only). Delegates to a pure `detectSkillPathsPure()` function for testability. Also exposes `isPluginManagedPath(path)` for Q4 stale-path eviction.

**Feature 2 (`$` Invocation) — OpenCode API + internal interfaces:**

**`GET /skill` (OpenCode server endpoint — existing, not new)**
Returns `[{ name: string, description: string, location: string, content: string }]` for all discovered skills. The `content` field contains the full SKILL.md body (without frontmatter). Called by `ChatViewModel.fetchAvailableSkills()` on init and session switch.

**`OpenCodeClient.listSkills(): List<SkillInfo>` (new HTTP client method)**
Wraps `GET /skill`. Returns parsed skill list. Follows the same pattern as `listCommands()`.

**`SkillInfo` data class (new)**
```kotlin
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val location: String = "",
    val content: String = ""
)
```

**`ChatViewModel.availableSkills: StateFlow<List<SkillInfo>>` (new)**
StateFlow holding the current skill list. Populated by `fetchAvailableSkills()`. Collected in `ChatScreen.kt` and passed to `InputArea`.

**`InputArea` new parameters:**
```kotlin
onSkillSelected: (SkillInfo) -> Unit = {},
availableSkills: List<SkillInfo> = emptyList(),
```

**`SkillPalette.kt` (new composable)**
Follows the `SlashCommandPalette.kt` pattern exactly. Renders a popup with filtered skill names + descriptions. Keyboard navigation (Up/Down/Enter/Escape).

### 4.5 Technology Stack

| Layer | Technology | Rationale |
|---|---|---|
| Language | Kotlin | Existing codebase |
| Config format | JSON (`opencode.json`) | OpenCode's native config format |
| Path detection | `PathManager.getSystemPath()` | IntelliJ Platform API — resolves to the correct IDE-specific cache directory on all platforms |
| Plugin detection | `PluginManager.isPluginInstalled(PluginId)` | Core Platform API — no dependency on AI Assistant plugin at compile time |
| Config writing | `McpConfigWriter.writeConfig {}` | Existing atomic read-modify-write pattern with file-level locking |
| Skill data source | `GET /skill` (OpenCode HTTP API) | Returns full skill list with content — no filesystem scanning needed |
| Skill palette UI | Compose (Jewel) | Matches existing `SlashCommandPalette.kt` pattern |
| Keyboard handling | `InputKeyboardHandler.kt` | Existing keyboard dispatch infrastructure — extend with skill palette actions |

### 4.7 Implementation Blueprint

> ⚠️ Blueprint code is illustrative, not authoritative. Developers must validate against a real compiler.

#### 4.7.1 Data Models & Schemas

**Feature 1 — `opencode.json` `skills` section** (per `https://opencode.ai/config.json` schema):

```json
{
  "skills": {
    "paths": [
      "C:\\Users\\josen\\AppData\\Local\\JetBrains\\IntelliJIdea2026.2\\aia\\agents\\.agents\\skills"
    ],
    "urls": []
  }
}
```

No new Kotlin data classes needed — the config is written as `JsonObject`/`JsonArray` via kotlinx.serialization JSON DSL, matching the existing `McpConfigWriter` pattern.

**Feature 2 — Skill data model:**

```kotlin
// In OpenCodeModels.kt — new data class
@Serializable
data class SkillInfo(
    val name: String,           // skill name (matches directory name)
    val description: String,    // from YAML frontmatter
    val location: String = "",  // filesystem path to skill directory
    val content: String = ""    // full SKILL.md body (without frontmatter)
)
```

#### 4.7.2 Class & Interface Definitions

**A. `JetBrainsSkillBridge` — new utility class (Feature 1)**

```kotlin
package com.opencode.acp.skill

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Detects JetBrains AI Assistant skill directories that OpenCode
 * does not scan by default, so they can be bridged via skills.paths
 * in opencode.json.
 *
 * This is a stateless utility — no instance state, no lifecycle.
 *
 * Testability: the pure path-construction logic is extracted into
 * [detectSkillPathsPure], which takes basePath + isAiAssistantInstalled
 * as parameters. The public [detectSkillPaths] delegates to it with
 * real Platform values. Unit tests call [detectSkillPathsPure] directly
 * — no mocking of PluginManager or PathManager required (per AGENTS.md
 * "extract pure-logic portions" pattern for Platform code).
 */
object JetBrainsSkillBridge {

    // ⚠️ Verify against the actual installed plugin before relying on this.
    // If the ID changes in a future AI Assistant version, the bridge silently
    // stops working (returns empty list for the IDE path). Log a warning if
    // the plugin is "installed" but the skill directory doesn't exist (possible
    // version mismatch). See AGENTS.md "MCP Integration" for the same pattern.
    private const val AI_ASSISTANT_PLUGIN_ID = "com.intellij.ai.assistant"

    /**
     * Returns skill directories that should be bridged to OpenCode.
     *
     * Detects two paths (per §10 Q2):
     *  1. IDE-level skill storage: {PathManager.getSystemPath()}/aia/agents/.agents/skills
     *     — gated on AI Assistant plugin presence + directory existence.
     *  2. Codex Global scope: ~/.codex/skills
     *     — gated on directory existence only (no plugin to detect).
     *
     * Returns an empty list if neither directory exists.
     *
     * @return List of absolute filesystem paths to skill directories
     */
    fun detectSkillPaths(): List<String> = detectSkillPathsPure(
        basePath = PathManager.getSystemPath(),
        isAiAssistantInstalled = PluginManager.isPluginInstalled(
            PluginId.getId(AI_ASSISTANT_PLUGIN_ID)
        ),
        userHome = System.getProperty("user.home"),
    )

    /**
     * Pure, testable core of [detectSkillPaths]. All Platform dependencies
     * are passed as parameters so this can be unit-tested without mocking.
     *
     * @param basePath Result of PathManager.getSystemPath() (IDE system dir)
     * @param isAiAssistantInstalled Whether the AI Assistant plugin is installed
     * @param userHome Result of System.getProperty("user.home")
     * @return Skill directory paths that exist on disk and should be bridged
     */
    fun detectSkillPathsPure(
        basePath: String,
        isAiAssistantInstalled: Boolean,
        userHome: String,
    ): List<String> {
        val paths = mutableListOf<String>()

        // 1. IDE-level skill storage (gated on plugin presence)
        if (isAiAssistantInstalled) {
            val idePath = Paths.get(basePath, "aia", "agents", ".agents", "skills")
            if (Files.isDirectory(idePath)) {
                paths.add(idePath.toString())
            }
        }

        // 2. Codex Global scope (existence check only — no plugin to detect)
        val codexPath = Paths.get(userHome, ".codex", "skills")
        if (Files.isDirectory(codexPath)) {
            paths.add(codexPath.toString())
        }

        return paths
    }

    /**
     * Returns true if [path] is a plugin-managed skill path (i.e., one that
     * [detectSkillPaths] would produce). Used by McpConfigWriter.writeSkillPaths()
     * to evict stale plugin-managed paths while preserving user-added paths.
     *
     * Matching is path-shape-based (per §10 Q4), not sentinel/comment-based.
     * Handles both forward-slash and backslash separators (Windows paths are
     * stored with backslashes in opencode.json — see §4.7.1). Matching is
     * case-insensitive on Windows, case-sensitive elsewhere.
     *
     * @param path The path string from skills.paths to classify
     * @return true if this path is plugin-managed (should be evicted on re-write)
     */
    fun isPluginManagedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.contains("/aia/agents/.agents/skills") ||
            normalized.contains("/.codex/skills")
    }
}
```

**Note on `~/.codex/skills/` source:** This path is the Codex CLI's global skill
directory. The JetBrains AI Assistant Skills Manager offers "Codex (Global)" as an
install scope that writes to this directory. **This assumption should be verified
against the actual Skills Manager UI before implementation** — if the scope name or
target directory differs, update `detectSkillPathsPure` accordingly. The existence
check means a non-existent directory is silently skipped, so an incorrect path is
a no-op, not a crash.

**Note on `PathManager.getSystemPath()`:** This returns the IDE's system (cache)
directory — `%LOCALAPPDATA%\JetBrains\<product><version>` on Windows,
`~/Library/Caches/JetBrains/<product><version>` on macOS,
`~/.cache/JetBrains/<product><version>` on Linux. The path is per-IDE-version, so
upgrading from 2026.1 to 2026.2 changes it — which is exactly why Q4's stale-path
eviction is necessary. See IntelliJ Platform SDK docs for `PathManager`.

**B. `McpConfigWriter.writeSkillPaths()` — new method on existing class (Feature 1)**

```kotlin
/**
 * Write skill paths to the "skills.paths" array in opencode.json.
 *
 * Implements §10 Q4 stale-path eviction: overwrites the plugin-managed
 * subset of skills.paths (paths matching JetBrainsSkillBridge.isPluginManagedPath)
 * with [paths], while preserving user-added paths and skills.urls.
 *
 * This is NOT a simple merge — plugin-managed paths from previous writes
 * (e.g., from an old IDE version) are evicted. User-added paths (custom
 * paths not matching the plugin-managed pattern) are always preserved.
 *
 * Preserves skills.urls and all other config keys including $schema
 * (writeConfig handles $schema — do not strip it here, per M1).
 *
 * @param paths Plugin-managed skill directory paths to write (from detectSkillPaths)
 * @return true if config was written successfully, false on error
 */
fun writeSkillPaths(paths: List<String>): Boolean {
    val success = writeConfig { config ->
        val existingSkills = config["skills"]?.jsonObject
        val existingUrls = existingSkills?.get("urls")?.jsonArray
        val existingPaths = existingSkills?.get("paths")?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        // Q4: Partition existing paths into user-added (preserve) and
        // plugin-managed (evict). Plugin-managed paths are fully
        // determined at runtime by detectSkillPaths() — there is no
        // reason to keep stale ones from old IDE versions.
        val userPaths = existingPaths.filter { !JetBrainsSkillBridge.isPluginManagedPath(it) }
        val finalPaths = (userPaths + paths).distinct()

        // Build the new config. Preserve all keys except "skills" (rebuilt below).
        // Do NOT strip $schema — writeConfig handles it (M1).
        buildJsonObject {
            for ((key, value) in config) {
                if (key != "skills") {
                    put(key, value)
                }
            }
            // Only write the "skills" section if there are paths OR urls to preserve.
            // If both are empty, omit the section entirely (clean config).
            if (finalPaths.isNotEmpty() || existingUrls != null) {
                put("skills", buildJsonObject {
                    if (finalPaths.isNotEmpty()) {
                        put("paths", buildJsonArray {
                            finalPaths.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                    // Preserve existing urls if present (H1: never destroy user urls)
                    if (existingUrls != null) {
                        put("urls", existingUrls)
                    }
                })
            }
        }
    }
    if (success) {
        logger.info { "[ACP] McpConfigWriter: wrote skill paths: $paths" }
    }
    return success
}
```

**Key behavior changes from the original blueprint:**
- **No destructive empty-list branch** (H1): When `paths` is empty, user paths and
  `skills.urls` are still preserved. The `skills` section is only omitted if BOTH
  paths and urls are empty.
- **Eviction, not merge** (C1): Plugin-managed paths from previous writes are
  evicted via `isPluginManagedPath()`. User paths are always preserved.
- **No `$schema` stripping** (M1): The base `writeConfig()` handles `$schema`
  preservation. Stripping it here was redundant and inconsistent with the `write()`
  method.

**C. `OpenCodeClient.listSkills()` — new HTTP client method (Feature 2)**

```kotlin
// In OpenCodeClient.kt — follows the listCommands() pattern
suspend fun listSkills(): List<SkillInfo> =
    getJson("/skill")
```

**D. `SkillPalette.kt` — new composable (Feature 2)**

```kotlin
package com.opencode.acp.chat.ui.compose

/**
 * Skill palette popup — shown when user types "$" in the input area.
 * Mirrors SlashCommandPalette.kt in structure and behavior.
 *
 * Displays skill name + size indicator + description, filtered by user input.
 * Keyboard: Up/Down to navigate, Enter to select, Escape to dismiss.
 *
 * Size indicator (per §10 Q5): shows "~Nk" next to the skill name so the
 * user can gauge context-window cost before injecting. Computed from
 * content.length (1k ≈ 250 tokens ≈ 4KB).
 *
 * // First non-escape char determines palette: '/' → slash, '$' → skill. See TDD §10 Q7.
 */
@Composable
fun SkillPalette(
    filtered: List<SkillInfo>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onSkillSelected: (SkillInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Structure mirrors SlashCommandPalette.kt exactly:
    //
    // if (filtered.isEmpty()) {
    //     // Empty state: "No matching skills" (muted, centered)
    //     return@Composable
    // }
    //
    // val scrollState = rememberScrollState()
    // val interactionSource = remember { MutableInteractionSource() }
    //
    // Surface(
    //     modifier = modifier
    //         .widthIn(max = paletteMaxWidth)  // same constant as SlashCommandPalette
    //         .shadow(elevation = paletteElevation, shape = paletteShape)
    //         .background(paletteBackgroundColor, shape = paletteShape)
    //         .border(paletteBorderWidth, paletteBorderColor, paletteShape),
    //     shape = paletteShape,
    // ) {
    //     Column(
    //         modifier = Modifier
    //             .verticalScroll(scrollState)
    //             .padding(vertical = paletteVerticalPadding),
    //     ) {
    //         filtered.forEachIndexed { index, skill ->
    //             val isSelected = index == selectedIndex
    //             val isHovered by interactionSource.collectIsHoveredAsState()
    //
    //             Row(
    //                 modifier = Modifier
    //                     .fillMaxWidth()
    //                     .hoverable(interactionSource)
    //                     .background(if (isSelected) selectionHighlight else Color.Transparent)
    //                     .padding(horizontal = paletteHorizontalPadding, vertical = paletteRowVerticalPadding)
    //                     .clickable { onSkillSelected(skill) },
    //                 verticalAlignment = Alignment.CenterVertically,
    //             ) {
    //                 // Icon: lightning (AllIcons.Actions.Lightning) wrapped via IntelliJIconKey
    //                 // Name: "$" + skill.name in blue (matches slash palette's "/" + command.id)
    //                 // Size indicator: "~{content.length / 1024}k" in muted, small font (Q5)
    //                 //   — only shown if content.length > 1024 (skip for tiny skills)
    //                 // Description: skill.description in muted, truncated with ellipsis
    //             }
    //         }
    //     }
    // }
    //
    // LaunchedEffect(selectedIndex) {
    //     if (selectedIndex in filtered.indices) {
    //         scrollState.animateScrollToItem(selectedIndex)
    //     }
    // }
}
```

**Size indicator format (Q5):** `~Nk` where N = `content.length / 1024` (rounded).
Only shown when `content.length > 1024` (1KB). For smaller skills, omit the indicator
to avoid clutter. The indicator uses the same muted color as the description text,
at a smaller font size.

**E. `InputArea.kt` — new state and integration (Feature 2)**

```kotlin
// New palette state (alongside existing slash palette state)
var showSkillPalette by remember { mutableStateOf(false) }
var skillSelectedIndex by remember { mutableStateOf(0) }

// Query extraction — "$" prefix, but NOT "$$" (escape) and NOT "$" followed
// by a non-letter (e.g., "$50", "$PATH" with no skill named "PATH" — but we
// still show the palette for "$PATH" since a skill COULD be named that; the
// real guard is: $ at position 0, not $$, no newline, and at least one char
// typed after $ that is a letter, digit, or hyphen — the common skill-name
// charset). This prevents "$50" from opening the palette (M7).
val currentText = textState.text.toString()
val skillQuery = if (currentText.startsWith("$") &&
    !currentText.startsWith("$$") &&
    currentText.length > 1 &&
    currentText[1].isLetterOrDigit() || currentText[1] == '-' || currentText[1] == '_') {
    currentText.substring(1)
} else ""

val filteredSkills = remember(skillQuery, availableSkills) {
    if (skillQuery.isBlank()) availableSkills
    else availableSkills.filter { it.name.startsWith(skillQuery, ignoreCase = true) }
}

LaunchedEffect(filteredSkills.size) { skillSelectedIndex = 0 }

// Trigger detection — watch text changes.
// Gate: starts with "$", NOT "$$" (escape), no newline, and second char is
// a valid skill-name-start character (letter/digit/hyphen/underscore).
// Mutual exclusion: skill palette only shows when slash palette is NOT visible
// (M2/M4). The first character determines which palette: '/' → slash, '$' → skill.
LaunchedEffect(Unit) {
    snapshotFlow { textState.text.toString() }
        .collect { text ->
            showSkillPalette = text.startsWith("$") &&
                !text.startsWith("$$") &&
                !text.contains("\n") &&
                text.length > 1 &&
                (text[1].isLetterOrDigit() || text[1] == '-' || text[1] == '_') &&
                !showSlashPalette  // mutual exclusion (M2/M4)
        }
}

// Popup rendering — only when skill palette is active AND slash/mention palettes are not.
// Mutual exclusion: if (showSkillPalette && !showSlashPalette && !showMentionPalette)
if (showSkillPalette && !showSlashPalette && !showMentionPalette) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomStart
    ) {
        Popup(
            alignment = Alignment.BottomStart,
            offset = IntOffset(0, -4),
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            onDismissRequest = { showSkillPalette = false },
        ) {
            SkillPalette(
                filtered = filteredSkills,
                selectedIndex = skillSelectedIndex,
                onSelectedIndexChange = { skillSelectedIndex = it },
                onSkillSelected = { skill ->
                    showSkillPalette = false
                    // Extract remaining text using QUERY LENGTH, not full skill name (M3).
                    // The user typed "$git-rel" and selected "git-release" — we skip
                    // the "$" prefix + the typed query (skillQuery.length + 1 chars),
                    // NOT the full skill name (which may be longer than what was typed).
                    val remainingText = if (currentText.length > skillQuery.length + 1) {
                        currentText.substring(skillQuery.length + 1).trim()
                    } else ""

                    // Q5: Log a warning if the skill is large (>8KB / ~2000 tokens)
                    if (skill.content.length > 8192) {
                        logger.warn {
                            "[ACP] Skill '${skill.name}' is large (${skill.content.length} chars) — consider trimming"
                        }
                    }

                    // H2: Inject into the text field for user review, NOT immediate send.
                    // The user can edit the injected content and press Enter to send.
                    // This matches the slash-command pattern (inject → review → send).
                    val injectedContent = buildString {
                        appendLine("<skill_content name=\"${skill.name}\">")
                        appendLine(skill.content.trim())
                        appendLine("</skill_content>")
                        if (remainingText.isNotEmpty()) {
                            appendLine()
                            appendLine(remainingText)
                        }
                    }
                    // Replace the entire input with the injected content.
                    // textState.edit replaces the full text (M15: use textState.length, not bare `length`).
                    textState.edit { replace(0, textState.text.length, injectedContent) }
                    // Do NOT call onSkillSelected here — the user reviews and presses
                    // Enter to send. The normal send path handles it.
                },
                onDismiss = { showSkillPalette = false },
            )
        }
    }
}

// $$ escape handling at SEND TIME (H4): when the user presses Enter to send,
// strip the leading $$ → $ (mirrors the // → / strip for slash commands).
// This is handled in InputKeyboardHandler's Enter branch — see §4.7.2.F.
```

**Key behavior changes from the original blueprint:**
- **Inject-into-field, not immediate send** (H2): The injected content is placed in
  the text field for the user to review/edit. The user presses Enter to send. This
  matches the slash-command pattern and gives the user a chance to see what was
  injected (important for large skills per Q5).
- **Query-length extraction** (M3): `remainingText` uses `skillQuery.length + 1`
  (the `$` prefix + typed query), not `substringAfter("$${skill.name}")` which breaks
  when the user types a partial skill name.
- **Literal `$` gate** (M7): `$50` and `$PATH` (where P is a letter) — `$50` does NOT
  trigger the palette (digit after `$`), but `$PATH` DOES (letter after `$`, could be
  a skill name). The gate checks the second character.
- **Mutual exclusion** (M2/M4): `showSkillPalette` is false when slash or mention
  palette is visible. Only one palette at a time.
- **Size warning** (Q5): Logs a warning if injected content > 8KB.
- **`$$` send-time strip** (H4): Handled in the keyboard handler's Enter branch, not
  here. See §4.7.2.F.

**F. `InputKeyboardHandler.kt` — new actions (Feature 2)**

```kotlin
// New sealed interface actions (alongside existing slash actions)
data class SelectSkillIndex(val index: Int) : InputKeyboardAction
object ExecuteSkillCommand : InputKeyboardAction
object DismissSkillPalette : InputKeyboardAction

// New state fields in InputKeyboardState
data class InputKeyboardState(
    // ... existing fields ...
    val showSkillPalette: Boolean = false,
    val filteredSkillSize: Int = 0,
    val skillSelectedIndex: Int = 0,
)

// ════════════════════════════════════════════════════════════════════════
// BRANCH ORDER (MUST NOT change — extends existing order with skill palette)
// ════════════════════════════════════════════════════════════════════════
// The existing InputKeyboardHandler has a documented 10-priority branch order.
// Skill palette cases are inserted BEFORE slash palette cases (same priority
// tier — the visibility flag disambiguates which palette is active).
//
// Updated branch order (skill palette cases marked with ★):
//
//  0. ★ Skill palette open + Up/Down → SelectSkillIndex (navigate skill list)
//  1.   Slash palette open + Up/Down → SelectSlashCommandIndex (existing)
//  2.   Mention palette open + Up/Down → SelectMentionIndex (existing)
//  3.   History navigation (Up/Down when no palette open) (existing)
//  4. ★ Skill palette open + Enter → ExecuteSkillCommand (inject + dismiss)
//  5.   Slash palette open + Enter → ExecuteSlashCommand (existing)
//  6.   $$ escape check (BEFORE slash // check) → strip $$ → $, then send (H4)
//  7.   // escape check → strip // → /, then send (existing)
//  8.   Plain Enter (no palette, no escape) → send message (existing)
//  9. ★ Escape cascade (updated order):
//       a. ★ Skill palette open → DismissSkillPalette (close skill palette)
//       b.   Slash palette open → DismissSlashPalette (existing)
//       c.   Mention palette open → DismissMentionPalette (existing)
//       d.   History navigation active → exit history (existing)
//       e.   Streaming active → cancel (existing)
//
// The skillPaletteVisible guard (analogous to slashPaletteVisible):
//   val skillPaletteVisible = state.showSkillPalette && state.filteredSkillSize > 0
//
// This guard is checked at the TOP of each key handler, before slash palette
// checks. Since mutual exclusion (§4.7.2.E) ensures only one palette is visible
// at a time, the skill palette cases only fire when the slash palette is NOT
// visible — no priority conflict.

// $$ send-time stripping (H4):
// In the Enter branch, BEFORE the // escape check, add:
//   if (text.startsWith("$$")) {
//       text = text.substring(1)  // strip one $, leaving literal $...
//       // ... then proceed to send (the $ is now literal, no palette trigger)
//   }
// This mirrors the existing // → / strip at InputKeyboardHandler.kt:208.
// $$ is the escape for literal $; // is the escape for literal /.
```

**Key additions:**
- **Explicit branch order** (C4): Skill palette cases inserted before slash palette
  cases at the same priority tier. Visibility flag disambiguates.
- **`$$` send-time strip** (H4): `$$foo` → `$foo` at send time, mirroring `//foo` →
  `/foo`. This is the missing half of the escape mechanism.
- **Escape cascade** (C4): Skill palette dismissal is the FIRST Escape action (before
  slash palette, mention palette, history, cancel).
- **`skillPaletteVisible` guard** (C4): `state.showSkillPalette && state.filteredSkillSize > 0`,
  checked before slash palette guards.

**G. `SkillManager` + `ChatViewModel.kt` — new skill state (Feature 2)**

**`SkillManager` (new class — mirrors `CommandManager` pattern, H3):**

```kotlin
package com.opencode.acp.chat.service

/**
 * Manages skill fetching from the OpenCode server, with staleness-based
 * re-fetch (per §10 Q6). Mirrors the CommandManager pattern: the manager
 * owns the fetch logic + error handling + staleness tracking; the ViewModel
 * delegates to it.
 */
class SkillManager(
    private val clientProvider: () -> OpenCodeClient?,
    private val logger: KLogger,
) {
    private val _availableSkills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val availableSkills: StateFlow<List<SkillInfo>> = _availableSkills.asStateFlow()

    @Volatile
    private var lastSkillFetchTimeMs: Long = 0L

    /**
     * Fetch skills from GET /skill. Called on init, session switch, and
     * on $ palette trigger if stale (Q6).
     *
     * @param force If true, always fetch. If false, only fetch if stale
     *              (now - lastSkillFetchTimeMs > SKILL_STALENESS_MS).
     */
    suspend fun fetchAvailableSkills(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSkillFetchTimeMs < SKILL_STALENESS_MS) {
            return  // cached, not stale
        }
        val client = clientProvider() ?: return
        try {
            val skills = client.listSkills()
            _availableSkills.value = skills
            lastSkillFetchTimeMs = now
            logger.debug { "[ACP] Fetched ${skills.size} skills from server" }
        } catch (e: Exception) {
            // Q6 edge case: do NOT clear _availableSkills on fetch error.
            // Fall back to the cached list rather than showing empty.
            logger.warn(e) { "[ACP] Failed to fetch skills — using cached list" }
        }
    }

    companion object {
        /** Q6: staleness window for skill re-fetch (30 seconds). */
        const val SKILL_STALENESS_MS = 30_000L
    }
}
```

**`ChatViewModel.kt` — new skill state (delegates to SkillManager):**

```kotlin
// New StateFlow (alongside _availableCommands) — backed by SkillManager
val availableSkills: StateFlow<List<SkillInfo>> = skillManager.availableSkills

// Fetch skills from SkillManager — called on init (alongside fetchAvailableCommands)
// and on session switch.
fun fetchAvailableSkills(force: Boolean = false) {
    scope.launch {
        skillManager.fetchAvailableSkills(force)
    }
}

// Q6: Re-fetch on $ palette trigger if stale.
// Called from a LaunchedEffect in ChatScreen.kt that watches showSkillPalette.
// If the palette is about to show and the cache is stale, force a re-fetch.
fun onSkillPaletteTriggered() {
    fetchAvailableSkills(force = true)
}
```

> **Implementation note (added 2026-07-26):** The blueprint above calls
> `fetchAvailableSkills(force = true)` (the ChatViewModel method that updates
> `_availableSkills.value`). The actual implementation must assign the result
> of `service.fetchAvailableSkills()` to `_availableSkills.value` — calling
> `service.fetchAvailableSkills()` without assigning the result discards the
> re-fetched skills and leaves the UI showing stale data. The implementation in
> `ChatViewModel.onSkillPaletteTriggered()` does:
> ```kotlin
> fun onSkillPaletteTriggered() {
>     scope.launch {
>         _availableSkills.value = service.fetchAvailableSkills()
>     }
> }
> ```

**`OpenCodeServiceApi` placement (M6):** Add `fetchAvailableSkills()` to the
`OpenCodeSessionApi` interface alongside `fetchAvailableCommands()`:

```kotlin
// In OpenCodeServiceApi.kt — OpenCodeSessionApi interface
suspend fun fetchAvailableSkills(): List<SkillInfo>
```

**`OpenCodeService.fetchAvailableSkills()` — delegates to client:**

```kotlin
// In OpenCodeService.kt
override suspend fun fetchAvailableSkills(): List<SkillInfo> {
    return try {
        client.listSkills()
    } catch (e: Exception) {
        logger.warn(e) { "[ACP] Failed to fetch skills" }
        emptyList()
    }
}
```

**Init flow call site:** `fetchAvailableSkills()` is called in `ChatViewModel.initialize()`
right after `fetchAvailableCommands()` (same location, same pattern).

**H. `ChatScreen.kt` — wiring (Feature 2)**

```kotlin
// Collect skill state
val availableSkills by viewModel.availableSkills.collectAsState()

// Q6: Re-fetch skills when the palette is triggered, if stale.
// This LaunchedEffect watches the showSkillPalette state from InputArea.
// When the palette is about to show, force a re-fetch to catch newly
// installed skills. SkillManager.fetchAvailableSkills(force=true) checks
// staleness internally — if the cache is fresh (<30s), it's a no-op.
LaunchedEffect(showSkillPalette) {
    if (showSkillPalette) {
        viewModel.onSkillPaletteTriggered()
    }
}

// Pass to InputArea
// H2: No onSkillSelected callback needed — the skill content is injected
// into the text field by InputArea (§4.7.2.E). The user reviews and presses
// Enter to send via the normal send path. This matches the slash-command
// pattern (inject → review → send).
availableSkills = availableSkills,
```

**Key change (H2):** The original blueprint had `onSkillSelected = { skill -> viewModel.sendMessage(skill.content) }`
which sent the message immediately on palette selection. The new design injects the
content into the text field (§4.7.2.E) and lets the user press Enter to send. This:
- Gives the user a chance to review/edit the injected content (important for large
  skills per Q5)
- Avoids polluting command history with giant `<skill_content>` blobs (the normal
  send path records what the user actually sends, which may be edited)
- Matches the slash-command pattern where the command is injected and the user
  can edit before sending

#### 4.7.3 Function Signatures

**Feature 1 — Bridge integration points:**

**`ProcessManager.initialize()`** — after MCP config write (~line 124), before server launch.
Reuse the existing `configWriter` instance (M2 — do not create a new one):

```kotlin
// Bridge JetBrains AI Assistant skills to OpenCode.
// Always call writeSkillPaths (even if empty) to evict stale paths from
// old IDE versions (Q4). writeSkillPaths handles the empty case by
// preserving user paths and evicting plugin-managed paths.
val skillPaths = JetBrainsSkillBridge.detectSkillPaths()
configWriter.writeSkillPaths(skillPaths)
```

**`OpenCodeService.resetMcpOnServerRestart()`** — after existing `configWriter.write()` (~line 493).
Reuse the existing `configWriter`:

```kotlin
val skillPaths = JetBrainsSkillBridge.detectSkillPaths()
configWriter.writeSkillPaths(skillPaths)
```

**`OpenCodeService.reinitializeMcpFromSettings()`** — after existing `configWriter.write()` (~line 469).
Reuse the existing `configWriter`:

```kotlin
val skillPaths = JetBrainsSkillBridge.detectSkillPaths()
configWriter.writeSkillPaths(skillPaths)
```

**Note:** The bridge runs regardless of MCP enable/disable state. Skill bridging is
independent of MCP configuration — a user may have MCP disabled but still want
JetBrains skills bridged. The `writeSkillPaths()` call is outside the
`if (settings.enableIntellijMcp || ...)` guard.

**Feature 2 — Skill fetch and invocation flow:**

```kotlin
// ChatViewModel.fetchAvailableSkills() — called on init and session switch
fun fetchAvailableSkills() {
    scope.launch {
        _availableSkills.value = service.fetchAvailableSkills()
    }
}

// OpenCodeService.fetchAvailableSkills() — delegates to client
suspend fun fetchAvailableSkills(): List<SkillInfo> {
    return try {
        client.listSkills()
    } catch (e: Exception) {
        logger.warn(e) { "[ACP] Failed to fetch skills" }
        emptyList()
    }
}

// OpenCodeClient.listSkills() — HTTP GET /skill
suspend fun listSkills(): List<SkillInfo> = getJson("/skill")
```

**Message injection on skill selection:**

When the user types `$git-release create a release for v1.2` and selects the `git-release` skill:
1. InputArea extracts the skill name (`git-release`) and remaining text (`create a release for v1.2`)
2. InputArea builds the injected message: `<skill_content name="git-release">[full SKILL.md body]</skill_content>\n\ncreate a release for v1.2`
3. The input text is cleared and the injected content is sent as the message
4. The agent receives the skill instructions + the user's request in one message

#### 4.7.5 Enums, Constants & Configuration

| Constant | Value | Location | Rationale |
|---|---|---|---|
| `AI_ASSISTANT_PLUGIN_ID` | `"com.intellij.ai.assistant"` | `JetBrainsSkillBridge` | JetBrains AI Assistant plugin ID for presence detection |
| Skill subdirectory | `"aia/agents/.agents/skills"` | `JetBrainsSkillBridge` | Hardcoded by AI Assistant plugin — confirmed in official docs, does not vary by version |
| Skill invocation prefix | `"$"` | `InputArea.kt` | JetBrains convention for manual skill invocation |
| Skill escape prefix | `"$$"` | `InputArea.kt` | Escape sequence to type a literal `$` (mirrors `//` escape for `/`) |
| Skill content delimiter | `<skill_content name="...">...</skill_content>` | `InputArea.kt` | XML-style tags wrapping skill content in the message — clear for the agent, doesn't conflict with markdown |
| `SKILL_STALENESS_MS` | `30_000L` (30 seconds) | `SkillManager` | Q6: staleness window for skill re-fetch on `$` palette trigger |
| `SKILL_SIZE_WARN_THRESHOLD` | `8192` (8KB) | `InputArea.kt` | Q5: threshold for logging a warning about large skill content |

No new settings fields. No user-facing configuration. Both features are always active when their preconditions are met (bridge: skill directory exists; palette: skills are discovered).

---

## 5. Assumptions & Dependencies

**Assumptions:**

- The OpenCode binary pinned by the user's `binaryPath` setting supports the `skills.paths` config key. The schema at `https://opencode.ai/config.json` includes it, and the feature was merged into OpenCode's source (~January 2026). If the binary is older, the `skills` key is silently ignored — no error, no crash.
- The OpenCode binary exposes the `GET /skill` endpoint. This endpoint is registered in `packages/opencode/src/server/server.ts` and returns `[{ name, description, location, content }]`. If the binary is older and doesn't have this endpoint, `listSkills()` will get an HTTP 404 → `fetchAvailableSkills()` catches the error and returns an empty list → the `$` palette shows "No matching skills" → no crash.
- The `aia/agents/.agents/skills/` subdirectory path is stable across AI Assistant plugin versions. JetBrains' official documentation confirms this path for 2026.2.
- `PathManager.getSystemPath()` returns the correct base directory on all platforms (Windows, macOS, Linux). This is a core IntelliJ Platform API.
- `PluginManager.isPluginInstalled()` is safe to call at any time — it does not require the AI Assistant plugin to be loaded, just installed.
- The `content` field from `GET /skill` contains the SKILL.md body without YAML frontmatter. This is what gets injected into the user's message.
- **Verify before implementation:** The `GET /skill` response format (`{ name, description, location, content }`) should be confirmed against the actual OpenCode binary by hitting `http://127.0.0.1:4096/skill` and inspecting the response. If `content` includes YAML frontmatter, the injection will contain raw `---` blocks — add a frontmatter-stripping step. If fields are null/missing, `SkillInfo` defaults handle it (empty strings).
- **Verify before implementation:** The `AI_ASSISTANT_PLUGIN_ID` (`"com.intellij.ai.assistant"`) should be confirmed by inspecting the actual installed plugin's ID (Settings → Plugins → AI Assistant → details). If the ID differs, the bridge silently fails for the IDE path.
- **Verify before implementation:** The `~/.codex/skills/` path as the "Codex (Global)" install scope should be confirmed by installing a skill at that scope via the Skills Manager and checking where the files land.

**Dependencies:**

- JetBrains AI Assistant plugin (`com.intellij.ai.assistant`) — optional runtime dependency for Feature 1. The bridge checks for its presence and silently skips if not installed. No compile-time dependency.
- `McpConfigWriter` — existing class, already used for MCP config writing (Feature 1).
- `PathManager` — IntelliJ Platform core API (Feature 1).
- `GET /skill` OpenCode server endpoint — existing endpoint, not new (Feature 2).
- `SlashCommandPalette.kt` — existing composable, used as the structural template for `SkillPalette.kt` (Feature 2).
- `InputKeyboardHandler.kt` — existing keyboard dispatch infrastructure, extended with skill palette actions (Feature 2).

---

## 6. Alternatives Considered

**Feature 1 — Bridge:**

**Alternative: Symlink the IDE skill directory into `.opencode/skills/`**
*What it is:* Create a symlink from `<project>/.opencode/skills/jetbrains-bridge` → `{system}/aia/agents/.agents/skills/` so OpenCode's default scan picks it up.
*Why plausible:* No config writing needed — OpenCode already scans `.opencode/skills/`.
*Why rejected:* Symlinks on Windows require elevated privileges or developer mode. Symlinks break when the IDE version changes (the path includes the version number). Symlinks are per-project, not global. The `skills.paths` approach is cross-platform, version-aware (detected at runtime), and works at the config level.

**Alternative: Copy skill files from IDE storage to `.opencode/skills/`**
*What it is:* On server launch, copy all SKILL.md folders from the IDE directory to `<project>/.opencode/skills/`.
*Why plausible:* Guaranteed format compatibility — files are literally copied.
*Why rejected:* Duplicates files (wastes disk, creates stale copies when skills are updated/uninstalled). Requires cleanup logic. Race conditions if the user installs/uninstalls skills while the server is running. The `skills.paths` approach is zero-copy — OpenCode reads the original files directly.

**Feature 2 — `$` Invocation:**

**Alternative: Tell the agent to call the `skill` tool**
*What it is:* When the user selects a skill from the `$` palette, send a message like "Please load the 'git-release' skill using the skill tool, then: create a release for v1.2".
*Why plausible:* Uses OpenCode's native skill tool — no content injection needed. The agent calls `skill({ name: "git-release" })` and gets the content.
*Why rejected:* Depends on the agent deciding to call the `skill` tool. The agent might ignore the instruction, misinterpret it, or call a different skill. Content injection is deterministic — the agent receives the instructions directly with no decision point. This is especially important for skills that the agent wouldn't auto-discover based on the user's message text alone.

*Context-window tradeoff (noted by council review):* Injecting the full skill content into the user message means it counts against user-message tokens in the context window. For a 10KB skill, this is ~2500 tokens every invocation. The `skill` tool, by contrast, is called conditionally by the model — the content only enters the context if the model decides to call it. Content injection is deterministic but token-expensive; the `skill` tool is token-efficient but non-deterministic. The Q5 size warning (§10) mitigates the token cost by making it visible. See §10 Q5 for the full discussion.

**Alternative: Filesystem scan instead of `GET /skill`**
*What it is:* Scan the same directories OpenCode scans (`.opencode/skills/`, `.agents/skills/`, `.claude/skills/`, custom paths) and parse YAML frontmatter from each SKILL.md.
*Why plausible:* No dependency on the OpenCode server being running. Could work before server launch.
*Why rejected:* Duplicates OpenCode's discovery logic in the plugin. Won't see skills from `skills.urls` (remote skills). Won't see skills from custom `skills.paths` that the plugin doesn't know about. The `GET /skill` endpoint returns exactly what OpenCode sees — single source of truth. The palette is only shown when the server is running anyway (user is in a chat session), so the server is always available.

---

## 7. Cross-Cutting Concerns

### 7.1 Security

**Feature 1 (Bridge):** The bridge writes a filesystem path to a config file. The path comes from `PathManager.getSystemPath()` — a trusted IntelliJ Platform API. No user input is involved. The path is not user-configurable, so there is no path traversal risk. The `McpConfigWriter.writeConfig()` method already uses file-level locking (`ReentrantLock`) to prevent concurrent write races. The atomic write pattern (temp file + rename) prevents partial writes.

**Feature 2 (`$` Invocation):** Skill content from `GET /skill` is injected into the user's message text. The content comes from SKILL.md files on the local filesystem (discovered by OpenCode) — not from untrusted remote sources. However, if a user installs a malicious skill from an external registry, its content would be injected. This is a similar risk profile to the agent calling the `skill` tool natively, with one nuance: content injection places the skill body as **user-message text** (the highest trust level in the agent's context), whereas the `skill` tool returns content as **tool output** (a lower trust level). A malicious skill injected via `$` is treated as direct user instruction, which is marginally higher risk. However, the user explicitly selected the skill from the palette — this is an opt-in action, not an automatic injection. The risk is acceptable for v1.

The `$$` escape sequence ensures users can type a literal `$` without triggering the palette.

### 7.2 Reliability & Availability

Graceful degradation at every level:

| Failure Condition | Feature | Behavior |
|---|---|---|
| AI Assistant plugin not installed | Bridge | `detectSkillPaths()` returns empty list → no config write |
| Skill directory doesn't exist | Bridge | `detectSkillPaths()` returns empty list → no config write |
| OpenCode binary doesn't support `skills.paths` | Bridge | `skills` key silently ignored by server → no error |
| `opencode.json` write fails | Bridge | `writeSkillPaths()` returns false, logged as warning → server still launches |
| `GET /skill` returns 404 (old binary) | `$` Palette | `fetchAvailableSkills()` catches error, returns empty list → palette shows "No matching skills" |
| `GET /skill` returns empty array | `$` Palette | Palette shows "No matching skills" → user types `$`, sees empty list, dismisses |
| No skills installed | `$` Palette | Same as above — empty palette, no crash |
| Server not running | `$` Palette | Input area is disabled when disconnected (per `ConnectionBanner` / `inputEnabled` check), so the user cannot type `$` to trigger the palette. This failure mode is unreachable in practice. |

No new failure modes are introduced. Neither feature can block server launch or cause crashes.

### 7.3 Performance

**Feature 1 (Bridge):** `detectSkillPaths()` does two `Files.isDirectory()` checks
(IDE path + Codex path) — sub-millisecond on any modern filesystem. `writeSkillPaths()`
does a single read-modify-write of `opencode.json` under file-level lock — same cost
as the existing `write()` call. No performance concern.

**Feature 2 (`$` Palette):** `GET /skill` is a single HTTP call returning a JSON array.
For typical skill counts (10-50 skills), parsing is sub-millisecond. The staleness
window (Q6: 30s) ensures we don't re-fetch on every keystroke. The palette filtering
(`startsWith` on a list of 10-50 items) is negligible. The size indicator (Q5) is
computed from `content.length` — O(1). No performance concern.

**Memory:** `SkillInfo` objects are held in a `StateFlow<List<SkillInfo>>`. Each object
includes the full `content` string (SKILL.md body). For 50 skills averaging 4KB each,
that's ~200KB — negligible. The list is replaced (not appended) on each fetch, so
old objects are GC'd.

### 7.4 Observability

All operations log via the existing `logger.info {}` / `logger.warn {}` pattern with `[ACP]` prefix:

- `[ACP] McpConfigWriter: wrote skill paths: [...]` — on successful bridge write
- `[ACP] McpConfigWriter: failed to write config` — on bridge write failure (existing error path)
- `[ACP] Failed to fetch skills` — on `GET /skill` failure (Feature 2)
- `[ACP] Fetched N skills from server` — on successful skill fetch (Feature 2, debug level)
- No logging when AI Assistant is not installed (silent skip — not worth log noise)

---

## 8. Testing Strategy

### 8.2 Key Scenarios

| # | Scenario | Feature | Test Type | Verification |
|---|---|---|---|---|
| 1 | `writeSkillPaths()` writes `skills.paths` to empty config | Bridge | Unit | Config file contains `skills.paths` with the given paths |
| 2 | `writeSkillPaths()` merges with existing `skills.paths` | Bridge | Unit | New paths appended, existing paths preserved, no duplicates |
| 3 | `writeSkillPaths()` preserves `skills.urls` | Bridge | Unit | `urls` array unchanged when `paths` is written |
| 4 | `writeSkillPaths()` preserves `mcp` and `agent` sections | Bridge | Unit | Other config keys untouched |
| 5 | `writeSkillPaths()` with empty list preserves user `skills.urls` and evicts plugin-managed paths | Bridge | Unit | `skills.urls` preserved, plugin-managed paths gone, user paths preserved |
| 6 | `writeSkillPaths()` deduplicates identical paths | Bridge | Unit | No duplicate entries in `paths` array |
| 6a | `writeSkillPaths()` evicts stale IDE-version paths | Bridge | Unit | Old `2026.1` path gone, new `2026.2` path written, user paths untouched |
| 6b | `writeSkillPaths()` evicts all plugin-managed paths when detectSkillPaths returns empty | Bridge | Unit | All `aia/agents/.agents/skills` and `.codex/skills` paths gone, user paths preserved |
| 6c | `writeSkillPaths()` preserves `skills.urls` when paths is empty | Bridge | Unit | `urls` array unchanged, `paths` omitted or empty |
| 6d | `writeSkillPaths()` handles Windows backslash paths in pattern matching | Bridge | Unit | `C:\\Users\\...\\aia\\agents\\.agents\\skills` correctly classified as plugin-managed |
| 7 | `JetBrainsSkillBridge.detectSkillPaths()` returns path when directory exists | Bridge | Unit | Returns list with the expected path string |
| 8 | `JetBrainsSkillBridge.detectSkillPaths()` returns empty when directory doesn't exist | Bridge | Unit | Returns empty list |
| 9 | `JetBrainsSkillBridge.detectSkillPaths()` returns empty when AI Assistant not installed | Bridge | Unit | Returns empty list (mock `PluginManager`) |
| 10 | Full integration: `ProcessManager.initialize()` writes skill paths before launch | Bridge | Integration | `opencode.json` contains `skills.paths` after initialization |
| 11 | `OpenCodeClient.listSkills()` parses `GET /skill` response | `$` Palette | Unit | Returns list of `SkillInfo` with name, description, content |
| 12 | `ChatViewModel.fetchAvailableSkills()` populates `availableSkills` StateFlow | `$` Palette | Unit | StateFlow contains skills from `listSkills()` |
| 13 | `fetchAvailableSkills()` handles 404 gracefully | `$` Palette | Unit | Returns empty list, no exception thrown |
| 14 | `fetchAvailableSkills()` handles empty response gracefully | `$` Palette | Unit | Returns empty list |
| 15 | Skill palette filters by name as user types | `$` Palette | Unit | `filteredSkills` contains only skills whose name starts with the query |
| 16 | Skill palette shows "No matching skills" when filter matches nothing | `$` Palette | Unit | Empty state rendered |
| 17 | Skill selection injects content into message | `$` Palette | Unit | Message text contains `<skill_content>` tags + user's remaining text |
| 18 | `$$` escape does not trigger palette | `$` Palette | Unit | `showSkillPalette` is false when text starts with `$$` |
| 18a | `$$/` does not trigger skill palette (escape suppresses) | `$` Palette | Unit | `showSkillPalette` is false when text starts with `$$` — palette never opens. `$$/` sends as literal `$/` after send-time strip. |
| 19 | `$` with newline does not trigger palette | `$` Palette | Unit | `showSkillPalette` is false when text contains `\n` |
| 20 | Skill palette keyboard: Up/Down navigates, Enter selects, Escape dismisses | `$` Palette | Unit | Correct `InputKeyboardAction` dispatched for each key |

**Test approach:**
- `McpConfigWriter` tests: temp directory + real file I/O. **Note:** there is no
  existing `McpConfigWriterTest` in the codebase (M10) — use the temp-directory
  pattern from `ServerVerifierTest` or `ToolDiscovererTest` as the template.
  Test the eviction logic (Q4) by pre-populating `opencode.json` with stale
  plugin-managed paths + user paths, calling `writeSkillPaths(newPaths)`, and
  verifying stale paths are evicted while user paths are preserved.
- `JetBrainsSkillBridge` tests: call `detectSkillPathsPure(basePath, isPluginInstalled, userHome)`
  directly with temp directories — no mocking of `PluginManager` or `PathManager`
  required (M8). Test `isPluginManagedPath()` with both forward-slash and backslash
  variants (Windows path matching, Q4).
- `OpenCodeClient.listSkills()` tests: mock HTTP response (matching existing
  `OpenCodeClientTest` patterns).
- `ChatViewModel` / `SkillManager` tests: **per AGENTS.md "MockK SharedFlow/StateFlow —
  Must Stub with Real Flows"** (M9), stub `service.signals` / `service.globalSignals`
  with real `MutableSharedFlow(extraBufferCapacity = 256)` and `service.connectionState`
  with a real `MutableStateFlow(...)`. Cancel all scopes in `@AfterEach`. Mock
  `skillManager.fetchAvailableSkills()`, verify `availableSkills` StateFlow.
- Palette filtering/escape tests: pure logic — test the query extraction and filtering
  functions directly. Include `$$/` escape test (palette suppressed, sends literal `$/`).
- Keyboard handler tests: verify correct `InputKeyboardAction` dispatched for each key
  combination (matching existing `InputKeyboardHandlerTest` patterns). Verify `$$`
  send-time strip in the Enter branch.

**Note on Compose UI tests:** Per AGENTS.md "Compose UI Tests — ComposePanel Cannot Render in Plain Unit Tests", the `SkillPalette` composable itself cannot be rendered in unit tests. Test the filtering logic and message injection as pure functions. The composable structure mirrors `SlashCommandPalette.kt` which is already tested via its logic, not via rendering.

---

## 10. Open Questions

### 10.1 Resolved Questions

1. **Should the bridge also write project-level skill paths?**
   **Resolution: No action.** OpenCode already scans `<project>/.agents/skills/`, `<project>/.claude/skills/`, and `.opencode/skills/` by default — project-scoped skills are visible. Bridging them would create duplicate entries and risk write conflicts with user-managed paths. Leave project-level to OpenCode's native discovery. Confirmed by OpenCode source code.

2. **Should the bridge handle the `~/.codex/skills/` and `~/.claude/skills/` global paths?**
   **Resolution: Yes — add `~/.codex/skills/` to `detectSkillPaths()` with an existence check.** OpenCode scans `~/.claude/skills/` and `~/.agents/skills/` by default but does NOT scan `~/.codex/skills/`. A skill installed at "Codex (Global)" scope via the Skills Manager goes to `~/.codex/skills/` — invisible to OpenCode without bridging.

   **Caveat:** Only add the path if the directory exists (same existence check as the IDE path). Don't add it unconditionally — writing a non-existent path to `skills.paths` is harmless but noisy in the config file. The existence check keeps `opencode.json` clean when the user hasn't installed any Codex-scoped skills.

   **No plugin gating:** Unlike the IDE path (gated on AI Assistant plugin presence via `PluginManager.isPluginInstalled`), `~/.codex/skills/` is a user-managed directory with no associated plugin to detect. Existence check is sufficient.

3. **Binary version verification:** Should the bridge check `GET /global/health` for the OpenCode version and skip writing `skills.paths` if the version is too old?
   **Resolution: No.** Three reasons:
   1. OpenCode's documented behavior is to silently ignore unknown config keys — no error, no crash.
   2. A version check adds a network round-trip (`GET /global/health`) before config write, introducing a new failure mode (health check timeout) for zero user-visible benefit.
   3. The bridge already runs *after* `ProcessManager.initialize()` waits for health check in the normal flow — by the time `writeSkillPaths()` is called, the server is known healthy. Adding a second version probe is redundant.

   The only scenario where this would matter: an old binary that *errors* on unknown keys. OpenCode does not do this. Skip the check.

4. **Stale path cleanup:** If the user uninstalls the AI Assistant plugin or the IDE version changes (e.g., upgrade from 2026.1 to 2026.2), the old skill path in `opencode.json` becomes stale. Should the bridge clean up stale paths on each write?
   **Resolution: Yes — overwrite the plugin-managed subset, do not merge.** Plugin-managed paths are *fully determined* at runtime by `detectSkillPaths()`. There is no reason to preserve previously-written plugin paths across runs — they are recomputed every time.

   **Approach: Path-pattern matching (not sentinel comments).** JSON does not support comments, and injecting a `"_managed_by": "sigil"` marker pollutes the config schema. Instead, match on known path shapes:

   - Any path matching `*/aia/agents/.agents/skills*` is plugin-managed (IDE scope).
   - Any path matching `~/.codex/skills*` (or its resolved form) is plugin-managed (Codex Global scope).
   - All other paths are user-added and preserved.

   Final `skills.paths` = `userPaths (non-matching, preserved) + detectedPaths (matching, deduplicated)`.

   **Handles:**
   - IDE version upgrade (2026.1 → 2026.2): old path matches the pattern → evicted, new path written.
   - Plugin uninstall: `detectSkillPaths()` returns empty → all matching paths evicted, user paths preserved.
   - User-added custom path: doesn't match pattern → preserved across writes.

   **Test coverage:** Expand test #4 in §8.2 to cover the eviction scenario: old version path present in config → `writeSkillPaths(newPaths)` called → old path gone, new path written, user paths untouched.

5. **Skill content size:** Some SKILL.md files may be large (thousands of lines). Injecting the full content into the user's message could consume significant context window. Should there be a size limit?
   **Resolution: No hard limit for v1, but add a soft warning and a palette size indicator.** Agree that `agentskills.io` skills are designed to be concise, and a hard limit would break legitimate large skills (e.g., a comprehensive codebase onboarding skill).

   **Two cheap safeguards:**
   1. **Log a warning** if injected skill content exceeds a threshold (e.g., 8KB / ~2000 tokens). This gives observability into "which skills are eating context" without blocking the user. Use `logger.warn { "[ACP] Skill '$name' is large (${content.length} chars) — consider trimming" }`.
   2. **Show the skill size in the palette** — next to the skill name, show a small `~N lines` or `~Nk` indicator. This lets the user make an informed choice before injecting. Cheap to compute from `content.length`.

   **Do NOT implement the "summary + call skill tool" fallback now** — it reintroduces the non-determinism that content injection was designed to eliminate (the agent might not call the `skill` tool). Revisit only if telemetry shows users routinely injecting >10KB skills.

6. **`$` palette fetch timing:** `fetchAvailableSkills()` is called on init and session switch. If the user installs a skill via the Skills Manager while a session is active, the palette won't show it until the next session switch or server restart. Should we re-fetch on palette open (when user types `$`)?
   **Resolution: Yes, but debounce by session activity (staleness window), not by keystroke.**

   - **Re-fetch on `$` trigger only if stale** — track `lastSkillFetchTimeMs`. On `$` trigger, if `now - lastFetch > 30s`, re-fetch. Otherwise use the cached list. This avoids a network call on every keystroke while still catching recently-installed skills.
   - **Subscribe to skill SSE events if OpenCode emits them.** Check the OpenAPI spec for `skill.installed` / `skill.removed` events. If they exist, subscribe and invalidate the cache on receipt — this gives instant updates without any polling. If they don't exist, the 30s staleness check on `$` trigger is the fallback.

   **Do NOT re-fetch on every keystroke** — even debounced, it is wasteful. The 30s staleness window is a better tradeoff: cheap, responsive enough for "I just installed a skill" workflows, and doesn't add latency to palette rendering.

   **Edge case:** If the re-fetch fails (network blip), fall back to the cached list rather than showing empty. Log the failure. Do NOT clear `_availableSkills` on fetch error.

7. **Skill palette vs. slash command palette interaction:** What happens if the user types `/$`? The `/` triggers the slash palette, and `$` is just a filter character. What about `$/`? The `$` triggers the skill palette, and `/` is just a filter character.
   **Resolution: First character wins — no special handling needed.** The existing `startsWith("/")` vs `startsWith("$")` check naturally disambiguates:
   - `/$` → slash palette filtered by `$`.
   - `$/` → skill palette filtered by `/`.

   **Documentation:** Add a one-line KDoc comment to both `SkillPalette.kt` and `SlashCommandPalette.kt` so future maintainers don't "fix" the perceived ambiguity by adding conflicting special-case logic:
   ```kotlin
   // First non-escape char determines palette: '/' → slash, '$' → skill. See TDD §10 Q7.
   ```

   **Edge case (add a test):** `$$/` — the `$$` escape suppresses the skill palette
   entirely (the gate `!text.startsWith("$$")` is true, so `showSkillPalette` is false).
   The slash palette is also not triggered (text starts with `$`, not `/`). So `$$/`
   shows **neither** palette while typing. At send time, the `$$` → `$` strip (§4.7.2.F)
   converts it to `$/`, which is sent as literal text. Add a test case (test #18a in
   §8.2) to lock this behavior. (Test #18 covers `$$` escape but not the `$$/`
   interaction.)

---

## 13. Document History

| Date | Author | Changes |
|---|---|---|
| 2026-07-23 | — | Initial draft (Feature 1: Skill Bridge) |
| 2026-07-23 | — | Added Feature 2: `$` Skill Invocation palette |
| 2026-07-25 | — | Council review fixes: reconciled §4.7 blueprint with §10 resolutions (Q2 codex path, Q4 eviction, Q6 staleness, Q7 $$ escape), added SkillManager, inject-into-field UX, mutual exclusion, size indicator, Windows path matching, testing fixes |
| 2026-07-26 | — | Adversarial review fixes: skill name escaping (prompt injection), onSkillPaletteTriggered stale-data bug, isPluginManagedPath endsWith (false-positive eviction), writeSkillPaths unknown-key preservation, added CancellationException/unknown-key/false-positive tests |