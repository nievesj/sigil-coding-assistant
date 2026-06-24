package com.opencode.acp.config.settings

/**
 * Log levels for the OpenCode plugin, mapped to Logback [ch.qos.logback.classic.Level].
 * Persisted as a String in [OpenCodeSettingsState] for XStream compatibility.
 *
 * Default is [INFO] — end users get diagnostic logs (startup, connection, errors)
 * without the verbose SSE wire-format debug output. Set to [DEBUG] or [ALL] for
 * troubleshooting; [OFF] for a completely silent idea.log.
 */
enum class AcpLogLevel {
    OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL;

    companion object {
        /** Returns the level matching [name], or [INFO] if unknown/blank. Never throws. */
        fun fromName(name: String?): AcpLogLevel =
            entries.find { it.name == name } ?: INFO
    }
}
