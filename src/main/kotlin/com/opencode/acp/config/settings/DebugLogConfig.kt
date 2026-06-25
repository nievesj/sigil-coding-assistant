package com.opencode.acp.config.settings

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory

/**
 * Applies the persisted [AcpLogLevel] to the Logback logger for the
 * `com.opencode.acp` package at runtime. All plugin loggers (declared via
 * `KotlinLogging.logger {}` across 33 files) share this package prefix, so a
 * single level change propagates to every call site via SLF4J's level gating.
 *
 * Call [apply] on startup (see [StartupLogConfigListener]) and on settings Apply
 * (see [OpenCodeSettingsConfigurable]).
 */
object DebugLogConfig {
    /** The Logback logger name covering all plugin code. */
    const val CATEGORY = "com.opencode.acp"

    private val logger = KotlinLogging.logger {}

    /**
     * Sets the Logback level for [CATEGORY]. Returns `true` on success,
     * `false` if the SLF4J backend is not Logback (silently no-ops with a warning).
     */
    fun apply(level: AcpLogLevel): Boolean {
        return try {
            val ctx = LoggerFactory.getILoggerFactory() as? LoggerContext ?: run {
                logger.warn { "[ACP] DebugLogConfig: SLF4J backend is not Logback — cannot set log level" }
                return false
            }
            ctx.getLogger(CATEGORY).level = level.toLogbackLevel()
            true
        } catch (e: Exception) {
            logger.warn { "[ACP] DebugLogConfig: failed to set log level: ${e.message}" }
            false
        }
    }

    /** Convenience overload accepting a persisted level name string. */
    fun apply(levelName: String?): Boolean = apply(AcpLogLevel.fromName(levelName))

    private fun AcpLogLevel.toLogbackLevel(): Level = when (this) {
        AcpLogLevel.OFF -> Level.OFF
        AcpLogLevel.ERROR -> Level.ERROR
        AcpLogLevel.WARN -> Level.WARN
        AcpLogLevel.INFO -> Level.INFO
        AcpLogLevel.DEBUG -> Level.DEBUG
        AcpLogLevel.TRACE -> Level.TRACE
        AcpLogLevel.ALL -> Level.ALL
    }
}
