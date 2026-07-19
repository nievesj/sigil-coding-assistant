package com.opencode.acp.follow

import com.agentclientprotocol.model.ToolKind
import com.opencode.acp.config.settings.OpenCodeFollowSettingsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps ToolKind → highlight color and inlay label via a registry pattern.
 *
 * Colors are read from [OpenCodeSettingsState] hex strings and parsed to [java.awt.Color].
 * THINK and SWITCH_MODE have null entries (no file path → no highlight).
 *
 * Hex format is "#RRGGBBAA" — alpha is required so the highlight reads correctly
 * over the editor's background.
 */
object FollowColorProvider {

    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    /** Configuration for a single ToolKind's follow-agent behavior. */
    data class ColorConfig(
        val defaultColorHex: String,
        val inlayLabel: String?,
        val settingsReader: (OpenCodeFollowSettingsState) -> String,
    )

    private val colorConfigs: Map<ToolKind, ColorConfig?> = mapOf(
        ToolKind.READ to ColorConfig(
            defaultColorHex = "#5078C888",
            inlayLabel = "Agent is reading",
            settingsReader = { it.followReadColor },
        ),
        ToolKind.EDIT to ColorConfig(
            defaultColorHex = "#50A05088",
            inlayLabel = "Agent is editing",
            settingsReader = { it.followEditColor },
        ),
        ToolKind.SEARCH to ColorConfig(
            defaultColorHex = "#C8B43C88",
            inlayLabel = "Agent is searching",
            settingsReader = { it.followSearchColor },
        ),
        ToolKind.EXECUTE to ColorConfig(
            defaultColorHex = "#B4785088",
            inlayLabel = "Agent is running",
            settingsReader = { it.followExecuteColor },
        ),
        ToolKind.DELETE to ColorConfig(
            defaultColorHex = "#C8505088",
            inlayLabel = "Agent is deleting",
            settingsReader = { it.followDeleteColor },
        ),
        ToolKind.MOVE to ColorConfig(
            defaultColorHex = "#A050C888",
            inlayLabel = "Agent is moving",
            settingsReader = { it.followMoveColor },
        ),
        ToolKind.FETCH to ColorConfig(
            defaultColorHex = "#50A0C888",
            inlayLabel = "Agent is fetching",
            settingsReader = { it.followFetchColor },
        ),
        ToolKind.THINK to null,
        ToolKind.SWITCH_MODE to null,
        ToolKind.OTHER to ColorConfig(
            defaultColorHex = "#80808088",
            inlayLabel = "Agent is working",
            settingsReader = { it.followOtherColor },
        ),
    )

    /**
     * Cache of parsed color per ToolKind, keyed by the hex string last seen.
     * Avoids re-parsing the same hex on every call (hot path during streaming).
     * Invalidated automatically when the hex string changes in settings.
     */
    private val colorCache = java.util.concurrent.ConcurrentHashMap<ToolKind, Pair<String, java.awt.Color?>>()

    fun getColor(kind: ToolKind): java.awt.Color? {
        val config = colorConfigs[kind] ?: return null
        val hex = config.settingsReader(OpenCodeFollowSettingsState.getInstance())
        // Cache hit: same hex as last time → reuse parsed color.
        colorCache[kind]?.let { (cachedHex, cachedColor) ->
            if (cachedHex == hex) return cachedColor
        }
        // parseColorOrDefault returns null on bad input (which we treat as
        // "color disabled" — caller skips the highlight). The persisted
        // default hex is always valid; the only way to get here with bad
        // input is a user typo in the settings panel.
        val color = parseColorOrDefault(hex)
        colorCache[kind] = hex to color
        return color
    }

    fun getInlayLabel(kind: ToolKind): String? {
        return colorConfigs[kind]?.inlayLabel
    }

    /**
     * Compose a rich inlay label with agent name, model name, action context, and duration.
     *
     * Format: "Agent · Reading file.kt lines 42-58 (0.3s)"
     * Falls back to the static inlayLabel when no extra context is available.
     */
    fun composeInlayLabel(
        kind: ToolKind,
        agentName: String? = null,
        modelName: String? = null,
        input: JsonObject? = null,
        startTimeMs: Long? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): String {
        val action = when (kind) {
            ToolKind.READ -> "Reading"
            ToolKind.EDIT -> "Editing"
            ToolKind.SEARCH -> "Searching"
            ToolKind.EXECUTE -> "Running"
            ToolKind.DELETE -> "Deleting"
            ToolKind.MOVE -> "Moving"
            ToolKind.FETCH -> "Fetching"
            ToolKind.OTHER -> "Working"
            else -> return getInlayLabel(kind) ?: ""
        }

       // Build context suffix from input
       val context = when (kind) {
           ToolKind.READ, ToolKind.EDIT -> {
               val path = input?.getString("file_path")
                   ?: input?.getString("filePath")
                   ?: input?.getString("path")
               val fileName = path?.substringAfterLast('/')?.substringAfterLast('\\')
                // Read line range using the same keys as FollowAgentDispatcher.extractLineRange:
                // start_line/end_line (snake_case) or offset+limit (read tool).
                // Match FollowAgentDispatcher.extractLineRange key priority: offset first, then start_line
                val startLine = input?.get("offset")?.jsonPrimitive?.intOrNull?.let { it + 1 }
                    ?: input?.get("start_line")?.jsonPrimitive?.intOrNull?.let { if (it == 0) 1 else it }
                    ?: input?.get("startLine")?.jsonPrimitive?.intOrNull?.let { if (it == 0) 1 else it }
                val endLine = (input?.get("offset")?.jsonPrimitive?.intOrNull?.let { offset ->
                    val limit = input?.get("limit")?.jsonPrimitive?.intOrNull ?: 0
                    if (limit > 0) (offset + 1) + limit - 1 else null
                }) ?: input?.get("end_line")?.jsonPrimitive?.intOrNull?.let { if (it == 0) 1 else it }
                    ?: input?.get("endLine")?.jsonPrimitive?.intOrNull?.let { if (it == 0) 1 else it }
               // For edits, compute delta
               val delta = if (kind == ToolKind.EDIT) computeEditDelta(input) else null
               buildString {
                   if (fileName != null) append(fileName)
                   if (startLine != null && endLine != null && startLine > 0) {
                       append(" L$startLine-$endLine")
                   }
                   if (delta != null) append(" $delta")
               }.takeIf { it.isNotEmpty() }
           }
           ToolKind.SEARCH -> {
               val query = input?.getString("pattern") ?: input?.getString("query")
               query?.let { "\"${it.take(40)}\"" }
           }
           ToolKind.EXECUTE -> {
               val command = input?.getString("command")?.take(40)
               command?.let { "`$it`" }
           }
           else -> null
       }

       // Extract a reason/description from the tool input when the agent
       // provided one. The OpenCode protocol only includes `description` for
       // EXECUTE tools, but some agents/tools include it for READ/EDIT too.
       // When present, it is shown as a quoted suffix after the context.
       val reason = input?.getString("description")?.takeIf { it.isNotBlank() }

       // Build duration suffix
       // EXECUTE duration is omitted here because long-running commands are shown
       // in the Run console with their own completion footer (see CommandFollowManager).
       // The inlay label is transient (5s) and would show a misleading 0.0s for
       // commands that are still running.
       val duration = if (startTimeMs != null && kind != ToolKind.EXECUTE) {
           val elapsed = (currentTimeMs - startTimeMs) / 1000.0
           if (elapsed >= 0.1) "(%.1fs)".format(elapsed) else null
       } else null

       // Compose: "Agent · Action" or "Agent · Action context" or "Agent · Action (0.3s)"
       val who = agentName ?: "Agent"
       val model = modelName?.let { " · $it" } ?: ""
       return buildString {
           append(who)
           append(model)
           append(" · ")
           append(action)
           if (context != null) append(" $context")
           if (reason != null) append(" — \"${reason.take(80)}\"")
           if (duration != null) append(" $duration")
       }
    }

    /** Extract edit delta string like "(+12 -3 lines)" from tool input.
     *  Uses multiset counting to handle duplicate lines correctly: a set-difference
     *  would treat 3 identical lines as 1, miscounting additions/deletions when
     *  the same line appears multiple times in old or new content. */
    private fun computeEditDelta(input: JsonObject?): String? {
        if (input == null) return null
        val oldString = input.getString("oldString")
            ?: input.getString("old_string")
            ?: input.getString("old")
        val newString = input.getString("newString")
            ?: input.getString("new_string")
            ?: input.getString("new")
        return if (oldString != null || newString != null) {
            LineDeltaUtils.formatDelta(oldString, newString)
        } else {
            val content = input.getString("content")
            if (content != null) "(+${content.lines().size} lines)" else null
        }
    }

    private fun JsonObject.getString(key: String): String? {
        return try {
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug(e) { "[ACP] FollowColorProvider: failed to read string for key '$key'" }
            null
        }
    }

    /** Returns all highlightable ToolKind entries (those with non-null configs). */
    fun highlightableKinds(): List<ToolKind> {
        return colorConfigs.entries.filter { it.value != null }.map { it.key }
    }

    /** Returns the default hex color for a given ToolKind. */
    fun getDefaultHex(kind: ToolKind): String? {
        return colorConfigs[kind]?.defaultColorHex
    }
}

/**
 * Converts "#RRGGBB" or "#RRGGBBAA" hex to [java.awt.Color].
 *
 * Returns null on invalid input (length != 6 or != 8, non-hex characters,
 * empty string). Callers should treat null as "skip the highlight" — never
 * crash. This is the only failure mode the user can trigger by typing in the
 * settings panel.
 *
 * Alpha handling: 6-digit hex defaults to 0xFF (fully opaque). 8-digit hex uses
 * the explicit alpha from the string.
 */
internal fun parseColorOrDefault(hex: String?): java.awt.Color? {
    if (hex.isNullOrBlank()) return null
    val h = hex.removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    return try {
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        val a = if (h.length >= 8) h.substring(6, 8).toInt(16) else 0xFF
        java.awt.Color(r, g, b, a)
    } catch (_: NumberFormatException) {
        null
    }
}
