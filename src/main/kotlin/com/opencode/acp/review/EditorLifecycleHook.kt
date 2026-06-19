package com.opencode.acp.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Subscribes to `EditorFactory` events to apply/remove comment highlights
 * when editors are opened or closed.
 *
 * This class implements [EditorFactoryListener] directly and is registered
 * programmatically in [ReviewCommentManager.init] via
 * `EditorFactory.getInstance().addEditorFactoryListener`.
 *
 * ## Line-shift handling (C1 + C4 fix)
 *
 * There is intentionally NO `DocumentListener` here. IntelliJ's
 * `RangeHighlighter` created with [HighlighterTargetArea.LINES_IN_RANGE] and
 * the `Inlay` created with `relatesToPrecedingText(true)` already shift
 * automatically as the document is edited — the highlighter and inlay follow
 * the text they are anchored to. The previous implementation registered a
 * per-editor `DocumentListener` that, on every line-shifting keystroke, did a
 * full `clearForEditor` + `addHighlights` using the SAME (stale) `startLine`
 * / `endLine` from the JSON — which actively undid IntelliJ's auto-shift and
 * re-pinned highlights to wrong lines, while also leaking the listener (it
 * was parented to the project, not the editor, so it survived until project
 * close). Removing the watcher fixes both the leak and the stale-highlight
 * bug. Comment line numbers in the JSON stay valid because the highlighter
 * tracks the live document.
 *
 * If a comment needs to be marked stale after large edits, a future
 * revision can compare the file's current git hash against `comment.revision`
 * — that lives in the index, not in a per-keystroke listener.
 */
class EditorLifecycleHook(
    private val project: Project,
    private val manager: ReviewCommentManager,
) : EditorFactoryListener, Disposable {

    private val logger = KotlinLogging.logger {}

    /** Per-relative-source-path pre-built line→comments map. Safe to read
     *  from EDT (LineMarkerProvider) — `ConcurrentHashMap` provides thread
     *  visibility; the [LineCommentMap] value itself is immutable. */
    private val lineMaps = ConcurrentHashMap<String, LineCommentMap>()

    /** M10: cache relative path → set of open Editors, invalidated on editor
     *  create/release so [findOpenEditor] is O(1) not O(openEditors).
     *  Uses a set to support multiple editors for the same file (split view). */
    private val openEditorsByPath = ConcurrentHashMap<String, MutableSet<Editor>>()

    /** Get the line map for a file path, building it on demand from the
     *  current index if it hasn't been cached yet. Returns an empty map if
     *  the file has no open comments. Safe to call from EDT. */
    fun lineMapForFile(path: String): LineCommentMap =
        lineMaps.computeIfAbsent(path) {
            val comments = manager.getIndex().openForFile(path)
            if (comments.isEmpty()) LineCommentMap.EMPTY else LineCommentMap.build(comments)
        }

    /** Called by [ReviewCommentManager] when the index changes for a file —
     *  rebuilds the line map and re-applies highlights for the open editor.
     *
     *  EDT DISPATCH: [EditorHighlightSupport.clearForEditor] and [addHighlights]
     *  touch `editor.markupModel` and `editor.inlayModel`, which IntelliJ
     *  documents as requiring EDT. This method is called from background
     *  threads (via [ReviewCommentManager.loadAll] and [updateIndex]), so
     *  the editor API calls are wrapped in [invokeLater] when not already
     *  on EDT — matching the pattern in [ReviewCommentManager.refreshGutterIcons]. */
    fun onFileCommentsChanged(path: String, comments: List<ReviewComment>) {
        if (comments.isEmpty()) {
            lineMaps.remove(path)
        } else {
            lineMaps[path] = LineCommentMap.build(comments)
        }
        val editor = findOpenEditorForPath(path) ?: return
        val applyHighlights = {
            EditorHighlightSupport.clearForEditor(editor)
            if (comments.isNotEmpty()) {
                EditorHighlightSupport.addHighlights(editor, comments, this@EditorLifecycleHook)
            }
        }
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) applyHighlights() else app.invokeLater(applyHighlights)
    }

    // ── EditorFactoryListener ──

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.project != project) return
        val vf = editor.virtualFile ?: return
        val path = PathUtils.relativePath(project, vf) ?: return
        // Cache for O(1) findOpenEditor lookups. Use a set to support
        // multiple editors for the same file (split view).
        val editors = openEditorsByPath.computeIfAbsent(path) { mutableSetOf() }
        editors.add(editor)
        val comments = manager.getIndex().openForFile(path)
        if (comments.isNotEmpty()) {
            lineMaps[path] = LineCommentMap.build(comments)
            EditorHighlightSupport.addHighlights(editor, comments, this@EditorLifecycleHook)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val vf = editor.virtualFile ?: return
        val path = PathUtils.relativePath(project, vf) ?: return
        lineMaps.remove(path)
        val editors = openEditorsByPath[path]
        editors?.remove(editor)
        if (editors.isNullOrEmpty()) openEditorsByPath.remove(path)
        EditorHighlightSupport.clearForEditor(editor)
    }

    // ── Disposable ──

    override fun dispose() {
        // EditorFactory listener auto-removed via Disposable contract
        // (parent = ReviewCommentManager, which is disposed by the platform).
        // No DocumentListeners are registered by this hook (C1/C4 fix), so
        // there is nothing per-editor to clean up here.
        lineMaps.clear()
        openEditorsByPath.clear()
    }

    // ── Private helpers ──

    /** Find an open editor for a given project-relative source path.
     *  O(1) via the [openEditorsByPath] cache, populated on editorCreated.
     *  Returns the first editor when multiple exist (split view).
     *  Public for [ReviewCommentManager.psiFileForPath] fast-path. */
    fun findOpenEditorForPath(path: String): Editor? = openEditorsByPath[path]?.firstOrNull()
}