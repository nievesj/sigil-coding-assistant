package com.opencode.acp.chat.util

/**
 * Truncation constants for log preview output. Consolidates 8 different
 * `take(N)` values scattered across 15+ files.
 */
object LogPreview {
    const val SHORT = 50
    const val MEDIUM = 200
    const val LONG = 500
    const val FULL = 1000
}