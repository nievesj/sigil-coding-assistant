package com.opencode.acp.chat.processor

import kotlinx.serialization.json.JsonObject

/**
 * Minimal interface exposing only the [SessionManager] methods used by [SessionState]
 * and its collaborators ([ToolCallStateManager], signal forwarding).
 *
 * Extracted to break the dependency on the concrete [SessionManager] class, enabling
 * unit testing of [SessionState] without a real IntelliJ
 * [com.intellij.openapi.project.Project] (and the full [SessionManager] init logic
 * that requires it). [SessionManager] implements this interface.
 *
 * See TDD §4.2.4 — extracting this interface unblocks the @Disabled SessionStateTest.
 */
interface SessionStateContext {
    /** Forward a per-session UI signal to the global merged signal flow. */
    fun emitSessionSignal(sessionId: String, signal: UiSignal)

    /**
     * Truncate tool output if truncation is enabled in settings.
     * Called by [ToolCallStateManager] before storing tool results.
     * Returns the (possibly truncated) output list, or the original list if
     * truncation is disabled.
     */
    fun maybeTruncateToolOutput(toolName: String, output: List<JsonObject>): List<JsonObject>
}