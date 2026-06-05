package com.opencode.acp.chat.ui.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads provider icon SVGs bundled in resources/icons/provider/ as Compose [ImageBitmap].
 *
 * Uses Skia's SVGDOM renderer (available via Compose Desktop runtime) to rasterize
 * SVGs with transparent backgrounds at the requested size.
 */
object ProviderIconLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val missingCache = ConcurrentHashMap.newKeySet<String>()

    /**
     * Returns an [ImageBitmap] for the given provider ID, or null if no icon exists.
     * Icons are cached after first load.
     */
    fun load(providerId: String, size: Int = 20): ImageBitmap? {
        if (providerId.isBlank()) return null
        if (providerId in missingCache) return null

        cache[providerId]?.let { return it }

        val bytes = loadSvgBytes(providerId) ?: run {
            missingCache.add(providerId)
            return null
        }

        return try {
            val image = renderSvgToImage(bytes, size, size)
            val bitmap = image.toComposeImageBitmap()
            cache[providerId] = bitmap
            bitmap
        } catch (_: Exception) {
            missingCache.add(providerId)
            null
        }
    }

    private fun loadSvgBytes(providerId: String): ByteArray? {
        val path = "/icons/provider/$providerId.svg"
        return javaClass.getResourceAsStream(path)?.use { it.readBytes() }
    }

    private fun renderSvgToImage(svgBytes: ByteArray, width: Int, height: Int): Image {
        val data = Data.makeFromBytes(svgBytes)
        val dom = SVGDOM(data)

        // Use the SVG's intrinsic dimensions if available, otherwise use requested size
        val svgWidth = dom.root?.width?.value?.takeIf { it > 0 }?.toInt() ?: width
        val svgHeight = dom.root?.height?.value?.takeIf { it > 0 }?.toInt() ?: height

        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas

        // Scale to fit the requested size
        val scaleX = width.toFloat() / svgWidth
        val scaleY = height.toFloat() / svgHeight
        val scale = minOf(scaleX, scaleY)

        canvas.translate(
            (width - svgWidth * scale) / 2f,
            (height - svgHeight * scale) / 2f
        )
        canvas.scale(scale, scale)

        dom.render(canvas)
        return surface.makeImageSnapshot()
    }
}
