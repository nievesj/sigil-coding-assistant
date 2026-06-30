package com.opencode.acp.follow

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.opencode.acp.config.settings.OpenCodeSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import javax.swing.JPanel
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages read-only ConsoleView tabs in the Run tool window for agent-executed
 * commands. The agent already executed the command on the OpenCode server — this
 * manager displays the output, it does NOT re-run anything.
 *
 * Thread safety: Called from SSE event processing on background threads.
 * All editor/IDE API calls are dispatched to EDT via [ApplicationManager.invokeLater].
 * The console map uses [ConcurrentHashMap] for thread-safe reads/writes.
 *
 * Lifecycle: Project-level service, disposed when the project closes. Each console
 * tab is registered as a child disposable of the project and cleaned up on dispose.
 */
@Service(Service.Level.PROJECT)
class CommandFollowManager(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}

    companion object {
        /** Maximum number of console tabs open at once. Oldest are evicted first. */
        const val MAX_CONSOLE_TABS = 5

        /** Cooldown between opening command consoles to avoid UI spam. */
        const val COMMAND_COOLDOWN_MS = 2_000L

        fun getInstance(project: Project): CommandFollowManager =
            project.service<CommandFollowManager>()
    }

    /**
     * Per-console state tracked for lifecycle management.
     *
     * @property consoleView The IntelliJ console view (receives output text).
     * @property descriptor  The run content descriptor shown in the Run tool window.
     * @property command     The command string (for display in the tab header).
     * @property createdAtMs Wall-clock time when this console was created (for eviction).
     */
    private data class ConsoleState(
        val consoleView: ConsoleView,
        val descriptor: RunContentDescriptor,
        val command: String,
        val createdAtMs: Long,
    )

    /** Active console tabs keyed by toolCallId. */
    private val consoles = ConcurrentHashMap<String, ConsoleState>()

    /** Throttle state — last time a command console was opened. */
    @Volatile private var lastCommandMs: Long = 0

    // ── Entry points ──────────────────────────────────────────────────

    /**
     * Open a new console tab for an agent-executed command.
     *
     * This is the main entry point called by [SessionState] when an EXECUTE tool
     * call is detected. It creates a read-only console with a header showing the
     * agent name, model, working directory, and the command itself.
     *
     * @param project     The IntelliJ project.
     * @param toolCallId  Unique tool call identifier (used as map key).
     * @param command     The command string to display. If blank, returns immediately.
     * @param workdir     Working directory (displayed in header, may be null).
     * @param description Human-readable description (unused, reserved for future tab subtitle).
     * @param agentName   Name of the agent that executed the command (may be null).
     * @param modelName   Name of the model used (may be null).
     */
    fun followCommand(
        project: Project,
        toolCallId: String,
        command: String?,
        workdir: String?,
        description: String?,
        agentName: String?,
        modelName: String?,
    ) {
        // Guard: feature must be enabled
        val settings = OpenCodeSettingsState.getInstance()
        if (!settings.followAgentEnabled) return
        if (!settings.followCommandsInConsole) return

        // Guard: project alive
        if (project.isDisposed) return

        // Guard: non-blank command
        if (command.isNullOrBlank()) return

        // Throttle: skip if too soon since last console opened
        val now = System.currentTimeMillis()
        if (now - lastCommandMs < COMMAND_COOLDOWN_MS) return
        lastCommandMs = now

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                openConsoleOnEdt(project, toolCallId, command, workdir, agentName, modelName)
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Follow Agent: error opening command console for $toolCallId" }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Append command output to an existing console tab.
     *
     * Called when the tool result arrives. The [output] is a list of JSON objects
     * with a "text" key containing the output chunk. Multiple chunks may arrive
     * for long-running commands.
     *
     * @param project    The IntelliJ project.
     * @param toolCallId Tool call identifier (must match a previous [followCommand] call).
     * @param output     List of JSON objects with "text" fields, or null.
     * @param isError    True if the output is stderr / error output.
     */
    fun followCommandResult(
        project: Project,
        toolCallId: String,
        output: List<JsonObject>?,
        isError: Boolean,
    ) {
        if (project.isDisposed) return

        val state = consoles[toolCallId] ?: return

        // Parse output text: extract "text" field from each JSON object and concatenate
        val text = output
            ?.mapNotNull { obj -> obj["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?: return

        if (text.isEmpty()) return

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val contentType = if (isError) {
                    ConsoleViewContentType.ERROR_OUTPUT
                } else {
                    ConsoleViewContentType.NORMAL_OUTPUT
                }
                state.consoleView.print(text, contentType)
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Follow Agent: error printing output for $toolCallId" }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Print a footer indicating command completion (success or failure) and
     * clean up the console state. The tab remains visible for the user to
     * read the output.
     *
     * @param project    The IntelliJ project.
     * @param toolCallId Tool call identifier.
     * @param isError    True if the command failed.
     */
    fun finishCommand(
        project: Project,
        toolCallId: String,
        isError: Boolean,
    ) {
        if (project.isDisposed) return

        val state = consoles[toolCallId] ?: return

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val footer = if (isError) {
                    "\n--- Process finished with error ---"
                } else {
                    "\n--- Process finished with exit code 0 ---"
                }
                val contentType = if (isError) {
                    ConsoleViewContentType.LOG_ERROR_OUTPUT
                } else {
                    ConsoleViewContentType.LOG_INFO_OUTPUT
                }
                state.consoleView.print(footer, contentType)
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Follow Agent: error printing footer for $toolCallId" }
            }
        }, ModalityState.nonModal())
    }

    // ── Internal ──────────────────────────────────────────────────────

    /**
     * Create and display a new console tab on the EDT.
     */
    private fun openConsoleOnEdt(
        project: Project,
        toolCallId: String,
        command: String,
        workdir: String?,
        agentName: String?,
        modelName: String?,
    ) {
        // Create the console via IntelliJ's builder API
        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        val consoleView = builder.console

        // Register console as child disposable of the project
        Disposer.register(project, consoleView)

        // Print header
        printHeader(consoleView, agentName, modelName, workdir, command)

        // Build the toolbar: close button
        val descriptor = createDescriptor(project, command, consoleView)

        // Show in Run tool window under the "Run" executor
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        RunContentManager.getInstance(project).showRunContent(executor, descriptor)

        // Store in map
        val state = ConsoleState(
            consoleView = consoleView,
            descriptor = descriptor,
            command = command,
            createdAtMs = System.currentTimeMillis(),
        )
        consoles[toolCallId] = state

        // Evict oldest if over limit
        evictOldestIfNeeded()

        logger.info { "[ACP] Follow Agent: command console opened for $toolCallId" }
    }

    /**
     * Print the header block at the top of the console:
     * - Agent name + model (if available)
     * - Working directory (if available)
     * - The command line (prefixed with `$ `)
     * - A separator line
     */
    private fun printHeader(
        consoleView: ConsoleView,
        agentName: String?,
        modelName: String?,
        workdir: String?,
        command: String,
    ) {
        val agentInfo = buildString {
            append("Agent: ")
            append(agentName ?: "unknown")
            if (!modelName.isNullOrBlank()) {
                append(" (model: ")
                append(modelName)
                append(")")
            }
        }
        consoleView.print("$agentInfo\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        if (!workdir.isNullOrBlank()) {
            consoleView.print("Working directory: $workdir\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        consoleView.print("$ $command\n", ConsoleViewContentType.USER_INPUT)
        consoleView.print("─".repeat(60) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /**
     * Create a [RunContentDescriptor] with the console view wrapped in a panel
     * that includes a toolbar with a CloseAction button.
     *
     * The display name is truncated to 40 characters for the tab label.
     */
    private fun createDescriptor(
        project: Project,
        command: String,
        consoleView: ConsoleView,
    ): RunContentDescriptor {
        val executor = DefaultRunExecutor.getRunExecutorInstance()

        // Truncate command for tab display
        val displayName = if (command.length > 40) {
            "Agent: ${command.take(37)}..."
        } else {
            "Agent: $command"
        }

        // Build the panel: console component + close toolbar.
        // The close action captures `project` and removes this tab from the
        // Run tool window. We use a mutable holder to break the circular
        // dependency between the descriptor and its close action.
        val panel = JPanel(BorderLayout())
        val actionGroup = DefaultActionGroup()

        // We need the descriptor to construct CloseAction, but the descriptor
        // wraps the panel that contains the close button. Use a var holder
        // that the action reads at click-time (always initialized by then).
        var descriptorRef: RunContentDescriptor? = null
        actionGroup.add(object : com.intellij.openapi.actionSystem.AnAction(
            "Close", "Close this console tab", AllIcons.Actions.Close
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val d = descriptorRef ?: return
                RunContentManager.getInstance(project).removeRunContent(executor, d)
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "AgentConsole", actionGroup, false
        )
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.WEST)

        // Console content fills the rest
        panel.add(consoleView.component, BorderLayout.CENTER)

        val descriptor = object : RunContentDescriptor(
            consoleView,            // executionConsole
            null,                   // processHandler (null — not running a process)
            panel,                  // component
            displayName,            // displayName
            AllIcons.Nodes.Console, // icon
        ) {
            override fun isContentReuseProhibited(): Boolean = true
        }
        descriptorRef = descriptor

        return descriptor
    }

    /**
     * Evict the oldest console tab if the map exceeds [MAX_CONSOLE_TABS].
     * Disposes the console view and removes it from the Run tool window.
     *
     * NOTE: This is a best-effort check-then-act — between reading `consoles.size`
     * and calling `disposeConsole`, another thread could add or remove entries.
     * The worst case is briefly having MAX_CONSOLE_TABS + 1 consoles, which
     * self-corrects on the next eviction. This is acceptable for a UI convenience
     * feature (the exact tab count is not a correctness invariant).
     */
    private fun evictOldestIfNeeded() {
        if (consoles.size <= MAX_CONSOLE_TABS) return

        // Find the oldest entry by createdAtMs
        val oldest = consoles.entries
            .minByOrNull { it.value.createdAtMs }
            ?: return

        logger.info { "[ACP] Follow Agent: evicting oldest console tab (toolCallId=${oldest.key})" }
        disposeConsole(oldest.key)
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    /**
     * Dispose and remove a single console tab by its toolCallId.
     *
     * @param toolCallId The tool call identifier to remove.
     */
    fun disposeConsole(toolCallId: String) {
        val state = consoles.remove(toolCallId) ?: return
        try {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            RunContentManager.getInstance(project).removeRunContent(executor, state.descriptor)
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] Follow Agent: error removing run content for $toolCallId" }
        }
        try {
            Disposer.dispose(state.consoleView)
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] Follow Agent: error disposing console view for $toolCallId" }
        }
    }

    /**
     * Activate the Run tool window and select the console tab for the given toolCallId.
     * Used by the ToolPill "open in console" button.
     */
    fun activateConsole(project: Project, toolCallId: String) {
        val state = consoles[toolCallId] ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                // Bring Run tool window to front — the console tab will be visible.
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow(executor.toolWindowId)?.activate(null)
                logger.info { "[ACP] Follow Agent: activated Run console for $toolCallId" }
            } catch (e: Exception) {
                logger.warn(e) { "[ACP] Follow Agent: error activating console for $toolCallId" }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Dispose all tracked consoles. Called when the project is closing.
     */
    override fun dispose() {
        val keys = ArrayList(consoles.keys)
        for (key in keys) {
            disposeConsole(key)
        }
        consoles.clear()
    }
}
