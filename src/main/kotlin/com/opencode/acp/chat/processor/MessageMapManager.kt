package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.ChatConstants
import com.opencode.acp.chat.model.ChatMessage
import com.opencode.acp.chat.model.MessagePart
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the per-session message cache: add, remove, replace, and update
 * operations with FIFO eviction and tool-index cleanup.
 *
 * Thread safety: acquires [stateLock] for all operations. [update] is
 * reentrant-safe — it can be called from within other `stateLock.withLock`
 * blocks (e.g., from [TextStreamingManager.resegmentDirect] which already
 * holds the lock). [ReentrantLock] counts acquisitions per thread, so nested
 * acquisition from the same thread does not deadlock.
 *
 * @param ctx Shared processor context — used for tool call index cleanup
 *        during eviction and replacement.
 * @param stateLock Shared reentrant lock — same instance as SessionState's.
 * @param messages The message cache StateFlow — shared by reference with
 *        SessionState, which exposes it as a public read-only [StateFlow].
 * @param isClosed Callback to check whether the session is closed. Preserves
 *        the `if (closed) return` guard in [add] and [replaceAll].
 * @param logger Shared logger instance.
 */
internal class MessageMapManager(
    private val toolCallState: ToolCallState,
    private val stateLock: ReentrantLock,
    private val messages: MutableStateFlow<LinkedHashMap<String, ChatMessage>>,
    private val isClosed: () -> Boolean,
    private val logger: KLogger,
) {
    /**
     * Add a message to the map with FIFO eviction and tool-index cleanup.
     * Skips if session is closed (preserves existing guard).
     */
    fun add(message: ChatMessage) = stateLock.withLock {
        if (isClosed()) {
            logger.debug { "[ACP] addMessage: session is closed, dropping message id=${message.id}" }
            return@withLock
        }
        val current = LinkedHashMap(messages.value)
        current[message.id] = message
        message.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
            toolCallState.toolCallIndex[part.pill.toolCallId] = message.id
        }
        // FIFO eviction
        if (current.size > ChatConstants.MAX_MESSAGE_HISTORY) {
            val excess = current.size - ChatConstants.MAX_MESSAGE_HISTORY
            val iter = current.entries.iterator()
            var toRemove = excess
            while (iter.hasNext() && toRemove > 0) {
                val entry = iter.next()
                entry.value.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                    toolCallState.toolCallIndex.remove(part.pill.toolCallId)
                    toolCallState.toolCallPills.remove(part.pill.toolCallId)
                    toolCallState.toolPartStates.remove(part.pill.toolCallId)
                }
                iter.remove()
                toRemove--
            }
        }
        messages.value = current
        logger.info { "[ACP] addMessage: id=${message.id}, role=${message.role}, mapSize=${current.size}" }
    }

    /**
     * Remove a message from the cache by its server message ID.
     * Used when the server sends message.removed (e.g., after compaction).
     * The message map is keyed by local ID, so we search by [ChatMessage.serverMessageId].
     */
    fun removeByServerId(serverMessageId: String) = stateLock.withLock {
        val current = LinkedHashMap(messages.value)
        val entry = current.entries.find { it.value.serverMessageId == serverMessageId }
        if (entry != null) {
            // Clean up tool call index for the removed message
            entry.value.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                toolCallState.toolCallIndex.remove(part.pill.toolCallId)
                toolCallState.toolCallPills.remove(part.pill.toolCallId)
                toolCallState.toolPartStates.remove(part.pill.toolCallId)
            }
            current.remove(entry.key)
            messages.value = current
            logger.info { "[ACP] removeMessageByServerId: serverId=$serverMessageId, localId=${entry.key}, mapSize=${current.size}" }
        } else {
            logger.debug { "[ACP] removeMessageByServerId: serverId=$serverMessageId not found in cache" }
        }
    }

    /**
     * Replace all messages in the cache with a fresh set from the server.
     * Used after auto-compaction (session.compacted) when the local cache is stale.
     * Rebuilds the tool call index from the new message set.
     * Skips if session is closed (preserves existing guard).
     */
    fun replaceAll(newMessages: List<ChatMessage>) = stateLock.withLock {
        if (isClosed()) return@withLock
        val current = LinkedHashMap<String, ChatMessage>()
        toolCallState.toolCallIndex.clear()
        toolCallState.toolCallPills.clear()
        toolCallState.toolPartStates.clear()
        newMessages.forEach { message ->
            current[message.id] = message
            message.parts.values.filterIsInstance<MessagePart.ToolCall>().forEach { part ->
                toolCallState.toolCallIndex[part.pill.toolCallId] = message.id
                toolCallState.toolCallPills[part.pill.toolCallId] = part.pill
            }
        }
        messages.value = current
        logger.info { "[ACP] replaceAllMessages: ${newMessages.size} messages, mapSize=${current.size}" }
    }

    /**
     * Apply a transform to a message in the map under stateLock.
     * Reentrant — safe to call from within stateLock.withLock blocks
     * (e.g., from TextStreamingManager.resegmentDirect which already holds the lock).
     */
    fun update(messageId: String, transform: (ChatMessage) -> ChatMessage) = stateLock.withLock {
        val map = messages.value
        val existing = map[messageId] ?: return@withLock
        val beforePartsCount = existing.parts.size
        val result = transform(existing)
        val afterPartsCount = result.parts.size
        if (afterPartsCount == 0 && beforePartsCount > 0) {
            logger.warn { "[ACP] updateMessage: EMPTY PARTS! msg=$messageId before=$beforePartsCount after=$afterPartsCount" }
        }
        val updated = LinkedHashMap(map)
        updated[messageId] = result
        messages.value = updated
    }
}