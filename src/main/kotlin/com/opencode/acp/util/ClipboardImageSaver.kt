package com.opencode.acp.util

import com.intellij.openapi.project.Project
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

/**
 * Saves a clipboard/dropped image to `<projectDir>/.opencode/attachments/` so it
 * has a real on-disk path for the `file://` wire format.
 *
 * The filename format is `clipboard-image-<epochMs>-<8hex>.png` for sortability
 * and collision resistance.
 *
 * @param image The rasterized image to save (always PNG on output).
 * @param project Used to determine the save location. If null or if `project.basePath`
 *   is null, falls back to `user.home/.opencode/attachments/` (which will not be
 *   auto-cleaned on project close — only on IDE shutdown).
 * @return The absolute path of the saved file, or null on failure.
 */
internal fun saveClipboardImageToDisk(
    image: BufferedImage,
    project: Project?
): String? {
    val baseDir = project?.basePath
    if (baseDir == null) {
        logger.warn { "[ACP] No project basePath; saving clipboard image to user.home/.opencode/attachments (will not be auto-cleaned)" }
    }
    val effectiveBase = baseDir ?: System.getProperty("user.home") ?: return null
    val attachmentsDir = File(effectiveBase, ".opencode/attachments")
    if (!attachmentsDir.exists() && !attachmentsDir.mkdirs()) {
        logger.error { "[ACP] Could not create ${attachmentsDir.absolutePath} — clipboard image not saved" }
        return null
    }
    val timestamp = System.currentTimeMillis()
    val randomHex = (1..8).map { "%x".format(kotlin.random.Random.nextInt(16)) }.joinToString("")
    val file = File(attachmentsDir, "clipboard-image-$timestamp-$randomHex.png")
    if (!ImageIO.write(image, "png", file)) {
        logger.error { "[ACP] ImageIO.write returned false for ${file.absolutePath} — unsupported format or I/O error" }
        file.delete() // clean up partial write
        return null
    }
    return file.absolutePath
}
