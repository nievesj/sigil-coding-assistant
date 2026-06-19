package com.opencode.acp.review

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

/**
 * Renders a review comment inlay pill above a highlighted line range.
 * Mirrors [com.opencode.acp.follow.AgentActionRenderer] but uses
 * a different background color scheme for review vs. follow-agent pills.
 */
class ReviewCommentInlayRenderer(
    private val text: String,
    bgColor: Color,
) : EditorCustomElementRenderer {

    /** Background color with boosted alpha for the inlay pill label.
     *
     *  The review highlight itself uses a low-alpha color (~60/255) so it
     *  doesn't obscure the code underneath. The inlay pill, however, sits
     *  ABOVE the line and needs a more opaque background to be readable
     *  against arbitrary editor themes. We triple the source alpha (clamped
     *  to 255) so e.g. alpha=60 → 180 (readable) rather than 60 (washed out).
     *  The clamping is belt-and-suspenders — `Color`'s constructor also clamps. */
    private val bgColor = Color(
        bgColor.red, bgColor.green, bgColor.blue,
        minOf(bgColor.alpha * 3, 255)
    )

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(text) + 16
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }

    // Legacy paint — standard displays
    override fun paint(
        inlay: Inlay<*>, g: Graphics,
        targetRegion: Rectangle, textAttributes: TextAttributes
    ) {
        paintInternal(g, targetRegion, inlay.editor)
    }

    // HiDPI paint — Retina displays
    override fun paint(
        inlay: Inlay<*>, g: Graphics2D,
        targetRegion: Rectangle2D, textAttributes: TextAttributes
    ) {
        val intRect = Rectangle(
            targetRegion.x.toInt(), targetRegion.y.toInt(),
            targetRegion.width.toInt(), targetRegion.height.toInt()
        )
        paintInternal(g, intRect, inlay.editor)
    }

    private fun paintInternal(g: Graphics, region: Rectangle, editor: Editor) {
        if (g is Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        g.color = bgColor
        g.fillRoundRect(region.x, region.y, region.width, region.height, 6, 6)
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.color = editor.colorsScheme.defaultForeground
        val metrics = g.fontMetrics
        val textY = region.y + (region.height + metrics.ascent - metrics.descent) / 2
        g.drawString(text, region.x + 8, textY)
    }
}
