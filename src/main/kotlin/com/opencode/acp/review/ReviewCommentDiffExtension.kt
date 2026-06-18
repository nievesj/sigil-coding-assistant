package com.opencode.acp.review

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce

/**
 * [DiffExtension] that annotates the after-side editor in diff viewers with
 * review comment highlights + gutter icons.
 *
 * Registered via the `com.intellij.diff.DiffExtension` extension point. Fires
 * [onViewerCreated] once the viewer is fully constructed — no timing race with
 * asynchronously-created viewers (the previous draft's
 * `DiffManager.getDiffViewer()` approach raced viewer construction).
 *
 * ## API pitfalls avoided (documented in TDD §4.3)
 *
 * - **File path**: recovered from `DiffRequest` user data
 *   ([REVIEW_PATH_KEY], set by `openDiffForPath` in ReviewPanel) — NOT from
 *   `ContentDiffRequest.contentTitles`, which are display labels ("Working",
 *   "Local Changes"), not file paths. Falls back to the diff content's
 *   `DocumentContent.getHighlightFile()` when user data is absent (e.g. diff
 *   opened outside the Review tab).
 * - **Project**: read from `context.project`, NOT `editor.project` — transient
 *   diff editors frequently have a null project association.
 * - **Viewer resolution**: dispatched by type (`TwosideTextDiffViewer` →
 *   `getEditor(Side.RIGHT)`, `UnifiedDiffViewer` → `getEditor()`,
 *   `SimpleOnesideDiffViewer` → `getEditor()`), unknown types skipped.
 * - **Cleanup**: registered against the viewer (which implements `Disposable`)
 *   via [Disposer.register] — `DiffExtension` has no `onViewerDisposed` hook.
 */
class ReviewCommentDiffExtension : DiffExtension() {

    @OptIn(FlowPreview::class)
    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        // Fast-path: if no REVIEW_PATH_KEY is set, this diff wasn't opened
        // from the Review tab. There is no fallback path (Phase 2 enhancement —
        // see class doc). Skip early to avoid wasted work on every diff open.
        if (request.getUserData(REVIEW_PATH_KEY) == null) return

        // Resolve the editor based on viewer type.
        val editor: EditorEx? = when (viewer) {
            is TwosideTextDiffViewer -> viewer.getEditor(com.intellij.diff.util.Side.RIGHT)
            is UnifiedDiffViewer -> viewer.getEditor()
            is SimpleOnesideDiffViewer -> viewer.getEditor()
            else -> return  // unknown viewer type — nothing to annotate
        }
        if (editor == null) return

        // Project from context.project (NOT editor.project — null in transient diff editors).
        val project = context.project ?: return
        if (project.isDisposed) return

        // File path from DiffRequest user data (set by openDiffForPath in ReviewPanel).
        // Diffs opened OUTSIDE the Review tab have no REVIEW_PATH_KEY and were already
        // filtered out by the line-53 guard — there is no fallback path here. Annotating
        // arbitrary diffs (Cmd+D, VCS-show-diff) requires recovering a VirtualFile from
        // the diff content, which is a Phase 2 enhancement (the TDD's
        // `extractRelativePathFromContent` approach via `DocumentContent.highlightFile`
        // — see TDD §4.3). For now, only Review-tab diffs get annotations.
        val filePath = request.getUserData(REVIEW_PATH_KEY) ?: return

        val comments = ReviewCommentManager.getInstance(project).getIndex().openForFile(filePath)
        if (comments.isEmpty()) return

        // Apply highlights to the resolved editor. The viewer implements
        // Disposable, so registering cleanup against it ensures the
        // highlights are removed when the diff viewer is disposed.
        EditorHighlightSupport.addHighlights(editor, comments, viewer)

        // Re-apply highlights when the index changes (new comments added/deleted while diff is open)
        // NOTE: Each diff viewer creates its own CoroutineScope. This is acceptable because:
        // (1) The scope is cleaned up via Disposer.register(viewer, ...) when the viewer closes.
        // (2) A single project-scoped collector would require a fan-out mechanism to reach
        //     individual diff editors, adding complexity without meaningful performance gain.
        // (3) The number of simultaneously open diff viewers is typically small (< 5).
        val manager = ReviewCommentManager.getInstance(project)
        val reapplyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reapplyJob = reapplyScope.launch {
            manager.commentChanges
                .debounce(100)
                .collect {
                if (project.isDisposed) return@collect
                val updatedComments = manager.getIndex().openForFile(filePath)
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    EditorHighlightSupport.clearForEditor(editor)
                    if (updatedComments.isNotEmpty()) {
                        EditorHighlightSupport.addHighlights(editor, updatedComments, viewer)
                    }
                }
            }
        }
        Disposer.register(viewer, com.intellij.openapi.Disposable {
            reapplyJob.cancel()
            reapplyScope.cancel()
        })
    }

    companion object {
        /** User-data key for the project-relative source path, set by
         *  `openDiffForPath` on the [DiffRequest] before showing the diff. */
        val REVIEW_PATH_KEY = Key.create<String>("opencode.review.diff-path")
    }
}