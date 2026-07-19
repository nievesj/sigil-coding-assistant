package com.opencode.acp.chat.ui.compose

import com.intellij.openapi.project.Project
import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.util.saveClipboardImageToDisk
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger {}

/** Result of reading the clipboard — either an image/file attachment or plain text. */
sealed class ClipboardResult {
    data class FileResult(val files: List<AttachedFile>) : ClipboardResult()
    data class TextResult(val text: String) : ClipboardResult()
}

/**
 * Reads the system clipboard for images, files, or text.
 *
 * Extracted from `InputArea.kt` (Phase 4 — TDD `docs/tdd/ui-testability-refactor.md` §9 step 9).
 * All clipboard I/O is consolidated here for DRY and testability. The existing
 * `InputAreaClipboardTest` regression test targets the top-level wrapper in `InputArea.kt`
 * which delegates to [ClipboardReader.readClipboardContent].
 */
object ClipboardReader {

    /**
     * Reads the system clipboard for images, files, or text.
     * Returns [ClipboardResult] if content is present, null otherwise.
     *
     * AWT clipboard access MUST happen on the Event Dispatch Thread.
     *
     * @param project Used to determine the save location for clipboard images.
     *   If null, images are saved to `user.home` (will not be auto-cleaned).
     */
    suspend fun readClipboardContent(project: Project? = null): ClipboardResult? {
        // ALWAYS use invokeLater + withTimeoutOrNull, even when already on EDT.
        // The direct EDT path (readClipboardOnEdt() with no timeout) can block for
        // 5-9 seconds when another app holds the OLE clipboard lock (Windows
        // OleGetClipboard), freezing the entire IDE. The invokeLater path posts
        // the clipboard read as the next EDT event and times out after 5s if the
        // clipboard is locked, returning null instead of hanging.
        val clipResult: Any? = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val deferred = kotlinx.coroutines.CompletableDeferred<Any?>()
                // Cancellation flag: if the timeout fires, the invokeLater callback
                // checks this flag before performing the expensive clipboard read.
                // This prevents the EDT from being blocked by a clipboard read that
                // nobody is waiting for anymore (resource leak fix).
                val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    // Re-check `cancelled` right before the expensive clipboard read.
                    // If the timeout already fired (cancelled == true), bail early to
                    // avoid wasting EDT time on a clipboard read whose result will be
                    // discarded (the awaiter already got null from the timeout path).
                    // This closes the benign race between the timeout returning null
                    // and the invokeLater completing the deferred — the result is no
                    // longer computed when the caller has given up.
                    if (!cancelled.get()) {
                        deferred.complete(readClipboardOnEdt())
                    }
                }
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() } ?: run {
                    // Mark as cancelled so the invokeLater callback (if it hasn't run yet)
                    // skips the expensive clipboard read.
                    cancelled.set(true)
                    logger.warn { "[ACP] readClipboardContent: EDT timed out after 5s — clipboard read failed (clipboard may be locked by another application)" }
                    null
                }
            } catch (e: SecurityException) {
                logger.warn(e) { "[ACP] readClipboardContent: clipboard access denied (SecurityException)" }
                null
            } catch (e: Exception) {
                logger.debug(e) { "[ACP] readClipboardContent: clipboard read failed" }
                null
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                when (clipResult) {
                    is java.awt.Image -> {
                        val bufferedImage = clipResult.toBufferedImage()
                        val savedPath = saveClipboardImageToDisk(bufferedImage, project)
                        if (savedPath != null) {
                            // No path validation needed here: saveClipboardImageToDisk
                            // always writes inside .opencode/attachments/ by construction,
                            // so the path is guaranteed to be within the allowed dir.
                            val filename = java.io.File(savedPath).name
                            ClipboardResult.FileResult(listOf(AttachedFile(name = filename, path = savedPath, mime = "image/png")))
                        } else {
                            logger.warn { "[ACP] Clipboard image save failed; skipping attachment" }
                            null
                        }
                    }
                    is List<*> -> {
                        val files = clipResult.filterIsInstance<java.io.File>()
                        if (files.isEmpty()) return@withContext null
                        ClipboardResult.FileResult(files.mapNotNull { it.toAttachedFile(project) })
                    }
                    is String -> {
                        if (clipResult.isNotBlank()) {
                            ClipboardResult.TextResult(clipResult)
                        } else null
                    }
                    else -> null
                }
            } catch (e: Exception) {
                logger.debug(e) { "[ACP] readClipboardContent: failed to read clipboard" }
                null
            }
        }
    }

    /**
     * Reads clipboard content on the EDT. Returns java.awt.Image, List<java.io.File>, String (text), or null.
     */
    private fun readClipboardOnEdt(): Any? {
        val startTime = System.currentTimeMillis()
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val transferable = clipboard.getContents(null)
            val clipAcquireMs = System.currentTimeMillis() - startTime
            if (clipAcquireMs > 500) {
                logger.warn { "[ACP] readClipboardOnEdt: clipboard acquisition took ${clipAcquireMs}ms (slow — another app may hold the clipboard lock)" }
            }
            if (transferable == null) {
                return null
            }

            val flavors = transferable.transferDataFlavors

            // 1. Try stringFlavor / plain text FIRST — if the clipboard has text
            //    content, it should be treated as text even if it also has an image
            //    representation (many apps put both on the clipboard).
            for (flavor in flavors) {
                if (flavor.mimeType.startsWith("text/plain") && flavor.isRepresentationClassReader) {
                    try {
                        val reader = transferable.getTransferData(flavor) as? java.io.Reader
                        if (reader != null) {
                            val text = reader.readText()
                            if (text.isNotBlank()) {
                                return text
                            }
                        }
                    } catch (e: SecurityException) {
                        logger.warn(e) { "[ACP] readClipboardOnEdt: clipboard access denied (SecurityException)" }
                    } catch (e: Exception) {
                        logger.debug(e) { "[ACP] readClipboardOnEdt: text flavor read failed" }
                    }
                }
            }
            // Also try stringFlavor directly (common for macOS)
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
                    if (text != null && text.isNotBlank()) {
                        return text
                    }
                } catch (e: SecurityException) {
                    logger.warn(e) { "[ACP] readClipboardOnEdt: stringFlavor access denied (SecurityException)" }
                } catch (e: Exception) {
                    logger.debug(e) { "[ACP] readClipboardOnEdt: string flavor read failed" }
                }
            }

            // 2. Try javaFileListFlavor (files from OS file manager) — only return
            //    actual image files here; non-image files are intentionally ignored.
            //    Non-image files should be attached via the "Attach" button (which
            //    runs the full copy-into-allowed-dir + MIME detection path). The
            //    clipboard path is for images and text only.
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File>
                if (files != null && files.isNotEmpty()) {
                    val imageExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")
                    val imageFiles = files.filter { f ->
                        f.extension.lowercase() in imageExts
                    }
                    if (imageFiles.isNotEmpty()) {
                        return imageFiles
                    }
                }
            }

            // 3. Try imageFlavor (screenshot, copied image from image editor) —
            //    last priority since screenshots typically don't also have text.
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val image = transferable.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
                if (image != null) {
                    return image
                }
            }

        } catch (e: SecurityException) {
            logger.warn(e) { "[ACP] readClipboardOnEdt: clipboard access denied (SecurityException)" }
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] readClipboardOnEdt: clipboard access failed" }
        }
        val totalMs = System.currentTimeMillis() - startTime
        if (totalMs > 500) {
            logger.warn { "[ACP] readClipboardOnEdt: total clipboard read took ${totalMs}ms" }
        }
        return null
    }
}

/**
 * Converts a java.awt.Image to a BufferedImage.
 *
 * Extracted from `InputArea.kt` — used by both [ClipboardReader] (clipboard image
 * path) and the drag-and-drop AWT fallback in `InputArea` (dropped image path).
 */
internal fun java.awt.Image.toBufferedImage(): BufferedImage {
    if (this is BufferedImage) return this
    val width = getWidth(null)
    val height = getHeight(null)
    if (width <= 0 || height <= 0) return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = bufferedImage.createGraphics()
    try {
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return bufferedImage
}

/**
 * Converts a java.io.File into an AttachedFile by reading its MIME type from the filename.
 * No bytes are read — the file content is referenced by path on the wire.
 *
 * If [sourceFile] lives outside the allowed attachment directories (project base,
 * `<project>/.opencode/attachments/`, `<user.home>/.opencode/attachments/`), it is
 * copied into `<project>/.opencode/attachments/` first so the security guard in
 * `OpenCodeService.sendMessageInternal()` doesn't silently drop it. The attachment
 * chip shows the copied path. If the copy fails, the original path is used and the
 * service guard will log/skip it.
 *
 * Extracted from `InputArea.kt` — used by both [ClipboardReader] (clipboard file list
 * path) and the drag-and-drop handlers in `InputArea`.
 */
internal fun java.io.File.toAttachedFile(project: Project? = null): AttachedFile? {
    val mime = com.opencode.acp.util.MimeTypes.guessFromFileName(name)
    val copied = if (project != null) {
        com.opencode.acp.util.copyExternalAttachmentToAllowedDir(this, project)
    } else null
    val effectivePath = if (copied != null) {
        copied.absolutePath
    } else {
        // Copy failed — check if source is within allowed dirs
        val canonicalSource = canonicalPath
        val projectBase = project?.basePath?.let { com.opencode.acp.chat.util.AttachmentPathValidator.canonicalizeOrReject(it) }
        val userHome = System.getProperty("user.home")?.let { com.opencode.acp.chat.util.AttachmentPathValidator.canonicalizeOrReject(it) }
        if (!com.opencode.acp.chat.util.AttachmentPathValidator.isAllowed(canonicalSource, projectBase, userHome)) {
            // Defense-in-depth: skip the file entirely instead of returning a path
            // outside allowed dirs. The server-side AttachmentValidator is the real
            // guard, but this prevents sending arbitrary paths (e.g., C:\Windows\System32\config\sam)
            // to the server when the copy fails.
            logger.warn { "[ACP] toAttachedFile: file outside allowed dirs and copy failed — skipping: $name ($absolutePath)" }
            return null
        }
        canonicalSource
    }
    return AttachedFile(name = name, path = effectivePath, mime = mime)
}