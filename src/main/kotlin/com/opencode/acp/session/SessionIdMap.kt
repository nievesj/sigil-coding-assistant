package com.opencode.acp.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe bidirectional mapping between ACP session IDs and OpenCode session IDs.
 * ACP session IDs are generated (prefixed "acp_"), OpenCode IDs come from HTTP API.
 */
class SessionIdMap {
    private val acpToOc = ConcurrentHashMap<String, String>()
    private val ocToAcp = ConcurrentHashMap<String, String>()

    fun put(acpId: String, ocId: String) {
        acpToOc[acpId] = ocId
        ocToAcp[ocId] = acpId
    }

    fun getOpenCodeId(acpId: String): String? = acpToOc[acpId]
    fun getAcpId(ocId: String): String? = ocToAcp[ocId]

    fun remove(acpId: String) {
        val ocId = acpToOc.remove(acpId)
        if (ocId != null) ocToAcp.remove(ocId)
    }

    fun list(): List<String> = acpToOc.keys.toList()
    fun size(): Int = acpToOc.size
}
