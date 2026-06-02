package com.opencode.acp

import com.agentclientprotocol.model.ContentBlock
import kotlinx.serialization.Serializable

/**
 * Persisted session message. Used for session load/replay.
 */
@Serializable
data class StoredMessage(
    val role: String, // "user" | "assistant" | "system"
    val contentJson: String, // JSON-serialized ContentBlock (polymorphic)
    val timestamp: Long = System.currentTimeMillis()
)
