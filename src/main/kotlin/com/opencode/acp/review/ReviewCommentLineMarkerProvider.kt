package com.opencode.acp.review

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.util.Function

/**
 * Provides gutter icons for lines that have open review comments.
 *
 * Uses the batched [collectSlowLineMarkers] path — one call per file,
 * receives all elements. A single per-file setup pass computes the relative
 * path once, fetches the [LineCommentMap] once, then emits one aggregated
 * marker per commented line.
 *
 * Implements [DumbAware] so it runs during indexing (the comment index is
 * file-system-based, not PSI-index-dependent).
 *
 * ## hasComments cache (M3)
 *
 * The "has any open comments?" short-circuit cache lives on the project-scoped
 * [ReviewCommentManager], NOT as a `companion object` static. A static cache
 * keyed only by relative path would be shared across open projects, poisoning
 * project A's result into project B when both have `src/main/kotlin/Service.kt`.
 * Per-project instance state is correct. The provider obtains the cache via
 * [ReviewCommentManager.getInstance] and calls [ReviewCommentManager.hasCommentsCache]
 * / [ReviewCommentManager.invalidateCommentsCache].
 */
class ReviewCommentLineMarkerProvider : LineMarkerProvider, DumbAware {

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        val firstFile = elements.first().containingFile ?: return
        val project = firstFile.project
        val vFile = firstFile.virtualFile ?: return

        // Early exit 1: no review comments exist anywhere in the project.
        val manager = ReviewCommentManager.getInstance(project)
        if (manager.getIndex().totalOpen == 0) return

        // Resolve relative path ONCE per file.
        val path = PathUtils.relativePath(project, vFile) ?: return

        // Early exit 2 (cached, project-scoped): this file has no open comments.
        if (manager.hasCommentsCache(path) == false) return

        // Fetch the pre-built line map (built by EditorLifecycleHook).
        val lineMap = manager.lineMapForFile(path)
        if (lineMap.isEmpty) {
            manager.putCommentsCache(path, false)
            return
        }
        manager.putCommentsCache(path, true)

        val document = firstFile.viewProvider.document ?: return

        // Get the actual open comments (LineCommentMap spreads each comment across
        // its full line range, which is useful for highlights but would create one
        // gutter icon per line here). Group by start line so we draw exactly ONE
        // icon per comment, anchored at the line where the comment begins.
        val commentsByStartLine = manager.getIndex()
            .openForFile(path)
            .groupBy { it.startLine - 1 } // 1-based JSON -> 0-based Document line
        if (commentsByStartLine.isEmpty()) {
            manager.putCommentsCache(path, false)
            return
        }
        manager.putCommentsCache(path, true)

        // Build a per-line → first-leaf-PsiElement index in one pass.
        val lineToFirstElement = HashMap<Int, PsiElement>()
        for (e in elements) {
            if (e.firstChild != null) continue  // leaf only
            val line = document.getLineNumber(e.textRange.startOffset)
            if (line in commentsByStartLine) {
                lineToFirstElement.putIfAbsent(line, e)
            }
        }
        if (lineToFirstElement.isEmpty()) return

        // Emit one aggregated marker per comment start line.
        for ((line, anchor) in lineToFirstElement) {
            val lineComments = commentsByStartLine[line] ?: continue
            if (lineComments.isEmpty()) continue

            val highestSeverity = lineComments.maxOf { it.severity }
            val icon = when (highestSeverity) {
                ReviewSeverity.ERROR -> ReviewIcons.ERROR_MARKER
                ReviewSeverity.WARNING -> ReviewIcons.WARNING_MARKER
                ReviewSeverity.INFO -> ReviewIcons.INFO_MARKER
            }

            // Display line numbers as 1-based (Document lines are 0-based).
            val tooltip = buildString {
                append("${lineComments.size} review comment(s) on line ${line + 1}:")
                lineComments.forEachIndexed { i, c ->
                    append("\n  ${i + 1}. [${c.severity}] ${c.comment.take(100)}")
                }
            }

            result += LineMarkerInfo(
                anchor,
                anchor.textRange,
                icon,
                Function<PsiElement, String> { tooltip },
                GutterIconNavigationHandler<PsiElement> { _, elem ->
                    // Click handler: show the comment popup at the clicked line.
                    val proj = elem.project
                    if (proj.isDisposed) return@GutterIconNavigationHandler
                    val editor = com.intellij.openapi.fileEditor.FileEditorManager
                        .getInstance(proj).selectedTextEditor ?: return@GutterIconNavigationHandler
                    val clickedLine = editor.document.getLineNumber(elem.textRange.startOffset)
                    ReviewCommentGutterPopup.show(proj, editor, path, clickedLine, lineComments)
                },
                GutterIconRenderer.Alignment.CENTER,
            )
        }
    }

    /** Synchronous path — NOT used. Must be overridden when using [collectSlowLineMarkers]. */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null
}