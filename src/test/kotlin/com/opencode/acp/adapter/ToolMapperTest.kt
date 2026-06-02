package com.opencode.acp.adapter

import com.agentclientprotocol.model.ToolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolMapperTest {
    @Test
    fun `bash maps to EXECUTE`() = assertEquals(ToolKind.EXECUTE, ToolMapper.toAcpKind("bash"))
    
    @Test
    fun `shell maps to EXECUTE`() = assertEquals(ToolKind.EXECUTE, ToolMapper.toAcpKind("shell"))
    
    @Test
    fun `edit maps to EDIT`() = assertEquals(ToolKind.EDIT, ToolMapper.toAcpKind("edit"))
    
    @Test
    fun `apply_patch maps to EDIT`() = assertEquals(ToolKind.EDIT, ToolMapper.toAcpKind("apply_patch"))
    
    @Test
    fun `write maps to EDIT`() = assertEquals(ToolKind.EDIT, ToolMapper.toAcpKind("write"))
    
    @Test
    fun `read maps to READ`() = assertEquals(ToolKind.READ, ToolMapper.toAcpKind("read"))
    
    @Test
    fun `list maps to READ`() = assertEquals(ToolKind.READ, ToolMapper.toAcpKind("list"))
    
    @Test
    fun `grep maps to SEARCH`() = assertEquals(ToolKind.SEARCH, ToolMapper.toAcpKind("grep"))
    
    @Test
    fun `glob maps to SEARCH`() = assertEquals(ToolKind.SEARCH, ToolMapper.toAcpKind("glob"))
    
    @Test
    fun `find maps to SEARCH`() = assertEquals(ToolKind.SEARCH, ToolMapper.toAcpKind("find"))
    
    @Test
    fun `websearch maps to FETCH`() = assertEquals(ToolKind.FETCH, ToolMapper.toAcpKind("websearch"))
    
    @Test
    fun `webfetch maps to FETCH`() = assertEquals(ToolKind.FETCH, ToolMapper.toAcpKind("webfetch"))
    
    @Test
    fun `question maps to THINK`() = assertEquals(ToolKind.THINK, ToolMapper.toAcpKind("question"))
    
    @Test
    fun `lsp maps to READ`() = assertEquals(ToolKind.READ, ToolMapper.toAcpKind("lsp"))
    
    @Test
    fun `skill maps to OTHER`() = assertEquals(ToolKind.OTHER, ToolMapper.toAcpKind("skill"))
    
    @Test
    fun `todowrite maps to OTHER`() = assertEquals(ToolKind.OTHER, ToolMapper.toAcpKind("todowrite"))
    
    @Test
    fun `task maps to OTHER`() = assertEquals(ToolKind.OTHER, ToolMapper.toAcpKind("task"))
    
    @Test
    fun `external_directory maps to OTHER`() = assertEquals(ToolKind.OTHER, ToolMapper.toAcpKind("external_directory"))
    
    @Test
    fun `unknown tool maps to OTHER`() = assertEquals(ToolKind.OTHER, ToolMapper.toAcpKind("unknown_tool"))
    
    @Test
    fun `tool names are case insensitive`() = assertEquals(ToolKind.EXECUTE, ToolMapper.toAcpKind("BASH"))
    
    @Test
    fun `allMappings returns expected tool kinds`() {
        val mappings = ToolMapper.allMappings()
        assert(mappings.containsKey(ToolKind.EXECUTE))
        assert(mappings.containsKey(ToolKind.EDIT))
        assert(mappings.containsKey(ToolKind.READ))
        assert(mappings.containsKey(ToolKind.SEARCH))
        assert(mappings.containsKey(ToolKind.FETCH))
        assert(mappings.containsKey(ToolKind.THINK))
        assert(mappings.containsKey(ToolKind.OTHER))
    }
}
