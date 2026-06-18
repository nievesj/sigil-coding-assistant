package com.opencode.acp.follow

import com.agentclientprotocol.model.ToolKind
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.projectView.ProjectView
import com.opencode.acp.config.settings.OpenCodeSettingsState
import com.opencode.acp.chat.util.EDT
import com.opencode.acp.review.EditorHighlightSupport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * Manages "Follow Agent" editor interaction.
 * Opens files, scrolls to line ranges, applies transient highlights,
 * and adds block inlay labels when the LLM reads/edits/searches files.
 *
 * Thread safety: SessionState.processEventInternal() runs on Dispatchers.Default.
 * This manager is called from that context. All editor API calls are dispatched
 * to EDT via ApplicationManager.invokeLater(). Throttle state uses @Volatile
 * for cross-thread visibility.
 */
@Service(Service.Level.PROJECT)
class EditorFollowManager(private val project: Project) : Disposable {

    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    companion object {
        const val FOLLOW_FILE_COOLDOWN_MS = 2_000L
        const val HIGHLIGHT_DURATION_MS = 5_000L
        const val PROJECT_VIEW_COOLDOWN_MS = 5_000L

        private val LINE_HEADER_REGEX = Regex("""^Line (\d+):""")

        fun getInstance(project: Project): EditorFollowManager =
            project.service<EditorFollowManager>()
    }

    @Volatile private var lastFollowFileMs: Long = 0
    @Volatile private var lastProjectViewSelectMs: Long = 0
    @Volatile private var pendingFollow: PendingFollow? = null

    private data class PendingFollow(
        val filePath: String, val startLine: Int, val endLine: Int, val kind: ToolKind,
        val agentName: String? = null, val modelName: String? = null,
        val input: JsonObject? = null, val startTimeMs: Long? = null,
    )

    // Job handle for the in-flight throttle wait. Cancelled and replaced on each
    // new throttled call — guarantees at most ONE pending coroutine at a time,
    // so we don't pile up N coroutines that all race to read pendingFollow.
    @Volatile private var pendingJob: kotlinx.coroutines.Job? = null

    override fun dispose() {
        scope.cancel()
    }

    /** Check if Follow Agent is enabled for this project. */
    fun isFollowEnabled(): Boolean {
        return OpenCodeSettingsState.getInstance().followAgentEnabled
    }

    /** Enable or disable Follow Agent. */
    fun setFollowEnabled(enabled: Boolean) {
        OpenCodeSettingsState.getInstance().followAgentEnabled = enabled
    }

    /**
     * Main entry point: called by SessionState on ToolUse events.
     * Thread-safe: can be called from any thread.
     */
    fun followToolCall(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        kind: ToolKind,
        agentName: String? = null,
        modelName: String? = null,
        input: JsonObject? = null,
        startTimeMs: Long? = null,
    ) {
        if (!isFollowEnabled()) return
        val color = FollowColorProvider.getColor(kind) ?: return
        val label = FollowColorProvider.composeInlayLabel(kind, agentName, modelName, input, startTimeMs)

        if (!throttleCheck()) {
            pendingFollow = PendingFollow(filePath, startLine, endLine, kind, agentName, modelName, input, startTimeMs)
            logger.debug { "[ACP] Follow Agent: queued $filePath (throttled)" }
            schedulePendingFollow()
            return
        }

        navigateOnEdt(project, filePath, startLine, endLine, color, label, focus = false)
    }

    /**
     * Entry point for ToolResult events (search tools — line numbers in results).
     * Thread-safe: can be called from any thread.
     */
    fun followToolResult(
        project: Project,
        toolCallId: String,
        output: List<JsonObject>?,
        kind: ToolKind,
    ) {
        if (!isFollowEnabled()) return
        if (kind != ToolKind.SEARCH) return
        val color = FollowColorProvider.getColor(kind) ?: return
        val label = FollowColorProvider.getInlayLabel(kind) ?: return

        // Parse first match from tool output text.
        // OpenCode grep format: two lines per match:
        //   <abs_path>:            (line 1, trailing colon)
        //     Line <N>: <text>     (line 2, "Line N:" prefix)
        val firstText = output?.firstOrNull()?.let { obj ->
            obj["text"]?.jsonPrimitive?.contentOrNull
        } ?: return
        val lines = firstText.lineSequence().iterator()
        if (!lines.hasNext()) return
        val pathLine = lines.next().trimEnd()
        if (!pathLine.endsWith(':')) return
        val filePath = pathLine.dropLast(1)
        if (!lines.hasNext()) return
        val lineLine = lines.next().trim()
        val line = LINE_HEADER_REGEX.find(lineLine)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return

        if (!throttleCheck()) {
            pendingFollow = PendingFollow(filePath, line, line, kind)
            schedulePendingFollow()
            return
        }

        navigateOnEdt(project, filePath, line, line, color, label, focus = false)
    }

    /**
     * Open file at a specific line with explicit focus control.
     * Used by ToolPill click-to-open (focus=true) vs auto-follow (focus=false).
     */
    fun openFileAtLine(project: Project, filePath: String, line: Int, focus: Boolean) {
        ApplicationManager.getApplication().invokeLater({
            try {
                val vf = resolveVirtualFile(project, filePath) ?: return@invokeLater
                if (line > 0) {
                    OpenFileDescriptor(project, vf, line - 1, 0).navigate(focus)
                } else {
                    FileEditorManager.getInstance(project).openFile(vf, focus)
                }
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Follow Agent: error opening $filePath" }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Resolve file path to VirtualFile.
     * Uses findFileByPath() first (VFS snapshot, no I/O).
     * Falls back to refreshAndFindFileByPath() for externally-created files.
     */
    fun resolveVirtualFile(project: Project, filePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val absPath = if (Path.of(filePath).isAbsolute) {
            filePath
        } else {
            Path.of(basePath).resolve(filePath).normalize().toString()
        }
        // CWE-22: reject paths that escape the project root.
        // Without this, an LLM (or prompt-injection attacker) could provide
        // "../" sequences to open files outside the project directory.
        val baseNormalized = basePath.replace('\\', '/')
        val absNormalized = absPath.replace('\\', '/')
        if (!absNormalized.startsWith("$baseNormalized/") && absNormalized != baseNormalized) {
            logger.warn { "[ACP] Follow Agent: path traversal blocked: $filePath resolves outside project" }
            return null
        }
        val normalized = absPath.replace('/', File.separatorChar)
        val vfs = LocalFileSystem.getInstance()
        return vfs.findFileByPath(normalized)
            ?: vfs.refreshAndFindFileByPath(normalized)
    }

    // ── Throttle ────────────────────────────────────────────────────────

    private fun throttleCheck(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFollowFileMs < FOLLOW_FILE_COOLDOWN_MS) return false
        lastFollowFileMs = now
        return true
    }

    private fun schedulePendingFollow() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(FOLLOW_FILE_COOLDOWN_MS)
            if (project.isDisposed) return@launch
            val pending = pendingFollow ?: return@launch
            pendingFollow = null
            val color = FollowColorProvider.getColor(pending.kind) ?: return@launch
            val label = FollowColorProvider.composeInlayLabel(
                pending.kind, pending.agentName, pending.modelName, pending.input, pending.startTimeMs
            )
            lastFollowFileMs = System.currentTimeMillis()
            navigateOnEdt(project, pending.filePath, pending.startLine, pending.endLine, color, label, focus = false)
        }
    }

    // ── Editor interaction (all on EDT) ─────────────────────────────────

    private fun navigateOnEdt(
        project: Project, filePath: String,
        startLine: Int, endLine: Int,
        color: java.awt.Color, label: String, focus: Boolean
    ) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                val vf = resolveVirtualFile(project, filePath) ?: run {
                    logger.warn { "[ACP] Follow Agent: file not found: $filePath" }
                    return@invokeLater
                }

                val fem = FileEditorManager.getInstance(project)
                val hasLineRange = startLine > 0 && endLine > 0

                if (hasLineRange) {
                    val midLine = (startLine + endLine) / 2
                    OpenFileDescriptor(project, vf, midLine - 1, 0).navigate(focus)
                    scrollAndHighlight(fem, vf, startLine, endLine, midLine, color, label)
                } else {
                    // No line numbers in the tool input (e.g. edit/write/apply_patch).
                    // Just open the file at the top — do NOT navigate to line 0 via
                    // OpenFileDescriptor(line=0) because that scrolls the editor and
                    // moves the caret to a meaningless position. Then add the inlay
                    // label at the top of the file so the user sees "Agent is editing".
                    fem.openFile(vf, focus)
                    addInlayOnly(fem, vf, color, label)
                }

                selectInProjectView(project, vf)
                logger.info { "[ACP] Follow Agent: opened $filePath" }

                // Balloon notifications intentionally omitted — the inlay label
                // and project-view selection are sufficient visual cues.
            } catch (e: Exception) {
                logger.error(e) { "[ACP] Follow Agent: error navigating to $filePath" }
            }
        }, ModalityState.nonModal())
    }

    /**
     * Add a block inlay label at the top of the file (offset 0) without a
     * highlighter. Used for tools that don't carry line numbers in their
     * input (edit, write, apply_patch) — the user gets a visual "Agent is
     * editing" pill but the cursor and viewport stay where they were.
     */
    private fun addInlayOnly(
        fem: FileEditorManager, vf: VirtualFile,
        color: java.awt.Color, actionLabel: String
    ) {
        val selectedEditor = fem.getSelectedEditor(vf)
        val targetEditor = if (selectedEditor is TextEditor) selectedEditor
            else fem.getEditors(vf).filterIsInstance<TextEditor>().firstOrNull()
            ?: return
        val editor = targetEditor.editor
        val doc = editor.document
        if (doc.lineCount == 0) return
        val firstLineOffset = doc.getLineStartOffset(0)
        val inlay = editor.inlayModel.addBlockElement(
            firstLineOffset,
            InlayProperties()
                .relatesToPrecedingText(true)
                .showAbove(true),
            AgentActionRenderer(actionLabel, color)
        )
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        scope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            try {
                inlay?.dispose()
            } catch (e: Exception) {
                logger.debug(e) { "[ACP] Follow Agent: error removing inlay" }
            }
        }
    }

    private fun scrollAndHighlight(
        fem: FileEditorManager, vf: VirtualFile,
        startLine: Int, endLine: Int, midLine: Int,
        color: java.awt.Color, actionLabel: String
    ) {
        val selectedEditor = fem.getSelectedEditor(vf)
        val targetEditor = if (selectedEditor is TextEditor) selectedEditor
            else fem.getEditors(vf).filterIsInstance<TextEditor>().firstOrNull()
            ?: return

        val editor = targetEditor.editor
        val doc = editor.document
        val lineCount = doc.lineCount

        if (midLine - 1 < lineCount) {
            val visibleLines = editor.scrollingModel.visibleArea.height / editor.lineHeight
            val rangeLines = if (startLine > 0 && endLine > 0) endLine - startLine + 1 else 0
            val fitsInViewport = rangeLines in 1..visibleLines

            // Scroll to the START of the range (not the CENTER). The inlay label
            // is anchored at hlStart with showAbove=true, so the inlay appears
            // ABOVE the first highlighted line. If we scrolled to midLine, the
            // inlay would be above the viewport and invisible. Scrolling to the
            // start of the range puts the inlay at the top of the viewport.
            val offset = if (fitsInViewport) {
                // Range fits in viewport — center it so both inlay and highlight are visible
                doc.getLineStartOffset(maxOf(midLine - 1, 0))
            } else {
                // Range is larger than viewport — put the start (with inlay) at the top
                doc.getLineStartOffset(maxOf(startLine - 1, 0))
            }

            // Use MAKE_VISIBLE to ensure the offset is in the viewport without
            // unnecessary scrolling. Falls back to CENTER if the offset is already visible.
            editor.scrollingModel.scrollTo(
                editor.offsetToLogicalPosition(offset), ScrollType.MAKE_VISIBLE
            )
            editor.caretModel.moveToOffset(offset)

            flashLineRange(editor, doc, startLine, endLine, color, actionLabel, targetEditor)
        }
    }

    private fun flashLineRange(
        editor: Editor, doc: Document,
        startLine: Int, endLine: Int,
        color: java.awt.Color, actionLabel: String,
        disposableParent: Disposable
    ) {
        // Delegate to shared EditorHighlightSupport, then auto-cleanup after 5s.
        // Pass null for disposable since we manage lifecycle via the coroutine below.
        val handle = EditorHighlightSupport.addHighlight(
            editor, startLine, endLine, color, actionLabel, disposable = null
        )
        scope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            EditorHighlightSupport.removeHighlight(handle)
        }
    }

    private fun selectInProjectView(project: Project, vf: VirtualFile) {
        val now = System.currentTimeMillis()
        if (now - lastProjectViewSelectMs < PROJECT_VIEW_COOLDOWN_MS) return
        lastProjectViewSelectMs = now

        try {
            val twm = ToolWindowManager.getInstance(project)
            val tw = twm.getToolWindow("Project") ?: return
            if (!tw.isVisible) return
            ProjectView.getInstance(project).select(null, vf, false)
        } catch (e: Exception) {
            // warn (not debug) — the user enables this feature explicitly and
            // should see in idea.log if the Project View integration silently fails.
            logger.warn(e) { "[ACP] Follow Agent: error selecting in Project View" }
        }
    }
}
