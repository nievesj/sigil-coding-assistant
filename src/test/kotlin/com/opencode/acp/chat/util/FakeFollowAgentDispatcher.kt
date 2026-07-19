package com.opencode.acp.chat.util

import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.chat.processor.UiSignal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * No-op Follow Agent dispatcher for tests. Records calls for verification.
 * Unblocks SessionStateTest and FollowAgentDispatcherTest (@Disabled tests).
 */
class FakeFollowAgentDispatcher : FollowAgentDispatcherInterface {
    val toolUseCalls = mutableListOf<Triple<String, String, ToolKind>>()
    val toolResultCalls = mutableListOf<Triple<String, ToolKind, Boolean>>()

    override fun dispatchToolUse(
        toolCallId: String,
        toolName: String,
        toolKind: ToolKind,
        input: JsonObject?,
        metadata: JsonObject?,
        startTimeMs: Long?,
        isDuplicate: Boolean,
        existingPill: ToolCallPill?,
    ) {
        toolUseCalls.add(Triple(toolCallId, toolName, toolKind))
    }

    override fun dispatchToolResult(
        toolCallId: String,
        resolvedKind: ToolKind,
        content: List<JsonObject>?,
        isError: Boolean,
        isOrphan: Boolean,
        input: JsonObject?,
        metadata: JsonObject?,
        signals: MutableSharedFlow<UiSignal>,
    ) {
        toolResultCalls.add(Triple(toolCallId, resolvedKind, isError))
    }
}