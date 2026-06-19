package com.opencode.acp.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.launch

/**
 * Editor popup action: "Add Review Comment".
 *
 * Registers on the editor right-click context menu. Computes the line range
 * from the current selection (or the caret line if there's no selection),
 * opens [AddReviewCommentDialog], and on OK persists the comment via
 * [ReviewCommentManager.addComment].
 *
 * Registered in `plugin.xml` under `<actions>` with `EditorPopupGroup` so it
 * appears in the editor right-click menu.
 */
class AddReviewCommentAction : AnAction("Add Review Comment", "Add a review comment on the selected line range", AllIcons.General.BalloonInformation), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabledAndVisible = editor != null && project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        if (project.isDisposed) return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val sourcePath = PathUtils.relativePath(project, vf) ?: return

        val doc = editor.document
        val (startLine, endLine) = selectionLineRange(editor)

        val dialog = AddReviewCommentDialog(project, sourcePath, startLine, endLine, doc)
        if (!dialog.showAndGet()) return

        val comment = dialog.buildComment() ?: return

        // Persist via the manager. addComment is a suspend fun — launch on
        // the manager's scope so it survives the action's EDT lifecycle.
        val manager = ReviewCommentManager.getInstance(project)
        manager.scope.launch {
            try {
                manager.addComment(sourcePath, comment)
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to add review comment: ${ex.message}",
                        "Review Comment Error",
                    )
                }
            }
        }
    }

    /** Compute the (start, end) 1-based line range from the editor selection.
     *  If there's no selection, both values are the caret's current line.
     *
     *  Off-by-one guard: IntelliJ selections that span a full line include the
     *  trailing newline, so `selectionEnd` lands on the START offset of the next
     *  line. Without adjustment, `end` would be one greater than the visually
     *  selected last line (e.g., selecting line 42 fully yields end=43). When
     *  `selectionEnd` sits on a line boundary AND it isn't the start of the
     *  selection, back up one character so the reported line is the last
     *  actually-selected line. */
    private fun selectionLineRange(editor: Editor): Pair<Int, Int> {
        val doc = editor.document
        val selModel = editor.selectionModel
        return if (selModel.hasSelection()) {
            val startOffset = selModel.selectionStart
            val endOffset = selModel.selectionEnd
            val start = doc.getLineNumber(startOffset) + 1
            // If the selection ends exactly at a line start (i.e., right after a
            // newline) and that's past the selection start, the last visually
            // selected line is the one BEFORE endOffset — back up one char.
            val adjustedEnd = if (endOffset > startOffset &&
                endOffset == doc.getLineStartOffset(doc.getLineNumber(endOffset))) {
                endOffset - 1
            } else {
                endOffset
            }
            val end = doc.getLineNumber(adjustedEnd) + 1
            start to end
        } else {
            val caretLine = doc.getLineNumber(editor.caretModel.offset) + 1
            caretLine to caretLine
        }
    }
}