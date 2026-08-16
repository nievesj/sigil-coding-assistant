# Technical Design Document: Review — Staged Changes Only

> **Status:** Draft
> **Author(s):** AI Assistant
> **Last Updated:** 2026-08-15
> **Related docs:** `docs/tdd/Done/review-tab.md` (original Review tab TDD — explicitly deferred staged/unstaged separation to v2 in §2.2 and §3 Non-Goals), `AGENTS.md` (testing policy, logging convention)

---

## 1. TL;DR

The Review tab and the `/review-perform`, `/review-perform-gaming`, and `/review-recheck` slash commands currently review **all modified files** (default changelist + untracked). We are changing them to review **only git-staged changes** — files that have been `git add`ed (the index vs HEAD diff). This requires introducing a git-specific code path that runs `git diff --cached` via **git4idea's managed git execution** (`GitLineHandler` — the IDE's own git invocation, NOT a raw `ProcessBuilder` shell-out), gated to git repositories, and adding clear empty-state guidance ("stage your changes with `git add`") when nothing is staged. git4idea is declared as an **optional bundled-plugin dependency** (`<depends optional="true" config-file="plugin-git.xml">Git4Idea</depends>`, matching the existing MCP server pattern at `plugin.xml:77`) so the plugin remains compatible with all 10 targeted IDEs — some (e.g., DataGrip) may not bundle git4idea. When git4idea is absent, `getStagedFiles()` returns `NoGitRepository` gracefully. The original review-tab TDD explicitly listed "Staged vs unstaged grouping" as a deferred v2 feature — this TDD implements that deferred scope, narrowed to **staged-only** (no toggle).

---

## 2. Context & Scope

### 2.1 Current State

The Review feature was built per `docs/tdd/Done/review-tab.md` (shipped, status Done). File gathering is centralized in **`GitService.getChangedFiles()`** (`src/main/kotlin/com/opencode/acp/chat/service/GitService.kt:38-88`), which:

1. Reads `changeListManager.defaultChangeList.changes` — **all working-tree tracked changes** (staged + unstaged combined; IntelliJ's default changelist does not separate them). The `Change` objects' `beforeRevision`/`afterRevision` carry **working-tree vs HEAD** content, not **index vs HEAD** content.
2. Reads `changeListManager.unversionedFilesPaths` — **untracked files** (never `git add`ed).
3. Computes LCS line deltas on the working-tree content.
4. Filters out `.review/` internal files.

Four call sites consume `getChangedFiles()`:
- `ReviewPanel.kt:213` — the Review tab UI (inside `produceState`, debounced 300ms).
- `ReviewCommandHandler.executeReviewPerformCommand` (`src/main/kotlin/com/opencode/acp/chat/viewmodel/ReviewCommandHandler.kt:99`) — `/review-perform`.
- `ReviewCommandHandler.executeReviewPerformGamingCommand` (`src/main/kotlin/com/opencode/acp/chat/viewmodel/ReviewCommandHandler.kt:118`) — `/review-perform-gaming`.
- `ReviewCommandHandler.executeReviewRecheckCommand` (`src/main/kotlin/com/opencode/acp/chat/viewmodel/ReviewCommandHandler.kt:157`) — `/review-recheck`.

`/review-resolve` does **not** gather files (it reads `.review/` comment JSON via `ReviewCommentManager`) and is unaffected.

The commands pass only **file paths** to the LLM prompt (`ReviewSkill.buildPerformPrompt(changedPaths)`) — the LLM reads the files itself. So the change is about **which files get listed in the prompt**, not what diff content is sent. The Review tab UI additionally opens the IDE diff viewer via `openDiffForPath` (`ReviewPanel.kt:724`), which re-reads `defaultChangeList.changes` and shows working-tree vs HEAD content.

**Current empty-list behavior (deliberate improvement in this TDD):** Today, when `getChangedFiles()` returns an empty list, `ReviewSkill.buildPerformPrompt(emptyList())` returns the string "There are currently no uncommitted changes in the project." — and this string **IS sent to the LLM as a prompt** (a wasteful LLM round-trip that produces no useful review). The new design intercepts the empty case with `injectLocalMessage` (a local chat message, no LLM call) — a deliberate improvement that avoids the empty-review LLM round-trip entirely.

`GitService` is deliberately VCS-agnostic (class doc, `GitService.kt:17-18`: "Uses platform ChangeListManager — works with any VCS"). git4idea is **not** a declared dependency in `build.gradle.kts` or `plugin.xml` today; this TDD adds it as an **optional** dependency so the plugin remains compatible with IDEs that don't bundle git4idea (see §4.7.1).

### 2.2 Problem Statement

The user wants reviews to target only the changes they have explicitly staged with `git add` — the set of changes about to be committed — not every modified file in the working tree. Reviewing unstaged or untracked files produces noise (work-in-progress, scratch edits, untracked experiments) and reviews content that may never be committed. Restricting to staged changes makes the review match the user's intent: "review what I'm about to commit."

---

## 3. Goals & Non-Goals

### Goals

1. `/review-perform`, `/review-perform-gaming`, and `/review-recheck` list **only git-staged files** (the index vs HEAD diff) in their prompts.
2. The Review tab displays **only git-staged files** with line deltas computed from the staged (index vs HEAD) diff.
3. When nothing is staged, the Review tab shows a clear **empty-state message** directing the user to `git add`, and `/review-perform*` injects a **user-facing local message** (not an LLM prompt) telling the user to stage files first — never sends an empty review to the LLM.
4. For non-git projects (no git repo, or SVN/Hg/Perforce VCS roots), the Review tab shows a **"requires a Git repository"** message and `/review-perform*` injects a user-facing message — graceful, no crash.
5. The diff viewer opened from the Review tab shows the **staged vs HEAD** diff (not working-tree vs HEAD).
6. All VCS reads continue to run inside `runReadAction` on `Dispatchers.IO`; all UI mutations on EDT (existing threading contract preserved).
7. New unit tests cover the staged-filtering logic and empty/non-git states (AGENTS.md testing policy).

### Non-Goals

- **No staged/unstaged toggle.** The feature is staged-only. Users who want all-changes review can stage everything (`git add -A`). A toggle is a future enhancement, not in scope.
- **No partial-staging (hunk-level `git add -p`) awareness.** If a file has some staged hunks and some unstaged hunks, the whole file is listed as staged (IntelliJ's staged-changes API operates at file granularity for the change list, matching `git status --staged` behavior). Hunk-level filtering is out of scope.
- **No changes to `/review-resolve`.** It reads review comments, not files.
- **No multi-repo staging UI** (per-repo grouping, staging per repo). The flat list is preserved.
- **No migration of existing review sessions.** Reviews already written against all-modified-files remain valid; this only changes future review invocations.

---

## 4. Proposed Solution

**Introduce a git-specific staged-changes path in `GitService`, replacing the VCS-agnostic `getChangedFiles()` for the Review feature. Add git4idea as an **optional** bundled-plugin dependency so the git APIs are available at compile time and runtime where the IDE bundles them. The new `getStagedFiles()` method uses `GitRepositoryManager` to find git repos and runs `git diff --cached` via **git4idea's managed git execution** (`GitLineHandler` — the IDE's own git invocation, which respects the configured git binary path and read-action threading; this is NOT a raw `ProcessBuilder` shell-out). Line deltas come from the staged (index-vs-HEAD) diff content parsed from the `git diff --cached` output directly — not from `Change` revision properties. All four call sites switch to `getStagedFiles()`. Empty-staging and non-git states are surfaced as distinct `ReviewState` variants and as user-facing injected messages for the commands. The diff viewer is updated to open staged-vs-HEAD diffs. When git4idea is absent (IDE that doesn't bundle it), `getStagedFiles()` returns `NoGitRepository` gracefully.**

This is the right approach because the staged-changes concept is fundamentally git-specific (no `ChangeListManager` abstraction exposes it uniformly across VCS), so leaning on git4idea — the IDE's own git integration — is both correct and minimal. Using `git diff --cached` via git4idea's managed execution (rather than `ChangeListManager`'s staged-aware API) works **regardless of IntelliJ's "Enable staging area" setting** (which is OFF by default) — system git always knows what's staged. Adding git4idea as an optional dependency is safe: it ships with IntelliJ IDEA and most JetBrains IDEs, and the `<depends optional="true">` pattern (matching the existing MCP server plugin at `plugin.xml:77`) ensures the plugin still loads on IDEs that don't bundle it.

### 4.3 API / Interface Design

**Internal interface change — `GitService`:**

| Method | Current | Proposed |
|--------|---------|----------|
| `getChangedFiles(): List<ChangedFile>` | Returns all default-changelist + untracked files. | **Deprecated** (kept temporarily for any unexpected external callers, but Review feature stops using it). |
| `getStagedFiles(): StagedFilesResult` | — | **New.** Returns staged files for git repos via `git diff --cached` (git4idea managed execution); `NothingStaged` or `NoGitRepository` result types for the empty/non-git cases. When git4idea is absent (optional dependency not loaded), returns `NoGitRepository`. MUST be called inside `runReadAction` on `Dispatchers.IO` (same contract as `getChangedFiles`). |

The new return type is a sealed result (see 4.7.2) so callers can distinguish "staged files present" from "nothing staged" from "not a git repo" without sentinel values or exceptions.

**No HTTP/API changes.** The review commands still produce a text prompt consumed by the OpenCode LLM; only the file list inside the prompt changes.

### 4.5 Technology Stack

| Layer | Technology | Version | Rationale |
|-------|-----------|---------|-----------|
| Language | Kotlin | 2.3.0 | Existing project language |
| Git integration | git4idea (bundled IntelliJ plugin) | Bundled with IU 2026.1 | IDE's native git support; provides `GitLineHandler` for managed `git diff --cached` execution. **Optional** dependency — `<depends optional="true" config-file="plugin-git.xml">Git4Idea</depends>` — so the plugin loads on IDEs that don't bundle it (e.g., DataGrip) |
| VCS abstraction | IntelliJ `ChangeListManager` | Platform | Retained for `openDiffForPath`'s current working-tree diff (existing behavior); staged enumeration moves to git4idea managed git execution |

### 4.7 Implementation Blueprint

> ⚠️ Blueprint code is illustrative, not authoritative. Validate against the compiler and the actual git4idea API surface during implementation — the exact `GitLineHandler`/`GitHandler` class names must be confirmed against the pinned platform version (IU 2026.1). The plugin id for `<depends>` is **`Git4Idea`** (CamelCase) — verified from the git4idea `plugin.xml` inside the pinned platform (IU 2026.1, `plugins/vcs-git/lib/vcs-git.jar`). The Kotlin package is `git4idea` (lowercase) — so `<depends>` uses `Git4Idea`, imports use `git4idea.*`, and `build.gradle.kts` `bundledPlugin()` uses `"git4idea"` (lowercase Gradle module name).

#### 4.7.1 Data Models & Schemas

**New: `StagedFilesResult` sealed interface** — add to `src/main/kotlin/com/opencode/acp/chat/model/ReviewModels.kt` (where `ReviewState` and `ChangedFile` live — NOT `src/main/kotlin/com/opencode/acp/review/ReviewModels.kt`, which holds `ReviewComment`/`ReviewIndex`; the project has TWO files named `ReviewModels.kt`):

```kotlin
/** Result of enumerating git-staged files. Sealed so callers distinguish
 *  the "empty" and "non-git" cases from "files present" without sentinels. */
sealed interface StagedFilesResult {
    /** Staged files exist. [files] is non-empty. */
    data class Staged(val files: List<ChangedFile>) : StagedFilesResult
    /** Git repo(s) found, but nothing is staged (no `git add` yet). */
    data object NothingStaged : StagedFilesResult
    /** No git repository in the project, or git4idea plugin not loaded. */
    data object NoGitRepository : StagedFilesResult
}
```

**Existing models (unchanged):** `ChangedFile`, `FileChangeStatus`, `LineDelta` — reused as-is.

**`ReviewState` changes — REMOVE `Empty`, ADD two new variants:**

> **`NotAGitRepository` is a NEW capability, not a restoration.** The original review-tab TDD (`docs/tdd/Done/review-tab.md`) specified `ReviewState.NoGitRepository` in its design, but it was **never shipped** — today, non-git projects get `ReviewState.Empty` (the same "no changes" state as an empty git repo). The new `NotAGitRepository` is being introduced for the first time. `NothingStaged` is also new (replaces the overloaded `Empty` for the "git repo but nothing staged" case).

```kotlin
sealed interface ReviewState {
    data object Loading : ReviewState
    data class Loaded(...) : ReviewState
    // ReviewState.Empty is REMOVED — replaced by NothingStaged / NotAGitRepository.
    // The `is ReviewState.Empty -> ReviewEmptyContent(...)` branch in ReviewPanel
    // (ReviewPanel.kt:242) is deleted.
    /** Nothing staged in a git repo — prompt user to `git add`. */
    data object NothingStaged : ReviewState
    /** Project is not a git repository, or git4idea is not loaded — feature requires git. */
    data object NotAGitRepository : ReviewState
    data class Error(val message: String, val retryable: Boolean = true) : ReviewState
}
```

**Optional dependency in `plugin.xml`** (matches the existing MCP server pattern at `plugin.xml:77`):

```xml
<!-- Optional: git4idea for staged-changes review.
     When present, staged-changes review is enabled.
     When absent, getStagedFiles() returns NoGitRepository gracefully. -->
<depends config-file="plugin-git.xml" optional="true">Git4Idea</depends>
```

The `plugin-git.xml` config file declares the git4idea `<dependencies>` entry so it's only loaded when git4idea is present. `build.gradle.kts` adds `bundledPlugin("git4idea")` for compile-time API access (the plugin compiles against git4idea; at runtime the optional `<depends>` guard prevents `NoClassDefFoundError` on IDEs that don't bundle it).

#### 4.7.2 Class & Interface Definitions

**`GitService` — new method contract:**

```kotlin
class GitService(private val project: Project) {
    // Existing — now used only internally for line-delta computation helpers.
    // NOTE: No ReplaceWith — getStagedFiles() returns StagedFilesResult (sealed),
    // not List<ChangedFile>, so a ReplaceWith("getStagedFiles()") would not compile.
    @Deprecated("Use getStagedFiles() for the Review feature. Retained for compatibility.")
    fun getChangedFiles(): List<ChangedFile> { /* existing impl, unchanged */ }

    /** Returns git-staged files (index vs HEAD). MUST be called inside
     *  runReadAction on Dispatchers.IO.
     *
     *  - Git repo with staged files → [StagedFilesResult.Staged] (non-empty).
     *  - Git repo, nothing staged   → [StagedFilesResult.NothingStaged].
     *  - No git repo, or git4idea not loaded → [StagedFilesResult.NoGitRepository].
     *
     *  Implementation uses git4idea GitRepositoryManager to locate repos and
     *  runs `git diff --cached` via GitLineHandler (git4idea's managed git
     *  execution — the IDE's own git invocation, NOT a raw ProcessBuilder).
     *  Line deltas are computed by parsing the `git diff --cached` output
     *  directly (index-vs-HEAD content), not from Change revision properties.
     *  Guarded by git4idea presence check (optional dependency). */
    fun getStagedFiles(): StagedFilesResult { /* new impl */ }

    // ...existing private helpers (computeLineDeltaCached, computeLcsDiff,
    //     getRelativePath, mapFileStatus) reused unchanged...
    fun invalidateCache() { /* unchanged */ }
}
```

**`ReviewCommandHandler` — command changes:**

Each of the three file-gathering commands changes from:
```kotlin
val changedFiles = withContext(Dispatchers.IO) { runReadActionBlocking { gitService.getChangedFiles() } }
val changedPaths = changedFiles.map { it.filePath }
val prompt = ReviewSkill.buildPerformPrompt(changedPaths)
executeMultiModelReview(args, prompt)
```
to a result-handling branch:
```kotlin
val result = withContext(Dispatchers.IO) { runReadActionBlocking { gitService.getStagedFiles() } }
when (result) {
    is StagedFilesResult.Staged -> {
        val paths = result.files.map { it.filePath }
        executeMultiModelReview(args, ReviewSkill.buildPerformPrompt(paths))
    }
    StagedFilesResult.NothingStaged -> {
        injectLocalMessage("⚠️ No staged changes. Use `git add <file>` to stage files for review, then run /review-perform again.")
    }
    StagedFilesResult.NoGitRepository -> {
        injectLocalMessage("⚠️ Staged-changes review requires a Git repository. Initialize git (`git init`) or open a git project.")
    }
}
```

The same pattern applies to `executeReviewPerformGamingCommand` (gaming prompt). For `executeReviewRecheckCommand`, the `NothingStaged`/`NoGitRepository` branches must **`return` before the `try/finally` block** — since no LLM send occurs, there's no need for `refreshReviewFiles().join()` or the reply-restoration safety net (those only apply when the LLM actually rewrites `.review/` files). The `preRecheckIndex`/`replySnapshot` setup can stay before the `when` (it's cheap), or move into the `Staged` branch — implementer's choice. Only the `Staged` branch enters the `try { executeMultiModelReview(...) } finally { refreshReviewFiles().join(); restoreMissingReplies(...) }` block.

**`ReviewPanel` — UI state mapping:**

The `produceState` block (`ReviewPanel.kt:191-226`) changes from:
```kotlin
val files = gitService.getChangedFiles()
if (files.isEmpty()) ReviewState.Empty
else ReviewState.Loaded(files, counts, openComments)
```
to:
```kotlin
when (val result = gitService.getStagedFiles()) {
    is StagedFilesResult.Staged -> ReviewState.Loaded(result.files, counts, openComments)
    StagedFilesResult.NothingStaged -> ReviewState.NothingStaged
    StagedFilesResult.NoGitRepository -> ReviewState.NotAGitRepository
}
```
plus two new composable branches rendering the guidance messages. The existing `is ReviewState.Empty -> ReviewEmptyContent(...)` branch (`ReviewPanel.kt:242`) and the `ReviewEmptyContent` composable (`ReviewPanel.kt:665`) are **deleted** — `NothingStaged` and `NotAGitRepository` replace them with distinct guidance text.

#### 4.7.3 Function Signatures

**`GitService.getStagedFiles()`** — the core new function. Pseudocode:

```
fun getStagedFiles(): StagedFilesResult
  0. Guard: if git4idea plugin is not loaded (optional dependency absent),
     return NoGitRepository immediately — no git APIs available.
  1. Locate git repos via GitRepositoryManager.getRepositories(project)
     - getRepositories can return multiple repos (multi-root project, submodules).
     - Staged changes from ALL repos are merged into a flat list with
       project-root-relative paths (same getRelativePathFromRoot normalization
       as getChangedFiles uses today).
  2. If no repos found → return NoGitRepository
  3. For each repo, run `git diff --cached` via git4idea managed execution:
       - Use GitLineHandler (or GitHandler — confirm exact class against pinned
         platform, see OQ1) with the repo's git binary path.
       - This is git4idea's managed git invocation — the IDE's own git execution,
         NOT a raw ProcessBuilder shell-out. It respects the configured git binary
         and read-action threading.
       - Parse the unified diff output to extract:
         (a) file paths (staged set)
         (b) line-level additions/deletions (for LineDelta.Known)
         (c) file status (added/modified/deleted) from diff headers
  4. If no staged changes found across all repos → return NothingStaged
  5. Map each staged file → ChangedFile:
       - filePath via getRelativePathFromRoot (existing helper)
       - lineDelta from parsed diff hunks (additions/deletions counts)
       - status from diff header (new file = ADDED, deleted = DELETED, etc.)
       - virtualFile resolved from filePath for diff-viewer open
  6. Filter out .review/ internal files (existing filter)
  7. CACHE LIFECYCLE: getChangedFiles() cleans up lineDeltaCache by retaining
     only keys for current paths (GitService.kt:82-83). getStagedFiles() must
     ALSO maintain this cache — add a cleanup step that retains only keys for
     the staged file paths. Alternatively, use a separate cache instance
     (stagedLineDeltaCache) to avoid cross-contamination between staged and
     all-changes caches. Implementer's choice; the cleanup step is required
     either way to prevent unbounded cache growth.
  8. Return Staged(files)
```

**`openDiffForPath` change** (`ReviewPanel.kt:724`): the `Change` lookup currently searches `changeListManager.defaultChangeList.changes` (working-tree changes). It must instead search the **staged changes set from GitService**. Approach: `GitService.getStagedFiles()` returns (or caches) the staged `List<Change>` alongside the `List<ChangedFile>`; `openDiffForPath` receives the staged changes as a parameter and searches that set instead of `defaultChangeList.changes`. The `DiffContentFactory` content comes from the staged Change's revisions (index-vs-HEAD). This ensures the diff viewer opens the staged-vs-HEAD diff, consistent with what the Review tab lists.

> **Review comment line-number alignment risk:** Review comments store 1-based line numbers (`ReviewComment.startLine`/`endLine`) relative to the **file content** (working-tree), not relative to a diff. When the diff viewer switches from working-tree content to staged content, comment line numbers could appear misaligned if staged content has different line counts than working-tree content (e.g., the user staged an earlier version, then made further unstaged edits). **Mitigation:** comment line numbers are file-line-based (the line in the source file), and for the same file at the same commit, staged and working-tree line numbers are stable as long as the user hasn't made unstaged edits after staging. The `ReviewCommentDiffExtension` (which renders comment markers in the diff viewer) should use the **staged revision's** line numbers when rendering in the staged-vs-HEAD diff. If the user has post-staging unstaged edits, comments may show offset in the staged diff — this is an acceptable known limitation (the comment was written against the working-tree; the staged diff shows a different snapshot). Document this in the empty-state guidance if it causes confusion.

#### 4.7.4 Component Mapping

| Component | Responsibility | Data Model(s) | API Endpoint(s) | Key Class(es) / Function(s) |
|-----------|---------------|---------------|------------------|------------------------------|
| `GitService` | Enumerate git-staged files + line deltas | `StagedFilesResult`, `ChangedFile` | — | `getStagedFiles()` |
| `ReviewCommandHandler` | Run `/review-perform*`, `/review-recheck`; handle empty/non-git by injecting local messages | `StagedFilesResult` | — | `executeReviewPerformCommand`, `executeReviewPerformGamingCommand`, `executeReviewRecheckCommand` |
| `ReviewPanel` (UI) | Render staged file list + empty/non-git guidance; open staged diff viewer | `ReviewState` (+ new `NothingStaged`/`NotAGitRepository`) | — | `ReviewPanel`, `openDiffForPath` |
| `ReviewSkill` | Build review prompts from file paths | — | — | `buildPerformPrompt`, `buildPerformGamingPrompt`, `buildRecheckPrompt` (unchanged signatures) |

#### 4.7.5 Enums, Constants & Configuration

No new configuration values. The feature is unconditional (no settings toggle for staged-only vs all-changes — that's a non-goal). The empty-state and non-git message strings live as constants in a new `ReviewMessages` object placed in `src/main/kotlin/com/opencode/acp/review/ReviewMessages.kt` (alongside `ReviewSkill.kt` in the `review` package), to keep them out of inline UI strings:

```kotlin
object ReviewMessages {
    const val NOTHING_STAGED = "⚠️ No staged changes. Use `git add <file>` to stage files for review, then run /review-perform again."
    const val NO_GIT_REPO = "⚠️ Staged-changes review requires a Git repository. Initialize git (`git init`) or open a git project."
    const val NOTHING_STAGED_UI = "No staged changes\nUse `git add` to stage files for review"
    const val NO_GIT_REPO_UI = "Staged-changes review requires a Git repository\nInitialize git to use the Review tab"
}
```

---

## 5. Assumptions & Dependencies

**Assumptions:**
- git4idea ships with IntelliJ IDEA and most JetBrains IDEs, but **not all** (some targeted IDEs like DataGrip may not bundle it). Declaring it as an **optional** bundled-plugin dependency (`<depends optional="true" config-file="plugin-git.xml">Git4Idea</depends>`, matching the MCP server pattern at `plugin.xml:77`) makes the APIs available at compile time (`build.gradle.kts` `bundledPlugin("git4idea")`) and runtime where present, while the plugin still loads on IDEs that don't bundle it — `getStagedFiles()` returns `NoGitRepository` gracefully. **Plugin id verified (see OQ2):** the authoritative plugin id is `"Git4Idea"` (CamelCase), confirmed from the git4idea `plugin.xml` inside the pinned platform (IU 2026.1, `plugins/vcs-git/lib/vcs-git.jar`). Note: the plugin **directory** was renamed to `vcs-git` in recent platform versions, but the plugin **id `Git4Idea`** is unchanged. The Kotlin **package** remains `git4idea` (lowercase) — so `<depends>` uses `Git4Idea` (CamelCase) while import statements use `git4idea.*` (lowercase). The `build.gradle.kts` `bundledPlugin()` name is `"git4idea"` (lowercase, matching the Gradle bundled-plugin module name, not the platform plugin id).
- **Staged changes source (RESOLVED):** We use `git diff --cached` via git4idea's managed git execution (`GitLineHandler`), NOT `ChangeListManager`'s staged-aware API. This works **regardless of IntelliJ's "Enable staging area" setting** (OFF by default) — system git always knows what's staged. Line deltas come from parsing the `git diff --cached` unified diff output directly (index-vs-HEAD content), not from `Change` revision properties — this resolves the "unverified content source" concern.
- Users who want to review all modified files can `git add -A` first. This is the documented workaround in the empty-state message.

**Dependencies:**
- **git4idea bundled plugin** — new **optional** compile/runtime dependency. Add to `build.gradle.kts` `intellijPlatform { bundledPlugin("git4idea") }` (compile-time) and `plugin.xml` `<depends optional="true" config-file="plugin-git.xml">Git4Idea</depends>` (runtime guard). Create `plugin-git.xml` with the git4idea `<dependencies>` entry.
- Existing `ChangeListManager` — retained for `openDiffForPath`'s current working-tree diff (existing behavior preserved for the non-staged path if any).
- No new external libraries.

---

## 6. Alternatives Considered

**Alternative: Keep `ChangeListManager` and filter by changelist name.**
- *What it is:* IntelliJ's git integration sometimes represents staged changes in a named changelist (e.g., "Staged"). Filter `ChangeListManager.getChangeLists()` for that name.
- *Why plausible:* Stays VCS-agnostic; no new dependency.
- *Why rejected:* The "staged" changelist naming is not guaranteed across git4idea versions and workflows (the staging workflow is opt-in in IntelliJ settings; without it, staged and unstaged share the default changelist). Unreliable. git4idea's explicit staged API is the source of truth.

**Alternative: Raw `ProcessBuilder` shell-out to `git diff --cached --name-only`.**
- *What it is:* Run the `git` binary directly via `ProcessBuilder` to list staged files.
- *Why plausible:* Simple, unambiguous, no API research needed.
- *Why rejected:* A **raw `ProcessBuilder`** shell-out bypasses read-action threading contracts, ignores IntelliJ's cached VCS state, requires locating the `git` binary per repo, and is inconsistent with the existing architecture (the codebase uses IntelliJ's VCS APIs).
- *Distinction from the CHOSEN approach:* The chosen approach uses **git4idea's managed git execution** (`GitLineHandler` / `git diff --cached`) — this is the IDE's own git invocation, NOT a raw `ProcessBuilder`. It respects the configured git binary path, read-action threading, and cached VCS state. git4idea-managed git execution is the **primary** approach, not a fallback. The raw `ProcessBuilder` shell-out is rejected; git4idea-managed `git diff --cached` is chosen.

**Alternative: Hybrid — staged for git, all-changes for non-git.**
- *What it is:* Fall back to the current all-modified-files behavior for SVN/Hg/Perforce.
- *Why plausible:* Preserves the feature for non-git users.
- *Why rejected:* User decision (scope question 3) — the feature is git-only with explicit guidance for non-git. A silent fallback would review the wrong set (working-tree changes presented as "staged") and mislead non-git users. Honest guidance is better than silent wrong behavior.

---

## 7. Cross-Cutting Concerns

### 7.1 Security
No new security surface. The change narrows which files are reviewed (staged only), which is a reduction in data sent to the LLM, not an expansion. No new file-path handling.

### 7.2 Reliability & Availability
git4idea is a bundled plugin present in IntelliJ IDEA and most JetBrains IDEs, but the **optional dependency** pattern ensures graceful degradation on IDEs that don't bundle it (e.g., DataGrip) — `getStagedFiles()` returns `NoGitRepository` and the Review tab shows the "requires a Git repository" message. The non-git detection path (`NoGitRepository`) covers both "no git repo" and "git4idea not loaded" cases. If git4idea APIs throw (e.g., repo in a bad state, git binary not found), the existing `catch (Exception)` in `ReviewPanel.produceState` maps it to `ReviewState.Error(retryable=true)`, preserving current resilience.

### 7.3 Performance

`git diff --cached` via git4idea's managed execution spawns a git process on each call — this has a **process-spawn cost** (typically 50-150ms per repo for small-to-medium repos) that the current `ChangeListManager` read (cached in-memory, no spawn) does not. The 300ms debounce in `ReviewPanel.produceState` (`ReviewPanel.kt:180`) mitigates rapid re-spawns during bursty VCS events (e.g., staging multiple files in quick succession). For multi-repo projects, the cost scales linearly with repo count (one `git diff --cached` per repo). The `lineDeltaCache` in `GitService` avoids recomputing line deltas on unchanged files across refreshes.

**Trade-off assessment:** The process-spawn cost is acceptable because (a) the 300ms debounce prevents rapid re-spawns, (b) the Review tab is user-driven (not a hot path), and (c) the correctness benefit (staged-only, regardless of the staging-area setting) outweighs the latency cost. The current `ChangeListManager` read is faster but reviews the wrong file set (all working-tree changes, not staged-only). The trade-off is acceptable.

**Refresh trigger confirmation:** `ChangeListManager` change events fire on `git add` operations — staging changes updates the changelist state, which triggers the `produceState` re-evaluation. This is the existing refresh mechanism and continues to work with the new staged-only path.

### 7.4 Observability
All new code paths log via `logger.info { "[ACP] ..." }` per AGENTS.md logging convention. Specifically: when `NothingStaged` or `NoGitRepository` is returned, log at INFO so the state transition is visible in `idea.log`. Never `println`.

---

## 8. Testing Strategy

### 8.2 Key Scenarios

Per AGENTS.md: subagents write tests, the main orchestrator runs `.\gradlew.bat test` (baseline **1171 tests, 0 failures**). Compose UI rendering tests are `@Disabled` (ComposePanel cannot render in plain unit tests — see AGENTS.md "Compose UI Tests"); test the **logic**, not the composables.

**New test file: `GitServiceTest.kt`** (no `GitServiceTest` exists today — the file-gathering logic is currently untested):

| Scenario | Verification |
|----------|--------------|
| Git repo with staged files → `Staged(files)` with correct paths + statuses | `getStagedFiles()` returns `StagedFilesResult.Staged`; `files` match the staged set; `.review/` files filtered out |
| Git repo, nothing staged → `NothingStaged` | Returns `StagedFilesResult.NothingStaged` |
| No git repo → `NoGitRepository` | `GitRepositoryManager.getRepositories` empty → returns `StagedFilesResult.NoGitRepository` |
| Line deltas computed from staged (index-vs-HEAD) content, not working-tree | Mock staged `Change` revisions; assert `LineDelta.Known` values match the staged diff |
| Threading: `getStagedFiles()` runs inside `runReadAction` | Follow `ReviewCommandHandlerTest` pattern — mock `ApplicationManager.getApplication().runReadAction` to execute the `Computable` synchronously |

`GitService` touches IntelliJ platform APIs (`GitRepositoryManager`, `ChangeListManager`), so these tests require either MockK mocking of those static `getInstance(project)` calls (matching `ReviewCommandHandlerTest`'s approach) or, if the platform APIs can't be reliably mocked, a PSI-tagged integration test (`@Tag("psi")`, runs in `testPsi` task with the IntelliJ test framework). The implementer should prefer MockK unit tests for the pure branching logic (`NothingStaged`/`NoGitRepository` mapping) and use a PSI integration test only if the staged-enumeration API can't be mocked.

**Update `ReviewCommandHandlerTest.kt`:**

| Scenario | Verification |
|----------|--------------|
| `/review-perform` with staged files → sends prompt with staged paths | Mock `gitService.getStagedFiles()` returns `Staged(listOf(...))`; assert `sendCalls[0].text` contains the staged path |
| `/review-perform` with nothing staged → injects local message, does NOT send to LLM | Mock returns `NothingStaged`; assert `injectedMessages` contains the "stage your changes" message; `sendCalls` empty |
| `/review-perform` in non-git project → injects local message, does NOT send | Mock returns `NoGitRepository`; assert injected message; `sendCalls` empty |
| Same three scenarios for `/review-perform-gaming` and `/review-recheck` | Mirror the perform tests; for recheck, verify the `preRecheckIndex`/`replySnapshot`/`refreshReviewFiles` flow still runs in the `Staged` branch |

**Test migration scope (13 mock sites):** There are **13 `getChangedFiles()` mock sites** in `ReviewCommandHandlerTest.kt` (lines 205, 216, 228, 250, 263, 278, 293, 304, 315, 327, 340, 350, 363) that must all migrate to `getStagedFiles()`. Since `StagedFilesResult` is a **sealed interface**, MockK relaxed mocking needs **explicit stubs on every test** — a relaxed mock's default return for a sealed-return-type method may not satisfy the `when` exhaustive branching. Tests currently returning `emptyList()` to test model-arg resolution (e.g., wildcard/cancellation tests that don't care about files but need the send to proceed) must return `Staged(listOf(changedFile(...)))` to preserve the send behavior being tested — returning `NothingStaged` would intercept with `injectLocalMessage` and never reach `executeMultiModelReview`, breaking those tests. The wildcard/cancellation/model-arg tests keep working because they mock the file-gathering step entirely.

---

## 9. Rollout / Migration

**User-visible behavior change:** This is a **user-visible** behavior change — the Review tab and `/review-perform*` commands now cover **fewer files** (staged only) instead of all modified files. Users who previously relied on reviewing all working-tree changes must now `git add` their files first. The empty-state message ("No staged changes — Use `git add` to stage files for review") is the **primary UX communication vector** for this change. No in-app notification or migration dialog is planned — the empty-state guidance is sufficient.

**No data migration needed:** Existing review sessions (`.review/` JSON files with comments) remain valid — comments are keyed by file path and line number, not by changelist state. This change only affects **future** review invocations (which files get listed in the prompt), not existing review artifacts.

**Backward compatibility:** `GitService.getChangedFiles()` is kept (deprecated) for any unexpected external callers. `ReviewState.Empty` is removed — any code referencing it must migrate to `NothingStaged` or `NotAGitRepository` (the `ReviewEmptyContent` composable and its `is ReviewState.Empty` branch in `ReviewPanel.kt:242` are deleted).

**Rollout is unconditional:** No feature flag, no settings toggle. The change ships for all users on the next plugin update. Users who want the old behavior can `git add -A` before reviewing.

---

## 10. Open Questions

1. **~~Exact git4idea staged-enumeration API.~~** ✅ **RESOLVED:** We use `git diff --cached` via git4idea's managed git execution (`GitLineHandler`), NOT `ChangeListManager`'s staged-aware API or `GitChangeProvider`. Line deltas come from parsing the `git diff --cached` unified diff output directly. The exact git4idea class is `GitLineHandler` (or `GitHandler`) — confirm against the pinned platform (IU 2026.1) during implementation. This resolves the "unverified content source" concern (content comes from the diff output, not `Change` revision properties).
2. **~~git4idea plugin id for `plugin.xml` `<depends>`.~~** ✅ **RESOLVED (verified from pinned platform):** The plugin id is **`Git4Idea`** (CamelCase), confirmed from the git4idea `plugin.xml` inside the pinned platform (IU 2026.1, `plugins/vcs-git/lib/vcs-git.jar` → `<id>Git4Idea</id>`). The plugin **directory** was renamed to `vcs-git` in recent platform versions, but the plugin **id `Git4Idea`** is unchanged. The Kotlin **package** is `git4idea` (lowercase). Summary of the three naming contexts: (a) `<depends>` / `PluginManager.isPluginInstalled()` → `Git4Idea` (CamelCase); (b) `import` statements → `git4idea.*` (lowercase package); (c) `build.gradle.kts` `bundledPlugin("...")` → `git4idea` (lowercase Gradle bundled-plugin module name).
3. **~~Staged changelist detection when IntelliJ's staging workflow is disabled.~~** ✅ **RESOLVED:** System git (`git diff --cached`) works **regardless** of IntelliJ's "Enable staging area" setting (OFF by default). The staging-area setting only affects IntelliJ's changelist UI representation, not git's index. Using `git diff --cached` via git4idea managed execution bypasses the setting entirely. No longer a blocker.
4. **~~`ReviewState.Empty` disposition.~~** ✅ **RESOLVED (user decision):** Remove `ReviewState.Empty` from the sealed interface. `NothingStaged` and `NotAGitRepository` replace it. Delete the `is ReviewState.Empty -> ReviewEmptyContent(...)` branch in `ReviewPanel.kt:242` and the `ReviewEmptyContent` composable.

---

## 13. Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-08-15 | AI Assistant | Initial draft. Implements the deferred v2 "staged/unstaged separation" from `docs/tdd/Done/review-tab.md`, narrowed to staged-only. Scope: Review tab + `/review-perform`, `/review-perform-gaming`, `/review-recheck`. Decisions (user-confirmed): all file-gathering commands scoped to staged; clear "stage your changes" empty-state message; git-only with guidance for non-git VCS. |
| 2026-08-15 | AI Assistant | **v2 revision** addressing council review findings. Key changes: (1) git4idea is an **optional** dependency (`<depends optional="true" config-file="plugin-git.xml">Git4Idea</depends>`, matching MCP server pattern) — not hard — preserving compatibility with all 10 targeted IDEs; `getStagedFiles()` returns `NoGitRepository` when git4idea absent. (2) Staged changes source is **`git diff --cached` via git4idea managed execution** (`GitLineHandler` — the IDE's own git invocation, NOT raw `ProcessBuilder`) as the PRIMARY approach; works regardless of "Enable staging area" setting (OFF by default). (3) `ReviewState.Empty` **REMOVED** — `NothingStaged`/`NotAGitRepository` replace it; `ReviewEmptyContent` composable deleted. (4) `openDiffForPath` receives staged changes from GitService as a parameter. (5) Line deltas come from `git diff --cached` output directly (resolves unverified content source). (6) Fixed `ReviewCommandHandler.kt` path (was `review/`, correct is `chat/viewmodel/`). (7) `StagedFilesResult` placed in `chat/model/ReviewModels.kt` (not `review/ReviewModels.kt`). (8) `NotAGitRepository` acknowledged as NEW (original TDD specified it but never shipped). (9) Current empty-list behavior (sends "no uncommitted changes" to LLM) documented as deliberate improvement (intercepts with `injectLocalMessage`). (10) Fixed `@Deprecated` `ReplaceWith` (removed — return types don't match). (11) Cache lifecycle note for `getStagedFiles()`. (12) 13 mock-site test migration scope spelled out. (13) `executeReviewRecheckCommand` early-return before try/finally for non-Staged branches. (14) Review comment line-number alignment risk documented. (15) Multi-repo merge behavior stated. (16) Added §7.3 Performance, §9 Rollout/Migration. (17) Reframed §6 shell-out alternative (softened — raw `ProcessBuilder` rejected, git4idea-managed execution chosen). (18) OQ1/OQ3/OQ4 resolved; OQ2 (plugin id casing) kept open. (19) `ReviewMessages` location specified (`review/ReviewMessages.kt`). |
| 2026-08-15 | AI Assistant | **v2.1: OQ2 resolved.** Verified the git4idea plugin id from the actual `plugin.xml` inside the pinned platform (IU 2026.1, `plugins/vcs-git/lib/vcs-git.jar`). The plugin id is **`Git4Idea`** (CamelCase) — confirmed via `<id>Git4Idea</id>`. Key findings: (a) the plugin **directory** was renamed to `vcs-git` in recent platform versions, but the plugin **id** `Git4Idea` is unchanged; (b) the Kotlin **package** remains `git4idea` (lowercase); (c) the `build.gradle.kts` `bundledPlugin()` name is `"git4idea"` (lowercase Gradle module name). Summary: `<depends>` → `Git4Idea`, imports → `git4idea.*`, `bundledPlugin()` → `git4idea`. All four open questions now resolved; the TDD has zero remaining open questions and is implementation-ready. |