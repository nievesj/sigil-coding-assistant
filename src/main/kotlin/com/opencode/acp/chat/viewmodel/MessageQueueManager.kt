package com.opencode.acp.chat.viewmodel

import com.opencode.acp.chat.model.AttachedFile
import com.opencode.acp.chat.model.QueuedMessage
import com.opencode.acp.chat.service.SendMessageResult
import com.opencode.acp.chat.util.generateId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the message queue (queue mode) — holding messages while streaming is
 * active and auto-sending them when the current response completes.
 *
 * Extracted from [ChatViewModel] per TDD §4.2.3. Owns the `_queuedMessages`
 * StateFlow, the drain mutex, the EDT-safe queue lock, retry counts, and the
 * next-allowed-retry timestamps. The actual send is delegated to the
 * [sendFunction] passed in at construction so the queue manager stays decoupled
 * from the ViewModel's streaming-phase bookkeeping.
 *
 * RETRY LIMIT: Failed messages are re-queued at most [MAX_QUEUE_RETRIES] times
 * before being dropped. This prevents infinite retry loops when the server is
 * unavailable. A [RETRY_DELAY_MS] delay is enforced BEFORE the next send attempt
 * (not after the re-queue), so consecutive failures cannot bypass the delay.
 *
 * LOCK ORDERING: Always acquire [queueLock] BEFORE [drainMutex] if both are
 * needed. [drainQueue] acquires `drainMutex` first, then `queueLock` inside —
 * this is safe because no EDT caller acquires `drainMutex`. DO NOT add
 * `drainMutex` acquisition inside a `queueLock`-held block without reversing
 * the order, or a deadlock will occur.
 *
 * @param sendFunction Suspends to send a queued message. Returns the
 *   [SendMessageResult]. Called inside [drainQueue] under `drainMutex`.
 */
class MessageQueueManager(
    private val sendFunction: suspend (QueuedMessage) -> SendMessageResult,
) {

    private val logger = KotlinLogging.logger {}

    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    /** Serializes drainQueue to prevent concurrent queue-drain races. */
    private val drainMutex = Mutex()

    /** Lock for non-suspend queue mutations (queueMessage/removeQueuedMessage/
     *  editQueuedMessage/clearQueue). These are called from EDT callbacks and cannot
     *  use the suspend `drainMutex.withLock`. A plain ReentrantLock avoids signature
     *  changes while preventing the read-modify-write race with drainQueue.
     *
     *  LOCK ORDERING CONSTRAINT: Always acquire `queueLock` BEFORE `drainMutex` if
     *  both are needed. Currently, `drainQueue` acquires `drainMutex` first, then
     *  `queueLock` inside — this is safe because no EDT caller acquires `drainMutex`.
     *  DO NOT add `drainMutex` acquisition inside a `queueLock`-held block without
     *  reversing the order, or a deadlock will occur. */
    private val queueLock = ReentrantLock()

    /** Retry counts for queued messages — prevents infinite retry loops.
     *  Uses ConcurrentHashMap for thread-safety: clearQueue() may be called from
     *  EDT coroutines (session switch, cancel) while drainQueue() runs on the
     *  ViewModel scope. Both are serialized by [drainMutex] for correctness, but
     *  the ConcurrentHashMap provides a safety net against data races. */
    private val queueRetryCounts = ConcurrentHashMap<String, Int>()

    /** Next-allowed-retry timestamp (ms) per queued message id. Enforces RETRY_DELAY_MS
     *  before the next send attempt, not after the re-queue, so consecutive failures
     *  cannot bypass the retry delay. */
    private val nextRetryTime = ConcurrentHashMap<String, Long>()

    /**
     * Add a message to the queue instead of sending it immediately.
     * Used when streaming is active (SENDING or STREAMING phase) and queue mode is enabled.
     * The message will be auto-sent when the current response completes.
     */
    fun queueMessage(text: String, files: List<AttachedFile> = emptyList()) {
        queueLock.withLock {
            val msg = QueuedMessage(
                id = generateId(),
                text = text,
                files = files
            )
            _queuedMessages.value = _queuedMessages.value + msg
            logger.info { "[ACP] queueMessage: queued '${text.take(50)}' (${_queuedMessages.value.size} in queue)" }
        }
    }

    /**
     * Remove a queued message by ID (user cancelled it from the queue bar).
     */
    fun removeQueuedMessage(messageId: String) {
        queueLock.withLock {
            _queuedMessages.value = _queuedMessages.value.filter { it.id != messageId }
            logger.info { "[ACP] removeQueuedMessage: $messageId (${_queuedMessages.value.size} remaining)" }
        }
    }

    /**
     * Edit a queued message's text (user clicked edit in the queue bar).
     */
    fun editQueuedMessage(messageId: String, newText: String) {
        queueLock.withLock {
            _queuedMessages.value = _queuedMessages.value.map {
                if (it.id == messageId) it.copy(text = newText) else it
            }
        }
    }

    /**
     * Clear all queued messages. Called on session switch, cancel, etc.
     */
    fun clearQueue() {
        queueLock.withLock {
            val count = _queuedMessages.value.size
            if (count > 0) {
                _queuedMessages.value = emptyList()
                queueRetryCounts.clear()
                nextRetryTime.clear()
                logger.info { "[ACP] clearQueue: cleared $count queued messages" }
            }
        }
    }

    /**
     * Drain the queue — send the next queued message if any.
     * Called automatically when StreamingCompleted fires and queue is non-empty.
     * Serialized by [drainMutex] to prevent concurrent queue-drain races.
     *
     * RETRY LIMIT: Failed messages are re-queued at most [MAX_QUEUE_RETRIES]
     * times before being dropped. This prevents infinite retry loops when the
     * server is unavailable.
     */
    suspend fun drainQueue() = drainMutex.withLock {
        val next: QueuedMessage?
        queueLock.withLock {
            val queue = _queuedMessages.value
            if (queue.isEmpty()) {
                next = null
                return@withLock
            }
            next = queue.first()
            _queuedMessages.value = queue.drop(1)
        }
        if (next == null) return@withLock
        // Enforce retry delay BEFORE sending, not after re-queue. Without this,
        // a re-queued message would be re-sent immediately on the next drainQueue
        // call (triggered by the next StreamingCompleted), bypassing RETRY_DELAY_MS.
        val retryAt = nextRetryTime[next.id]
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            // Not yet time to retry — re-queue at end and return without sending.
            queueLock.withLock {
                _queuedMessages.value = _queuedMessages.value + next
            }
            return@withLock
        }
        logger.info { "[ACP] drainQueue: sending '${next.text.take(50)}' (${_queuedMessages.value.size} remaining)" }

        val result = try {
            sendFunction(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[ACP] drainQueue: sendMessage threw exception" }
            SendMessageResult.Error(e.message ?: "Send failed")
        }
        if (result is SendMessageResult.Error) {
            queueLock.withLock {
                // Check retry count inside queueLock to prevent race with clearQueue
                // (which clears queueRetryCounts under queueLock as well)
                val retryCount = queueRetryCounts.getOrDefault(next.id, 0) + 1
                if (retryCount <= MAX_QUEUE_RETRIES) {
                    queueRetryCounts[next.id] = retryCount
                    // Record next-allowed-retry timestamp so the delay is enforced
                    // before the next send attempt (checked at the top of drainQueue).
                    nextRetryTime[next.id] = System.currentTimeMillis() + RETRY_DELAY_MS
                    val alreadyRequeued = _queuedMessages.value.any { it.id == next.id }
                    if (!alreadyRequeued) {
                        // Re-queue at the END of the queue (not the front) to avoid
                        // starving later messages. The retry delay already gives
                        // the server time to recover, so the message doesn't need
                        // priority over others.
                        _queuedMessages.value = _queuedMessages.value + next
                        logger.warn { "[ACP] drainQueue: re-queued failed message at end of queue (attempt $retryCount/$MAX_QUEUE_RETRIES) — ${result.message}" }
                    } else {
                        logger.debug { "[ACP] drainQueue: message ${next.id} already re-queued, skipping duplicate add (attempt $retryCount/$MAX_QUEUE_RETRIES)" }
                    }
                } else {
                    queueRetryCounts.remove(next.id)
                    nextRetryTime.remove(next.id)
                    logger.error { "[ACP] drainQueue: dropping message after $MAX_QUEUE_RETRIES failed attempts — ${result.message}" }
                }
            }
        } else {
            queueRetryCounts.remove(next.id)
            nextRetryTime.remove(next.id)
        }
    }

    companion object {
        private const val MAX_QUEUE_RETRIES = 3
        /** Delay before re-queuing a failed message to prevent rapid retry loops. */
        private const val RETRY_DELAY_MS = 2_000L
    }
}