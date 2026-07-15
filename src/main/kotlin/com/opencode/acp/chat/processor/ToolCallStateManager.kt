package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.chat.model.MessagePart
import com.opencode.acp.chat.model.MessageState
import com.opencode.acp.chat.model.PartState
import com.opencode.acp.chat.model.ToolCallPill
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages tool call pill and part-state lifecycle: create, update status,
 * set state, and snapshot.
 *
 * Thread safety: acquires [stateLock] for all operations. [updateStatus] and
 * [setState] call [MessageMapManager.update] which also acquires [stateLock] �
 * this is safe because [ReentrantLock] allows nested acquisition from the same
 * thread.
 *
 * @param toolCallState Tool call state - used for tool call pills, index, and
 *        part states.
 * @param stateLock Shared reentrant lock � same instance as SessionState's.
 * @param messageMap Message cache manager � used to update message parts when
 *        tool call status/state changes.
 * @param sessionManager Session manager � used for tool output truncation
 *        ([SessionManager.maybeTruncateToolOutput]).
 * @param logger Shared logger instance.
 */
internal class ToolCallStateManager(
    private val toolCallState: ToolCallState,
    private val stateLock: ReentrantLock,
    private val messageMap: MessageMapManager,
    private val sessionManager: SessionManager,
    private val logger: KLogger,
) {
    /**
     * Update tool call status with optional output truncation.
     * Applies tool output truncation if enabled in settings (opt-in).
     * Truncation is UI-only � the server receives the original, untruncated
     * output via SSE; truncation happens client-side after the SSE event is parsed.
     */
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun updateStatus(toolCallId: String, status: ToolCallStatus, output: List<JsonObject>? = null) = stateLock.withLock {
        val truncatedOutput = if (output != null) {
            sessionManager.maybeTruncateToolOutput(
                toolName = toolCallState.toolCallPills[toolCallId]?.toolName ?: "tool",
                output = output
            )
        } else null
        val newPartState = when (status) {
            ToolCallStatus.COMPLETED -> PartState.Completed
            ToolCallStatus.FAILED -> PartState.Failed(
                truncatedOutput?.joinToString("\n") { obj ->
                    try { obj["text"]?.jsonPrimitive?.contentOrNull ?: obj.toString() } catch (_: Exception) { obj.toString() }
                }?.let { text -> if (text.length > 500) text.take(500) + "�[truncated]" else text } ?: "Tool error"
            )
            ToolCallStatus.PENDING -> PartState.Pending
            ToolCallStatus.IN_PROGRESS -> PartState.InProgress
            else -> PartState.InProgress
        }
        toolCallState.toolPartStates[toolCallId] = newPartState

        val targetMsgId = toolCallState.toolCallIndex[toolCallId]
        // If the tool call was evicted (message exceeded MAX_MESSAGE_HISTORY),
        // don't fall back to activeMessageId � that would misroute the result
        // to the wrong message. Log and skip instead.
        if (targetMsgId == null) {
            logger.warn { "[ACP] updateToolCallStatus: toolCallId=$toolCallId not in index (evicted?) � skipping to avoid misrouting to activeMessageId" }
            return@withLock
        }
        messageMap.update(targetMsgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            val existing = parts[toolCallId]
            if (existing is MessagePart.ToolCall) {
                parts[toolCallId] = existing.copy(
                    pill = existing.pill.copy(status = status, output = truncatedOutput ?: existing.pill.output),
                    state = newPartState
                )
            } else {
                // Fallback: try to use pill from toolCallState.toolCallPills (has proper kind/title)
                val existingPill = toolCallState.toolCallPills[toolCallId]
                parts[toolCallId] = MessagePart.ToolCall(
                    pill = existingPill?.copy(status = status, output = truncatedOutput ?: existingPill.output)
                        ?: ToolCallPill(toolCallId = toolCallId, toolName = "tool", title = "tool", kind = ToolKind.OTHER, status = status, output = truncatedOutput),
                    state = newPartState
                )
            }
            msg.copy(parts = parts)
        }
        val pill = toolCallState.toolCallPills[toolCallId]
        if (pill != null) {
            toolCallState.toolCallPills[toolCallId] = pill.copy(status = status, output = truncatedOutput ?: pill.output)
        }
    }

    /**
     * Set tool part state and sync pill status.
     */
    fun setState(toolCallId: String, state: PartState) = stateLock.withLock {
        toolCallState.toolPartStates[toolCallId] = state
        val pill = toolCallState.toolCallPills[toolCallId]
        val pillStatus = when (state) {
            PartState.Completed -> ToolCallStatus.COMPLETED
            is PartState.Failed -> ToolCallStatus.FAILED
            PartState.Pending -> ToolCallStatus.PENDING
            PartState.InProgress -> ToolCallStatus.IN_PROGRESS
            else -> pill?.status ?: ToolCallStatus.IN_PROGRESS
        }
        if (pill != null) {
            toolCallState.toolCallPills[toolCallId] = pill.copy(status = pillStatus)
        }
        val targetMsgId = toolCallState.toolCallIndex[toolCallId]
        // If the tool call was evicted (message exceeded MAX_MESSAGE_HISTORY),
        // don't fall back to activeMessageId � that would misroute the result
        // to the wrong message. Log and skip instead, matching updateToolCallStatus.
        if (targetMsgId == null) {
            logger.warn { "[ACP] setToolPartState: toolCallId=$toolCallId not in index (evicted?) � skipping to avoid misrouting to activeMessageId" }
            return@withLock
        }
        messageMap.update(targetMsgId) { msg ->
            val parts = LinkedHashMap(msg.parts)
            val existing = parts[toolCallId]
            if (existing is MessagePart.ToolCall) {
                parts[toolCallId] = existing.copy(state = state)
            } else {
                // Fallback: the part at toolCallId is not a ToolCall (key collision or
                // overwrite). Create a new ToolCall part so the UI reflects the new state,
                // matching updateStatus's fallback behavior.
                val existingPill = toolCallState.toolCallPills[toolCallId]
                parts[toolCallId] = MessagePart.ToolCall(
                    pill = existingPill?.copy(status = pillStatus)
                        ?: ToolCallPill(toolCallId = toolCallId, toolName = "tool", title = "tool", kind = ToolKind.OTHER, status = pillStatus),
                    state = state
                )
            }
            msg.copy(parts = parts)
        }
    }

    /**
     * Snapshot tool states and pills under stateLock for consistent reads from
     *  external coroutines (e.g., activity monitor in OpenCodeService).
     *
     *  Without the lock, the event processing coroutine could mutate these maps
     *  mid-snapshot, producing an inconsistent view (e.g., a tool appears
     *  InProgress in partStates but Completed in pills). The lock is ReentrantLock
     *  and the read is O(n) � brief enough to not cause contention.
     *
     *  Returns a Triple of:
     *   1. partStates values (List<PartState>)
     *   2. toolCallPills entries (List<Map.Entry<String, ToolCallPill>>)
     *   3. toolPartStates map copy (Map<String, PartState>)
     */
    fun snapshot(): Triple<List<PartState>, List<Map.Entry<String, ToolCallPill>>, Map<String, PartState>> {
        return stateLock.withLock {
            Triple(
                toolCallState.toolPartStates.values.toList(),
                toolCallState.toolCallPills.entries.toList(),
                toolCallState.toolPartStates.toMap(),
            )
        }
    }
}