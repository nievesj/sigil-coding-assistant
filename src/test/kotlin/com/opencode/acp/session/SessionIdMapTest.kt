package com.opencode.acp.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionIdMapTest {
    private val map = SessionIdMap()
    
    @Test
    fun `put and get openCodeId`() {
        map.put("acp_1", "oc_1")
        assertEquals("oc_1", map.getOpenCodeId("acp_1"))
    }
    
    @Test
    fun `put and get acpId`() {
        map.put("acp_1", "oc_1")
        assertEquals("acp_1", map.getAcpId("oc_1"))
    }
    
    @Test
    fun `get returns null for unknown id`() {
        assertNull(map.getOpenCodeId("unknown"))
        assertNull(map.getAcpId("unknown"))
    }
    
    @Test
    fun `remove deletes both mappings`() {
        map.put("acp_1", "oc_1")
        map.remove("acp_1")
        assertNull(map.getOpenCodeId("acp_1"))
        assertNull(map.getAcpId("oc_1"))
    }
    
    @Test
    fun `list returns all acp ids`() {
        map.put("acp_1", "oc_1")
        map.put("acp_2", "oc_2")
        assertEquals(2, map.list().size)
    }
    
    @Test
    fun `size increases with put`() {
        map.put("acp_1", "oc_1")
        assertEquals(1, map.size())
    }
}
