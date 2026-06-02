package com.opencode.acp.adapter

import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.opencode.acp.PlanEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlanAdapterTest {
    @Test
    fun `converts plan entries with correct priority and status`() {
        val entries = listOf(
            PlanEntry(description = "Task 1", priority = "high", status = "pending"),
            PlanEntry(description = "Task 2", priority = "medium", status = "in_progress"),
            PlanEntry(description = "Task 3", priority = "low", status = "completed")
        )
        val result = PlanAdapter.toPlanEntries(entries)
        assertEquals(3, result.size)
        assertEquals(PlanEntryPriority.HIGH, result[0].priority)
        assertEquals(PlanEntryStatus.PENDING, result[0].status)
        assertEquals(PlanEntryPriority.MEDIUM, result[1].priority)
        assertEquals(PlanEntryStatus.IN_PROGRESS, result[1].status)
        assertEquals(PlanEntryPriority.LOW, result[2].priority)
        assertEquals(PlanEntryStatus.COMPLETED, result[2].status)
    }
    
    @Test
    fun `handles case-insensitive priority`() {
        val entry = PlanEntry(description = "Task", priority = "HIGH", status = "COMPLETED")
        val result = PlanAdapter.toPlanEntry(entry)
        assertEquals(PlanEntryPriority.HIGH, result.priority)
    }
    
    @Test
    fun `defaults unknown priority to MEDIUM`() {
        val entry = PlanEntry(description = "Task", priority = "unknown", status = "pending")
        val result = PlanAdapter.toPlanEntry(entry)
        assertEquals(PlanEntryPriority.MEDIUM, result.priority)
    }
    
    @Test
    fun `defaults unknown status to PENDING`() {
        val entry = PlanEntry(description = "Task", priority = "medium", status = "unknown")
        val result = PlanAdapter.toPlanEntry(entry)
        assertEquals(PlanEntryStatus.PENDING, result.status)
    }
}
