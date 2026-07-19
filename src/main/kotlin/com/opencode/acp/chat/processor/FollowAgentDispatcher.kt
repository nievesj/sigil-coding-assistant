package com.opencode.acp.chat.processor

import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.SseEvent
import com.opencode.acp.adapter.ToolMapper
import com.opencode.acp.chat.model.ChatFileChange
import com.opencode.acp.chat.model.ToolCallPill
import com.opencode.acp.follow.CommandFollowManager
import com.opencode.acp.follow.EditorFollowManager
import com.opencode.acp.follow.SearchFollowManager
import com.intellij.openapi.project.Project
import com.opencode.acp.chat.util.FollowAgentDispatcherInterface
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Follow Agent integration: editor navigation, command console, Find in Files �
 * for both ToolUse and ToolResult events.
 *
 * NOTE: This dispatcher is NOT stateless � it mutates [ToolCallState.pendingFileChanges]
 * for EDIT tools and reads [ToolCallState.activeAgentName] / [TurnLifecycleState.modelID]
 * for Follow Agent calls. This is safe because all calls happen on the event processing
 * coroutine (single-writer invariant).
 *
 * @param project The IntelliJ project.
 * @param ctx Shared processor context � read for activeAgentName/modelID, written for
 *        pendingFileChanges (EDIT tools).
 * @param scope Coroutine scope � used for launching child session caching coroutines.
 * @param sessionManager Session manager � used for [SessionManager.ensureSessionCached]
 *        (task tool child session caching).
 * @param logger Shared logger instance.
 */
internal class FollowAgentDispatcher(
    private val project: Project,
    private val toolCallState: ToolCallState,
    private val turnLifecycleState: TurnLifecycleState,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager,
    private val logger: KLogger,
) : FollowAgentDispatcherInterface {
    /**
     * Dispatch Follow Agent actions for a ToolUse event.
     * Handles: editor navigation, command console (EXECUTE), Find in Files (SEARCH),
     * file change extraction (EDIT ? toolCallState.pendingFileChanges), task session caching.
     * Called from both primary and duplicate ToolUse paths � eliminates duplication.
     *
     * @param toolCallId The tool call ID
     * @param toolName The tool name
     * @param toolKind The resolved tool kind
     * @param input The tool input JSON (may be null)
     * @param metadata The tool metadata (may be null)
     * @param startTimeMs The pill start time (for editor follow timing)
     * @param isDuplicate Whether this is a duplicate ToolUse (metadata-merge path).
     *        When true, the duplicate path does NOT re-extract file changes (they were
     *        tracked on the primary path). It only re-fires Follow Agent navigation
     *        if the duplicate carries real input that the primary didn't have.
     * @param existingPill The existing pill (for duplicate path kind re-detection),
     *        null for primary path.
     */
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
        // Resolve the effective tool kind once — used by editor follow, EXECUTE, and SEARCH.
        // Re-detect from input if the existing pill's kind was OTHER (duplicate path),
        // otherwise use the passed kind (primary) or the existing pill's kind (duplicate).
        val effectiveKind = if (isDuplicate && existingPill?.kind == ToolKind.OTHER && input != null) {
            ToolMapper.detectKindFromInput(input)
        } else if (isDuplicate) {
            existingPill?.kind ?: toolKind
        } else toolKind

        // -- Editor navigation (READ/EDIT/etc. with file path) --
        // For the duplicate path: only re-fire if the duplicate carries real input
        // that the primary didn't have.
        val shouldFireFollow = if (isDuplicate) {
            input != null && input.isNotEmpty() &&
                (existingPill?.input == null || existingPill.input.isEmpty())
        } else {
            input != null
        }

        if (shouldFireFollow) {
            val followFilePath = input?.let { extractFilePath(it) }
            if (followFilePath != null) {
                val (startLine, endLine) = extractLineRange(input)
                // Non-essential: if the project service isn't available (very rare —
                // dispose race) we skip silently. NEVER `?: return` here — that would
                // drop the rest of the ToolUse event processing.
                runCatching {
                    EditorFollowManager.getInstance(project).followToolCall(
                        project, followFilePath, startLine, endLine, effectiveKind,
                        agentName = toolCallState.activeAgentName,
                        modelName = turnLifecycleState.modelID,
                        input = input,
                        startTimeMs = startTimeMs,
                    )
                }.onFailure { logger.debug(it) { "[ACP] Follow Agent: skipped (${if (isDuplicate) "duplicate-ToolUse" else "ToolUse"} path)" } }
            }
        }

        // -- EXECUTE tools — open a read-only Run console showing the command --
        if (effectiveKind == ToolKind.EXECUTE && input != null) {
            val command = try {
                input["command"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            val workdir = try {
                input["workdir"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            val description = try {
                input["description"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            if (command != null) {
                runCatching {
                    CommandFollowManager.getInstance(project).followCommand(
                        project = project,
                        toolCallId = toolCallId,
                        command = command,
                        workdir = workdir,
                        description = description,
                        agentName = toolCallState.activeAgentName,
                        modelName = turnLifecycleState.modelID,
                    )
                }.onFailure { logger.debug(it) { "[ACP] Follow Agent: command console skipped (${if (isDuplicate) "duplicate path" else "primary"})" } }
            }
        }

        // -- SEARCH tools — open IntelliJ's native Find in Files --
        if (effectiveKind == ToolKind.SEARCH && input != null) {
            val pattern = try {
                input["pattern"]?.jsonPrimitive?.contentOrNull
                    ?: input["query"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            val searchPath = try {
                input["path"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            val includeGlob = try {
                input["include"]?.jsonPrimitive?.contentOrNull
                    ?: input["glob"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            if (pattern != null) {
                runCatching {
                    SearchFollowManager.getInstance(project).followSearch(
                        project = project,
                        pattern = pattern,
                        searchPath = searchPath,
                        includeGlob = includeGlob,
                        isRegex = false,
                        agentName = toolCallState.activeAgentName,
                        modelName = turnLifecycleState.modelID,
                    )
                }.onFailure { logger.debug(it) { "[ACP] Follow Agent: Find in Files skipped (${if (isDuplicate) "duplicate path" else "primary"})" } }
            }
        }

        // -- EDIT tools � extract ChatFileChange (primary path only) --
        // The duplicate path does NOT re-extract file changes (they were tracked on
        // the primary path).
        if (!isDuplicate && toolKind == ToolKind.EDIT && input != null) {
            val filePath = extractFilePath(input)
            if (filePath != null) {
                val fileName = java.io.File(filePath).name
                val oldString = input["oldString"]?.jsonPrimitive?.contentOrNull
                    ?: input["old_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["old"]?.jsonPrimitive?.contentOrNull
                val newString = input["newString"]?.jsonPrimitive?.contentOrNull
                    ?: input["new_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["new"]?.jsonPrimitive?.contentOrNull
                val content = input["content"]?.jsonPrimitive?.contentOrNull
                val additions = when {
                    oldString != null && newString != null -> newString.lines().size
                    content != null -> content.lines().size
                    else -> 0
                }
                val deletions = when {
                    oldString != null && newString != null -> oldString.lines().size
                    else -> 0
                }
                val change = ChatFileChange(
                    filePath = filePath,
                    fileName = fileName,
                    additions = additions,
                    deletions = deletions
                )
                toolCallState.pendingFileChanges.add(change)
            }
        }

        // -- Task tools � proactively cache the child session --
        // For the duplicate path: only cache if the primary didn't already cache it
        // (existingPill.metadata?.get("sessionId") == null but event.metadata has one).
        if (isDuplicate) {
            if (existingPill?.toolName == "task" && existingPill.metadata?.get("sessionId") == null && metadata?.get("sessionId") != null) {
                val childId = try { metadata["sessionId"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                if (childId != null) {
                    logger.info { "[ACP] Task tool (metadata update): proactively caching child session $childId" }
                    scope.launch { sessionManager.ensureSessionCached(childId) }
                }
            }
        } else {
            if (toolName == "task") {
                logger.info { "[ACP] Task tool ToolUse: callID=$toolCallId, metadata=$metadata" }
                val childId = try { metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                if (childId != null) {
                    logger.info { "[ACP] Task tool: proactively caching child session $childId" }
                    scope.launch { sessionManager.ensureSessionCached(childId) }
                }
            }
        }
    }

    /**
     * Dispatch Follow Agent actions for a ToolResult event.
     * Handles: editor navigation to first search match, command result output,
     * FileChanged signal emission for orphan EDIT results, task session caching.
     *
     * @param toolCallId The tool call ID
     * @param resolvedKind The resolved tool kind
     * @param content The tool result content (may be null)
     * @param isError Whether the tool call failed
     * @param isOrphan Whether this ToolResult has no prior ToolUse (fast sub-agent path).
     *                 When true, the caller has already created the pill from scratch;
     *                 this method handles FileChanged signal emission and task caching.
     * @param input The tool input (for orphan path kind detection), may be null
     * @param metadata The tool metadata (for orphan path task caching), may be null
     * @param signals The signal flow � used for FileChanged emission on orphan EDIT results
     */
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
        if (!isOrphan) {
            // -- SEARCH tools � navigate to the first match result --
            if (resolvedKind == ToolKind.SEARCH) {
                runCatching {
                    EditorFollowManager.getInstance(project).followToolResult(
                        project, toolCallId, content, ToolKind.SEARCH
                    )
                }.onFailure { logger.debug(it) { "[ACP] Follow Agent: skipped (ToolResult path)" } }
            }
            // -- EXECUTE tools � print output into the Run console --
            if (resolvedKind == ToolKind.EXECUTE) {
                runCatching {
                    CommandFollowManager.getInstance(project).followCommandResult(
                        project = project,
                        toolCallId = toolCallId,
                        output = content,
                        isError = isError,
                    )
                    CommandFollowManager.getInstance(project).finishCommand(
                        project = project,
                        toolCallId = toolCallId,
                        isError = isError,
                    )
                }.onFailure { logger.debug(it) { "[ACP] Follow Agent: command result skipped" } }
            }
        } else {
            // Orphan path � no prior ToolUse. Emit FileChanged for EDIT tools and
            // cache task child sessions.
            if (resolvedKind == ToolKind.EDIT) {
                signals.tryEmit(UiSignal.FileChanged(Unit))
            }
            // For task tools: proactively cache the child session
            val childId = try { metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
            if (childId != null) {
                logger.info { "[ACP] Task tool (from ToolResult): proactively caching child session $childId" }
                scope.launch { sessionManager.ensureSessionCached(childId) }
            }
        }
    }

    /** Extract and canonicalize file path from tool input JSON. */
    fun extractFilePath(input: JsonObject): String? {
        for (key in listOf("file_path", "filePath", "path")) {
            val element = input[key] ?: continue
            val rawPath = try {
                (element as? JsonPrimitive)?.content
            } catch (_: Exception) { null }
            if (!rawPath.isNullOrEmpty()) {
                // Canonicalize to resolve ../ sequences and symlinks.
                // Callers (sendMessageInternal, EditorFollowManager) rely on this
                // for path traversal validation � returning raw paths bypasses
                // their boundary checks.
                // Return null on failure: a path that can't be canonicalized is
                // untrusted (permissions, broken symlink, non-existent path).
                // Callers already handle null gracefully (they skip the operation).
                return try {
                    java.io.File(rawPath).canonicalPath
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Extract startLine/endLine from tool-specific input fields.
     * Returns Pair(0, 0) when no line info is available (e.g. edit/write).
     *
     * Known field names:
     * - `offset` (1-indexed start) + `limit` (max lines) ? read tool
     * - `start_line` / `end_line` ? generic (not used by OpenCode's built-in tools)
     */
    fun extractLineRange(input: JsonObject?): Pair<Int, Int> {
        if (input == null) return Pair(0, 0)
        val startLine = when {
            input.containsKey("offset") ->
                input["offset"]?.jsonPrimitive?.intOrNull ?: 0
            input.containsKey("start_line") ->
                input["start_line"]?.jsonPrimitive?.intOrNull ?: 0
            else -> 0
        }
        val endLine = when {
            startLine > 0 && input.containsKey("limit") -> {
                val limit = input["limit"]?.jsonPrimitive?.intOrNull ?: 0
                if (limit > 0) startLine + limit - 1 else 0
            }
            input.containsKey("end_line") ->
                input["end_line"]?.jsonPrimitive?.intOrNull ?: 0
            else -> 0
        }
        return Pair(startLine, endLine)
    }
}