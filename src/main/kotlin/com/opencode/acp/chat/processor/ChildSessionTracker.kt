package com.opencode.acp.chat.processor

import com.opencode.acp.chat.model.SessionItem
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Extracted from [SessionManager] (TDD §4.2.3). Owns child session ephemeral state:
 * the hidden/known/pending sets, the child→parent reverse index, and the child
 * agent label map.
 *
 * These sets are populated from multiple sources:
 *  - [loadSessions] (snapshot from the server) populates [childSessionMap], [knownChildSessionIds],
 *    [childToParent], and prunes [hiddenChildSessionIds].
 *  - [SessionManager.processEvent] (SSE Subtask events) populates [childAgentLabels] and
 *    [knownChildSessionIds] inline.
 *  - [markChildSessionComplete] (on StreamingCompleted/SessionIdle) populates [hiddenChildSessionIds].
 *
 * The active-session ID is read via the injected provider so [markChildSessionComplete]
 * can skip the active/selected session (it stays visible until the user switches away).
 */
class ChildSessionTracker(
    /** Provides the currently active session ID (null if none). */
    private val activeSessionIdProvider: () -> String?,
) {

    private val logger = KotlinLogging.logger {}

    /** Completed child session IDs to hide from the sidebar.
     *  Populated by [markChildSessionComplete] on StreamingCompleted/SessionIdle.
     *  Pruned by [SessionManager.loadSessions] to remove IDs for sessions the server has deleted. */
    private val _hiddenChildSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenChildSessionIds: StateFlow<Set<String>> = _hiddenChildSessionIds.asStateFlow()

    /** All known child session IDs, populated independently of [childSessionMap].
     *  Fixes the fast-completion race: a child created, streamed, and completed
     *  before loadSessions() refreshes childSessionMap is still recognized via
     *  this set (populated on session creation and in loadSessions()). */
    private val _knownChildSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val knownChildSessionIds: StateFlow<Set<String>> = _knownChildSessionIds.asStateFlow()

    /** Reverse index: child session ID → parent session ID. O(1) child→parent lookup.
     *  Populated from [SessionManager.loadSessions] (snapshot). Real-time population from Subtask
     *  SSE events is handled inline in [SessionManager.processEvent] (agent label only — parent
     *  is unknown from the Subtask event payload). */
    private val _childToParent = MutableStateFlow<Map<String, String>>(emptyMap())
    val childToParent: StateFlow<Map<String, String>> = _childToParent.asStateFlow()

    /** Child session agent labels: child session ID → agent name (e.g., "fixer", "explorer").
     *  Populated inline in [SessionManager.processEvent] from Subtask SSE events. Used by
     *  ChildPermissionRelay for sub-agent labels. */
    private val _childAgentLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val childAgentLabels: StateFlow<Map<String, String>> = _childAgentLabels.asStateFlow()

    /** Child sessions with pending permissions — prevents cache eviction. */
    private val _childPendingPermissions = MutableStateFlow<Set<String>>(emptySet())
    val childPendingPermissions: StateFlow<Set<String>> = _childPendingPermissions.asStateFlow()

    /** Mark a completed child session for hiding from the sidebar.
     *  Skips the active/selected session — it stays visible until the user switches away. */
    fun markChildSessionComplete(sessionId: String) {
        if (sessionId == activeSessionIdProvider()) return
        _hiddenChildSessionIds.update { it + sessionId }
        logger.debug { "[ACP] Child session $sessionId completed — marking for sidebar removal" }
    }

    /** Un-hide a child session (e.g., when switching to it via ToolPill "open child"). */
    fun unhideChildSession(sessionId: String) {
        _hiddenChildSessionIds.update { it - sessionId }
    }

    /** Find the parent session ID for a given child session ID.
     *  O(1) lookup via childToParent reverse index.
     *  Falls back to scanning childSessionMap if reverse index is stale. */
    fun getParentSession(childSessionId: String, childSessionMap: Map<String, List<SessionItem>>): String? {
        return _childToParent.value[childSessionId]
            ?: childSessionMap.entries.firstOrNull { (_, children) ->
                children.any { it.id == childSessionId }
            }?.key
    }

    /** Get the agent label for a child session (e.g., "fixer", "explorer"). */
    fun getChildAgentLabel(childSessionId: String): String? =
        _childAgentLabels.value[childSessionId]

    /** Mark a child session as having a pending permission. Prevents cache eviction. */
    fun markChildPendingPermission(childSessionId: String) {
        _childPendingPermissions.update { it + childSessionId }
    }

    /** Clear a child session's pending permission flag. */
    fun clearChildPendingPermission(childSessionId: String) {
        _childPendingPermissions.update { it - childSessionId }
    }

    // ── Mutators used by SessionManager (loadSessions / processEvent / ensureSessionCached) ──

    /** Add a child session ID to the known set (fixes fast-completion race). */
    fun addKnownChild(sessionId: String) {
        _knownChildSessionIds.update { it + sessionId }
    }

    /** Record a child agent label from a Subtask SSE event. */
    fun setChildAgentLabel(childSessionId: String, agent: String) {
        _childAgentLabels.update { it + (childSessionId to agent) }
    }

    /** Update the child→parent reverse index from session items (called by loadSessions). */
    fun updateChildToParent(items: List<SessionItem>) {
        _childToParent.update { existing ->
            val updated = existing.toMutableMap()
            for (item in items) {
                item.parentID?.let { parentId ->
                    updated[item.id] = parentId
                }
            }
            updated
        }
    }

    /** Populate knownChildSessionIds from session items (called by loadSessions). */
    fun addKnownChildrenFromItems(items: List<SessionItem>) {
        _knownChildSessionIds.update { known ->
            known + items.filter { it.parentID != null }.map { it.id }.toSet()
        }
    }

    /** Prune hiddenChildSessionIds and knownChildSessionIds to remove IDs for
     *  sessions the server has deleted (called by loadSessions). */
    fun pruneDeleted(currentSessionIds: Set<String>) {
        _hiddenChildSessionIds.update { ids -> ids.filter { it in currentSessionIds }.toSet() }
        _knownChildSessionIds.update { ids -> ids.filter { it in currentSessionIds }.toSet() }
    }

    /** Whether a session ID is in the pending-permissions set (used by cache eviction guards). */
    fun hasPendingPermission(sessionId: String): Boolean =
        sessionId in _childPendingPermissions.value

    /** Whether a session ID is a known child (used by SSE auto-cache and completion backstop). */
    fun isKnownChild(sessionId: String): Boolean =
        sessionId in _knownChildSessionIds.value
}