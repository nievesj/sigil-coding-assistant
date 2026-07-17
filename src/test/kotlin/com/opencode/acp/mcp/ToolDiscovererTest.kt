package com.opencode.acp.mcp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ToolDiscoverer] (TDD §4.2.6).
 *
 * Uses WireMock for the OpenCode /experimental/tool/ids endpoint and MockK
 * for [McpToolDiscovery]. Tests built-in tool discovery, MCP tool discovery,
 * permission merging, and the concurrent-discovery guard.
 */
class ToolDiscovererTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var httpClient: io.ktor.client.HttpClient
    private lateinit var mcpToolDiscovery: McpToolDiscovery
    private lateinit var discoverer: ToolDiscoverer

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wireMock.start()
        WireMock.configureFor("localhost", wireMock.port())

        httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.java.Java)
        mcpToolDiscovery = mockk<McpToolDiscovery>(relaxed = true)
        discoverer = ToolDiscoverer(httpClient, mcpToolDiscovery, json)
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        wireMock.stop()
    }

    private fun stubToolIds(vararg ids: String) {
        val body = if (ids.isEmpty()) "[]" else "[${ids.joinToString(",") { "\"$it\"" }}]"
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/experimental/tool/ids"))
                .willReturn(WireMock.okJson(body))
        )
    }

    // ── getBuiltinToolDescription ─────────────────────────────────────────

    @Test
    fun `getBuiltinToolDescription returns description for known tools`() {
        discoverer.getBuiltinToolDescription("bash") shouldBe "Execute shell commands"
        discoverer.getBuiltinToolDescription("read") shouldBe "Read file contents"
        discoverer.getBuiltinToolDescription("edit") shouldBe "Edit files with string replacement"
        discoverer.getBuiltinToolDescription("write") shouldBe "Write new files"
        discoverer.getBuiltinToolDescription("grep") shouldBe "Search file contents with regex"
        discoverer.getBuiltinToolDescription("glob") shouldBe "Find files by pattern"
        discoverer.getBuiltinToolDescription("task") shouldBe "Launch specialized agents"
        discoverer.getBuiltinToolDescription("webfetch") shouldBe "Fetch URLs and extract content"
        discoverer.getBuiltinToolDescription("websearch") shouldBe "Search the web"
        discoverer.getBuiltinToolDescription("todowrite") shouldBe "Manage task lists"
    }

    @Test
    fun `getBuiltinToolDescription returns generic description for unknown tool`() {
        val desc = discoverer.getBuiltinToolDescription("unknown_tool")
        desc shouldBe "Built-in tool: unknown_tool"
    }

    @Test
    fun `getBuiltinToolDescription returns description for all known tools`() {
        // Verify all entries in the when block return non-generic descriptions
        val knownTools = listOf("bash", "read", "glob", "grep", "edit", "write", "task",
            "webfetch", "todowrite", "websearch", "skill", "apply_patch",
            "council_session", "auto_continue", "ast_grep_search", "ast_grep_replace",
            "subtask", "read_session")
        for (tool in knownTools) {
            val desc = discoverer.getBuiltinToolDescription(tool)
            desc shouldNotBe "Built-in tool: $tool"
        }
    }

    // ── discoverAll (built-in tools) ──────────────────────────────────────

    @Test
    fun `discoverAll discovers built-in tools from endpoint`() = runTest {
        stubToolIds("bash", "read", "edit")
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools.size shouldBe 3
        tools.map { it.name } shouldContain "bash"
        tools.map { it.name } shouldContain "read"
        tools.map { it.name } shouldContain "edit"
    }

    @Test
    fun `discoverAll assigns BUILTIN source to built-in tools`() = runTest {
        stubToolIds("bash")
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools[0].source shouldBe ToolSource.BUILTIN
        tools[0].serverName shouldBe "builtin"
    }

    @Test
    fun `discoverAll sets enabled and ALLOW permission for built-in tools`() = runTest {
        stubToolIds("bash")
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools[0].enabled shouldBe true
        tools[0].permission shouldBe ToolPermission.ALLOW
    }

    @Test
    fun `discoverAll returns empty list when endpoint returns 404`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/experimental/tool/ids"))
                .willReturn(WireMock.status(404))
        )
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools.size shouldBe 0
    }

    @Test
    fun `discoverAll handles wrapped format with value key`() = runTest {
        wireMock.stubFor(
            WireMock.get(WireMock.urlEqualTo("/experimental/tool/ids"))
                .willReturn(WireMock.okJson("""{"value":["bash","read"]}"""))
        )
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools.size shouldBe 2
    }

    // ── discoverAll (MCP tools) ───────────────────────────────────────────

    @Test
    fun `discoverAll discovers MCP tools from McpToolDiscovery`() = runTest {
        stubToolIds() // no built-in tools
        val mcpTools = mapOf(
            "server1" to listOf(
                McpToolDescriptor(name = "create_file", description = "Create a file"),
                McpToolDescriptor(name = "delete_file", description = "Delete a file"),
            )
        )
        coEvery { mcpToolDiscovery.discoverAllTools(mapOf("server1" to "http://sse")) } returns mcpTools

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = mapOf("server1" to "http://sse"),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools.size shouldBe 2
        tools.map { it.name } shouldContain "create_file"
        tools.map { it.name } shouldContain "delete_file"
    }

    @Test
    fun `discoverAll assigns MCP source to MCP tools`() = runTest {
        stubToolIds()
        val mcpTools = mapOf(
            "server1" to listOf(McpToolDescriptor(name = "tool1", description = "desc"))
        )
        coEvery { mcpToolDiscovery.discoverAllTools(any()) } returns mcpTools

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = mapOf("server1" to "http://sse"),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools[0].source shouldBe ToolSource.MCP
        tools[0].serverName shouldBe "server1"
    }

    @Test
    fun `discoverAll combines built-in and MCP tools`() = runTest {
        stubToolIds("bash", "read")
        val mcpTools = mapOf(
            "server1" to listOf(McpToolDescriptor(name = "custom_tool", description = "Custom"))
        )
        coEvery { mcpToolDiscovery.discoverAllTools(any()) } returns mcpTools

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = mapOf("server1" to "http://sse"),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        tools.size shouldBe 3
    }

    // ── Permission merging ────────────────────────────────────────────────

    @Test
    fun `discoverAll preserves permissions from old snapshot`() = runTest {
        stubToolIds("bash")
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        // Pre-populate snapshot with a tool that has DENY permission
        val oldTool = ToolInfo.create(
            name = "bash",
            description = "old desc",
            source = ToolSource.BUILTIN,
            enabled = false,
            permission = ToolPermission.DENY,
        )
        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(mapOf(oldTool.id to oldTool))
        val mutex = Mutex()

        val tools = discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        // The discovered tool should have the old permission/enabled state
        val bashTool = tools.find { it.name == "bash" }!!
        bashTool.enabled shouldBe false
        bashTool.permission shouldBe ToolPermission.DENY
    }

    @Test
    fun `discoverAll updates snapshot atomically`() = runTest {
        stubToolIds("bash")
        coEvery { mcpToolDiscovery.discoverAllTools(emptyMap()) } returns emptyMap()

        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()

        discoverer.discoverAll(
            opencodeBaseUrl = wireMock.baseUrl(),
            mcpServerUrls = emptyMap(),
            toolsMutex = mutex,
            snapshotRef = { snapshot.get() },
            snapshotSetter = { snapshot.set(it) },
        )

        snapshot.get().size shouldBe 1
        snapshot.get().containsKey("builtin_bash") shouldBe true
    }
}