package com.opencode.acp.chat.util

import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.processor.UiSignal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * Interface for Follow Agent dispatch, enabling testability of [com.opencode.acp.chat.processor.SessionState]
 * without requiring a real IntelliJ [com.intellij.openapi.project.Project].
 *
 * Uses raw String (not ToolCallId value class) for backward compatibility with
 * existing callers. Value types are a convention for new/extracted code only.
 *
 * See TDD §4.2.4 — extracting this interface unblocks the @Disabled SessionStateTest
 * and FollowAgentDispatcherTest.
 */
interface FollowAgentDispatcherInterface {
    fun dispatchToolUse(
        toolCallId: String,
        toolName: String,
        toolKind: ToolKind,
        input: JsonObject?,
        metadata: JsonObject?,
        startTimeMs: Long?,
        isDuplicate: Boolean = false,
        existingPill: ToolCallPill? = null,
    )

    fun dispatchToolResult(
        toolCallId: String,
        resolvedKind: ToolKind,
        content: List<JsonObject>?,
        isError: Boolean,
        isOrphan: Boolean = false,
        input: JsonObject? = null,
        metadata: JsonObject? = null,
        signals: MutableSharedFlow<UiSignal> = MutableSharedFlow(),
    )
}