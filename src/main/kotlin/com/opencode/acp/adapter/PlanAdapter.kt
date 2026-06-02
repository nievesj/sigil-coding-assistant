package com.opencode.acp.adapter

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus

/**
 * Adapts OpenCode plan entries (with string-based priority and status)
 * to ACP SDK [PlanEntry] instances with typed enums.
 */
object PlanAdapter {

    /**
     * Converts a list of OpenCode [com.opencode.acp.PlanEntry] (with String priority/status)
     * to ACP SDK [PlanEntry] instances with [PlanEntryPriority] and [PlanEntryStatus] enums.
     */
    fun toPlanEntries(entries: List<com.opencode.acp.PlanEntry>): List<PlanEntry> =
        entries.map { entry ->
            PlanEntry(
                content = entry.description,
                priority = parsePriority(entry.priority),
                status = parseStatus(entry.status)
            )
        }

    /**
     * Converts a single OpenCode [com.opencode.acp.PlanEntry] to SDK [PlanEntry].
     */
    fun toPlanEntry(entry: com.opencode.acp.PlanEntry): PlanEntry = PlanEntry(
        content = entry.description,
        priority = parsePriority(entry.priority),
        status = parseStatus(entry.status)
    )

    private fun parsePriority(priority: String): PlanEntryPriority = when (priority.uppercase()) {
        "HIGH" -> PlanEntryPriority.HIGH
        "MEDIUM", "MED" -> PlanEntryPriority.MEDIUM
        "LOW" -> PlanEntryPriority.LOW
        else -> {
            // Default to MEDIUM for unrecognized values
            PlanEntryPriority.MEDIUM
        }
    }

    private fun parseStatus(status: String): PlanEntryStatus = when (status.uppercase()) {
        "PENDING" -> PlanEntryStatus.PENDING
        "IN_PROGRESS", "INPROGRESS", "RUNNING" -> PlanEntryStatus.IN_PROGRESS
        "COMPLETED", "DONE", "SUCCESS" -> PlanEntryStatus.COMPLETED
        "FAILED", "FAILURE", "ERROR" -> PlanEntryStatus.COMPLETED
        "CANCELLED", "CANCELED" -> PlanEntryStatus.COMPLETED
        else -> {
            // Default to PENDING for unrecognized values
            PlanEntryStatus.PENDING
        }
    }
}
