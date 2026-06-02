package com.opencode.acp.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PermissionBridgeTest {
    private val bridge = PermissionBridge()
    
    @Test
    fun `defaultOptions returns three options`() {
        val options = bridge.defaultOptions()
        assertEquals(3, options.size)
    }
    
    @Test
    fun `defaultOptions contains allow-once, reject-once, allow-always`() {
        val options = bridge.defaultOptions()
        val ids = options.map { it.optionId.value }
        assert(ids.contains("allow-once"))
        assert(ids.contains("reject-once"))
        assert(ids.contains("allow-always"))
    }
    
    @Test
    fun `findOption returns matching option`() {
        val option = bridge.findOption("allow-once")
        assertNotNull(option)
        assertEquals("allow-once", option!!.optionId.value)
    }
    
    @Test
    fun `findOption returns null for unknown option`() {
        val option = bridge.findOption("unknown")
        assert(option == null)
    }
}
