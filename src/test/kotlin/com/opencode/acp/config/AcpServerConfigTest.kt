package com.opencode.acp.config

import com.opencode.acp.TransportMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AcpServerConfigTest {
    @Test
    fun `default config has expected defaults`() {
        val config = AcpServerConfig()
        assertEquals("127.0.0.1", config.openCodeHost)
        assertEquals(4096, config.openCodePort)
        assertNull(config.openCodePassword)
        assertEquals(TransportMode.STDIO, config.transport)
    }
    
    @Test
    fun `parse with CLI args`() {
        val config = AcpServerConfig.parse(arrayOf("--opencode-host", "localhost", "--opencode-port", "5000"))
        assertEquals("localhost", config.openCodeHost)
        assertEquals(5000, config.openCodePort)
    }
    
    @Test
    fun `invalid port throws`() {
        assertThrows<IllegalArgumentException> {
            AcpServerConfig(openCodePort = 99999)
        }
    }
    
    @Test
    fun `zero concurrent sessions throws`() {
        assertThrows<IllegalArgumentException> {
            AcpServerConfig(maxConcurrentSessions = 0)
        }
    }
}
