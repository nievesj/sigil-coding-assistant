package com.opencode.acp.chat.util

import java.util.Collections

/**
 * Shared constants for file attachment handling.
 *
 * Single source of truth for values that must stay in sync across multiple
 * attachment-related sites (image dialog filter, clipboard file filter, etc.).
 * Previously, [FileAttachmentService.IMAGE_EXTENSIONS] and the local
 * `imageExts` set in `ClipboardReader.readClipboardOnEdt()` were independent
 * copies that could silently diverge.
 */
object AttachmentConstants {

    /**
     * File extensions recognized as images by the attachment UI.
     *
     * Used by:
     * - [com.opencode.acp.chat.ui.compose.FileAttachmentService.addFileAttachment]
     *   (requireImage gate — rejects non-image files from the image picker dialog)
     * - [com.opencode.acp.chat.ui.compose.ClipboardReader] (clipboard file-list
     *   filter — only image files from the OS file manager are attached; non-image
     *   files are ignored on the clipboard path and must be attached via the
     *   "Attach" button)
     *
     * All extensions are lowercase. Callers must lowercase the file extension
     * before checking membership.
     */
    val IMAGE_EXTENSIONS: Set<String> = Collections.unmodifiableSet(
        setOf("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp")
    )
}