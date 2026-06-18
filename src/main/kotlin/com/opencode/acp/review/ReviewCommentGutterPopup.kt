package com.opencode.acp.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
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
        for (c in comments.filter { it.status == ReviewStatus.OPEN }) {
            listPanel.add(commentRow(project, manager, sourcePath, c))
        }
        panel.add(listPanel, BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setTitle("Review Comments")
            .setMovable(true)
            .setResizable(true)
            .createPopup()

        val point = RelativePoint(editor.contentComponent, Point(20, editor.visualPositionToXY(editor.offsetToVisualPosition(editor.document.getLineStartOffset(line))).y))
        popup.show(point)
    }

    /** Build one comment row with severity icon, text, and action buttons. */
    private fun commentRow(
        project: Project,
        manager: ReviewCommentManager,
        sourcePath: String,
        comment: ReviewComment,
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

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        val resolveBtn = JButton("Resolve").apply {
            addActionListener {
                manager.scope.launch {
                    try {
                        manager.updateCommentStatus(
                            sourcePath, comment.id, ReviewStatus.RESOLVED,
                            resolution = "Resolved via gutter popup",
                        )
                    } catch (e: Exception) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
                    } catch (e: Exception) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
        row.add(actions, BorderLayout.SOUTH)

        return row
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