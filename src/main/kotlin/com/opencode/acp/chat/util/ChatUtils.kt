package com.opencode.acp.chat.util

import com.intellij.icons.AllIcons
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import javax.swing.Icon
import javax.swing.SwingUtilities

/** EDT dispatcher for Swing mutations. */
val Dispatchers.EDT: CoroutineDispatcher
    get() = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            SwingUtilities.invokeLater(block)
        }
    }

/** Creates a CoroutineScope that dispatches to EDT. */
fun edtScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

/** Generate a stable UUID for message/tool IDs. */
fun generateId(): String = UUID.randomUUID().toString()

// Reusable Flexmark instances — O(n²) otherwise for long streams
private val markdownParser = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
    .build()

private val markdownRenderer: HtmlRenderer by lazy {
    val options = com.vladsch.flexmark.util.data.MutableDataSet()
    options.set(HtmlRenderer.SUPPRESS_HTML, true)
    HtmlRenderer.builder(options).build()
}

/** Renders markdown to sanitized HTML.
 *  Strips inline styles and classes that Swing's limited CSS parser cannot handle.
 *  Suppresses raw HTML to prevent XSS from LLM output. */
fun renderMarkdownToHtml(markdown: String): String {
    val document = markdownParser.parse(markdown)
    return markdownRenderer.render(document)
        // Strip inline styles Swing's limited CSS parser cannot handle
        .replace(Regex(""" style\s*=\s*"[^"]*""""), "")
        .replace(Regex(""" style\s*=\s*'[^']*'"""), "")
        // Strip class attributes Swing doesn't use
        .replace(Regex("""\s+class\s*=\s*"[^"]*""""), "")
        .replace(Regex("""\s+class\s*=\s*'[^']*'"""), "")
}

/** Escape HTML for user message display. Covers & < > " ' — use on ANY user/LLM-provided text. */
fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/** Tool call status display mapping. Uses IntelliJ platform icons. */
object ToolStatusDisplay {
    fun icon(status: ToolCallStatus): Icon = when (status) {
        ToolCallStatus.PENDING -> AllIcons.Actions.Execute
        ToolCallStatus.IN_PROGRESS -> AllIcons.Actions.Execute
        ToolCallStatus.COMPLETED -> AllIcons.Actions.Checked
        ToolCallStatus.FAILED -> AllIcons.Actions.Cancel
    }

    fun label(kind: ToolKind): String = when (kind) {
        ToolKind.EXECUTE -> "Running"
        ToolKind.EDIT -> "Editing"
        ToolKind.READ -> "Reading"
        ToolKind.DELETE -> "Deleting"
        ToolKind.MOVE -> "Moving"
        ToolKind.SEARCH -> "Searching"
        ToolKind.FETCH -> "Fetching"
        ToolKind.THINK -> "Thinking"
        ToolKind.SWITCH_MODE -> "Switching mode"
        ToolKind.OTHER -> "Processing"
    }
}
