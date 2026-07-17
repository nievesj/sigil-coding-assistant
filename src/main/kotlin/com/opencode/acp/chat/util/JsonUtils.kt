package com.opencode.acp.chat.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON field extraction extensions. Consolidates duplicated `getString` helpers
 * and fallback chains across the codebase.
 */
object JsonUtils {
    /** Extract a string field, returning null if absent or not a primitive. */
    fun JsonObject.getString(key: String): String? =
        this[key]?.let { (it as? JsonPrimitive)?.contentOrNull }

    /** Extract an int field, returning null if absent or not parseable. */
    fun JsonObject.getInt(key: String): Int? =
        this[key]?.let { it.jsonPrimitive.intOrNull }

    /** Extract a file path from common field name variants. */
    fun JsonObject.getFilePath(): String? =
        getString("filePath") ?: getString("file_path") ?: getString("path")
}