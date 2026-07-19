package com.opencode.acp.mcp

import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ToolPermissionManager] (TDD §4.2.6).
 *
 * Tests tool permission state management: enable/disable, allow/ask/deny,
 * batch operations, persistence, and JSON export. The manager does NOT own
 * the tool snapshot — it receives the mutex and snapshot reference/setter as
 * parameters, so tests use a simple `var snapshot` with lambda accessors.
 */
class ToolPermissionManagerTest {

    private val manager = ToolPermissionManager()

    private fun makeTool(
        name: String,
        serverName: String = "builtin",
        enabled: Boolean = true,
        permission: ToolPermission = ToolPermission.ALLOW,
    ): ToolInfo = ToolInfo.create(
        name = name,
        description = "desc for $name",
        source = if (serverName == "builtin") ToolSource.BUILTIN else ToolSource.MCP,
        serverName = serverName,
        enabled = enabled,
        permission = permission,
    )

    private fun makeSnapshot(): Pair<java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>, Mutex> {
        val snapshot = java.util.concurrent.atomic.AtomicReference<Map<String, ToolInfo>>(emptyMap())
        val mutex = Mutex()
        return snapshot to mutex
    }

    // ── setToolPermission ─────────────────────────────────────────────────

    @Test
    fun `setToolPermission updates the tool permission in the snapshot`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.setToolPermission(
            tool.id, ToolPermission.DENY, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.DENY
    }

    @Test
    fun `setToolPermission with unknown toolId is a no-op`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash")
        snapshot.set(mapOf(tool.id to tool))

        manager.setToolPermission(
            "nonexistent", ToolPermission.ASK, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get().size shouldBe 1
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ALLOW
    }

    // ── setToolEnabled ────────────────────────────────────────────────────

    @Test
    fun `setToolEnabled updates the tool enabled flag`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true)
        snapshot.set(mapOf(tool.id to tool))

        manager.setToolEnabled(
            tool.id, false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe false
    }

    @Test
    fun `setToolEnabled preserves the permission flag`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ASK)
        snapshot.set(mapOf(tool.id to tool))

        manager.setToolEnabled(
            tool.id, false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe false
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    @Test
    fun `setToolEnabled with unknown toolId is a no-op`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash")
        snapshot.set(mapOf(tool.id to tool))

        manager.setToolEnabled(
            "nonexistent", false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
    }

    // ── enableAll ─────────────────────────────────────────────────────────

    @Test
    fun `enableAll with null enables all tools overriding DENY to ALLOW`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val denyTool = makeTool("bash", enabled = false, permission = ToolPermission.DENY)
        val askTool = makeTool("read", enabled = false, permission = ToolPermission.ASK)
        val allowTool = makeTool("edit", enabled = false, permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(denyTool.id to denyTool, askTool.id to askTool, allowTool.id to allowTool))

        manager.enableAll(null, mutex, { snapshot.get() }, { snapshot.set(it) })

        snapshot.get()[denyTool.id]!!.enabled shouldBe true
        snapshot.get()[denyTool.id]!!.permission shouldBe ToolPermission.ALLOW
        snapshot.get()[askTool.id]!!.enabled shouldBe true
        // ASK is preserved (not overridden to ALLOW)
        snapshot.get()[askTool.id]!!.permission shouldBe ToolPermission.ASK
        snapshot.get()[allowTool.id]!!.enabled shouldBe true
        snapshot.get()[allowTool.id]!!.permission shouldBe ToolPermission.ALLOW
    }

    @Test
    fun `enableAll with savedPermissions restores permissions from saved map`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = false, permission = ToolPermission.DENY)
        snapshot.set(mapOf(tool.id to tool))

        val saved = mapOf(tool.id to ToolPermission.ASK)
        manager.enableAll(saved, mutex, { snapshot.get() }, { snapshot.set(it) })

        snapshot.get()[tool.id]!!.enabled shouldBe true
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    @Test
    fun `enableAll with empty savedPermissions falls back to in-memory cache`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ASK)
        snapshot.set(mapOf(tool.id to tool))

        // First disable to populate the in-memory cache
        manager.disableAll(mutex, { snapshot.get() }, { snapshot.set(it) })
        // Then enable with empty saved map — should restore ASK from cache
        manager.enableAll(emptyMap(), mutex, { snapshot.get() }, { snapshot.set(it) })

        snapshot.get()[tool.id]!!.enabled shouldBe true
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    // ── disableAll ─────────────────────────────────────────────────────────

    @Test
    fun `disableAll disables all tools and sets DENY permission`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool1 = makeTool("bash", enabled = true, permission = ToolPermission.ALLOW)
        val tool2 = makeTool("read", enabled = true, permission = ToolPermission.ASK)
        snapshot.set(mapOf(tool1.id to tool1, tool2.id to tool2))

        val saved = manager.disableAll(mutex, { snapshot.get() }, { snapshot.set(it) })

        snapshot.get()[tool1.id]!!.enabled shouldBe false
        snapshot.get()[tool1.id]!!.permission shouldBe ToolPermission.DENY
        snapshot.get()[tool2.id]!!.enabled shouldBe false
        snapshot.get()[tool2.id]!!.permission shouldBe ToolPermission.DENY
        saved[tool1.id] shouldBe ToolPermission.ALLOW
        saved[tool2.id] shouldBe ToolPermission.ASK
    }

    @Test
    fun `disableAll returns saved permissions map for restoration`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", permission = ToolPermission.ASK)
        snapshot.set(mapOf(tool.id to tool))

        val saved = manager.disableAll(mutex, { snapshot.get() }, { snapshot.set(it) })

        saved shouldContainKey tool.id
        saved[tool.id] shouldBe ToolPermission.ASK
    }

    // ── syncEnabled ───────────────────────────────────────────────────────

    @Test
    fun `syncEnabled true enables matching tools by name`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = false, permission = ToolPermission.DENY)
        snapshot.set(mapOf(tool.id to tool))

        manager.syncEnabled(
            setOf("bash"), true, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ALLOW
    }

    @Test
    fun `syncEnabled true enables matching tools by id`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = false, permission = ToolPermission.DENY)
        snapshot.set(mapOf(tool.id to tool))

        manager.syncEnabled(
            setOf(tool.id), true, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
    }

    @Test
    fun `syncEnabled true preserves ASK permission`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = false, permission = ToolPermission.ASK)
        snapshot.set(mapOf(tool.id to tool))

        manager.syncEnabled(
            setOf("bash"), true, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    @Test
    fun `syncEnabled false disables matching tools`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.syncEnabled(
            setOf("bash"), false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe false
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.DENY
    }

    @Test
    fun `syncEnabled applies to all tools with the same name across servers`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val toolA = makeTool("create_file", serverName = "serverA")
        val toolB = makeTool("create_file", serverName = "serverB")
        snapshot.set(mapOf(toolA.id to toolA, toolB.id to toolB))

        manager.syncEnabled(
            setOf("create_file"), false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[toolA.id]!!.enabled shouldBe false
        snapshot.get()[toolB.id]!!.enabled shouldBe false
    }

    @Test
    fun `syncEnabled does not affect non-matching tools`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val matching = makeTool("bash", enabled = true)
        val other = makeTool("read", enabled = true)
        snapshot.set(mapOf(matching.id to matching, other.id to other))

        manager.syncEnabled(
            setOf("bash"), false, mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[matching.id]!!.enabled shouldBe false
        snapshot.get()[other.id]!!.enabled shouldBe true
    }

    // ── loadPermissions ──────────────────────────────────────────────────

    @Test
    fun `loadPermissions applies permission overrides by name`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.loadPermissions(
            mapOf("bash" to ToolPermission.DENY), mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.DENY
    }

    @Test
    fun `loadPermissions applies permission overrides by id`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.loadPermissions(
            mapOf(tool.id to ToolPermission.ASK), mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    @Test
    fun `loadPermissions does not change enabled flag`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.loadPermissions(
            mapOf("bash" to ToolPermission.DENY), mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
    }

    @Test
    fun `loadPermissions applies to all matching tools with same name`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val toolA = makeTool("create_file", serverName = "serverA", permission = ToolPermission.ALLOW)
        val toolB = makeTool("create_file", serverName = "serverB", permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(toolA.id to toolA, toolB.id to toolB))

        manager.loadPermissions(
            mapOf("create_file" to ToolPermission.DENY), mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[toolA.id]!!.permission shouldBe ToolPermission.DENY
        snapshot.get()[toolB.id]!!.permission shouldBe ToolPermission.DENY
    }

    // ── loadEnabledAndPermissions ─────────────────────────────────────────

    @Test
    fun `loadEnabledAndPermissions applies both enabled and permission`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ALLOW)
        snapshot.set(mapOf(tool.id to tool))

        manager.loadEnabledAndPermissions(
            mapOf("bash" to (false to ToolPermission.ASK)),
            mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe false
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ASK
    }

    @Test
    fun `loadEnabledAndPermissions matches by id`() = runTest {
        val (snapshot, mutex) = makeSnapshot()
        val tool = makeTool("bash", enabled = false, permission = ToolPermission.DENY)
        snapshot.set(mapOf(tool.id to tool))

        manager.loadEnabledAndPermissions(
            mapOf(tool.id to (true to ToolPermission.ALLOW)),
            mutex, { snapshot.get() }, { snapshot.set(it) }
        )

        snapshot.get()[tool.id]!!.enabled shouldBe true
        snapshot.get()[tool.id]!!.permission shouldBe ToolPermission.ALLOW
    }

    // ── exportPermissionsJson ─────────────────────────────────────────────

    @Test
    fun `exportPermissionsJson produces valid JSON string`() {
        val permissions = mapOf(
            "bash" to (true to ToolPermission.ALLOW),
            "read" to (false to ToolPermission.DENY),
        )
        val json = manager.exportPermissionsJson(permissions)
        val parsed = Json.parseToJsonElement(json).jsonObject

        parsed.containsKey("bash") shouldBe true
        parsed.containsKey("read") shouldBe true
        parsed["bash"]!!.jsonObject["enabled"]!!.jsonPrimitive.content shouldBe "true"
        parsed["bash"]!!.jsonObject["permission"]!!.jsonPrimitive.content shouldBe "allow"
        parsed["read"]!!.jsonObject["enabled"]!!.jsonPrimitive.content shouldBe "false"
        parsed["read"]!!.jsonObject["permission"]!!.jsonPrimitive.content shouldBe "deny"
    }

    @Test
    fun `exportPermissionsJson with empty map produces empty JSON object`() {
        val json = manager.exportPermissionsJson(emptyMap())
        json shouldBe "{}"
    }

    @Test
    fun `exportPermissionsJson encodes ASK permission as ask`() {
        val permissions = mapOf("bash" to (true to ToolPermission.ASK))
        val json = manager.exportPermissionsJson(permissions)
        val parsed = Json.parseToJsonElement(json).jsonObject
        parsed["bash"]!!.jsonObject["permission"]!!.jsonPrimitive.content shouldBe "ask"
    }

    // ── exportPermissionsJsonFromSnapshot ──────────────────────────────────

    @Test
    fun `exportPermissionsJsonFromSnapshot produces valid JSON keyed by tool id`() {
        val tool = makeTool("bash", enabled = true, permission = ToolPermission.ALLOW)
        val snapshot = mapOf(tool.id to tool)

        val json = manager.exportPermissionsJsonFromSnapshot(snapshot)
        val parsed = Json.parseToJsonElement(json).jsonObject

        parsed.containsKey(tool.id) shouldBe true
        parsed[tool.id]!!.jsonObject["enabled"]!!.jsonPrimitive.content shouldBe "true"
        parsed[tool.id]!!.jsonObject["permission"]!!.jsonPrimitive.content shouldBe "allow"
    }

    // ── exportPermissions ─────────────────────────────────────────────────

    @Test
    fun `exportPermissions produces a Map keyed by tool name`() {
        val tool = makeTool("bash", permission = ToolPermission.DENY)
        val snapshot = mapOf(tool.id to tool)

        val exported = manager.exportPermissions(snapshot)
        exported["bash"] shouldBe ToolPermission.DENY
    }

    @Test
    fun `exportPermissions with multiple tools maps each by name`() {
        val tool1 = makeTool("bash", permission = ToolPermission.ALLOW)
        val tool2 = makeTool("read", permission = ToolPermission.ASK)
        val snapshot = mapOf(tool1.id to tool1, tool2.id to tool2)

        val exported = manager.exportPermissions(snapshot)
        exported["bash"] shouldBe ToolPermission.ALLOW
        exported["read"] shouldBe ToolPermission.ASK
    }

    @Test
    fun `exportPermissions with empty snapshot returns empty map`() {
        val exported = manager.exportPermissions(emptyMap())
        exported shouldBe emptyMap()
    }
}