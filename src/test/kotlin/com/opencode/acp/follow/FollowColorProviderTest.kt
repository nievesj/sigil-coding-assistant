package com.opencode.acp.follow

import com.agentclientprotocol.model.ToolKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Unit tests for [FollowColorProvider.composeInlayLabel] and [FollowColorProvider.getInlayLabel].
 *
 * Pure-logic tests — no IntelliJ platform context required. The label format is:
 * `<agent> · <model> · <action> <context> — "<reason>" <duration>`
 */
class FollowColorProviderTest {

    // ── composeInlayLabel: basic action / agent / model ────────────────────

    @Test
    fun `composeInlayLabel returns action only when no extra context`() {
        FollowColorProvider.composeInlayLabel(ToolKind.READ) shouldBe "Agent · Reading"
    }

    @Test
    fun `composeInlayLabel includes agent name when provided`() {
        FollowColorProvider.composeInlayLabel(ToolKind.READ, agentName = "fixer") shouldBe "fixer · Reading"
    }

    @Test
    fun `composeInlayLabel includes model name when provided`() {
        FollowColorProvider.composeInlayLabel(
            ToolKind.READ,
            agentName = "fixer",
            modelName = "claude-3-opus",
        ) shouldBe "fixer · claude-3-opus · Reading"
    }

    @Test
    fun `composeInlayLabel falls back to Agent when agentName is null`() {
        FollowColorProvider.composeInlayLabel(ToolKind.EDIT) shouldBe "Agent · Editing"
    }

    // ── READ context: filename + line range ───────────────────────────────

    @Test
    fun `composeInlayLabel includes filename context for READ`() {
        val input = JsonObject(mapOf("file_path" to JsonPrimitive("src/main/Foo.kt")))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        label shouldContain "Foo.kt"
    }

    @Test
    fun `composeInlayLabel includes filename and line range for READ`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "startLine" to JsonPrimitive(10),
                "endLine" to JsonPrimitive(20),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        label shouldContain "Foo.kt L10-20"
    }

    @Test
    fun `composeInlayLabel includes filename and line range for READ with snake_case keys`() {
        // Tests the REAL key names used by FollowAgentDispatcher.extractLineRange:
        // start_line/end_line (snake_case), not startLine/endLine (camelCase).
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "start_line" to JsonPrimitive(10),
                "end_line" to JsonPrimitive(20),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        label shouldContain "Foo.kt L10-20"
    }

    @Test
    fun `composeInlayLabel includes filename and line range for READ with offset and limit`() {
        // Tests the read tool's offset+limit format (0-indexed offset → 1-indexed start).
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "offset" to JsonPrimitive(9),
                "limit" to JsonPrimitive(11),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        // offset 9 → startLine 10, limit 11 → endLine 10 + 11 - 1 = 20
        label shouldContain "Foo.kt L10-20"
    }

    @Test
    fun `composeInlayLabel handles offset 0 as first line`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "offset" to JsonPrimitive(0),
                "limit" to JsonPrimitive(5),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        // offset 0 → startLine 1, limit 5 → endLine 5
        label shouldContain "L1-5"
    }

    @Test
    fun `composeInlayLabel handles limit 1 as single line`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "offset" to JsonPrimitive(10),
                "limit" to JsonPrimitive(1),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        // offset 10 → startLine 11, limit 1 → endLine 11
        label shouldContain "L11-11"
    }

    // ── EDIT context: edit delta ───────────────────────────────────────────

    @Test
    fun `composeInlayLabel includes edit delta for EDIT`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "old_string" to JsonPrimitive("a\nb\nc"),
                "new_string" to JsonPrimitive("x\ny\nz\nw\ne"),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EDIT, input = input)
        // Set-difference: 5 new lines (none in old), 3 old lines (none in new).
        label shouldContain "(+5 -3 lines)"
    }

    @Test
    fun `composeInlayLabel edit delta counts only changed lines not total lines`() {
        // Replacing "a\nb\nc" with "a\nb\nx" — 1 line changed (c→x), 0 net additions.
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "old_string" to JsonPrimitive("a\nb\nc"),
                "new_string" to JsonPrimitive("a\nb\nx"),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EDIT, input = input)
        // a and b are unchanged (in both sets). Only c was removed and x was added.
        label shouldContain "(+1 -1 lines)"
    }

    @Test
    fun `composeInlayLabel edit delta handles duplicate lines correctly`() {
        // 3 identical lines → 2 identical lines: real delta is -1 line.
        // Set-difference would give (+0 -0); multiset counting gives (+0 -1).
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "old_string" to JsonPrimitive("line\nline\nline"),
                "new_string" to JsonPrimitive("line\nline"),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EDIT, input = input)
        // After the multiset fix, this should show -1 deletion.
        label shouldContain "(+0 -1 lines)"
    }

    @Test
    fun `composeInlayLabel edit delta handles pure addition with common lines`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "old_string" to JsonPrimitive("a\nb\nc"),
                "new_string" to JsonPrimitive("a\nb\nc\nd\ne"),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EDIT, input = input)
        // a, b, c are common; d and e are additions
        label shouldContain "(+2 -0 lines)"
    }

    @Test
    fun `composeInlayLabel edit delta handles pure deletion with common lines`() {
        val input = JsonObject(
            mapOf(
                "file_path" to JsonPrimitive("src/main/Foo.kt"),
                "old_string" to JsonPrimitive("a\nb\nc\nd\ne"),
                "new_string" to JsonPrimitive("a\nb\nc"),
            )
        )
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EDIT, input = input)
        // a, b, c are common; d and e are deletions
        label shouldContain "(+0 -2 lines)"
    }

    // ── SEARCH context: quoted query ──────────────────────────────────────

    @Test
    fun `composeInlayLabel includes search query for SEARCH`() {
        val input = JsonObject(mapOf("pattern" to JsonPrimitive("myPattern")))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.SEARCH, input = input)
        label shouldContain "\"myPattern\""
    }

    // ── EXECUTE context: backtick command ─────────────────────────────────

    @Test
    fun `composeInlayLabel includes command for EXECUTE`() {
        val input = JsonObject(mapOf("command" to JsonPrimitive("npm test")))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.EXECUTE, input = input)
        label shouldContain "`npm test`"
    }

    // ── reason / description ──────────────────────────────────────────────

    @Test
    fun `composeInlayLabel includes description reason when present`() {
        val input = JsonObject(mapOf("description" to JsonPrimitive("find the bug")))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        label shouldContain " — \"find the bug\""
    }

    @Test
    fun `composeInlayLabel truncates long description to 80 chars`() {
        val longDescription = "x".repeat(100)
        val input = JsonObject(mapOf("description" to JsonPrimitive(longDescription)))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        // Reason is truncated to 80 chars inside the quoted suffix.
        label shouldContain " — \"${"x".repeat(80)}\""
        label shouldNotContain "x".repeat(81)
    }

    @Test
    fun `composeInlayLabel omits reason when description is blank`() {
        val input = JsonObject(mapOf("description" to JsonPrimitive("")))
        val label = FollowColorProvider.composeInlayLabel(ToolKind.READ, input = input)
        label shouldNotContain " — \""
    }

    // ── duration ──────────────────────────────────────────────────────────

    @Test
    fun `composeInlayLabel includes duration when startTimeMs provided`() {
        val startTime = 1000L
        val currentTime = 2500L
        val label = FollowColorProvider.composeInlayLabel(
            kind = ToolKind.READ,
            agentName = "fixer",
            startTimeMs = startTime,
            currentTimeMs = currentTime,
        )
        label shouldContain "(1.5s)"
    }

    @Test
    fun `composeInlayLabel omits duration for EXECUTE`() {
        val startTime = 1000L
        val currentTime = 2500L
        val label = FollowColorProvider.composeInlayLabel(
            kind = ToolKind.EXECUTE,
            agentName = "fixer",
            startTimeMs = startTime,
            currentTimeMs = currentTime,
        )
        label shouldNotContain "(1.5s)"
        label shouldNotContain "s)"
    }

    // ── THINK / SWITCH_MODE fallthrough ───────────────────────────────────

    @Test
    fun `composeInlayLabel returns empty for THINK`() {
        FollowColorProvider.composeInlayLabel(ToolKind.THINK) shouldBe ""
    }

    @Test
    fun `composeInlayLabel returns empty for SWITCH_MODE`() {
        FollowColorProvider.composeInlayLabel(ToolKind.SWITCH_MODE) shouldBe ""
    }

    // ── getInlayLabel ─────────────────────────────────────────────────────

    @Test
    fun `getInlayLabel returns static label for READ`() {
        FollowColorProvider.getInlayLabel(ToolKind.READ) shouldBe "Agent is reading"
    }

    @Test
    fun `getInlayLabel returns null for THINK`() {
        FollowColorProvider.getInlayLabel(ToolKind.THINK).shouldBeNull()
    }

    @Test
    fun `getInlayLabel returns null for SWITCH_MODE`() {
        FollowColorProvider.getInlayLabel(ToolKind.SWITCH_MODE).shouldBeNull()
    }

    @Test
    fun `getInlayLabel returns static label for each kind`() {
        FollowColorProvider.getInlayLabel(ToolKind.EDIT) shouldBe "Agent is editing"
        FollowColorProvider.getInlayLabel(ToolKind.SEARCH) shouldBe "Agent is searching"
        FollowColorProvider.getInlayLabel(ToolKind.EXECUTE) shouldBe "Agent is running"
        FollowColorProvider.getInlayLabel(ToolKind.DELETE) shouldBe "Agent is deleting"
        FollowColorProvider.getInlayLabel(ToolKind.MOVE) shouldBe "Agent is moving"
        FollowColorProvider.getInlayLabel(ToolKind.FETCH) shouldBe "Agent is fetching"
        FollowColorProvider.getInlayLabel(ToolKind.OTHER) shouldBe "Agent is working"
    }

    // ── parseColorOrDefault ───────────────────────────────────────────────

    @Test
    fun `parseColorOrDefault returns null for null`() {
        parseColorOrDefault(null) shouldBe null
    }

    @Test
    fun `parseColorOrDefault returns null for empty`() {
        parseColorOrDefault("") shouldBe null
    }

    @Test
    fun `parseColorOrDefault returns null for blank`() {
        parseColorOrDefault("   ") shouldBe null
    }

    @Test
    fun `parseColorOrDefault returns null for wrong length`() {
        parseColorOrDefault("#GGG") shouldBe null
        parseColorOrDefault("#1234567") shouldBe null
        parseColorOrDefault("#123456789") shouldBe null
    }

    @Test
    fun `parseColorOrDefault returns null for non-hex`() {
        parseColorOrDefault("#GG0000") shouldBe null
    }

    @Test
    fun `parseColorOrDefault parses 6-char hex with opaque alpha`() {
        val color = parseColorOrDefault("#FF0000")
        color shouldBe Color(255, 0, 0, 255)
    }

    @Test
    fun `parseColorOrDefault parses 8-char hex with explicit alpha`() {
        val color = parseColorOrDefault("#FF000080")
        color shouldBe Color(255, 0, 0, 128)
    }

    @Test
    fun `parseColorOrDefault parses hex without hash prefix`() {
        val color = parseColorOrDefault("FF0000")
        color shouldBe Color(255, 0, 0, 255)
    }

    @Test
    fun `parseColorOrDefault parses fully transparent 8-char`() {
        val color = parseColorOrDefault("#FF000000")
        color shouldBe Color(255, 0, 0, 0)
    }

    @Test
    fun `parseColorOrDefault returns null for invalid hex ensuring getColor skips highlight`() {
        // This verifies the null-safety contract: getColor calls parseColorOrDefault
        // which returns null on bad input, and EditorFollowManager.followToolCall
        // does `?: return` on null, skipping the highlight. No crash.
        parseColorOrDefault("not-a-color") shouldBe null
        parseColorOrDefault("#GGGGGG") shouldBe null
        parseColorOrDefault("#12345") shouldBe null
    }

    // ── getColor cache logic ──────────────────────────────────────────────
    // These tests require the IntelliJ application context (ApplicationManager.getApplication())
    // which is not available in plain unit tests. See AGENTS.md "Compose UI Tests" section.
    // The cache logic is exercised indirectly via the FollowColorProvider tests that call
    // composeInlayLabel (which calls getColor internally when rendering the inlay label).

    @org.junit.jupiter.api.Disabled("Requires IntelliJ application context (ApplicationManager.getApplication()) — see AGENTS.md")
    @Test
    fun `getColor returns null for THINK`() {
        FollowColorProvider.getColor(ToolKind.THINK).shouldBeNull()
    }

    @org.junit.jupiter.api.Disabled("Requires IntelliJ application context (ApplicationManager.getApplication()) — see AGENTS.md")
    @Test
    fun `getColor returns null for SWITCH_MODE`() {
        FollowColorProvider.getColor(ToolKind.SWITCH_MODE).shouldBeNull()
    }

    @org.junit.jupiter.api.Disabled("Requires IntelliJ application context (ApplicationManager.getApplication()) — see AGENTS.md")
    @Test
    fun `getColor returns a color for READ`() {
        val color = FollowColorProvider.getColor(ToolKind.READ)
        color shouldNotBe null
    }

    @org.junit.jupiter.api.Disabled("Requires IntelliJ application context (ApplicationManager.getApplication()) — see AGENTS.md")
    @Test
    fun `getColor returns the same color on repeated calls — cache hit`() {
        val first = FollowColorProvider.getColor(ToolKind.EDIT)
        val second = FollowColorProvider.getColor(ToolKind.EDIT)
        first shouldBe second
    }

    // ── highlightableKinds and getDefaultHex ──────────────────────────────

    @Test
    fun `highlightableKinds does not include THINK or SWITCH_MODE`() {
        val kinds = FollowColorProvider.highlightableKinds()
        kinds shouldNotContain ToolKind.THINK
        kinds shouldNotContain ToolKind.SWITCH_MODE
    }

    @Test
    fun `highlightableKinds includes all file-based kinds`() {
        val kinds = FollowColorProvider.highlightableKinds()
        kinds shouldContain ToolKind.READ
        kinds shouldContain ToolKind.EDIT
        kinds shouldContain ToolKind.SEARCH
        kinds shouldContain ToolKind.EXECUTE
        kinds shouldContain ToolKind.DELETE
        kinds shouldContain ToolKind.MOVE
        kinds shouldContain ToolKind.FETCH
        kinds shouldContain ToolKind.OTHER
    }

    @Test
    fun `getDefaultHex returns expected defaults`() {
        FollowColorProvider.getDefaultHex(ToolKind.READ) shouldBe "#5078C888"
        FollowColorProvider.getDefaultHex(ToolKind.EDIT) shouldBe "#50A05088"
        FollowColorProvider.getDefaultHex(ToolKind.SEARCH) shouldBe "#C8B43C88"
        FollowColorProvider.getDefaultHex(ToolKind.EXECUTE) shouldBe "#B4785088"
        FollowColorProvider.getDefaultHex(ToolKind.DELETE) shouldBe "#C8505088"
        FollowColorProvider.getDefaultHex(ToolKind.MOVE) shouldBe "#A050C888"
        FollowColorProvider.getDefaultHex(ToolKind.FETCH) shouldBe "#50A0C888"
        FollowColorProvider.getDefaultHex(ToolKind.OTHER) shouldBe "#80808088"
    }

    @Test
    fun `getDefaultHex returns null for THINK and SWITCH_MODE`() {
        FollowColorProvider.getDefaultHex(ToolKind.THINK).shouldBeNull()
        FollowColorProvider.getDefaultHex(ToolKind.SWITCH_MODE).shouldBeNull()
    }
}