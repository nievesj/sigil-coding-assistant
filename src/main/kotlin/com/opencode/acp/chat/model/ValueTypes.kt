package com.opencode.acp.chat.model

/**
 * Type-safe ID value types for new/extracted code.
 *
 * CONVENTION ONLY — do NOT migrate 50+ existing signatures. Use these in new
 * and extracted code. They do NOT cross the XStream boundary (not used in
 * OpenCodeSettingsState or CommandHistoryEntry).
 *
 * CRITICAL: No `init { require(...) }` — that throws on deserialization.
 * Validation via factory function [SessionId.create] returning Result.
 */

/** Type-safe session identifier. */
@JvmInline value class SessionId(val value: String) {
    override fun toString() = value
    companion object {
        /** Validates and creates a SessionId. Session IDs start with "ses_". */
        fun create(value: String): Result<SessionId> =
            if (value.startsWith("ses_")) Result.success(SessionId(value))
            else Result.failure(IllegalArgumentException("SessionId must start with ses_"))
    }
}

/** Type-safe message identifier. */
@JvmInline value class MessageId(val value: String) {
    override fun toString() = value
}

/** Type-safe part identifier. */
@JvmInline value class PartId(val value: String) {
    override fun toString() = value
}

/** Type-safe tool call identifier. */
@JvmInline value class ToolCallId(val value: String) {
    override fun toString() = value
}

/** Provider + model identity pair, replacing the (modelID, providerID) data clump. */
data class ModelIdentity(val providerID: String, val modelID: String)

/** File identity triple for FileReadCache, replacing the (path, mtimeMs, sizeBytes) data clump. */
data class FileIdentity(val path: String, val mtimeMs: Long, val sizeBytes: Long)

/**
 * Event routing info value object, composing the (sessionId, messageId, partId) data clump.
 * Uses raw String (not value class) to avoid forced migration of existing handlers.
 */
data class EventRoutingInfo(
    val sessionId: String,
    val messageId: String? = null,
    val partId: String? = null,
)