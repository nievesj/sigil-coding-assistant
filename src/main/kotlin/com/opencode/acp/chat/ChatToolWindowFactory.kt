package com.opencode.acp.chat

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.opencode.acp.chat.service.FreezeDetector
import com.opencode.acp.chat.service.OpenCodeService
import com.opencode.acp.chat.ui.compose.ChatScreen
import com.opencode.acp.chat.ui.theme.ChatTheme
import com.opencode.acp.chat.viewmodel.ChatViewModel
import com.opencode.acp.review.ReviewCommentManager
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.addComposeTab

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        /** Per-project ComposePanel references. Keyed by Project to prevent
         *  cross-project state leaks in multi-project windows. */
        private val activePanels = java.util.concurrent.ConcurrentHashMap<Project, androidx.compose.ui.awt.ComposePanel>()

        /** Legacy accessor for single-project scenarios. Returns the panel
         *  for the first project that has one, or null. */
        val activeComposePanel: androidx.compose.ui.awt.ComposePanel?
            get() = activePanels.values.firstOrNull()

        /** Register a ComposePanel for a specific project. */
        internal fun registerPanel(project: Project, panel: androidx.compose.ui.awt.ComposePanel) {
            activePanels[project] = panel
        }

        /** Unregister a ComposePanel for a specific project. */
        internal fun unregisterPanel(project: Project) {
            activePanels.remove(project)
        }

        /** Async dispose — for OpenCodeService.dispose() during IDE restart.
         *  ComposePanel.dispose() can block EDT if Skiko is mid-frame.
         *  Daemon thread ensures EDT is NEVER blocked.
         *  Disposes ALL registered panels across all projects. */
        fun disposeActiveComposePanelAsync() {
            val iterator = activePanels.entries.iterator()
            while (iterator.hasNext()) {
                val (project, panel) = iterator.next()
                iterator.remove()
                Thread({
                    try { panel.isVisible = false; panel.dispose() } catch (e: Exception) {
                        // Log the exception so failed disposes are visible in idea.log.
                        // Previously this was silently swallowed, making native resource
                        // leaks invisible. On IDE restart the JVM exits so leaks are
                        // reclaimed, but on tool window close+reopen without restart,
                        // undisposed panels leak native (Skiko) resources.
                        com.intellij.openapi.diagnostic.Logger.getInstance("ACP")
                            .warn("[ACP] disposeActiveComposePanelAsync: ComposePanel.dispose() failed: ${e.message}")
                    }
                }, "opencode-compose-dispose-${System.identityHashCode(project)}").apply { isDaemon = true; start() }
            }
        }

    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Skiko renderer selection happens at class-load time, before this method
        // runs, so a runtime System.setProperty("skiko.renderApi", "SOFTWARE")
        // here is a no-op. The renderer is controlled by the JVM argument
        // -Dskiko.renderApi=SOFTWARE set in build.gradle.kts (runIde task) and
        // in the user's idea64.exe.vmoptions for installed builds.

        val service = project.service<OpenCodeService>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Register the scope with IntelliJ's Disposer so IDE shutdown cancels it
        // even if the content disposer doesn't fire (e.g., IDE crash).
        val scopeDisposable = com.intellij.openapi.Disposable { scope.cancel() }
        com.intellij.openapi.util.Disposer.register(project, scopeDisposable)
        val viewModel = ChatViewModel(scope, service, project)

        // Reload the review comment index from disk when the tool window opens.
        // This catches .review/ files that appeared since project open (git pull,
        // branch switch, external tools, LLM writes from a previous session).
        // loadAll() reads directly from disk via java.nio.file.Files.walk,
        // bypassing the VFS, so it sees files the VFS hasn't refreshed yet.
        //
        // ReviewCommentStartupActivity also calls loadAll() on project open. The
        // two calls are serialized by ReviewCommentManager.loadAllMutex, so they
        // can't interleave their clearForEditor + addHighlights on the same
        // editor (the second waits for the first, then re-reads — idempotent).
        scope.launch {
            ReviewCommentManager.getInstance(project).loadAll()
        }

        // Start freeze detector — captures thread dumps on EDT hangs without
        // depending on the EDT itself. Writes to <project>/.opencode/freezes/.
        // Skip when basePath is null (default/virtual projects, scratch files)
        // to avoid NPE from java.io.File(null, ...).
        val freezeDumpDir = project.basePath?.let { java.io.File(it, ".opencode/freezes") }
        val freezeDetector = if (freezeDumpDir != null) {
            FreezeDetector(freezeDumpDir).also { it.start() }
        } else {
            null
        }

        toolWindow.addComposeTab("") {
            ChatTheme {
                ChatScreen(viewModel, project)
            }
        }

        val content = toolWindow.contentManager.contents.firstOrNull()
        if (content != null) {
            val component = content.component
            val composePanelRef = findComposePanel(component)
            if (composePanelRef != null) {
                registerPanel(project, composePanelRef)
            } else {
                com.intellij.openapi.diagnostic.Logger.getInstance("ACP").warn("[ACP] ComposePanel not found in tool window content")
            }

            val pasteAction = DumbAwareAction.create { viewModel.requestImagePaste() }
            val pasteShortcut = CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), null),
            )
            pasteAction.registerCustomShortcutSet(pasteShortcut, component, project)

            content.setDisposer(object : com.intellij.openapi.Disposable {
                override fun dispose() {
                    val logger = com.intellij.openapi.diagnostic.Logger.getInstance("ACP")
                    logger.info("[ACP] ContentDisposer: disposing tool window content")
                    freezeDetector?.stop()
                    // Close the ViewModel BEFORE cancelling the scope so any async cleanup
                    // coroutines launched by close() can execute on a still-alive scope.
                    viewModel.close()
                    scope.cancel()
                    try { component.isVisible = false } catch (_: Exception) {}
                    // CRITICAL: Do NOT call ComposePanel.dispose() synchronously on EDT.
                    // Skiko's render thread may be mid-frame, causing EDT to block → IDE lockup.
                    // Use async dispose on a daemon thread (same pattern as disposeActiveComposePanelAsync).
                    val panel = composePanelRef ?: activePanels[project]
                    unregisterPanel(project)
                    if (panel != null) {
                        logger.info("[ACP] ContentDisposer: async disposing ComposePanel=$panel")
                        Thread({
                            try {
                                // Timeout the dispose to prevent thread accumulation under rapid toggle.
                                // If dispose() hangs (Skiko mid-frame), log and move on — the JVM
                                // will reclaim native resources on shutdown.
                                val disposeThread = Thread.currentThread()
                                val timeoutThread = Thread({
                                    try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@Thread }
                                    if (disposeThread.isAlive) {
                                        logger.warn("[ACP] ContentDisposer: ComposePanel.dispose() timed out after 10s — thread may leak")
                                        disposeThread.interrupt()
                                    }
                                }, "opencode-compose-dispose-timeout").apply { isDaemon = true; start() }
                                panel.isVisible = false
                                panel.dispose()
                                timeoutThread.interrupt()
                            } catch (e: Exception) {
                                logger.warn("[ACP] ContentDisposer: ComposePanel.dispose() failed: ${e.message}")
                            }
                        }, "opencode-compose-dispose").apply { isDaemon = true; start() }
                    } else {
                        logger.warn("[ACP] ContentDisposer: composePanelRef is null!")
                    }
                    logger.info("[ACP] ContentDisposer: done")
                }
            })
        }

        val settings = com.opencode.acp.config.settings.OpenCodeSettingsState.getInstance()
        if (settings.autoConnect) {
            scope.launch { viewModel.initialize(project.basePath) }
        }
    }

    private fun findComposePanel(container: java.awt.Container): androidx.compose.ui.awt.ComposePanel? {
        for (child in container.components) {
            if (child is androidx.compose.ui.awt.ComposePanel) return child
            if (child is java.awt.Container) {
                val found = findComposePanel(child)
                if (found != null) return found
            }
        }
        return null
    }
}
