package com.opencode.acp.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque handle for a highlight+inlay pair, used for targeted removal.
 */
class HighlightHandle internal constructor(
    internal val editor: Editor,
    internal val highlighter: RangeHighlighter?,
    internal val inlay: Inlay<*>?,
    internal val disposables: MutableList<Disposable> = mutableListOf(),
    /** The parent disposable that owns this highlight. Set by [EditorHighlightSupport.addHighlight]
     *  to enable O(1) removal in [removeHighlight] without scanning all disposableHighlights. */
    internal var parentDisposable: Disposable? = null,
) {
    /** Prevents double-dispose in concurrent clearForEditor + clearAll race. */
    internal val disposed = AtomicBoolean(false)
}

/**
 * Shared utility for `RangeHighlighter` + `Inlay` creation.
 *
 * Both Follow Agent (transient highlights) and Review Comments (persistent
 * highlights) use this. The decoration behavior (highlight + inlay) is
 * shared; scrolling is NOT — callers that want to scroll to a highlight do
 * so explicitly after calling [addHighlight].
 *
 * Highlight tracking is maintained per-editor and per-disposable so cleanup
 * can be done by either key.
 */
object EditorHighlightSupport {

    private val logger = KotlinLogging.logger {}

    // Thread-safe inner lists: `removeHighlight` runs on a background dispatcher
    // (EditorFollowManager's 5s delayed cleanup coroutine on Dispatchers.Default)
    // while `addHighlight`/`clearForEditor`/`clearAll` run on EDT. ArrayList is not
    // safe under concurrent mutation — CopyOnWriteArrayList is (iteration is snapshot-
    // based and never throws CME). Writes are infrequent (one per highlight), so the
    // copy-on-write overhead is negligible.
    private val editorHighlights = ConcurrentHashMap<Editor, MutableList<HighlightHandle>>()
    private val disposableHighlights = ConcurrentHashMap<Disposable, MutableList<HighlightHandle>>()

    private fun newHandleList(): MutableList<HighlightHandle> = CopyOnWriteArrayList()

    /** Per-parent Disposable registration memoization. We register ONE
     *  cleanup [Disposable] per parent (not per handle) so that a parent
     *  owning N highlights only runs `clearAll` once on disposal instead of
     *  N times. */
    private val registeredParents = java.util.Collections.newSetFromMap(ConcurrentHashMap<Disposable, Boolean>())

    /** Highlight layer for review comments — above follow-agent highlights. */
    private const val REVIEW_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 2

    /** Add a persistent line range highlight with a block inlay label.
     *
     *  [disposable] controls lifecycle — when disposed, both highlighter and
     *  inlay are cleaned up automatically. Returns an opaque handle for later
     *  targeted removal.
     *
     *  NOTE: this method does NOT scroll the editor. Review comments are
     *  persistent decorations and should not hijack the viewport; Follow
     *  Agent callers that want to navigate to the highlight scroll
     *  explicitly via [Editor.scrollingModel] / `OpenFileDescriptor` after
     *  calling this. (C5: removed the unconditional `scrollToCaret` that
     *  caused viewport jumps on every highlight application.) */
    fun addHighlight(
        editor: Editor,
        startLine: Int,
        endLine: Int,
        color: Color,
        label: String,
        disposable: Disposable?,
    ): HighlightHandle {
        val doc = editor.document
        val lineCount = doc.lineCount
        if (startLine <= 0 || endLine <= 0 || startLine > lineCount) {
            // Return a no-op handle for invalid ranges.
            val handle = HighlightHandle(editor, null, null)
            if (disposable != null) trackHandle(editor, handle, disposable)
            return handle
        }

        val hlStart = doc.getLineStartOffset(startLine - 1)
        val hlEnd = doc.getLineEndOffset(minOf(endLine, lineCount) - 1)
        if (hlEnd <= hlStart) {
            val handle = HighlightHandle(editor, null, null)
            if (disposable != null) trackHandle(editor, handle, disposable)
            return handle
        }

        val attrs = TextAttributes().apply {
            backgroundColor = color
            effectColor = color
            effectType = com.intellij.openapi.editor.markup.EffectType.BOXED
        }
        val hl = editor.markupModel.addRangeHighlighter(
            hlStart, hlEnd,
            REVIEW_HIGHLIGHT_LAYER,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE
        )

        // Gutter stripe: a thin vertical bar to the left of the line numbers,
        // similar to VCS change markers, showing the full span of the comment.
        hl.lineMarkerRenderer = ReviewCommentLineMarkerRenderer(color)

        // Right-side error stripe mark on the scrollbar, also colored by severity.
        hl.setErrorStripeMarkColor(color.darker())
        hl.setThinErrorStripeMark(true)

        val inlay = editor.inlayModel.addBlockElement(
            hlStart,
            InlayProperties()
                .relatesToPrecedingText(true)
                .showAbove(true),
            ReviewCommentInlayRenderer(label, color)
        )

        val handle = HighlightHandle(editor, hl, inlay)
        handle.parentDisposable = disposable
        // Track in editorHighlights even when disposable is null, so
        // clearForEditor() can clean up orphaned handles (e.g., flashLineRange
        // with disposable=null whose delayed coroutine was cancelled).
        editorHighlights.computeIfAbsent(editor) { newHandleList() }.add(handle)
        if (disposable != null) {
            disposableHighlights.computeIfAbsent(disposable) { newHandleList() }.add(handle)
            // C6/M8: register ONE cleanup Disposable per parent, memoized.
            if (registeredParents.add(disposable)) {
                Disposer.tryRegister(disposable, object : Disposable {
                    override fun dispose() {
                        clearAll(disposable)
                        registeredParents.remove(disposable)
                    }
                })
            }
        }
        return handle
    }

    /** Convenience: apply highlights for a list of comments to an editor.
     *  Maps each comment's severity to a color and creates one [HighlightHandle]
     *  per comment. Returns the list of handles (for later targeted removal). */
    fun addHighlights(
        editor: Editor,
        comments: List<ReviewComment>,
        disposable: Disposable?,
    ): List<HighlightHandle> {
        return comments.map { c ->
            val color = when (c.severity) {
                ReviewSeverity.ERROR -> Color(255, 80, 80, 60)
                ReviewSeverity.WARNING -> Color(255, 180, 50, 60)
                ReviewSeverity.INFO -> Color(80, 160, 255, 60)
            }
            addHighlight(editor, c.startLine, c.endLine, color, c.comment.take(80), disposable)
        }
    }

    /** Remove a specific highlight by handle. O(1) via parentDisposable lookup. */
    fun removeHighlight(handle: HighlightHandle) {
        disposeHandle(handle)
        editorHighlights[handle.editor]?.remove(handle)
        handle.parentDisposable?.let { disposableHighlights[it]?.remove(handle) }
    }

    /** Remove ALL highlights previously added to [editor] via this object. */
    fun clearForEditor(editor: Editor) {
        val handles = editorHighlights.remove(editor) ?: return
        for (h in handles) {
            disposeHandle(h)
            // Use parentDisposable for O(1) lookup instead of scanning all entries
            h.parentDisposable?.let { disposableHighlights[it]?.remove(h) }
        }
    }

    /** Remove all highlights parented by [disposable]. */
    fun clearAll(disposable: Disposable) {
        val handles = disposableHighlights.remove(disposable) ?: return
        for (h in handles) {
            disposeHandle(h)
            editorHighlights[h.editor]?.remove(h)
        }
    }

    // ── Private helpers ──

    private fun trackHandle(editor: Editor, handle: HighlightHandle, disposable: Disposable?) {
        if (disposable == null) return
        handle.parentDisposable = disposable
        editorHighlights.computeIfAbsent(editor) { newHandleList() }.add(handle)
        disposableHighlights.computeIfAbsent(disposable) { newHandleList() }.add(handle)

        // C6/M8: register ONE cleanup Disposable per parent, memoized.
        // Without this, N highlights shared one parent would register N
        // Disposables each calling clearAll(parent) — the first removes all
        // N, the rest iterate an already-removed list. Memoizing to one
        // registration per parent makes disposal O(N) not O(N²).
        if (registeredParents.add(disposable)) {
            Disposer.tryRegister(disposable, object : Disposable {
                override fun dispose() {
                    clearAll(disposable)
                    registeredParents.remove(disposable)
                }
            })
        }
    }

    /** Renders a narrow colored vertical bar in the gutter area for the
     *  full line range of a review comment, like the git change markers. */
    private class ReviewCommentLineMarkerRenderer(private val color: Color) : LineMarkerRenderer {
        override fun paint(editor: com.intellij.openapi.editor.Editor, g: Graphics, r: Rectangle) {
            g.color = color
            val stripeWidth = 3.coerceAtMost(r.width.coerceAtLeast(1))
            // Align to the left edge of the gutter marker area.
            g.fillRect(r.x + 1, r.y, stripeWidth, r.height)
        }
    }

    private fun disposeHandle(handle: HighlightHandle) {
        if (!handle.disposed.compareAndSet(false, true)) return  // already disposed
        try {
            handle.highlighter?.let { handle.editor.markupModel.removeHighlighter(it) }
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] Failed to remove highlighter during dispose" }
        }
        try {
            handle.inlay?.dispose()
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] Failed to dispose inlay during dispose" }
        }
        for (d in handle.disposables) {
            try { Disposer.dispose(d) } catch (e: Exception) {
                logger.debug(e) { "[ACP] Failed to dispose child disposable during dispose" }
            }
        }
        handle.disposables.clear()
    }
}