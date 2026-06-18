package com.opencode.acp.review

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Modal dialog for adding a review comment on a line range.
 *
 * Pre-fills the line range from the editor selection (or the caret line if
 * there's no selection). The user enters the comment body in an
 * [EditorTextField] with Markdown syntax highlighting and picks a severity.
 * On OK, [buildComment] returns a ready-to-persist [ReviewComment].
 *
 * ## EditorTextField (not JBTextArea)
 *
 * Uses [EditorTextField] — IntelliJ's embedded code-editor component (same
 * engine that powers commit messages and PR descriptions). It gives the user
 * a real editing surface: Markdown syntax highlighting (`**bold**`,
 * `` `code` ``, lists), word wrap, undo/redo, keyboard shortcuts, and a
 * resizable viewport that fills the dialog. `getText()` returns plain text
 * (Markdown source), which goes directly into [ReviewComment.comment] — no
 * conversion, no HTML layer. `kotlinx.serialization` handles JSON escaping
 * automatically on write.
 *
 * Used by [AddReviewCommentAction] (gutter/selection-triggered) and by the
 * Review-tab "Add Comment" action (file-triggered).
 */
class AddReviewCommentDialog(
    project: Project,
    private val filePath: String,
    private val initialStartLine: Int,
    private val initialEndLine: Int,
    private val document: Document?,
) : DialogWrapper(project) {

    /** Embedded IntelliJ editor with Markdown highlighting. Plain text in,
     *  plain text out — no HTML conversion. The FileType controls only
     *  syntax coloring while editing, not content transformation. */
    private val commentField = EditorTextField("", project, FileTypes.PLAIN_TEXT).apply {
        setPlaceholder("Enter your review comment… (Markdown supported)")
        preferredSize = Dimension(600, 300)
    }

    private val severityCombo = JComboBox(ReviewSeverity.entries.toTypedArray()).apply {
        selectedItem = ReviewSeverity.WARNING
    }

    private val startLineSpinner: JSpinner
    private val endLineSpinner: JSpinner

    init {
        val lineCount = (document?.lineCount ?: initialEndLine).coerceAtLeast(1)
        startLineSpinner = JSpinner(SpinnerNumberModel(initialStartLine, 1, lineCount, 1))
        endLineSpinner = JSpinner(SpinnerNumberModel(initialEndLine, 1, lineCount, 1))
        title = "Add Review Comment"
        setOKButtonText("Add Comment")
        super.init()
    }

    /** Build a [ReviewComment] from the dialog input. Returns null if the
     *  comment body is blank (the dialog should not have allowed OK, but
     *  callers should be defensive). */
    fun buildComment(): ReviewComment? {
        val text = commentField.text.trim()
        if (text.isBlank()) return null
        val start = (startLineSpinner.value as Number).toInt().coerceAtLeast(1)
        val end = (endLineSpinner.value as Number).toInt().coerceAtLeast(start)
        return ReviewComment(
            id = ReviewComment.generateId(),
            startLine = start,
            endLine = end,
            comment = text,
            severity = severityCombo.selectedItem as ReviewSeverity,
            author = "user",
        )
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(620, 480)

        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

        // File label — full width. HTML-escaped to prevent XSS via filenames
        // containing HTML metacharacters (e.g., <script> in a filename).
        val safePath = filePath.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4
        panel.add(JBLabel("<html><b>File:</b> $safePath</html>"), gbc)

        // Line range row — compact, left-aligned
        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0
        panel.add(JBLabel("Start line:"), gbc)
        gbc.gridx = 1
        panel.add(startLineSpinner, gbc)

        gbc.gridx = 2
        panel.add(JBLabel("End line:"), gbc)
        gbc.gridx = 3
        panel.add(endLineSpinner, gbc)

        // Severity row
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1
        panel.add(JBLabel("Severity:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        panel.add(severityCombo, gbc)

        // Comment label
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4; gbc.weightx = 1.0
        panel.add(JBLabel("Comment (Markdown supported):"), gbc)

        // EditorTextField — fills all remaining vertical space
        gbc.gridy = 4; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
        panel.add(commentField, gbc)

        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = commentField

    override fun doOKAction() {
        // Validate: non-blank comment, start <= end.
        if (commentField.text.trim().isBlank()) {
            setErrorText("Comment body cannot be empty", commentField)
            return
        }
        if (commentField.text.length > MAX_COMMENT_LENGTH) {
            setErrorText("Comment body exceeds maximum length ($MAX_COMMENT_LENGTH characters)", commentField)
            return
        }
        val start = (startLineSpinner.value as Number).toInt()
        val end = (endLineSpinner.value as Number).toInt()
        if (end < start) {
            setErrorText("End line cannot be before start line", endLineSpinner)
            return
        }
        super.doOKAction()
    }

    companion object {
        private const val MAX_COMMENT_LENGTH = 10_000
    }
}