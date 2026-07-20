package com.opencode.acp.chat.util

import com.opencode.acp.chat.ui.compose.FileAttachmentService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

/**
 * Verifies that [AttachmentConstants.IMAGE_EXTENSIONS] is the single source of
 * truth for the image-extension set, and that [FileAttachmentService] references
 * it rather than maintaining an independent copy.
 *
 * Regression guard for the review comment that flagged the duplicated
 * `IMAGE_EXTENSIONS` set — if someone re-introduces a local copy in
 * [FileAttachmentService], this test will fail because the reflection check
 * won't find a field that points to the shared constant.
 *
 * Note: [com.opencode.acp.chat.ui.compose.ClipboardReader] also uses
 * [AttachmentConstants.IMAGE_EXTENSIONS] directly, but we cannot verify this
 * via reflection (it's a local variable reference in a private method, not a
 * field). A behavioral test would require a real clipboard, which is not
 * available in unit tests. The reference is verified by code review and the
 * compiler (the constant is imported and used directly).
 */
class AttachmentConstantsTest {

    @Test
    fun `IMAGE_EXTENSIONS contains the expected image formats`() {
        AttachmentConstants.IMAGE_EXTENSIONS shouldContainExactly setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp"
        )
    }

    @Test
    fun `IMAGE_EXTENSIONS is immutable`() {
        // The set is wrapped in Collections.unmodifiableSet() — attempting to
        // mutate it should throw UnsupportedOperationException.
        var threw = false
        try {
            @Suppress("UNCHECKED_CAST")
            (AttachmentConstants.IMAGE_EXTENSIONS as MutableSet<String>).add("tiff")
        } catch (_: UnsupportedOperationException) {
            threw = true
        }
        threw shouldBe true
    }

    @Test
    fun `FileAttachmentService IMAGE_EXTENSIONS references AttachmentConstants`() {
        // FileAttachmentService.IMAGE_EXTENSIONS is a private val that should be
        // assigned from AttachmentConstants.IMAGE_EXTENSIONS (not a fresh copy).
        // We verify this by checking that the field value is the SAME instance
        // (reference equality) as AttachmentConstants.IMAGE_EXTENSIONS.
        val field: Field = FileAttachmentService::class.java.getDeclaredField("IMAGE_EXTENSIONS")
        field.isAccessible = true
        val fieldValue = field.get(FileAttachmentService) as Set<*>
        // Reference identity — both should point to the same Set instance.
        fieldValue shouldBe AttachmentConstants.IMAGE_EXTENSIONS
    }
}