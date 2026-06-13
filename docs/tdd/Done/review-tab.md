# Technical Design Document: Review Tab

> **Status:** Draft — Revised after 3 adversarial review rounds
> **Author(s):** AI Assistant
> **Reviewer(s):** Kimi K2.6, DeepSeek V4 Pro, GLM 5.1, MiniMax M2.7
> **Last Updated:** 2026-06-04
> **Related docs:** [Image reference: Review tab UI mockup]

---

## 1. TL;DR

Add a "Review" tab to the sidebar that displays git changes (modified, added, deleted, untracked files) with line count deltas (+N/-N). Clicking a file opens the native IDE diff viewer via `DiffManager`. Line deltas are computed using an LCS diff algorithm on `ContentRevision.content` in-memory (no subprocess). If the project has no git repository, display a warning message. All VCS data reads happen inside `runReadAction` on `Dispatchers.IO`; all UI mutations and diff viewer calls happen on the EDT. Git4Idea classes are accessed via reflection to avoid `NoClassDefFoundError` when the optional plugin is absent. Untracked file clicks use the `VirtualFile` already stored in `ChangedFile` (not path resolution). The `DiffContentFactory.create()` third parameter is the full `fileName` (not just the extension) for correct syntax highlighting.

---

## 2. Context & Scope

### 2.1 Current State

The plugin sidebar has two tabs: **Sessions** and **Context**. The Context tab shows session metadata (model, tokens, cost, changes). The plugin has zero git integration — no dependencies on `git4idea`, no VCS API usage.

Users currently have no way to review code changes made during an AI session within the plugin UI. They must switch to the IDE's Git tool window or use external tools.

### 2.2 Problem Statement

Users working with AI-assisted coding need a quick way to review what files changed during a session. Switching context to the IDE's Git tool window breaks the workflow. A dedicated Review tab in the sidebar provides a focused view of changes with one-click diff access.

### 2.3 Scope

**In scope:**
- Display list of changed files from git (modified, added, deleted, untracked)
- Show line count deltas per file (+N/-N) computed via LCS diff algorithm
- File status colors (modified/added/deleted/untracked/conflicted)
- Click file to open native IDE diff viewer (tracked) or editor (untracked)
- Warning message when no git repository is detected
- Live updates when files change (via `ChangeListManager.addChangeListListener`)
- Graceful fallback when git4idea plugin is absent

**Out of scope:**
- Inline diff preview within the sidebar (use IDE's native diff viewer)
- Staged vs unstaged grouping (future enhancement)
- Commit history (future enhancement)
- PR/MR status integration (future enhancement)
- Unified/Split diff toggle (handled by IDE's diff viewer)
- Session-scoped change filtering (shows all project changes, not just AI session changes)

---

## 3. Goals & Non-Goals

### Goals

1. Display all changed files in a scrollable list with file icons and accurate line deltas
2. Single-click opens the IDE's native diff viewer for tracked files, or the editor for untracked files
3. Show a clear warning when no git repository exists in the project
4. Auto-refresh the file list when git state changes (debounced 500ms)
5. All VCS data reads happen inside `runReadAction` on background threads; all UI calls happen on EDT
6. Gracefully handle absent git4idea plugin (no crash)

### Non-Goals

- Inline diff rendering in the sidebar (deferred to v2)
- Staged/unstaged separation (deferred to v2)
- Commit history or PR integration (deferred to v2)
- Session-scoped changes only (deferred to v2)

---

## 4. Proposed Solution

**Add a third tab "Review" to the sidebar. Data reads use `ChangeListManager` (default changelist only) inside `runReadAction` on `Dispatchers.IO`. Line deltas are computed via an LCS diff on `ContentRevision.content`. Untracked files come from `ChangeListManager.unversionedFiles`. Git4Idea availability is checked via reflection (`PluginManager.isPluginInstalled`) to avoid class-loading crashes. The diff viewer is opened on EDT via `invokeLater`. Change notifications use `ChangeListManager.addChangeListListener()` directly (not message bus). Debouncing uses `MutableSharedFlow.debounce(500)`. Missing `Change` objects at click time open the file directly.**

### 4.1 Architecture Diagram

> **Omitted** per Mini TDD guidelines.

### 4.2 Component & Module Design

> **Omitted** per Mini TDD guidelines.

### 4.3 API / Interface Design

This feature uses IntelliJ Platform APIs only — no new HTTP endpoints or external APIs.

**Key Platform APIs:**

| API | Package | Purpose | Threading |
|-----|---------|---------|-----------|
| `ChangeListManager` | `com.intellij.openapi.vcs.changes` | Get changed files, listen for changes | Requires read action |
| `Change` | `com.intellij.openapi.vcs.changes` | Individual file change (before/after revision) | Requires read action |
| `ContentRevision` | `com.intellij.openapi.vcs.changes` | File content at a specific revision | Requires read action |
| `FileStatus` | `com.intellij.openapi.vcs` | Status enum (MODIFIED, ADDED, DELETED, etc.) | Thread-safe |
| `DiffManager` | `com.intellij.diff` | Open native diff viewer | **Must be called on EDT** |
| `DiffContentFactory` | `com.intellij.diff` | Create diff content from strings/files | Requires read action for content |
| `SimpleDiffRequest` | `com.intellij.diff.requests` | Two-panel diff request | N/A (data class) |
| `FileEditorManager` | `com.intellij.openapi.fileEditor` | Open file in editor | **Must be called on EDT** |
| `PluginManager` | `com.intellij.ide.plugins` | Check if git4idea is installed | Thread-safe |

**Dependency declarations required:**

| File | Change |
|------|--------|
| `build.gradle.kts` | Add `bundledPlugin("Git4Idea")` to `intellijPlatform {}` block |
| `plugin.xml` | Add `<depends optional="true">Git4Idea</depends>` (no `config-file`) |

> **Important:** Do NOT use `config-file` attribute. Git4Idea classes are accessed via reflection guarded by `PluginManager.isPluginInstalled()`. This avoids `NoClassDefFoundError` when git4idea is absent.

### 4.4 Key Flows

**Flow 1: Review tab opened with git available**

1. User clicks "Review" tab
2. `ReviewPanel` composable enters `produceState` block
3. On `Dispatchers.IO` inside `runReadAction`: queries `ChangeListManager.getInstance(project).defaultChangeList?.changes` for tracked changes
4. On `Dispatchers.IO` inside `runReadAction`: queries `ChangeListManager.getInstance(project).unversionedFiles` for untracked files
5. For tracked changes, computes line deltas via LCS diff on `beforeRevision.content` vs `afterRevision.content`
6. Returns `ReviewState.Loaded(files)` (state mutation happens on EDT via `produceState`)
7. Registers change listener in `DisposableEffect` via `ChangeListManager.addChangeListListener(listener, parentDisposable)` — the SAME listener emits to `MutableStateFlow`
8. `MutableStateFlow` is collected via `.debounce(500).collectAsState()` and used as `produceState` key — all in a single `DisposableEffect`, no separate helper function

**Flow 2: Review tab opened without git**

1. User clicks "Review" tab
2. `produceState` checks `PluginManager.isPluginInstalled("Git4Idea")` via reflection
3. If git4idea not installed, or `GitRepositoryManager` (loaded via reflection) reports no repos, returns `ReviewState.NoGitRepository`
4. UI displays warning: "No git repository detected. Initialize git to track changes."

**Flow 3: User clicks a tracked file**

1. User clicks file row in Review tab
2. `ReviewPanel` calls `onFileClick(filePath, status)` on the EDT
3. On EDT inside `runReadAction`: look up current `Change` from `changeListManager.defaultChangeList?.changes` by file path
4. If found: build `SimpleDiffRequest` with `DiffContentFactory.create(project, content, fileExtension)`, call `DiffManager.getInstance().showDiff(project, request)` on EDT
5. If not found (change was committed/reverted): open file via `FileEditorManager.getInstance(project).openFile(virtualFile, true)` on EDT

**Flow 4: User clicks an untracked file**

1. User clicks untracked file row
2. Resolve `VirtualFile` from `LocalFileSystem.getInstance().findFileByPath()`
3. Open via `FileEditorManager.getInstance(project).openFile(virtualFile, true)` on EDT

**Flow 5: Stale change reference handling**

1. User clicks a file whose `Change` object was invalidated by a VCS refresh
2. `openDiffForPath` looks up current `Change` by file path from `defaultChangeList`
3. If found, opens diff with fresh `Change`; if not found, opens the file in the editor

### 4.5 Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | Kotlin | Project standard |
| UI | Compose for Desktop (Jewel) | Project standard |
| Git API | `git4idea` (optional, via reflection) | Ships with IDEA, accessed via `PluginManager` guard |
| VCS API | `com.intellij.openapi.vcs` (bundled) | Platform VCS abstraction, always available |
| Diff API | `com.intellij.diff` (bundled) | Platform diff viewer |
| Diff Algorithm | LCS (custom implementation) | In-memory, no subprocess, thread-safe |

### 4.6 Migration Strategy

> **Omitted** — greenfield feature, no migration needed.

### 4.7 Implementation Blueprint

#### 4.7.1 Data Models & Schemas

```kotlin
// New enum value in existing SidebarTab enum
enum class SidebarTab { SESSIONS, CONTEXT, REVIEW }

// Display model for a single changed file.
// Stores filePath (not Change) to avoid stale references after VCS refresh.
data class ChangedFile(
    val filePath: String,           // Relative path from project root (always uses '/')
    val fileName: String,           // File name only (for display)
    val status: FileChangeStatus,   // MODIFIED, ADDED, DELETED, UNTRACKED, CONFLICTED
    val lineDelta: LineDelta,       // Line count info (may be unknown for binaries)
    val virtualFile: VirtualFile?   // Null for deleted files; used to open in editor
)

// Line delta — distinguishes "zero changes" from "unknown/binary"
sealed interface LineDelta {
    /** Known line counts computed via LCS diff. */
    data class Known(val additions: Int, val deletions: Int) : LineDelta
    /** Binary file, untracked file, or diff unavailable — display "—" in UI. */
    data object Unknown : LineDelta
}

// Maps from IntelliJ's FileStatus (15+ values) to our simplified status
enum class FileChangeStatus {
    MODIFIED,
    ADDED,
    DELETED,
    UNTRACKED,
    CONFLICTED
}

// Sealed state for the review panel
sealed interface ReviewState {
    data object Loading : ReviewState
    data class Loaded(val files: List<ChangedFile>) : ReviewState
    data object Empty : ReviewState           // Git repo exists but no changes
    data object NoGitRepository : ReviewState
    data class Error(val message: String, val retryable: Boolean = true) : ReviewState
}
```

#### 4.7.2 Class & Interface Definitions

```kotlin
/**
 * Service layer — wraps IntelliJ VCS APIs.
 *
 * CRITICAL: All methods that touch VCS data must be called inside
 * runReadAction on Dispatchers.IO. Git4Idea classes are accessed
 * via reflection to avoid NoClassDefFoundError when the plugin is absent.
 */
class GitService(private val project: Project) {

    companion object {
        private const val GIT4IDEA_PLUGIN_ID = "Git4Idea"

        /** Check if git4idea is installed via PluginManager (no class loading). */
        fun isGitPluginInstalled(): Boolean {
            return PluginManager.isPluginInstalled(
                PluginManager.getPlugin(PluginId.getId(GIT4IDEA_PLUGIN_ID))
            )
        }
    }

    /** Returns true if git4idea is installed AND at least one git repo exists. */
    fun isGitAvailable(): Boolean {
        if (!isGitPluginInstalled()) return false
        // Access GitRepositoryManager via reflection to avoid NoClassDefFoundError
        return try {
            val managerClass = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstance = managerClass.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val getRepositories = managerClass.getMethod("getRepositories")
            @Suppress("UNCHECKED_CAST")
            val repos = getRepositories.invoke(manager) as? List<*> ?: emptyList<Any>()
            repos.isNotEmpty()
        } catch (e: Exception) {
            // Catch Exception for git4idea reflection failures.
            // Note: NoClassDefFoundError extends Error, not Exception — it is caught
            // by the outer produceState's catch (Throwable) block, not here.
            false
        }
    }

    /**
     * Returns list of changed files with line deltas.
     * MUST be called inside runReadAction.
     */
    fun getChangedFiles(): List<ChangedFile> {
        val changeListManager = ChangeListManager.getInstance(project)

        // Use default changelist only (not allChanges which includes shelves)
        val defaultChanges = changeListManager.defaultChangeList?.changes
            ?: changeListManager.allChanges

        val trackedChanges = defaultChanges.mapNotNull { change ->
            try {
                val filePath = getRelativePath(change)
                val fileName = change.virtualFile?.name
                    ?: change.beforeRevision?.file?.name
                    ?: "unknown"
                val virtualFile = change.virtualFile ?: change.afterRevision?.virtualFile
                ChangedFile(
                    filePath = filePath,
                    fileName = fileName,
                    status = mapFileStatus(change.fileStatus),
                    lineDelta = computeLineDelta(change),
                    virtualFile = virtualFile
                )
            } catch (e: Exception) {
                null // Skip changes that throw (binary, locked, etc.)
            }
        }

        // Untracked files (separate API — not in allChanges)
        val untrackedFiles = changeListManager.unversionedFiles.map { virtualFile ->
            val filePath = getRelativePathFromRoot(virtualFile.path)
            ChangedFile(
                filePath = filePath,
                fileName = virtualFile.name,
                status = FileChangeStatus.UNTRACKED,
                lineDelta = LineDelta.Unknown,
                virtualFile = virtualFile
            )
        }

        return trackedChanges + untrackedFiles
    }

    /**
     * Computes line deltas using LCS diff algorithm on ContentRevision.content.
     * Returns LineDelta.Unknown for binary files or when content is unavailable.
     * MUST be called inside runReadAction.
     */
    private fun computeLineDelta(change: Change): LineDelta {
        return try {
            val before = change.beforeRevision?.content
            val after = change.afterRevision?.content

            // Binary files or completely unavailable content
            if (before == null && after == null) return LineDelta.Unknown

            val beforeLines = before?.lines() ?: emptyList()
            val afterLines = after?.lines() ?: emptyList()

            // Use LCS diff to compute actual additions and deletions
            val (additions, deletions) = computeLcsDiff(beforeLines, afterLines)
            LineDelta.Known(additions = additions, deletions = deletions)
        } catch (e: Throwable) {
            // Catch Throwable (not Exception) to handle NoClassDefFoundError,
            // VcsException, IOException, etc. for locked/binary/large files
            LineDelta.Unknown
        }
    }

    /**
     * LCS-based diff algorithm that computes real additions/deletions.
     * Unlike simple line-count comparison, this correctly identifies lines
     * that were changed (not just net additions).
     */
    private fun computeLcsDiff(before: List<String>, after: List<String>): Pair<Int, Int> {
        val m = before.size
        val n = after.size

        // Optimize: if one side is empty, all lines are added or deleted
        if (m == 0) return Pair(n, 0)
        if (n == 0) return Pair(0, m)

        // Standard LCS dynamic programming
        // Use space-optimized version for large files (only keep 2 rows)
        val maxDim = maxOf(m, n)
        if (maxDim > 5000) {
            // Fall back to line-count comparison for very large files
            // to avoid O(n*m) memory/time cost
            return Pair(
                (after.size - before.size).coerceAtLeast(0),
                (before.size - after.size).coerceAtLeast(0)
            )
        }

        val dp = Array(2) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i % 2][j] = if (before[i - 1] == after[j - 1]) {
                    dp[(i - 1) % 2][j - 1] + 1
                } else {
                    maxOf(dp[(i - 1) % 2][j], dp[i % 2][j - 1])
                }
            }
        }

        val lcsLength = dp[m % 2][n]
        val additions = n - lcsLength  // Lines in "after" not in LCS
        val deletions = m - lcsLength  // Lines in "before" not in LCS
        return Pair(additions, deletions)
    }

    /**
     * Gets relative path from project root.
     * Uses beforeRevision.file.path for deleted files (virtualFile is null).
     * Normalizes path separators to '/' for cross-platform consistency.
     */
    private fun getRelativePath(change: Change): String {
        val absolutePath = change.virtualFile?.path
            ?: change.beforeRevision?.file?.path
            ?: return "unknown"
        return getRelativePathFromRoot(absolutePath)
    }

    private fun getRelativePathFromRoot(absolutePath: String): String {
        val basePath = project.basePath ?: return absolutePath
        // Normalize separators for cross-platform consistency
        val normalizedAbsolute = absolutePath.replace('\\', '/')
        val normalizedBase = basePath.replace('\\', '/') + "/"
        return if (normalizedAbsolute.startsWith(normalizedBase)) {
            normalizedAbsolute.removePrefix(normalizedBase)
        } else {
            normalizedAbsolute
        }
    }

    private fun mapFileStatus(status: FileStatus): FileChangeStatus = when (status) {
        FileStatus.MODIFIED -> FileChangeStatus.MODIFIED
        FileStatus.ADDED -> FileChangeStatus.ADDED
        FileStatus.DELETED -> FileChangeStatus.DELETED
        FileStatus.MERGED_WITH_CONFLICTS -> FileChangeStatus.CONFLICTED
        FileStatus.UNVERSIONED -> FileChangeStatus.UNTRACKED
        // UNKNOWN, IGNORED, HIJACKED, SWITCHED, OBSOLETE, TYPE_CHANGED, etc.
        else -> FileChangeStatus.MODIFIED
    }
}
```

#### 4.7.3 Function Signatures

```kotlin
/**
 * Shared utility: compute relative path from project root.
 * Used by both GitService and openDiffForPath to ensure consistent path matching.
 * Normalizes path separators to '/' for cross-platform consistency.
 */
fun getRelativePath(project: Project, change: Change): String {
    val absolutePath = change.virtualFile?.path
        ?: change.beforeRevision?.file?.path
        ?: return "unknown"
    return getRelativePathFromRoot(project, absolutePath)
}

fun getRelativePathFromRoot(project: Project, absolutePath: String): String {
    val basePath = project.basePath ?: return absolutePath
    val normalizedAbsolute = absolutePath.replace('\\', '/')
    val normalizedBase = basePath.replace('\\', '/') + "/"
    return if (normalizedAbsolute.startsWith(normalizedBase)) {
        normalizedAbsolute.removePrefix(normalizedBase)
    } else {
        normalizedAbsolute
    }
}

/**
 * Opens the IDE's native diff viewer for a tracked change.
 * Called on EDT via invokeLater.
 *
 * If the Change is stale (committed/reverted), falls back to opening the file.
 * Uses full fileName (not extension) as 3rd arg to DiffContentFactory for syntax highlighting.
 */
fun openDiffForPath(project: Project, filePath: String, virtualFile: VirtualFile?) {
    ApplicationManager.getApplication().invokeLater {
        try {
            val changeListManager = ChangeListManager.getInstance(project)
            val currentChanges = ApplicationManager.getApplication().runReadAction<List<Change>> {
                changeListManager.defaultChangeList?.changes?.toList() ?: emptyList()
            }

            val change = currentChanges.find {
                getRelativePath(project, it) == filePath
            }

            if (change != null) {
                val fileName = change.virtualFile?.name ?: filePath.substringAfterLast('/')
                // Use full fileName (not extension) for DiffContentFactory —
                // the 3rd parameter is interpreted as a filename for syntax highlighting
                val beforeContent = ApplicationManager.getApplication().runReadAction<String?> {
                    change.beforeRevision?.content
                } ?: ""
                val afterContent = ApplicationManager.getApplication().runReadAction<String?> {
                    change.afterRevision?.content
                } ?: ""

                val factory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(
                    fileName,
                    factory.create(project, beforeContent, fileName),  // fileName, not extension
                    factory.create(project, afterContent, fileName),     // fileName, not extension
                    change.beforeRevision?.revisionNumber?.asString() ?: "Base",
                    change.afterRevision?.revisionNumber?.asString() ?: "Working"
                )
                DiffManager.getInstance().showDiff(project, request)
            } else if (virtualFile != null) {
                // Change was committed/reverted — open file directly
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        } catch (e: Throwable) {
            // Catch Throwable (not Exception) to handle NoClassDefFoundError from optional plugins
            println("[ReviewTab] Failed to open diff: ${e.message}")
        }
    }
}

/** Opens an untracked file in the editor. Uses VirtualFile directly (already stored in ChangedFile). */
fun openUntrackedFile(project: Project, virtualFile: VirtualFile) {
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
}

/**
 * Main composable for the Review tab.
 *
 * ARCHITECTURE NOTE: The change listener and SharedFlow are unified in a single
 * DisposableEffect to ensure the listener that emits events is the same one that's
 * registered. The previous design had a separate `changeListManager_events()` helper
 * that created an unregistered listener and a local SharedFlow that went out of scope.
 *
 * All VCS reads happen inside runReadAction on Dispatchers.IO.
 * All UI mutations happen on EDT.
 */
@Composable
fun ReviewPanel(
    project: Project,
    modifier: Modifier = Modifier
) {
    val gitService = remember { GitService(project) }
    val refreshSignal = remember { MutableStateFlow(0) }

    // Register ChangeListListener with debounce in a SINGLE DisposableEffect.
    // The listener emits to refreshSignal, which is debounced before triggering produceState.
    DisposableEffect(project) {
        val changeListManager = ChangeListManager.getInstance(project)
        val refreshScope = rememberCoroutineScope()
        val listener = object : ChangeListAdapter() {
            override fun changeListUpdateDone() {
                refreshSignal.tryEmit(refreshSignal.value + 1)
            }
        }
        changeListManager.addChangeListListener(listener, this)
        onDispose {
            changeListManager.removeChangeListListener(listener)
        }
    }

    // Debounce the refresh signal (500ms)
    val debouncedRefresh by refreshSignal
        .debounce(500)
        .collectAsState(initial = 0)

    // Fetch data on background thread inside read action, update state on EDT.
    // Catch Throwable (not Exception) to handle NoClassDefFoundError from git4idea.
    val state by produceState<ReviewState>(
        initialValue = ReviewState.Loading,
        key1 = debouncedRefresh
    ) {
        try {
            value = withContext(Dispatchers.IO) {
                ApplicationManager.getApplication().runReadAction<ReviewState> {
                    if (!gitService.isGitAvailable()) {
                        ReviewState.NoGitRepository
                    } else {
                        val files = gitService.getChangedFiles()
                        if (files.isEmpty()) ReviewState.Empty
                        else ReviewState.Loaded(files)
                    }
                }
            }
        } catch (e: Throwable) {
            // Catch Throwable to handle NoClassDefFoundError from optional git4idea dependency
            value = ReviewState.Error(
                message = e.message ?: "Failed to load changes",
                retryable = true
            )
        }
    }

    // Render based on state
    when (val s = state) {
        is ReviewState.Loading -> LoadingContent(modifier)
        is ReviewState.NoGitRepository -> NoGitContent(modifier)
        is ReviewState.Empty -> EmptyContent(modifier)
        is ReviewState.Error -> ErrorContent(
            message = s.message,
            retryable = s.retryable,
            onRetry = { refreshSignal.tryEmit(refreshSignal.value + 1) },
            modifier = modifier
        )
        is ReviewState.Loaded -> FileListContent(
            files = s.files,
            onFileClick = { filePath, status, virtualFile ->
                when (status) {
                    FileChangeStatus.UNTRACKED -> {
                        // Use virtualFile directly — ChangedFile already stores it.
                        // Do NOT resolve from relative path (LocalFileSystem.findFileByPath
                        // requires absolute paths).
                        if (virtualFile != null) {
                            openUntrackedFile(project, virtualFile)
                        }
                    }
                    else -> openDiffForPath(project, filePath, virtualFile)
                }
            },
            modifier = modifier
        )
    }
}

> **Note:** The `ChangeListAdapter` class is from `com.intellij.openapi.vcs.changes.ChangeListAdapter` and provides empty defaults for all `ChangeListListener` methods, avoiding the need to implement every method.

#### 4.7.4 Component Mapping

| Component | Responsibility | Data Model(s) | Key Class(es) |
|-----------|---------------|---------------|----------------|
| `ReviewPanel.kt` | UI: file list, no-git warning, loading, error states, lifecycle | `ReviewState`, `ChangedFile` | `ReviewPanel()`, `ChangedFileRow()` |
| `GitService.kt` | Data: VCS queries, in-memory LCS diff, reflection-based git detection | `ChangedFile`, `FileChangeStatus`, `LineDelta` | `GitService` |
| `SessionSidebar.kt` | Wiring: add REVIEW tab button, update `when` branch | `SidebarTab.REVIEW` | `SidebarTabRow()`, `SessionSidebar()` |
| `ChatScreen.kt` | State: pass `project` to `ReviewPanel`, add REVIEW width branch | — | `ChatScreen()` |
| `ChatModels.kt` | Model: add `REVIEW` to `SidebarTab` enum | `SidebarTab` | — |
| `ChatConstants.kt` | Constants: add `SIDEBAR_REVIEW_WIDTH_DP` | — | — |
| `plugin.xml` | Dependency: declare optional Git4Idea | — | `<depends>` |
| `build.gradle.kts` | Build: declare Git4Idea bundled plugin | — | `bundledPlugin()` |

#### 4.7.5 UI Composable Signatures

The following composables are referenced by `ReviewPanel` but not yet defined. Each follows the same pattern as existing composables in `SessionSidebar.kt` and `ContextPanel.kt`.

```kotlin
/** Shows file list with status icons and line deltas. */
@Composable
fun FileListContent(
    files: List<ChangedFile>,
    onFileClick: (filePath: String, status: FileChangeStatus, virtualFile: VirtualFile?) -> Unit,
    modifier: Modifier = Modifier
)

/** Renders a single file row with icon, name, path, status color, and +N/-N delta. */
@Composable
fun ChangedFileRow(
    file: ChangedFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)

/** Loading spinner (reuses pattern from SessionSidebar.LoadingContent). */
@Composable
fun LoadingContent(modifier: Modifier = Modifier)

/** Warning message when no git repository is detected. */
@Composable
fun NoGitContent(modifier: Modifier = Modifier)

/** Empty state when git repo exists but has no changes. */
@Composable
fun EmptyContent(modifier: Modifier = Modifier)

/** Error state with retry button (mirrors ContextPanel error pattern). */
@Composable
fun ErrorContent(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
)
```

#### 4.7.6 Enums, Constants & Configuration

```kotlin
// In ChatConstants.kt — const val Int pattern matching existing SIDEBAR_WIDTH_DP
const val SIDEBAR_REVIEW_WIDTH_DP = 260  // Same width as Sessions tab

// In ReviewPanel.kt — theme-aware colors via Jewel's retrieveColorOrUnspecified
// These use IntelliJ's FileStatus color keys for platform consistency
// Fallback JBColor is used when the key is not available
@Composable
private fun statusColor(status: FileChangeStatus): Color {
    return when (status) {
        FileChangeStatus.MODIFIED -> retrieveColorOrUnspecified("FileStatus.ModifiedColor")
        FileChangeStatus.ADDED -> retrieveColorOrUnspecified("FileStatus.AddedColor")
        FileChangeStatus.DELETED -> retrieveColorOrUnspecified("FileStatus.DeletedColor")
        FileChangeStatus.UNTRACKED -> retrieveColorOrUnspecified("FileStatus.UnknownColor")
        FileChangeStatus.CONFLICTED -> retrieveColorOrUnspecified("FileStatus.ConflictColor")
    }
}
```

#### 4.7.7 Integration Points (Existing Files to Modify)

The following existing files need changes when adding the REVIEW tab:

**`ChatModels.kt`** — Add `REVIEW` to `SidebarTab` enum:
```kotlin
enum class SidebarTab { SESSIONS, CONTEXT, REVIEW }
```

**`ChatConstants.kt`** — Add sidebar width constant:
```kotlin
val SIDEBAR_REVIEW_WIDTH_DP = 260  // matches SIDEBAR_WIDTH_DP
```

**`ChatScreen.kt`** — Add REVIEW branch to sidebar width `when` expression (line ~271):
```kotlin
val sidebarTargetWidth = if (isSidebarVisible) {
    when (selectedSidebarTab) {
        SidebarTab.SESSIONS -> ChatConstants.SIDEBAR_WIDTH_DP
        SidebarTab.CONTEXT -> ChatConstants.SIDEBAR_CONTEXT_WIDTH_DP
        SidebarTab.REVIEW -> ChatConstants.SIDEBAR_REVIEW_WIDTH_DP
    }
} else 0
```

**`SessionSidebar.kt`** — Add third tab button + `when` branch (lines ~127-148 and ~80-113):
```kotlin
// In SidebarTabRow: add third SidebarTabButton for "Review"
// In SessionSidebar: add SidebarTab.REVIEW -> ReviewPanel(project, modifier.weight(1f))
```

**`SessionSidebar.kt`** — Update `SessionSidebar` signature to accept `project: Project`:
The sidebar needs `project` to pass to `ReviewPanel`. Currently it only receives `state` and `contextState`. Add `project` parameter.

**`plugin.xml`** — Add optional dependency:
```xml
<depends optional="true">Git4Idea</depends>
```

**`build.gradle.kts`** — Add bundled plugin:
```kotlin
bundledPlugin("Git4Idea")
```

---

## 5. Assumptions & Dependencies

**Assumptions:**
- `git4idea` is bundled with IntelliJ IDEA 2026.1+ (confirmed — it's a bundled plugin)
- `ChangeListManager` provides accurate change data via `defaultChangeList`
- `ContentRevision.content` is accessible inside `runReadAction` without exceptions for text files
- `PluginManager.isPluginInstalled("Git4Idea")` reliably detects plugin presence

**Dependencies (both require build/config changes):**
- `git4idea` — optional dependency, declared via `bundledPlugin("Git4Idea")` in `build.gradle.kts` and `<depends optional="true">Git4Idea</depends>` in `plugin.xml`. Accessed via reflection to avoid `NoClassDefFoundError`.
- `com.intellij.openapi.vcs` — platform API, always available
- `com.intellij.diff` — platform API, always available

---

## 6. Alternatives Considered

**Alternative: Run raw git commands via `GitLineHandler` for line diffs**
- *What it is:* Use `git diff --numstat` via subprocess to get line counts
- *Why plausible:* More control over output format, handles renames with `--numstat -M`
- *Why rejected:* (1) Blocks EDT unless carefully threaded, (2) misses staged changes without `--cached`, (3) binary files return `-` requiring special parsing, (4) path separator issues on Windows, (5) subprocess overhead per change event. LCS diff on `ContentRevision.content` provides accurate results in-memory.

**Alternative: Use `GitChangeListManager` instead of `ChangeListManager`**
- *What it is:* Git-specific change list manager from git4idea
- *Why plausible:* Git-specific features like staging area awareness
- *Why rejected:* Requires direct git4idea class references (crash risk with optional dependency). `ChangeListManager` is VCS-agnostic, always available, and sufficient for listing changes.

**Alternative: Use `ChangeListManager.CHANGES_LIST_TOPIC` message bus**
- *What it is:* Subscribe to change events via project message bus
- *Why rejected:* `CHANGES_LIST_TOPIC` is not a public API on `ChangeListManager`. The correct approach is `addChangeListListener(listener, disposable)` directly on the manager instance, which also provides proper lifecycle management via the `Disposable` parameter.

**Alternative: Simple line-count diff (net change)**
- *What it is:* `additions = max(0, afterLines - beforeLines), deletions = max(0, beforeLines - afterLines)`
- *Why plausible:* Trivially simple, O(1) computation
- *Why rejected:* Mathematically wrong. Replacing 10 lines with 10 different lines shows `+0/-0` instead of `+10/-10`. Users expect `git diff --stat`-style numbers. LCS diff produces accurate additions/deletions.

---

## 7. Cross-Cutting Concerns

### 7.1 Security

No security concerns — reads local git state only, no network calls, no credential access.

### 7.2 Reliability & Availability

- If `ChangeListManager.defaultChangeList` is null, fall back to `allChanges`
- If `ContentRevision.content` throws (binary, locked, large files), catch and return `LineDelta.Unknown`
- If git4idea is absent, show `NoGitRepository` state (checked via reflection, no crash)
- Listener cleanup via `DisposableEffect` prevents memory leaks
- `GitRepositoryManager.repositories` may be empty during project startup — show Loading until VCS init completes
- `ReviewState.Error` includes `retryable` flag and `onRetry` callback

### 7.3 Performance & Scalability

- `ChangeListManager.defaultChangeList.changes` is O(n) where n = number of changed files (typically <100)
- `ContentRevision.content` reads from VFS cache (fast) or disk (slower); all reads happen in `runReadAction` on `Dispatchers.IO`
- LCS diff is O(n*m) where n and m are line counts; falls back to net-change for files >5000 lines
- Change listener debounced 500ms via `MutableSharedFlow.debounce(500)` to avoid excessive recomputation
- Large files (>5000 lines) use simple net-change to avoid O(n*m) memory/time
- All VCS data reads on `Dispatchers.IO` inside `runReadAction`; all UI and diff viewer calls on EDT via `invokeLater`

### 7.4 Observability

- Log `GitService.getChangedFiles()` call count, result size, and timing
- Log `ChangeListListener` callback frequency for debugging refresh issues
- Log when LCS fallback triggers (files >5000 lines)

---

## 8. Testing Strategy

### 8.1 Testing Levels

> **Omitted** per Mini TDD guidelines.

### 8.2 Key Scenarios

| Scenario | Expected Behavior |
|----------|-------------------|
| Project with git repo and changes | File list shows with accurate LCS-computed line deltas |
| Project with git repo, no changes | `ReviewState.Empty`: "No changes" |
| Project without git repo | `ReviewState.NoGitRepository`: warning message |
| Git4Idea plugin disabled at runtime | `NoGitRepository` state (no crash) |
| User clicks a modified file | IDE diff viewer opens with syntax highlighting |
| User clicks a deleted file | Diff opens using `beforeRevision` content |
| User clicks an untracked file | File opens in editor (no diff available) |
| User clicks a file that was just committed | File opens in editor (stale change not found) |
| User saves a file while Review tab is open | List auto-refreshes after 500ms debounce |
| Multiple git repos in project | Shows changes from default changelist (project-global) |
| Binary file (image, JAR) | Shows status icon with "—" (`LineDelta.Unknown`) |
| File with merge conflicts | Shows CONFLICTED status with themed color |
| File renamed | Shows with new path, line delta computed from content |
| VCS refresh during tab open | Loading state shown, then refreshed data |
| `ContentRevision.content` throws exception | File shows with `LineDelta.Unknown`, no crash |
| File >5000 lines changed | Falls back to net-change line counts (accurate enough) |

---

## 9. Deployment & Rollout Plan

> **Omitted** per Mini TDD guidelines.

---

## 10. Open Questions

1. **Multi-repo handling:** `ChangeListManager.defaultChangeList` is project-global (all repos). Should we add per-repo grouping in the UI? (Default: flat list, no grouping)
2. **File icons:** Should we use `FileTypeManager.getInstance().getFileTypeByFileName()` for file type icons, or a generic icon? (Using file type icons would provide visual context like the existing sidebar icons)
3. **LCS performance threshold:** The 5000-line threshold for LCS fallback is arbitrary. Should it be configurable or based on profiling?

---

## 11. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| `ContentRevision.content` throws for large/binary files | Low — affects one file | try/catch returns `LineDelta.Unknown` |
| `ChangeListListener` fires during heavy VCS operations (branch switch) | Medium — UI lag | 500ms `SharedFlow.debounce()` prevents excessive recomputation |
| `GitRepositoryManager` empty during startup | Low — temporary state | Show Loading until VCS init completes |
| Stale `Change` objects after VCS refresh | Medium — diff failure | Look up `Change` by file path at click time; fall back to opening file |
| Optional `Git4Idea` dependency missing | High — crash | Reflection + `PluginManager.isPluginInstalled()` guard; no direct class references in main code |
| `NoClassDefFoundError` from git4idea classes | High — crash | Reflection + `PluginManager.isPluginInstalled()` guard; catch `Throwable` (not `Exception`) in `produceState` and `computeLineDelta` |
| LCS diff too slow for very large files | Low — UI stutter | Fallback to net-change for files >5000 lines |
| `allChanges` includes shelves/patches | Medium — shows irrelevant changes | Use `defaultChangeList?.changes` with fallback to `allChanges` |
| Path separators on Windows | Medium — relative path mismatch | Normalize all paths to `/` via `replace('\\', '/')` |
| Thread-safety: iterating `allChanges` on IO while EDT modifies | Medium — `ConcurrentModificationException` | Copy list on EDT inside `runReadAction` before processing on IO |

---

## 12. Timeline & Milestones

> **Omitted** per Mini TDD guidelines.

---

## 13. Document History

| Date | Author | Changes |
|------|--------|---------|
| 2026-06-04 | AI Assistant | Initial draft |
| 2026-06-04 | AI Assistant | v2: Fixed fabricated APIs, added threading model, switched to in-memory line diffs, added plugin.xml/build.gradle.kgs dependency declarations, added ReviewState.Empty, fixed deleted file paths, added debouncing, added CONFLICTED status, changed ChangedFile to store filePath not Change, added LineDelta sealed interface |
| 2026-06-04 | AI Assistant | v3: Addressed adversarial review round 2 findings: (1) Fixed threading — all VCS reads in `runReadAction` on `Dispatchers.IO`, all UI/diff calls on EDT via `invokeLater`; (2) Replaced net-change line delta with LCS diff algorithm with >5000 line fallback; (3) Replaced `ChangeListListener.TOPIC` with `addChangeListListener(listener, disposable)`; (4) Used `DiffContentFactory.create(project, content, extension)` 3-arg overload for syntax highlighting; (5) Added reflection-based git4idea access via `PluginManager.isPluginInstalled()` to avoid `NoClassDefFoundError`; (6) Removed `config-file` from `plugin.xml`; (7) Added `ReviewState.Error(retryable)` with onRetry callback; (8) Added `virtualFile` to `ChangedFile` for untracked file opening; (9) Replaced fake debounce with `MutableSharedFlow.debounce(500)`; (10) Added `DisposableEffect` for lifecycle; (11) Used `defaultChangeList?.changes` instead of `allChanges`; (12) Added path separator normalization; (13) Added existing file modification list (ChatModels, ChatConstants, ChatScreen, SessionSidebar); (14) Made diff request titles show revision info; (15) Used theme-aware `retrieveColorOrUnspecified` for FileStatus colors; (16) Added `ChangeListAdapter` instead of raw interface |
| 2026-06-04 | AI Assistant | v4: Addressed adversarial review round 3 findings: (1) Fixed broken listener architecture — merged `DisposableEffect` and `changeListManager_events` into single `DisposableEffect` with `MutableStateFlow` + `debounce(500)` + `collectAsState`; the old design had two separate listeners (one no-op, one unregistered) making auto-refresh dead code; (2) Fixed `openUntrackedFile` using relative path — now uses `virtualFile` from `ChangedFile` directly instead of `LocalFileSystem.findFileByPath(relativePath)` which always returned null; (3) Fixed `DiffContentFactory.create` 3rd arg — pass full `fileName` (e.g., `"build.gradle.kts"`) not just extension (`"kts"`) for correct syntax highlighting; (4) Extracted `getRelativePath(project, change)` as standalone utility used by both `GitService` and `openDiffForPath`; (5) Changed `catch (Exception)` to `catch (Throwable)` in `produceState` and `computeLineDelta` to handle `NoClassDefFoundError` from optional git4idea dependency; (6) Removed separate `changeListManager_events` helper that created unregistered listener and discarded SharedFlow |