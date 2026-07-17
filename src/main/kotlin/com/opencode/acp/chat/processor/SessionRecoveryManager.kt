package com.opencode.acp.chat.processor

import com.opencode.acp.adapter.OpenCodeClient
import com.opencode.acp.adapter.OpenCodePart
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Extracted from [SessionManager] (TDD §4.2.3). Owns background session recovery
 * after SSE reconnection.
 *
 * When the SSE stream drops unexpectedly, in-flight streaming responses may be left
 * in a "streaming" state with a pending [SessionState.responseDeferred]. After the
 * SSE connection is re-established, [recoverBackgroundSessions] re-fetches recent
 * messages for each streaming session and finalizes any whose generation completed
 * on the server while the client was disconnected.
 *
 * SAFETY: Before finalizing, checks for in-progress tool calls. If the last assistant
 * message has ToolUse parts without matching ToolResult parts, the session is likely
 * still generating — we skip finalization and let the SSE reconnection deliver the
 * remaining events. This prevents incorrectly finalizing a session that's
 * mid-tool-execution (TDD §11 Risk 3).
 */
class SessionRecoveryManager(
    /** Provides the currently streaming cached sessions (empty if none). */
    private val streamingSessionsProvider: () -> List<SessionState>,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Check all cached sessions that were streaming when SSE dropped.
     * Re-fetches recent messages for each streaming session. If the server's
     * last message is an assistant message (indicating generation completed),
     * finalize it locally. Prevents responseDeferred from hanging until the
     * configurable response timeout.
     *
     * ASSUMPTION: The server's REST API (listMessages) returns the current
     * state of messages, including in-progress tool parts. If this assumption
     * is wrong, the safety check may not catch all cases.
     *
     * @param client The OpenCodeClient to use for REST calls (passed by caller
     *   from ProcessManager.client — SessionRecoveryManager does NOT own a client reference).
     */
    suspend fun recoverBackgroundSessions(client: OpenCodeClient?) {
        if (client == null) return
        val streamingSessions = streamingSessionsProvider()
        if (streamingSessions.isEmpty()) return

        logger.info { "[ACP] recoverBackgroundSessions: checking ${streamingSessions.size} streaming sessions" }

        // Use supervisorScope instead of coroutineScope so that one failed
        // async block (e.g., CancellationException from session eviction)
        // does NOT cancel the other recovery attempts. Each async block
        // catches its own exceptions internally, but supervisorScope ensures
        // structured concurrency without cross-cancellation.
        supervisorScope {
            val deferreds = streamingSessions.map { session ->
                async {
                    val sessionId = session.sessionId
                    try {
                        // Fetch 20 messages (not 5) for the in-progress tool check.
                        // A ToolUse beyond a 5-message window (e.g., a long-running subtask
                        // that spawned its own messages) would be missed, causing premature
                        // finalization. 20 is a balance between safety and REST payload size.
                        val messages = client.listMessages(sessionId, limit = 20)
                        val lastMessage = messages.lastOrNull()
                        // RESOLVED (false positive): Current logic is correct — only
                        // finalize if the last message is assistant (generation completed).
                        // If the last is user, the assistant hasn't started responding yet,
                        // so skipping finalization is the correct behavior (the SSE
                        // reconnection will deliver the assistant's response normally).
                        // Using lastOrNull (not lastOrNull { role == "assistant" }) is
                        // intentional: we want to know the server's CURRENT last message,
                        // not the most recent assistant message.
                        val lastIsAssistant = lastMessage?.info?.role == "assistant"

                        if (lastIsAssistant) {
                            // Safety check: detect in-progress tool calls across ALL fetched messages,
                            // not just the last one. The server returns messages in chronological order
                            // in practice, but scanning all fetched messages is safer and bounded (limit=20).
                            // O(n) instead of O(n²): build a Set of completed tool IDs first,
                            // then check ToolUse membership. Single flatMap reused for both checks.
                            val allParts = messages.flatMap { it.parts }
                            val completedToolIds = allParts.filterIsInstance<OpenCodePart.ToolResult>()
                                .map { it.toolUseId }
                                .toSet()
                            val hasInProgressTools = allParts.filterIsInstance<OpenCodePart.ToolUse>()
                                .any { it.id !in completedToolIds }

                            if (hasInProgressTools) {
                                logger.info { "[ACP] recoverBackgroundSessions: session $sessionId has in-progress tools — skipping finalization (will recover via SSE)" }
                                return@async
                            }

                            val activeMsgId = session.activeMessageId
                            if (activeMsgId != null) {
                                session.completeStreaming(activeMsgId)
                            } else {
                                logger.warn { "[ACP] recoverBackgroundSessions: session $sessionId has no activeMessageId — cannot finalize, session may remain in streaming state" }
                            }
                            session.responseDeferred?.complete(Unit)
                            session.responseDeferred = null
                            logger.info { "[ACP] Recovered background session $sessionId after SSE reconnection" }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "[ACP] Failed to recover session $sessionId" }
                    }
                }
            }
            deferreds.awaitAll()
        }
    }
}