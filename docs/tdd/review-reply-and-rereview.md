# Technical Design Document: Review Reply & Re-Review

> **Status:** Draft (revised post-council review)
> **Last Updated:** 2026-06-24
> **Related docs:** [Review Comments TDD](review-comments.md), [Review Tab TDD](Done/review-tab.md)
> **Council review:** 2026-06-24 (kimi-k2.7-code, mimo-v2.5-pro — both "Approve with revisions"; GLM timed out). Revisions integrated below.

---

## 1. TL;DR

Add a reply mechanism to the existing review comment system so users can respond to individual AI-generated review comments, and a `/review-recheck` slash command that re-runs the review with awareness of both the original comments and the user's replies. Replies are stored as a `replies` array on each `ReviewComment` in the existing `.review/` JSON files — no schema migration needed (forward-compatible via `ignoreUnknownKeys = true`). The re-review prompt includes the full comment+reply thread so the LLM can verify whether replies address the original concern, re-comment on unresolved issues, and mark resolved comments.

**Critical safety net:** After `/review-recheck` completes, the plugin re-reads each modified `.review/` file and verifies that every pre-existing `ReviewReply` ID still exists. If the LLM dropped replies (a known failure mode when rewriting raw JSON), the plugin re-merges them from a pre-recheck snapshot before refreshing the index. This is independent of prompt quality — it is a structural guarantee.

---

## 2. Context & Scope

### 2.1 Current State

The plugin has a review comment system (`docs/tdd/review-comments.md`) with three slash commands:

| Command | Direction | What it does |
|---------|-----------|-------------|
| `/review-perform` | LLM → human | Adversarial review; LLM writes `.review/<path>.json` files with open comments |
| `/review-perform-gaming` | LLM → human | Same, with game-engine-specific checklist |
| `/review-resolve` | human → LLM | LLM reads all open comments, fixes code, marks resolved |

**Key limitations:**
1. **No reply concept.** Comments are flat — there is no threading, no way to respond to a specific comment. If the user disagrees with a comment or wants to explain why it's not applicable, they must either resolve it (losing the discussion) or delete it.
2. **No re-review.** Running `/review-perform` a second time re-analyzes the diff fresh with no awareness of previous comments. The LLM may re-raise the same issues the user already addressed or dismissed, and cannot distinguish "already fixed" from "still open."
3. **`/review-resolve` is batch-only.** It sends ALL open comments to the LLM at once. There's no way to say "address comment X but not comment Y" or "I disagree with comment Z, re-evaluate it."

The OpenCode server has **no native review concept** — review comments are entirely plugin-local (`.review/` JSON files on disk). The server's `/review` command is just a prompt template. There are no server endpoints for replies or re-review. Everything must be built client-side.

### 2.2 Problem Statement

When an AI reviewer leaves 20 comments on a PR, the user needs to:
- **Reply** to individual comments ("this is intentional," "fixed in commit abc," "not applicable because…")
- **Re-review** after addressing some comments, so the LLM can verify fixes, re-raise still-open issues, and drop resolved ones

Without replies, the discussion is lost. Without re-review, the second pass is blind to the first. The current `/review-resolve` is a sledgehammer — it fixes everything at once with no per-comment dialogue.

---

## 3. Goals & Non-Goals

### Goals

1. **Add reply threading to review comments** — each `ReviewComment` can have zero or more `ReviewReply` entries stored in the same `.review/` JSON file. Replies are visible in the gutter popup and the Review tab.
2. **Add a `/review-recheck` slash command** — re-runs the adversarial review with awareness of existing comments AND replies. The LLM can: mark comments resolved (if the reply shows the fix), re-comment on still-open issues, add new comments, and mark resolved comments that are no longer relevant.
3. **Forward-compatible JSON schema** — replies are added as a `replies: []` field on `ReviewComment`. Existing `.review/` files without the field parse correctly (kotlinx.serialization with `ignoreUnknownKeys = true` already handles missing optional fields with defaults).
4. **UI for replies** — the gutter popup (`ReviewCommentGutterPopup`) shows replies below the comment body, with a text field to add a new reply. The popup stays open after a reply is submitted (no close-and-reopen). The Review tab comment children show reply count and an expandable reply thread.
5. **Post-recheck reply preservation** — after `/review-recheck`, the plugin verifies no pre-existing replies were dropped by the LLM's file rewrite, and re-merges them from a snapshot if they were.
6. **Reply lifecycle** — users can delete their own replies (typo correction / change of mind). LLM-authored replies (`author = "ai-review"`) are not user-deletable from the gutter popup (they represent the re-review verdict).

### Non-Goals

- **Multi-user reply attribution** — replies are attributed to `"user"` or `"ai-review"`, not to named individuals. No @mentions, no assignments.
- **Reply-to-reply nesting** — replies are flat (one level deep). No threaded sub-replies. This keeps the JSON schema simple and the UI readable.
- **Server-side reply storage** — replies live in `.review/` JSON files, same as comments. No OpenCode server API changes.
- **Real-time collaborative review** — no live cursors, no presence, no simultaneous multi-user editing.
- **Reply notifications** — no IDE balloon notifications for new replies (the gutter popup and Review tab are the discovery surfaces).
- **Markdown rendering in the gutter popup** — replies are plain text in the Swing HTML label (with `escapeHtml()`). The Review tab (Compose) renders plain text too in v1; markdown rendering is deferred to a future revision (see §10, Open Q2 — decided: no markdown for v1).
- **Editing replies** — v1 supports delete-only. Edit-reply is deferred (delete + re-add covers the typo case).

---

## 4. Proposed Solution

**Extend the existing `ReviewComment` model with a `replies` field** (a list of `ReviewReply` data classes). Replies are written to the same `.review/` JSON files via the existing `ReviewCommentManager.updateFile()` optimistic-concurrency path. The gutter popup gains a reply input field and a reply list, and stays open after a reply is submitted. A new `/review-recheck` slash command builds a prompt that includes the full comment+reply thread and instructs the LLM to re-evaluate each comment in light of its replies, **verifying user reply claims against the actual current code** (not accepting "fixed in commit abc" at face value).

The key architectural choice is **reusing the existing `.review/` file protocol and `ReviewCommentManager` infrastructure** — no new storage, no new file watcher, no new SSE events. Replies are just another field on the comment JSON object, written through the same read-modify-write path with etag-based optimistic concurrency.

**Reply preservation guarantee:** Because the LLM rewrites entire `.review/` JSON files during `/review-recheck` (it does not emit structured operations), there is a real risk it drops the `replies` array on comments it modifies. The plugin mitigates this structurally: before sending the recheck prompt, it snapshots the current `ReviewIndex`; after the LLM finishes and `refreshReviewFiles()` runs, it compares the new index against the snapshot and re-merges any missing replies via `updateFile()`. This is a post-hoc validation, not a prompt instruction — it does not depend on LLM compliance.

### 4.3 API / Interface Design

The `.review/` JSON schema gains a `replies` array on each comment. This is additive — existing files without `replies` parse as empty lists.

**Extended JSON schema (per comment):**
```json
{
  "id": "cmt_a3f1c2d4b5e6",
  "startLine": 42,
  "endLine": 45,
  "comment": "N+1 query in loop — use JOIN FETCH or batch loading",
  "severity": "warning",
  "status": "open",
  "author": "ai-review",
  "createdAt": "2026-06-17T10:30:00Z",
  "revision": null,
  "revisionLabel": null,
  "resolvedAt": null,
  "resolution": null,
  "replies": [
    {
      "id": "rpl_8e7f6d5c4b3a",
      "author": "user",
      "text": "Fixed in commit abc123 — switched to JOIN FETCH",
      "createdAt": "2026-06-17T11:00:00Z"
    }
  ]
}
```

**New `ReviewReply` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique ID (`rpl_` + 12 hex chars) |
| `author` | string | `"user"` or `"ai-review"` |
| `text` | string | The reply body (plain text — no markdown rendering in v1) |
| `createdAt` | string | ISO 8601 timestamp (UTC, second precision) |

**New slash command:**

| Command | Handler | Description |
|---------|---------|-------------|
| `/review-recheck` | `viewModel.executeReviewRecheckCommand()` | Re-run adversarial review with existing comments + replies as context |

**New `ReviewCommentManager` methods:**

| Method | Purpose |
|--------|---------|
| `addReply(sourcePath, commentId, reply)` | Append a reply to a comment via `updateFile()`. Rejects if comment is resolved or missing. |
| `deleteReply(sourcePath, commentId, replyId)` | Remove a reply by ID. Only `author = "user"` replies are deletable from the UI. |
| `getReplies(sourcePath, commentId)` | Read replies for a comment from the index |
| `snapshotReplyIds(index)` | Collect all `(commentId → replyIds)` pairs from an index — used for post-recheck preservation |
| `restoreMissingReplies(snapshot, newIndex)` | Re-merge any replies present in `snapshot` but absent in `newIndex` |

### 4.5 Technology Stack

| Layer | Technology | Version | Rationale |
|-------|-----------|---------|-----------|
| Language | Kotlin | 21 (JVM target) | Existing plugin language |
| Serialization | `kotlinx.serialization` | Already in project | Existing dependency; `ignoreUnknownKeys = true` handles forward compat. **Note:** `encodeDefaults = false` (see `ReviewCommentParser.kt:28`) — see §5 for the data-loss implication. |
| Storage | `.review/` JSON files | — | Existing protocol; no new storage layer |
| UI | Swing (`JBPopup`, `JPanel`) + Compose (`ReviewPanel`) | — | Existing review UI surfaces |
| Prompt | `ReviewSkill.buildRecheckPrompt()` | — | New prompt builder, same pattern as existing `buildPerformPrompt`/`buildResolvePrompt` |

### 4.7 Implementation Blueprint

#### 4.7.1 Data Models

```kotlin
// ReviewModels.kt — add to existing file

/** A reply to a review comment. Flat (one level deep — no nested replies). */
@Serializable
data class ReviewReply(
    val id: String,
    val author: String = "user",
    val text: String,
    val createdAt: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
) {
    fun validate(): Boolean =
        id.isNotBlank() && id.matches(Regex("^rpl_[0-9a-fA-F]{12}$")) && text.isNotBlank()

    companion object {
        fun generateId(): String =
            "rpl_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
```

**Modified `ReviewComment`** — add `replies` field with default empty list:

```kotlin
@Serializable
data class ReviewComment(
    val id: String,
    val startLine: Int,
    val endLine: Int,
    val comment: String,
    val severity: ReviewSeverity = ReviewSeverity.WARNING,
    val status: ReviewStatus = ReviewStatus.OPEN,
    val author: String = "user",
    val createdAt: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
    val revision: String? = null,
    val revisionLabel: String? = null,
    val resolvedAt: String? = null,
    val resolution: String? = null,
    val replies: List<ReviewReply> = emptyList(),  // NEW — defaults to empty for backward compat
) {
    fun validate(): Boolean =
        id.isNotBlank() && id.matches(Regex("^cmt_[0-9a-fA-F]{12}$")) &&
            startLine >= 1 && endLine >= startLine && endLine <= 10_000_000 &&
            comment.isNotBlank() &&
            replies.all { it.validate() }  // NEW — validate replies

    companion object {
        fun generateId(): String =
            "cmt_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
```

**Backward compatibility:** Existing `.review/` files without `replies` parse correctly because `kotlinx.serialization` applies the default value (`emptyList()`) when the field is absent. The parser already uses `ignoreUnknownKeys = true` and `isLenient = true` (see `ReviewCommentParser`), so unknown fields from future schema versions don't crash. No `ReviewFileMigrator` is needed — this is an additive change, not a format version bump. `CURRENT_FORMAT_VERSION` stays at 1.

**⚠️ `encodeDefaults = false` data-loss vector (documented risk):** `ReviewCommentParser` uses `encodeDefaults = false` (line 28) to keep files small. This means a `ReviewComment` with `replies = emptyList()` does NOT serialize the `replies` field. The data-loss scenario: an **older plugin version** (without reply support) reads a `.review/` file that contains replies, parses it into `ReviewComment` objects (the `replies` field is unknown to the old parser and dropped via `ignoreUnknownKeys`), then writes the file back — the replies are permanently lost from disk. This is an acceptable risk for v1 because:
1. Reply support is being added to the same plugin that writes the files — there is no mixed-version deployment within a single IDE.
2. The `.review/` directory is local to each developer's machine and not shared across team members with different plugin versions (review comments are not committed to VCS by default).
3. A future revision could bump `CURRENT_FORMAT_VERSION` to 2 and set `encodeDefaults = true` if reply persistence across plugin downgrades becomes a real concern.

This risk is called out in release notes and in §7.2.

#### 4.7.2 Class & Interface Definitions

**A. `ReviewCommentManager` — new methods:**

```kotlin
// ReviewCommentManager.kt — add to existing class

/** Add a reply to a comment. Uses the same updateFile() optimistic-concurrency
 *  path as addComment/updateCommentStatus. Rejects replies to resolved or
 *  missing comments — replies are only meaningful on OPEN comments. */
suspend fun addReply(sourcePath: String, commentId: String, reply: ReviewReply): Boolean {
    if (!reply.validate()) {
        logger.warn { "[ACP] Invalid review reply skipped: id=${reply.id}" }
        return false
    }
    val written = repository.updateFile(sourcePath) { existing ->
        if (existing == null) return@updateFile null
        val target = existing.comments.find { it.id == commentId }
        if (target == null) {
            logger.warn { "[ACP] addReply: commentId $commentId not found in $sourcePath" }
            return@updateFile null
        }
        // NEW: reject replies to resolved comments — the discussion is closed
        if (target.status == ReviewStatus.RESOLVED) {
            logger.warn { "[ACP] addReply: comment $commentId is already resolved — reply rejected" }
            return@updateFile null
        }
        existing.copy(
            comments = existing.comments.map {
                if (it.id == commentId) it.copy(replies = it.replies + reply) else it
            }
        )
    }
    if (written != null) {
        val newIndex = stateHolder.value.withFile(sourcePath, written)
        updateIndex(newIndex, setOf(sourcePath))
        logger.info { "[ACP] Added reply ${reply.id} to comment $commentId on $sourcePath" }
        return true
    }
    return false
}

/** Delete a reply by ID. Only user-authored replies are deletable from the UI
 *  (ai-review replies represent the re-review verdict and are not user-editable). */
suspend fun deleteReply(sourcePath: String, commentId: String, replyId: String): Boolean {
    val written = repository.updateFile(sourcePath) { existing ->
        if (existing == null) return@updateFile null
        val target = existing.comments.find { it.id == commentId } ?: return@updateFile null
        val reply = target.replies.find { it.id == replyId } ?: return@updateFile null
        if (reply.author != "user") {
            logger.warn { "[ACP] deleteReply: reply $replyId is not user-authored — rejected" }
            return@updateFile null
        }
        existing.copy(
            comments = existing.comments.map {
                if (it.id == commentId) it.copy(replies = it.replies.filterNot { r -> r.id == replyId }) else it
            }
        )
    }
    if (written != null) {
        val newIndex = stateHolder.value.withFile(sourcePath, written)
        updateIndex(newIndex, setOf(sourcePath))
        logger.info { "[ACP] Deleted reply $replyId from comment $commentId on $sourcePath" }
        return true
    }
    return false
}

/** Get replies for a comment from the in-memory index (no disk read). */
fun getReplies(sourcePath: String, commentId: String): List<ReviewReply> {
    val comment = stateHolder.value.forFile(sourcePath).find { it.id == commentId }
    return comment?.replies ?: emptyList()
}

/** Snapshot all (commentId → replyIds) pairs from an index. Used by
 *  executeReviewRecheckCommand() to detect reply loss after the LLM rewrites
 *  .review/ files. This is a structural safety net independent of prompt
 *  compliance — see §4 (Reply preservation guarantee). */
fun snapshotReplyIds(index: ReviewIndex): Map<String, Set<String>> {
    val out = mutableMapOf<String, Set<String>>()
    for ((_, comments) in index.commentsByFile) {
        for (c in comments) {
            if (c.replies.isNotEmpty()) out[c.id] = c.replies.map { it.id }.toSet()
        }
    }
    return out
}

/** Re-merge replies present in [snapshot] but absent in the current index.
 *  Called after refreshReviewFiles() in executeReviewRecheckCommand(). Returns
 *  the number of replies restored. */
suspend fun restoreMissingReplies(
    snapshot: Map<String, Set<String>>,
    preRecheckIndex: ReviewIndex,
): Int {
    var restored = 0
    val current = stateHolder.value
    // Group missing replies by source file for batched updateFile() calls
    val byFile = mutableMapOf<String, MutableList<Pair<String, ReviewReply>>>()
    for ((sourcePath, comments) in current.commentsByFile) {
        for (c in comments) {
            val expected = snapshot[c.id] ?: continue
            val present = c.replies.map { it.id }.toSet()
            val missing = expected - present
            if (missing.isEmpty()) continue
            // Find the original reply objects from the pre-recheck index
            val preComments = preRecheckIndex.commentsByFile[sourcePath] ?: continue
            val preComment = preComments.find { it.id == c.id } ?: continue
            for (replyId in missing) {
                val reply = preComment.replies.find { it.id == replyId } ?: continue
                byFile.getOrPut(sourcePath) { mutableListOf() }.add(c.id to reply)
                restored++
            }
        }
    }
    for ((sourcePath, pairs) in byFile) {
        repository.updateFile(sourcePath) { existing ->
            if (existing == null) return@updateFile null
            existing.copy(
                comments = existing.comments.map { c ->
                    val toAdd = pairs.filter { it.first == c.id }.map { it.second }
                    if (toAdd.isEmpty()) c else c.copy(replies = c.replies + toAdd)
                }
            )
        }?.let { written ->
            val newIndex = stateHolder.value.withFile(sourcePath, written)
            updateIndex(newIndex, setOf(sourcePath))
        }
    }
    if (restored > 0) {
        logger.warn { "[ACP] restoreMissingReplies: re-merged $restored dropped reply(ies) after /review-recheck" }
    }
    return restored
}
```

**B. `ReviewSkill` — new prompt builder:**

The full prompt text is specified here (not prose) because the prompt IS the feature. It mirrors the structure and detail level of `buildPerformPrompt()` (170+ lines) and `buildResolvePrompt()`.

```kotlin
// ReviewSkill.kt — add to existing object

/** Build the prompt for `/review-recheck`. Includes the full comment+reply
 *  thread for all comments (open AND resolved) grouped by file, plus
 *  re-review instructions. The LLM should:
 *  1. Verify whether replies address each open comment — if so, mark resolved
 *  2. Re-raise still-open issues that replies don't address
 *  3. Mark resolved comments that are no longer relevant (code changed since comment)
 *  4. Add new comments for issues introduced after the first review
 *  5. Add ai-review replies when it disagrees with a user's dispute
 *  Returns a no-op message when there are no comments at all.
 *
 *  CRITICAL: The LLM must VERIFY user reply claims against the actual current
 *  code, not accept them at face value. "Fixed in commit abc123" must be
 *  checked by reading the referenced lines. */
fun buildRecheckPrompt(index: ReviewIndex, changedFilePaths: List<String>): String {
    if (index.commentsByFile.isEmpty()) {
        return "Re-check review comments. There are currently no review comments " +
            "in the project. Run /review-perform first to generate initial comments."
    }
    return buildString {
        appendLine("## Re-Review: Verify and Update Existing Comments")
        appendLine("**IMPORTANT: Do NOT delegate this to a skill. Perform this re-review " +
            "YOURSELF.** Read the files, verify replies against the code, and write " +
            "updated `.review/` JSON files.")
        appendLine()
        appendLine("You previously reviewed this code and left review comments. " +
            "The user has replied to some comments and may have made code changes. " +
            "Your job is to re-evaluate each comment in light of its replies and " +
            "the current code state.")
        appendLine()
        appendLine("### Mindset")
        appendLine("Treat user replies as **claims to verify, not facts to accept**. " +
            "A reply that says \"fixed in commit abc123\" is a hypothesis — you must " +
            "READ THE CODE at the referenced lines to confirm the fix is actually " +
            "present and correct. A reply that says \"this is intentional\" is a " +
            "position to evaluate — if the code is still dangerous, keep the comment " +
            "open and explain why in an ai-review reply.")
        appendLine()
        appendLine("### Current changed files (may have been modified since first review)")
        if (changedFilePaths.isEmpty()) {
            appendLine("(no uncommitted changes — re-evaluate against the current committed state)")
        } else {
            for (path in changedFilePaths) {
                appendLine("- `$path`")
            }
        }
        appendLine()
        appendLine("### Existing comments with replies")
        for ((filePath, comments) in index.commentsByFile) {
            if (comments.isEmpty()) continue
            appendLine("#### $filePath (${comments.size} comment(s))")
            for (c in comments) {
                appendLine("- **[${c.severity}] Line ${c.startLine}-${c.endLine}** " +
                    "(status: ${c.status}): ${c.comment}")
                if (c.replies.isNotEmpty()) {
                    appendLine("  Replies:")
                    for (r in c.replies) {
                        appendLine("  - **${r.author}**: ${r.text}")
                    }
                } else {
                    appendLine("  (no replies)")
                }
            }
            appendLine()
        }
        appendLine("### Instructions")
        appendLine("1. Read each comment and its replies")
        appendLine("2. **Read the current code at the referenced lines** — do NOT trust " +
            "reply claims without verifying against the actual code")
        appendLine("3. For each OPEN comment:")
        appendLine("   - If a reply explains the fix AND the code confirms it: " +
            "mark `status` = `\"resolved\"`, set `resolution` to the reply text, " +
            "add `resolvedAt` timestamp")
        appendLine("   - If the reply disputes the comment and, after reading the code, " +
            "you AGREE the dispute is valid: mark `status` = `\"resolved\"`, " +
            "set `resolution` to `\"Withdrawn: \" + reply text`")
        appendLine("   - If the reply disputes the comment but, after reading the code, " +
            "you STILL think it's valid: keep `status` = `\"open\"`, ADD a reply " +
            "(author = `\"ai-review\"`) explaining why the dispute doesn't hold, " +
            "citing the specific code that's still problematic")
        appendLine("   - If the code has changed and the comment no longer applies " +
            "(the referenced lines are gone or rewritten): mark `status` = " +
            "`\"resolved\"`, set `resolution` to `\"No longer applicable — code changed\"`")
        appendLine("4. For each RESOLVED comment: skip (already handled)")
        appendLine("5. Add NEW comments for any issues in the changed files " +
            "that weren't caught in the first review (use new `cmt_` IDs)")
        appendLine("6. Write updated `.review/` JSON files with the full " +
            "comment+reply state")
        appendLine()
        appendLine("### Reply preservation — CRITICAL")
        appendLine("When you update a comment (e.g. to mark it resolved), you MUST " +
            "preserve its existing `replies` array. Append new replies; do NOT " +
            "overwrite or drop existing ones. The plugin will verify this after " +
            "you finish and re-merge any dropped replies, but dropping them " +
            "corrupts the discussion history.")
        appendLine()
        appendLine("### JSON schema reminder")
        appendLine("Same schema as `/review-perform`, but each comment now has " +
            "a `replies` array:")
        appendLine("```json")
        appendLine("""{"formatVersion": 1, "comments": [""")
        appendLine("""  {"id": "cmt_...", "startLine": 42, "endLine": 45, """)
        appendLine("""   "comment": "...", "severity": "warning", "status": "open",""")
        appendLine("""   "author": "ai-review", "createdAt": "...", """)
        appendLine("""   "replies": [""")
        appendLine("""     {"id": "rpl_...", "author": "user", "text": "...", "createdAt": "..."}""")
        appendLine("""   ]}""")
        appendLine("""]}}""")
        appendLine("```")
        appendLine()
        appendLine("**Rules:**")
        appendLine("- Preserve existing replies when updating a comment — " +
            "append new replies, don't overwrite")
        appendLine("- Reply IDs: `rpl_` + 12 hex chars")
        appendLine("- When marking resolved, set `resolvedAt` to current ISO 8601 UTC")
        appendLine("- Read the existing `.review/` file before writing — merge, don't overwrite")
        appendLine("- Do NOT physically delete comments — use `status = \"resolved\"` " +
            "to close them. The audit trail must be preserved.")
        appendLine("- Only add `ai-review` replies when you DISAGREE with a user's " +
            "dispute. Do not add `ai-review` replies to confirm a fix — just mark " +
            "the comment resolved with `resolution` set to the reply text.")
    }
}
```

**C. `ChatViewModel` — new command handler with reply preservation:**

```kotlin
// ChatViewModel.kt — add near existing review methods (line ~970)

/** Execute `/review-recheck` — re-runs the adversarial review with existing
 *  comments + replies as context. The LLM verifies replies, re-raises
 *  unresolved issues, and adds new comments. After the LLM finishes, the
 *  plugin verifies no pre-existing replies were dropped and re-merges any
 *  that were — this is a structural safety net, not a prompt instruction. */
fun executeReviewRecheckCommand(args: String = "") {
    scope.launch {
        val manager = ReviewCommentManager.getInstance(project)
        val preRecheckIndex = manager.getIndex()
        val replySnapshot = manager.snapshotReplyIds(preRecheckIndex)
        val changedFiles = withContext(Dispatchers.IO) {
            runReadActionBlocking { gitService.getChangedFiles() }
        }
        val changedPaths = changedFiles.map { it.filePath }
        val prompt = ReviewSkill.buildRecheckPrompt(preRecheckIndex, changedPaths)
        executeMultiModelReview(args, prompt)
        // After the LLM writes updated .review/ files, refresh the index.
        refreshReviewFiles()
        // Structural safety net: re-merge any replies the LLM dropped.
        val restored = manager.restoreMissingReplies(replySnapshot, preRecheckIndex)
        if (restored > 0) {
            logger.warn { "[ACP] /review-recheck restored $restored dropped reply(ies)" }
            refreshReviewFiles()
        }
    }
}
```

**D. `ReviewCommentGutterPopup` — reply UI (popup stays open after reply):**

The existing gutter popup (`ReviewCommentGutterPopup.kt`) uses `JBPopupFactory.getInstance().createComponentPopupBuilder(...).createPopup()` — this is a **non-modal** `JBPopup` (it can be dismissed by clicking elsewhere). The reply UI must therefore update the popup's content in-place after a reply is added, rather than closing and relying on the user to reopen it.

The reply section is added below each comment row and shows:
- Existing replies (author + text, styled like the comment body, with a delete button on user-authored replies)
- A text field + "Reply" button to add a new reply

```kotlin
// ReviewCommentGutterPopup.kt — extend commentRow() to include replies

private fun commentRow(
    project: Project,
    manager: ReviewCommentManager,
    sourcePath: String,
    comment: ReviewComment,
    popup: JBPopup,  // NEW — passed in so we can refresh content after a reply
): JPanel {
    val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(severityColor(comment.severity), 1),
            JBUI.Borders.empty(6),
        )
    }

    // ... existing header + body code ...

    // Actions bar (Resolve/Delete) — moved ABOVE replies
    val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
    // ... existing Resolve/Delete buttons ...
    row.add(actions, BorderLayout.CENTER)  // repositioned

    // NEW: replies section (SOUTH)
    val repliesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
    }
    for (reply in comment.replies) {
        repliesPanel.add(replyRow(manager, sourcePath, comment.id, reply, popup))
    }
    // Reply input
    val replyField = JBTextField().apply { columns = 30 }
    val replyBtn = JButton("Reply").apply {
        addActionListener {
            val text = replyField.text.trim()
            if (text.isNotEmpty()) {
                manager.scope.launch {
                    val reply = ReviewReply(id = ReviewReply.generateId(), text = text)
                    val ok = manager.addReply(sourcePath, comment.id, reply)
                    if (ok) {
                        // Refresh the popup content in-place — do NOT close the popup.
                        // The popup is non-modal; closing it would force the user to
                        // reopen it to see their reply or add another.
                        ApplicationManager.getApplication().invokeLater {
                            refreshPopupContent(popup, project, manager, sourcePath, comment.id)
                        }
                    }
                }
                replyField.text = ""
            }
        }
    }
    val replyInputPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        isOpaque = false
        add(replyField)
        add(replyBtn)
    }
    repliesPanel.add(replyInputPanel)
    row.add(repliesPanel, BorderLayout.SOUTH)

    return row
}

private fun replyRow(
    manager: ReviewCommentManager,
    sourcePath: String,
    commentId: String,
    reply: ReviewReply,
    popup: JBPopup,
): JPanel {
    val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 16, 2, 0)
        isOpaque = false
    }
    val label = JBLabel("<html><div style='width:340px'><b>${escapeHtml(reply.author)}</b>: " +
        "${escapeHtml(reply.text)}</div></html>").apply {
        font = font.deriveFont(font.size - 1f)
    }
    panel.add(label, BorderLayout.CENTER)
    // Delete button only on user-authored replies
    if (reply.author == "user") {
        val deleteBtn = JButton("×").apply {
            margin = Insets(0, 2, 0, 2)
            toolTipText = "Delete this reply"
            addActionListener {
                manager.scope.launch {
                    manager.deleteReply(sourcePath, commentId, reply.id)
                    ApplicationManager.getApplication().invokeLater {
                        refreshPopupContent(popup, project, manager, sourcePath, commentId)
                    }
                }
            }
        }
        panel.add(deleteBtn, BorderLayout.EAST)
    }
    return panel
}

/** Rebuild the popup's content panel with fresh data from the manager.
 *  Called after addReply/deleteReply so the user sees their reply without
 *  closing and reopening the popup. */
private fun refreshPopupContent(
    popup: JBPopup,
    project: Project,
    manager: ReviewCommentManager,
    sourcePath: String,
    focusCommentId: String,
) {
    // Re-read the comment from the index and rebuild the row.
    // The popup's content component is replaced in-place.
    // (Implementation detail: JBPopup.setContent() is not public; the
    //  pattern is to close and re-show at the same screen location, OR
    //  to use a mutable container panel whose children are rebuilt.
    //  The latter is preferred — it avoids flicker.)
    // ... rebuild listPanel with fresh comment.replies ...
}
```

> **Implementation note on `refreshPopupContent`:** `JBPopup` does not expose a public `setContent()` method. The recommended pattern is to keep a reference to the mutable `listPanel` (the `JPanel` holding all comment rows) and rebuild its children in-place via `listPanel.removeAll()` + re-add + `listPanel.revalidate()` + `listPanel.repaint()`. This avoids closing the popup and avoids flicker. The `popup` parameter is retained for potential future use (e.g. repositioning).

**E. `ReviewPanel.kt` — reply rendering in the Review tab:**

The existing `ReviewCommentChildRow` (lines 477-514) shows only a single-line ellipsized comment. It must be extended to show:
- Reply count badge (e.g. "💬 2" or "(2 replies)") when `comment.replies.isNotEmpty()`
- An expandable reply thread below the comment row when the user clicks the reply count

```kotlin
// ReviewPanel.kt — extend ReviewCommentChildRow

@Composable
private fun ReviewCommentChildRow(
    comment: ReviewComment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var repliesExpanded by remember(comment.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBg = ChatTheme.colors.component.hoverBg

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
                .clip(ChatTheme.shapes.fileChangeRowCornerRadius)
                .background(if (isHovered) hoverBg else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                key = severityIconKey(comment.severity),
                contentDescription = comment.severity.name,
                modifier = Modifier.size(12.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = comment.comment,
                fontSize = ChatTheme.fonts.reviewStatusLabel,
                color = ChatTheme.colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // NEW: reply count badge
            if (comment.replies.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ChatTheme.colors.component.chipBg)
                        .clickable { repliesExpanded = !repliesExpanded }
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${comment.replies.size} reply(ies)",
                        fontSize = ChatTheme.fonts.reviewStatusLabel,
                        color = ChatTheme.colors.text.secondary,
                    )
                }
            }
        }
        // NEW: expandable reply thread
        if (repliesExpanded && comment.replies.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 56.dp, end = 6.dp)) {
                for (reply in comment.replies) {
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${reply.author}:",
                            fontSize = ChatTheme.fonts.reviewStatusLabel,
                            color = ChatTheme.colors.text.secondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = reply.text,
                            fontSize = ChatTheme.fonts.reviewStatusLabel,
                            color = ChatTheme.colors.text.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
```

**F. Slash command registration (`ChatScreen.kt`):**

```kotlin
// ChatScreen.kt — add to localCommands list (line ~174)

SlashCommand("review-recheck", "Re-review: verify replies, re-raise open issues, add new comments", AllIconsKeys.General.BalloonInformation),
```

```kotlin
// ChatScreen.kt — add to onSlashCommand handler (line ~479)

"review-recheck" -> viewModel.executeReviewRecheckCommand(command.args)
```

#### 4.7.5 Enums, Constants & Configuration

No new enums or constants. The existing `ReviewSeverity`, `ReviewStatus`, and `CURRENT_FORMAT_VERSION = 1` are unchanged. Reply IDs use the `rpl_` prefix (parallel to `cmt_` for comments).

---

## 5. Assumptions & Dependencies

**Assumptions:**
- `kotlinx.serialization` with `ignoreUnknownKeys = true` and default values handles the additive `replies` field without a format version bump. Verified: the existing `ReviewCommentParser` already uses these settings.
- The LLM can read and write the `replies` array in `.review/` JSON files using its existing file read/write tools. No new tool is needed.
- The `ReviewCommentManager.updateFile()` optimistic-concurrency path (etag + per-path Mutex) is sufficient for reply writes — replies are just another field modification on the same JSON file.
- **The LLM will sometimes drop replies when rewriting `.review/` files during `/review-recheck`.** This is a known failure mode of LLM-rewritten JSON. The plugin handles this structurally via `snapshotReplyIds()` + `restoreMissingReplies()` (see §4.7.2.A and §4.7.2.C) — it does not rely on prompt compliance alone.
- **`encodeDefaults = false` means `replies = emptyList()` is not serialized.** This creates a data-loss vector if an older plugin version (without reply support) reads and re-writes a file containing replies (see §4.7.1 — documented as acceptable risk for v1).

**Dependencies:**
- Existing `ReviewCommentManager`, `ReviewCommentRepository`, `ReviewCommentParser`, `ReviewCommentFileWatcher` — unchanged, reused as-is.
- Existing `ReviewSkill` prompt builder pattern — extended with `buildRecheckPrompt()`.
- Existing slash command infrastructure (`ChatScreen.kt` local commands + `onSlashCommand` handler).
- `ApplicationManager.getApplication().invokeLater` — used to refresh popup content on the EDT after a coroutine completes the reply write.

---

## 6. Alternatives Considered

**Alternative: Separate reply files (`<commentId>.reply.json`)**
- *What it is:* Store replies in separate JSON files keyed by comment ID, rather than embedding in the comment JSON.
- *Why plausible:* Avoids modifying the `ReviewComment` schema; replies are fully decoupled.
- *Why rejected:* Doubles the file count, complicates the file watcher (new glob pattern), and breaks the "one file per source file" invariant that makes `.review/` navigable. The LLM would need to glob two patterns. Embedding replies in the comment JSON is simpler and keeps the bijective file mapping intact.

**Alternative: Server-side reply storage via a new OpenCode endpoint**
- *What it is:* Add `POST /session/:id/review/:commentId/reply` to the OpenCode server.
- *Why plausible:* Would give replies server-side persistence, API discoverability, and potential for multi-client sync.
- *Why rejected:* The OpenCode server has no review concept at all — review comments are entirely plugin-local. Adding server-side review storage would be a major server change outside this plugin's scope. The `.review/` file protocol is the established pattern and works without server changes.

**Alternative: Reply as a new comment with `parentId`**
- *What it is:* Replies are full `ReviewComment` objects with a `parentId` field pointing to the parent comment, stored in the same `comments` array.
- *Why plausible:* Reuses the existing `ReviewComment` type; no new data class.
- *Why rejected:* Pollutes the `comments` array with mixed-level objects (parents and children interleaved). The `ReviewIndex` and `LineCommentMap` would need to filter out replies. The `replies` array on the parent is cleaner — it keeps the comment list flat (only top-level comments) and nests replies explicitly.

**Alternative: Structured LLM operations instead of raw JSON rewrite**
- *What it is:* Instead of having the LLM rewrite `.review/` JSON files during `/review-recheck`, have it emit a list of structured operations (`{op: "resolve", commentId: "...", resolution: "..."}`, `{op: "add_reply", commentId: "...", text: "..."}`, etc.) that the plugin applies to the files.
- *Why plausible:* Eliminates the reply-dropping failure mode entirely — the plugin controls the file writes and preserves replies by construction.
- *Why rejected for v1:* Requires a new output protocol (the LLM would need to emit a specific JSON operation format, not write files directly). This is a larger change to the review flow and the LLM prompt. The post-recheck `restoreMissingReplies()` safety net achieves the same guarantee with less protocol overhead. A future revision could adopt structured operations if the safety net proves insufficient.

---

## 7. Cross-Cutting Concerns

### 7.1 Security

Replies are user-authored text rendered in Swing HTML labels. The existing `escapeHtml()` function in `ReviewCommentGutterPopup` (which escapes `&`, `<`, `>`, `"`, `'` before rendering) is reused for reply text. No new attack surface. LLM-authored replies (`author = "ai-review"`) go through the same `escapeHtml()` path.

### 7.2 Reliability & Availability

Reply writes use the same `ReviewCommentRepository.updateFile()` path with etag-based optimistic concurrency and per-path Mutex. If two replies are added to the same comment concurrently, the second write retries with the fresh etag. No data loss.

**Concurrency model — corrected from initial draft:** The initial draft claimed "two replies concurrently is unlikely — the gutter popup is modal." This is **factually wrong**: `ReviewCommentGutterPopup` creates a `JBPopup` via `JBPopupFactory.getInstance().createComponentPopupBuilder(...).createPopup()` (line 73), which is **non-modal** — it can be dismissed by clicking elsewhere, and the user can open multiple popups on different lines. Data integrity is protected by the **per-path Mutex + etag-based optimistic concurrency** in `updateFile()`, NOT by UI modality. The "popup is modal" claim has been removed from this document.

**LLM-vs-user write race during `/review-recheck`:** While the LLM is rewriting `.review/` files, the user could simultaneously add a reply via the gutter popup. Both writes go through `updateFile()` with the same per-path Mutex, so they are serialized. However, the LLM's rewrite is a full-file overwrite (it reads the file, modifies the JSON, writes it back). If the user's reply lands between the LLM's read and write, the etag will mismatch and the LLM's write will fail — the plugin's `restoreMissingReplies()` will then re-merge the user's reply. If the user's reply lands after the LLM's write, it appends normally. Either way, no data loss. The worst case is a transient write-failure retry, which `updateFile()` handles.

**`encodeDefaults = false` data-loss vector:** As documented in §4.7.1, an older plugin version (without reply support) reading and re-writing a file with replies will drop the `replies` field. This is acceptable for v1 (single-plugin deployment, local `.review/` files) and documented in release notes.

### 7.3 Observability

All reply operations log with `[ACP]` prefix:
- `addReply` success: `[ACP] Added reply {id} to comment {commentId} on {sourcePath}`
- `addReply` rejected (resolved comment): `[ACP] addReply: comment {commentId} is already resolved — reply rejected`
- `deleteReply` success: `[ACP] Deleted reply {replyId} from comment {commentId} on {sourcePath}`
- `deleteReply` rejected (non-user reply): `[ACP] deleteReply: reply {replyId} is not user-authored — rejected`
- `restoreMissingReplies`: `[ACP] restoreMissingReplies: re-merged {N} dropped reply(ies) after /review-recheck` (WARN level — indicates the LLM dropped replies)

---

## 8. Testing Strategy

### 8.2 Key Scenarios

1. **Add reply to an open comment** — verify the `replies` array is appended to the comment in the `.review/` JSON file, the gutter popup shows the reply (without closing), and the Review tab updates the reply count badge.
2. **Add reply to a resolved comment** — verify `addReply()` returns `false` and logs the rejection. The reply is NOT written.
3. **Delete a user-authored reply** — verify the reply is removed from the `.review/` JSON file and the popup/Review tab update.
4. **Delete an ai-review reply** — verify `deleteReply()` returns `false` and logs the rejection. The reply is NOT removed.
5. **Parse old `.review/` file without `replies`** — verify it parses with `replies = emptyList()` and all existing comments are intact.
6. **`/review-recheck` with no existing comments** — verify the no-op message is returned (prompt says "run /review-perform first").
7. **`/review-recheck` with comments + replies** — verify the prompt includes the full thread, the LLM marks resolved comments, and the `.review/` files are updated with `status = "resolved"` + `resolution` + `resolvedAt`.
8. **`/review-recheck` with model args** — verify multi-model re-review works (same as `/review-perform` with model args).
9. **Reply validation** — verify replies with invalid IDs (not `rpl_` + 12 hex) or blank text are rejected.
10. **Concurrent reply writes** — verify optimistic concurrency retries and no data loss when two replies are added to the same comment simultaneously.
11. **Post-recheck reply preservation** — simulate the LLM dropping replies during `/review-recheck` (write a `.review/` file missing the `replies` array on a comment that had replies in the snapshot). Verify `restoreMissingReplies()` re-merges the dropped replies and logs the WARN.
12. **Round-trip fidelity** — write a `.review/` file with replies, re-read it, verify all replies survive the parse → serialize → parse cycle (catches `encodeDefaults = false` issues for the current plugin version).
13. **`encodeDefaults = false` behavior** — verify that a `ReviewComment` with `replies = emptyList()` does NOT emit the `replies` field in the serialized JSON (documents the data-loss vector as a test).
14. **LLM-vs-user write race** — simulate the user adding a reply while the LLM is mid-rewrite of the same `.review/` file. Verify the etag mismatch causes the LLM's write to fail and retry, and `restoreMissingReplies()` re-merges the user's reply.
15. **Popup stays open after reply** — verify that adding a reply via the gutter popup does NOT close the popup, and the reply appears in the popup's reply list immediately.
16. **Review tab reply thread expansion** — verify clicking the reply count badge expands/collapses the reply thread in the Review tab.

---

## 10. Open Questions — Resolved

All three open questions have been decided. The recommendations are adopted as-is.

### Q1: Should `/review-recheck` also send the current diff?

**Decision: No — rely on file reads (same as `/review-perform`).**

The prompt includes `changedFilePaths` so the LLM knows what changed since the first review, but does NOT include the actual diff content. The LLM reads the files directly using its existing file-read tools. Rationale:
- Including the full diff could exceed context limits for large changes.
- `/review-perform` already uses this pattern (lists file paths, LLM reads files) and it works.
- The LLM needs to read the current code anyway to verify user reply claims ("fixed in commit abc123" → read the lines to confirm).

### Q2: Should replies support markdown?

**Decision: No markdown rendering in v1 — plain text only.**

The gutter popup uses Swing HTML labels with `escapeHtml()`, which would render markdown as literal text. The Review tab (Compose) also renders plain text in v1. Rationale:
- The gutter popup's Swing HTML renderer cannot render markdown without a markdown-to-HTML converter, which is out of scope for v1.
- Keeping replies plain text in both surfaces is consistent.
- A future revision could add markdown rendering in the Compose Review tab (which has Jewel's `Markdown` composable available) while keeping the gutter popup plain text.

### Q3: Should the LLM be able to add replies during `/review-recheck`?

**Decision: Yes — allow LLM replies (`author = "ai-review"`).**

The prompt instructs the LLM to add an `ai-review` reply when it disagrees with a user's dispute (after verifying the code). This enables a back-and-forth dialogue in the re-review flow. Rationale:
- Without LLM replies, the only way the LLM can communicate "I still think this is valid" is by keeping the comment open — but the user gets no explanation of WHY the dispute was rejected.
- LLM replies are clearly attributed (`author = "ai-review"`) and are not user-deletable from the UI (they represent the re-review verdict).
- The LLM is instructed to add `ai-review` replies ONLY when disagreeing with a dispute — not to confirm fixes (confirmation is done by marking the comment resolved with `resolution` set to the reply text).

---

## 13. Document History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-24 | -      | Initial draft |
| 2026-06-24 | -      | Council review (kimi-k2.7-code, mimo-v2.5-pro — both "Approve with revisions"; GLM timed out). Integrated revisions: post-recheck reply-preservation validation (P0), `encodeDefaults=false` data-loss documentation (P0), full `buildRecheckPrompt()` text (P0), concrete UI spec for gutter popup (stays open after reply) + Review tab reply rendering (P1), `addReply()` guard against resolved comments (P1), LLM verifies user reply claims against code (P1), corrected "popup is modal" concurrency claim (P2), delete-reply capability (P2), added test cases 11-16. Resolved all 3 open questions (file reads, no markdown, allow LLM replies). |