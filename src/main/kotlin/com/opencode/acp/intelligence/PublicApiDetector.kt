package com.opencode.acp.intelligence

/**
 * Pure-logic function: returns true if the modifier list indicates public API
 * (public or protected visibility). False for private or package-private.
 *
 * Kotlin `internal` is NOT considered public API (it's module-private).
 * Case-insensitive comparison handles Java/Kotlin modifier casing differences.
 */
fun isPublicApi(modifiers: List<String>): Boolean {
    val mods = modifiers.map { it.lowercase() }
    return "public" in mods || "protected" in mods
}