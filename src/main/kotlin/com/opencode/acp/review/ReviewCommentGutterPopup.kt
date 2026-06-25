package com.opencode.acp.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBTextField
import javax.swing.BoxLayout

/**
 * Popup shown when the user clicks a review-comment gutter icon.
 *
 * Lists every open comment on the clicked line, each with its severity icon,
 * author, body, and two actions: **Resolve** (marks `status = RESOLVED` with
 * a default resolution message) and **Delete** (soft-deletes).
 *
 * Uses [ReviewCommentManager.scope] for the suspend-API calls so the popup
 * can close immediately while the write completes in the background.
 */
object ReviewCommentGutterPopup {

    /** Show the popup at the gutter-icon location for the given comments on
     *  [sourcePath]. [line] is the 0-based line the icon is anchored to. */
    fun show(
        project: Project,
        editor: Editor,
        sourcePath: String,
        line: Int,
        comments: List<ReviewComment>,
    ) {
        if (comments.isEmpty()) return
        val manager = ReviewCommentManager.getInstance(project)

        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            background = editor.colorsScheme.defaultBackground
        }

        val title = JLabel(
            "${comments.size} comment(s) on line ${line + 1}",
            SwingConstants.LEFT,
        ).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(6)
        }
        panel.add(title, BorderLayout.NORTH)

        val listPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setTitle("Review Comments")
            .setMovable(true)
            .setResizable(true)
            .createPopup()

        for (c in comments.filter { it.status == ReviewStatus.OPEN }) {
            listPanel.add(commentRow(project, manager, sourcePath, c, popup, listPanel))
        }
        panel.add(listPanel, BorderLayout.CENTER)

        val point = RelativePoint(editor.contentComponent, Point(20, editor.visualPositionToXY(editor.offsetToVisualPosition(editor.document.getLineStartOffset(line))).y))
        popup.show(point)
    }

    /** Build one comment row with severity icon, text, action buttons, and a
     *  replies section. The popup stays open after a reply is added — the
     *  replies panel is rebuilt in-place via [refreshPopupContent] so the user
     *  sees their reply without closing and reopening the popup. */
    private fun commentRow(
        project: Project,
        manager: ReviewCommentManager,
        sourcePath: String,
        comment: ReviewComment,
        popup: JBPopup,
        listPanel: JPanel,
    ): JPanel {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(severityColor(comment.severity), 1),
                JBUI.Borders.empty(6),
            )
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel(severityIcon(comment.severity)))
            add(JBLabel("<html><b>${escapeHtml(comment.author)}</b> · ${comment.severity}</html>"))
        }
        row.add(header, BorderLayout.NORTH)

        val body = JBLabel("<html><div style='width:360px'>${escapeHtml(comment.comment)}</div></html>").apply {
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }
        row.add(body, BorderLayout.CENTER)

        // Actions bar (Resolve/Delete) — placed in a wrapper so the replies
        // section can occupy SOUTH below it.
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        val resolveBtn = JButton("Resolve").apply {
            addActionListener {
                manager.scope.launch {
                    try {
                        manager.updateCommentStatus(
                            sourcePath, comment.id, ReviewStatus.RESOLVED,
                            resolution = "Resolved via gutter popup",
                        )
                        ApplicationManager.getApplication().invokeLater {
                            refreshPopupContent(popup, listPanel, project, manager, sourcePath)
                        }
                    } catch (e: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                "Failed to resolve comment: ${e.message}",
                                "Review Comment"
                            )
                        }
                    }
                }
            }
        }
        val deleteBtn = JButton("Delete").apply {
            addActionListener {
                manager.scope.launch {
                    try {
                        manager.deleteComment(sourcePath, comment.id)
                        ApplicationManager.getApplication().invokeLater {
                            refreshPopupContent(popup, listPanel, project, manager, sourcePath)
                        }
                    } catch (e: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                "Failed to delete comment: ${e.message}",
                                "Review Comment"
                            )
                        }
                    }
                }
            }
        }
        actions.add(resolveBtn)
        actions.add(deleteBtn)

        // Replies section: existing replies + reply input field.
        val repliesPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
        }
        for (reply in comment.replies) {
            repliesPanel.add(replyRow(manager, sourcePath, comment.id, reply, popup, listPanel, project))
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
                                refreshPopupContent(popup, listPanel, project, manager, sourcePath)
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

        // Combine actions + replies into the SOUTH slot.
        val southPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        southPanel.add(actions, BorderLayout.NORTH)
        southPanel.add(repliesPanel, BorderLayout.CENTER)
        row.add(southPanel, BorderLayout.SOUTH)

        return row
    }

    /** Build one reply row with author, text, and a delete button (only on
     *  user-authored replies — ai-review replies are not user-deletable). */
    private fun replyRow(
        manager: ReviewCommentManager,
        sourcePath: String,
        commentId: String,
        reply: ReviewReply,
        popup: JBPopup,
        listPanel: JPanel,
        project: Project,
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
                margin = java.awt.Insets(0, 2, 0, 2)
                toolTipText = "Delete this reply"
                addActionListener {
                    manager.scope.launch {
                        manager.deleteReply(sourcePath, commentId, reply.id)
                        ApplicationManager.getApplication().invokeLater {
                            refreshPopupContent(popup, listPanel, project, manager, sourcePath)
                        }
                    }
                }
            }
            panel.add(deleteBtn, BorderLayout.EAST)
        }
        return panel
    }

    /** Rebuild the popup's content panel with fresh data from the manager.
     *  Called after addReply/deleteReply/resolve/delete so the user sees the
     *  updated state without closing and reopening the popup. Rebuilds the
     *  [listPanel]'s children in-place via removeAll() + re-add + revalidate(). */
    private fun refreshPopupContent(
        popup: JBPopup,
        listPanel: JPanel,
        project: Project,
        manager: ReviewCommentManager,
        sourcePath: String,
    ) {
        // Re-read the current comments for this file from the index and rebuild
        // the listPanel children. Only OPEN comments are shown (matching the
        // original filter in show()).
        val index = manager.getIndex()
        val comments = index.forFile(sourcePath).filter { it.status == ReviewStatus.OPEN }
        listPanel.removeAll()
        for (c in comments) {
            listPanel.add(commentRow(project, manager, sourcePath, c, popup, listPanel))
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun severityIcon(severity: ReviewSeverity) = when (severity) {
        ReviewSeverity.ERROR -> ReviewIcons.ERROR_MARKER
        ReviewSeverity.WARNING -> ReviewIcons.WARNING_MARKER
        ReviewSeverity.INFO -> ReviewIcons.INFO_MARKER
    }

    private fun severityColor(severity: ReviewSeverity) = when (severity) {
        ReviewSeverity.ERROR -> Color(255, 80, 80)
        ReviewSeverity.WARNING -> Color(255, 180, 50)
        ReviewSeverity.INFO -> Color(80, 160, 255)
    }

    /** Escape HTML entities for safe rendering in Swing HTML labels.
     *  ORDER MATTERS: all HTML metacharacters (&, <, >, ", ') must be escaped
     *  BEFORE the \\n → <br> replacement. If \\n were escaped first, the
     *  subsequent & replacement would double-escape the <br> tag. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br>")
}