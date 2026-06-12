package com.opencode.acp.follow

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * Renders "Agent is reading/editing" block inlay label above highlighted region.
 *
 * Overrides both legacy and HiDPI `paint()` overloads for maximum compatibility:
 * - Legacy: handles standard displays. Uses Graphics APIs only.
 * - HiDPI: handles Retina displays. Rounds Rectangle2D to ints.
 */
class AgentActionRenderer(
    private val text: String,
    bgColor: Color
) : EditorCustomElementRenderer {

    /** Boosted alpha for readable inlay label background. */
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
        targetRegion: java.awt.geom.Rectangle2D, textAttributes: TextAttributes
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
