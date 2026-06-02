package com.opencode.acp.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandMapperTest {
    private val mapper = CommandMapper()
    
    @Test
    fun `maps CommandInfo to AvailableCommand`() {
        val commands = listOf(
            CommandInfo(id = "cmd1", name = "Command One", description = "Desc 1"),
            CommandInfo(id = "cmd2", name = "Command Two", description = null)
        )
        val result = mapper.toAvailableCommands(commands)
        assertEquals(2, result.size)
        assertEquals("Command One", result[0].name)
        assertEquals("Desc 1", result[0].description)
        assertEquals("", result[1].description) // null maps to empty string
    }
    
    @Test
    fun `maps single CommandInfo`() {
        val cmd = CommandInfo(id = "help", name = "Help", description = "Show help")
        val result = mapper.toAvailableCommand(cmd)
        assertEquals("Help", result.name)
        assertEquals("Show help", result.description)
    }
}
