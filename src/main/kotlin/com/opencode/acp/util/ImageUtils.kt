package com.opencode.acp.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Maximum dimension (width or height) for decoded preview bitmaps.
 * Thumbnails in the chat UI are typically 60–100 dp (~200 px at 2x density).
 * Capping at 400 px keeps memory under ~640 KB (400×400 RGBA) even for
 * multi-megapixel originals.
 */
internal const val MAX_PREVIEW_DIMENSION = 400

/**
 * Reads an image file from disk, downsamples to [MAX_PREVIEW_DIMENSION],
 * and returns a Compose [ImageBitmap], or null if:
 *   - the path is blank
 *   - the file does not exist or cannot be read
 *   - the format is not recognized by [ImageIO] (e.g., SVG — JDK has no
 *     built-in SVG reader; use a library or convert before attaching)
 *   - decoding fails (corrupt file, I/O error, OOM)
 *
 * Used by all UI image-preview call sites (replaces the base64-data-URI approach).
 *
 * **Threading:** This function performs synchronous disk I/O and image decoding.
 * Callers MUST NOT call it on the EDT. Use it inside a background coroutine
 * (e.g., `withContext(Dispatchers.IO) { decodeFileToBitmap(path) }`) and observe
 * the result via `mutableStateOf` from the composable.
 */
internal fun decodeFileToBitmap(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.exists() || !file.canRead()) return null
    return try {
        val bufferedImage: BufferedImage = ImageIO.read(file) ?: return null
        val scaled = downsample(bufferedImage, MAX_PREVIEW_DIMENSION)
        bufferedImage.flush() // Release native raster data after downsampling
        scaled.toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/**
 * Downsamples a [BufferedImage] so that both width and height are ≤ [maxDimension],
 * preserving aspect ratio. If the image is already within bounds, returns it unchanged.
 * Returns a new [BufferedImage] in TYPE_INT_ARGB (safe for Compose).
 */
private fun downsample(image: BufferedImage, maxDimension: Int): BufferedImage {
    val w = image.width
    val h = image.height
    if (w <= maxDimension && h <= maxDimension) return image

    val scale = minOf(maxDimension.toDouble() / w, maxDimension.toDouble() / h)
    val newW = (w * scale).toInt()
    val newH = (h * scale).toInt()

    val scaled = BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB)
    val g2d = scaled.createGraphics()
    try {
        g2d.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g2d.drawImage(image, 0, 0, newW, newH, null)
    } finally {
        g2d.dispose()
    }
    return scaled
}
