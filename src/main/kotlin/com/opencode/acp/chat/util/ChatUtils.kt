package com.opencode.acp.chat.util

import com.intellij.icons.AllIcons
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
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