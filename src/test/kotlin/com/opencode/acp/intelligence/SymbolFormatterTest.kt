package com.opencode.acp.intelligence

import com.opencode.acp.intelligence.model.AffectedSymbol
import com.opencode.acp.intelligence.model.CallHierarchyNode
import com.opencode.acp.intelligence.model.ClassStructure
import com.opencode.acp.intelligence.model.FileStructure
import com.opencode.acp.intelligence.model.ImpactResult
import com.opencode.acp.intelligence.model.MemberInfo
import com.opencode.acp.intelligence.model.ReferenceInfo
import com.opencode.acp.intelligence.model.RepoMapEntry
import com.opencode.acp.intelligence.model.RiskLevel
import com.opencode.acp.intelligence.model.SymbolInfo
import com.opencode.acp.intelligence.model.SymbolKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SymbolFormatter] — pure-logic JSON serialization of PSI
 * query results.
 *
 * Verifies field presence/omission, JSON escaping, truncation footer, and
 * the `_meta` footer for truncated reference lists.
 */
class SymbolFormatterTest {

    // ------------------------------------------------------------------
    // formatError
    // ------------------------------------------------------------------

    @Test
    fun `formatError with retry false produces valid JSON with error and retry fields`() {
        val json = SymbolFormatter.formatError("not found", retry = false)
        json shouldBe """{"error":"not found","retry":false}"""
    }

    @Test
    fun `formatError with retry true has retry=true`() {
        val json = SymbolFormatter.formatError("indexing", retry = true)
        json shouldBe """{"error":"indexing","retry":true}"""
    }

    @Test
    fun `formatError escapes quotes and backslashes`() {
        val json = SymbolFormatter.formatError("""a "quote" and \ backslash""", retry = false)
        json shouldContain """"error":"a \"quote\" and \\ backslash""""
        // Unescaped quote must not appear inside the string value.
        json shouldNotContain """"a "quote"""" // unescaped quote must not appear
    }

    // ------------------------------------------------------------------
    // jsonEscape
    // ------------------------------------------------------------------

    @Test
    fun `jsonEscape escapes backslash`() {
        SymbolFormatter.jsonEscape("a\\b") shouldBe "a\\\\b"
    }

    @Test
    fun `jsonEscape escapes double quote`() {
        SymbolFormatter.jsonEscape("a\"b") shouldBe "a\\\"b"
    }

    @Test
    fun `jsonEscape escapes newline`() {
        SymbolFormatter.jsonEscape("a\nb") shouldBe "a\\nb"
    }

    @Test
    fun `jsonEscape escapes tab`() {
        SymbolFormatter.jsonEscape("a\tb") shouldBe "a\\tb"
    }

    @Test
    fun `jsonEscape escapes control chars`() {
        SymbolFormatter.jsonEscape("a\u0001b") shouldBe "a\\u0001b"
    }

    // ------------------------------------------------------------------
    // formatSymbols
    // ------------------------------------------------------------------

    @Test
    fun `formatSymbols produces JSON array with correct fields`() {
        val symbols = listOf(
            SymbolInfo("Foo", SymbolKind.CLASS, "src/Foo.kt", 10, signature = "class Foo"),
        )
        val json = SymbolFormatter.formatSymbols(symbols)
        json shouldBe """[{"name":"Foo","kind":"CLASS","file":"src/Foo.kt","line":10,"signature":"class Foo"}]"""
    }

    @Test
    fun `formatSymbols omits signature and qualifiedName when null`() {
        val symbols = listOf(
            SymbolInfo("bar", SymbolKind.FUNCTION, "src/Bar.kt", 5),
        )
        val json = SymbolFormatter.formatSymbols(symbols)
        json shouldBe """[{"name":"bar","kind":"FUNCTION","file":"src/Bar.kt","line":5}]"""
        json shouldNotContain "signature"
        json shouldNotContain "qualifiedName"
    }

    @Test
    fun `formatSymbols includes qualifiedName when present`() {
        val symbols = listOf(
            SymbolInfo("Foo", SymbolKind.CLASS, "src/Foo.kt", 10, qualifiedName = "com.example.Foo"),
        )
        val json = SymbolFormatter.formatSymbols(symbols)
        json shouldContain """"qualifiedName":"com.example.Foo""""
        json shouldNotContain "signature"
    }

    @Test
    fun `formatSymbols with empty list returns empty array`() {
        SymbolFormatter.formatSymbols(emptyList()) shouldBe "[]"
    }

    // ------------------------------------------------------------------
    // formatReferences
    // ------------------------------------------------------------------

    @Test
    fun `formatReferences with truncated true includes _meta footer with total and showing`() {
        val refs = listOf(
            ReferenceInfo("A.kt", 10, 5, "Foo.bar()", "val x = Foo()"),
        )
        val json = SymbolFormatter.formatReferences(refs, truncated = true, total = 42)
        json shouldContain """"references":["""
        json shouldContain """"truncated":true"""
        json shouldContain """"total":42"""
        json shouldContain """"showing":1"""
    }

    @Test
    fun `formatReferences with truncated false has no _meta footer`() {
        val refs = listOf(
            ReferenceInfo("A.kt", 10, 5, null, "val x = Foo()"),
        )
        val json = SymbolFormatter.formatReferences(refs, truncated = false, total = 1)
        json shouldStartWith "["
        json shouldEndWith "]"
        json shouldNotContain "references"
        json shouldNotContain "truncated"
        json shouldNotContain "total"
        json shouldNotContain "showing"
        json shouldNotContain "enclosingSymbol"
    }

    // ------------------------------------------------------------------
    // formatCallHierarchy
    // ------------------------------------------------------------------

    @Test
    fun `formatCallHierarchy produces nested JSON with children`() {
        val root = CallHierarchyNode(
            name = "main",
            kind = SymbolKind.FUNCTION,
            file = "Main.kt",
            line = 1,
            children = listOf(
                CallHierarchyNode("helper", SymbolKind.FUNCTION, "Util.kt", 5),
            ),
        )
        val json = SymbolFormatter.formatCallHierarchy(root)
        json shouldContain """"name":"main""""
        json shouldContain """"children":["""
        json shouldContain """"name":"helper""""
    }

    // ------------------------------------------------------------------
    // formatImpact
    // ------------------------------------------------------------------

    @Test
    fun `formatImpact produces JSON with all ImpactResult fields`() {
        val result = ImpactResult(
            symbol = "processOrder",
            affectedFiles = listOf("A.kt", "B.kt"),
            affectedSymbols = listOf(
                AffectedSymbol("caller", SymbolKind.METHOD, "A.kt", 10, 1, "calls"),
            ),
            riskLevel = RiskLevel.HIGH,
            summary = "High impact",
            totalAffected = 25,
        )
        val json = SymbolFormatter.formatImpact(result)
        json shouldContain """"symbol":"processOrder""""
        json shouldContain """"affectedFiles":["A.kt","B.kt"]"""
        json shouldContain """"riskLevel":"HIGH""""
        json shouldContain """"summary":"High impact""""
        json shouldContain """"totalAffected":25"""
        json shouldContain """"name":"caller""""
    }

    // ------------------------------------------------------------------
    // formatFileStructure
    // ------------------------------------------------------------------

    @Test
    fun `formatFileStructure produces JSON with nested classes`() {
        val structure = FileStructure(
            file = "src/Foo.kt",
            language = "Kotlin",
            classes = listOf(
                ClassStructure(
                    name = "Foo",
                    kind = SymbolKind.CLASS,
                    fields = listOf(MemberInfo("count", "val count: Int", listOf("public"))),
                    methods = listOf(MemberInfo("bar", "fun bar()", listOf("private"))),
                    nestedClasses = listOf(
                        ClassStructure("Inner", SymbolKind.CLASS, emptyList(), emptyList(), emptyList()),
                    ),
                ),
            ),
        )
        val json = SymbolFormatter.formatFileStructure(structure)
        json shouldContain """"file":"src/Foo.kt""""
        json shouldContain """"language":"Kotlin""""
        json shouldContain """"name":"Foo""""
        json shouldContain """"name":"Inner""""
        json shouldContain """"name":"bar""""
        json shouldContain """"name":"count""""
    }

    // ------------------------------------------------------------------
    // formatRepoMap
    // ------------------------------------------------------------------

    @Test
    fun `formatRepoMap produces JSON array with importance as double`() {
        val entries = listOf(
            RepoMapEntry("Foo", SymbolKind.CLASS, "src/Foo.kt", 10, referenceCount = 50, importance = 0.85),
        )
        val json = SymbolFormatter.formatRepoMap(entries)
        json shouldBe """[{"name":"Foo","kind":"CLASS","file":"src/Foo.kt","line":10,"referenceCount":50,"importance":0.85}]"""
    }

    @Test
    fun `formatRepoMap with empty list returns empty array`() {
        SymbolFormatter.formatRepoMap(emptyList()) shouldBe "[]"
    }

    // ------------------------------------------------------------------
    // formatCandidates
    // ------------------------------------------------------------------

    @Test
    fun `formatCandidates produces JSON with ambiguous true and candidates array`() {
        val candidates = listOf(
            SymbolInfo("Foo", SymbolKind.CLASS, "a/Foo.kt", 1),
            SymbolInfo("Foo", SymbolKind.CLASS, "b/Foo.kt", 2),
        )
        val json = SymbolFormatter.formatCandidates(candidates)
        json shouldContain """"ambiguous":true"""
        json shouldContain """"candidates":["""
        json shouldContain """"file":"a/Foo.kt""""
        json shouldContain """"file":"b/Foo.kt""""
    }

    // ------------------------------------------------------------------
    // enforceTokenBudget (via direct call)
    // ------------------------------------------------------------------

    @Test
    fun `enforceTokenBudget with short string returns unchanged`() {
        val json = """{"x":1}"""
        SymbolFormatter.enforceTokenBudget(json) shouldBe json
    }

    @Test
    fun `enforceTokenBudget with string exceeding limit truncates and appends _truncated`() {
        // Build a JSON object larger than MAX_TOOL_OUTPUT_CHARS.
        val big = "x".repeat(MAX_TOOL_OUTPUT_CHARS + 100)
        val json = """{"data":"$big"}"""
        val result = SymbolFormatter.enforceTokenBudget(json)
        // Result must contain the truncation marker and be no longer than
        // the limit plus the marker length.
        result shouldContain """"_truncated":true"""
        (result.length <= MAX_TOOL_OUTPUT_CHARS + 50) shouldBe true
    }

    @Test
    fun `enforceTokenBudget truncation inside string produces valid JSON`() {
        // Build a JSON object where the string value is larger than the limit,
        // so truncation falls inside the string literal.
        val big = "x".repeat(MAX_TOOL_OUTPUT_CHARS + 100)
        val json = """{"data":"$big"}"""
        val result = SymbolFormatter.enforceTokenBudget(json)

        // The result MUST be parseable as valid JSON — the truncation logic
        // must close any open string and containers before appending the marker.
        result shouldContain """"_truncated":true"""
        // Actually parse the result to verify it's valid JSON.
        kotlinx.serialization.json.Json.parseToJsonElement(result)
    }

    // ------------------------------------------------------------------
    // enforceTokenBudget — boundary truncation edge cases
    //
    // Each test constructs JSON where `json.substring(0, MAX_TOOL_OUTPUT_CHARS)`
    // ends exactly at a key-value boundary character (`:`, `,`, `{`, or `[`).
    // The filler is placed inside a string value so the prefix up to the
    // boundary is valid JSON, and the boundary character falls right at the
    // cut point. The result must remain valid JSON after the marker is
    // inserted.
    // ------------------------------------------------------------------

    @Test
    fun `enforceTokenBudget truncation after colon produces valid JSON`() {
        // head = {"a":"<filler>","b":  — ends with ':' at exactly MAX_TOOL_OUTPUT_CHARS.
        // {"a":" = 6 chars, ","b": = 6 chars → filler = MAX_TOOL_OUTPUT_CHARS - 12.
        val head = "{\"a\":\"" + "x".repeat(MAX_TOOL_OUTPUT_CHARS - 12) + "\",\"b\":"
        val json = head + "1}" // total > MAX_TOOL_OUTPUT_CHARS; cut falls after ':'
        head.length shouldBe MAX_TOOL_OUTPUT_CHARS
        val result = SymbolFormatter.enforceTokenBudget(json)
        result shouldContain """"_truncated":true"""
        kotlinx.serialization.json.Json.parseToJsonElement(result) // must not throw
    }

    @Test
    fun `enforceTokenBudget truncation after comma in array produces valid JSON`() {
        // head = [1,2,3,"<filler>",  — ends with ',' at exactly MAX_TOOL_OUTPUT_CHARS.
        // [1,2,3," = 8 chars, ", = 2 chars → filler = MAX_TOOL_OUTPUT_CHARS - 10.
        val head = "[1,2,3,\"" + "x".repeat(MAX_TOOL_OUTPUT_CHARS - 10) + "\","
        val json = head + "4]" // total > MAX_TOOL_OUTPUT_CHARS; cut falls after ','
        head.length shouldBe MAX_TOOL_OUTPUT_CHARS
        val result = SymbolFormatter.enforceTokenBudget(json)
        // Innermost container is an array → marker is a bare string value, not a key-value pair
        result shouldContain """"_truncated""""
        kotlinx.serialization.json.Json.parseToJsonElement(result) // must not throw
    }

    @Test
    fun `enforceTokenBudget truncation after opening brace produces valid JSON`() {
        // head = {"a":{"b":"<filler>","c":{  — ends with '{' at exactly MAX_TOOL_OUTPUT_CHARS.
        // {"a":{"b":" = 11 chars, ","c":{ = 7 chars → filler = MAX_TOOL_OUTPUT_CHARS - 18.
        val head = "{\"a\":{\"b\":\"" + "x".repeat(MAX_TOOL_OUTPUT_CHARS - 18) + "\",\"c\":{"
        val json = head + "1}}" // total > MAX_TOOL_OUTPUT_CHARS; cut falls after '{'
        head.length shouldBe MAX_TOOL_OUTPUT_CHARS
        val result = SymbolFormatter.enforceTokenBudget(json)
        result shouldContain """"_truncated":true"""
        kotlinx.serialization.json.Json.parseToJsonElement(result) // must not throw
    }

   @Test
   fun `enforceTokenBudget truncation after opening bracket produces valid JSON`() {
        // head = {"a":["<filler>"],"b":[  — ends with '[' at exactly MAX_TOOL_OUTPUT_CHARS.
        // {"a":[" = 7 chars, "],"b":[ = 8 chars → filler = MAX_TOOL_OUTPUT_CHARS - 15.
        val head = "{\"a\":[\"" + "x".repeat(MAX_TOOL_OUTPUT_CHARS - 15) + "\"],\"b\":["
        val json = head + "1]}" // total > MAX_TOOL_OUTPUT_CHARS; cut falls after '['
        head.length shouldBe MAX_TOOL_OUTPUT_CHARS
        val result = SymbolFormatter.enforceTokenBudget(json)
        // Innermost container is an array → marker is a bare string value, not a key-value pair
        result shouldContain """"_truncated""""
        kotlinx.serialization.json.Json.parseToJsonElement(result) // must not throw
    }
}
